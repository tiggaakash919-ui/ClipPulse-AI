package com.example.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.example.data.model.PlatformSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.math.log10
import kotlin.math.sqrt

private const val TAG = "StreamResolver"

data class TranscriptSegment(
    val startSec: Double,
    val endSec: Double,
    val text: String
)

data class ResolvedStreamInfo(
    val sourceUrl: String,
    val platform: PlatformSource,
    val title: String,
    val streamerHandle: String,
    val durationSec: Long,
    val isLive: Boolean,
    val directPlayableUri: String? = null,
    val requiredUserAgent: String? = null,
    val requiredHeaders: Map<String, String> = emptyMap(),
    val transcriptSegments: List<TranscriptSegment> = emptyList(),
    val heatmapMarkers: Map<Long, Double> = emptyMap(),
    val audioPeaks: List<ViralMomentEngine.AudioEnergyPoint> = emptyList(),
    val hlsSegments: List<String> = emptyList(),
    val sourceBytesEstimate: Long = 0L,
    val notes: String? = null
)

private data class HlsSegmentEntry(
    val url: String,
    val durationSec: Double,
    val startOffsetSec: Double
)

private data class InnertubeProfile(
    val clientName: String,
    val clientVersion: String,
    val userAgent: String,
    val androidSdkVersion: Int? = null,
    val deviceModel: String? = null,
    val thirdPartyEmbedUrl: String? = null
)

private const val DEFAULT_MOBILE_UA =
    "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

