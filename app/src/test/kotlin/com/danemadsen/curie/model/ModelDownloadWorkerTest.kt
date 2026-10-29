package com.danemadsen.curie.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestForegroundUpdater
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.TestProgressUpdater
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The worker's decisions, with a real downloader against a local server. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ModelDownloadWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()
    private val client = OkHttpClient()
    private val weights = Random(3).nextBytes(100_000)
    private val tokenizer = Random(4).nextBytes(30_000)
    private val files = listOf(remoteFileOf("onnx/weights.onnx_data", weights), remoteFileOf("tokenizer.json", tokenizer))
    private val fileServer = WeightsServer(mapOf("/onnx/weights.onnx_data" to weights, "/tokenizer.json" to tokenizer))
    private val directory = ModelRepository.modelDirectoryOf(context)
    private val failedRunsRecord = File(directory, ModelDownloadWorker.FAILED_RUNS_FILE_NAME)
    private val progressUpdater = RecordingProgressUpdater()
    private var storage = FakeStorage(Long.MAX_VALUE)

    @Before
    fun setUp() {
        val configuration = Configuration.Builder()
        configuration.setExecutor(SynchronousExecutor())
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration.build())

        server.dispatcher = fileServer
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        WorkManagerTestInitHelper.closeWorkDatabase()
        context.filesDir.deleteRecursively()
    }

    private fun run(
        runAttemptCount: Int,
        foregroundUpdater: TestForegroundUpdater = TestForegroundUpdater(),
    ): ListenableWorker.Result {
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker = ModelDownloadWorker(appContext, workerParameters) { directory -> downloader(directory) }
        }

        val builder = TestListenableWorkerBuilder.from(context, ModelDownloadWorker::class.java)
        builder.setWorkerFactory(factory)
        builder.setRunAttemptCount(runAttemptCount)
        builder.setProgressUpdater(progressUpdater)
        builder.setForegroundUpdater(foregroundUpdater)
        val worker = builder.build()

        return runBlocking { worker.doWork() }
    }

    private fun downloader(directory: File) = ModelDownloader(
        client = client,
        directory = directory,
        files = files,
        storage = storage,
        urlOf = { file -> server.url("/" + file.path).toString() },
        retryDelayMs = 1,
    )

    private fun failureOf(result: ListenableWorker.Result): ModelState.Failed {
        assertIs<ListenableWorker.Result.Failure>(result)
        return ModelDownloadWorker.failureOf(result.outputData)
    }

    @Test
    fun aCompleteDownloadSucceedsAndReportsEachStepOnce() {
        val result = run(runAttemptCount = 0)

        assertIs<ListenableWorker.Result.Success>(result)
        assertTrue(ModelDownloader.isComplete(directory, files))
        // Robolectric's clock stands still: only a new phase or file is published
        val steps = progressUpdater.reports.map { data -> data.getString(ModelDownloadWorker.KEY_FILE_NAME) }
        assertEquals(listOf("weights.onnx_data", "tokenizer.json"), steps)
        assertFalse(failedRunsRecord.exists())
    }

    @Test
    fun runsThatReceiveNothingFailOnceTheBudgetIsSpentHoweverOftenTheWorkWasStopped() {
        fileServer.status = 503

        // Run attempt counts far past the budget: WorkManager counts the runs it stopped too
        for (failedRuns in 1 until ModelDownloadWorker.MAX_FAILED_RUNS) {
            val runAttemptCount = if (failedRuns == 1) 0 else failedRuns * 10
            assertIs<ListenableWorker.Result.Retry>(run(runAttemptCount))
            assertEquals(failedRuns.toString(), failedRunsRecord.readText())
        }
        val last = run(runAttemptCount = 100)

        assertEquals(ModelState.Failed(ModelFailure.SERVER), failureOf(last))
        assertFalse(failedRunsRecord.exists())
    }

    @Test
    fun aRunThatReceivedBytesRestoresTheWholeBudget() {
        directory.mkdirs()
        val lastChance = ModelDownloadWorker.MAX_FAILED_RUNS - 1
        failedRunsRecord.writeText(lastChance.toString())
        // Half the body, then a server that answers 503
        fileServer.disconnectFirstBody = true
        fileServer.status = 503

        val result = run(runAttemptCount = 9)

        assertIs<ListenableWorker.Result.Retry>(result)
        assertFalse(failedRunsRecord.exists())
        assertTrue(ModelDownloader.bytesOnDisk(directory, files) > 0)
    }

    @Test
    fun aNewWorkStartsWithTheWholeBudget() {
        directory.mkdirs()
        val lastChance = ModelDownloadWorker.MAX_FAILED_RUNS - 1
        failedRunsRecord.writeText(lastChance.toString())
        fileServer.status = 503

        val result = run(runAttemptCount = 0)

        assertIs<ListenableWorker.Result.Retry>(result)
        assertEquals("1", failedRunsRecord.readText())
    }

    @Test
    fun aPermanentFailureEndsTheWorkAtOnce() {
        fileServer.status = 404

        val result = run(runAttemptCount = 0)

        assertEquals(ModelState.Failed(ModelFailure.SERVER), failureOf(result))
        assertEquals(1, fileServer.requests.size)
    }

    @Test
    fun notEnoughSpaceRecordsTheBytesNeeded() {
        storage = FakeStorage(1_000)

        val result = run(runAttemptCount = 0)

        val required = 130_000 + ModelDownloader.SPACE_MARGIN_BYTES
        assertEquals(ModelState.Failed(ModelFailure.INSUFFICIENT_STORAGE, required), failureOf(result))
        assertTrue(fileServer.requests.isEmpty())
    }

    @Test
    fun aRefusedForegroundServiceLeavesABackgroundDownload() {
        val refusing = RefusingForegroundUpdater()

        val result = run(runAttemptCount = 3, foregroundUpdater = refusing)

        assertIs<ListenableWorker.Result.Success>(result)
        assertTrue(ModelDownloader.isComplete(directory, files))
        // Refused once, never asked again
        assertEquals(1, refusing.calls.get())
    }

    private class RecordingProgressUpdater : TestProgressUpdater() {
        val reports = CopyOnWriteArrayList<Data>()

        override fun updateProgress(context: Context, id: UUID, data: Data): ListenableFuture<Void> {
            reports.add(data)
            return super.updateProgress(context, id, data)
        }
    }

    /** Android 12+ refusing a foreground service started from the background. */
    private class RefusingForegroundUpdater : TestForegroundUpdater() {
        val calls = AtomicInteger()

        override fun setForegroundAsync(context: Context, id: UUID, foregroundInfo: ForegroundInfo): ListenableFuture<Void> {
            calls.incrementAndGet()
            throw IllegalStateException("foreground service start not allowed")
        }
    }

    /** Serves whole files by path, or answers every request with [status]. */
    private class WeightsServer(private val contents: Map<String, ByteArray>) : Dispatcher() {
        val requests = CopyOnWriteArrayList<RecordedRequest>()

        @Volatile
        var status: Int? = null

        /** The first response sends half its body, then drops the connection. */
        @Volatile
        var disconnectFirstBody = false

        override fun dispatch(request: RecordedRequest): MockResponse {
            requests.add(request)
            val response = MockResponse()
            val content = contents[request.path] ?: return response.setResponseCode(404)

            if (disconnectFirstBody) {
                disconnectFirstBody = false
                response.setBody(Buffer().write(content))
                response.setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                return response
            }

            val code = status
            if (code != null) {
                return response.setResponseCode(code)
            }
            return response.setBody(Buffer().write(content))
        }
    }
}
