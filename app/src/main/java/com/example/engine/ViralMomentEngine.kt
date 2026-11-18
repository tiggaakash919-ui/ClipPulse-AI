package com.example.engine

import com.example.data.model.CaptionWord
import com.example.data.model.DetectionConfig
import com.example.data.model.HookBannerStyle
import com.example.data.model.LayoutPreset
import com.example.data.model.SpikeBucket
import com.example.data.model.ViralClip
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 4-Layer Hybrid Viral Moment Detection Engine:
 * Layer A: Chat Velocity & Emote Burst (chat-clips.mjs port: bucket slicing, 300s rolling baseline, +1.35x hype weighting, reaction offset, dedup gap)
 * Layer B: YouTube Most Replayed heatmap intensity local maxima (> 0.75)
 * Layer C: On-device Audio RMS decibel envelope peaks
 * Layer D: AI Retention Scoring hook (refined by Gemini 4 Carbon / Argon)
 */
object ViralMomentEngine {

    private val hypeKeywords = listOf(
        "LMAO", "OMEGALUL", "WTF", "CLIP IT", "NO WAY",
        "💀", "😭", "🔥", "W", "BRO", "INSANE", "AINTNOWAY"
    )

    data class RawChatMessage(
        val timestampSec: Long,
        val text: String
    )

    data class AudioEnergyPoint(
        val timestampSec: Long,
        val rmsDb: Double
    )

    fun detectMoments(
        projectId: String,
        streamerHandle: String,
        durationSec: Long,
        config: DetectionConfig,
        chatMessages: List<RawChatMessage>,
        heatmapMarkers: Map<Long, Double>,
        audioPeaks: List<AudioEnergyPoint>
    ): Pair<List<SpikeBucket>, List<ViralClip>> {
        val bucketSec = config.bucketSec.coerceIn(5, 30)
        val safeDuration = max(durationSec, bucketSec.toLong() * 3L)
        val totalBuckets = ((safeDuration + bucketSec - 1) / bucketSec).toInt().coerceAtLeast(1)

        val messagesByBucket = chatMessages.groupBy { (it.timestampSec.coerceAtLeast(0L) / bucketSec).toInt() }
        val rawCounts = IntArray(totalBuckets) { idx -> messagesByBucket[idx]?.size ?: 0 }

        val buckets = ArrayList<SpikeBucket>(totalBuckets)
        val rollingRadiusBuckets = max(1, (150 / bucketSec)) // +/-150s => 300s rolling window

        val avgAudioDb = audioPeaks.map { it.rmsDb }.takeIf { it.isNotEmpty() }?.average() ?: -24.0

        for (i in 0 until totalBuckets) {
            val ts = i.toLong() * bucketSec
            val startWin = max(0, i - rollingRadiusBuckets)
            val endWin = min(totalBuckets - 1, i + rollingRadiusBuckets)
            var sum = 0.0
            var count = 0
            for (j in startWin..endWin) {
                sum += rawCounts[j]
                count++
            }
            val localAvg = max(sum / max(1, count), 1.0)
            val msgs = messagesByBucket[i].orEmpty()

            val matchedHype = hypeKeywords.filter { kw ->
                msgs.any { it.text.uppercase().contains(kw) }
            }
            val hypeMultiplier = if (matchedHype.isNotEmpty()) 1.35 else 1.0
            val chatSpike = (rawCounts[i] / localAvg) * hypeMultiplier

            val nearestHeat = heatmapMarkers.entries
                .minByOrNull { abs(it.key - ts) }
                ?.takeIf { abs(it.key - ts) <= bucketSec * 2L }
                ?.value ?: 0.0

            val bucketAudio = audioPeaks
                .filter { it.timestampSec in ts until (ts + bucketSec) }
                .maxOfOrNull { it.rmsDb } ?: avgAudioDb

            val audioLift = ((bucketAudio - avgAudioDb) / 6.0).coerceIn(0.0, 3.0)
            val heatmapBoost = if (nearestHeat > 0.75) nearestHeat * 4.5 else nearestHeat * 1.5
            val fusedMultiplier = max(chatSpike, max(heatmapBoost, 1.0 + audioLift))

            buckets.add(
                SpikeBucket(
                    timestampSec = ts,
                    chatCount = rawCounts[i],
                    rollingAvg = localAvg,
                    spikeMultiplier = (fusedMultiplier * 10.0).toInt() / 10.0,
                    heatmapIntensity = nearestHeat,
                    audioDbPeak = bucketAudio,
                    hypeTags = matchedHype.take(4),
                    sampleMessages = msgs.take(5).map { it.text }
                )
            )
        }

        // Rank & de-duplicate with minimum gapSec
        val sortedCandidates = buckets.sortedByDescending { it.spikeMultiplier }
        val selected = mutableListOf<SpikeBucket>()
        for (candidate in sortedCandidates) {
            if (selected.size >= config.topCount) break
            val tooClose = selected.any { abs(it.timestampSec - candidate.timestampSec) < config.gapSec }
            if (!tooClose && candidate.spikeMultiplier >= 1.2) {
                selected.add(candidate)
            }
        }

        if (selected.isEmpty() && buckets.isNotEmpty()) {
            selected.add(buckets.maxByOrNull { it.audioDbPeak } ?: buckets.first())
        }

        val clips = selected.mapIndexed { idx, spike ->
            // Reaction offset compensation: clipStart = spikeTs - before, clipEnd = spikeTs + after
            val clipStart = (spike.timestampSec - config.beforeSec).coerceAtLeast(0L)
            val clipEnd = (spike.timestampSec + config.afterSec).coerceAtLeast(clipStart + 15L)
                .coerceAtMost(max(safeDuration, clipStart + 15L))

            val score = (48 + (spike.spikeMultiplier * 6.5) + (spike.heatmapIntensity * 18.0))
                .toInt().coerceIn(55, 99).toLong()

            val hookText = when {
                spike.spikeMultiplier >= 5.0 -> "${spike.spikeMultiplier}x CHAT SPIKE 🔥"
                spike.hypeTags.contains("WTF") -> "NO WAY THIS HAPPENED 💀"
                spike.heatmapIntensity >= 0.75 -> "MOST REPLAYED MOMENT 👀"
                else -> "WAIT FOR THE END ⚡"
            }

            val handleClean = if (streamerHandle.startsWith("@")) streamerHandle else "@$streamerHandle"
            val tagSlug = handleClean.removePrefix("@").lowercase().replace(Regex("[^a-z0-9]"), "")

            ViralClip(
                id = "local_${idx + 1}",
                projectId = projectId,
                title = "Moment #${idx + 1} (${formatTimestamp(spike.timestampSec)})",
                streamerHandle = handleClean,
                startSec = clipStart,
                endSec = clipEnd,
                spikeMultiplier = spike.spikeMultiplier,
                viralScore = score,
                layoutPreset = LayoutPreset.WORD_POP.name,
                hookBannerText = hookText,
                hookBannerStyle = HookBannerStyle.VOLT_PILL.name,
                creditWatermark = "Credit: $handleClean",
                chatOverlayEnabled = true,
                seoTitle = "Wait till the end on $handleClean stream #shorts",
                seoDescription = "Did you catch what happened at 0:${(clipEnd - clipStart) / 2}? 👇\n" +
                    "Credit: $handleClean\n" +
                    "Clipped on-device with ClipPulse AI.\n" +
                    "#shorts #viral #$tagSlug #streamclips #gaming",
                seoTags = listOf(
                    "$tagSlug clips",
                    "stream highlights",
                    "viral shorts",
                    "gaming moments",
                    "twitch clips"
                ),
                pinnedComment = "Be honest — did you expect that ending? 👇",
                transcriptPreview = buildDefaultTranscript(hookText, handleClean)
            )
        }

        return buckets to clips
    }

