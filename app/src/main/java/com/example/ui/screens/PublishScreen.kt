package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.model.ClippingCampaign
import com.example.data.model.ComplianceCheckItem
import com.example.data.model.PrivacyStatus
import com.example.data.model.PublishPlatform
import com.example.data.model.ViralClip
import com.example.engine.YouTubeUploadManager
import com.example.ui.theme.AmberWarn
import com.example.ui.theme.BroadcastSlate
import com.example.ui.theme.CrimsonLive
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.ElectricVolt
import com.example.ui.theme.ElevatedCard
import com.example.ui.theme.NeonMint
import com.example.ui.theme.ObsidianInk
import com.example.ui.theme.SteelOutline
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.viewmodel.AiStudioUiState
import com.example.ui.viewmodel.AsyncOpState
import com.example.ui.viewmodel.PublishUiState
import com.google.android.gms.auth.api.identity.Identity
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PublishScreen(
    clip: ViralClip?,
    publishState: PublishUiState,
    aiState: AiStudioUiState,
    campaigns: List<ClippingCampaign>,
    complianceChecks: List<ComplianceCheckItem>,
    onGenerateSeo: (ViralClip, String) -> Unit,
    onUpdatePublish: (
        selectedTitleIdx: Int?,
        title: String?,
        description: String?,
        tagsCsv: String?,
        pinnedComment: String?,
        privacyStatus: PrivacyStatus?,
        scheduledIso: String?,
        campaignId: String?
    ) -> Unit,
    onSetYouTubeToken: (String?) -> Unit,
    onUploadYouTube: (ViralClip, ClippingCampaign?) -> Unit,
    onLogExternalShare: (ViralClip, PublishPlatform, ClippingCampaign?) -> Unit
) {
    val context = LocalContext.current

    if (clip == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Detect or select a clip on the Analyze tab first to generate Viral SEO and publish.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                modifier = Modifier.padding(24.dp)
            )
        }
        return
    }

    var gameTopic by remember { mutableStateOf("Gaming / Live Stream Highlights") }
    var oauthStatusError by remember { mutableStateOf<String?>(null) }

    val activeCampaign = campaigns.firstOrNull { it.id == publishState.activeCampaignId }
        ?: campaigns.firstOrNull { it.active }

    val authClient = remember(context) { Identity.getAuthorizationClient(context) }
    val oauthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        runCatching {
            val authResult = authClient.getAuthorizationResultFromIntent(result.data)
            onSetYouTubeToken(authResult.accessToken)
            oauthStatusError = null
        }.onFailure { err ->
            oauthStatusError = err.localizedMessage ?: "YouTube OAuth authorization was cancelled."
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 840.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Search-Grounded Viral SEO Engine
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
                        Text(
                            "Viral YouTube Shorts SEO Engine",
                            style = MaterialTheme.typography.titleLarge,
                            color = ElectricVolt
                        )
                        Text(
                            "Powered by Gemini 4 Hydrogen + Google Search Grounding for live trending hashtags & CTR titles.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )

                        OutlinedTextField(
                            value = gameTopic,
                            onValueChange = { gameTopic = it },
                            label = { Text("Game / Topic Context for Google Search Grounding") },
                            modifier = Modifier.fillMaxWidth().testTag("seo_topic_input")
                        )

                        val isGeneratingSeo = aiState.seoBundle is AsyncOpState.Loading
                        Button(
                            onClick = { onGenerateSeo(clip, gameTopic) },
                            enabled = !isGeneratingSeo,
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricVolt, contentColor = ObsidianInk),
                            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("generate_seo_button")
                        ) {
                            if (isGeneratingSeo) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = ObsidianInk, strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Grounding with Google Search...")
                            } else {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Generate Viral SEO Package")
                            }
                        }

                        if (aiState.seoBundle is AsyncOpState.Error) {
                            Text(aiState.seoBundle.message, color = CrimsonLive, style = MaterialTheme.typography.bodyMedium)
                        }

                        val seoData = (aiState.seoBundle as? AsyncOpState.Success)?.data
                        val variants = seoData?.titleOptions.orEmpty()
                        if (variants.isNotEmpty()) {
                            Text("3 High-CTR Title Variants (<60 chars)", style = MaterialTheme.typography.labelMedium, color = CyberCyan)
                            variants.forEachIndexed { idx, variant ->
                                FilterChip(
                                    selected = publishState.selectedTitleIdx == idx,
                                    onClick = {
                                        onUpdatePublish(idx, variant, null, null, null, null, null, null)
                                    },
                                    label = { Text(variant) }
                                )
                            }
                        }

                        OutlinedTextField(
                            value = publishState.customTitle,
                            onValueChange = { onUpdatePublish(null, it, null, null, null, null, null, null) },
                            label = { Text("Shorts Title (${publishState.customTitle.length}/60)") },
                            modifier = Modifier.fillMaxWidth().testTag("seo_title_input")
                        )

                        OutlinedTextField(
                            value = publishState.customDescription,
                            onValueChange = { onUpdatePublish(null, null, it, null, null, null, null, null) },
                            label = { Text("Algorithm-Primed Description + Credit") },
                            minLines = 4,
                            modifier = Modifier.fillMaxWidth().testTag("seo_description_input")
                        )

                        OutlinedTextField(
                            value = publishState.customTagsCsv,
                            onValueChange = { onUpdatePublish(null, null, null, it, null, null, null, null) },
                            label = { Text("Backend Keyword Tags (comma separated)") },
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth().testTag("seo_tags_input")
                        )

                        OutlinedTextField(
                            value = publishState.pinnedComment,
                            onValueChange = { onUpdatePublish(null, null, null, null, it, null, null, null) },
                            label = { Text("Comment Pin Prompt") },
                            modifier = Modifier.fillMaxWidth().testTag("seo_pin_input")
                        )

                        val sources = seoData?.groundingSources.orEmpty()
                        if (sources.isNotEmpty()) {
                            Text("Google Search Grounding Sources", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                sources.take(5).forEach { src ->
                                    Surface(color = ElevatedCard, shape = RoundedCornerShape(8.dp)) {
                                        Text(src, style = MaterialTheme.typography.labelSmall, color = CyberCyan, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 2. Campaign Compliance Gate
            item {
                val allPassed = complianceChecks.isEmpty() || complianceChecks.all { it.passed }
                Surface(
                    color = ElevatedCard,
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, if (allPassed) NeonMint else AmberWarn)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(
                                imageVector = if (allPassed) Icons.Default.VerifiedUser else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (allPassed) NeonMint else AmberWarn
                            )
                            Text(
                                text = activeCampaign?.let { "Campaign Compliance: ${it.name}" } ?: "Pre-Flight Shorts Compliance",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (allPassed) NeonMint else AmberWarn
                            )
                        }

                        if (campaigns.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                campaigns.forEach { camp ->
                                    FilterChip(
                                        selected = activeCampaign?.id == camp.id,
                                        onClick = { onUpdatePublish(null, null, null, null, null, null, null, camp.id) },
                                        label = { Text("${camp.name} ($${camp.cpmRateUsd}/1K)") }
                                    )
                                }
                            }
                        }

                        complianceChecks.forEach { item ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(
                                    imageVector = if (item.passed) Icons.Default.CheckCircle else Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = if (item.passed) NeonMint else CrimsonLive,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text("${item.label}: ${item.detail}", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                            }
                        }
                    }
                }
            }

            // 3. YouTube Data API v3 Resumable Publisher & Multi-Platform Share
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
                        Text("YouTube Data API v3 Auto-Publisher", style = MaterialTheme.typography.titleMedium, color = TextPrimary)

                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            PrivacyStatus.entries.forEach { priv ->
                                FilterChip(
                                    selected = publishState.privacyStatus == priv,
                                    onClick = { onUpdatePublish(null, null, null, null, null, priv, null, null) },
                                    label = { Text(priv.label) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = ElectricVolt,
                                        selectedLabelColor = ObsidianInk
                                    )
                                )
                            }
                        }

                        if (publishState.privacyStatus == PrivacyStatus.PRIVATE_SCHEDULED) {
                            OutlinedTextField(
                                value = publishState.scheduledIso,
                                onValueChange = { onUpdatePublish(null, null, null, null, null, null, it, null) },
                                label = { Text("Scheduled publishAt (ISO-8601 UTC)") },
                                placeholder = { Text("2026-11-19T18:00:00Z") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                oauthStatusError = null
                                authClient.authorize(YouTubeUploadManager.buildAuthorizationRequest())
                                    .addOnSuccessListener { result ->
                                        val pending = result.pendingIntent
                                        if (result.hasResolution() && pending != null) {
                                            oauthLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                                        } else {
                                            onSetYouTubeToken(result.accessToken)
                                        }
                                    }
                                    .addOnFailureListener { err ->
                                        oauthStatusError = err.localizedMessage ?: "YouTube OAuth failed."
                                    }
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("youtube_oauth_button")
                        ) {
                            Text(
                                text = if (publishState.youtubeAccessToken != null) {
                                    "YouTube OAuth Authorized ✓"
                                } else {
                                    "Connect YouTube Channel (OAuth 2.0)"
                                },
                                color = if (publishState.youtubeAccessToken != null) NeonMint else CyberCyan
                            )
                        }

                        if (!oauthStatusError.isNullOrBlank()) {
                            Text(oauthStatusError.orEmpty(), color = CrimsonLive, style = MaterialTheme.typography.bodyMedium)
                        }

                        val isUploading = publishState.uploadState is AsyncOpState.Loading
                        Button(
                            onClick = { onUploadYouTube(clip, activeCampaign) },
                            enabled = !isUploading,
                            colors = ButtonDefaults.buttonColors(containerColor = CrimsonLive, contentColor = TextPrimary),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().height(52.dp).testTag("youtube_upload_button")
                        ) {
                            if (isUploading) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = TextPrimary, strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Uploading Short (${(publishState.uploadProgress * 100).toInt()}%)...")
                            } else {
                                Icon(Icons.Default.CloudUpload, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Upload to YouTube Shorts")
                            }
                        }

                        if (isUploading) {
                            LinearProgressIndicator(
                                progress = { publishState.uploadProgress },
                                modifier = Modifier.fillMaxWidth(),
                                color = CrimsonLive
                            )
                        }

                        when (val up = publishState.uploadState) {
                            is AsyncOpState.Success -> {
                                Text("Uploaded: ${up.data}", color = NeonMint, style = MaterialTheme.typography.bodyMedium)
                            }
                            is AsyncOpState.Error -> {
                                Text(up.message, color = CrimsonLive, style = MaterialTheme.typography.bodyMedium)
                            }
                            else -> Unit
                        }

                        OutlinedButton(
                            onClick = {
                                val file = clip.localOutputPath?.let { File(it) }
                                YouTubeUploadManager.shareClipWithSeoCaption(
                                    context = context,
                                    videoFile = file,
                                    seoTitle = publishState.customTitle.ifBlank { clip.title },
                                    seoDescription = publishState.customDescription.ifBlank { clip.seoDescription }
                                )
                                onLogExternalShare(clip, PublishPlatform.TIKTOK, activeCampaign)
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("native_share_sheet_button")
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("1-Tap Share (TikTok / Reels / Shorts + Copy SEO)")
                        }

                        if (clip.localOutputPath.isNullOrBlank()) {
                            Text(
                                "Tip: Render the 9:16 MP4 on the Studio tab before uploading.",
                                color = AmberWarn,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }
    }
}
