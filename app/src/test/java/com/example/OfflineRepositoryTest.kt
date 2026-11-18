package com.example

import com.example.data.firebase.ClipPulseRepository
import com.example.data.model.ClipProject
import com.example.data.model.ClippingCampaign
import com.example.data.model.HookBannerStyle
import com.example.data.model.LayoutPreset
import com.example.data.model.PublishedShort
import com.example.data.model.ViralClip
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineRepositoryTest {

    @Test
    fun `offline repository stores projects clips campaigns and publications without Firebase`() = runTest {
        val repo = ClipPulseRepository(db = null, auth = null)

        val projId = repo.saveProject(ClipProject(title = "Stream VOD")).getOrThrow()
        assertTrue(projId.startsWith("proj_"))
        assertEquals(1, repo.observeProjects("local_studio").first().size)

        val clipId = repo.saveClip(ViralClip(id = "c1", projectId = projId, title = "Clutch")).getOrThrow()
        assertEquals("c1", clipId)
        repo.updateClipCustomization(
            clipId = "c1",
            layoutPreset = LayoutPreset.CINEMA_BLUR,
            hookBannerText = "WAIT FOR IT",
            hookBannerStyle = HookBannerStyle.CRIMSON_ALERT,
            creditWatermark = "@Creator",
            chatOverlayEnabled = true,
            startSec = 5L,
            endSec = 35L
        ).getOrThrow()
        assertEquals(LayoutPreset.CINEMA_BLUR.name, repo.observeClips("local_studio").first().first().layoutPreset)

        val campId = repo.saveCampaign(ClippingCampaign(name = "Main Campaign")).getOrThrow()
        repo.toggleCampaignActive(campId, false).getOrThrow()
        assertEquals(false, repo.observeCampaigns("local_studio").first().first().active)

        val pubId = repo.savePublication(PublishedShort(clipId = "c1", clipTitle = "Short 1")).getOrThrow()
        assertTrue(pubId.startsWith("pub_"))
        assertEquals(1, repo.observePublications("local_studio").first().size)
    }
}
