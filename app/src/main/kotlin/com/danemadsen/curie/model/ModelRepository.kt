package com.danemadsen.curie.model

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import com.danemadsen.curie.data.SettingsRepository
import com.danemadsen.curie.engine.ModelFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The model's files: downloads them with WorkManager ([ModelDownloadWorker], unique work
 * "model-download") and installs the bundled LoRA assets next to them. [state] is derived from
 * the files on disk and the work's WorkInfo, so it survives process death.
 *
 * Keep one instance per process, created with an application-wide [scope].
 */
class ModelRepository internal constructor(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope,
    private val workManager: WorkManager,
) {
    constructor(context: Context, settingsRepository: SettingsRepository, scope: CoroutineScope) : this(
        context = context.applicationContext,
        settingsRepository = settingsRepository,
        scope = scope,
        workManager = WorkManager.getInstance(context.applicationContext),
    )

    /** filesDir/model: the downloads and the installed LoRA assets, side by side. */
    val modelDirectory: File = modelDirectoryOf(context)

    private val installer by lazy { ModelInstaller(context.assets, modelDirectory, installStampOf(context)) }
    private val installMutex = Mutex()
    private val installFailed = MutableStateFlow(false)

    /** Bumped when the files change outside the download work (an install, a deletion). */
    private val diskChanges = MutableStateFlow(0)

    /** Starts as [ModelState.Verifying] until the files have been looked at (off the main thread). */
    val state: StateFlow<ModelState> = observeState()

    init {
        followNetworkPreference()
    }

    /** Starts (or resumes) the download; a download already queued or running is kept. */
    fun startDownload() {
        scope.launch {
            val settings = settingsRepository.settings.first()
            enqueue(settings.downloadOnWifiOnly, ExistingWorkPolicy.KEEP)
        }
    }

    /** Stops the download; the partial file stays, so a later start resumes it. */
    fun cancelDownload() {
        workManager.cancelUniqueWork(WORK_NAME)
    }

    /**
     * Cancels the download and deletes every model file, partial ones included. Close the engine
     * first: it maps these files. Returns false when some file could not be deleted.
     */
    suspend fun deleteModel(): Boolean = installMutex.withLock {
        val cancellation = workManager.cancelUniqueWork(WORK_NAME)
        cancellation.await()

        // A cancelled download may still be unwinding: wait until it lets go of the files
        val deleted = ModelDownloader.lock.withLock {
            withContext(Dispatchers.IO) { modelDirectory.deleteRecursively() }
        }

        installFailed.value = false
        diskChanges.update { count -> count + 1 }
        deleted
    }

    /**
     * True when the model can be loaded from [modelDirectory]: the downloads are complete and
     * verified, and the bundled LoRA assets are installed next to them (copied now if needed).
     */
    suspend fun ensureReady(): Boolean = installMutex.withLock {
        val complete = withContext(Dispatchers.IO) { ModelDownloader.isComplete(modelDirectory) }
        if (!complete) {
            return@withLock false
        }

        val installed = try {
            installer.install()
            true
        } catch (error: IOException) {
            false
        }
        installFailed.value = !installed
        diskChanges.update { count -> count + 1 }
        installed
    }

    private fun observeState(): StateFlow<ModelState> {
        val work = workManager.getWorkInfosForUniqueWorkFlow(WORK_NAME)
        val states = combine(work, installFailed, diskChanges) { infos, failed, _ -> stateOf(infos, failed) }
        val statesOffMainThread = states.flowOn(Dispatchers.IO)
        return statesOffMainThread.stateIn(scope, SharingStarted.Eagerly, ModelState.Verifying)
    }

    private fun stateOf(infos: List<WorkInfo>, installFailed: Boolean): ModelState {
        // A unique work keeps one record: enqueuing again deletes the finished one
        val work = infos.firstOrNull { info -> !info.state.isFinished } ?: infos.lastOrNull()
        val complete = ModelDownloader.isComplete(modelDirectory)
        val bytesOnDisk = if (complete) ModelFiles.DOWNLOAD_SIZE else ModelDownloader.bytesOnDisk(modelDirectory)
        return modelStateOf(work, complete, bytesOnDisk, installFailed)
    }

    /** Applies a change of the Wi-Fi-only setting to a download that is queued or running. */
    private fun followNetworkPreference() {
        scope.launch {
            val wifiOnly = settingsRepository.settings.map { settings -> settings.downloadOnWifiOnly }
            val changes = wifiOnly.distinctUntilChanged().drop(1)
            changes.collect { onlyWifi -> requeuePendingDownload(onlyWifi) }
        }
    }

    private suspend fun requeuePendingDownload(wifiOnly: Boolean) {
        val infos = workManager.getWorkInfosForUniqueWorkFlow(WORK_NAME).first()
        val pending = infos.any { info -> !info.state.isFinished }
        if (pending) {
            // A running download is stopped and resumed by its successor under the new constraint
            enqueue(wifiOnly, ExistingWorkPolicy.REPLACE)
        }
    }

    private fun enqueue(wifiOnly: Boolean, policy: ExistingWorkPolicy) {
        val networkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        val constraintsBuilder = Constraints.Builder()
        constraintsBuilder.setRequiredNetworkType(networkType)
        val constraints = constraintsBuilder.build()

        val requestBuilder = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
        requestBuilder.setConstraints(constraints)
        requestBuilder.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETRY_BACKOFF_SECONDS, TimeUnit.SECONDS)
        val request = requestBuilder.build()

        workManager.enqueueUniqueWork(WORK_NAME, policy, request)
    }

    companion object {
        const val WORK_NAME = "model-download"
        const val DIRECTORY_NAME = "model"
        private const val RETRY_BACKOFF_SECONDS = 30L

        fun modelDirectoryOf(context: Context): File = File(context.filesDir, DIRECTORY_NAME)

        /** Changes with every install or update of the app, so the bundled assets are copied again. */
        private fun installStampOf(context: Context): String {
            val packageInfo = packageInfoOf(context)
            val versionCode = PackageInfoCompat.getLongVersionCode(packageInfo)
            return "$versionCode:${packageInfo.lastUpdateTime}"
        }

        private fun packageInfoOf(context: Context): PackageInfo {
            val packageManager = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val flags = PackageManager.PackageInfoFlags.of(0)
                return packageManager.getPackageInfo(context.packageName, flags)
            }
            @Suppress("DEPRECATION")
            return packageManager.getPackageInfo(context.packageName, 0)
        }
    }
}

