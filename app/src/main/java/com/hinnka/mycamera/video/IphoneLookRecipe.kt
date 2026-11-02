package com.hinnka.mycamera.video

import com.hinnka.mycamera.model.ColorRecipeParams

/**
 * iPhone-style video look: bright, natural colour, open shadows, protected highlights,
 * low contrast but not flat.
 *
 * This is a render-time override on top of the user's recipe. It only uses recipe fields that
 * RealtimeVideoRenderer already binds (exposure, tonality, contrast, shadows, highlights,
 * vibrance, saturation, sharpness), so no shader or encoder change is needed.
 *
 * Tuning (change one constant at a time):
 *  - too bright / washed out  -> lower EXPOSURE or TONALITY
 *  - looks flat               -> lower SHADOWS, raise CONTRAST towards 1.0, raise VIBRANCE
 *  - highlights clipping      -> make HIGHLIGHTS more negative
 *  - colours dull             -> raise VIBRANCE first, then SATURATION
 */
object IphoneLookRecipe {
    const val EXPOSURE = 0.30f      // EV, bright overall
    const val TONALITY = 1.0f       // BasicTone high-key end, same as the old HDR toggle
    const val CONTRAST = 0.97f      // slightly under neutral, never flat
    const val SHADOWS = 0.70f       // open shadows (shader doubles this internally)
    const val HIGHLIGHTS = -0.30f   // pull highlights down, keeps sky and lamps detailed
    const val VIBRANCE = 0.20f      // lifts weak colours more than strong ones
    const val SATURATION = 1.04f    // multiplier on the user's saturation
    const val SHARPNESS = 0.15f     // small clarity gain

    fun apply(base: ColorRecipeParams): ColorRecipeParams {
        return base.copy(
            exposure = EXPOSURE,
            tonality = TONALITY,
            contrast = CONTRAST,
            shadows = SHADOWS,
            highlights = (base.highlights + HIGHLIGHTS).coerceIn(-1f, 1f),
            color = (base.color + VIBRANCE).coerceIn(-1f, 1f),
            saturation = (base.saturation * SATURATION).coerceIn(0f, 2f),
            sharpness = (base.sharpness + SHARPNESS).coerceIn(-1f, 1f),
        )
    }
}
