package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.res.ResourcesCompat
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.TextureOverlay
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExoPlayerAssetLoader
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.example.R
import com.example.data.model.CaptionHighlightColor
import com.example.data.model.CaptionWord
import com.example.data.model.HookBannerStyle
import com.example.data.model.LayoutPreset
import com.example.data.model.ViralClip
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "Media3RenderEngine"
private const val DEFAULT_RENDER_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

class Media3RenderEngine(private val context: Context) {

    @Volatile
    private var activeTransformer: Transformer? = null

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    companion object {
        /**
         * Computes a safe [startMs, endMs] clipping window within [actualDurationMs], or null if
         * the full asset should be rendered without clipping.
         */
        fun computeSafeTrimWindowMs(
            clipStartSec: Long,
            clipEndSec: Long,
            actualDurationMs: Long?,
            isPreSlicedFile: Boolean
        ): Pair<Long, Long>? {
            val requestedDurMs = ((clipEndSec - clipStartSec).coerceAtLeast(1L)) * 1000L
            val rawStartMs = if (isPreSlicedFile) 0L else (clipStartSec * 1000L).coerceAtLeast(0L)
            val rawEndMs = if (isPreSlicedFile) requestedDurMs else (clipEndSec * 1000L).coerceAtLeast(rawStartMs + 1000L)

            if (actualDurationMs == null || actualDurationMs <= 0L) {
                return if (isPreSlicedFile) null else rawStartMs to rawEndMs
            }

            if (actualDurationMs <= 1200L) {
                return null
            }

            val clampedStart = rawStartMs.coerceIn(0L, (actualDurationMs - 1000L).coerceAtLeast(0L))
            val clampedEnd = rawEndMs.coerceIn(clampedStart + 500L, actualDurationMs)

            return if (clampedStart == 0L && clampedEnd >= actualDurationMs - 250L) {
                null
            } else {
                clampedStart to clampedEnd
            }
        }
    }

    @OptIn(UnstableApi::class)
    suspend fun renderVerticalShort(
        inputUri: Uri,
        clip: ViralClip,
        captionWords: List<CaptionWord>,
        captionHighlight: CaptionHighlightColor = CaptionHighlightColor.VIBRANT_YELLOW,
        isPreSlicedFile: Boolean = false,
        httpUserAgent: String? = null,
        httpHeaders: Map<String, String> = emptyMap(),
        onProgress: (Float) -> Unit
    ): File {
        val effectiveUa = httpUserAgent?.takeIf { it.isNotBlank() } ?: DEFAULT_RENDER_USER_AGENT
        val initialUri = prepareSafeInputUri(inputUri, forceStageContent = false)

        return try {
            executeTransformerRender(
                resolvedUri = initialUri,
                clip = clip,
                captionWords = captionWords,
                captionHighlight = captionHighlight,
                isPreSlicedFile = isPreSlicedFile,
                userAgent = effectiveUa,
                httpHeaders = httpHeaders,
                onProgress = onProgress
            )
        } catch (primaryError: Exception) {
            val scheme = initialUri.scheme.orEmpty().lowercase()
            val canFallbackStageContent = scheme == "content"
            val canFallbackDownloadHttp = (scheme == "http" || scheme == "https") &&
                !initialUri.toString().contains(".m3u8", ignoreCase = true)

            if (canFallbackStageContent) {
                Log.w(TAG, "Direct content:// render failed, staging to cache and retrying", primaryError)
                onProgress(0.05f)
                val cachedLocalUri = prepareSafeInputUri(initialUri, forceStageContent = true)
                return executeTransformerRender(
                    resolvedUri = cachedLocalUri,
                    clip = clip,
                    captionWords = captionWords,
                    captionHighlight = captionHighlight,
                    isPreSlicedFile = isPreSlicedFile,
                    userAgent = effectiveUa,
                    httpHeaders = httpHeaders,
                    onProgress = onProgress
                )
            }

            if (canFallbackDownloadHttp) {
                Log.w(TAG, "Direct HTTP stream render failed, caching remote MP4 to disk and retrying", primaryError)
                onProgress(0.05f)
                val (cachedRemoteUri, httpErrDetail) = downloadRemoteToCache(initialUri, effectiveUa, httpHeaders)
                if (cachedRemoteUri != null) {
                    return executeTransformerRender(
                        resolvedUri = cachedRemoteUri,
                        clip = clip,
                        captionWords = captionWords,
                        captionHighlight = captionHighlight,
                        isPreSlicedFile = isPreSlicedFile,
                        userAgent = effectiveUa,
                        httpHeaders = httpHeaders,
                        onProgress = onProgress
                    )
                }
                if (!httpErrDetail.isNullOrBlank()) {
                    throw IllegalStateException(
                        "${formatExportFailure(primaryError)} ($httpErrDetail)",
                        primaryError
                    )
                }
            }

            throw IllegalStateException(formatExportFailure(primaryError), primaryError)
        }
    }

