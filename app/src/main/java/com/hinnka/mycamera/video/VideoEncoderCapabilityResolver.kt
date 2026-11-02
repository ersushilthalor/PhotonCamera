package com.hinnka.mycamera.video

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.util.Size

/**
 * Checks the encoder side of a recording configuration before camera options are exposed.
 * Camera2 stream support alone does not guarantee that the final MediaCodec input surface
 * accepts the requested output size and frame rate.
 */
object VideoEncoderCapabilityResolver {
    private val encoderInfos: List<MediaCodecInfo> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder }
        }.getOrDefault(emptyList())
    }

    fun supportsSizeAndRate(codecMime: String, size: Size, fps: Int): Boolean {
        if (codecMime.isBlank() || size.width <= 0 || size.height <= 0 || fps <= 0) {
            return false
        }

        return encoderInfos.any { codecInfo ->
            if (codecInfo.supportedTypes.none { it.equals(codecMime, ignoreCase = true) }) {
                return@any false
            }
            runCatching {
                codecInfo.getCapabilitiesForType(codecMime)
                    .videoCapabilities
                    ?.areSizeAndRateSupported(size.width, size.height, fps.toDouble()) == true
            }.getOrDefault(false)
        }
    }
}
