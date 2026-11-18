package com.example.engine

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.example.data.model.PrivacyStatus
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.TimeUnit

object YouTubeUploadManager {

    const val SCOPE_YOUTUBE_UPLOAD = "https://www.googleapis.com/auth/youtube.upload"
    const val SCOPE_YOUTUBE_FORCE_SSL = "https://www.googleapis.com/auth/youtube.force-ssl"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun buildAuthorizationRequest(): AuthorizationRequest {
        return AuthorizationRequest.builder()
            .setRequestedScopes(
                listOf(
                    Scope(SCOPE_YOUTUBE_UPLOAD),
                    Scope(SCOPE_YOUTUBE_FORCE_SSL)
                )
            )
            .build()
    }

    data class YouTubeUploadResult(
        val videoId: String,
        val shortsUrl: String
    )

    /**
     * Executes YouTube Data API v3 Resumable Upload (videos.insert).
     */
    suspend fun uploadShortResumable(
        accessToken: String,
        videoFile: File,
        title: String,
        description: String,
        tags: List<String>,
        categoryId: String = "20",
        privacyStatus: PrivacyStatus,
        scheduledPublishIso: String? = null,
        onProgress: (Float) -> Unit
    ): YouTubeUploadResult = withContext(Dispatchers.IO) {
        require(videoFile.exists() && videoFile.length() > 0L) {
            "Exported 9:16 MP4 clip not found. Export the clip in Studio before uploading."
        }

        val metadataJson = JSONObject().apply {
            put("snippet", JSONObject().apply {
                put("title", title.take(100))
                put("description", description.take(5000))
                put("tags", JSONArray(tags.take(25)))
                put("categoryId", categoryId)
            })
            put("status", JSONObject().apply {
                put("privacyStatus", privacyStatus.apiValue)
                put("selfDeclaredMadeForKids", false)
                if (privacyStatus == PrivacyStatus.PRIVATE_SCHEDULED && !scheduledPublishIso.isNullOrBlank()) {
                    put("publishAt", scheduledPublishIso)
                }
            })
        }

        // Step 1: Initiate resumable upload session
        val initReq = Request.Builder()
            .url("https://www.googleapis.com/upload/youtube/v3/videos?uploadType=resumable&part=snippet,status")
            .header("Authorization", "Bearer $accessToken")
            .header("X-Upload-Content-Length", videoFile.length().toString())
            .header("X-Upload-Content-Type", "video/mp4")
            .post(metadataJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val uploadSessionUrl: String = client.newCall(initReq).execute().use { resp ->
            if (!resp.isSuccessful) {
                val errBody = resp.body?.string().orEmpty()
                throw IllegalStateException("YouTube upload init failed (${resp.code}): $errBody")
            }
            resp.header("Location")
                ?: throw IllegalStateException("YouTube resumable upload missing Location header.")
        }

        // Step 2: Upload MP4 bytes
        onProgress(0.25f)
        val bytes = FileInputStream(videoFile).use { it.readBytes() }
        val putReq = Request.Builder()
            .url(uploadSessionUrl)
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "video/mp4")
            .put(bytes.toRequestBody("video/mp4".toMediaType()))
            .build()

        onProgress(0.65f)
        client.newCall(putReq).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("YouTube MP4 transfer failed (${resp.code}): $body")
            }
            val json = JSONObject(body)
            val videoId = json.optString("id")
            if (videoId.isBlank()) {
                throw IllegalStateException("YouTube API returned empty video ID.")
            }
            onProgress(1f)
            YouTubeUploadResult(
                videoId = videoId,
                shortsUrl = "https://youtube.com/shorts/$videoId"
            )
        }
    }

    fun shareClipWithSeoCaption(
        context: Context,
        videoFile: File?,
        seoTitle: String,
        seoDescription: String
    ) {
        val fullCaption = "$seoTitle\n\n$seoDescription"
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("ClipPulse Viral SEO", fullCaption))

        val intent = if (videoFile != null && videoFile.exists()) {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                videoFile
            )
            Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, seoTitle)
                putExtra(Intent.EXTRA_TEXT, fullCaption)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, seoTitle)
                putExtra(Intent.EXTRA_TEXT, fullCaption)
            }
        }

        val chooser = Intent.createChooser(intent, "Share to Shorts / Reels / TikTok")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}
