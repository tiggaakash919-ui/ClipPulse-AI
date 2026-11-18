package com.example

import com.example.data.model.CaptionHighlightColor
import com.example.data.model.CaptionWord
import com.example.data.model.LayoutPreset
import com.example.data.model.ViralClip
import com.example.engine.FfmpegCommandBuilder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegCommandBuilderTest {

    private fun sampleClip(preset: LayoutPreset = LayoutPreset.WORD_POP) = ViralClip(
        id = "clip_1",
        projectId = "proj_1",
        title = "Insane Clutch",
        streamerHandle = "@Creator",
        startSec = 10L,
        endSec = 40L,
        layoutPreset = preset.name,
        hookBannerText = "WAIT FOR IT {NO WAY}",
        creditWatermark = "Credit: @Creator"
    )

    private val sampleWords = listOf(
        CaptionWord("Wait", 0L, 450L),
        CaptionWord("for", 450L, 800L),
        CaptionWord("this", 800L, 1200L),
        CaptionWord("clutch", 1200L, 1700L)
    )

    @Test
    fun `buildAssScript configures Montserrat Black thick outline shadow lower-third and yellow pop`() {
        val ass = FfmpegCommandBuilder.buildAssScript(
            clip = sampleClip(),
            words = sampleWords,
            highlightColor = CaptionHighlightColor.VIBRANT_YELLOW
        )

        assertTrue(ass.contains("Style: HormoziPop,Montserrat Black,76"))
        // Outline=7, Shadow=4, Alignment=2 (bottom-center), MarginV=390 (lower-third)
        assertTrue(ass.contains(",1,7,4,2,64,64,390,1"))
        // Vibrant Yellow BGR color tag & pop-scale animation
        assertTrue(ass.contains("\\c&H0000E6FF&"))
        assertTrue(ass.contains("\\fscx118\\fscy118"))
        assertTrue(ass.contains("\\fscx100\\fscy100"))
        assertTrue(ass.contains("{\\rHormoziPop}"))
        // Sanitizes unsafe braces from user hook text
        assertFalse(ass.contains("{NO WAY}"))
        assertTrue(ass.contains("(NO WAY)"))
    }

    @Test
    fun `buildAssScript supports neon green active word highlight`() {
        val ass = FfmpegCommandBuilder.buildAssScript(
            clip = sampleClip(),
            words = sampleWords,
            highlightColor = CaptionHighlightColor.NEON_GREEN
        )

        assertTrue(ass.contains("\\c&H0014FF39&"))
        assertTrue(ass.contains("WAIT"))
        assertTrue(ass.contains("CLUTCH"))
    }

    @Test
    fun `buildRenderCommand embeds ffmpeg ass filtergraph with fontsdir across presets`() {
        LayoutPreset.entries.forEach { preset ->
            val cmd = FfmpegCommandBuilder.buildRenderCommand(
                inPath = "cache/clip_raw.mp4",
                outPath = "cache/short_9x16.mp4",
                assPath = "cache/captions.ass",
                clip = sampleClip(preset),
                fontsDir = "fonts"
            )
            assertTrue(cmd.contains("ass='cache/captions.ass':fontsdir='fonts'"))
            assertTrue(cmd.contains("-map \"[v]\""))
            assertTrue(cmd.contains("-c:v libx264"))
        }
    }
}
