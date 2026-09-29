package tv.livingroom.watch

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities

data class DeviceCodecs(
    val video: List<String>,
    val hevcMain10: Boolean,
    val audio: List<String>,
    val maxAudioChannels: Int
) {
    companion object {
        @OptIn(UnstableApi::class)
        fun detect(context: Context): DeviceCodecs {
            val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }

            fun has(mime: String): Boolean = decoders.any { info -> info.supportedTypes.any { it.equals(mime, true) } }

            fun hasProfile(mime: String, profile: Int): Boolean = decoders.any { info ->
                info.supportedTypes.any { it.equals(mime, true) } && runCatching {
                    info.getCapabilitiesForType(mime).profileLevels.any { it.profile == profile }
                }.getOrDefault(false)
            }

            val video = buildList {
                add("h264")
                if (has("video/hevc")) add("hevc")
                if (has("video/x-vnd.on2.vp9")) add("vp9")
                if (has("video/av01")) add("av1")
                if (has("video/mpeg2")) add("mpeg2video")
            }
            val main10 = hasProfile("video/hevc", MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)

            @Suppress("DEPRECATION")
            val sink = AudioCapabilities.getCapabilities(context)
            val audio = buildList {
                addAll(listOf("aac", "mp3", "mp2", "flac", "opus", "vorbis", "pcm_s16le", "pcm_s24le"))
                if (has("audio/ac3") || sink.supportsEncoding(C.ENCODING_AC3)) add("ac3")
                if (has("audio/eac3") || sink.supportsEncoding(C.ENCODING_E_AC3)) add("eac3")
                if (has("audio/vnd.dts") || sink.supportsEncoding(C.ENCODING_DTS)) add("dts")
                if (has("audio/true-hd") || sink.supportsEncoding(C.ENCODING_DOLBY_TRUEHD)) add("truehd")
            }
            return DeviceCodecs(video, main10, audio, if (sink.maxChannelCount >= 6) 8 else 6)
        }
    }
}
