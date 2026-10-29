package com.danemadsen.curie.ui.navigation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.danemadsen.curie.R
import com.danemadsen.curie.call.CallLauncher
import com.danemadsen.curie.data.AttachmentKind
import com.danemadsen.curie.ui.chat.ChatAction
import com.danemadsen.curie.ui.chat.ChatEvent
import com.danemadsen.curie.ui.chat.ChatScreen
import com.danemadsen.curie.ui.chat.ChatViewModel
import com.danemadsen.curie.ui.chat.MediaSource
import com.danemadsen.curie.ui.common.hasModelFiles
import kotlinx.coroutines.launch
import java.io.File

/** The trigger CallLauncher records for the header's phone button. */
private const val CALL_TRIGGER_BUTTON = "button"

/** Chat exports are JSON; some file managers label .json files as text or as plain bytes. */
private val IMPORT_TYPES = arrayOf("application/json", "text/plain", "application/octet-stream")

private const val EXPORT_TYPE = "application/json"

/**
 * The chat screen with what it needs from the activity: the photo picker and the camera, the
 * microphone permission, chat import and export documents, other apps for attachments, calls and
 * navigation. Everything else goes to the [ChatViewModel].
 */
@Composable
internal fun ChatRoute(
    viewModel: ChatViewModel,
    sharedMedia: SharedMedia?,
    onSharedMediaTaken: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSetup: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var exportingChatId by rememberSaveable { mutableStateOf<String?>(null) }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            viewModel.addMedia(uri)
        }
    }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { isSaved ->
        viewModel.onCaptureFinished(isSaved)
    }
    val captureVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { isSaved ->
        viewModel.onCaptureFinished(isSaved)
    }
    val importChats = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.importChats(uris)
        }
    }
    val exportChat = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(EXPORT_TYPE)) { uri ->
        val chatId = exportingChatId
        exportingChatId = null
        if (uri != null && chatId != null) {
            viewModel.exportChat(chatId, uri)
        }
    }
    val microphoneRequest = ActivityResultContracts.RequestPermission()
    val requestMicrophone = rememberLauncherForActivityResult(microphoneRequest) { isGranted ->
        if (isGranted) {
            viewModel.startRecording()
        } else {
            val text = resources.getString(R.string.recording_microphone_needed)
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        }
    }
    val requestToolPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ -> }

    LifecycleResumeEffect(viewModel) {
        viewModel.onScreenVisible()
        onPauseOrDispose {}
    }

    LaunchedEffect(sharedMedia) {
        if (sharedMedia != null) {
            viewModel.onSharedMedia(sharedMedia.uris, sharedMedia.mimeType)
            onSharedMediaTaken()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.events.collect { event ->
                when (event) {
                    is ChatEvent.Notice -> Toast.makeText(context, event.text, Toast.LENGTH_LONG).show()
                    is ChatEvent.RequestPermissions -> requestToolPermissions.launch(event.permissions.toTypedArray())
                }
            }
        }
    }

    val onAction: (ChatAction) -> Unit = { action ->
        when (action) {
            ChatAction.OpenModelStatus -> {
                if (state.modelStatus.hasModelFiles) onOpenSettings() else onOpenSetup()
            }
            ChatAction.StartCall -> {
                if (viewModel.canStartCall()) {
                    val callIntent = CallLauncher.intent(context, CALL_TRIGGER_BUTTON)
                    context.startActivitySafely(callIntent)
                }
            }
            ChatAction.ImportChats -> importChats.launch(IMPORT_TYPES)
            is ChatAction.ExportChat -> {
                exportingChatId = action.chatId
                val fileName = viewModel.exportFileName(action.chatId)
                exportChat.launch(fileName)
            }
            ChatAction.OpenSettings -> onOpenSettings()
            is ChatAction.AddMedia -> when (action.source) {
                MediaSource.PICK_PHOTO -> {
                    val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    pickMedia.launch(request)
                }
                MediaSource.PICK_VIDEO -> {
                    val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                    pickMedia.launch(request)
                }
                MediaSource.TAKE_PHOTO -> scope.launch {
                    val uri = viewModel.createCaptureUri(AttachmentKind.IMAGE)
                    if (uri != null) {
                        takePicture.launch(uri)
                    }
                }
                MediaSource.RECORD_VIDEO -> scope.launch {
                    val uri = viewModel.createCaptureUri(AttachmentKind.VIDEO)
                    if (uri != null) {
                        captureVideo.launch(uri)
                    }
                }
            }
            ChatAction.StartRecording -> {
                if (hasMicrophonePermission(context)) {
                    viewModel.startRecording()
                } else {
                    requestMicrophone.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
            is ChatAction.OpenAttachment -> openAttachment(context, viewModel, action.attachmentId)
            else -> viewModel.onAction(action)
        }
    }

    ChatScreen(state = state, prompt = viewModel.prompt, onAction = onAction)
}

private fun hasMicrophonePermission(context: Context): Boolean {
    val state = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
    return state == PackageManager.PERMISSION_GRANTED
}

/** Opens a message's picture or video in the user's viewer, with a read grant for its file. */
private fun openAttachment(context: Context, viewModel: ChatViewModel, attachmentId: String) {
    val isOpened = tryOpenAttachment(context, viewModel, attachmentId)
    if (!isOpened) {
        val text = context.getString(R.string.attachment_open_failed)
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }
}

private fun tryOpenAttachment(context: Context, viewModel: ChatViewModel, attachmentId: String): Boolean {
    val attachment = viewModel.attachmentFile(attachmentId) ?: return false
    val authority = context.packageName + FILE_PROVIDER_SUFFIX
    val file = File(attachment.path)
    val uri = try {
        FileProvider.getUriForFile(context, authority, file)
    } catch (error: IllegalArgumentException) {
        return false
    }

    val intent = Intent(Intent.ACTION_VIEW)
    intent.setDataAndType(uri, attachment.mimeType)
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return context.startActivitySafely(intent)
}

private const val FILE_PROVIDER_SUFFIX = ".files"
