package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.IngestMode
import com.example.data.model.ViralClip
import com.example.engine.ViralMomentEngine
import com.example.ui.components.SpikeGraphCard
import com.example.ui.theme.BroadcastSlate
import com.example.ui.theme.CrimsonLive
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.ElectricVolt
import com.example.ui.theme.ElevatedCard
import com.example.ui.theme.NeonMint
import com.example.ui.theme.ObsidianInk
import com.example.ui.theme.SteelOutline
import com.example.ui.theme.StudioObsidian
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.viewmodel.AiStudioUiState
import com.example.ui.viewmodel.AnalyzerUiState
import com.example.ui.viewmodel.AsyncOpState
import kotlin.math.abs

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnalyzeScreen(
    state: AnalyzerUiState,
    cloudClips: List<ViralClip>,
    aiState: AiStudioUiState,
    onUrlChange: (String) -> Unit,
    onModeChange: (IngestMode) -> Unit,
    onAnalyzeClick: () -> Unit,
    onToggleLiveMonitor: () -> Unit,
    onLocalVideoPicked: (Uri) -> Unit,
    onUpdateBucketSec: (Int) -> Unit,
    onUpdateBeforeSec: (Int) -> Unit,
    onUpdateAfterSec: (Int) -> Unit,
    onUpdateGapSec: (Int) -> Unit,
    onUpdateTopCount: (Int) -> Unit,
    onSelectClip: (ViralClip) -> Unit,
    onOpenClipInStudio: (ViralClip) -> Unit,
    onRunDeepRetentionAudit: (ViralClip) -> Unit
) {
    var showParams by remember { mutableStateOf(false) }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) onLocalVideoPicked(uri)
    }

    val mergedClips = remember(state.detectedClips, cloudClips) {
        val seen = HashSet<String>()
        buildList {
            for (c in state.detectedClips) {
                if (seen.add(c.id)) add(c)
            }
            for (c in cloudClips) {
                if (seen.add(c.id)) add(c)
            }
        }
    }
    val selectedClip = mergedClips.firstOrNull { it.id == state.selectedClipId }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 840.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Hero Studio Header Card
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(128.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .border(1.dp, SteelOutline, RoundedCornerShape(20.dp))
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.img_studio_hero_1795006681398),
                        contentDescription = "ClipPulse AI viral clipping studio banner",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.horizontalGradient(
                                    listOf(StudioObsidian.copy(alpha = 0.92f), StudioObsidian.copy(alpha = 0.55f))
                                )
                            )
                    )
                    Column(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .clip(CircleShape)
                                    .background(if (state.isLiveMonitoring) CrimsonLive else ElectricVolt)
                            )
                            Text(
                                text = if (state.isLiveMonitoring) "LIVE ROLLING DVR ACTIVE" else "4-LAYER VIRAL ENGINE",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (state.isLiveMonitoring) CrimsonLive else ElectricVolt
                            )
                        }
                        Text(
                            text = "Source Analyzer & Live Monitor",
                            style = MaterialTheme.typography.headlineMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = "YouTube • Twitch • Kick • HLS .m3u8 • Device Video",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                }
            }

            // Source Ingestion Card
            item {
                Surface(
                    color = BroadcastSlate,
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, SteelOutline)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            IngestMode.entries.forEach { mode ->
                                val selected = state.ingestMode == mode
                                FilterChip(
                                    selected = selected,
                                    onClick = { onModeChange(mode) },
                                    label = { Text(mode.label, style = MaterialTheme.typography.labelLarge) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (mode == IngestMode.LIVE_DVR) Icons.Default.LiveTv else Icons.Default.PlayArrow,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = if (mode == IngestMode.LIVE_DVR) CrimsonLive else ElectricVolt,
                                        selectedLabelColor = if (mode == IngestMode.LIVE_DVR) TextPrimary else ObsidianInk,
                                        selectedLeadingIconColor = if (mode == IngestMode.LIVE_DVR) TextPrimary else ObsidianInk
                                    ),
                                    modifier = Modifier.testTag("mode_${mode.name.lowercase()}")
                                )
                            }
                        }

                        OutlinedTextField(
                            value = state.sourceUrl,
                            onValueChange = onUrlChange,
                            label = { Text("Paste Video / Stream URL or .m3u8") },
                            placeholder = { Text("https://youtube.com/watch?v=... or twitch.tv/...") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("source_url_input")
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = onAnalyzeClick,
                                enabled = !state.isAnalyzing,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = ElectricVolt,
                                    contentColor = ObsidianInk
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("analyze_source_button")
                            ) {
                                if (state.isAnalyzing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        color = ObsidianInk,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("Scanning...")
                                } else {
                                    Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Detect Viral Spikes")
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    videoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                    )
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("pick_local_video_button")
                            ) {
                                Icon(Icons.Default.VideoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Device Video")
                            }
                        }

                        if (state.ingestMode == IngestMode.LIVE_DVR) {
                            Surface(
                                color = CrimsonLive.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, CrimsonLive.copy(alpha = 0.4f))
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Rolling DVR Buffer (-20s / +10s) • ${state.liveMultiplier}x",
                                                style = MaterialTheme.typography.titleMedium,
                                                color = CrimsonLive
                                            )
                                            Text(
                                                text = state.liveStatus,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = TextSecondary
                                            )
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Button(
                                            onClick = onToggleLiveMonitor,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (state.isLiveMonitoring) ElevatedCard else CrimsonLive,
                                                contentColor = TextPrimary
                                            ),
                                            modifier = Modifier
                                                .height(48.dp)
                                                .testTag("toggle_live_monitor_button")
                                        ) {
                                            Icon(Icons.Default.RadioButtonChecked, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text(if (state.isLiveMonitoring) "Stop" else "Go Live")
                                        }
                                    }
                                    if (state.liveChatFeed.isNotEmpty()) {
                                        Text(
                                            text = state.liveChatFeed.take(3).joinToString("  •  "),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextSecondary,
                                            maxLines = 2
                                        )
                                    }
                                }
                            }
                        }

                        // Expandable Detection Parameters
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { showParams = !showParams }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.Tune, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(18.dp))
                                Text(
                                    text = "Algorithm Tuning (--top ${state.config.topCount} • --before ${state.config.beforeSec}s • --gap ${state.config.gapSec}s)",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = CyberCyan
                                )
                            }
                            Icon(
                                imageVector = if (showParams) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = "Toggle algorithm parameters",
                                tint = TextSecondary
                            )
                        }

                        AnimatedVisibility(visible = showParams) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                ParameterSlider(
                                    label = "Top Ranked Clips (--top)",
                                    valueText = "${state.config.topCount}",
                                    value = state.config.topCount.toFloat(),
                                    range = 1f..12f,
                                    onValueChange = { onUpdateTopCount(it.toInt()) }
                                )
                                ParameterSlider(
                                    label = "Reaction Offset Before (--before)",
                                    valueText = "${state.config.beforeSec}s",
                                    value = state.config.beforeSec.toFloat(),
                                    range = 5f..45f,
                                    onValueChange = { onUpdateBeforeSec(it.toInt()) }
                                )
                                ParameterSlider(
                                    label = "Reaction Tail After (--after)",
                                    valueText = "${state.config.afterSec}s",
                                    value = state.config.afterSec.toFloat(),
                                    range = 5f..30f,
                                    onValueChange = { onUpdateAfterSec(it.toInt()) }
                                )
                                ParameterSlider(
                                    label = "De-duplication Gap (--gap)",
                                    valueText = "${state.config.gapSec}s",
                                    value = state.config.gapSec.toFloat(),
                                    range = 30f..180f,
                                    onValueChange = { onUpdateGapSec(it.toInt()) }
                                )
                                ParameterSlider(
                                    label = "Bucket Size (--bucket)",
                                    valueText = "${state.config.bucketSec}s",
                                    value = state.config.bucketSec.toFloat(),
                                    range = 5f..15f,
                                    onValueChange = { onUpdateBucketSec(it.toInt()) }
                                )
                            }
                        }

                        if (!state.errorMessage.isNullOrBlank()) {
                            Text(
                                text = state.errorMessage,
                                color = CrimsonLive,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        } else if (state.resolvedInfo?.notes != null) {
                            Text(
                                text = state.resolvedInfo.notes,
                                color = NeonMint,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }

            // Interactive Chat & Audio Spike Graph
            item {
                SpikeGraphCard(
                    buckets = state.spikeBuckets,
                    selectedTimestampSec = selectedClip?.startSec,
                    onSelectBucket = { bucket ->
                        val nearest = mergedClips.minByOrNull { abs(it.startSec - bucket.timestampSec) }
                        if (nearest != null) onSelectClip(nearest)
                    }
                )
            }

            // Gemini 4 Carbon High-Thinking Audit Card
            if (aiState.retentionAudit !is AsyncOpState.Idle) {
                item {
                    Surface(
                        color = ElevatedCard,
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, CyberCyan.copy(alpha = 0.45f))
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.Psychology, contentDescription = null, tint = CyberCyan)
                                Text(
                                    text = "Gemini 4 Carbon (High Thinking) Retention Audit",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = CyberCyan
                                )
                            }
                            when (val auditState = aiState.retentionAudit) {
                                is AsyncOpState.Loading -> {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = CyberCyan)
                                        Spacer(Modifier.width(10.dp))
                                        Text("Deep-scoring hook strength, payoff & dead-air...", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                                    }
                                }
                                is AsyncOpState.Error -> {
                                    Text(auditState.message, color = CrimsonLive, style = MaterialTheme.typography.bodyMedium)
                                }
                                is AsyncOpState.Success -> {
                                    val audit = auditState.data
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        ScoreBadge("Score ${audit.score}/100", ElectricVolt)
                                        ScoreBadge("Hook: ${audit.hookStrength}", CyberCyan)
                                        ScoreBadge("Payoff: ${audit.emotionalPayoff}", NeonMint)
                                    }
                                    Text("Clarity: ${audit.standaloneClarity}", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                                    Text("Trim Advice: ${audit.trimAdvice}", style = MaterialTheme.typography.bodyMedium, color = ElectricVolt)
                                }
                                is AsyncOpState.Idle -> Unit
                            }
                        }
                    }
                }
            }

            // Ranked Viral Clips List
            item {
                Text(
                    text = "Detected Viral Moments (${mergedClips.size})",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )
            }

            if (mergedClips.isEmpty()) {
                item {
                    Surface(
                        color = BroadcastSlate,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, SteelOutline)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Bolt, contentDescription = null, tint = TextMuted, modifier = Modifier.size(34.dp))
                            Text(
                                text = "No viral clips staged yet",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary
                            )
                            Text(
                                text = "Paste a stream URL or choose a video from your device above to detect peak moments.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                    }
                }
            } else {
                items(mergedClips, key = { it.id }) { clip ->
                    val isSelected = clip.id == selectedClip?.id
                    Surface(
                        color = if (isSelected) ElevatedCard else BroadcastSlate,
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(
                            width = if (isSelected) 1.5.dp else 1.dp,
                            color = if (isSelected) ElectricVolt else SteelOutline
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectClip(clip) }
                            .testTag("clip_card_${clip.id}")
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(clip.title, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                                    Text(
                                        text = "${ViralMomentEngine.formatTimestamp(clip.startSec)} → ${ViralMomentEngine.formatTimestamp(clip.endSec)} (${clip.durationSec()}s) • ${clip.streamerHandle}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = TextSecondary
                                    )
                                }
                                ScoreBadge("${clip.spikeMultiplier}x", ElectricVolt)
                            }

                            Text(
                                text = "Hook: ${clip.hookBannerText} • Score: ${clip.viralScore}/100 • Status: ${clip.status}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = CyberCyan
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { onOpenClipInStudio(clip) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = ElectricVolt,
                                        contentColor = ObsidianInk
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("open_studio_${clip.id}")
                                ) {
                                    Text("Edit 9:16", style = MaterialTheme.typography.labelLarge)
                                }
                                OutlinedButton(
                                    onClick = { onRunDeepRetentionAudit(clip) },
                                    modifier = Modifier
                                        .height(48.dp)
                                        .testTag("deep_audit_${clip.id}")
                                ) {
                                    Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Deep Audit")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ParameterSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            Text(valueText, style = MaterialTheme.typography.labelMedium, color = ElectricVolt)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = ElectricVolt,
                activeTrackColor = ElectricVolt
            )
        )
    }
}

@Composable
private fun ScoreBadge(text: String, accent: Color) {
    Surface(
        color = accent.copy(alpha = 0.16f),
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.5f))
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = accent,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}
