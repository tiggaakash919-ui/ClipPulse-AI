package com.example.data.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class PlatformSource(val label: String) {
    YOUTUBE("YouTube"),
    TWITCH("Twitch"),
    KICK("Kick"),
    CUSTOM_URL("Direct URL"),
    LOCAL_VIDEO("Device Video")
}

enum class IngestMode(val label: String) {
    VOD("VOD Mode"),
    LIVE_DVR("Live Monitor")
}

enum class LayoutPreset(val label: String, val subtitle: String) {
    WORD_POP("Word-by-Word Pop", "Yellow pop captions + Top Hook"),
    SPLIT_SCREEN("Gaming Split-Screen", "32% Facecam + 68% Gameplay"),
    CINEMA_BLUR("Cinema Blur Stack", "Boxblur 9:16 + Crisp Center"),
    FULL_CROP("Dynamic Full-Bleed", "9:16 Center Action Crop")
}

enum class HookBannerStyle(val label: String) {
    VOLT_PILL("Electric Volt"),
    WHITE_PILL("High-Contrast White"),
    CRIMSON_ALERT("Crimson Spike")
}

enum class WatermarkCorner(val label: String) {
    TOP_LEFT("Top Left"),
    TOP_RIGHT("Top Right"),
    BOTTOM_LEFT("Bottom Left"),
    BOTTOM_RIGHT("Bottom Right")
}

enum class ClipStatus {
    DETECTED,
    RENDERED,
    PUBLISHED
}

enum class PublishPlatform(val label: String) {
    YOUTUBE_SHORTS("YouTube Shorts"),
    TIKTOK("TikTok"),
    INSTAGRAM_REELS("Instagram Reels")
}

enum class PrivacyStatus(val apiValue: String, val label: String) {
    PUBLIC("public", "Public"),
    UNLISTED("unlisted", "Unlisted"),
    PRIVATE_SCHEDULED("private", "Scheduled")
}

inline fun <reified T : Enum<T>> safeEnum(raw: String?, default: T): T {
    return enumValues<T>().firstOrNull { it.name == raw } ?: default
}

