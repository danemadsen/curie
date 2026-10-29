package com.danemadsen.curie.model

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The downloader against a local HTTP server: plain JVM, real sockets, real files. */
class ModelDownloaderTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val client = OkHttpClient()
    private val first = Random(1).nextBytes(150_000)
    private val second = Random(2).nextBytes(70_000)
    private val files = listOf(remoteFileOf("onnx/first.onnx_data", first), remoteFileOf("second.json", second))
    private val contents = mapOf("onnx/first.onnx_data" to first, "second.json" to second)
    private val fileServer = FileServer(contents)
    private lateinit var directory: File

    @Before
    fun setUp() {
        server.dispatcher = fileServer
        server.start()
        directory = File(folder.root, "model")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun downloader(
        storage: StorageSpace = FakeStorage(Long.MAX_VALUE),
        urlServer: MockWebServer = server,
        openPart: (File) -> OutputStream = { part -> FileOutputStream(part, true) },
    ) = ModelDownloader(
        client = client,
        directory = directory,
        files = files,
        storage = storage,
        urlOf = { file -> urlServer.url("/" + file.path).toString() },
        retryDelayMs = 1,
        openPart = openPart,
    )

    private fun download(downloader: ModelDownloader = downloader()): List<DownloadProgress> = runBlocking {
        val reports = mutableListOf<DownloadProgress>()
        downloader.download { progress -> reports.add(progress) }
        reports
    }

    private fun assertDownloaded() {
        assertContentEquals(first, File(directory, "first.onnx_data").readBytes())
        assertContentEquals(second, File(directory, "second.json").readBytes())
        assertFalse(File(directory, "first.onnx_data.part").exists())
        assertTrue(ModelDownloader.isComplete(directory, files))
    }

    @Test
    fun downloadsEveryFileFlatAndVerified() {
        val reports = download()

        assertDownloaded()
        assertEquals(listOf("/onnx/first.onnx_data", "/second.json"), fileServer.requests.map { request -> request.path })
        assertTrue(fileServer.requests.all { request -> request.getHeader("Range") == null })
        assertTrue(fileServer.requests.all { request -> request.getHeader("Accept-Encoding") == "identity" })

        val doneBytes = reports.map { progress -> progress.doneBytes }
        assertEquals(doneBytes.sorted(), doneBytes)
        assertEquals(DownloadProgress(DownloadPhase.DOWNLOADING, 220_000, 220_000, "second.json"), reports.last())
        assertTrue(reports.none { progress -> progress.phase == DownloadPhase.VERIFYING })
        assertEquals(220_000, ModelDownloader.bytesOnDisk(directory, files))
    }

    @Test
    fun verifiedFilesAreSkippedWithoutHashing() {
        download()
        val requestsBefore = fileServer.requests.size

        val reports = download()

        assertEquals(requestsBefore, fileServer.requests.size)
        assertTrue(reports.isEmpty())
        assertDownloaded()
    }

    @Test
    fun aLostVerificationRecordMeansHashingNotDownloading() {
        download()
        File(directory, VerificationCache.FILE_NAME).delete()
        val requestsBefore = fileServer.requests.size

        val reports = download()

        assertEquals(requestsBefore, fileServer.requests.size)
        assertEquals(listOf(DownloadPhase.VERIFYING, DownloadPhase.VERIFYING), reports.map { progress -> progress.phase })
        assertTrue(File(directory, VerificationCache.FILE_NAME).exists())
        assertDownloaded()
    }

    @Test
    fun aFileChangedAfterItsVerificationIsFetchedAgain() {
        download()
        val damaged = File(directory, "second.json")
        damaged.writeBytes(ByteArray(second.size))
        damaged.setLastModified(damaged.lastModified() - 10_000)

        download()

        assertEquals("/second.json", fileServer.requests.last().path)
        assertDownloaded()
    }

    @Test
    fun aDroppedConnectionResumesWithARangeRequest() {
        fileServer.disconnectMidBody.add("/onnx/first.onnx_data")

        download()

        assertDownloaded()
        val firstFileRequests = fileServer.requests.filter { request -> request.path == "/onnx/first.onnx_data" }
        assertEquals(2, firstFileRequests.size)
        assertNull(firstFileRequests[0].getHeader("Range"))
        val resumedFrom = firstFileRequests[1].getHeader("Range").orEmpty()
        assertTrue(resumedFrom.matches(Regex("bytes=[1-9][0-9]*-")), "resumed with $resumedFrom")
    }

    @Test
    fun aPartFromAnEarlierRunIsHashedAndResumed() {
        directory.mkdirs()
        File(directory, "first.onnx_data.part").writeBytes(first.copyOfRange(0, 100_000))

        val reports = download()

        assertDownloaded()
        assertEquals(DownloadPhase.VERIFYING, reports.first().phase)
        assertEquals("bytes=100000-", fileServer.requests.first().getHeader("Range"))
    }

    @Test
    fun aServerThatIgnoresTheRangeRestartsTheFile() {
        fileServer.ignoreRanges = true
        fileServer.disconnectMidBody.add("/onnx/first.onnx_data")

        download()

        assertDownloaded()
        val retried = fileServer.requests.filter { request -> request.path == "/onnx/first.onnx_data" }.last()
        assertTrue(retried.getHeader("Range").orEmpty().startsWith("bytes="))
    }

    @Test
    fun aRangeThatStartsElsewhereRestartsTheFile() {
        directory.mkdirs()
        File(directory, "first.onnx_data.part").writeBytes(first.copyOfRange(0, 1_000))
        fileServer.wrongRangeStartOnce = true

        download()

        assertDownloaded()
        val firstFileRequests = fileServer.requests.filter { request -> request.path == "/onnx/first.onnx_data" }
        assertEquals(listOf("bytes=1000-", null), firstFileRequests.map { request -> request.getHeader("Range") })
    }

    @Test
    fun redirectsAreFollowedWithTheRangeAndNoCredentials() {
        val cdn = MockWebServer()
        val cdnFiles = FileServer(contents)
        cdnFiles.disconnectMidBody.add("/blobs/onnx/first.onnx_data")
        cdn.dispatcher = cdnFiles
        cdn.start()
        fileServer.redirectTo = { path -> cdn.url("/blobs$path").toString() }

        try {
            download()
        } finally {
            cdn.shutdown()
        }

        assertDownloaded()
        val resumed = cdnFiles.requests.filter { request -> request.path == "/blobs/onnx/first.onnx_data" }
        assertEquals(2, resumed.size)
        assertTrue(resumed[1].getHeader("Range").orEmpty().startsWith("bytes="))
        val everyRequest = fileServer.requests + cdnFiles.requests
        assertTrue(everyRequest.all { request -> request.getHeader("Authorization") == null })
    }

    @Test
    fun aHashMismatchDeletesThePart() {
        fileServer.corrupt.add("/onnx/first.onnx_data")

        val error = assertFailsWith<ModelDownloadException.Corrupt> { download() }

        assertEquals("first.onnx_data", error.fileName)
        assertEquals(ModelFailure.CORRUPT_DOWNLOAD, error.failure)
        assertFalse(File(directory, "first.onnx_data").exists())
        assertFalse(File(directory, "first.onnx_data.part").exists())
        assertFalse(ModelDownloader.isComplete(directory, files))
    }

    @Test
    fun aDamagedPartFailsTheHashAndTheNextRunStartsOver() {
        directory.mkdirs()
        File(directory, "first.onnx_data.part").writeBytes(ByteArray(50_000))

        assertFailsWith<ModelDownloadException.Corrupt> { download() }
        assertFalse(File(directory, "first.onnx_data.part").exists())

        download()
        assertDownloaded()
    }

    @Test
    fun aBodyOfAnotherLengthIsRejected() {
        fileServer.extraBytes["/second.json"] = 10

        val declared = assertFailsWith<ModelDownloadException.UnexpectedResponse> { download() }
        assertEquals(ModelFailure.SERVER, declared.failure)

        fileServer.chunked = true
        assertFailsWith<ModelDownloadException.Corrupt> { download() }
        assertFalse(File(directory, "second.json.part").exists())
    }

    @Test
    fun transientStatusesAreRetriedThenFail() {
        fileServer.status["/onnx/first.onnx_data"] = 503

        val error = assertFailsWith<ModelDownloadException.Http> { download() }

        assertEquals(503, error.code)
        assertTrue(error.isTransient)
        assertEquals(ModelDownloader.MAX_ATTEMPTS, fileServer.requests.size)
    }

    @Test
    fun aMissingFileFailsWithoutRetries() {
        fileServer.status["/second.json"] = 404

        val error = assertFailsWith<ModelDownloadException.Http> { download() }

        assertEquals(404, error.code)
        assertFalse(error.isTransient)
        assertEquals(1, fileServer.requests.count { request -> request.path == "/second.json" })
        assertContentEquals(first, File(directory, "first.onnx_data").readBytes())
    }

    @Test
    fun anUnreachableServerIsANetworkFailure() {
        val unreachable = MockWebServer()
        unreachable.start()
        unreachable.shutdown()

        val error = assertFailsWith<ModelDownloadException.Network> { download(downloader(urlServer = unreachable)) }

        assertTrue(error.isTransient)
        assertEquals(ModelFailure.NETWORK, error.failure)
    }

    @Test
    fun notEnoughSpaceFailsBeforeAnyRequest() {
        val error = assertFailsWith<ModelDownloadException.InsufficientStorage> {
            download(downloader(FakeStorage(1_000_000)))
        }

        assertEquals(220_000 + ModelDownloader.SPACE_MARGIN_BYTES, error.requiredBytes)
        assertEquals(1_000_000, error.availableBytes)
        assertTrue(fileServer.requests.isEmpty())
    }

    @Test
    fun theSpaceIsAllocatedBeforeDownloading() {
        val storage = FakeStorage(Long.MAX_VALUE)

        download(downloader(storage))

        assertEquals(listOf(220_000 + ModelDownloader.SPACE_MARGIN_BYTES), storage.allocations)
        assertDownloaded()
    }

    @Test
    fun spaceThatCannotBeClearedIsInsufficient() {
        val storage = FakeStorage(Long.MAX_VALUE, canAllocate = false)

        assertFailsWith<ModelDownloadException.InsufficientStorage> { download(downloader(storage)) }

        assertTrue(fileServer.requests.isEmpty())
    }

    @Test
    fun onlyTheMissingBytesNeedSpace() {
        download()
        File(directory, "second.json").delete()

        val error = assertFailsWith<ModelDownloadException.InsufficientStorage> {
            download(downloader(FakeStorage(0)))
        }

        assertEquals(70_000 + ModelDownloader.SPACE_MARGIN_BYTES, error.requiredBytes)
    }

    @Test
    fun aDiskThatFillsUpMidwayIsInsufficientStorageAndThePartIsCutBack() {
        val storage = FakeStorage(Long.MAX_VALUE)
        val openFillingPart = { part: File ->
            FullDiskOutputStream(part, capacity = 100_000, onFull = { storage.bytes = 0 })
        }
        val fullDisk = downloader(storage, openPart = openFillingPart)

        val error = assertFailsWith<ModelDownloadException.InsufficientStorage> { download(fullDisk) }

        // The write that failed stored part of its chunk: the part keeps only what was hashed
        val part = File(directory, "first.onnx_data.part")
        val kept = part.length()
        assertTrue(kept in 1 until 100_000, "kept $kept bytes")
        assertContentEquals(first.copyOfRange(0, kept.toInt()), part.readBytes())
        assertEquals(220_000 - kept + ModelDownloader.SPACE_MARGIN_BYTES, error.requiredBytes)
        assertEquals(0, error.availableBytes)

        // With room again, the next run resumes from the kept bytes
        storage.bytes = Long.MAX_VALUE
        download(downloader(storage))
        assertDownloaded()
        val resumed = fileServer.requests.last { request -> request.path == "/onnx/first.onnx_data" }
        assertEquals("bytes=$kept-", resumed.getHeader("Range"))
    }

    @Test
    fun aWriteThatFailsWithSpaceLeftIsAStorageFailure() {
        val openFailingPart = { part: File -> FullDiskOutputStream(part, capacity = 100_000, onFull = {}) }
        val failingWrites = downloader(openPart = openFailingPart)

        val error = assertFailsWith<ModelDownloadException.Storage> { download(failingWrites) }

        assertEquals(ModelFailure.STORAGE, error.failure)
        assertFalse(error.isTransient)
        val part = File(directory, "first.onnx_data.part")
        assertContentEquals(first.copyOfRange(0, part.length().toInt()), part.readBytes())
    }

    @Test
    fun cancellingStopsPromptlyAndTheNextRunResumes() = runBlocking {
        fileServer.throttle = true
        val downloader = downloader()
        val started = CompletableDeferred<Unit>()

        val job = launch {
            downloader.download { progress ->
                if (progress.doneBytes > 10_000) {
                    started.complete(Unit)
                }
            }
        }
        withTimeout(10_000) { started.await() }
        withTimeout(1_000) {
            job.cancel()
            job.join()
        }

        assertTrue(job.isCancelled)
        val part = File(directory, "first.onnx_data.part")
        val keptBytes = part.length()
        assertTrue(keptBytes in 10_001 until first.size, "kept $keptBytes bytes")
        assertContentEquals(first.copyOfRange(0, keptBytes.toInt()), part.readBytes())

        fileServer.throttle = false
        downloader.download()
        assertDownloaded()
        val resumed = fileServer.requests.first { request -> request.getHeader("Range") != null }
        assertEquals("bytes=$keptBytes-", resumed.getHeader("Range"))
    }

    /**
     * Serves [contents] by path with byte ranges, and misbehaves on request: drops the connection
     * half way, ignores ranges, answers a range from the wrong start, corrupts, pads or refuses.
     */
    private class FileServer(private val contents: Map<String, ByteArray>) : Dispatcher() {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val disconnectMidBody = mutableSetOf<String>()
        val corrupt = mutableSetOf<String>()
        val status = mutableMapOf<String, Int>()
        val extraBytes = mutableMapOf<String, Int>()

        @Volatile
        var ignoreRanges = false

        @Volatile
        var wrongRangeStartOnce = false

        @Volatile
        var chunked = false

        @Volatile
        var throttle = false

        @Volatile
        var redirectTo: ((String) -> String)? = null

        override fun dispatch(request: RecordedRequest): MockResponse {
            requests.add(request)
            val path = request.path.orEmpty()

            val redirect = redirectTo
            if (redirect != null) {
                val response = MockResponse()
                response.setResponseCode(302)
                response.setHeader("Location", redirect(path))
                return response
            }

            val name = path.substringAfter("/blobs").removePrefix("/")
            val content = contents[name] ?: return MockResponse().setResponseCode(404)
            status[path]?.let { code -> return MockResponse().setResponseCode(code) }

            var body = content
            if (path in corrupt) {
                body = content.copyOf()
                body[0] = (body[0] + 1).toByte()
            }
            val padding = extraBytes[path] ?: 0
            if (padding > 0) {
                body = body + ByteArray(padding)
            }

            val range = request.getHeader("Range")
            val start = range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
            val response = MockResponse()
            if (range != null && !ignoreRanges) {
                val reportedStart = if (wrongRangeStartOnce) start + 1 else start
                wrongRangeStartOnce = false
                response.setResponseCode(206)
                response.setHeader("Content-Range", "bytes $reportedStart-${body.size - 1}/${body.size}")
                body = body.copyOfRange(start, body.size)
            } else {
                response.setResponseCode(200)
            }

            val buffer = Buffer()
            buffer.write(body)
            if (chunked) {
                response.setChunkedBody(buffer, 4_096)
            } else {
                response.setBody(buffer)
            }

            if (disconnectMidBody.remove(path)) {
                response.setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
            }
            // 12 KB at once, then a stall a blocked read can only leave by being cancelled
            if (throttle) {
                response.throttleBody(12_000, 3, TimeUnit.SECONDS)
            }
            return response
        }
    }
}