/**
 * The state for the download [work] (the unique work's current record, if any) and the files: an
 * unfinished work decides; otherwise complete files are [ModelState.Ready] whatever an old record
 * says, and a failed work explains why the files are not.
 */
internal fun modelStateOf(
    work: WorkInfo?,
    downloadsComplete: Boolean,
    bytesOnDisk: Long,
    installFailed: Boolean,
): ModelState {
    val totalBytes = ModelFiles.DOWNLOAD_SIZE

    if (work != null && !work.state.isFinished) {
        if (work.state != WorkInfo.State.RUNNING) {
            return ModelState.Downloading(bytesOnDisk, totalBytes, fileName = null, isQueued = true)
        }

        val progress = work.progress
        if (progress.getString(ModelDownloadWorker.KEY_PHASE) == DownloadPhase.VERIFYING.name) {
            return ModelState.Verifying
        }
        val doneBytes = progress.getLong(ModelDownloadWorker.KEY_DONE_BYTES, bytesOnDisk)
        val fileName = progress.getString(ModelDownloadWorker.KEY_FILE_NAME)
        return ModelState.Downloading(doneBytes, totalBytes, fileName)
    }

    if (downloadsComplete) {
        return if (installFailed) ModelState.Failed(ModelFailure.INSTALL) else ModelState.Ready
    }
    if (work?.state == WorkInfo.State.FAILED) {
        return ModelDownloadWorker.failureOf(work.outputData)
    }
    return ModelState.NotDownloaded
}