    @OptIn(UnstableApi::class)
    private suspend fun executeTransformerRender(
        resolvedUri: Uri,
        clip: ViralClip,
        captionWords: List<CaptionWord>,
        captionHighlight: CaptionHighlightColor,
        isPreSlicedFile: Boolean,
        userAgent: String,
        httpHeaders: Map<String, String>,
        onProgress: (Float) -> Unit
    ): File {
        val actualDurationMs = withContext(Dispatchers.IO) { probeDurationMs(resolvedUri, userAgent, httpHeaders) }
        val trimWindow = computeSafeTrimWindowMs(
            clipStartSec = clip.startSec,
            clipEndSec = clip.endSec,
            actualDurationMs = actualDurationMs,
            isPreSlicedFile = isPreSlicedFile
        )

        return withContext(Dispatchers.Main) {
            val outputDir = File(context.cacheDir, "clipper/clips").apply { mkdirs() }
            val outputFile = File(outputDir, "${clip.id}_9x16.mp4")
            if (outputFile.exists()) outputFile.delete()

            val mediaItemBuilder = MediaItem.Builder().setUri(resolvedUri)
            if (trimWindow != null) {
                mediaItemBuilder.setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(trimWindow.first)
                        .setEndPositionMs(trimWindow.second)
                        .build()
                )
            }
            val mediaItem = mediaItemBuilder.build()

            val layoutMode = when (clip.layoutPresetEnum()) {
                LayoutPreset.CINEMA_BLUR -> Presentation.LAYOUT_SCALE_TO_FIT
                LayoutPreset.WORD_POP, LayoutPreset.SPLIT_SCREEN, LayoutPreset.FULL_CROP ->
                    Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
            }

            val presentationEffect = Presentation.createForWidthAndHeight(
                1080,
                1920,
                layoutMode
            )

            val dynamicOverlay = ShortsDynamicBitmapOverlay(
                context = context,
                clip = clip,
                words = captionWords,
                captionHighlight = captionHighlight
            )
            val overlayEffect = OverlayEffect(ImmutableList.of<TextureOverlay>(dynamicOverlay))

            val videoEffects: List<Effect> = listOf(presentationEffect, overlayEffect)
            val editedMediaItem = EditedMediaItem.Builder(mediaItem)
                .setEffects(Effects(emptyList(), videoEffects))
                .build()

            val composition = Composition.Builder(
                EditedMediaItemSequence.Builder(editedMediaItem).build()
            ).build()

            val httpFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(20_000)
                .setReadTimeoutMs(30_000)
                .apply {
                    if (httpHeaders.isNotEmpty()) {
                        setDefaultRequestProperties(httpHeaders)
                    }
                }
            val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
            val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
            val decoderFactory = DefaultDecoderFactory.Builder(context).build()
            val assetLoaderFactory = ExoPlayerAssetLoader.Factory(
                context,
                decoderFactory,
                Clock.DEFAULT,
                mediaSourceFactory
            )
            val encoderFactory = DefaultEncoderFactory.Builder(context)
                .setEnableFallback(true)
                .build()

            suspendCancellableCoroutine { cont ->
                val mainHandler = Handler(Looper.getMainLooper())
                var finished = false
                val progressHolder = ProgressHolder()
                lateinit var transformer: Transformer

                val progressPoller = object : Runnable {
                    override fun run() {
                        if (finished) return
                        val state = transformer.getProgress(progressHolder)
                        if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                            onProgress((progressHolder.progress / 100f).coerceIn(0f, 0.99f))
                        }
                        if (!finished && state != Transformer.PROGRESS_STATE_NOT_STARTED) {
                            mainHandler.postDelayed(this, 250L)
                        }
                    }
                }

                transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setAssetLoaderFactory(assetLoaderFactory)
                    .setEncoderFactory(encoderFactory)
                    .setMaxDelayBetweenMuxerSamplesMs(C.TIME_UNSET)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            finished = true
                            activeTransformer = null
                            mainHandler.removeCallbacks(progressPoller)
                            onProgress(1f)
                            if (cont.isActive) cont.resume(outputFile)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            finished = true
                            activeTransformer = null
                            mainHandler.removeCallbacks(progressPoller)
                            Log.e(TAG, "Media3 export failed: ${exportException.errorCodeName}", exportException)
                            if (cont.isActive) cont.resumeWithException(exportException)
                        }
                    })
                    .build()

                activeTransformer = transformer

                cont.invokeOnCancellation {
                    finished = true
                    activeTransformer = null
                    mainHandler.removeCallbacks(progressPoller)
                    runCatching { transformer.cancel() }
                }

                try {
                    transformer.start(composition, outputFile.absolutePath)
                    mainHandler.postDelayed(progressPoller, 250L)
                } catch (e: Exception) {
                    finished = true
                    activeTransformer = null
                    mainHandler.removeCallbacks(progressPoller)
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }
        }
    }

    private suspend fun prepareSafeInputUri(inputUri: Uri, forceStageContent: Boolean): Uri = withContext(Dispatchers.IO) {
        if (inputUri.scheme != "content") return@withContext inputUri
        val sizeBytes = runCatching {
            context.contentResolver.query(inputUri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getLong(0) else -1L
            }
        }.getOrNull() ?: -1L

        val shouldStage = forceStageContent || (sizeBytes in 1L..200_000_000L)
        if (!shouldStage) return@withContext inputUri

        val inputDir = File(context.cacheDir, "clipper/inputs").apply { mkdirs() }
        val stagedFile = File(inputDir, "local_${inputUri.toString().hashCode().toUInt()}.mp4")
        if (stagedFile.exists() && stagedFile.length() > 0L && probeDurationMs(Uri.fromFile(stagedFile), DEFAULT_RENDER_USER_AGENT, emptyMap()) != null) {
            return@withContext Uri.fromFile(stagedFile)
        }

        return@withContext runCatching {
            context.contentResolver.openInputStream(inputUri)?.use { input ->
                stagedFile.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            }
            if (stagedFile.exists() && stagedFile.length() > 0L) Uri.fromFile(stagedFile) else inputUri
        }.getOrElse { inputUri }
    }

    private suspend fun downloadRemoteToCache(
        remoteUri: Uri,
        userAgent: String,
        httpHeaders: Map<String, String>
    ): Pair<Uri?, String?> = withContext(Dispatchers.IO) {
        val url = remoteUri.toString()
        val inputDir = File(context.cacheDir, "clipper/inputs").apply { mkdirs() }
        val targetFile = File(inputDir, "remote_${url.hashCode().toUInt()}.mp4")
        if (targetFile.exists() && targetFile.length() > 100_000L &&
            probeDurationMs(Uri.fromFile(targetFile), userAgent, emptyMap()) != null
        ) {
            return@withContext Uri.fromFile(targetFile) to null
        }
        if (targetFile.exists()) targetFile.delete()

        return@withContext try {
            val reqBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
            httpHeaders.forEach { (k, v) -> reqBuilder.header(k, v) }
            downloadClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@use null to "HTTP ${resp.code} ${resp.message}"
                }
                val contentType = resp.header("Content-Type").orEmpty().lowercase()
                if (contentType.startsWith("text/html")) {
                    return@use null to "Server returned HTML instead of MP4"
                }
                val body = resp.body ?: return@use null to "Empty HTTP response body"
                val maxCapBytes = 250_000_000L
                val contentLen = body.contentLength()
                if (contentLen > maxCapBytes) return@use null to "Remote MP4 exceeds 250MB cache limit"
                body.byteStream().use { input ->
                    targetFile.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            total += read
                            if (total > maxCapBytes) break
                            output.write(buffer, 0, read)
                        }
                    }
                }
                if (targetFile.exists() && targetFile.length() > 10_000L) {
                    Uri.fromFile(targetFile) to null
                } else {
                    targetFile.delete()
                    null to "Downloaded stream was empty"
                }
            }
        } catch (e: Exception) {
            null to (e.localizedMessage ?: "Network download failed")
        }
    }

    private fun probeDurationMs(uri: Uri, userAgent: String, httpHeaders: Map<String, String>): Long? {
        val scheme = uri.scheme.orEmpty().lowercase()
        if (scheme == "http" || scheme == "https") {
            return null
        }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    @OptIn(UnstableApi::class)
    private fun formatExportFailure(error: Throwable): String {
        if (error is ExportException) {
            val detail = error.cause?.localizedMessage ?: error.localizedMessage ?: "Unsupported or unreachable media source"
            return "${error.errorCodeName}: $detail"
        }
        return error.localizedMessage ?: "Unknown codec error"
    }

    fun cancelExport() {
        runCatching { activeTransformer?.cancel() }
        activeTransformer = null
    }
}

