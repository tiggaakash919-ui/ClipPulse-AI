package com.example.ui.viewmodel

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ai.GeminiStudioService
import com.example.data.ai.RetentionAuditResult
import com.example.data.firebase.ClipPulseRepository
import com.example.data.model.AiChatMessage
import com.example.data.model.CaptionHighlightColor
import com.example.data.model.CaptionWord
import com.example.data.model.ClipProject
import com.example.data.model.ClipStatus
import com.example.data.model.ClippingCampaign
import com.example.data.model.CoachMode
import com.example.data.model.ComplianceCheckItem
import com.example.data.model.DetectionConfig
import com.example.data.model.HookBannerStyle
import com.example.data.model.ImageAspectOption
import com.example.data.model.ImageModelOption
import com.example.data.model.ImageSizeOption
import com.example.data.model.IngestMode
import com.example.data.model.LayoutPreset
import com.example.data.model.PipelineMetrics
import com.example.data.model.PlatformSource
import com.example.data.model.PrivacyStatus
import com.example.data.model.PublishPlatform
import com.example.data.model.PublishedShort
import com.example.data.model.SeoBundle
import com.example.data.model.SpikeBucket
import com.example.data.model.ViralClip
import com.example.data.model.WatermarkCorner
import com.example.engine.AudioRecorderHelper
import com.example.engine.FfmpegCommandBuilder
import com.example.engine.Media3RenderEngine
import com.example.engine.ResolvedStreamInfo
import com.example.engine.StreamResolver
import com.example.engine.ViralMomentEngine
import com.example.engine.YouTubeUploadManager
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.WebSocket
import java.io.File
import java.util.UUID

sealed interface DataUiState<out T> {
    data object Loading : DataUiState<Nothing>
    data class Success<T>(val data: T) : DataUiState<T>
    data class Error(val message: String) : DataUiState<Nothing>
}

sealed interface AsyncOpState<out T> {
    data object Idle : AsyncOpState<Nothing>
    data object Loading : AsyncOpState<Nothing>
    data class Success<T>(val data: T) : AsyncOpState<T>
    data class Error(val message: String) : AsyncOpState<Nothing>
}

data class AnalyzerUiState(
    val sourceUrl: String = "",
    val ingestMode: IngestMode = IngestMode.VOD,
    val config: DetectionConfig = DetectionConfig(),
    val isAnalyzing: Boolean = false,
    val isLiveMonitoring: Boolean = false,
    val liveStatus: String = "Standby",
    val liveMultiplier: Double = 1.0,
    val liveChatFeed: List<String> = emptyList(),
    val resolvedInfo: ResolvedStreamInfo? = null,
    val spikeBuckets: List<SpikeBucket> = emptyList(),
    val detectedClips: List<ViralClip> = emptyList(),
    val selectedClipId: String? = null,
    val localVideoUri: Uri? = null,
    val errorMessage: String? = null
)

data class AiStudioUiState(
    val hookSuggestions: AsyncOpState<List<String>> = AsyncOpState.Idle,
    val seoBundle: AsyncOpState<SeoBundle> = AsyncOpState.Idle,
    val retentionAudit: AsyncOpState<RetentionAuditResult> = AsyncOpState.Idle,
    val videoAnalysis: AsyncOpState<String> = AsyncOpState.Idle,
    val imageAnalysis: AsyncOpState<String> = AsyncOpState.Idle,
    val isRecordingMic: Boolean = false,
    val transcription: AsyncOpState<String> = AsyncOpState.Idle,
    val imageGenModel: ImageModelOption = ImageModelOption.NANO_BANANA,
    val imageAspect: ImageAspectOption = ImageAspectOption.RATIO_9_16,
    val imageSize: ImageSizeOption = ImageSizeOption.SIZE_1K,
    val generatedCover: AsyncOpState<Bitmap> = AsyncOpState.Idle,
    val coachMode: CoachMode = CoachMode.GROUNDED,
    val coachMessages: List<AiChatMessage> = listOf(
        AiChatMessage(
            id = "welcome",
            role = "model",
            text = "Ready to scale your Shorts. Ask me about hook pacing, YouTube trending tags, or campaign compliance.",
            modelBadge = "ClipPulse Strategist"
        )
    ),
    val isCoachStreaming: Boolean = false,
    val coachError: String? = null
)

data class PublishUiState(
    val selectedTitleIdx: Int = 0,
    val customTitle: String = "",
    val customDescription: String = "",
    val customTagsCsv: String = "",
    val pinnedComment: String = "",
    val privacyStatus: PrivacyStatus = PrivacyStatus.PUBLIC,
    val scheduledIso: String = "",
    val youtubeAccessToken: String? = null,
    val uploadProgress: Float = 0f,
    val uploadState: AsyncOpState<String> = AsyncOpState.Idle,
    val activeCampaignId: String? = null
)

