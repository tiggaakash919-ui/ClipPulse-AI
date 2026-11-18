package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.example.data.model.SpikeBucket
import com.example.engine.ViralMomentEngine
import com.example.ui.theme.BroadcastSlate
import com.example.ui.theme.CrimsonLive
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.ElectricVolt
import com.example.ui.theme.ElevatedCard
import com.example.ui.theme.ObsidianInk
import com.example.ui.theme.SteelOutline
import kotlin.math.max

@Composable
fun SpikeGraphCard(
    buckets: List<SpikeBucket>,
    selectedTimestampSec: Long?,
    onSelectBucket: (SpikeBucket) -> Unit,
    modifier: Modifier = Modifier
) {
    val peakBucket = buckets.maxByOrNull { it.spikeMultiplier }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(BroadcastSlate)
            .border(1.dp, SteelOutline, RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "CHAT & AUDIO SPIKE GRAPH",
                    style = MaterialTheme.typography.labelMedium,
                    color = CyberCyan
                )
                Text(
                    text = if (buckets.isEmpty()) "Analyze a stream or video to plot spikes" else "${buckets.size} rolling buckets",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (peakBucket != null) {
                Surface(
                    color = ElectricVolt,
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Text(
                        text = "${peakBucket.spikeMultiplier}x NORMAL",
                        style = MaterialTheme.typography.labelLarge,
                        color = ObsidianInk,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(ElevatedCard)
        ) {
            if (buckets.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No telemetry buckets yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 10.dp, vertical = 14.dp)
                        .pointerInput(buckets) {
                            detectTapGestures { offset ->
                                if (buckets.isNotEmpty() && size.width > 0) {
                                    val idx = ((offset.x / size.width) * buckets.size)
                                        .toInt()
                                        .coerceIn(0, buckets.lastIndex)
                                    onSelectBucket(buckets[idx])
                                }
                            }
                        }
                ) {
                    val maxMult = max(buckets.maxOf { it.spikeMultiplier }, 4.0).toFloat()
                    val stepX = if (buckets.size <= 1) size.width else size.width / (buckets.size - 1).toFloat()

                    // Baseline threshold line (4.0x DVR trigger)
                    val thresholdY = size.height * (1f - (4f / maxMult).coerceIn(0f, 1f))
                    drawLine(
                        color = CrimsonLive.copy(alpha = 0.45f),
                        start = Offset(0f, thresholdY),
                        end = Offset(size.width, thresholdY),
                        strokeWidth = 2f
                    )

                    val spikePath = Path()
                    val heatPath = Path()
                    buckets.forEachIndexed { idx, bucket ->
                        val x = idx * stepX
                        val ySpike = size.height * (1f - (bucket.spikeMultiplier.toFloat() / maxMult).coerceIn(0.04f, 1f))
                        val yHeat = size.height * (1f - bucket.heatmapIntensity.toFloat().coerceIn(0.02f, 1f))
                        if (idx == 0) {
                            spikePath.moveTo(x, ySpike)
                            heatPath.moveTo(x, yHeat)
                        } else {
                            spikePath.lineTo(x, ySpike)
                            heatPath.lineTo(x, yHeat)
                        }
                    }

                    // Heatmap secondary trace
                    drawPath(
                        path = heatPath,
                        color = CyberCyan.copy(alpha = 0.55f),
                        style = Stroke(width = 3.5f, cap = StrokeCap.Round)
                    )

                    // Primary Spike Multiplier curve
                    drawPath(
                        path = spikePath,
                        brush = Brush.horizontalGradient(listOf(ElectricVolt, CrimsonLive, ElectricVolt)),
                        style = Stroke(width = 6f, cap = StrokeCap.Round)
                    )

                    // Highlight peak dots >= 2.5x or selected
                    buckets.forEachIndexed { idx, bucket ->
                        val x = idx * stepX
                        val y = size.height * (1f - (bucket.spikeMultiplier.toFloat() / maxMult).coerceIn(0.04f, 1f))
                        val isSelected = selectedTimestampSec != null &&
                            kotlin.math.abs(bucket.timestampSec - selectedTimestampSec) <= 15L
                        if (bucket.spikeMultiplier >= 2.5 || isSelected) {
                            drawCircle(
                                color = if (isSelected) Color.White else ElectricVolt,
                                radius = if (isSelected) 11f else 7f,
                                center = Offset(x, y)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LegendDot(color = ElectricVolt, label = "Spike Score")
            LegendDot(color = CyberCyan, label = "Most Replayed")
            LegendDot(color = CrimsonLive, label = "4.0x DVR Trigger")
            Spacer(modifier = Modifier.weight(1f))
            if (peakBucket != null) {
                Text(
                    text = "Peak @ ${ViralMomentEngine.formatTimestamp(peakBucket.timestampSec)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
