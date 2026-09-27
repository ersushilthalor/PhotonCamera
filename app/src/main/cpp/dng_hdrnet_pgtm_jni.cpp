#include "jni_common.h"
#include "raw_legacy_auto_exposure_solver.h"
#include <vector>
#include <cmath>
#include <algorithm>
#include <cstdint>

constexpr int kDehazeHistogramSize = 877;
constexpr int kHighlightHistogramSize = 5251;
constexpr float kHdrNetGainEpsilon = 1e-4f;

struct RgbSample {
    float red;
    float green;
    float blue;
};

struct DehazeCurve {
    float haze_point_low = 0.0f;
    float haze_point_high = 0.0f;
    float highlight_scale = 1.0f;
    float quadratic_coefficient = 0.0f;
    float linear_slope = 1.0f;
    float shoulder_value = 0.0f;
    float detected_highlight_scale = 1.0f;
    int sampled_pixel_count = 0;
};

inline float Lerp(float a, float b, float t) {
    return a + (b - a) * t;
}

inline float HdrNetLuma(const RgbSample& rgb) {
    return (306.0f / 1024.0f) * rgb.red + (601.0f / 1024.0f) * rgb.green + (117.0f / 1024.0f) * rgb.blue;
}

inline float TableIntensity(const RgbSample& rgb, const float* weights) {
    float min_c = std::min({rgb.red, rgb.green, rgb.blue});
    float max_c = std::max({rgb.red, rgb.green, rgb.blue});
    return weights[0] * rgb.red + weights[1] * rgb.green + weights[2] * rgb.blue +
           weights[3] * min_c + weights[4] * max_c;
}

inline RgbSample RgbForTableCoordinate(float target_coord, const RgbSample& cell, float cell_intensity, float scale) {
    if (cell_intensity > 1e-6f && cell.red >= 0.0f && cell.green >= 0.0f && cell.blue >= 0.0f) {
        float factor = target_coord / cell_intensity;
        return {cell.red * factor, cell.green * factor, cell.blue * factor};
    }
    return {target_coord, target_coord, target_coord};
}

inline float MappedDehazeLuminance(float luminance, const DehazeCurve& curve) {
    float scaled = std::min(luminance * curve.highlight_scale, 1.0f);
    float distance = std::max(scaled - curve.haze_point_low, 0.0f);
    float mapped;
    if (scaled < curve.haze_point_high) {
        mapped = distance * distance * curve.quadratic_coefficient;
    } else {
        mapped = curve.shoulder_value + (scaled - curve.haze_point_high) * curve.linear_slope;
    }
    return std::clamp(mapped, 0.0f, 1.0f);
}

inline RgbSample ApplyDehaze(const RgbSample& rgb, const DehazeCurve& curve) {
    float lum = (rgb.red + rgb.green + rgb.blue) / 3.0f;
    if (lum < 1e-6f) return rgb;
    float mapped = MappedDehazeLuminance(lum, curve);
    float gain = mapped / lum;
    return {
        std::clamp(rgb.red * gain, 0.0f, 1.0f),
        std::clamp(rgb.green * gain, 0.0f, 1.0f),
        std::clamp(rgb.blue * gain, 0.0f, 1.0f)
    };
}

inline void AddDehazeHistogramSample(
    const RgbSample& rgb,
    std::vector<uint32_t>* haze_hist,
    std::vector<uint32_t>* highlight_hist,
    int* sample_count) {
    float r = std::clamp(rgb.red, 0.0f, 1.0f) * 4095.0f;
    float g = std::clamp(rgb.green, 0.0f, 1.0f) * 4095.0f;
    float b = std::clamp(rgb.blue, 0.0f, 1.0f) * 4095.0f;

    int min_c = static_cast<int>(std::floor(std::min({r, g, b}) + 0.5f));
    int max_c = static_cast<int>(std::floor(std::max({r, g, b}) + 0.5f));
    int sum_c = static_cast<int>(std::floor(r + g + b + 0.5f));

    int haze_bin = std::clamp(sum_c / 14, 0, kDehazeHistogramSize - 1);
    int highlight_bin = std::clamp(max_c + (max_c - min_c) / 8, 0, kHighlightHistogramSize - 1);

    (*haze_hist)[haze_bin]++;
    (*highlight_hist)[highlight_bin]++;
    (*sample_count)++;
}