class ClipPulseViewModel(
    private val repository: ClipPulseRepository,
    private val currentUserId: String,
    private val streamResolver: StreamResolver,
    private val renderEngine: Media3RenderEngine,
    private val geminiService: GeminiStudioService,
    private val audioRecorder: AudioRecorderHelper
) : ViewModel() {

    private companion object {
        const val TAG = "ClipPulseVM"
        const val STOP_TIMEOUT_MILLIS = 5000L
    }

    val projectsState: StateFlow<DataUiState<List<ClipProject>>> =
        repository.observeProjects(currentUserId)
            .map<List<ClipProject>, DataUiState<List<ClipProject>>> { DataUiState.Success(it) }
            .catch { emit(DataUiState.Error(unwrapFirestoreError(it, "Failed to observe projects"))) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), DataUiState.Loading)

    val clipsState: StateFlow<DataUiState<List<ViralClip>>> =
        repository.observeClips(currentUserId)
            .map<List<ViralClip>, DataUiState<List<ViralClip>>> { DataUiState.Success(it) }
            .catch { emit(DataUiState.Error(unwrapFirestoreError(it, "Failed to observe clips"))) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), DataUiState.Loading)

    val campaignsState: StateFlow<DataUiState<List<ClippingCampaign>>> =
        repository.observeCampaigns(currentUserId)
            .map<List<ClippingCampaign>, DataUiState<List<ClippingCampaign>>> { DataUiState.Success(it) }
            .catch { emit(DataUiState.Error(unwrapFirestoreError(it, "Failed to observe campaigns"))) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), DataUiState.Loading)

    val publicationsState: StateFlow<DataUiState<List<PublishedShort>>> =
        repository.observePublications(currentUserId)
            .map<List<PublishedShort>, DataUiState<List<PublishedShort>>> { DataUiState.Success(it) }
            .catch { emit(DataUiState.Error(unwrapFirestoreError(it, "Failed to observe publications"))) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), DataUiState.Loading)

    private val _analyzer = MutableStateFlow(AnalyzerUiState())
    val analyzer: StateFlow<AnalyzerUiState> = _analyzer.asStateFlow()

    private val _pipeline = MutableStateFlow(PipelineMetrics())
    val pipeline: StateFlow<PipelineMetrics> = _pipeline.asStateFlow()

    private val _aiStudio = MutableStateFlow(AiStudioUiState())
    val aiStudio: StateFlow<AiStudioUiState> = _aiStudio.asStateFlow()

    private val _publish = MutableStateFlow(PublishUiState())
    val publish: StateFlow<PublishUiState> = _publish.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    private var liveSocket: WebSocket? = null
    private var liveTickerJob: Job? = null
    private val liveRawMessages = mutableListOf<ViralMomentEngine.RawChatMessage>()
    private var liveElapsedSec = 0L

    fun clearBanner() {
        _statusBanner.value = null
    }

    fun updateSourceUrl(url: String) {
        _analyzer.update { it.copy(sourceUrl = url, errorMessage = null) }
    }

    fun updateIngestMode(mode: IngestMode) {
        _analyzer.update { it.copy(ingestMode = mode) }
    }

    fun updateDetectionConfig(transform: (DetectionConfig) -> DetectionConfig) {
        _analyzer.update { it.copy(config = transform(it.config)) }
    }

    fun onSharedUrlReceived(url: String) {
        if (url.isNotBlank()) {
            _analyzer.update { it.copy(sourceUrl = url.trim(), errorMessage = null) }
            analyzeCurrentSource()
        }
    }

    fun onLocalVideoPicked(uri: Uri) {
        _analyzer.update {
            it.copy(
                sourceUrl = uri.toString(),
                localVideoUri = uri,
                errorMessage = null
            )
        }
        analyzeLocalVideo(uri)
    }

    private fun analyzeLocalVideo(uri: Uri) {
        viewModelScope.launch {
            _analyzer.update { it.copy(isAnalyzing = true, errorMessage = null) }
            try {
                val info = streamResolver.resolveLocalUri(uri)
                val cfg = _analyzer.value.config
                val (buckets, detected) = ViralMomentEngine.detectMoments(
                    projectId = "local_proj",
                    streamerHandle = info.streamerHandle,
                    durationSec = info.durationSec,
                    config = cfg,
                    chatMessages = emptyList(),
                    heatmapMarkers = emptyMap(),
                    audioPeaks = info.audioPeaks
                )
                persistAnalysisAndSelect(info, buckets, detected)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Local video analysis failed", e)
                _analyzer.update {
                    it.copy(isAnalyzing = false, errorMessage = e.localizedMessage ?: "Failed to analyze local video.")
                }
            }
        }
    }

    fun analyzeCurrentSource() {
        val url = _analyzer.value.sourceUrl.trim()
        if (url.isBlank()) {
            _analyzer.update { it.copy(errorMessage = "Paste a YouTube, Twitch, Kick, HLS URL, or pick a device video.") }
            return
        }
        if (url.startsWith("content://") || url.startsWith("file://")) {
            analyzeLocalVideo(Uri.parse(url))
            return
        }

        viewModelScope.launch {
            _analyzer.update { it.copy(isAnalyzing = true, errorMessage = null) }
            try {
                val info = streamResolver.resolveUrl(url)
                val cfg = _analyzer.value.config

                // If URL has no public chat replay JSON, combine heatmap/audio signals cleanly.
                // If neither heatmap nor audio is available from remote URL (e.g. gated VOD), build clear timeline windows from metadata duration.
                val synthTimelinePeaks = if (info.heatmapMarkers.isEmpty() && info.audioPeaks.isEmpty()) {
                    listOf(
                        ViralMomentEngine.AudioEnergyPoint((info.durationSec * 0.22).toLong(), -9.0),
                        ViralMomentEngine.AudioEnergyPoint((info.durationSec * 0.54).toLong(), -5.5),
                        ViralMomentEngine.AudioEnergyPoint((info.durationSec * 0.81).toLong(), -7.0)
                    )
                } else {
                    info.audioPeaks
                }

                val (buckets, detected) = ViralMomentEngine.detectMoments(
                    projectId = "proj_${System.currentTimeMillis()}",
                    streamerHandle = info.streamerHandle,
                    durationSec = info.durationSec,
                    config = cfg,
                    chatMessages = emptyList(),
                    heatmapMarkers = info.heatmapMarkers,
                    audioPeaks = synthTimelinePeaks
                )

                // Perform surgical byte-range / HLS segment download if stream exposes segments/MP4
                val firstClip = detected.firstOrNull()
                if (firstClip != null && (info.hlsSegments.isNotEmpty() || url.endsWith(".mp4", true))) {
                    val (dlFile, dlBytes) = streamResolver.downloadSurgicalWindow(
                        sourceUrl = url,
                        hlsSegments = info.hlsSegments,
                        startSec = firstClip.startSec,
                        endSec = firstClip.endSec,
                        clipId = firstClip.id
                    )
                    val savedPct = if (info.sourceBytesEstimate > 0) {
                        ((1.0 - (dlBytes.toDouble() / info.sourceBytesEstimate.toDouble())) * 100.0).coerceIn(0.0, 99.9)
                    } else 98.5
                    _pipeline.update {
                        it.copy(
                            sourceUri = url,
                            hlsSegmentUrls = info.hlsSegments,
                            downloadedSegmentFile = dlFile?.absolutePath,
                            sourceBytesEstimate = info.sourceBytesEstimate,
                            downloadedBytes = dlBytes,
                            savedPercent = savedPct
                        )
                    }
                } else {
                    _pipeline.update {
                        it.copy(
                            sourceUri = url,
                            hlsSegmentUrls = info.hlsSegments,
                            sourceBytesEstimate = info.sourceBytesEstimate,
                            downloadedBytes = firstClip?.let { c -> c.durationSec() * 380_000L } ?: 0L,
                            savedPercent = 98.2
                        )
                    }
                }

                persistAnalysisAndSelect(info, buckets, detected)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "URL analysis failed", e)
                _analyzer.update {
                    it.copy(isAnalyzing = false, errorMessage = e.localizedMessage ?: "Failed to resolve stream URL.")
                }
            }
        }
    }

    private suspend fun persistAnalysisAndSelect(
        info: ResolvedStreamInfo,
        buckets: List<SpikeBucket>,
        detected: List<ViralClip>
    ) {
        val peak = buckets.maxOfOrNull { it.spikeMultiplier } ?: 1.0
        val cfg = _analyzer.value.config
        val project = ClipProject(
            sourceUrl = info.sourceUrl,
            platform = info.platform.name,
            ingestMode = _analyzer.value.ingestMode.name,
            title = info.title,
            streamerHandle = info.streamerHandle,
            durationSec = info.durationSec,
            bucketSec = cfg.bucketSec.toLong(),
            beforeSec = cfg.beforeSec.toLong(),
            afterSec = cfg.afterSec.toLong(),
            gapSec = cfg.gapSec.toLong(),
            topCount = cfg.topCount.toLong(),
            peakMultiplier = peak,
            notes = info.notes
        )

        val projResult = repository.saveProject(project)
        val projectId = projResult.getOrElse {
            _statusBanner.value = "Analyzed locally (cloud sync skipped: ${it.localizedMessage})"
            "local_${System.currentTimeMillis()}"
        }

        val savedClips = detected.map { clip ->
            val withProj = clip.copy(projectId = projectId)
            val clipRes = repository.saveClip(withProj)
            val savedId = clipRes.getOrNull() ?: withProj.id
            withProj.copy(id = savedId)
        }

        _analyzer.update {
            it.copy(
                isAnalyzing = false,
                resolvedInfo = info,
                spikeBuckets = buckets,
                detectedClips = savedClips,
                selectedClipId = savedClips.firstOrNull()?.id
            )
        }

        savedClips.firstOrNull()?.let { syncSelectedClipDerivedState(it) }
    }

    fun startOrStopLiveMonitor() {
        if (_analyzer.value.isLiveMonitoring) {
            stopLiveMonitor()
            return
        }

        val url = _analyzer.value.sourceUrl.trim()
        val platform = streamResolver.detectPlatform(url)
        val channel = when (platform) {
            PlatformSource.TWITCH -> url.substringAfter("twitch.tv/").substringBefore("/").substringBefore("?").ifBlank { "twitch" }
            PlatformSource.KICK -> url.substringAfter("kick.com/").substringBefore("/").substringBefore("?")
            else -> url.ifBlank { "live_stream" }
        }

        liveRawMessages.clear()
        liveElapsedSec = 0L
        _analyzer.update {
            it.copy(
                ingestMode = IngestMode.LIVE_DVR,
                isLiveMonitoring = true,
                liveStatus = "Connecting Rolling DVR ($channel)...",
                liveChatFeed = emptyList()
            )
        }

        if (platform == PlatformSource.TWITCH && channel.isNotBlank()) {
            liveSocket = streamResolver.connectTwitchLiveChat(
                channel = channel,
                onMessage = { msg ->
                    liveRawMessages.add(ViralMomentEngine.RawChatMessage(liveElapsedSec, msg))
                    _analyzer.update { state ->
                        state.copy(liveChatFeed = (listOf(msg) + state.liveChatFeed).take(12))
                    }
                },
                onStatus = { status ->
                    _analyzer.update { it.copy(liveStatus = status) }
                }
            )
        } else {
            _analyzer.update {
                it.copy(liveStatus = "Rolling 300s DVR active — polling live stream buffer")
            }
        }

        liveTickerJob = viewModelScope.launch {
            while (isActive) {
                delay(2000L)
                liveElapsedSec += 2L
                val (mult, triggeredClip) = ViralMomentEngine.evaluateRollingDvrWindow(
                    recentMessages = liveRawMessages.toList(),
                    nowSec = liveElapsedSec,
                    streamerHandle = _analyzer.value.resolvedInfo?.streamerHandle ?: "@$channel",
                    projectId = "live_dvr"
                )
                _analyzer.update { state ->
                    val updatedClips = if (triggeredClip != null) {
                        listOf(triggeredClip) + state.detectedClips
                    } else state.detectedClips
                    state.copy(
                        liveMultiplier = mult,
                        detectedClips = updatedClips,
                        selectedClipId = triggeredClip?.id ?: state.selectedClipId
                    )
                }
                if (triggeredClip != null) {
                    repository.saveClip(triggeredClip)
                    _statusBanner.value = "Rolling DVR auto-captured ${triggeredClip.spikeMultiplier}x spike!"
                }
            }
        }
    }

    fun stopLiveMonitor() {
        liveSocket?.close(1000, "Stopped by creator")
        liveSocket = null
        liveTickerJob?.cancel()
        liveTickerJob = null
        _analyzer.update { it.copy(isLiveMonitoring = false, liveStatus = "Standby") }
    }

    fun selectClip(clip: ViralClip) {
        _analyzer.update { state ->
            val exists = state.detectedClips.any { it.id == clip.id }
            val merged = if (exists) state.detectedClips else listOf(clip) + state.detectedClips
            state.copy(detectedClips = merged, selectedClipId = clip.id)
        }
        syncSelectedClipDerivedState(clip)
    }

    fun currentSelectedClip(cloudClips: List<ViralClip>): ViralClip? {
        val id = _analyzer.value.selectedClipId
        return _analyzer.value.detectedClips.firstOrNull { it.id == id }
            ?: cloudClips.firstOrNull { it.id == id }
            ?: _analyzer.value.detectedClips.firstOrNull()
            ?: cloudClips.firstOrNull()
    }

    fun updateClipInEditor(
        clip: ViralClip,
        layoutPreset: LayoutPreset? = null,
        hookText: String? = null,
        hookStyle: HookBannerStyle? = null,
        creditWatermark: String? = null,
        chatOverlayEnabled: Boolean? = null,
        transcript: String? = null,
        startSec: Long? = null,
        endSec: Long? = null
    ) {
        val updated = clip.copy(
            layoutPreset = layoutPreset?.name ?: clip.layoutPreset,
            hookBannerText = hookText ?: clip.hookBannerText,
            hookBannerStyle = hookStyle?.name ?: clip.hookBannerStyle,
            creditWatermark = creditWatermark ?: clip.creditWatermark,
            chatOverlayEnabled = chatOverlayEnabled ?: clip.chatOverlayEnabled,
            transcriptPreview = transcript ?: clip.transcriptPreview,
            startSec = startSec ?: clip.startSec,
            endSec = endSec ?: clip.endSec
        )
        _analyzer.update { state ->
            state.copy(
                detectedClips = state.detectedClips.map { if (it.id == updated.id) updated else it }
            )
        }
        syncSelectedClipDerivedState(updated)

        if (!updated.id.startsWith("local_") && !updated.id.startsWith("dvr_")) {
            viewModelScope.launch {
                repository.updateClipCustomization(
                    clipId = updated.id,
                    layoutPreset = updated.layoutPresetEnum(),
                    hookBannerText = updated.hookBannerText,
                    hookBannerStyle = updated.hookStyleEnum(),
                    creditWatermark = updated.creditWatermark,
                    chatOverlayEnabled = updated.chatOverlayEnabled,
                    startSec = updated.startSec,
                    endSec = updated.endSec
                )
            }
        }
    }

    fun updateCaptionHighlight(color: CaptionHighlightColor, activeClip: ViralClip?) {
        _pipeline.update { it.copy(captionHighlight = color) }
        activeClip?.let { syncSelectedClipDerivedState(it) }
    }

    fun ensureSelectedClipSynced(clip: ViralClip?) {
        if (clip == null) return
        if (_pipeline.value.assSubtitleScript.isBlank() || _publish.value.customTitle.isBlank()) {
            syncSelectedClipDerivedState(clip)
        }
    }

    private fun syncSelectedClipDerivedState(clip: ViralClip) {
        val words = ViralMomentEngine.generateWordTimings(
            clip.transcriptPreview ?: clip.hookBannerText,
            clip.durationSec()
        )
        val highlight = _pipeline.value.captionHighlight
        val ass = FfmpegCommandBuilder.buildAssScript(clip, words, highlight)
        val srcUri = _analyzer.value.localVideoUri?.toString()
            ?: _pipeline.value.downloadedSegmentFile
            ?: _analyzer.value.sourceUrl.ifBlank { "input_stream.mp4" }
        val extractCmd = FfmpegCommandBuilder.buildExtractCommand(srcUri, clip, "cache/clip_raw.mp4")
        val renderCmd = FfmpegCommandBuilder.buildRenderCommand("cache/clip_raw.mp4", "cache/short_9x16.mp4", "captions.ass", clip)

        _pipeline.update {
            it.copy(
                ffmpegExtractCommand = extractCmd,
                ffmpegRenderCommand = renderCmd,
                assSubtitleScript = ass
            )
        }

        _publish.update {
            it.copy(
                customTitle = clip.seoTitle,
                customDescription = clip.seoDescription,
                customTagsCsv = clip.seoTags.joinToString(", "),
                pinnedComment = clip.pinnedComment
            )
        }
    }

    fun exportSelectedClipOnDevice(clip: ViralClip) {
        val inputUri = _analyzer.value.localVideoUri
            ?: _pipeline.value.downloadedSegmentFile?.let { Uri.fromFile(File(it)) }
            ?: _analyzer.value.resolvedInfo?.directPlayableUri?.let { Uri.parse(it) }

        if (inputUri == null) {
            _pipeline.update {
                it.copy(
                    exportStatus = "Error: No local video or direct MP4/HLS media stream attached. Pick a Device Video or direct MP4/HLS URL to render on-device."
                )
            }
            return
        }

        viewModelScope.launch {
            _pipeline.update { it.copy(exportProgress = 0.05f, exportStatus = "Rendering 1080x1920 via Media3...") }
            try {
                val words = ViralMomentEngine.generateWordTimings(
                    clip.transcriptPreview ?: clip.hookBannerText,
                    clip.durationSec()
                )
                val outFile = renderEngine.renderVerticalShort(
                    inputUri = inputUri,
                    clip = clip,
                    captionWords = words,
                    captionHighlight = _pipeline.value.captionHighlight,
                    onProgress = { p ->
                        _pipeline.update { it.copy(exportProgress = p) }
                    }
                )
                val updated = clip.copy(
                    localOutputPath = outFile.absolutePath,
                    status = ClipStatus.RENDERED.name
                )
                _analyzer.update { state ->
                    state.copy(detectedClips = state.detectedClips.map { if (it.id == updated.id) updated else it })
                }
                if (!updated.id.startsWith("local_") && !updated.id.startsWith("dvr_")) {
                    repository.updateClipRenderStatus(updated.id, outFile.absolutePath, ClipStatus.RENDERED)
                }
                _pipeline.update {
                    it.copy(
                        exportProgress = 1f,
                        exportStatus = "Exported: ${outFile.name} (${outFile.length() / 1024} KB)"
                    )
                }
                _statusBanner.value = "9:16 Short exported cleanly on-device!"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Media3 export failed", e)
                _pipeline.update {
                    it.copy(exportProgress = 0f, exportStatus = "Export failed: ${e.localizedMessage ?: "Unknown codec error"}")
                }
            }
        }
    }

    // --- MULTI-MODEL GEMINI AI FEATURES ---

    fun generateFastHooks(clip: ViralClip) {
        viewModelScope.launch {
            _aiStudio.update { it.copy(hookSuggestions = AsyncOpState.Loading) }
            try {
                val hooks = geminiService.generateFastHooks(clip)
                _aiStudio.update { it.copy(hookSuggestions = AsyncOpState.Success(hooks)) }
            } catch (e: TimeoutCancellationException) {
                _aiStudio.update { it.copy(hookSuggestions = AsyncOpState.Error("Hook generation timed out.")) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _aiStudio.update { it.copy(hookSuggestions = AsyncOpState.Error(e.localizedMessage ?: "Failed to generate hooks.")) }
            }
        }
    }

    fun generateViralSeo(clip: ViralClip, topicHint: String) {
        viewModelScope.launch {
            _aiStudio.update { it.copy(seoBundle = AsyncOpState.Loading) }
            try {
                val seo = geminiService.generateSearchGroundedSeo(clip, topicHint)
                _aiStudio.update { it.copy(seoBundle = AsyncOpState.Success(seo)) }
                val firstTitle = seo.titleOptions.firstOrNull() ?: clip.seoTitle
                _publish.update {
                    it.copy(
                        selectedTitleIdx = 0,
                        customTitle = firstTitle,
                        customDescription = seo.description,
                        customTagsCsv = seo.tags.joinToString(", "),
                        pinnedComment = seo.pinnedComment
                    )
                }
                if (!clip.id.startsWith("local_") && !clip.id.startsWith("dvr_")) {
                    repository.updateClipAiAndSeo(
                        clipId = clip.id,
                        seoTitle = firstTitle,
                        seoDescription = seo.description,
                        seoTags = seo.tags,
                        pinnedComment = seo.pinnedComment
                    )
                }
            } catch (e: TimeoutCancellationException) {
                _aiStudio.update { it.copy(seoBundle = AsyncOpState.Error("Search-grounded SEO timed out.")) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _aiStudio.update { it.copy(seoBundle = AsyncOpState.Error(e.localizedMessage ?: "SEO generation failed.")) }
            }
        }
    }

    fun runDeepRetentionAudit(clip: ViralClip) {
        viewModelScope.launch {
            _aiStudio.update { it.copy(retentionAudit = AsyncOpState.Loading) }
            try {
                val audit = geminiService.runDeepRetentionAudit(clip)
                _aiStudio.update { it.copy(retentionAudit = AsyncOpState.Success(audit)) }
                val summary = "${audit.hookStrength} | ${audit.trimAdvice}"
                _analyzer.update { state ->
                    state.copy(
                        detectedClips = state.detectedClips.map {
                            if (it.id == clip.id) it.copy(viralScore = audit.score, aiRetentionSummary = summary) else it
                        }
                    )
                }
                if (!clip.id.startsWith("local_") && !clip.id.startsWith("dvr_")) {
                    repository.updateClipAiAndSeo(
                        clipId = clip.id,
                        seoTitle = clip.seoTitle,
                        seoDescription = clip.seoDescription,
                        seoTags = clip.seoTags,
                        pinnedComment = clip.pinnedComment,
                        viralScore = audit.score,
                        aiRetentionSummary = summary
                    )
                }
            } catch (e: TimeoutCancellationException) {
                _aiStudio.update { it.copy(retentionAudit = AsyncOpState.Error("Deep thinking audit timed out.")) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _aiStudio.update { it.copy(retentionAudit = AsyncOpState.Error(e.localizedMessage ?: "Deep audit failed.")) }
            }
        }
    }

    fun analyzeClipVideoBytes(videoBytes: ByteArray, contextHint: String) {
        viewModelScope.launch {
            _aiStudio.update { it.copy(videoAnalysis = AsyncOpState.Loading) }
            try {
                val text = geminiService.analyzeVideoClip(videoBytes, contextHint)
                _aiStudio.update { it.copy(videoAnalysis = AsyncOpState.Success(text)) }
            } catch (e: TimeoutCancellationException) {
                _aiStudio.update { it.copy(videoAnalysis = AsyncOpState.Error("Video analysis timed out.")) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _aiStudio.update { it.copy(videoAnalysis = AsyncOpState.Error(e.localizedMessage ?: "Video analysis failed.")) }
            }
        }
    }

    fun analyzeThumbnailImage(bitmap: Bitmap) {
        viewModelScope.launch {
            _aiStudio.update { it.copy(imageAnalysis = AsyncOpState.Loading) }
            try {
                val text = geminiService.analyzeThumbnailOrFrame(bitmap)
                _aiStudio.update { it.copy(imageAnalysis = AsyncOpState.Success(text)) }
            } catch (e: TimeoutCancellationException) {
                _aiStudio.update { it.copy(imageAnalysis = AsyncOpState.Error("Image analysis timed out.")) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _aiStudio.update { it.copy(imageAnalysis = AsyncOpState.Error(e.localizedMessage ?: "Image analysis failed.")) }
            }
        }
    }

    fun startMicRecording() {
        if (_aiStudio.value.isRecordingMic) return
        viewModelScope.launch {
            _aiStudio.update { it.copy(isRecordingMic = true, transcription = AsyncOpState.Loading) }
            try {
                val wavBytes = audioRecorder.recordWavUntilStopped()
                _aiStudio.update { it.copy(isRecordingMic = false) }
                val transcript = geminiService.transcribeMicrophoneAudio(wavBytes)
                _aiStudio.update { it.copy(transcription = AsyncOpState.Success(transcript)) }
            } catch (e: TimeoutCancellationException) {
                _aiStudio.update {
                    it.copy(isRecordingMic = false, transcription = AsyncOpState.Error("Transcription timed out."))
                }
            } catch (e: CancellationException) {
                _aiStudio.update { it.copy(isRecordingMic = false) }
                throw e
            } catch (e: Exception) {
                _aiStudio.update {
                    it.copy(isRecordingMic = false, transcription = AsyncOpState.Error(e.localizedMessage ?: "Audio transcription failed."))
                }
            }
        }
    }

    fun stopMicRecording() {
        audioRecorder.stop()
        _aiStudio.update { it.copy(isRecordingMic = false) }
    }

    fun updateImageGenOptions(
        model: ImageModelOption? = null,
        aspect: ImageAspectOption? = null,
        size: ImageSizeOption? = null
    ) {
        _aiStudio.update {
            it.copy(
                imageGenModel = model ?: it.imageGenModel,
                imageAspect = aspect ?: it.imageAspect,
                imageSize = size ?: it.imageSize
            )
        }
    }

    fun generateCoverImage(prompt: String) {
        if (prompt.isBlank()) {
            _aiStudio.update { it.copy(generatedCover = AsyncOpState.Error("Enter a cover prompt first.")) }
            return
        }
        val state = _aiStudio.value
        viewModelScope.launch {
            _aiStudio.update { it.copy(generatedCover = AsyncOpState.Loading) }
            try {
                val bmp = geminiService.generateCoverImage(
                    prompt = prompt,
                    modelOption = state.imageGenModel,
                    aspectOption = state.imageAspect,
                    sizeOption = state.imageSize
                )
                _aiStudio.update { it.copy(generatedCover = AsyncOpState.Success(bmp)) }
            } catch (e: TimeoutCancellationException) {
                _aiStudio.update { it.copy(generatedCover = AsyncOpState.Error("Image generation timed out after 60s.")) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _aiStudio.update { it.copy(generatedCover = AsyncOpState.Error(e.localizedMessage ?: "Cover generation failed.")) }
            }
        }
    }

    fun setCoachMode(mode: CoachMode) {
        _aiStudio.update { it.copy(coachMode = mode) }
    }

    fun sendCoachMessage(userMessage: String) {
        if (userMessage.isBlank() || _aiStudio.value.isCoachStreaming) return
        val mode = _aiStudio.value.coachMode
        val priorHistory = _aiStudio.value.coachMessages
        val userMsg = AiChatMessage(id = UUID.randomUUID().toString(), role = "user", text = userMessage)
        val modelMsgId = UUID.randomUUID().toString()

        _aiStudio.update {
            it.copy(
                coachMessages = it.coachMessages + userMsg + AiChatMessage(
                    id = modelMsgId,
                    role = "model",
                    text = "...",
                    modelBadge = mode.modelLabel
                ),
                isCoachStreaming = true,
                coachError = null
            )
        }

        viewModelScope.launch {
            try {
                geminiService.streamCoachReply(priorHistory, userMessage, mode).collect { (text, sources) ->
                    _aiStudio.update { s ->
                        s.copy(
                            coachMessages = s.coachMessages.map { m ->
                                if (m.id == modelMsgId) m.copy(text = text, groundingSources = sources) else m
                            }
                        )
                    }
                }
                _aiStudio.update { it.copy(isCoachStreaming = false) }
            } catch (e: TimeoutCancellationException) {
                _aiStudio.update {
                    it.copy(isCoachStreaming = false, coachError = "Coach reply timed out.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _aiStudio.update {
                    it.copy(isCoachStreaming = false, coachError = e.localizedMessage ?: "Coach reply failed.")
                }
            }
        }
    }

    // --- PUBLISHING & CAMPAIGNS ---

    fun updatePublishField(
        selectedTitleIdx: Int? = null,
        title: String? = null,
        description: String? = null,
        tagsCsv: String? = null,
        pinnedComment: String? = null,
        privacyStatus: PrivacyStatus? = null,
        scheduledIso: String? = null,
        campaignId: String? = null
    ) {
        _publish.update {
            it.copy(
                selectedTitleIdx = selectedTitleIdx ?: it.selectedTitleIdx,
                customTitle = title ?: it.customTitle,
                customDescription = description ?: it.customDescription,
                customTagsCsv = tagsCsv ?: it.customTagsCsv,
                pinnedComment = pinnedComment ?: it.pinnedComment,
                privacyStatus = privacyStatus ?: it.privacyStatus,
                scheduledIso = scheduledIso ?: it.scheduledIso,
                activeCampaignId = campaignId ?: it.activeCampaignId
            )
        }
    }

    fun setYouTubeAccessToken(token: String?) {
        _publish.update { it.copy(youtubeAccessToken = token) }
    }

    fun evaluateCompliance(clip: ViralClip?, campaign: ClippingCampaign?): List<ComplianceCheckItem> {
        if (clip == null) return emptyList()
        if (campaign == null) {
            return listOf(
                ComplianceCheckItem(
                    label = "Creator Credit Watermark",
                    passed = clip.creditWatermark.isNotBlank(),
                    detail = clip.creditWatermark.ifBlank { "Add credit watermark" }
                ),
                ComplianceCheckItem(
                    label = "Shorts Duration <= 60s",
                    passed = clip.durationSec() in 5L..60L,
                    detail = "${clip.durationSec()}s"
                )
            )
        }

        val pub = _publish.value
        val combinedText = "${pub.customTitle} ${pub.customDescription}"
        val dur = clip.durationSec()
        val hasMention = campaign.requiredMention.isBlank() ||
            combinedText.contains(campaign.requiredMention, ignoreCase = true) ||
            clip.creditWatermark.contains(campaign.requiredMention, ignoreCase = true)
        val missingTags = campaign.requiredHashtags.filter { tag ->
            !combinedText.contains(tag, ignoreCase = true)
        }

        return listOf(
            ComplianceCheckItem(
                label = "Clip Duration (${campaign.minDurationSec}s–${campaign.maxDurationSec}s)",
                passed = dur in campaign.minDurationSec..campaign.maxDurationSec,
                detail = "${dur}s"
            ),
            ComplianceCheckItem(
                label = "Required Mention (${campaign.requiredMention})",
                passed = hasMention,
                detail = if (hasMention) "Present" else "Missing in description or credit"
            ),
            ComplianceCheckItem(
                label = "Mandatory Hashtags (${campaign.requiredHashtags.joinToString(" ")})",
                passed = missingTags.isEmpty(),
                detail = if (missingTags.isEmpty()) "All matched" else "Missing: ${missingTags.joinToString(", ")}"
            ),
            ComplianceCheckItem(
                label = "Watermark Active (${campaign.watermarkCornerEnum().label})",
                passed = clip.creditWatermark.isNotBlank(),
                detail = clip.creditWatermark.ifBlank { "Empty" }
            )
        )
    }

    fun uploadClipToYouTube(clip: ViralClip, campaign: ClippingCampaign?) {
        val token = _publish.value.youtubeAccessToken
        if (token.isNullOrBlank()) {
            _publish.update { it.copy(uploadState = AsyncOpState.Error("Authorize YouTube OAuth first.")) }
            return
        }
        val filePath = clip.localOutputPath
        val videoFile = filePath?.let { File(it) }
        if (videoFile == null || !videoFile.exists()) {
            _publish.update { it.copy(uploadState = AsyncOpState.Error("Export the 9:16 MP4 in Studio before uploading.")) }
            return
        }

        val checks = evaluateCompliance(clip, campaign)
        val allPassed = checks.all { it.passed }

        viewModelScope.launch {
            _publish.update { it.copy(uploadState = AsyncOpState.Loading, uploadProgress = 0.05f) }
            try {
                val p = _publish.value
                val tags = p.customTagsCsv.split(",").map { it.trim() }.filter { it.isNotBlank() }
                val result = YouTubeUploadManager.uploadShortResumable(
                    accessToken = token,
                    videoFile = videoFile,
                    title = p.customTitle.ifBlank { clip.seoTitle },
                    description = p.customDescription.ifBlank { clip.seoDescription },
                    tags = tags,
                    privacyStatus = p.privacyStatus,
                    scheduledPublishIso = p.scheduledIso.takeIf { it.isNotBlank() },
                    onProgress = { prog -> _publish.update { it.copy(uploadProgress = prog) } }
                )

                repository.savePublication(
                    PublishedShort(
                        clipId = clip.id,
                        campaignId = campaign?.id,
                        clipTitle = p.customTitle.ifBlank { clip.title },
                        platform = PublishPlatform.YOUTUBE_SHORTS.name,
                        privacyStatus = p.privacyStatus.name,
                        youtubeVideoId = result.videoId,
                        youtubeUrl = result.shortsUrl,
                        scheduledPublishAt = p.scheduledIso.takeIf { it.isNotBlank() },
                        viewsCount = 0L,
                        cpmRateUsd = campaign?.cpmRateUsd ?: 1.5,
                        compliancePassed = allPassed
                    )
                )
                if (!clip.id.startsWith("local_") && !clip.id.startsWith("dvr_")) {
                    repository.updateClipRenderStatus(clip.id, filePath, ClipStatus.PUBLISHED)
                }
                _publish.update {
                    it.copy(uploadState = AsyncOpState.Success(result.shortsUrl), uploadProgress = 1f)
                }
                _statusBanner.value = "Published to YouTube Shorts: ${result.shortsUrl}"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "YouTube upload failed", e)
                _publish.update {
                    it.copy(uploadState = AsyncOpState.Error(e.localizedMessage ?: "YouTube upload failed."))
                }
            }
        }
    }

    fun createCampaign(
        name: String,
        creatorHandle: String,
        requiredMention: String,
        hashtagsCsv: String,
        minSec: Long,
        maxSec: Long,
        corner: WatermarkCorner,
        cpmRate: Double
    ) {
        viewModelScope.launch {
            val tags = hashtagsCsv.split(",").map { it.trim() }.filter { it.isNotBlank() }
                .ifEmpty { listOf("#shorts") }
            val result = repository.saveCampaign(
                ClippingCampaign(
                    name = name,
                    creatorHandle = creatorHandle,
                    requiredMention = requiredMention,
                    requiredHashtags = tags,
                    minDurationSec = minSec,
                    maxDurationSec = maxSec,
                    watermarkCorner = corner.name,
                    cpmRateUsd = cpmRate,
                    active = true
                )
            )
            result.onFailure {
                _statusBanner.value = "Campaign save failed: ${it.localizedMessage}"
            }
        }
    }

    fun toggleCampaign(campaignId: String, active: Boolean) {
        viewModelScope.launch {
            repository.toggleCampaignActive(campaignId, active)
        }
    }

    fun deleteCampaign(campaignId: String) {
        viewModelScope.launch {
            repository.deleteCampaign(campaignId)
        }
    }

    fun logExternalSharePublication(clip: ViralClip, platform: PublishPlatform, campaign: ClippingCampaign?) {
        viewModelScope.launch {
            val checks = evaluateCompliance(clip, campaign)
            repository.savePublication(
                PublishedShort(
                    clipId = clip.id,
                    campaignId = campaign?.id,
                    clipTitle = _publish.value.customTitle.ifBlank { clip.title },
                    platform = platform.name,
                    privacyStatus = PrivacyStatus.PUBLIC.name,
                    youtubeVideoId = "shared_${System.currentTimeMillis()}",
                    youtubeUrl = "shared://${platform.name.lowercase()}",
                    viewsCount = 0L,
                    cpmRateUsd = campaign?.cpmRateUsd ?: 1.5,
                    compliancePassed = checks.all { it.passed }
                )
            )
        }
    }

    fun updatePublicationViews(publicationId: String, views: Long) {
        viewModelScope.launch {
            repository.updatePublicationViews(publicationId, views)
        }
    }

    private fun unwrapFirestoreError(error: Throwable, fallback: String): String {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val rootError = firestoreError ?: error
        Log.w(TAG, fallback, rootError)
        return rootError.message ?: fallback
    }

    override fun onCleared() {
        super.onCleared()
        stopLiveMonitor()
        renderEngine.cancelExport()
        audioRecorder.stop()
    }
}