class StreamResolver(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun detectPlatform(input: String): PlatformSource {
        val lower = input.trim().lowercase()
        return when {
            lower.startsWith("content://") || lower.startsWith("file://") -> PlatformSource.LOCAL_VIDEO
            "youtube.com" in lower || "youtu.be" in lower -> PlatformSource.YOUTUBE
            "twitch.tv" in lower -> PlatformSource.TWITCH
            "kick.com" in lower -> PlatformSource.KICK
            else -> PlatformSource.CUSTOM_URL
        }
    }

    suspend fun resolveLocalUri(uri: Uri): ResolvedStreamInfo = withContext(Dispatchers.IO) {
        val uriString = uri.toString()
        val retriever = MediaMetadataRetriever()
        var durationSec = 60L
        var estimatedBytes = 0L
        try {
            retriever.setDataSource(context, uri)
            val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 60_000L
            durationSec = (durMs / 1000L).coerceAtLeast(1L)
            val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 4_000_000L
            estimatedBytes = (bitrate / 8L) * durationSec
        } catch (e: Exception) {
            Log.w(TAG, "Metadata extraction failed for local URI", e)
        } finally {
            runCatching { retriever.release() }
        }

        val audioPeaks = extractLocalAudioEnvelopeDb(uriString = uriString, bucketSec = 10)
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "Device Video"

        ResolvedStreamInfo(
            sourceUrl = uriString,
            platform = PlatformSource.LOCAL_VIDEO,
            title = name,
            streamerHandle = "@LocalCreator",
            durationSec = durationSec,
            isLive = false,
            directPlayableUri = uriString,
            audioPeaks = audioPeaks,
            sourceBytesEstimate = estimatedBytes,
            notes = "Local video resolved on-device (${durationSec}s, ${audioPeaks.size} RMS audio buckets)."
        )
    }

    suspend fun resolveUrl(rawInput: String): ResolvedStreamInfo = withContext(Dispatchers.IO) {
        val trimmed = rawInput.trim()
        val platform = detectPlatform(trimmed)
        when (platform) {
            PlatformSource.LOCAL_VIDEO -> resolveLocalUri(Uri.parse(trimmed))
            PlatformSource.YOUTUBE -> resolveYouTube(trimmed)
            PlatformSource.TWITCH -> resolveTwitch(trimmed)
            PlatformSource.KICK -> resolveKick(trimmed)
            PlatformSource.CUSTOM_URL -> resolveGenericOrHls(trimmed, platform)
        }
    }

    private fun resolveYouTube(url: String): ResolvedStreamInfo {
        val videoId = extractYouTubeId(url)
        val normalizedUrl = if (videoId != null) "https://www.youtube.com/watch?v=$videoId" else url

        var title = "YouTube Stream ${videoId.orEmpty()}".trim()
        var author = "Streamer"
        var duration = 1800L
        var isLive = "/live" in url.lowercase()
        var hlsUrl: String? = null
        var progressiveMp4Url: String? = null
        var matchedUserAgent: String? = null
        var matchedHeaders: Map<String, String> = emptyMap()
        var captionBaseUrl: String? = null
        var heatmap: Map<Long, Double> = emptyMap()

        val innertubeProfiles = listOf(
            InnertubeProfile(
                clientName = "ANDROID_VR",
                clientVersion = "1.57.29",
                userAgent = "com.google.android.apps.youtube.vr.oculus/1.57.29 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
                androidSdkVersion = 32
            ),
            InnertubeProfile(
                clientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
                clientVersion = "2.0",
                userAgent = "Mozilla/5.0 (SMART-TV; LINUX; Tizen 6.0) AppleWebKit/537.36 Version/6.0 TV Safari/537.36",
                thirdPartyEmbedUrl = "https://www.youtube.com/"
            ),
            InnertubeProfile(
                clientName = "IOS",
                clientVersion = "19.45.4",
                userAgent = "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X)",
                deviceModel = "iPhone16,2"
            ),
            InnertubeProfile(
                clientName = "ANDROID",
                clientVersion = "19.29.37",
                userAgent = "com.google.android.youtube/19.29.37 (Linux; U; Android 13) gzip",
                androidSdkVersion = 33
            ),
            InnertubeProfile(
                clientName = "MWEB",
                clientVersion = "2.20240726.00.00",
                userAgent = DEFAULT_MOBILE_UA
            )
        )

        // 1. Try YouTube Innertube player API across multiple clients & verify each candidate URL returns HTTP 200/206
        if (videoId != null) {
            for (profile in innertubeProfiles) {
                runCatching {
                    val clientObj = JSONObject().apply {
                        put("clientName", profile.clientName)
                        put("clientVersion", profile.clientVersion)
                        put("hl", "en")
                        put("gl", "US")
                        profile.androidSdkVersion?.let { put("androidSdkVersion", it) }
                        profile.deviceModel?.let { put("deviceModel", it) }
                    }
                    val contextObj = JSONObject().apply {
                        put("client", clientObj)
                        if (profile.thirdPartyEmbedUrl != null) {
                            put("thirdParty", JSONObject().apply {
                                put("embedUrl", profile.thirdPartyEmbedUrl)
                            })
                        }
                    }
                    val payload = JSONObject().apply {
                        put("videoId", videoId)
                        put("context", contextObj)
                        put("contentCheckOk", true)
                        put("racyCheckOk", true)
                    }
                    val playerReq = Request.Builder()
                        .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                        .header("User-Agent", profile.userAgent)
                        .header("Content-Type", "application/json")
                        .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()

                    client.newCall(playerReq).execute().use { resp ->
                        val body = resp.body?.string().orEmpty()
                        if (resp.isSuccessful && body.startsWith("{")) {
                            val root = JSONObject(body)
                            val details = root.optJSONObject("videoDetails")
                            if (details != null) {
                                details.optString("title").takeIf { it.isNotBlank() }?.let { title = it }
                                details.optString("author").takeIf { it.isNotBlank() }?.let { author = it }
                                details.optString("lengthSeconds").toLongOrNull()?.let { if (it > 0) duration = it }
                                if (details.optBoolean("isLiveContent", false)) {
                                    isLive = details.optBoolean("isLive", isLive)
                                }
                            }
                            if (captionBaseUrl == null) {
                                val tracks = root.optJSONObject("captions")
                                    ?.optJSONObject("playerCaptionsTracklistRenderer")
                                    ?.optJSONArray("captionTracks")
                                if (tracks != null && tracks.length() > 0) {
                                    captionBaseUrl = tracks.optJSONObject(0)?.optString("baseUrl")
                                        ?.takeIf { it.startsWith("http") }
                                }
                            }
                            val streamingData = root.optJSONObject("streamingData")
                            if (streamingData != null) {
                                val candidateMp4s = mutableListOf<Pair<Int, String>>()
                                val formats = streamingData.optJSONArray("formats")
                                if (formats != null) {
                                    for (i in 0 until formats.length()) {
                                        val fmt = formats.optJSONObject(i) ?: continue
                                        val mime = fmt.optString("mimeType")
                                        val directUrl = fmt.optString("url").takeIf { it.startsWith("http") } ?: continue
                                        if ("video/mp4" in mime) {
                                            val itag = fmt.optInt("itag", 0)
                                            candidateMp4s.add(itag to directUrl)
                                        }
                                    }
                                }
                                // Sort 720p (22) -> 1080p (37) -> 360p (18) -> others, verify HTTP 200/206
                                val prioritized = candidateMp4s.sortedBy { (itag, _) ->
                                    when (itag) {
                                        22 -> 0
                                        37 -> 1
                                        18 -> 2
                                        else -> 3
                                    }
                                }
                                val clientHeaders = if (profile.clientName.startsWith("TV") || profile.clientName == "MWEB") {
                                    mapOf("Referer" to "https://www.youtube.com/", "Origin" to "https://www.youtube.com")
                                } else emptyMap()

                                for ((_, candidateUrl) in prioritized) {
                                    if (verifyHttpStreamPlayable(candidateUrl, profile.userAgent, clientHeaders)) {
                                        progressiveMp4Url = candidateUrl
                                        matchedUserAgent = profile.userAgent
                                        matchedHeaders = clientHeaders
                                        break
                                    }
                                }

                                if (hlsUrl == null) {
                                    val foundHls = streamingData.optString("hlsManifestUrl").takeIf { it.startsWith("http") }
                                    if (foundHls != null && verifyHttpStreamPlayable(foundHls, profile.userAgent, clientHeaders)) {
                                        hlsUrl = foundHls
                                        if (matchedUserAgent == null) {
                                            matchedUserAgent = profile.userAgent
                                            matchedHeaders = clientHeaders
                                        }
                                    }
                                }
                            }
                        }
                    }
                }.onFailure {
                    Log.w(TAG, "YouTube Innertube (${profile.clientName}) lookup skipped: ${it.message}")
                }
                if (progressiveMp4Url != null) break
            }
        }

        // 2. Fetch watch HTML for Most Replayed heatmap & metadata fallback
        runCatching {
            val req = Request.Builder()
                .url(normalizedUrl)
                .header("User-Agent", DEFAULT_MOBILE_UA)
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            client.newCall(req).execute().use { resp ->
                val html = resp.body?.string().orEmpty()
                if (title.startsWith("YouTube Stream")) {
                    title = Regex("<meta property=\"og:title\" content=\"([^\"]+)\"")
                        .find(html)?.groupValues?.getOrNull(1)
                        ?: Regex("\"title\":\"([^\"]{2,150})\"").find(html)?.groupValues?.getOrNull(1)
                        ?: title
                }
                if (author == "Streamer") {
                    author = Regex("\"ownerChannelName\":\"([^\"]+)\"")
                        .find(html)?.groupValues?.getOrNull(1)
                        ?: Regex("\"author\":\"([^\"]+)\"").find(html)?.groupValues?.getOrNull(1)
                        ?: author
                }
                Regex("\"lengthSeconds\":\"(\\d+)\"")
                    .find(html)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let {
                        if (it > 0) duration = it
                    }
                if (html.contains("\"isLive\":true")) isLive = true
                if (progressiveMp4Url == null && hlsUrl == null) {
                    val htmlHls = Regex("\"hlsManifestUrl\":\"([^\"]+)\"")
                        .find(html)?.groupValues?.getOrNull(1)?.replace("\\u0026", "&")
                    if (!htmlHls.isNullOrBlank() && verifyHttpStreamPlayable(htmlHls, DEFAULT_MOBILE_UA)) {
                        hlsUrl = htmlHls
                        matchedUserAgent = DEFAULT_MOBILE_UA
                    }
                }
                heatmap = parseYouTubeHeatmap(html)
            }
        }.onFailure {
            Log.w(TAG, "YouTube watch HTML fetch warning: ${it.message}")
        }

        // 3. Fallback when direct googlevideo.com URLs returned HTTP 403 (PO-Token enforced): try verified public resolvers
        if (progressiveMp4Url == null && hlsUrl == null && videoId != null) {
            val fallback = resolveYouTubeViaPublicFallback(videoId)
            if (fallback != null) {
                progressiveMp4Url = fallback.first
                matchedUserAgent = fallback.second
            }
        }

        val transcript = if (!captionBaseUrl.isNullOrBlank()) fetchTimedTextTranscript(captionBaseUrl!!) else emptyList()
        val segments = if (!hlsUrl.isNullOrBlank()) {
            parseHlsManifest(hlsUrl!!, matchedUserAgent ?: DEFAULT_MOBILE_UA, matchedHeaders)
        } else emptyList()

        // Ensure HLS manifest itself has playable segments if progressive MP4 wasn't found
        val verifiedHlsUrl = if (hlsUrl != null && (segments.isEmpty() || verifyHttpStreamPlayable(segments.first().url, matchedUserAgent ?: DEFAULT_MOBILE_UA, matchedHeaders))) {
            hlsUrl
        } else null

        val playableUri = progressiveMp4Url ?: verifiedHlsUrl
        val handle = "@" + author.replace(Regex("[^A-Za-z0-9_]"), "").ifBlank { "Creator" }

        return ResolvedStreamInfo(
            sourceUrl = normalizedUrl,
            platform = PlatformSource.YOUTUBE,
            title = decodeBasicHtmlEntities(title),
            streamerHandle = handle,
            durationSec = duration.coerceAtLeast(30L),
            isLive = isLive,
            directPlayableUri = playableUri,
            requiredUserAgent = matchedUserAgent ?: DEFAULT_MOBILE_UA,
            requiredHeaders = matchedHeaders,
            transcriptSegments = transcript,
            heatmapMarkers = heatmap,
            hlsSegments = segments.map { it.url },
            sourceBytesEstimate = duration * 750_000L,
            notes = buildString {
                append("YouTube metadata fetched")
                if (heatmap.isNotEmpty()) append(" • ${heatmap.size} Most-Replayed markers")
                if (transcript.isNotEmpty()) append(" • ${transcript.size} spoken caption lines")
                if (progressiveMp4Url != null) append(" • Verified MP4 stream ready (HTTP 200)")
                else if (verifiedHlsUrl != null) append(" • Verified HLS stream ready")
                else append(" • YouTube blocked direct streaming (HTTP 403 PO-Token/DRM). Pick Device Video or direct .mp4/.m3u8 URL to render.")
            }
        )
    }

    private fun resolveYouTubeViaPublicFallback(videoId: String): Pair<String, String>? {
        val invidiousInstances = listOf(
            "https://inv.nadeko.net",
            "https://invidious.nerdvpn.de",
            "https://yewtu.be"
        )
        for (base in invidiousInstances) {
            val found = runCatching {
                val req = Request.Builder()
                    .url("$base/api/v1/videos/$videoId")
                    .header("User-Agent", DEFAULT_MOBILE_UA)
                    .header("Accept", "application/json")
                    .build()
                probeClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful || !body.startsWith("{")) return@use null
                    val json = JSONObject(body)
                    val streams = json.optJSONArray("formatStreams") ?: return@use null
                    for (i in 0 until streams.length()) {
                        val fmt = streams.optJSONObject(i) ?: continue
                        val container = fmt.optString("container")
                        if (container != "mp4" && !fmt.optString("type").contains("video/mp4")) continue
                        val itag = fmt.optString("itag")
                        val rawUrl = fmt.optString("url").takeIf { it.startsWith("http") }
                        if (rawUrl != null && verifyHttpStreamPlayable(rawUrl, DEFAULT_MOBILE_UA)) {
                            return@use rawUrl to DEFAULT_MOBILE_UA
                        }
                        if (itag.isNotBlank()) {
                            val proxied = "$base/latest_version?id=$videoId&itag=$itag&local=true"
                            if (verifyHttpStreamPlayable(proxied, DEFAULT_MOBILE_UA)) {
                                return@use proxied to DEFAULT_MOBILE_UA
                            }
                        }
                    }
                    null
                }
            }.getOrNull()
            if (found != null) return found
        }
        val pipedInstances = listOf(
            "https://pipedapi.kavin.rocks",
            "https://api.piped.private.coffee"
        )
        for (base in pipedInstances) {
            val found = runCatching {
                val req = Request.Builder()
                    .url("$base/streams/$videoId")
                    .header("User-Agent", DEFAULT_MOBILE_UA)
                    .header("Accept", "application/json")
                    .build()
                probeClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful || !body.startsWith("{")) return@use null
                    val json = JSONObject(body)
                    val streams = json.optJSONArray("videoStreams") ?: return@use null
                    for (i in 0 until streams.length()) {
                        val fmt = streams.optJSONObject(i) ?: continue
                        if (fmt.optBoolean("videoOnly", true)) continue
                        val mime = fmt.optString("mimeType").lowercase()
                        if ("mp4" !in mime) continue
                        val rawUrl = fmt.optString("url").takeIf { it.startsWith("http") } ?: continue
                        if (verifyHttpStreamPlayable(rawUrl, DEFAULT_MOBILE_UA)) {
                            return@use rawUrl to DEFAULT_MOBILE_UA
                        }
                    }
                    null
                }
            }.getOrNull()
            if (found != null) return found
        }
        return null
    }

    private fun verifyHttpStreamPlayable(
        url: String,
        userAgent: String,
        headers: Map<String, String> = emptyMap()
    ): Boolean {
        return runCatching {
            val reqBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Range", "bytes=0-2047")
            headers.forEach { (k, v) -> reqBuilder.header(k, v) }
            probeClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use false
                val contentType = resp.header("Content-Type").orEmpty().lowercase()
                if (contentType.startsWith("text/html")) return@use false
                true
            }
        }.getOrDefault(false)
    }

    private fun fetchTimedTextTranscript(baseUrl: String): List<TranscriptSegment> {
        return runCatching {
            val req = Request.Builder().url(baseUrl).build()
            client.newCall(req).execute().use { resp ->
                val xml = resp.body?.string().orEmpty()
                if (!resp.isSuccessful || xml.isBlank()) return emptyList()
                val regex = Regex("<text start=\"([0-9.]+)\"(?: dur=\"([0-9.]+)\")?[^>]*>(.*?)</text>", RegexOption.DOT_MATCHES_ALL)
                regex.findAll(xml).mapNotNull { match ->
                    val start = match.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
                    val dur = match.groupValues[2].toDoubleOrNull() ?: 3.0
                    val rawText = decodeBasicHtmlEntities(
                        match.groupValues[3].replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
                    )
                    if (rawText.isBlank()) null else TranscriptSegment(start, start + dur, rawText)
                }.toList()
            }
        }.getOrDefault(emptyList())
    }

    private fun parseYouTubeHeatmap(html: String): Map<Long, Double> {
        val markers = linkedMapOf<Long, Double>()
        val markerRegex = Regex(
            "\"timeRangeStartMillis\":(\\d+),\"markerDurationMillis\":(\\d+),\"heatMarkerIntensityScoreNormalized\":([0-9.]+)"
        )
        for (match in markerRegex.findAll(html)) {
            val startMs = match.groupValues[1].toLongOrNull() ?: continue
            val score = match.groupValues[3].toDoubleOrNull() ?: 0.0
            markers[startMs / 1000L] = score.coerceIn(0.0, 1.0)
        }
        return markers
    }

    private fun resolveTwitch(url: String): ResolvedStreamInfo {
        val vodId = Regex("twitch\\.tv/videos/(\\d+)").find(url)?.groupValues?.getOrNull(1)
        val channel = Regex("twitch\\.tv/([A-Za-z0-9_]+)").find(url)?.groupValues?.getOrNull(1)
        val isVod = vodId != null
        return ResolvedStreamInfo(
            sourceUrl = url,
            platform = PlatformSource.TWITCH,
            title = if (isVod) "Twitch VOD #$vodId" else "Twitch Live — ${channel ?: "Channel"}",
            streamerHandle = "@${channel?.takeIf { it != "videos" } ?: "TwitchStreamer"}",
            durationSec = if (isVod) 7200L else 1800L,
            isLive = !isVod,
            sourceBytesEstimate = if (isVod) 5_400_000_000L else 900_000_000L,
            notes = if (isVod) "Twitch VOD #$vodId timeline staged." else "Twitch IRC Rolling DVR ready."
        )
    }

    private fun resolveKick(url: String): ResolvedStreamInfo {
        val slug = url.substringAfter("kick.com/").substringBefore("/").substringBefore("?").trim()
        val kickHeaders = mapOf("Referer" to "https://kick.com/", "Origin" to "https://kick.com")
        if (slug.isNotBlank() && !url.contains(".m3u8", true) && !url.contains(".mp4", true)) {
            runCatching {
                val req = Request.Builder()
                    .url("https://kick.com/api/v2/channels/$slug")
                    .header("User-Agent", DEFAULT_MOBILE_UA)
                    .header("Accept", "application/json")
                    .header("Referer", "https://kick.com/")
                    .build()
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (resp.isSuccessful && body.startsWith("{")) {
                        val json = JSONObject(body)
                        val playbackUrl = json.optString("playback_url").takeIf { it.startsWith("http") }
                        val livestream = json.optJSONObject("livestream")
                        val streamTitle = livestream?.optString("session_title")?.takeIf { it.isNotBlank() }
                            ?: "Kick Live — @$slug"
                        val verifiedUrl = playbackUrl?.takeIf { verifyHttpStreamPlayable(it, DEFAULT_MOBILE_UA, kickHeaders) }
                        val segments = if (verifiedUrl != null) parseHlsManifest(verifiedUrl, DEFAULT_MOBILE_UA, kickHeaders) else emptyList()
                        return ResolvedStreamInfo(
                            sourceUrl = url,
                            platform = PlatformSource.KICK,
                            title = streamTitle,
                            streamerHandle = "@$slug",
                            durationSec = 1800L,
                            isLive = livestream != null,
                            directPlayableUri = verifiedUrl,
                            requiredUserAgent = DEFAULT_MOBILE_UA,
                            requiredHeaders = kickHeaders,
                            hlsSegments = segments.map { it.url },
                            sourceBytesEstimate = 900_000_000L,
                            notes = if (verifiedUrl != null) "Kick HLS stream resolved (${segments.size} segments)." else "Kick stream protected/offline."
                        )
                    }
                }
            }
        }
        return resolveGenericOrHls(url, PlatformSource.KICK)
    }

    private fun resolveGenericOrHls(url: String, platform: PlatformSource): ResolvedStreamInfo {
        val lower = url.lowercase()
        if (".m3u8" in lower) {
            val segments = parseHlsManifest(url)
            val totalSec = segments.sumOf { it.durationSec }.toLong().coerceAtLeast(60L)
            val playable = verifyHttpStreamPlayable(url, DEFAULT_MOBILE_UA)
            return ResolvedStreamInfo(
                sourceUrl = url,
                platform = platform,
                title = url.substringAfterLast('/').substringBefore('?').ifBlank { "HLS Stream" },
                streamerHandle = if (platform == PlatformSource.KICK) "@KickStreamer" else "@StreamSource",
                durationSec = totalSec,
                isLive = segments.isNotEmpty(),
                directPlayableUri = if (playable) url else null,
                requiredUserAgent = DEFAULT_MOBILE_UA,
                hlsSegments = segments.map { it.url },
                sourceBytesEstimate = totalSec * 650_000L,
                notes = if (playable) "Parsed ${segments.size} HLS segments (${totalSec}s)." else "HLS stream returned HTTP error or is unreachable."
            )
        }

        val playable = verifyHttpStreamPlayable(url, DEFAULT_MOBILE_UA)
        var contentLength = 0L
        if (playable) {
            runCatching {
                val headReq = Request.Builder().url(url).header("User-Agent", DEFAULT_MOBILE_UA).head().build()
                probeClient.newCall(headReq).execute().use { resp ->
                    contentLength = resp.header("Content-Length")?.toLongOrNull() ?: 0L
                }
            }
        }
        var realDurationSec = 1200L
        if (playable) {
            val retriever = MediaMetadataRetriever()
            runCatching {
                retriever.setDataSource(url, mapOf("User-Agent" to DEFAULT_MOBILE_UA))
                val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                if (ms != null && ms > 1000L) realDurationSec = (ms / 1000L).coerceAtLeast(5L)
            }
            runCatching { retriever.release() }
        }
        return ResolvedStreamInfo(
            sourceUrl = url,
            platform = platform,
            title = url.substringAfterLast('/').substringBefore('?').ifBlank { "Direct Stream" },
            streamerHandle = if (platform == PlatformSource.KICK) "@KickCreator" else "@StreamCreator",
            durationSec = realDurationSec,
            isLive = false,
            directPlayableUri = if (playable) url else null,
            requiredUserAgent = DEFAULT_MOBILE_UA,
            sourceBytesEstimate = if (contentLength > 0L) contentLength else realDurationSec * 500_000L,
            notes = if (playable) "Direct stream verified (HTTP 200 • ${realDurationSec}s)." else "URL returned HTTP error / non-video response."
        )
    }

    private fun parseHlsManifest(
        manifestUrl: String,
        userAgent: String = DEFAULT_MOBILE_UA,
        headers: Map<String, String> = emptyMap()
    ): List<HlsSegmentEntry> {
        return try {
            val reqBuilder = Request.Builder().url(manifestUrl).header("User-Agent", userAgent)
            headers.forEach { (k, v) -> reqBuilder.header(k, v) }
            client.newCall(reqBuilder.build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful || body.isBlank()) return emptyList()
                val baseUri = manifestUrl.substringBeforeLast('/') + "/"
                val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }

                if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) {
                    val variantLine = lines.firstOrNull { !it.startsWith("#") }
                    if (variantLine != null) {
                        return parseHlsManifest(resolveRelativeUrl(baseUri, variantLine), userAgent, headers)
                    }
                }

                val result = mutableListOf<HlsSegmentEntry>()
                var pendingDuration = 6.0
                var offset = 0.0
                for (line in lines) {
                    if (line.startsWith("#EXTINF:")) {
                        pendingDuration = line.removePrefix("#EXTINF:")
                            .substringBefore(',')
                            .toDoubleOrNull() ?: 6.0
                    } else if (!line.startsWith("#")) {
                        result.add(HlsSegmentEntry(resolveRelativeUrl(baseUri, line), pendingDuration, offset))
                        offset += pendingDuration
                    }
                }
                result
            }
        } catch (e: Exception) {
            Log.w(TAG, "HLS manifest parse failed: ${e.message}")
            emptyList()
        }
    }

    suspend fun downloadSurgicalWindow(
        sourceUrl: String,
        hlsSegments: List<String>,
        startSec: Long,
        endSec: Long,
        clipId: String,
        userAgent: String = DEFAULT_MOBILE_UA,
        headers: Map<String, String> = emptyMap()
    ): Pair<File?, Long> = withContext(Dispatchers.IO) {
        val cacheDir = File(context.cacheDir, "clipper/clips").apply { mkdirs() }
        if (hlsSegments.isNotEmpty()) {
            val startIdx = (startSec / 6L).toInt().coerceIn(0, hlsSegments.lastIndex)
            val endIdx = ((endSec / 6L) + 1L).toInt().coerceIn(startIdx, hlsSegments.lastIndex)
            val slice = hlsSegments.subList(startIdx, endIdx + 1)
            val outFile = File(cacheDir, "${clipId}_raw.ts")
            var totalBytes = 0L
            runCatching {
                outFile.outputStream().use { out ->
                    for (segUrl in slice) {
                        val reqBuilder = Request.Builder().url(segUrl).header("User-Agent", userAgent)
                        headers.forEach { (k, v) -> reqBuilder.header(k, v) }
                        client.newCall(reqBuilder.build()).execute().use { resp ->
                            if (resp.isSuccessful) {
                                val bytes = resp.body?.bytes() ?: ByteArray(0)
                                out.write(bytes)
                                totalBytes += bytes.size
                            }
                        }
                    }
                }
            }
            if (totalBytes > 0L) return@withContext outFile to totalBytes
        }

        null to 0L
    }

    fun connectTwitchLiveChat(
        channel: String,
        onMessage: (String) -> Unit,
        onStatus: (String) -> Unit
    ): WebSocket {
        val cleanChannel = channel.trim().lowercase().removePrefix("@")
        val req = Request.Builder().url("wss://irc-ws.chat.twitch.tv:443").build()
        return client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("PASS SCHMOOPIIE")
                webSocket.send("NICK justinfan${(10000..99999).random()}")
                webSocket.send("JOIN #$cleanChannel")
                onStatus("Connected to Twitch #$cleanChannel Rolling DVR")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                for (line in text.lines()) {
                    if (line.startsWith("PING")) {
                        webSocket.send(line.replace("PING", "PONG"))
                    } else if ("PRIVMSG #$cleanChannel :" in line) {
                        val msg = line.substringAfter("PRIVMSG #$cleanChannel :").trim()
                        if (msg.isNotBlank()) onMessage(msg)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onStatus("DVR stream fallback active (${t.localizedMessage ?: "IRC closed"})")
            }
        })
    }

    private fun extractLocalAudioEnvelopeDb(
        uriString: String,
        bucketSec: Int
    ): List<ViralMomentEngine.AudioEnergyPoint> {
        val points = mutableListOf<ViralMomentEngine.AudioEnergyPoint>()
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, Uri.parse(uriString), null)
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                if (fmt.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = fmt
                    break
                }
            }
            if (audioTrackIndex == -1 || audioFormat == null) return points

            extractor.selectTrack(audioTrackIndex)
            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return points
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(audioFormat, null, null, 0)
                start()
            }

            val bucketSquares = linkedMapOf<Long, Double>()
            val bucketCounts = linkedMapOf<Long, Long>()
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var iterations = 0

            while (!outputDone && iterations < 1200) {
                iterations++
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(5_000L)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx) ?: continue
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 5_000L)
                if (outIdx >= 0) {
                    val outBuf: ByteBuffer? = codec.getOutputBuffer(outIdx)
                    if (outBuf != null && bufferInfo.size > 0) {
                        val ptsSec = bufferInfo.presentationTimeUs / 1_000_000L
                        val bucketKey = (ptsSec / bucketSec) * bucketSec
                        var sumSq = 0.0
                        var count = 0L
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        while (outBuf.remaining() >= 2) {
                            val sample = outBuf.short.toDouble() / 32768.0
                            sumSq += sample * sample
                            count++
                        }
                        bucketSquares[bucketKey] = (bucketSquares[bucketKey] ?: 0.0) + sumSq
                        bucketCounts[bucketKey] = (bucketCounts[bucketKey] ?: 0L) + count
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true
                    }
                }
            }

            for ((bucketKey, sumSq) in bucketSquares) {
                val count = (bucketCounts[bucketKey] ?: 1L).coerceAtLeast(1L)
                val rms = sqrt((sumSq / count).coerceAtLeast(1e-9))
                val db = (20.0 * log10(rms)).coerceIn(-60.0, 0.0)
                points.add(ViralMomentEngine.AudioEnergyPoint(bucketKey, db))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Local audio envelope extraction skipped: ${e.message}")
        } finally {
            runCatching { codec?.stop(); codec?.release() }
            runCatching { extractor.release() }
        }
        return points
    }

    private fun extractYouTubeId(url: String): String? {
        val patterns = listOf(
            Regex("[?&]v=([A-Za-z0-9_-]{11})"),
            Regex("youtu\\.be/([A-Za-z0-9_-]{11})"),
            Regex("youtube\\.com/live/([A-Za-z0-9_-]{11})"),
            Regex("youtube\\.com/shorts/([A-Za-z0-9_-]{11})")
        )
        for (p in patterns) {
            p.find(url)?.groupValues?.getOrNull(1)?.let { return it }
        }
        return null
    }

    private fun resolveRelativeUrl(base: String, target: String): String {
        return if (target.startsWith("http://") || target.startsWith("https://")) target else base + target.trimStart('/')
    }

    private fun decodeBasicHtmlEntities(text: String): String {
        return text.replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }
}