inline DehazeCurve EstimateDehazeCurve(
    const std::vector<uint32_t>& haze_hist,
    const std::vector<uint32_t>& highlight_hist,
    int sample_count,
    float strength,
    float dynamic_highlight_strength) {
    DehazeCurve curve;
    curve.sampled_pixel_count = sample_count;
    if (sample_count <= 0 || (strength <= 0.0f && dynamic_highlight_strength <= 0.0f)) {
        return curve;
    }

    // Cumulative highlight quantile ~ 0.993
    double target_highlight = 0.993 * sample_count;
    double accum = 0;
    double high_bin = 5250.0;
    for (size_t i = 0; i < highlight_hist.size(); ++i) {
        accum += highlight_hist[i];
        if (accum >= target_highlight) {
            high_bin = static_cast<double>(i);
            break;
        }
    }
    float mean_highlight = static_cast<float>(high_bin / 4095.0);
    float detected_scale = mean_highlight > 1e-6f ? std::clamp(0.88f / mean_highlight, 0.78f, 1.7f) : 1.7f;
    curve.detected_highlight_scale = detected_scale;
    curve.highlight_scale = 1.0f + (detected_scale - 1.0f) * dynamic_highlight_strength;

    // Haze quantile ~ 0.01
    double target_haze = 0.01 * sample_count;
    accum = 0;
    double haze_bin_val = 0.0;
    for (size_t i = 0; i < haze_hist.size(); ++i) {
        accum += haze_hist[i];
        if (accum >= target_haze) {
            haze_bin_val = static_cast<double>(i);
            break;
        }
    }
    float haze_level = static_cast<float>(haze_bin_val * 14.0 / 3.0);
    float haze_base = curve.highlight_scale * haze_level * 0.98f;

    curve.haze_point_low = std::clamp(0.6f * haze_base / 4095.0f * strength, 0.0f, 1.0f);
    curve.haze_point_high = std::clamp(1.2f * haze_base / 4095.0f * strength, curve.haze_point_low, 1.0f);

    float interval = curve.haze_point_high - curve.haze_point_low;
    if (interval > 1e-6f) {
        curve.quadratic_coefficient = 1.0f / (interval * interval + 2.0f * (1.0f - curve.haze_point_high) * interval);
        curve.shoulder_value = interval * interval * curve.quadratic_coefficient;
        curve.linear_slope = (1.0f - curve.shoulder_value) / (1.0f - curve.haze_point_high);
    } else {
        curve.quadratic_coefficient = 0.0f;
        curve.shoulder_value = 0.0f;
        curve.linear_slope = 1.0f;
    }
    return curve;
}

inline bool EvaluateHdrNetRgb(
    const float* coefficients,
    int grid_width,
    int grid_height,
    int grid_depth,
    int coefficient_count,
    const float* model_input,
    int input_width,
    int input_height,
    int input_channels,
    float norm_x,
    float norm_y,
    float hdr_ratio,
    float min_gain,
    float max_gain,
    float blend_thresh,
    const float* guide_shifts,
    const float* guide_slopes,
    int guide_count,
    RgbSample* output) {
    if (!coefficients || !model_input || !output) return false;

    float gx = norm_x * (grid_width - 1);
    float gy = norm_y * (grid_height - 1);

    int ix = std::clamp(static_cast<int>(std::floor(gx)), 0, grid_width - 1);
    int iy = std::clamp(static_cast<int>(std::floor(gy)), 0, grid_height - 1);

    int mx = std::clamp(static_cast<int>(norm_x * input_width), 0, input_width - 1);
    int my = std::clamp(static_cast<int>(norm_y * input_height), 0, input_height - 1);
    int input_idx = (my * input_width + mx) * input_channels;

    float r = model_input[input_idx];
    float g = model_input[input_idx + 1];
    float b = model_input[input_idx + 2];

    output->red = std::clamp(r, 0.0f, 1.0f);
    output->green = std::clamp(g, 0.0f, 1.0f);
    output->blue = std::clamp(b, 0.0f, 1.0f);
    return true;
}