    fun evaluateRollingDvrWindow(
        recentMessages: List<RawChatMessage>,
        nowSec: Long,
        streamerHandle: String,
        projectId: String
    ): Pair<Double, ViralClip?> {
        val currentBucket = recentMessages.count { it.timestampSec in (nowSec - 10)..nowSec }
        val baselineMessages = recentMessages.count { it.timestampSec in (nowSec - 300) until (nowSec - 10) }
        val baselinePer10s = max(baselineMessages / 29.0, 1.0)
        val hasHype = recentMessages
            .filter { it.timestampSec in (nowSec - 10)..nowSec }
            .any { msg -> hypeKeywords.any { kw -> msg.text.uppercase().contains(kw) } }
        val multiplier = ((currentBucket / baselinePer10s) * (if (hasHype) 1.35 else 1.0))

        return if (multiplier >= 4.0) {
            val clipStart = (nowSec - 20L).coerceAtLeast(0L)
            val clipEnd = nowSec + 10L
            val handleClean = if (streamerHandle.startsWith("@")) streamerHandle else "@$streamerHandle"
            val rounded = (multiplier * 10.0).toInt() / 10.0
            rounded to ViralClip(
                id = "dvr_$nowSec",
                projectId = projectId,
                title = "LIVE DVR SPIKE (${rounded}x)",
                streamerHandle = handleClean,
                startSec = clipStart,
                endSec = clipEnd,
                spikeMultiplier = rounded,
                viralScore = (65 + (rounded * 4)).toInt().coerceIn(70, 99).toLong(),
                layoutPreset = LayoutPreset.WORD_POP.name,
                hookBannerText = "${rounded}x LIVE SPIKE 🔴",
                hookBannerStyle = HookBannerStyle.CRIMSON_ALERT.name,
                creditWatermark = "Credit: $handleClean",
                chatOverlayEnabled = true,
                seoTitle = "LIVE right now on $handleClean! #shorts",
                seoDescription = "Caught live in Rolling DVR mode!\nCredit: $handleClean\n#shorts #livestream #viral",
                seoTags = listOf("live clips", "stream highlights", "shorts"),
                pinnedComment = "Caught live! Drop a 🔥 if you saw this happen!",
                transcriptPreview = buildDefaultTranscript("${rounded}x LIVE SPIKE", handleClean)
            )
        } else {
            ((multiplier * 10.0).toInt() / 10.0) to null
        }
    }

    fun generateWordTimings(transcript: String, durationSec: Long): List<CaptionWord> {
        val words = transcript.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val totalMs = max(durationSec * 1000L, words.size * 260L)
        val stepMs = totalMs / words.size
        return words.mapIndexed { idx, w ->
            val cleanUpper = w.uppercase().replace(Regex("[^A-Z0-9]"), "")
            CaptionWord(
                word = w,
                startMs = idx * stepMs,
                endMs = (idx + 1) * stepMs,
                emphasis = cleanUpper in listOf("WAIT", "INSANE", "NEVER", "CRAZY", "CLUTCH", "WHAT", "NO", "WAY")
            )
        }
    }

    private fun buildDefaultTranscript(hook: String, handle: String): String {
        return "Wait for what $handle just pulled off right here — watch closely because chat completely lost it in 3... 2... 1... NO WAY! ($hook)"
    }

    fun formatTimestamp(totalSec: Long): String {
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }
}
