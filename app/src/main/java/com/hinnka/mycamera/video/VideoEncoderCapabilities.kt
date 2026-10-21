package com.hinnka.mycamera.video

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Size

/**
 * Resolves a real encoder for a format and exposes the bitrate range it advertises.
 * The same selection is used by the capability UI and the recorder wherever possible,
 * so unsupported bitrate targets are not presented as usable options.
 */
internal object VideoEncoderCapabilities {
    data class Support(
        val codecName: String,
        val minBitrateBps: Int,
        val maxBitrateBps: Int,
    )

    fun findForFormat(mimeType: String, size: Size, fps: Int): Support? {
        if (mimeType.isBlank() || size.width <= 0 || size.height <= 0 || fps <= 0) return null

        val format = MediaFormat.createVideoFormat(mimeType, size.width, size.height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        }
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val encoders = codecList.codecInfos.filter { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
        }
        if (encoders.isEmpty()) return null

        val selectedName = runCatching { codecList.findEncoderForFormat(format) }.getOrNull()
        val candidates = encoders.mapNotNull { info ->
            val type = info.supportedTypes.firstOrNull { it.equals(mimeType, ignoreCase = true) }
                ?: return@mapNotNull null
            val capabilities = runCatching { info.getCapabilitiesForType(type) }.getOrNull()
                ?: return@mapNotNull null
            if (!capabilities.colorFormats.contains(
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
            ) {
                return@mapNotNull null
            }
            val videoCapabilities = capabilities.videoCapabilities
            val supportsRequestedSize = runCatching {
                videoCapabilities.areSizeAndRateSupported(
                    size.width,
                    size.height,
                    fps.toDouble()
                ) || videoCapabilities.areSizeAndRateSupported(
                    size.height,
                    size.width,
                    fps.toDouble()
                )
            }.getOrDefault(false)
            Candidate(
                info = info,
                minBitrateBps = capabilities.videoCapabilities.bitrateRange.lower,
                maxBitrateBps = capabilities.videoCapabilities.bitrateRange.upper,
                supportsRequestedSize = supportsRequestedSize
            )
        }

        // Prefer Android's exact format match. Otherwise prefer a size-compatible hardware
        // encoder, with the largest advertised bitrate range as a tie-breaker.
        val selected = candidates.firstOrNull { it.info.name == selectedName }
            ?: candidates.filter { it.supportsRequestedSize }.maxWithOrNull(
                compareBy<Candidate> { it.info.isHardwareAccelerated }
                    .thenBy { it.maxBitrateBps }
            )
            ?: candidates.maxWithOrNull(
                compareBy<Candidate> { it.info.isHardwareAccelerated }
                    .thenBy { it.maxBitrateBps }
            )
            ?: return null

        val safeMax = minOf(selected.maxBitrateBps.toLong(), Int.MAX_VALUE.toLong())
            .coerceAtLeast(1L).toInt()
        val safeMin = maxOf(selected.minBitrateBps.toLong(), 2_000_000L)
            .coerceAtMost(safeMax.toLong()).toInt()
        return Support(
            codecName = selected.info.name,
            minBitrateBps = safeMin,
            maxBitrateBps = safeMax
        )
    }

    private data class Candidate(
        val info: MediaCodecInfo,
        val minBitrateBps: Int,
        val maxBitrateBps: Int,
        val supportsRequestedSize: Boolean,
    )
}
