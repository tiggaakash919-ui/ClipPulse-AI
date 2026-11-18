package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.model.PipelineMetrics
import com.example.data.model.ViralClip
import com.example.engine.FfmpegCommandBuilder
import com.example.ui.theme.BroadcastSlate
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.ElectricVolt
import com.example.ui.theme.ElevatedCard
import com.example.ui.theme.NeonMint
import com.example.ui.theme.SteelOutline

@Composable
fun InspectorScreen(
    clip: ViralClip?,
    pipeline: PipelineMetrics,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var copiedBanner by remember { mutableStateOf<String?>(null) }

    fun copyText(label: String, value: String) {
        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText(label, value))
        copiedBanner = "Copied $label to clipboard"
    }

    val extractCmd = pipeline.ffmpegExtractCommand.ifBlank {
        "ffmpeg -y -ss 00:07:00 -to 00:07:35 -i \"stream.m3u8\" -c copy \"cache/clip_raw.mp4\""
    }
    val renderCmd = pipeline.ffmpegRenderCommand.ifBlank {
        "ffmpeg -y -i \"cache/clip_raw.mp4\" -filter_complex \"[0:v]crop=ih*9/16:ih:(iw-ow)/2:0,scale=1080:1920,ass='captions.ass'[v]\" -map \"[v]\" -map 0:a? -r 30 -c:v libx264 -crf 20 -preset veryfast -c:a aac -b:a 192k \"cache/short_9x16.mp4\""
    }
    val audioCmd = FfmpegCommandBuilder.buildAudioStatsCommand("cache/clip_raw.mp4")

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = BroadcastSlate),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, SteelOutline, RoundedCornerShape(18.dp))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("ON-DEVICE BYTE-RANGE & HLS PIPELINE", style = MaterialTheme.typography.labelMedium, color = CyberCyan)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Estimated Full VOD", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${pipeline.sourceBytesEstimate / (1024 * 1024)} MB", style = MaterialTheme.typography.labelMedium)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Surgical Window Fetched", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${pipeline.downloadedBytes / 1024} KB", style = MaterialTheme.typography.labelMedium, color = ElectricVolt)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Bandwidth Saved", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("%.1f%%".format(pipeline.savedPercent), style = MaterialTheme.typography.labelMedium, color = NeonMint)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Active Preset", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(clip?.layoutPresetEnum()?.label ?: "Word-by-Word Pop", style = MaterialTheme.typography.labelMedium)
                    }
                    if (copiedBanner != null) {
                        Text(copiedBanner.orEmpty(), style = MaterialTheme.typography.labelSmall, color = NeonMint)
                    }
                }
            }
        }

        item {
            CodeBlockCard(
                title = "1. SURGICAL INPUT-SEEKING EXTRACTION",
                code = extractCmd,
                testTag = "copy_extract_cmd",
                onCopy = { copyText("Extraction CLI", extractCmd) }
            )
        }

        item {
            CodeBlockCard(
                title = "2. AUDIO RMS ENERGY FILTERGRAPH (astats)",
                code = audioCmd,
                testTag = "copy_astats_cmd",
                onCopy = { copyText("Audio Stats CLI", audioCmd) }
            )
        }

        item {
            CodeBlockCard(
                title = "3. 9:16 RENDER FILTERGRAPH (1080x1920 H.264)",
                code = renderCmd,
                testTag = "copy_render_cmd",
                onCopy = { copyText("Render Filtergraph", renderCmd) }
            )
        }

        item {
            val ass = pipeline.assSubtitleScript.ifBlank {
                "Select a clip in Analyze or Studio to inspect its generated .ass Hormozi subtitle script."
            }
            CodeBlockCard(
                title = "4. GENERATED .ASS WORD-BY-WORD SUBTITLES",
                code = ass,
                testTag = "copy_ass_script",
                onCopy = { copyText(".ASS Subtitles", ass) }
            )
        }
    }
}

@Composable
private fun CodeBlockCard(
    title: String,
    code: String,
    testTag: String,
    onCopy: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = BroadcastSlate),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, SteelOutline, RoundedCornerShape(18.dp))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.labelMedium, color = ElectricVolt, modifier = Modifier.weight(1f))
                OutlinedButton(
                    onClick = onCopy,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.testTag(testTag)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy $title", modifier = Modifier.size(14.dp), tint = CyberCyan)
                    Spacer(Modifier.width(4.dp))
                    Text("Copy", style = MaterialTheme.typography.labelSmall, color = CyberCyan)
                }
            }
            Text(
                text = code,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(ElevatedCard)
                    .padding(12.dp)
            )
        }
    }
}
