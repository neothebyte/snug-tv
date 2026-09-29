package tv.livingroom.watch

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import java.util.Locale

/** Largest picture a decoder handles smoothly. */
data class VideoLimit(val width: Int, val height: Int)

data class DeviceCodecs(
    val video: List<String>,
    val hevcMain10: Boolean,
    val audio: List<String>,
    val maxAudioChannels: Int,
    val limits: Map<String, VideoLimit> = emptyMap(),
    val dolbyVision: Boolean = false
) {
    companion object {
        private val FULL_HD = VideoLimit(1920, 1080)
        private val UHD = VideoLimit(3840, 2160)

        private val VIDEO_MIMES = listOf(
            "h264" to "video/avc",
            "hevc" to "video/hevc",
            "vp9" to "video/x-vnd.on2.vp9",
            "av1" to "video/av01",
            "mpeg2video" to "video/mpeg2"
        )

        @OptIn(UnstableApi::class)
        fun detect(context: Context): DeviceCodecs {
            val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }

            fun supporting(mime: String): List<MediaCodecInfo> =
                decoders.filter { info -> info.supportedTypes.any { it.equals(mime, true) } }

            fun has(mime: String): Boolean = supporting(mime).isNotEmpty()

            // Many TVs list slow software decoders next to (or instead of) the real hardware ones.
            // Only hardware decoders are trusted with full-size video.
            val video = mutableListOf<String>()
            val limits = mutableMapOf<String, VideoLimit>()
            for ((codec, mime) in VIDEO_MIMES) {
                val all = supporting(mime)
                val hardware = all.filter { isHardware(it) }
                val limit = when {
                    hardware.isNotEmpty() -> hardware.map { largestSize(it, mime) }.maxBy { it.width * it.height }
                    // Software decoding copes with HD on most boxes; anything bigger goes to the server.
                    all.isNotEmpty() -> smaller(FULL_HD, all.map { largestSize(it, mime) }.maxBy { it.width * it.height })
                    // Every Android TV decodes H.264; keep it even if the list is oddly empty.
                    codec == "h264" -> FULL_HD
                    else -> null
                } ?: continue
                video += codec
                limits[codec] = limit
            }

            val main10 = supporting("video/hevc").filter { isHardware(it) }.any { info ->
                runCatching {
                    info.getCapabilitiesForType("video/hevc").profileLevels.any {
                        it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                    }
                }.getOrDefault(false)
            }

            @Suppress("DEPRECATION")
            val sink = AudioCapabilities.getCapabilities(context)
            val audio = buildList {
                addAll(listOf("aac", "mp3", "mp2", "flac", "opus", "vorbis", "pcm_s16le", "pcm_s24le"))
                if (has("audio/ac3") || sink.supportsEncoding(C.ENCODING_AC3)) add("ac3")
                if (has("audio/eac3") || sink.supportsEncoding(C.ENCODING_E_AC3)) add("eac3")
                if (has("audio/vnd.dts") || sink.supportsEncoding(C.ENCODING_DTS)) add("dts")
                if (has("audio/true-hd") || sink.supportsEncoding(C.ENCODING_DOLBY_TRUEHD)) add("truehd")
            }
            return DeviceCodecs(
                video = video,
                hevcMain10 = main10,
                audio = audio,
                maxAudioChannels = if (sink.maxChannelCount >= 6) 8 else 6,
                limits = limits,
                dolbyVision = supporting("video/dolby-vision").any { isHardware(it) }
            )
        }

        private fun isHardware(info: MediaCodecInfo): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return info.isHardwareAccelerated
            val name = info.name.lowercase(Locale.US)
            return !name.startsWith("omx.google.") &&
                !name.startsWith("c2.android.") &&
                !name.contains(".sw.") &&
                !name.contains("ffmpeg")
        }

        private fun largestSize(info: MediaCodecInfo, mime: String): VideoLimit {
            val caps = runCatching { info.getCapabilitiesForType(mime).videoCapabilities }.getOrNull() ?: return FULL_HD
            return when {
                runCatching { caps.areSizeAndRateSupported(UHD.width, UHD.height, 24.0) }.getOrDefault(false) -> UHD
                runCatching { caps.areSizeAndRateSupported(FULL_HD.width, FULL_HD.height, 24.0) }.getOrDefault(false) -> FULL_HD
                else -> VideoLimit(caps.supportedWidths.upper, caps.supportedHeights.upper)
            }
        }

        private fun smaller(a: VideoLimit, b: VideoLimit): VideoLimit =
            if (a.width * a.height <= b.width * b.height) a else b
    }
}
