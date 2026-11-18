package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.res.ResourcesCompat
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
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
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "Media3RenderEngine"

class Media3RenderEngine(private val context: Context) {

    @Volatile
    private var activeTransformer: Transformer? = null

    @OptIn(UnstableApi::class)
    suspend fun renderVerticalShort(
        inputUri: Uri,
        clip: ViralClip,
        captionWords: List<CaptionWord>,
        captionHighlight: CaptionHighlightColor = CaptionHighlightColor.VIBRANT_YELLOW,
        onProgress: (Float) -> Unit
    ): File = withContext(Dispatchers.Main) {
        val outputDir = File(context.cacheDir, "clipper/clips").apply { mkdirs() }
        val outputFile = File(outputDir, "${clip.id}_9x16.mp4")
        if (outputFile.exists()) outputFile.delete()

        val startMs = (clip.startSec * 1000L).coerceAtLeast(0L)
        val endMs = (clip.endSec * 1000L).coerceAtLeast(startMs + 1000L)

        val mediaItem = MediaItem.Builder()
            .setUri(inputUri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs)
                    .setEndPositionMs(endMs)
                    .build()
            )
            .build()

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
                        Log.e(TAG, "Media3 export failed", exportException)
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
