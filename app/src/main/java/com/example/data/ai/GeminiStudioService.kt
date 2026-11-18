package com.example.data.ai

import android.graphics.Bitmap
import android.util.Log
import com.example.data.model.AiChatMessage
import com.example.data.model.CoachMode
import com.example.data.model.ImageAspectOption
import com.example.data.model.ImageModelOption
import com.example.data.model.ImageSizeOption
import com.example.data.model.SeoBundle
import com.example.data.model.ViralClip
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.AspectRatio
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.ImageConfig
import com.google.firebase.ai.type.ImagePart
import com.google.firebase.ai.type.ImageSize
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.ThinkingLevel
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.ai.type.thinkingConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

private const val TAG = "GeminiStudioService"

data class RetentionAuditResult(
    val score: Long,
    val hookStrength: String,
    val emotionalPayoff: String,
    val standaloneClarity: String,
    val trimAdvice: String
)

class GeminiStudioService {

    companion object {
        const val MODEL_HYDROGEN = "gemini-4-hydrogen"
        const val MODEL_CARBON = "gemini-4-carbon"
        const val MODEL_ARGON = "gemini-4-argon"
        const val MODEL_FLASH_LITE = "gemini-3.1-flash-lite"
        const val MODEL_TRANSCRIBE = "gemini-3.5-transcribe"
        const val MODEL_IMAGE_NANO = "gemini-nano-banana-2.1"
        const val MODEL_IMAGE_PRO = "gemini-3-pro-image-preview"
    }

