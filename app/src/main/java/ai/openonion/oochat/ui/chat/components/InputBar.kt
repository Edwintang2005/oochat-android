package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.domain.model.AgentSkill
import ai.openonion.oochat.domain.model.ApprovalMode
import ai.openonion.oochat.domain.model.SessionUsageTotals
import ai.openonion.oochat.ui.common.rememberPermissionGate
import ai.openonion.oochat.ui.theme.spacing
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import java.io.File

/** A file picked via SAF's OpenDocument, with the display name resolved up front so the preview chip has something to show. */
private data class PickedFile(val uri: Uri, val name: String)

@Composable
fun InputBar(
    isConnected: Boolean,
    awaitingOnboardCode: Boolean = false,
    isSending: Boolean,
    // True while the agent has a running turn. It no longer locks anything:
    // the relay accepts an INPUT on a running session as runtime input
    // (ws_router/session.py's INPUT branch), so a message sent now reaches the
    // agent mid-run. It only adds the Stop control below the field. Distinct
    // from [isSending], which reflects the *connection* being mid-(re)connect.
    isAgentWorking: Boolean = false,
    // Tool-approval mode chip, on the row below the field alongside the session
    // usage. Defaults keep the dozens of existing InputBar call sites
    // (previews, screenshot cases) unchanged.
    approvalMode: ApprovalMode = ApprovalMode.DEFAULT,
    // True while approvalMode is only requested, not yet agent-confirmed —
    // see ConnectionRepository.modePending's doc.
    modePending: Boolean = false,
    usage: SessionUsageTotals = SessionUsageTotals(0, 0.0, null),
    // The connected agent's published skills (AgentLiveProfile.skills), which
    // are the whole candidate set for the `/` palette. Empty for most agents,
    // and an empty list means no palette is ever rendered.
    skills: List<AgentSkill> = emptyList(),
    // Live dictation, which fills this field and never sends on its own — see
    // ChatViewModel's voice-input section and [DictationMerge].
    voiceInput: VoiceInputState = VoiceInputState(),
    onCycleApprovalMode: () -> Unit = {},
    onSelectApprovalMode: (ApprovalMode, Int?) -> Unit = { _, _ -> },
    onSend: (text: String, images: List<String>, files: List<String>) -> Unit,
    onStartVoiceRecording: () -> Unit = {},
    onCancelVoiceRecording: () -> Unit = {},
    onFinishVoiceInput: () -> Unit = {},
    onVoiceTranscriptConsumed: () -> Unit = {},
    onQueryVoiceAmplitude: () -> Float = { 0f },
    onStop: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var input by remember { mutableStateOf(TextFieldValue("")) }
    // One cap across images *and* files, not one each. The modern photo picker
    // enforces it in its own UI; the gallery and document fallbacks cannot —
    // they let you select fifty — so they are truncated on the way back, and
    // say so rather than silently dropping most of a selection.
    fun toast(message: String) =
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()

    /**
     * Rejects the whole selection when it is over the cap rather than keeping
     * an arbitrary prefix of it. Silently dropping most of what someone
     * picked is worse than asking them to pick again: they cannot tell which
     * three survived, and the ones they cared about may not be among them.
     */
    fun <T> accepted(existing: List<T>, incoming: List<T>, otherKindCount: Int): List<T>? {
        val room = (MAX_ATTACHMENTS - otherKindCount - existing.size).coerceAtLeast(0)
        if (incoming.size > room) {
            toast(
                if (room == 0) "Already at $MAX_ATTACHMENTS attachments — remove one first."
                else "Pick at most $room more — up to $MAX_ATTACHMENTS per message."
            )
            return null
        }
        return existing + incoming
    }
    var attachedImages by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var attachedFiles by remember { mutableStateOf<List<PickedFile>>(emptyList()) }
    // Null when no dictation is running. Held here, not in the ViewModel, because
    // the field's text is here — see [DictationAnchor].
    var dictationAnchor by remember { mutableStateOf<DictationAnchor?>(null) }
    val isRecording = voiceInput.isRecording
    var recordingElapsedSeconds by remember { mutableFloatStateOf(0f) }
    // Holds the output Uri handed to the camera app until its TakePicture()
    // callback fires — that callback only reports success/failure, not the
    // Uri, since the caller (not the camera) chose it up front.
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var showModeSheet by remember { mutableStateOf(false) }
    val fieldInteractionSource = remember { MutableInteractionSource() }

    // Text is required even with attachments staged: the relay rejects any
    // INPUT with an empty prompt regardless of images/files, in both the
    // new-turn and interject paths. Offering the send here only produced a
    // "prompt required" error every time. TextInputField's placeholder says
    // why while attachments are waiting.
    val canSend = isConnected && !isSending && input.text.isNotBlank()
    val fieldEnabled = isConnected
    // Dictation only writes into this field, so it needs no more than the field
    // itself does; it still waits out a dictation, because finishing one now
    // belongs to the waveform and this slot has nothing left to offer until the
    // transcript lands. It does not wait out a send or an agent turn, which is
    // exactly when someone wants to dictate the next message.
    val micEnabled = fieldEnabled && !voiceInput.isBusy
    val hasPendingAttachments = attachedImages.isNotEmpty() || attachedFiles.isNotEmpty()

    /**
     * The composer's one send path, shared by the trailing button and anything
     * else that can post the field — so they cannot drift apart.
     */
    fun submit() {
        if (!canSend) return
        // The keyboard is left alone. It used to be dismissed here, on the
        // grounds that an emptied field has nothing left to edit — but that
        // hid the reply's arrival behind a relayout, and interjecting mid-run
        // means the next message often follows straight on.
        onSend(
            input.text.trim(),
            attachedImages.map { it.toString() },
            attachedFiles.map { it.uri.toString() }
        )
        input = TextFieldValue("")
        attachedImages = emptyList()
        attachedFiles = emptyList()
    }

    // Every update is recomputed from the anchor, so a stream of partials
    // replaces its own last guess instead of appending each one.
    LaunchedEffect(voiceInput.transcript, voiceInput.phase) {
        val anchor = dictationAnchor ?: return@LaunchedEffect
        DictationMerge.merge(anchor, voiceInput.transcript)?.let { input = it }
        if (!voiceInput.isBusy) {
            dictationAnchor = null
            onVoiceTranscriptConsumed()
        }
    }
    // Gated on fieldEnabled so a field locked by a dead socket can't leave a
    // palette hanging over the transcript.
    val slashCandidates = remember(skills, input.text, fieldEnabled) {
        if (fieldEnabled) SlashCommandMatcher.candidates(skills, input.text) else emptyList()
    }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = MAX_ATTACHMENTS)
    ) { uris ->
        if (uris.isNotEmpty()) {
            val fresh = uris.filterNot(attachedImages::contains)
            accepted(attachedImages, fresh, attachedFiles.size)?.let { attachedImages = it }
        }
    }

    // Used where no photo picker exists — see launchImagePicker.
    val galleryPicker = rememberLauncherForActivityResult(
        contract = remember { PickImagesFromGallery() }
    ) { uris ->
        if (uris.isNotEmpty()) {
            val fresh = uris.filterNot(attachedImages::contains)
            accepted(attachedImages, fresh, attachedFiles.size)?.let { attachedImages = it }
        }
    }

    val documentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val fresh = uris.filterNot(attachedImages::contains)
            accepted(attachedImages, fresh, attachedFiles.size)?.let { attachedImages = it }
        }
    }

    val hasGalleryApp = remember(context) {
        Intent(Intent.ACTION_PICK)
            .apply { type = "image/*" }
            .resolveActivity(context.packageManager) != null
    }

    /**
     * Three tiers, because pre-33 devices without the Play services backport
     * fall through PickMultipleVisualMedia to ACTION_OPEN_DOCUMENT, where
     * DocumentsUI buries multi-select under a long-press. ACTION_GET_CONTENT
     * does not help: DocumentsUI registers it at priority 100 and wins there
     * too. ACTION_PICK is handled only by gallery apps, which open in
     * multi-select directly — measured on a P30 Pro. GET_CONTENT stays as the
     * last resort for a device with no gallery app at all.
     */
    fun launchImagePicker() {
        when {
            ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(context) ->
                imagePicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            // The gallery and document fallbacks cannot be told a limit, so say
            // it before they open rather than rejecting the result afterwards.
            hasGalleryApp -> {
                toast("Pick up to $MAX_ATTACHMENTS")
                galleryPicker.launch(Unit)
            }
            else -> documentPicker.launch("image/*")
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = pendingCameraUri
        if (success && uri != null) {
            // The camera adds one, but it still has to fit the same budget.
            accepted(attachedImages, listOf(uri).filterNot(attachedImages::contains), attachedFiles.size)
                ?.let { attachedImages = it }
        }
        pendingCameraUri = null
    }

    fun launchCamera() {
        val uri = createCameraOutputUri(context)
        pendingCameraUri = uri
        cameraLauncher.launch(uri)
    }

    // See ui/common/PermissionGate.kt — shared with the mic gate below and
    // with onboarding's QR-scan camera gate (OnboardingScreen.kt).
    val requestCameraPermission = rememberPermissionGate(
        permission = Manifest.permission.CAMERA,
        rationaleTitle = "Camera access",
        rationaleMessage = "ConnectOnion needs your camera to take a photo to send. Nothing is captured until you take the picture.",
        deniedMessage = "Camera access is off, so a photo cannot be taken. Allow it in Settings, or attach a picture from your gallery instead.",
        onGranted = ::launchCamera
    )

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val picked = uris.map { uri -> PickedFile(uri, queryDisplayName(context, uri) ?: uri.lastPathSegment ?: "file") }
            // OpenMultipleDocuments has no built-in cap, so it is applied here
            // after merging with whatever was already picked.
            val fresh = picked.filterNot { p -> attachedFiles.any { it.uri == p.uri } }
            accepted(attachedFiles, fresh, attachedImages.size)?.let { attachedFiles = it }
        }
    }

    val requestMicPermission = rememberPermissionGate(
        permission = Manifest.permission.RECORD_AUDIO,
        rationaleTitle = "Microphone access",
        rationaleMessage = "ConnectOnion needs your microphone to dictate a message. Nothing is captured until you tap the mic, and what it hears goes into the text field for you to check before sending.",
        deniedMessage = "Microphone blocked. Allow microphone access in Settings, then try again. You can still type your message.",
        onGranted = {
            dictationAnchor = DictationAnchor(base = input.text)
            onStartVoiceRecording()
        }
    )

    // Keyed on isRecording, which is LISTENING alone: the clock starts when a
    // microphone opens, not when the mic button was tapped, so what it shows
    // matches the audio that was actually captured.
    LaunchedEffect(isRecording) {
        if (!isRecording) {
            recordingElapsedSeconds = 0f
            return@LaunchedEffect
        }
        // uptimeMillis, not currentTimeMillis: a stopwatch must not jump when
        // the clock is corrected by NTP or by the user mid-dictation.
        val start = SystemClock.uptimeMillis()
        while (isRecording) {
            recordingElapsedSeconds = (SystemClock.uptimeMillis() - start) / 1000f
            delay(50)
        }
    }

    // Most dictations reach a live microphone in a few milliseconds — see
    // PREPARING_VISIBLE_AFTER_MS. Showing "Preparing…" only once the wait has
    // outlasted that keeps the common case looking exactly as it does today.
    var preparingVisible by remember { mutableStateOf(false) }
    LaunchedEffect(voiceInput.phase) {
        if (voiceInput.phase != VoiceInputPhase.PREPARING) {
            preparingVisible = false
            return@LaunchedEffect
        }
        delay(PREPARING_VISIBLE_AFTER_MS)
        preparingVisible = true
    }
    val dictationRowVisible =
        voiceInput.isBusy && (voiceInput.phase != VoiceInputPhase.PREPARING || preparingVisible)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 16.dp)
    ) {
        if (showModeSheet) {
            ApprovalModeSheet(
                current = approvalMode,
                onSelect = onSelectApprovalMode,
                onDismiss = { showModeSheet = false }
            )
        }

        // Kept visible through a dictation: voice and attachments are not
        // alternatives, so a staged photo must not vanish while you dictate the
        // sentence that goes with it.
        AnimatedVisibility(
            visible = hasPendingAttachments,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
                contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 12.dp)
            ) {
                itemsIndexed(attachedImages, key = { _, it -> it.toString() }) { index, uri ->
                    AttachmentThumbnail(
                        uri = uri,
                        onRemove = { attachedImages = attachedImages - uri },
                        index = index,
                        total = attachedImages.size
                    )
                }
                if (attachedImages.size < 4) {
                    item(key = "add-more") {
                        AddMoreTile(
                            enabled = fieldEnabled,
                            onClick = { launchImagePicker() }
                        )
                    }
                }
                itemsIndexed(attachedFiles, key = { _, it -> "file-${it.uri}" }) { _, file ->
                    AttachmentFileChip(
                        name = file.name,
                        onRemove = { attachedFiles = attachedFiles - file }
                    )
                }
            }
        }

        // Above the recording row, not wedged between it and the field: the
        // notice is about the dictation, so it reads as a caption on the row
        // rather than a stray line splitting the composer in two.
        voiceInput.notice?.let { message ->
            VoiceInputNotice(message)
        }

        // Above the field, not instead of it: the whole point of streaming
        // partial results is watching them land in the text you are about to
        // send, which a full-width takeover hid.
        AnimatedVisibility(
            visible = dictationRowVisible,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            VoiceRecordingRow(
                elapsedSeconds = recordingElapsedSeconds,
                phase = voiceInput.phase,
                onCancel = onCancelVoiceRecording,
                onFinish = onFinishVoiceInput,
                onQueryVoiceAmplitude = onQueryVoiceAmplitude
            )
        }

        // Stays put: an error only ever shows with the row already gone, so it
        // is already sitting directly above the field it is talking about.
        voiceInput.error?.let { message ->
            VoiceInputError(message)
        }

        SlashCommandPalette(
            candidates = slashCandidates,
            // The trailing space in the completion is what closes the
            // palette; the caret follows it into the arguments.
            onSelect = {
                val completed = SlashCommandMatcher.completion(it)
                input = TextFieldValue(completed, TextRange(completed.length))
            }
        )

        TextInputField(
            value = input,
            onValueChange = { edited ->
                dictationAnchor = dictationAnchor?.let {
                    DictationMerge.onFieldChanged(it, input.text, edited.text)
                }
                input = edited
            },
            isConnected = isConnected,
            awaitingOnboardCode = awaitingOnboardCode,
            isAgentWorking = isAgentWorking,
            interactionSource = fieldInteractionSource,
            attachmentCount = attachedImages.size + attachedFiles.size,
            onCameraClick = requestCameraPermission,
            onGalleryClick = { launchImagePicker() },
            onFileClick = { filePicker.launch(arrayOf("*/*")) },
            trailingContent = {
                // Mic and Send both, always: dictation appends to whatever is
                // already in the field, so the two stopped being alternatives.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MicButton(enabled = micEnabled, onClick = requestMicPermission)
                    SendButton(enabled = canSend, onClick = ::submit)
                }
            }
        )

        // The composer's secondary shelf. Stop lives here rather than in the
        // send slot: sending is a real option mid-run now, so Stop needed a
        // home of its own, and a fourth button would not fit inside the pill.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = MaterialTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ApprovalModeChip(
                mode = approvalMode,
                // Switching mode is a message to a running agent; with no live
                // socket there is nothing to send it to.
                enabled = isConnected,
                pending = modePending,
                onCycle = onCycleApprovalMode,
                onOpenSheet = { showModeSheet = true }
            )
            Spacer(modifier = Modifier.weight(1f))
            if (isAgentWorking) {
                // Live throughout a dictation now: the mic slot no longer turns
                // into a second square glyph, so there is nothing to confuse it
                // with, and interrupting a runaway agent should not need to wait
                // for the sentence you are dictating at it to finish.
                StopButton(onClick = onStop)
                Spacer(modifier = Modifier.padding(horizontal = MaterialTheme.spacing.xxs))
            }
            SessionUsageSummary(usage)
        }
    }
}