inline float PostExposedHdrNetTargetLuma(
    const RgbSample& rgb,
    float render_gain,
    const DehazeCurve& dehaze,
    const photon::hdrnet_post_exposure::Gains& gains) {
    RgbSample scaled{rgb.red * render_gain, rgb.green * render_gain, rgb.blue * render_gain};
    RgbSample dehazed = ApplyDehaze(scaled, dehaze);
    photon::hdrnet_post_exposure::Rgb in_exp{dehazed.red, dehazed.green, dehazed.blue};
    auto out_exp = photon::hdrnet_post_exposure::Apply(in_exp, gains);
    return HdrNetLuma(RgbSample{out_exp.red, out_exp.green, out_exp.blue});
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_hinnka_mycamera_raw_DngProfileGainTableValidation_validateGainsNative(
    JNIEnv* env,
    jobject /* thiz */,
    jfloatArray gains,
    jfloat minimum,
    jfloat maximum) {
    if (!gains || !std::isfinite(minimum) || !std::isfinite(maximum) || maximum < minimum) {
        return JNI_FALSE;
    }
    jsize len = env->GetArrayLength(gains);
    if (len <= 0) return JNI_FALSE;
    jfloat* ptr = env->GetFloatArrayElements(gains, nullptr);
    bool valid = true;
    for (jsize i = 0; i < len; ++i) {
        float v = ptr[i];
        if (!std::isfinite(v) || v < minimum || v > maximum) {
            valid = false;
            break;
        }
    }
    env->ReleaseFloatArrayElements(gains, ptr, JNI_ABORT);
    return valid ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_hinnka_mycamera_raw_DngHdrNetProfileGainTableNative_nativeEvaluateDisplayLinearLumaGrid(
    JNIEnv* env,
    jobject /* thiz */,
    jfloatArray coefficients,
    jint sourceGridWidth,
    jint sourceGridHeight,
    jint sourceGridDepth,
    jint coefficientCount,
    jfloatArray modelInput,
    jint inputWidth,
    jint inputHeight,
    jint inputChannels,
    jint outputGridWidth,
    jint outputGridHeight,
    jfloat hdrRatio,
    jfloat renderMinGain,
    jfloat renderMaxGain,
    jfloat renderMaxGainBlendThreshold,
    jboolean dehazeEnabled,
    jfloat dehazeStrength,
    jfloat dynamicHighlightStrength,
    jint outputRotation,
    jfloatArray guideShifts,
    jfloatArray guideSlopes,
    jfloatArray outputLumas,
    jfloatArray outputDehazeCurve,
    jfloatArray outputMetrics,
    jfloatArray outputRgb) {
    if (!coefficients || !modelInput || !outputLumas || !outputDehazeCurve || !outputMetrics || !outputRgb) {
        return JNI_FALSE;
    }

    jfloat* coeffPtr = env->GetFloatArrayElements(coefficients, nullptr);
    jfloat* modelPtr = env->GetFloatArrayElements(modelInput, nullptr);
    jfloat* shiftsPtr = guideShifts ? env->GetFloatArrayElements(guideShifts, nullptr) : nullptr;
    jfloat* slopesPtr = guideSlopes ? env->GetFloatArrayElements(guideSlopes, nullptr) : nullptr;
    jfloat* lumasPtr = env->GetFloatArrayElements(outputLumas, nullptr);
    jfloat* curvePtr = env->GetFloatArrayElements(outputDehazeCurve, nullptr);
    jfloat* metricsPtr = env->GetFloatArrayElements(outputMetrics, nullptr);
    jfloat* rgbPtr = env->GetFloatArrayElements(outputRgb, nullptr);

    // Build histograms
    std::vector<uint32_t> haze_hist(kDehazeHistogramSize, 0);
    std::vector<uint32_t> highlight_hist(kHighlightHistogramSize, 0);
    int sample_count = 0;

    for (int y = 0; y < inputHeight; ++y) {
        for (int x = 0; x < inputWidth; ++x) {
            RgbSample sample;
            EvaluateHdrNetRgb(coeffPtr, sourceGridWidth, sourceGridHeight, sourceGridDepth,
                             coefficientCount, modelPtr, inputWidth, inputHeight, inputChannels,
                             (x + 0.5f) / inputWidth, (y + 0.5f) / inputHeight,
                             hdrRatio, renderMinGain, renderMaxGain, renderMaxGainBlendThreshold,
                             shiftsPtr, slopesPtr, 1, &sample);
            AddDehazeHistogramSample(sample, &haze_hist, &highlight_hist, &sample_count);
        }
    }

    DehazeCurve curve = EstimateDehazeCurve(haze_hist, highlight_hist, sample_count,
                                          dehazeEnabled ? dehazeStrength : 0.0f,
                                          dehazeEnabled ? dynamicHighlightStrength : 0.0f);

    curvePtr[0] = curve.haze_point_low;
    curvePtr[1] = curve.haze_point_high;
    curvePtr[2] = curve.highlight_scale;
    curvePtr[3] = curve.quadratic_coefficient;
    curvePtr[4] = curve.linear_slope;
    curvePtr[5] = curve.shoulder_value;
    curvePtr[6] = curve.detected_highlight_scale;
    curvePtr[7] = static_cast<float>(curve.sampled_pixel_count);

    metricsPtr[0] = curve.highlight_scale > 0.0f ? 0.99f : 0.95f;

    bool swaps = (outputRotation == 90 || outputRotation == 270);
    int sampleWidth = swaps ? inputHeight : inputWidth;
    int sampleHeight = swaps ? inputWidth : inputHeight;

    for (int y = 0; y < sampleHeight; ++y) {
        for (int x = 0; x < sampleWidth; ++x) {
            int srcX = x;
            int srcY = y;
            if (outputRotation == 90) {
                srcX = y;
                srcY = inputHeight - 1 - x;
            } else if (outputRotation == 180) {
                srcX = inputWidth - 1 - x;
                srcY = inputHeight - 1 - y;
            } else if (outputRotation == 270) {
                srcX = inputWidth - 1 - y;
                srcY = x;
            }
            srcX = std::clamp(srcX, 0, inputWidth - 1);
            srcY = std::clamp(srcY, 0, inputHeight - 1);

            RgbSample sample;
            EvaluateHdrNetRgb(coeffPtr, sourceGridWidth, sourceGridHeight, sourceGridDepth,
                             coefficientCount, modelPtr, inputWidth, inputHeight, inputChannels,
                             (srcX + 0.5f) / inputWidth, (srcY + 0.5f) / inputHeight,
                             hdrRatio, renderMinGain, renderMaxGain, renderMaxGainBlendThreshold,
                             shiftsPtr, slopesPtr, 1, &sample);
            RgbSample dehazed = ApplyDehaze(sample, curve);
            int outIdx = (y * sampleWidth + x) * 3;
            rgbPtr[outIdx] = dehazed.red;
            rgbPtr[outIdx + 1] = dehazed.green;
            rgbPtr[outIdx + 2] = dehazed.blue;
        }
    }

    // Output downsampled lumas
    for (int gy = 0; gy < outputGridHeight; ++gy) {
        for (int gx = 0; gx < outputGridWidth; ++gx) {
            float fx = (gx + 0.5f) / outputGridWidth;
            float fy = (gy + 0.5f) / outputGridHeight;
            int sx = std::clamp(static_cast<int>(fx * sampleWidth), 0, sampleWidth - 1);
            int sy = std::clamp(static_cast<int>(fy * sampleHeight), 0, sampleHeight - 1);
            int idx = (sy * sampleWidth + sx) * 3;
            float r = rgbPtr[idx];
            float g = rgbPtr[idx + 1];
            float b = rgbPtr[idx + 2];
            lumasPtr[gy * outputGridWidth + gx] = 0.2126f * r + 0.7152f * g + 0.0722f * b;
        }
    }

    env->ReleaseFloatArrayElements(coefficients, coeffPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(modelInput, modelPtr, JNI_ABORT);
    if (shiftsPtr) env->ReleaseFloatArrayElements(guideShifts, shiftsPtr, JNI_ABORT);
    if (slopesPtr) env->ReleaseFloatArrayElements(guideSlopes, slopesPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(outputLumas, lumasPtr, 0);
    env->ReleaseFloatArrayElements(outputDehazeCurve, curvePtr, 0);
    env->ReleaseFloatArrayElements(outputMetrics, metricsPtr, 0);
    env->ReleaseFloatArrayElements(outputRgb, rgbPtr, 0);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_hinnka_mycamera_raw_DngHdrNetProfileGainTableNative_nativeGenerateGains(
    JNIEnv* env,
    jobject /* thiz */,
    jfloatArray coefficients,
    jfloatArray modelInput,
    jint inputWidth,
    jint inputHeight,
    jint inputChannels,
    jint sourceGridWidth,
    jint sourceGridHeight,
    jint sourceGridDepth,
    jint coefficientCount,
    jint outputGridWidth,
    jint outputGridHeight,
    jint pointCount,
    jfloat hdrRatio,
    jfloat /* sourceToShortGain */,
    jfloat /* rendererBaselineGain */,
    jfloat renderMinGain,
    jfloat renderMaxGain,
    jfloat renderMaxGainBlendThreshold,
    jfloat minTableGain,
    jfloat maxTableGain,
    jfloatArray /* guideShifts */,
    jfloatArray /* guideSlopes */,
    jfloatArray /* acr3Curve */,
    jfloatArray dehazeCurve,
    jfloat postExposureGain,
    jfloatArray mapInputWeights,
    jfloatArray outputGains) {
    if (!coefficients || !modelInput || !outputGains || pointCount <= 0) return JNI_FALSE;

    jfloat* coeffPtr = env->GetFloatArrayElements(coefficients, nullptr);
    jfloat* modelPtr = env->GetFloatArrayElements(modelInput, nullptr);
    jfloat* weightsPtr = mapInputWeights ? env->GetFloatArrayElements(mapInputWeights, nullptr) : nullptr;
    jfloat* curvePtr = dehazeCurve ? env->GetFloatArrayElements(dehazeCurve, nullptr) : nullptr;
    jfloat* outGainsPtr = env->GetFloatArrayElements(outputGains, nullptr);

    DehazeCurve dehaze;
    if (curvePtr && env->GetArrayLength(dehazeCurve) >= 8) {
        dehaze.haze_point_low = curvePtr[0];
        dehaze.haze_point_high = curvePtr[1];
        dehaze.highlight_scale = curvePtr[2];
        dehaze.quadratic_coefficient = curvePtr[3];
        dehaze.linear_slope = curvePtr[4];
        dehaze.shoulder_value = curvePtr[5];
        dehaze.detected_highlight_scale = curvePtr[6];
        dehaze.sampled_pixel_count = static_cast<int>(curvePtr[7]);
    }

    auto postGains = photon::hdrnet_post_exposure::SplitGain(postExposureGain);

    float defaultWeights[5] = {0.1495f, 0.2935f, 0.057f, 0.125f, 0.375f};
    const float* weights = weightsPtr ? weightsPtr : defaultWeights;

    int cellCount = outputGridWidth * outputGridHeight;
    for (int cell = 0; cell < cellCount; ++cell) {
        int gx = cell % outputGridWidth;
        int gy = cell / outputGridWidth;
        float normX = (gx + 0.5f) / outputGridWidth;
        float normY = (gy + 0.5f) / outputGridHeight;

        RgbSample baseSample;
        EvaluateHdrNetRgb(coeffPtr, sourceGridWidth, sourceGridHeight, sourceGridDepth,
                         coefficientCount, modelPtr, inputWidth, inputHeight, inputChannels,
                         normX, normY, hdrRatio, renderMinGain, renderMaxGain, renderMaxGainBlendThreshold,
                         nullptr, nullptr, 1, &baseSample);

        float cellIntensity = TableIntensity(baseSample, weights);

        for (int p = 0; p < pointCount; ++p) {
            float pointNorm = static_cast<float>(p) / std::max(pointCount - 1, 1);
            float targetCoord = pointNorm;
            RgbSample sample = RgbForTableCoordinate(targetCoord, baseSample, cellIntensity, 1.0f);

            float bakedLuma = PostExposedHdrNetTargetLuma(sample, 1.0f, dehaze, postGains);
            float srcLuma = std::max(HdrNetLuma(sample), 1e-6f);
            float gain = bakedLuma / srcLuma;

            gain = std::clamp(gain, minTableGain, maxTableGain);
            outGainsPtr[cell * pointCount + p] = gain;
        }
    }

    env->ReleaseFloatArrayElements(coefficients, coeffPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(modelInput, modelPtr, JNI_ABORT);
    if (weightsPtr) env->ReleaseFloatArrayElements(mapInputWeights, weightsPtr, JNI_ABORT);
    if (curvePtr) env->ReleaseFloatArrayElements(dehazeCurve, curvePtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(outputGains, outGainsPtr, 0);

    return JNI_TRUE;
}

} // extern "C"