@Keep
@IgnoreExtraProperties
data class ClipProject(
    @DocumentId val id: String = "",
    val userId: String = "",
    val sourceUrl: String = "",
    val platform: String = PlatformSource.YOUTUBE.name,
    val ingestMode: String = IngestMode.VOD.name,
    val title: String = "",
    val streamerHandle: String = "@Creator",
    val durationSec: Long = 0L,
    val bucketSec: Long = 10L,
    val beforeSec: Long = 20L,
    val afterSec: Long = 10L,
    val gapSec: Long = 90L,
    val topCount: Long = 5L,
    val peakMultiplier: Double = 1.0,
    val notes: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun platformEnum(): PlatformSource = safeEnum(platform, PlatformSource.YOUTUBE)
    fun ingestModeEnum(): IngestMode = safeEnum(ingestMode, IngestMode.VOD)

    fun toCreateMap(uid: String): Map<String, Any> {
        val base = mutableMapOf<String, Any?>(
            "userId" to uid,
            "sourceUrl" to sourceUrl.take(1000).ifBlank { "local://video" },
            "platform" to platformEnum().name,
            "ingestMode" to ingestModeEnum().name,
            "title" to title.take(200).ifBlank { "Untitled Stream" },
            "streamerHandle" to streamerHandle.take(80).ifBlank { "@Creator" },
            "durationSec" to durationSec.coerceIn(0L, 86400L),
            "bucketSec" to bucketSec.coerceIn(5L, 30L),
            "beforeSec" to beforeSec.coerceIn(5L, 60L),
            "afterSec" to afterSec.coerceIn(5L, 60L),
            "gapSec" to gapSec.coerceIn(10L, 300L),
            "topCount" to topCount.coerceIn(1L, 20L),
            "peakMultiplier" to peakMultiplier.coerceIn(0.0, 100.0),
            "notes" to notes?.take(1000),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        @Suppress("UNCHECKED_CAST")
        return base.filterValues { it != null } as Map<String, Any>
    }
}

@Keep
@IgnoreExtraProperties
data class ViralClip(
    @DocumentId val id: String = "",
    val userId: String = "",
    val projectId: String = "",
    val title: String = "",
    val streamerHandle: String = "@Creator",
    val startSec: Long = 0L,
    val endSec: Long = 30L,
    val spikeMultiplier: Double = 1.0,
    val viralScore: Long = 75L,
    val layoutPreset: String = LayoutPreset.WORD_POP.name,
    val hookBannerText: String = "WAIT FOR IT",
    val hookBannerStyle: String = HookBannerStyle.VOLT_PILL.name,
    val creditWatermark: String = "Credit: @Creator",
    val chatOverlayEnabled: Boolean = true,
    val seoTitle: String = "",
    val seoDescription: String = "",
    val seoTags: List<String> = emptyList(),
    val pinnedComment: String = "",
    val localOutputPath: String? = null,
    val aiRetentionSummary: String? = null,
    val transcriptPreview: String? = null,
    val status: String = ClipStatus.DETECTED.name,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun durationSec(): Long = (endSec - startSec).coerceAtLeast(1L)
    fun layoutPresetEnum(): LayoutPreset = safeEnum(layoutPreset, LayoutPreset.WORD_POP)
    fun hookStyleEnum(): HookBannerStyle = safeEnum(hookBannerStyle, HookBannerStyle.VOLT_PILL)
    fun statusEnum(): ClipStatus = safeEnum(status, ClipStatus.DETECTED)

    fun toCreateMap(uid: String): Map<String, Any> {
        val safeStart = startSec.coerceAtLeast(0L)
        val safeEnd = endSec.coerceAtLeast(safeStart + 1L)
        val base = mutableMapOf<String, Any?>(
            "userId" to uid,
            "projectId" to projectId.take(128).ifBlank { "default_project" },
            "title" to title.take(120).ifBlank { "Viral Clip" },
            "streamerHandle" to streamerHandle.take(80).ifBlank { "@Creator" },
            "startSec" to safeStart,
            "endSec" to safeEnd,
            "spikeMultiplier" to spikeMultiplier.coerceIn(0.0, 100.0),
            "viralScore" to viralScore.coerceIn(0L, 100L),
            "layoutPreset" to layoutPresetEnum().name,
            "hookBannerText" to hookBannerText.take(80),
            "hookBannerStyle" to hookStyleEnum().name,
            "creditWatermark" to creditWatermark.take(80),
            "chatOverlayEnabled" to chatOverlayEnabled,
            "seoTitle" to seoTitle.take(100),
            "seoDescription" to seoDescription.take(2500),
            "seoTags" to seoTags.take(30),
            "pinnedComment" to pinnedComment.take(500),
            "localOutputPath" to localOutputPath?.take(500),
            "aiRetentionSummary" to aiRetentionSummary?.take(2000),
            "transcriptPreview" to transcriptPreview?.take(3000),
            "status" to statusEnum().name,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        @Suppress("UNCHECKED_CAST")
        return base.filterValues { it != null } as Map<String, Any>
    }
}

@Keep
@IgnoreExtraProperties
data class ClippingCampaign(
    @DocumentId val id: String = "",
    val userId: String = "",
    val name: String = "",
    val creatorHandle: String = "@Creator",
    val requiredMention: String = "@Creator",
    val requiredHashtags: List<String> = listOf("#shorts"),
    val minDurationSec: Long = 15L,
    val maxDurationSec: Long = 60L,
    val watermarkCorner: String = WatermarkCorner.TOP_LEFT.name,
    val cpmRateUsd: Double = 1.5,
    val active: Boolean = true,
    val notes: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun watermarkCornerEnum(): WatermarkCorner = safeEnum(watermarkCorner, WatermarkCorner.TOP_LEFT)

    fun toCreateMap(uid: String): Map<String, Any> {
        val safeMin = minDurationSec.coerceIn(3L, 180L)
        val safeMax = maxDurationSec.coerceIn(safeMin, 300L)
        val base = mutableMapOf<String, Any?>(
            "userId" to uid,
            "name" to name.take(100).ifBlank { "Creator Campaign" },
            "creatorHandle" to creatorHandle.take(80).ifBlank { "@Creator" },
            "requiredMention" to requiredMention.take(80),
            "requiredHashtags" to requiredHashtags.take(15),
            "minDurationSec" to safeMin,
            "maxDurationSec" to safeMax,
            "watermarkCorner" to watermarkCornerEnum().name,
            "cpmRateUsd" to cpmRateUsd.coerceIn(0.0, 1000.0),
            "active" to active,
            "notes" to notes?.take(1000),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        @Suppress("UNCHECKED_CAST")
        return base.filterValues { it != null } as Map<String, Any>
    }
}

@Keep
@IgnoreExtraProperties
data class PublishedShort(
    @DocumentId val id: String = "",
    val userId: String = "",
    val clipId: String = "",
    val campaignId: String? = null,
    val clipTitle: String = "",
    val platform: String = PublishPlatform.YOUTUBE_SHORTS.name,
    val privacyStatus: String = PrivacyStatus.PUBLIC.name,
    val youtubeVideoId: String = "",
    val youtubeUrl: String = "",
    val scheduledPublishAt: String? = null,
    val viewsCount: Long = 0L,
    val cpmRateUsd: Double = 1.5,
    val compliancePassed: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun estimatedEarningsUsd(): Double = (viewsCount / 1000.0) * cpmRateUsd
    fun platformEnum(): PublishPlatform = safeEnum(platform, PublishPlatform.YOUTUBE_SHORTS)
    fun privacyEnum(): PrivacyStatus = safeEnum(privacyStatus, PrivacyStatus.PUBLIC)

    fun toCreateMap(uid: String): Map<String, Any> {
        val base = mutableMapOf<String, Any?>(
            "userId" to uid,
            "clipId" to clipId.take(128).ifBlank { "clip_manual" },
            "campaignId" to campaignId?.take(128),
            "clipTitle" to clipTitle.take(120).ifBlank { "Published Short" },
            "platform" to platformEnum().name,
            "privacyStatus" to privacyEnum().name,
            "youtubeVideoId" to youtubeVideoId.take(64),
            "youtubeUrl" to youtubeUrl.take(300),
            "scheduledPublishAt" to scheduledPublishAt?.take(64),
            "viewsCount" to viewsCount.coerceAtLeast(0L),
            "cpmRateUsd" to cpmRateUsd.coerceIn(0.0, 1000.0),
            "compliancePassed" to compliancePassed,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        @Suppress("UNCHECKED_CAST")
        return base.filterValues { it != null } as Map<String, Any>
    }
}

data class SpikeBucket(
    val timestampSec: Long,
    val chatCount: Int,
    val rollingAvg: Double,
    val spikeMultiplier: Double,
    val heatmapIntensity: Double,
    val audioDbPeak: Double,
    val hypeTags: List<String>,
    val sampleMessages: List<String>
)

data class CaptionWord(
    val word: String,
    val startMs: Long,
    val endMs: Long,
    val emphasis: Boolean = false
)

data class DetectionConfig(
    val bucketSec: Int = 10,
    val beforeSec: Int = 20,
    val afterSec: Int = 10,
    val gapSec: Int = 90,
    val topCount: Int = 5
)

enum class CaptionHighlightColor(
    val label: String,
    val argbHex: Int,
    val assBgrColor: String
) {
    VIBRANT_YELLOW("Vibrant Yellow", 0xFFFFE600.toInt(), "&H0000E6FF&"),
    NEON_GREEN("Neon Green", 0xFF39FF14.toInt(), "&H0014FF39&")
}

data class PipelineMetrics(
    val sourceUri: String = "",
    val hlsSegmentUrls: List<String> = emptyList(),
    val downloadedSegmentFile: String? = null,
    val downloadedSegmentClipId: String? = null,
    val sourceBytesEstimate: Long = 0L,
    val downloadedBytes: Long = 0L,
    val savedPercent: Double = 0.0,
    val exportProgress: Float = 0f,
    val exportStatus: String = "Idle",
    val ffmpegExtractCommand: String = "",
    val ffmpegRenderCommand: String = "",
    val assSubtitleScript: String = "",
    val captionHighlight: CaptionHighlightColor = CaptionHighlightColor.VIBRANT_YELLOW,
    val exportedAssPath: String? = null
)

data class ComplianceCheckItem(
    val label: String,
    val passed: Boolean,
    val detail: String
)

data class SeoBundle(
    val titleOptions: List<String> = emptyList(),
    val description: String = "",
    val tags: List<String> = emptyList(),
    val pinnedComment: String = "",
    val groundingSources: List<String> = emptyList()
)

data class AiChatMessage(
    val id: String,
    val role: String,
    val text: String,
    val modelBadge: String = "",
    val groundingSources: List<String> = emptyList()
)

enum class CoachMode(val label: String, val modelLabel: String) {
    FAST("Fast Ideas", "gemini-3.1-flash-lite"),
    GROUNDED("Live Trends", "gemini-4-hydrogen + Search"),
    DEEP("Deep Strategy", "gemini-4-carbon (Thinking High)")
}

enum class ImageAspectOption(val label: String) {
    RATIO_1_1("1:1"),
    RATIO_2_3("2:3"),
    RATIO_3_2("3:2"),
    RATIO_3_4("3:4"),
    RATIO_4_3("4:3"),
    RATIO_9_16("9:16"),
    RATIO_16_9("16:9"),
    RATIO_21_9("21:9")
}

enum class ImageSizeOption(val label: String) {
    SIZE_1K("1K"),
    SIZE_2K("2K"),
    SIZE_4K("4K")
}

enum class ImageModelOption(val modelId: String, val label: String) {
    NANO_BANANA("gemini-nano-banana-2.1", "Nano Banana 2.1 (Fast)"),
    PRO_STUDIO("gemini-3-pro-image-preview", "Gemini 3 Pro Image (Studio)")
}