@OptIn(UnstableApi::class)
private class ShortsDynamicBitmapOverlay(
    context: Context,
    private val clip: ViralClip,
    private val words: List<CaptionWord>,
    private val captionHighlight: CaptionHighlightColor
) : BitmapOverlay() {

    private val width = 1080
    private val height = 1920
    private val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)

    private val montserratBlackTypeface: Typeface = run {
        val base = runCatching { ResourcesCompat.getFont(context, R.font.montserrat) }.getOrNull()
            ?: Typeface.SANS_SERIF
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(base, 900, false)
        } else {
            Typeface.create(base, Typeface.BOLD)
        }
    }

    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hookTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = montserratBlackTypeface
        textSize = 52f
        textAlign = Paint.Align.CENTER
    }
    private val creditPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        setShadowLayer(6f, 2f, 2f, Color.BLACK)
    }
    private val captionStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 16f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = Color.BLACK
        textSize = 76f
        typeface = montserratBlackTypeface
        textAlign = Paint.Align.LEFT
        setShadowLayer(12f, 0f, 6f, Color.argb(225, 0, 0, 0))
    }
    private val captionFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = 76f
        typeface = montserratBlackTypeface
        textAlign = Paint.Align.LEFT
    }
    private val chatBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 12, 16, 26)
    }
    private val chatTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        typeface = Typeface.MONOSPACE
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val timeMs = presentationTimeUs / 1000L
        bitmap.eraseColor(Color.TRANSPARENT)

        // 1. Creator Credit Watermark
        if (clip.creditWatermark.isNotBlank()) {
            pillPaint.color = Color.argb(145, 9, 11, 16)
            val textWidth = creditPaint.measureText(clip.creditWatermark)
            val badgeRect = RectF(44f, 64f, 44f + textWidth + 36f, 124f)
            canvas.drawRoundRect(badgeRect, 18f, 18f, pillPaint)
            canvas.drawText(clip.creditWatermark, 62f, 105f, creditPaint)
        }

        // 2. First-Second Viral Hook Banner (0.0s - 3.5s)
        if (timeMs <= 3500L && clip.hookBannerText.isNotBlank()) {
            val (bg, fg) = when (clip.hookStyleEnum()) {
                HookBannerStyle.VOLT_PILL -> Color.parseColor("#E6FF00") to Color.parseColor("#090B10")
                HookBannerStyle.WHITE_PILL -> Color.WHITE to Color.BLACK
                HookBannerStyle.CRIMSON_ALERT -> Color.parseColor("#FF3B5C") to Color.WHITE
            }
            pillPaint.color = bg
            hookTextPaint.color = fg
            val bannerWidth = (hookTextPaint.measureText(clip.hookBannerText) + 84f).coerceAtMost(width - 96f)
            val left = (width - bannerWidth) / 2f
            val rect = RectF(left, 185f, left + bannerWidth, 278f)
            canvas.drawRoundRect(rect, 36f, 36f, pillPaint)
            canvas.drawText(clip.hookBannerText, width / 2f, 249f, hookTextPaint)
        }

        // 3. Optional Mini Chat Burst Badge
        if (clip.chatOverlayEnabled) {
            val boxRect = RectF(44f, 1180f, 540f, 1280f)
            canvas.drawRoundRect(boxRect, 20f, 20f, chatBoxPaint)
            canvas.drawText("CHAT: ${clip.spikeMultiplier}x SPIKE 🔥", 64f, 1240f, chatTextPaint)
        }

        // 4. Word-by-Word Montserrat Black Pop Captions in lower-third (y = 1530 matches MarginV=390)
        val activeIdx = words.indexOfFirst { timeMs in it.startMs..it.endMs }
        if (activeIdx >= 0) {
            val windowStart = (activeIdx / 3) * 3
            val windowEnd = minOf(windowStart + 3, words.size)
            val group = words.subList(windowStart, windowEnd)
            val activeInGroup = activeIdx - windowStart
            val fullLine = group.joinToString(" ") { it.word.uppercase() }
            val y = 1530f

            val totalWidth = captionFillPaint.measureText(fullLine)
            var cursorX = (width - totalWidth) / 2f
            val spaceW = captionFillPaint.measureText(" ")

            group.forEachIndexed { idx, w ->
                val txt = w.word.uppercase()
                val tokenWidth = captionFillPaint.measureText(txt)
                val isActive = idx == activeInGroup

                val scale = if (isActive) {
                    val wordDur = (w.endMs - w.startMs).coerceAtLeast(120L)
                    val elapsed = (timeMs - w.startMs).coerceAtLeast(0L)
                    val popInMs = minOf(65L, wordDur / 3L).coerceAtLeast(1L)
                    val popSettleMs = minOf(170L, (wordDur * 2L) / 3L).coerceAtLeast(popInMs + 1L)
                    when {
                        elapsed <= popInMs -> 1.0f + 0.18f * (elapsed.toFloat() / popInMs.toFloat())
                        elapsed <= popSettleMs -> {
                            val settleProgress = (elapsed - popInMs).toFloat() / (popSettleMs - popInMs).toFloat()
                            1.18f - 0.14f * settleProgress
                        }
                        else -> 1.04f
                    }
                } else {
                    1.0f
                }

                val centerX = cursorX + tokenWidth / 2f
                canvas.save()
                canvas.scale(scale, scale, centerX, y - 24f)
                canvas.drawText(txt, cursorX, y, captionStrokePaint)
                captionFillPaint.color = if (isActive) captionHighlight.argbHex else Color.WHITE
                canvas.drawText(txt, cursorX, y, captionFillPaint)
                canvas.restore()

                cursorX += tokenWidth + spaceW
            }
        }

        return bitmap
    }
}
