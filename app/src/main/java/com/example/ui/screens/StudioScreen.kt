package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.data.model.HookBannerStyle
import com.example.data.model.ImageAspectOption
import com.example.data.model.ImageModelOption
import com.example.data.model.ImageSizeOption
import com.example.data.model.LayoutPreset
import com.example.data.model.PipelineMetrics
import com.example.data.model.ViralClip
import com.example.engine.ViralMomentEngine
import com.example.ui.theme.BroadcastSlate
import com.example.ui.theme.CrimsonLive
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.ElectricVolt
import com.example.ui.theme.ElevatedCard
import com.example.ui.theme.MontserratFont
import com.example.ui.theme.NeonMint
import com.example.ui.theme.ObsidianInk
import com.example.ui.theme.SteelOutline
import com.example.ui.theme.StudioObsidian
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.viewmodel.AiStudioUiState
import com.example.ui.viewmodel.AsyncOpState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StudioScreen(
    clip: ViralClip?,
    playbackUri: Uri?,
    pipeline: PipelineMetrics,
    aiState: AiStudioUiState,
    onUpdateClip: (
        clip: ViralClip,
        layoutPreset: LayoutPreset?,
        hookText: String?,
        hookStyle: HookBannerStyle?,
        creditWatermark: String?,
        chatOverlayEnabled: Boolean?,
        transcript: String?
    ) -> Unit,
    onExportClip: (ViralClip) -> Unit,
    onGoToPublish: () -> Unit,
    onGenerateFastHooks: (ViralClip) -> Unit,
    onStartMicRecording: () -> Unit,
    onStopMicRecording: () -> Unit,
    onAnalyzeClipVideo: (ByteArray, String) -> Unit,
    onAnalyzeImage: (Bitmap) -> Unit,
    onUpdateImageGenOptions: (ImageModelOption?, ImageAspectOption?, ImageSizeOption?) -> Unit,
    onGenerateCoverImage: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    if (clip == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Default.MovieCreation, contentDescription = null, tint = ElectricVolt, modifier = Modifier.size(44.dp))
                Text("Smart 9:16 Clip Studio", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                Text(
                    "Analyze a stream URL or pick a device video on the Analyze tab first to open a clip here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
            }
        }
        return
    }

    var hookDraft by remember(clip.id) { mutableStateOf(clip.hookBannerText) }
    var creditDraft by remember(clip.id) { mutableStateOf(clip.creditWatermark) }
    var transcriptDraft by remember(clip.id) { mutableStateOf(clip.transcriptPreview.orEmpty()) }
    var coverPrompt by remember(clip.id) {
        mutableStateOf("High-contrast vertical YouTube Shorts cover art for ${clip.streamerHandle}, ${clip.hookBannerText}, electric neon gaming atmosphere")
    }
    var pickedImagePreview by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(aiState.transcription) {
        val t = aiState.transcription
        if (t is AsyncOpState.Success && t.data.isNotBlank()) {
            transcriptDraft = t.data
            onUpdateClip(clip, null, null, null, null, null, t.data)
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                }
                if (bmp != null) {
                    pickedImagePreview = bmp
                    onAnalyzeImage(bmp)
                }
            }
        }
    }

    val videoPickerForArgonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val bytes = withContext(Dispatchers.IO) { readCappedUriBytes(context.contentResolver, uri) }
                if (bytes.isNotEmpty()) {
                    onAnalyzeClipVideo(bytes, clip.title)
                }
            }
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) onStartMicRecording()
    }

    val captionWords = remember(transcriptDraft, clip.hookBannerText, clip.startSec, clip.endSec) {
        ViralMomentEngine.generateWordTimings(
            transcriptDraft.ifBlank { clip.hookBannerText },
            clip.durationSec()
        )
    }

    var previewWordIndex by remember(captionWords) { mutableIntStateOf(0) }
    LaunchedEffect(captionWords) {
        if (captionWords.isNotEmpty()) {
            while (true) {
                delay(420L)
                previewWordIndex = (previewWordIndex + 1) % captionWords.size
            }
        }
    }

    val effectivePlaybackUri = clip.localOutputPath?.takeIf { File(it).exists() }?.let { Uri.fromFile(File(it)) }
        ?: playbackUri

    val exoPlayer = remember(effectivePlaybackUri) {
        if (effectivePlaybackUri != null) {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(effectivePlaybackUri))
                prepare()
            }
        } else null
    }
    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer?.release() }
    }

    val currentPreset = clip.layoutPresetEnum()
    val currentBannerStyle = clip.hookStyleEnum()
    val isRendering = pipeline.exportProgress in 0.01f..0.99f

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 840.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 9:16 Vertical Stage Preview
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .widthIn(max = 260.dp)
                            .fillMaxWidth()
                            .aspectRatio(9f / 16f)
                            .clip(RoundedCornerShape(22.dp))
                            .background(Color(0xFF06080C))
                            .border(2.dp, ElectricVolt.copy(alpha = 0.65f), RoundedCornerShape(22.dp))
                    ) {
                        if (exoPlayer != null) {
                            AndroidView(
                                factory = { ctx ->
                                    PlayerView(ctx).apply {
                                        player = exoPlayer
                                        useController = true
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                if (currentPreset == LayoutPreset.SPLIT_SCREEN) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(0.32f)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(ElevatedCard),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("FACECAM (32%)", style = MaterialTheme.typography.labelMedium, color = CyberCyan)
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(0.68f)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(BroadcastSlate),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("GAMEPLAY (68%)", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                                    }
                                } else {
                                    Text(
                                        text = currentPreset.label.uppercase(),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = CyberCyan
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = "1080 × 1920 • ${clip.durationSec()}s",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = TextMuted
                                    )
                                }
                            }
                        }

                        if (creditDraft.isNotBlank()) {
                            Surface(
                                color = StudioObsidian.copy(alpha = 0.72f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(10.dp)
                            ) {
                                Text(
                                    text = creditDraft,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextPrimary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        if (hookDraft.isNotBlank()) {
                            val (bg, fg) = when (currentBannerStyle) {
                                HookBannerStyle.VOLT_PILL -> ElectricVolt to ObsidianInk
                                HookBannerStyle.WHITE_PILL -> Color.White to Color.Black
                                HookBannerStyle.CRIMSON_ALERT -> CrimsonLive to Color.White
                            }
                            Surface(
                                color = bg,
                                shape = RoundedCornerShape(999.dp),
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 44.dp, start = 14.dp, end = 14.dp)
                            ) {
                                Text(
                                    text = hookDraft,
                                    color = fg,
                                    fontFamily = MontserratFont,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 13.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                )
                            }
                        }

                        if (captionWords.isNotEmpty()) {
                            val chunkStart = (previewWordIndex / 3) * 3
                            val chunk = captionWords.subList(chunkStart, minOf(chunkStart + 3, captionWords.size))
                            val activeInChunk = previewWordIndex - chunkStart
                            Row(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 52.dp, start = 12.dp, end = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                chunk.forEachIndexed { idx, w ->
                                    Text(
                                        text = w.word.uppercase(),
                                        fontFamily = MontserratFont,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = if (idx == activeInChunk) 18.sp else 16.sp,
                                        color = if (idx == activeInChunk) ElectricVolt else Color.White
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { onExportClip(clip) },
                            enabled = !isRendering,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ElectricVolt,
                                contentColor = ObsidianInk
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                                .testTag("render_clip_button")
                        ) {
                            Icon(Icons.Default.MovieCreation, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (isRendering) {
                                    "Rendering ${(pipeline.exportProgress * 100).toInt()}%"
                                } else {
                                    "Render 9:16 MP4"
                                },
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                        OutlinedButton(
                            onClick = onGoToPublish,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .height(52.dp)
                                .testTag("go_to_publish_button")
                        ) {
                            Icon(Icons.Default.CloudUpload, contentDescription = null, tint = CyberCyan)
                            Spacer(Modifier.width(6.dp))
                            Text("Publish", color = CyberCyan)
                        }
                    }

                    if (isRendering) {
                        LinearProgressIndicator(
                            progress = { pipeline.exportProgress },
                            modifier = Modifier.fillMaxWidth(),
                            color = ElectricVolt
                        )
                    }
                    if (pipeline.exportStatus.isNotBlank() && pipeline.exportStatus != "Idle") {
                        Text(
                            text = pipeline.exportStatus,
                            color = if (pipeline.exportStatus.contains("fail", true) || pipeline.exportStatus.contains("Error")) CrimsonLive else NeonMint,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

            // Layout Preset & Hook Customizer
            item {
                Surface(
                    color = BroadcastSlate,
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, SteelOutline)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("9:16 Template & Hook Overlay", style = MaterialTheme.typography.titleMedium, color = TextPrimary)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            LayoutPreset.entries.forEach { preset ->
                                FilterChip(
                                    selected = currentPreset == preset,
                                    onClick = { onUpdateClip(clip, preset, null, null, null, null, null) },
                                    label = { Text(preset.label) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = ElectricVolt,
                                        selectedLabelColor = ObsidianInk
                                    ),
                                    modifier = Modifier.testTag("preset_${preset.name.lowercase()}")
                                )
                            }
                        }

                        OutlinedTextField(
                            value = hookDraft,
                            onValueChange = {
                                hookDraft = it
                                onUpdateClip(clip, null, it, null, null, null, null)
                            },
                            label = { Text("Frame-1 Viral Hook Banner (0.0s–3.5s)") },
                            modifier = Modifier.fillMaxWidth().testTag("hook_banner_input")
                        )

                        OutlinedButton(
                            onClick = { onGenerateFastHooks(clip) },
                            enabled = aiState.hookSuggestions !is AsyncOpState.Loading,
                            modifier = Modifier.height(48.dp).testTag("quick_hooks_button")
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (aiState.hookSuggestions is AsyncOpState.Loading) "Thinking..."
                                else "Fast AI Hooks (Gemini 3.1 Flash Lite)"
                            )
                        }

                        when (val hooksState = aiState.hookSuggestions) {
                            is AsyncOpState.Success -> {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    hooksState.data.forEach { hook ->
                                        FilterChip(
                                            selected = hookDraft == hook,
                                            onClick = {
                                                hookDraft = hook
                                                onUpdateClip(clip, null, hook, null, null, null, null)
                                            },
                                            label = { Text(hook) }
                                        )
                                    }
                                }
                            }
                            is AsyncOpState.Error -> Text(hooksState.message, color = CrimsonLive, style = MaterialTheme.typography.bodyMedium)
                            else -> Unit
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            HookBannerStyle.entries.forEach { style ->
                                FilterChip(
                                    selected = currentBannerStyle == style,
                                    onClick = { onUpdateClip(clip, null, null, style, null, null, null) },
                                    label = { Text(style.label) }
                                )
                            }
                        }

                        OutlinedTextField(
                            value = creditDraft,
                            onValueChange = {
                                creditDraft = it
                                onUpdateClip(clip, null, null, null, it, null, null)
                            },
                            label = { Text("Creator Credit Watermark") },
                            modifier = Modifier.fillMaxWidth().testTag("credit_watermark_input")
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Mini Live Chat Replay Overlay", style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                            Switch(
                                checked = clip.chatOverlayEnabled,
                                onCheckedChange = { enabled ->
                                    onUpdateClip(clip, null, null, null, null, enabled, null)
                                },
                                modifier = Modifier.testTag("chat_overlay_switch")
                            )
                        }

                        OutlinedTextField(
                            value = transcriptDraft,
                            onValueChange = {
                                transcriptDraft = it
                                onUpdateClip(clip, null, null, null, null, null, it)
                            },
                            label = { Text("Word-by-Word Captions Script") },
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth().testTag("caption_transcript_input")
                        )

                        // Microphone Audio Transcription (gemini-3.5-transcribe)
                        OutlinedButton(
                            onClick = {
                                if (aiState.isRecordingMic) {
                                    onStopMicRecording()
                                } else {
                                    val granted = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (granted) onStartMicRecording()
                                    else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("studio_mic_transcribe_button")
                        ) {
                            Icon(
                                imageVector = if (aiState.isRecordingMic) Icons.Default.Stop else Icons.Default.Mic,
                                contentDescription = null,
                                tint = if (aiState.isRecordingMic) CrimsonLive else ElectricVolt
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                when {
                                    aiState.isRecordingMic -> "Stop Recording & Transcribe (3.5 Transcribe)"
                                    aiState.transcription is AsyncOpState.Loading -> "Transcribing Voiceover..."
                                    else -> "Dictate Captions (Mic -> Gemini 3.5 Transcribe)"
                                }
                            )
                        }
                        if (aiState.transcription is AsyncOpState.Error) {
                            Text(aiState.transcription.message, color = CrimsonLive, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            // Gemini 4 Argon Video & Image Understanding Lab
            item {
                Surface(
                    color = BroadcastSlate,
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, SteelOutline)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Multimodal Inspector (Gemini 4 Argon)", style = MaterialTheme.typography.titleMedium, color = CyberCyan)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val local = effectivePlaybackUri
                                    if (local != null && (local.scheme == "content" || local.scheme == "file")) {
                                        scope.launch {
                                            val bytes = withContext(Dispatchers.IO) { readCappedUriBytes(context.contentResolver, local) }
                                            if (bytes.isNotEmpty()) {
                                                onAnalyzeClipVideo(bytes, clip.title)
                                            }
                                        }
                                    } else {
                                        videoPickerForArgonLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                        )
                                    }
                                },
                                enabled = aiState.videoAnalysis !is AsyncOpState.Loading,
                                modifier = Modifier.weight(1f).height(48.dp).testTag("analyze_video_argon_button")
                            ) {
                                Icon(Icons.Default.VideoFile, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (aiState.videoAnalysis is AsyncOpState.Loading) "Analyzing..." else "Analyze Video")
                            }

                            OutlinedButton(
                                onClick = {
                                    imagePickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                enabled = aiState.imageAnalysis !is AsyncOpState.Loading,
                                modifier = Modifier.weight(1f).height(48.dp).testTag("analyze_image_argon_button")
                            ) {
                                Icon(Icons.Default.ImageSearch, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (aiState.imageAnalysis is AsyncOpState.Loading) "Scanning..." else "Critique Photo")
                            }
                        }

                        when (val va = aiState.videoAnalysis) {
                            is AsyncOpState.Success -> Text(va.data, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                            is AsyncOpState.Error -> Text(va.message, style = MaterialTheme.typography.bodyMedium, color = CrimsonLive)
                            else -> Unit
                        }

                        if (pickedImagePreview != null) {
                            Image(
                                bitmap = pickedImagePreview!!.asImageBitmap(),
                                contentDescription = "Uploaded thumbnail frame for Gemini 4 Argon critique",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(160.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.Crop
                            )
                        }
                        when (val ia = aiState.imageAnalysis) {
                            is AsyncOpState.Success -> Text(ia.data, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                            is AsyncOpState.Error -> Text(ia.message, style = MaterialTheme.typography.bodyMedium, color = CrimsonLive)
                            else -> Unit
                        }
                    }
                }
            }

            // AI Shorts Cover / Thumbnail Studio (1K/2K/4K + 8 Aspect Ratios)
            item {
                Surface(
                    color = BroadcastSlate,
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, SteelOutline)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("AI Cover & Thumbnail Studio", style = MaterialTheme.typography.titleMedium, color = ElectricVolt)

                        OutlinedTextField(
                            value = coverPrompt,
                            onValueChange = { coverPrompt = it },
                            label = { Text("Cover Art Prompt") },
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth().testTag("cover_prompt_input")
                        )

                        Text("Image Model", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ImageModelOption.entries.forEach { m ->
                                FilterChip(
                                    selected = aiState.imageGenModel == m,
                                    onClick = { onUpdateImageGenOptions(m, null, null) },
                                    label = { Text(m.label) }
                                )
                            }
                        }

                        Text("Resolution (1K • 2K • 4K)", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ImageSizeOption.entries.forEach { sz ->
                                FilterChip(
                                    selected = aiState.imageSize == sz,
                                    onClick = { onUpdateImageGenOptions(null, null, sz) },
                                    label = { Text(sz.label) },
                                    modifier = Modifier.testTag("cover_size_${sz.label.lowercase()}")
                                )
                            }
                        }

                        Text("Aspect Ratio", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ImageAspectOption.entries.forEach { ratio ->
                                FilterChip(
                                    selected = aiState.imageAspect == ratio,
                                    onClick = { onUpdateImageGenOptions(null, ratio, null) },
                                    label = { Text(ratio.label) },
                                    modifier = Modifier.testTag("cover_ratio_${ratio.label.replace(":", "x")}")
                                )
                            }
                        }

                        Button(
                            onClick = { onGenerateCoverImage(coverPrompt) },
                            enabled = aiState.generatedCover !is AsyncOpState.Loading,
                            colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = ObsidianInk),
                            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("generate_cover_button")
                        ) {
                            Text("Generate ${aiState.imageSize.label} (${aiState.imageAspect.label}) Cover")
                        }

                        when (val gen = aiState.generatedCover) {
                            is AsyncOpState.Loading -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = CyberCyan, strokeWidth = 2.dp)
                                    Spacer(Modifier.width(10.dp))
                                    Text("Generating studio cover (may take 30–60s)...", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                                }
                            }
                            is AsyncOpState.Success -> {
                                Image(
                                    bitmap = gen.data.asImageBitmap(),
                                    contentDescription = "AI Generated Shorts Cover",
                                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)),
                                    contentScale = ContentScale.Fit
                                )
                            }
                            is AsyncOpState.Error -> {
                                Text(gen.message, color = CrimsonLive, style = MaterialTheme.typography.bodyMedium)
                            }
                            is AsyncOpState.Idle -> Unit
                        }
                    }
                }
            }
        }
    }
}

private fun readCappedUriBytes(
    resolver: android.content.ContentResolver,
    uri: Uri,
    maxBytes: Int = 18 * 1024 * 1024
): ByteArray {
    return try {
        resolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (total < maxBytes) {
                val read = input.read(buf, 0, minOf(buf.size, maxBytes - total))
                if (read <= 0) break
                out.write(buf, 0, read)
                total += read
            }
            out.toByteArray()
        } ?: ByteArray(0)
    } catch (_: Exception) {
        ByteArray(0)
    }
}
