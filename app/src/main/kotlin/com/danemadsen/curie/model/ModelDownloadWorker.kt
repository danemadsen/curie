package com.danemadsen.curie.model

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Runs [ModelDownloader] as a foreground service (type dataSync) with a progress notification,
 * publishing progress through [setProgress] for [ModelRepository]. A transient failure is retried
 * by WorkManager (with backoff) for as long as the download moves, and ends the work after
 * [MAX_FAILED_RUNS] runs in a row that received no byte; any other failure ends it at once. A
 * failed work records why in its output data.
 */
class ModelDownloadWorker internal constructor(
    context: Context,
    parameters: WorkerParameters,
    private val createDownloader: (directory: File) -> ModelDownloader,
) : CoroutineWorker(context, parameters) {
    /** The constructor WorkManager calls. */
    constructor(context: Context, parameters: WorkerParameters) : this(
        context = context,
        parameters = parameters,
        createDownloader = { directory -> createAndroidDownloader(context.applicationContext, directory) },
    )

    private val notifications = ModelDownloadNotifications(applicationContext)
    private var canRunInForeground = true
    private var lastReportMs = 0L
    private var lastReported: DownloadProgress? = null

    override suspend fun doWork(): Result {
        notifications.createChannel()
        runInForeground(progress = null)

        val directory = ModelRepository.modelDirectoryOf(applicationContext)
        // A new work (the user started the download) starts with the whole budget
        val previousFailedRuns = if (runAttemptCount == 0) 0 else readFailedRuns(directory)
        val downloader = createDownloader(directory)

        var receivedBytes = false
        return try {
            downloader.download { progress ->
                if (progress.phase == DownloadPhase.DOWNLOADING) {
                    receivedBytes = true
                }
                report(progress)
            }
            writeFailedRuns(directory, failedRuns = 0)
            Result.success()
        } catch (error: ModelDownloadException) {
            resultOf(error, directory, previousFailedRuns, receivedBytes)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(progress = null)

    /** Publishes [progress] at most once a second, and whenever the phase or the file changes. */
    private suspend fun report(progress: DownloadProgress) {
        val now = SystemClock.elapsedRealtime()
        val previous = lastReported
        val stepChanged = previous == null || previous.phase != progress.phase || previous.fileName != progress.fileName
        if (!stepChanged && now - lastReportMs < REPORT_INTERVAL_MS) {
            return
        }
        lastReportMs = now
        lastReported = progress

        val data = workDataOf(
            KEY_PHASE to progress.phase.name,
            KEY_DONE_BYTES to progress.doneBytes,
            KEY_TOTAL_BYTES to progress.totalBytes,
            KEY_FILE_NAME to progress.fileName,
        )
        setProgress(data)
        runInForeground(progress)
    }

    /** Shows (or updates) the foreground notification. */
    private suspend fun runInForeground(progress: DownloadProgress?) {
        if (!canRunInForeground) {
            return
        }
        try {
            setForeground(foregroundInfo(progress))
        } catch (error: IllegalStateException) {
            // Android 12+ refuses a foreground service started from the background (a retry that
            // ran while the app was closed): the download continues as ordinary background work
            canRunInForeground = false
        }
    }

    private fun foregroundInfo(progress: DownloadProgress?): ForegroundInfo {
        val workManager = WorkManager.getInstance(applicationContext)
        val cancelIntent = workManager.createCancelPendingIntent(id)
        val notification = notifications.build(progress, cancelIntent)

        // minSdk is 29, where the typed constructor and the dataSync type exist; Android 14+
        // requires the type to match the service's manifest declaration
        return ForegroundInfo(
            ModelDownloadNotifications.NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private suspend fun resultOf(
        error: ModelDownloadException,
        directory: File,
        previousFailedRuns: Int,
        receivedBytes: Boolean,
    ): Result {
        if (error.isTransient) {
            // A run that received bytes moved the download on, however it ended
            val failedRuns = if (receivedBytes) 0 else previousFailedRuns + 1
            if (failedRuns < MAX_FAILED_RUNS) {
                writeFailedRuns(directory, failedRuns)
                return Result.retry()
            }
        }
        writeFailedRuns(directory, failedRuns = 0)

        val requiredBytes = (error as? ModelDownloadException.InsufficientStorage)?.requiredBytes ?: 0L
        val output = workDataOf(
            KEY_FAILURE to error.failure.name,
            KEY_REQUIRED_BYTES to requiredBytes,
        )
        return Result.failure(output)
    }

    internal companion object {
        const val KEY_PHASE = "phase"
        const val KEY_DONE_BYTES = "doneBytes"
        const val KEY_TOTAL_BYTES = "totalBytes"
        const val KEY_FILE_NAME = "fileName"
        const val KEY_FAILURE = "failure"
        const val KEY_REQUIRED_BYTES = "requiredBytes"

        /**
         * Runs in a row that fail transiently without receiving a byte before the failure sticks:
         * with ModelRepository's exponential backoff from 30 s, at least 7.5 minutes of a server
         * or network that sends nothing.
         */
        const val MAX_FAILED_RUNS = 5

        /**
         * Where the failed runs are counted, next to the files. WorkManager's run attempt count
         * cannot be used: it also counts the runs it stopped itself (a lost network, Doze, a
         * time limit, process death).
         */
        const val FAILED_RUNS_FILE_NAME = ".download-failed-runs"

        private const val REPORT_INTERVAL_MS = 1_000L
        private const val CONNECT_TIMEOUT_SECONDS = 30L
        private const val READ_TIMEOUT_SECONDS = 60L

        /** The failure a finished work recorded; [ModelFailure.UNKNOWN] when it recorded none. */
        fun failureOf(output: Data): ModelState.Failed {
            val name = output.getString(KEY_FAILURE)
            val reason = ModelFailure.entries.firstOrNull { failure -> failure.name == name } ?: ModelFailure.UNKNOWN
            val requiredBytes = output.getLong(KEY_REQUIRED_BYTES, 0L)
            return ModelState.Failed(reason, requiredBytes)
        }

        private fun createAndroidDownloader(context: Context, directory: File): ModelDownloader {
            val builder = OkHttpClient.Builder()
            builder.connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // A stalled transfer fails (and resumes) instead of hanging
            builder.readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val client = builder.build()

            val storage = AndroidStorageSpace(context)
            return ModelDownloader(client = client, directory = directory, storage = storage)
        }

        /** No record (a first run, or a model deleted since) is no failed run. */
        private fun readFailedRuns(directory: File): Int {
            val record = File(directory, FAILED_RUNS_FILE_NAME)
            return try {
                val text = record.readText()
                text.trim().toIntOrNull() ?: 0
            } catch (error: IOException) {
                0
            }
        }

        /**
         * Records [failedRuns] (0 removes the record). Holds the download lock, so a deletion of
         * the model, which holds it too, cannot leave the record behind in an emptied directory.
         */
        private suspend fun writeFailedRuns(directory: File, failedRuns: Int) {
            ModelDownloader.lock.withLock {
                val record = File(directory, FAILED_RUNS_FILE_NAME)
                if (failedRuns == 0) {
                    record.delete()
                    return@withLock
                }
                try {
                    record.writeText(failedRuns.toString())
                } catch (error: IOException) {
                    // A lost record only means a fuller budget for the next run
                }
            }
        }
    }
}