/**
 * Creates a fresh app-private file under `cacheDir/camera/` and hands back a
 * `content://` Uri for it via [FileProvider] (see AndroidManifest.xml's
 * `<provider>` and res/xml/file_paths.xml) — [ActivityResultContracts.TakePicture]
 * needs somewhere to write the photo it captures, unlike the gallery/file
 * pickers which just return a Uri to something that already exists.
 */
/**
 * ACTION_PICK against whatever gallery app the device ships, for the tier of
 * devices with no photo picker — see [InputBar]'s launchImagePicker.
 *
 * Not expressible with the stock contracts: they all build GET_CONTENT or
 * OPEN_DOCUMENT, both of which DocumentsUI claims. ALLOW_MULTIPLE returns the
 * selection in clipData; a gallery that ignores it still returns one Uri in
 * getData, so both shapes are read.
 */
/** Attachments per message, images and files counted together — see InputBar's fitting(). */
private const val MAX_ATTACHMENTS = 3

/**
 * How long a [VoiceInputPhase.PREPARING] window has to last before the row
 * says so — a label that appears and vanishes again reads as a glitch.
 *
 * Measured on TAS-AL00/API31 and an API 34 emulator: a cached verdict reaches
 * the recorder in 90-155ms and a healthy recognizer answers in 235-330ms,
 * while the first-dictation probe takes ~2.7s and a cold recognition service
 * ~1.7s. 400ms clears the first two and still catches both real waits.
 */
internal const val PREPARING_VISIBLE_AFTER_MS = 400L

private class PickImagesFromGallery : ActivityResultContract<Unit, List<Uri>>() {

    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(Intent.ACTION_PICK).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        if (resultCode != Activity.RESULT_OK || intent == null) return emptyList()
        val clip = intent.clipData ?: return listOfNotNull(intent.data)
        return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
    }
}

private fun createCameraOutputUri(context: Context): Uri {
    val dir = File(context.cacheDir, "camera").apply { if (!exists()) mkdirs() }
    val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

/** Best-effort SAF display name lookup for a picked document Uri — falls back to the Uri's last path segment when the provider doesn't report one. */
private fun queryDisplayName(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else {
                null
            }
        }
    } catch (e: Exception) {
        null
    }
}
