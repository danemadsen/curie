package com.danemadsen.curie.model

import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.workDataOf
import com.danemadsen.curie.engine.ModelFiles
import org.junit.Test
import java.util.UUID
import kotlin.test.assertEquals

class ModelStateTest {
    private val total = ModelFiles.DOWNLOAD_SIZE

    private fun work(state: WorkInfo.State, progress: Data = Data.EMPTY, output: Data = Data.EMPTY): WorkInfo =
        WorkInfo(UUID.randomUUID(), state, emptySet(), output, progress)

    private fun progressData(phase: DownloadPhase, doneBytes: Long, fileName: String) = workDataOf(
        ModelDownloadWorker.KEY_PHASE to phase.name,
        ModelDownloadWorker.KEY_DONE_BYTES to doneBytes,
        ModelDownloadWorker.KEY_TOTAL_BYTES to total,
        ModelDownloadWorker.KEY_FILE_NAME to fileName,
    )

    @Test
    fun withoutWorkTheFilesDecide() {
        assertEquals(ModelState.NotDownloaded, modelStateOf(null, false, 0, false))
        assertEquals(ModelState.NotDownloaded, modelStateOf(null, false, 1_000, false))
        assertEquals(ModelState.Ready, modelStateOf(null, true, total, false))
        assertEquals(ModelState.Failed(ModelFailure.INSTALL), modelStateOf(null, true, total, true))
    }

    @Test
    fun waitingWorkIsAQueuedDownloadOfTheBytesOnDisk() {
        val queued = ModelState.Downloading(500, total, fileName = null, isQueued = true)

        assertEquals(queued, modelStateOf(work(WorkInfo.State.ENQUEUED), false, 500, false))
        assertEquals(queued, modelStateOf(work(WorkInfo.State.BLOCKED), false, 500, false))
    }

    @Test
    fun runningWorkReportsItsProgress() {
        val downloading = work(WorkInfo.State.RUNNING, progressData(DownloadPhase.DOWNLOADING, 1_234, "tokenizer.json"))
        val verifying = work(WorkInfo.State.RUNNING, progressData(DownloadPhase.VERIFYING, 0, "tokenizer.json"))
        val starting = work(WorkInfo.State.RUNNING)

        assertEquals(ModelState.Downloading(1_234, total, "tokenizer.json"), modelStateOf(downloading, false, 0, false))
        assertEquals(ModelState.Verifying, modelStateOf(verifying, false, 0, false))
        assertEquals(ModelState.Downloading(77, total, null), modelStateOf(starting, false, 77, false))
    }

    @Test
    fun finishedWorkYieldsToTheFiles() {
        val succeeded = work(WorkInfo.State.SUCCEEDED)
        val cancelled = work(WorkInfo.State.CANCELLED)
        val failed = work(WorkInfo.State.FAILED)

        assertEquals(ModelState.Ready, modelStateOf(succeeded, true, total, false))
        assertEquals(ModelState.NotDownloaded, modelStateOf(succeeded, false, 0, false))
        assertEquals(ModelState.NotDownloaded, modelStateOf(cancelled, false, 10, false))
        assertEquals(ModelState.Ready, modelStateOf(failed, true, total, false))
    }

    @Test
    fun aFailedWorkExplainsWhy() {
        val noSpace = workDataOf(
            ModelDownloadWorker.KEY_FAILURE to ModelFailure.INSUFFICIENT_STORAGE.name,
            ModelDownloadWorker.KEY_REQUIRED_BYTES to 42L,
        )
        val unknown = workDataOf(ModelDownloadWorker.KEY_FAILURE to "SOMETHING_NEW")

        val withReason = work(WorkInfo.State.FAILED, output = noSpace)
        val withUnknownReason = work(WorkInfo.State.FAILED, output = unknown)
        val withoutReason = work(WorkInfo.State.FAILED)

        assertEquals(ModelState.Failed(ModelFailure.INSUFFICIENT_STORAGE, 42), modelStateOf(withReason, false, 0, false))
        assertEquals(ModelState.Failed(ModelFailure.UNKNOWN), modelStateOf(withUnknownReason, false, 0, false))
        assertEquals(ModelState.Failed(ModelFailure.UNKNOWN), modelStateOf(withoutReason, false, 0, false))
    }

    @Test
    fun onlyTransientDownloadFailuresAreRetried() {
        val network = ModelDownloadException.Network("a", null)
        val busy = ModelDownloadException.Http("a", 503)
        val missing = ModelDownloadException.Http("a", 404)
        val corrupt = ModelDownloadException.Corrupt("a", "hash")
        val full = ModelDownloadException.InsufficientStorage(10, 1)

        val errors = listOf(network, busy, missing, corrupt, full)
        val transient = errors.map { error -> error.isTransient }

        assertEquals(listOf(true, true, false, false, false), transient)
        assertEquals(ModelFailure.SERVER, missing.failure)
        assertEquals(ModelFailure.INSUFFICIENT_STORAGE, full.failure)
    }
}
