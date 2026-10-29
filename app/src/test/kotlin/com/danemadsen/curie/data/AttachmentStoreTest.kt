package com.danemadsen.curie.data

import android.content.pm.ProviderInfo
import android.net.Uri
import android.os.Bundle
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.danemadsen.curie.R
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AttachmentStoreTest {
    private val limits = AttachmentLimits(
        maxImageBytes = 64,
        maxVideoBytes = 128,
        maxVideoDurationMs = 300_000,
        maxAudioBytes = 96,
        maxAudioDurationMs = 600_000,
    )
    private val fixture = DataTestFixture(limits)
    private val store = fixture.attachmentStore

    @After
    fun tearDown() {
        fixture.close()
    }

    private fun storedFiles(): List<String> {
        val files = store.directory.listFiles() ?: return emptyList()
        return files.map { file -> file.name }
    }

    @Test
    fun importsACopyWithTheFactsTheContentHas() = runTest {
        val source = fixture.sourceUri("IMAGE")

        // The picker's claim does not matter: the content decides
        val image = store.import(source, mimeTypeHint = "video/mp4")

        assertEquals(AttachmentKind.IMAGE, image.kind)
        assertEquals("image/png", image.mimeType)
        assertEquals(640 to 480, image.width to image.height)
        assertEquals(File(store.directory, "${image.id}.png").path, image.path)
        assertEquals("IMAGE", File(image.path).readText())
        assertEquals(listOf("${image.id}.png"), storedFiles())
    }

    @Test
    fun videoAndAudioFactsCarryOver() = runTest {
        val video = fixture.importAttachment("VIDEO 4000")
        val audio = fixture.importAttachment("AUDIO 2500")

        assertEquals(AttachmentKind.VIDEO, video.kind)
        assertEquals(120, video.frameCount)
        assertEquals(30.0, video.fps)
        assertEquals(64_000, video.sampleCount16k)
        assertTrue(video.hasSoundtrack)
        assertEquals(AttachmentKind.AUDIO, audio.kind)
        assertEquals(2_500, audio.durationMs)
        assertEquals(40_000, audio.sampleCount16k)
        assertFalse(audio.hasSoundtrack)
    }

    @Test
    fun unsupportedContentLeavesNothingBehind() = runTest {
        val source = fixture.sourceUri("PDF document")

        val error = assertFailsWith<AttachmentImportException.Unsupported> { store.import(source, "image/jpeg") }

        assertEquals("image/jpeg", error.mimeTypeHint)
        assertTrue(storedFiles().isEmpty())
    }

    @Test
    fun eachKindHasItsSizeLimit() = runTest {
        val image = fixture.sourceUri("IMAGE " + "x".repeat(70))

        val error = assertFailsWith<AttachmentImportException.TooLarge> { store.import(image) }

        assertEquals(AttachmentKind.IMAGE, error.kind)
        assertEquals(76, error.sizeBytes)
        assertEquals(64, error.limitBytes)
        assertTrue(storedFiles().isEmpty())
    }

    @Test
    fun aDeclaredSizeOverEveryLimitIsRefusedBeforeCopying() = runTest {
        val huge = fixture.sourceUri("VIDEO " + "x".repeat(200))

        val error = assertFailsWith<AttachmentImportException.TooLarge> { store.import(huge) }

        assertNull(error.kind)
        assertEquals(206, error.sizeBytes)
        assertEquals(128, error.limitBytes)
    }

    @Test
    fun aStreamThatOutgrowsEveryLimitStopsCopying() = runTest {
        val bytes = ByteArray(1_000) { 'x'.code.toByte() }
        val input = ByteArrayInputStream(bytes)

        val error = assertFailsWith<AttachmentImportException.TooLarge> { store.importStream(input, null) }

        assertNull(error.kind)
        assertTrue(error.sizeBytes > 128)
        assertTrue(storedFiles().isEmpty())
    }

    @Test
    fun clipsLongerThanTheirLimitAreRefused() = runTest {
        val longVideo = fixture.sourceUri("VIDEO 300001")
        val longAudio = fixture.sourceUri("AUDIO 600001")

        val videoError = assertFailsWith<AttachmentImportException.TooLong> { store.import(longVideo) }
        val audioError = assertFailsWith<AttachmentImportException.TooLong> { store.import(longAudio) }

        assertEquals(AttachmentKind.VIDEO, videoError.kind)
        assertEquals(300_000, videoError.limitMs)
        assertEquals(AttachmentKind.AUDIO, audioError.kind)
        assertEquals(600_001, audioError.durationMs)
        assertTrue(storedFiles().isEmpty())
    }

    @Test
    fun aMissingSourceIsUnreadable() = runTest {
        val missing = Uri.fromFile(File(fixture.context.cacheDir, "missing.jpg"))

        assertFailsWith<AttachmentImportException.Unreadable> { store.import(missing) }
    }

    @Test
    fun aCaptureUriIsSharedThroughTheFileProviderAndConsumedByTheImport() = runTest {
        registerFileProvider()

        val captureUri = store.createCaptureUri(AttachmentKind.IMAGE)
        assertEquals("${fixture.context.packageName}.files", captureUri.authority)
        assertEquals("captures", captureUri.pathSegments.first())
        assertTrue(captureUri.lastPathSegment.orEmpty().endsWith(".jpg"))

        // The camera writes the photo through the Uri
        val resolver = fixture.context.contentResolver
        val output = resolver.openOutputStream(captureUri)
        output?.use { stream -> stream.write("IMAGE".toByteArray()) }

        val image = store.import(captureUri)

        assertEquals(AttachmentKind.IMAGE, image.kind)
        val captures = File(fixture.context.cacheDir, "captures")
        assertTrue(captures.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aCaptureWithContentThatIsRefusedIsConsumed() = runTest {
        registerFileProvider()
        val captureUri = store.createCaptureUri(AttachmentKind.VIDEO)
        val output = fixture.context.contentResolver.openOutputStream(captureUri)
        output?.use { stream -> stream.write("garbage".toByteArray()) }

        assertFailsWith<AttachmentImportException.Unsupported> { store.import(captureUri) }

        val captures = File(fixture.context.cacheDir, "captures")
        assertTrue(captures.listFiles().orEmpty().isEmpty())
        assertTrue(storedFiles().isEmpty())
    }

    @Test
    fun aCaptureIsKeptForARetryWhenStorageFails() = runTest {
        registerFileProvider()
        val captureUri = store.createCaptureUri(AttachmentKind.IMAGE)
        val output = fixture.context.contentResolver.openOutputStream(captureUri)
        output?.use { stream -> stream.write("IMAGE".toByteArray()) }

        // A file where the attachment directory belongs: storage refuses the copy
        store.directory.parentFile?.mkdirs()
        store.directory.writeText("in the way")
        assertFailsWith<AttachmentImportException.StorageFailed> { store.import(captureUri) }

        val captures = File(fixture.context.cacheDir, "captures")
        assertEquals(1, captures.listFiles().orEmpty().size)

        // Once storage recovers, the same Uri imports
        store.directory.delete()
        val image = store.import(captureUri)
        assertEquals(AttachmentKind.IMAGE, image.kind)
        assertTrue(captures.listFiles().orEmpty().isEmpty())
    }

    /** What the manifest's provider entry does on a device, with the app's real paths resource. */
    private fun registerFileProvider() {
        val context = fixture.context
        val metaData = Bundle()
        metaData.putInt("android.support.FILE_PROVIDER_PATHS", R.xml.file_paths)

        val info = ProviderInfo()
        info.authority = "${context.packageName}.files"
        info.name = FileProvider::class.java.name
        info.packageName = context.packageName
        info.applicationInfo = context.applicationInfo
        info.exported = false
        info.grantUriPermissions = true
        info.metaData = metaData

        shadowOf(context.packageManager).addOrUpdateProvider(info)
        Robolectric.buildContentProvider(FileProvider::class.java).create(info)
    }
}