    // 1. Ultra-low-latency hook banner generator (gemini-3.1-flash-lite)
    private val fastHookModel by lazy {
        Firebase.ai.generativeModel(
            modelName = MODEL_FLASH_LITE,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = Schema.obj(
                    properties = mapOf(
                        "hooks" to Schema.array(
                            items = Schema.string(),
                            description = "4 ultra-short viral hook banners under 28 characters"
                        )
                    )
                )
            }
        )
    }

    // 2. Search-grounded Viral SEO generator (gemini-4-hydrogen + Tool.googleSearch())
    private val searchGroundedSeoModel by lazy {
        Firebase.ai.generativeModel(
            modelName = MODEL_HYDROGEN,
            tools = listOf(Tool.googleSearch())
        )
    }

    // 3. Deep Viral Retention Scorer (gemini-4-carbon with ThinkingLevel.HIGH; NO maxOutputTokens)
    private val deepRetentionModel by lazy {
        Firebase.ai.generativeModel(
            modelName = MODEL_CARBON,
            generationConfig = generationConfig {
                thinkingConfig = thinkingConfig {
                    thinkingLevel = ThinkingLevel.HIGH
                }
                responseMimeType = "application/json"
                responseSchema = Schema.obj(
                    properties = mapOf(
                        "viralScore" to Schema.integer(description = "Overall retention score 0-100"),
                        "hookStrength" to Schema.string(description = "Evaluation of first 3 seconds"),
                        "emotionalPayoff" to Schema.string(description = "Plot twist, humor, or hype payoff"),
                        "standaloneClarity" to Schema.string(description = "Clarity for a cold Shorts viewer"),
                        "trimAdvice" to Schema.string(description = "Exact dead-air trim recommendation")
                    )
                )
            }
        )
    }

    // 4. Multimodal Video & Image Understanding (gemini-4-argon)
    private val multimodalArgonModel by lazy {
        Firebase.ai.generativeModel(modelName = MODEL_ARGON)
    }

    // 5. Audio Transcription (gemini-3.5-transcribe)
    private val transcribeModel by lazy {
        Firebase.ai.generativeModel(modelName = MODEL_TRANSCRIBE)
    }

    // 6. Coach models
    private val fastCoachModel by lazy {
        Firebase.ai.generativeModel(
            modelName = MODEL_FLASH_LITE,
            systemInstruction = coachSystemInstruction()
        )
    }

    private val groundedCoachModel by lazy {
        Firebase.ai.generativeModel(
            modelName = MODEL_HYDROGEN,
            tools = listOf(Tool.googleSearch()),
            systemInstruction = coachSystemInstruction()
        )
    }

    private val deepCoachModel by lazy {
        Firebase.ai.generativeModel(
            modelName = MODEL_CARBON,
            generationConfig = generationConfig {
                thinkingConfig = thinkingConfig {
                    thinkingLevel = ThinkingLevel.HIGH
                }
            },
            systemInstruction = coachSystemInstruction()
        )
    }

    private fun coachSystemInstruction(): Content = content {
        text(
            "You are ClipPulse Viral Growth Strategist, an expert in YouTube Shorts retention curves, " +
                "first-second hook psychology, Hormozi caption pacing, streamer clipping campaigns, and YouTube SEO. " +
                "Give punchy, actionable, metric-backed advice."
        )
    }

    suspend fun generateFastHooks(clip: ViralClip): List<String> {
        return try {
            val prompt = """
                Generate 4 punchy 1st-second viral Shorts hook banners (max 28 chars, bold curiosity/action, optional 1 emoji) for:
                Streamer: ${clip.streamerHandle}
                Spike: ${clip.spikeMultiplier}x normal
                Context: ${clip.title}
                Transcript: ${clip.transcriptPreview.orEmpty()}
            """.trimIndent()
            val response = withTimeout(30_000L) {
                fastHookModel.generateContent(prompt)
            }
            val raw = response.text ?: throw IllegalStateException("Empty hook response.")
            val arr = JSONObject(raw).getJSONArray("hooks")
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "generateFastHooks failed", e)
            throw e
        }
    }

    suspend fun generateSearchGroundedSeo(clip: ViralClip, topicHint: String): SeoBundle {
        return try {
            val prompt = """
                Use Google Search to check current trending gaming/streaming Shorts hashtags and phrasing for:
                Creator: ${clip.streamerHandle}
                Topic/Game: $topicHint
                Moment: ${clip.title} (${clip.spikeMultiplier}x spike, ${clip.durationSec()}s)
                Transcript: ${clip.transcriptPreview.orEmpty()}

                Return ONLY valid JSON with keys:
                - "titles": array of 3 high-CTR curiosity titles under 60 chars each ending with #shorts
                - "description": Shorts-optimized description starting with a comment-bait question, then Credit: ${clip.streamerHandle}, then a short SEO paragraph, then trending hashtags
                - "tags": array of 16 high-velocity keyword tags
                - "pinnedComment": 1 engaging first comment to pin
            """.trimIndent()

            val response = withTimeout(60_000L) {
                searchGroundedSeoModel.generateContent(prompt)
            }
            val text = response.text?.trim().orEmpty()
            val cleanJson = text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val obj = JSONObject(cleanJson)
            val titlesArr = obj.getJSONArray("titles")
            val tagsArr = obj.getJSONArray("tags")

            val sources = response.candidates.firstOrNull()
                ?.groundingMetadata
                ?.groundingChunks
                ?.mapNotNull { it.web?.title ?: it.web?.uri }
                ?.distinct()
                .orEmpty()

            SeoBundle(
                titleOptions = (0 until titlesArr.length()).map { titlesArr.getString(it) },
                description = obj.getString("description"),
                tags = (0 until tagsArr.length()).map { tagsArr.getString(it) },
                pinnedComment = obj.getString("pinnedComment"),
                groundingSources = sources
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "generateSearchGroundedSeo failed", e)
            throw e
        }
    }

    suspend fun runDeepRetentionAudit(clip: ViralClip): RetentionAuditResult {
        return try {
            val prompt = """
                Deeply evaluate this ${clip.durationSec()}s vertical Short candidate for YouTube Shorts retention:
                Streamer: ${clip.streamerHandle}
                Spike Multiplier: ${clip.spikeMultiplier}x baseline
                Hook Banner: ${clip.hookBannerText}
                Layout: ${clip.layoutPreset}
                Transcript: ${clip.transcriptPreview ?: "No transcript yet"}
                Score 0-100 and give dead-air trim advice so Frame 1 opens right before the payoff.
            """.trimIndent()
            val response = withTimeout(60_000L) {
                deepRetentionModel.generateContent(prompt)
            }
            val raw = response.text ?: throw IllegalStateException("Retention audit returned empty response.")
            val json = JSONObject(raw)
            RetentionAuditResult(
                score = json.optLong("viralScore", clip.viralScore).coerceIn(0L, 100L),
                hookStrength = json.getString("hookStrength"),
                emotionalPayoff = json.getString("emotionalPayoff"),
                standaloneClarity = json.getString("standaloneClarity"),
                trimAdvice = json.getString("trimAdvice")
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "runDeepRetentionAudit failed", e)
            throw e
        }
    }

    suspend fun analyzeVideoClip(videoBytes: ByteArray, contextPrompt: String): String {
        return try {
            require(videoBytes.size <= 19 * 1024 * 1024) {
                "Video clip exceeds 19MB inline limit. Trim clip window to <= 45s."
            }
            val response = withTimeout(60_000L) {
                multimodalArgonModel.generateContent(
                    content {
                        inlineData(videoBytes, "video/mp4")
                        text(
                            "Analyze this video clip with Gemini 4 Argon. Identify: " +
                                "1) Exact timestamp of the viral hook & payoff, 2) Dead-air seconds to trim, " +
                                "3) Suggested spoken captions, 4) Best 9:16 crop focus. Extra context: $contextPrompt"
                        )
                    }
                )
            }
            response.text?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Video analysis returned empty output.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "analyzeVideoClip failed", e)
            throw e
        }
    }

    suspend fun analyzeThumbnailOrFrame(bitmap: Bitmap): String {
        return try {
            val response = withTimeout(60_000L) {
                multimodalArgonModel.generateContent(
                    content {
                        image(bitmap)
                        text(
                            "Audit this frame/thumbnail for YouTube Shorts & mobile CTR. " +
                                "Critique visual contrast, subject framing in 9:16 safe zones, facial/action focal point, " +
                                "and suggest 3 concrete improvements."
                        )
                    }
                )
            }
            response.text?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Image analysis returned empty output.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "analyzeThumbnailOrFrame failed", e)
            throw e
        }
    }

    suspend fun transcribeMicrophoneAudio(wavBytes: ByteArray): String {
        return try {
            val response = withTimeout(60_000L) {
                transcribeModel.generateContent(
                    content {
                        inlineData(wavBytes, "audio/wav")
                        text("Transcribe the spoken audio cleanly with accurate punctuation.")
                    }
                )
            }
            response.text?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Transcription returned empty text.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "transcribeMicrophoneAudio failed", e)
            throw e
        }
    }

    suspend fun generateCoverImage(
        prompt: String,
        modelOption: ImageModelOption,
        aspectOption: ImageAspectOption,
        sizeOption: ImageSizeOption
    ): Bitmap {
        return try {
            val mappedRatio = when (aspectOption) {
                ImageAspectOption.RATIO_1_1 -> AspectRatio.SQUARE_1x1
                ImageAspectOption.RATIO_2_3 -> AspectRatio.PORTRAIT_2x3
                ImageAspectOption.RATIO_3_2 -> AspectRatio.LANDSCAPE_3x2
                ImageAspectOption.RATIO_3_4 -> AspectRatio.PORTRAIT_3x4
                ImageAspectOption.RATIO_4_3 -> AspectRatio.LANDSCAPE_4x3
                ImageAspectOption.RATIO_9_16 -> AspectRatio.PORTRAIT_9x16
                ImageAspectOption.RATIO_16_9 -> AspectRatio.LANDSCAPE_16x9
                ImageAspectOption.RATIO_21_9 -> AspectRatio.LANDSCAPE_21x9
            }
            val mappedSize = when (sizeOption) {
                ImageSizeOption.SIZE_1K -> ImageSize.SIZE_1K
                ImageSizeOption.SIZE_2K -> ImageSize.SIZE_2K
                ImageSizeOption.SIZE_4K -> ImageSize.SIZE_4K
            }
            val config = generationConfig {
                responseModalities = listOf(ResponseModality.IMAGE)
                imageConfig = ImageConfig.builder()
                    .setAspectRatio(mappedRatio)
                    .setImageSize(mappedSize)
                    .build()
            }
            val imageModel = Firebase.ai.generativeModel(
                modelName = modelOption.modelId,
                generationConfig = config
            )
            val response = withTimeout(60_000L) {
                imageModel.generateContent(prompt)
            }
            val imagePart = response.candidates.firstOrNull()
                ?.content?.parts?.filterIsInstance<ImagePart>()
                ?.firstOrNull()
            imagePart?.image
                ?: throw IllegalStateException("No image was returned by ${modelOption.modelId}.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "generateCoverImage failed", e)
            throw e
        }
    }

    fun streamCoachReply(
        history: List<AiChatMessage>,
        userPrompt: String,
        mode: CoachMode
    ): Flow<Pair<String, List<String>>> = flow {
        val model = when (mode) {
            CoachMode.FAST -> fastCoachModel
            CoachMode.GROUNDED -> groundedCoachModel
            CoachMode.DEEP -> deepCoachModel
        }
        val chatHistory = history.map { msg ->
            content(role = if (msg.role == "user") "user" else "model") {
                text(msg.text)
            }
        }
        val chat = model.startChat(history = chatHistory)
        val acc = StringBuilder()
        var latestSources = emptyList<String>()
        withTimeout(60_000L) {
            chat.sendMessageStream(userPrompt).collect { chunk ->
                val chunkText = chunk.text.orEmpty()
                acc.append(chunkText)
                val sources = chunk.candidates.firstOrNull()
                    ?.groundingMetadata
                    ?.groundingChunks
                    ?.mapNotNull { it.web?.title ?: it.web?.uri }
                    ?.distinct()
                if (!sources.isNullOrEmpty()) {
                    latestSources = sources
                }
                emit(acc.toString() to latestSources)
            }
        }
    }
}
