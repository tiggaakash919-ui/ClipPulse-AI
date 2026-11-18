package com.example

import com.example.data.model.DetectionConfig
import com.example.engine.ViralMomentEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViralMomentEngineTest {

    @Test
    fun `detectMoments applies rolling baseline, hype weighting, reaction offset and dedup gap`() {
        val messages = mutableListOf<ViralMomentEngine.RawChatMessage>()
        // Quiet baseline around 2 msgs per 10s
        for (sec in 0L..600L step 5L) {
            messages.add(ViralMomentEngine.RawChatMessage(sec, "nice stream"))
        }
        // Explosive hype burst at 240s
        repeat(24) {
            messages.add(ViralMomentEngine.RawChatMessage(242L, "OMEGALUL NO WAY CLIP IT"))
        }
        // Adjacent secondary burst at 260s (should be deduplicated by 90s gap)
        repeat(15) {
            messages.add(ViralMomentEngine.RawChatMessage(262L, "LMAO W"))
        }

        val (buckets, clips) = ViralMomentEngine.detectMoments(
            projectId = "test_proj",
            streamerHandle = "xqc",
            durationSec = 600L,
            config = DetectionConfig(bucketSec = 10, beforeSec = 20, afterSec = 10, gapSec = 90, topCount = 3),
            chatMessages = messages,
            heatmapMarkers = mapOf(240L to 0.92),
            audioPeaks = listOf(ViralMomentEngine.AudioEnergyPoint(240L, -3.0))
        )

        assertTrue(buckets.isNotEmpty())
        assertTrue(clips.isNotEmpty())
        val topClip = clips.first()
        assertEquals(220L, topClip.startSec) // 240 - 20 beforeSec
        assertEquals(250L, topClip.endSec)   // 240 + 10 afterSec
        assertTrue(topClip.spikeMultiplier >= 4.0)
        // Ensure no duplicate clip within 90s of topClip spike (240s)
        assertTrue(clips.drop(1).none { kotlin.math.abs((it.startSec + 20L) - 240L) < 90L })
    }

    @Test
    fun `evaluateRollingDvrWindow triggers clip when live spike exceeds 4x`() {
        val recent = mutableListOf<ViralMomentEngine.RawChatMessage>()
        for (sec in 10L..290L step 10L) {
            recent.add(ViralMomentEngine.RawChatMessage(sec, "chill"))
        }
        repeat(8) {
            recent.add(ViralMomentEngine.RawChatMessage(305L, "INSANE CLUTCH 🔥"))
        }

        val (mult, clip) = ViralMomentEngine.evaluateRollingDvrWindow(
            recentMessages = recent,
            nowSec = 310L,
            streamerHandle = "@Streamer",
            projectId = "live_test"
        )

        assertTrue(mult >= 4.0)
        assertNotNull(clip)
        assertEquals(290L, clip!!.startSec)
        assertEquals(320L, clip.endSec)
    }
}
