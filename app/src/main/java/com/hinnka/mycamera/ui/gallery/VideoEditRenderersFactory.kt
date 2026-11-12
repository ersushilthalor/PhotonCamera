package com.hinnka.mycamera.ui.gallery

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory

/**
 * Renderer setup for the video editor.
 *
 * Keep Media3's default MediaCodecVideoRenderer: ExoPlayer.setVideoEffects() is supported only
 * by the default renderer. Substituting a custom video renderer leaves edit controls visible but
 * prevents their GL effect from being applied reliably to the preview.
 *
 * Log decoding/colour handling remains inside VideoLutEffect and the export pipeline.
 */
@UnstableApi
internal class VideoEditRenderersFactory(
    context: Context,
) : DefaultRenderersFactory(context.applicationContext)
