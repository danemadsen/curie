package com.danemadsen.curie.model

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.danemadsen.curie.data.AttachmentLimits
import com.danemadsen.curie.data.AttachmentStore
import com.danemadsen.curie.data.FakeMediaProbe
import com.danemadsen.curie.data.SettingsRepository
import com.danemadsen.curie.engine.ModelFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The repository over a test WorkManager: the work is enqueued but never runs (its network
 * constraint is never met), so nothing is downloaded.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ModelRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var workManager: WorkManager
    private lateinit var settings: SettingsRepository
    private lateinit var repository: ModelRepository

    @Before
    fun setUp() {
        val configuration = Configuration.Builder()
        configuration.setExecutor(SynchronousExecutor())
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration.build())
        workManager = WorkManager.getInstance(context)

        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            File(context.filesDir, "settings-${UUID.randomUUID()}.preferences_pb")
        }
        val profileImages = AttachmentStore(context, File(context.filesDir, "profile"), AttachmentLimits(), FakeMediaProbe())
        settings = SettingsRepository(dataStore, profileImages)
        repository = ModelRepository(context, settings, scope, workManager)
    }

    @After
    fun tearDown() {
        scope.cancel()
        WorkManagerTestInitHelper.closeWorkDatabase()
        context.filesDir.deleteRecursively()
    }

    private suspend fun awaitState(predicate: (ModelState) -> Boolean): ModelState =
        withTimeout(5_000) { repository.state.first(predicate) }

    private suspend fun awaitWork(predicate: (WorkInfo) -> Boolean): WorkInfo = withTimeout(5_000) {
        val infos = workManager.getWorkInfosForUniqueWorkFlow(ModelRepository.WORK_NAME)
        val matching = infos.first { list -> list.any(predicate) }
        matching.first(predicate)
    }

    /** Sparse files of the pinned sizes, recorded as verified: complete downloads without 2.9 GB. */
    private fun fakeCompleteDownloads() {
        val directory = repository.modelDirectory
        directory.mkdirs()
        val cache = VerificationCache.load(directory)
        for (file in ModelFiles.DOWNLOADS) {
            val target = File(directory, file.fileName)
            RandomAccessFile(target, "rw").use { access -> access.setLength(file.size) }
            cache.markVerified(file, target)
        }
    }

    @Test
    fun startingQueuesOneUniqueDownloadOnWifiByDefault() = runBlocking {
        assertEquals(ModelState.NotDownloaded, awaitState { state -> state !is ModelState.Verifying })

        repository.startDownload()
        val work = awaitWork { info -> info.state == WorkInfo.State.ENQUEUED }

        assertEquals(NetworkType.UNMETERED, work.constraints.requiredNetworkType)
        val queued = ModelState.Downloading(0, ModelFiles.DOWNLOAD_SIZE, fileName = null, isQueued = true)
        assertEquals(queued, awaitState { state -> state is ModelState.Downloading })

        repository.startDownload()
        val again = awaitWork { info -> info.state == WorkInfo.State.ENQUEUED }
        assertEquals(work.id, again.id)
    }

    @Test
    fun changingTheWifiSettingRequeuesAPendingDownload() = runBlocking {
        repository.startDownload()
        val onWifi = awaitWork { info -> info.constraints.requiredNetworkType == NetworkType.UNMETERED }

        settings.update { current -> current.copy(downloadOnWifiOnly = false) }
        val anyNetwork = awaitWork { info -> info.constraints.requiredNetworkType == NetworkType.CONNECTED }

        assertTrue(onWifi.id != anyNetwork.id)
        assertEquals(WorkInfo.State.ENQUEUED, anyNetwork.state)
    }

    @Test
    fun cancellingKeepsThePartialFileForAResume() = runBlocking {
        repository.startDownload()
        awaitWork { info -> info.state == WorkInfo.State.ENQUEUED }
        val part = File(repository.modelDirectory, "decoder_q4.onnx_data.part")
        part.parentFile?.mkdirs()
        part.writeBytes(ByteArray(10))

        repository.cancelDownload()

        assertEquals(ModelState.NotDownloaded, awaitState { state -> state is ModelState.NotDownloaded })
        assertTrue(part.exists())
    }

    @Test
    fun ensureReadyInstallsTheBundledAssetsOnceTheDownloadsAreComplete() = runBlocking {
        assertFalse(repository.ensureReady())

        fakeCompleteDownloads()
        assertTrue(repository.ensureReady())

        for (fileName in ModelFiles.BUNDLED) {
            assertTrue(File(repository.modelDirectory, fileName).isFile, "$fileName is not installed")
        }
        assertEquals(ModelState.Ready, awaitState { state -> state is ModelState.Ready })
    }

    @Test
    fun deletingTheModelCancelsTheWorkAndRemovesEveryFile() = runBlocking {
        fakeCompleteDownloads()
        repository.ensureReady()
        awaitState { state -> state is ModelState.Ready }
        repository.startDownload()
        awaitWork { info -> info.state == WorkInfo.State.ENQUEUED }

        assertTrue(repository.deleteModel())

        assertFalse(repository.modelDirectory.exists())
        assertEquals(ModelState.NotDownloaded, awaitState { state -> state is ModelState.NotDownloaded })
        val work = awaitWork { info -> info.state.isFinished }
        assertEquals(WorkInfo.State.CANCELLED, work.state)
    }
}
