package com.example.engine

import com.example.data.model.CaptionWord
import com.example.data.model.LayoutPreset
import com.example.data.model.ViralClip

object FfmpegCommandBuilder {

    fun buildAssScript(
        clip: ViralClip,
        words: List<CaptionWord>
    ): String {
        val header = """
            [Script Info]
            Title: ClipPulse AI Hormozi Captions
            ScriptType: v4.00+
            PlayResX: 1080
            PlayResY: 1920
            WrapStyle: 2
            ScaledBorderAndShadow: yes

            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
            Style: HormoziPop,Montserrat,72,&H00FFFFFF,&H0000E6FF,&H00000000,&H80000000,-1,0,0,0,100,100,0,0,1,4,2,2,60,60,380,1
            Style: HookPill,Montserrat,56,&H00100B09,&H0000E6FF,&H0000E6FF,&HFF00E6FF,-1,0,0,0,100,100,0,0,3,14,0,8,80,80,160,1
            Style: CreditBadge,Plus Jakarta Sans,34,&HCCFFFFFF,&H00FFFFFF,&HAA000000,&H66000000,0,0,0,0,100,100,0,0,1,2,1,7,48,48,54,1

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 1,0:00:00.00,0:00:03.50,HookPill,,0,0,0,,{\fad(120,180)}${clip.hookBannerText}
            Dialogue: 1,0:00:00.00,${formatAssTime(clip.durationSec() * 1000L)},CreditBadge,,0,0,0,,${clip.creditWatermark}
        """.trimIndent()

        val chunkSize = 3
        val events = StringBuilder()
        for (i in words.indices step chunkSize) {
            val group = words.subList(i, minOf(i + chunkSize, words.size))
            for (activeIdx in group.indices) {
                val activeWord = group[activeIdx]
                val lineText = group.mapIndexed { idx, w ->
                    if (idx == activeIdx) {
                        "{\\c&H0000E6FF&\\t(0,80,\\fscx115\\fscy115)\\t(80,180,\\fscx100\\fscy100)}${w.word.uppercase()}{\\r}"
                    } else {
                        w.word.uppercase()
                    }
                }.joinToString(" ")
                events.appendLine(
                    "Dialogue: 2,${formatAssTime(activeWord.startMs)},${formatAssTime(activeWord.endMs)},HormoziPop,,0,0,0,,$lineText"
                )
            }
        }
        return "$header\n$events"
    }

    fun buildExtractCommand(
        sourceUrl: String,
        clip: ViralClip,
        outPath: String
    ): String {
        val start = ViralMomentEngine.formatTimestamp(clip.startSec)
        val end = ViralMomentEngine.formatTimestamp(clip.endSec)
        val safeSrc = sourceUrl.ifBlank { "<stream_url>" }
        return "ffmpeg -y -ss $start -to $end -i \"$safeSrc\" -c copy -avoid_negative_ts make_zero \"$outPath\""
    }

    fun buildRenderCommand(
        inPath: String,
        outPath: String,
        assPath: String,
        clip: ViralClip
    ): String {
        val filterGraph = when (clip.layoutPresetEnum()) {
            LayoutPreset.WORD_POP, LayoutPreset.FULL_CROP ->
                "crop=ih*9/16:ih:(iw-ow)/2:0,scale=1080:1920,ass='$assPath'"
            LayoutPreset.CINEMA_BLUR ->
                "[0:v]scale=1080:1920:force_original_aspect_ratio=increase,crop=1080:1920,boxblur=25:5,eq=brightness=-0.12[bg];" +
                    "[0:v]scale=1080:-2[fg];[bg][fg]overlay=(W-w)/2:(H-h)/2,ass='$assPath'"
            LayoutPreset.SPLIT_SCREEN ->
                "[0:v]crop=iw*0.28:ih*0.32:iw*0.70:ih*0.64,scale=1080:614[cam];" +
                    "[0:v]crop=ih*9/11:ih:(iw-ow)/2:0,scale=1080:1306[game];" +
                    "[cam][game]vstack=inputs=2,ass='$assPath'"
        }
        return "ffmpeg -y -i \"$inPath\" -filter_complex \"$filterGraph\" " +
            "-c:v libx264 -crf 20 -preset veryfast -r 30 -c:a aac -b:a 192k \"$outPath\""
    }

    fun buildAudioStatsCommand(inputPath: String): String {
        return "ffmpeg -i \"$inputPath\" -af \"astats=metadata=1:reset=1,ametadata=print:key=lavfi.astats.Overall.RMS_level:file=audio_peaks.txt\" -f null -"
    }

    private fun formatAssTime(ms: Long): String {
        val totalCs = (ms.coerceAtLeast(0L) / 10L)
        val cs = totalCs % 100
        val totalSec = totalCs / 100
        val s = totalSec % 60
        val m = (totalSec / 60) % 60
        val h = totalSec / 3600
        return "%d:%02d:%02d.%02d".format(h, m, s, cs)
    }
}
