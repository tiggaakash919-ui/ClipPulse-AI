package com.example

import com.example.engine.Media3RenderEngine
import com.example.ui.viewmodel.AutoPilotUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3TrimAndAutoPilotTest {

    @Test
    fun preSlicedFileMatchesDurationWithoutRedundantOffsetClipping() {
        // Clip originally at 240s..270s, but file is already sliced to 30s
        val trim = Media3RenderEngine.computeSafeTrimWindowMs(
            clipStartSec = 240L,
            clipEndSec = 270L,
            actualDurationMs = 30_000L,
            isPreSlicedFile = true
        )
        assertNull(trim)
    }

    @Test
    fun preSlicedFileLongerThanClipTrimsFromZero() {
        val trim = Media3RenderEngine.computeSafeTrimWindowMs(
            clipStartSec = 240L,
            clipEndSec = 265L,
            actualDurationMs = 36_000L,
            isPreSlicedFile = true
        )
        assertEquals(0L to 25_000L, trim)
    }

    @Test
    fun fullSourceClampsOutOfRangeTimestampsToActualDuration() {
        val trim = Media3RenderEngine.computeSafeTrimWindowMs(
            clipStartSec = 35L,
            clipEndSec = 65L,
            actualDurationMs = 45_000L,
            isPreSlicedFile = false
        )
        assertEquals(35_000L to 45_000L, trim)
    }

    @Test
    fun clipBeyondVideoDurationClampsStartSafelyBelowEnd() {
        val trim = Media3RenderEngine.computeSafeTrimWindowMs(
            clipStartSec = 120L,
            clipEndSec = 150L,
            actualDurationMs = 20_000L,
            isPreSlicedFile = false
        )
        assertEquals(19_000L to 20_000L, trim)
    }

    @Test
    fun fullLengthClipOmitsClippingConfig() {
        val trim = Media3RenderEngine.computeSafeTrimWindowMs(
            clipStartSec = 0L,
            clipEndSec = 30L,
            actualDurationMs = 30_000L,
            isPreSlicedFile = false
        )
        assertNull(trim)
    }

    @Test
    fun autoPilotDefaultsEnableAutoUploadAndCleanState() {
        val state = AutoPilotUiState()
        assertTrue(state.autoUploadYouTube)
        assertEquals(2, state.clipsTargetCount)
        assertEquals(4, state.totalSteps)
    }
}
