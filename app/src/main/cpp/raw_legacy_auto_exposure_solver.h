#pragma once

#include <cstdint>
#include <cstddef>
#include <cmath>
#include <vector>
#include <memory>
#include <algorithm>
#include <optional>
#include <jni.h>

namespace photon {
namespace hdrnet_post_exposure {

struct Rgb {
    float red;
    float green;
    float blue;
};

struct Gains {
    float rolloff;
    float digital;
};

inline Gains SplitGain(float gain) {
    if (gain <= 1.0f) {
        return {1.0f, gain};
    } else if (gain < 3.0f) {
        float rolloff = (-5.0f * gain * gain + 23.0f * gain - 6.0f) / 12.0f;
        return {rolloff, gain / rolloff};
    } else {
        return {1.5f, gain / 1.5f};
    }
}

inline Rgb Apply(const Rgb& rgb, const Gains& gains) {
    float peak = std::clamp(std::max({rgb.red, rgb.green, rgb.blue}), 0.0f, 1.0f);
    float gain_rolloff = 1.0f + (gains.rolloff - 1.0f) * (1.0f - peak) * (1.0f - peak);
    float total_gain = gain_rolloff * gains.digital;
    return {
        std::clamp(rgb.red * total_gain, 0.0f, 1.0f),
        std::clamp(rgb.green * total_gain, 0.0f, 1.0f),
        std::clamp(rgb.blue * total_gain, 0.0f, 1.0f)
    };
}

inline float DisplayLuma(const Rgb& rgb) {
    return 0.2126f * rgb.red + 0.7152f * rgb.green + 0.0722f * rgb.blue;
}

} // namespace hdrnet_post_exposure
} // namespace photon

constexpr int kGridColumns = 8;
constexpr int kGridRows = 6;
constexpr int kGridCellCount = kGridColumns * kGridRows;
constexpr float kMatchResidualToleranceEv = 0.1f;
constexpr int kMaximumSampleCount = 32;

inline float srgb_to_linear_scalar(float srgb) {
    srgb = std::clamp(srgb, 0.0f, 1.0f);
    return srgb <= 0.04045f ? srgb / 12.92f : std::pow((srgb + 0.055f) / 1.055f, 2.4f);
}

inline float linear_to_srgb_scalar(float linear) {
    linear = std::max(linear, 0.0f);
    return linear <= 0.0031308f ? linear * 12.92f : 1.055f * std::pow(linear, 1.0f / 2.4f) - 0.055f;
}

inline std::optional<float> DisplayLinearLuma(jint argb) {
    float r = static_cast<float>((argb >> 16) & 0xff) / 255.0f;
    float g = static_cast<float>((argb >> 8) & 0xff) / 255.0f;
    float b = static_cast<float>(argb & 0xff) / 255.0f;
    float lin_r = srgb_to_linear_scalar(r);
    float lin_g = srgb_to_linear_scalar(g);
    float lin_b = srgb_to_linear_scalar(b);
    return 0.2126f * lin_r + 0.7152f * lin_g + 0.0722f * lin_b;
}

inline float HdrNetShadowPriority(float luma) {
    luma = std::clamp(luma, 0.0f, 1.0f);
    if (luma >= 0.25f) return 1.0f;
    if (luma <= 0.02f) return 1.25f;
    float t = (luma - 0.02f) / (0.25f - 0.02f);
    return 1.25f - 0.25f * t;
}

inline float ReferenceReliabilityWeight(float luma) {
    auto smoothstep = [](float edge0, float edge1, float x) {
        float t = std::clamp((x - edge0) / (edge1 - edge0), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    };
    float shadow_zero = srgb_to_linear_scalar(4.0f / 255.0f);
    float shadow_full = srgb_to_linear_scalar(16.0f / 255.0f);
    float highlight_full = srgb_to_linear_scalar(220.0f / 255.0f);
    float highlight_zero = srgb_to_linear_scalar(240.0f / 255.0f);
    return smoothstep(shadow_zero, shadow_full, luma) * (1.0f - smoothstep(highlight_full, highlight_zero, luma));
}

inline float SpatialCellWeight(int x, int y, int cols = kGridColumns, int rows = kGridRows) {
    int dist_x = std::min(x, cols - 1 - x);
    int dist_y = std::min(y, rows - 1 - y);
    return static_cast<float>(std::min(dist_x, dist_y) + 1);
}

struct ExposureMatch {
    float match_rate = 0.0f;
    float mean_absolute_error_ev = 0.0f;
    float weighted_median_ev = 0.0f;
};

struct ExposureSample {
    float exposure_ev = 0.0f;
    ExposureMatch match;
};

class ExposureSolver {
public:
    static std::unique_ptr<ExposureSolver> Create(
        const jint* reference_pixels,
        const float* portrait_weights,
        int width,
        int height);

    bool ConfigureExposureBounds(float min_ev, float max_ev);
    bool ConfigureHdrNetPriority();
    std::optional<float> NextExposureEv();
    bool SubmitCandidate(float exposure_ev, const jint* candidate_pixels, int width, int height);
    bool SubmitCandidate(float exposure_ev, const float* candidate_lumas, int cols, int rows);
    std::optional<ExposureSample> SolveSingleGridExposure(
        const float* candidate_display_linear_rgb,
        int width,
        int height,
        float min_ev,
        float max_ev);

    bool HasResult() const { return has_result_; }
    const ExposureSample& BestSample() const { return best_sample_; }
    float ResultExposureEv() const { return best_sample_.exposure_ev; }

private:
    ExposureSolver() = default;

    std::vector<float> ref_grid_lumas_;
    std::vector<float> weights_;
    float min_ev_ = -4.0f;
    float max_ev_ = 4.0f;
    bool hdrnet_priority_ = false;
    int step_index_ = 0;
    bool has_result_ = false;
    ExposureSample best_sample_;
};

inline bool BuildLegacyHdrNetReference(
    const float* display_linear_rgb,
    int pixel_count,
    float post_exposure_ev,
    std::vector<jint>* output) {
    if (!display_linear_rgb || !output || !std::isfinite(post_exposure_ev) || pixel_count <= 0) {
        return false;
    }
    output->resize(pixel_count);
    auto gains = photon::hdrnet_post_exposure::SplitGain(std::exp2(post_exposure_ev));
    auto encode = [](float linear) -> uint32_t {
        float srgb = linear <= 0.0031308f ? 12.92f * linear : 1.055f * std::pow(linear, 1.0f / 2.4f) - 0.055f;
        return static_cast<uint32_t>(std::clamp(std::lround(srgb * 255.0f), 0L, 255L));
    };
    for (int i = 0; i < pixel_count; ++i) {
        photon::hdrnet_post_exposure::Rgb in_rgb{
            display_linear_rgb[i * 3],
            display_linear_rgb[i * 3 + 1],
            display_linear_rgb[i * 3 + 2]
        };
        auto out_rgb = photon::hdrnet_post_exposure::Apply(in_rgb, gains);
        (*output)[i] = static_cast<jint>(
            0xff000000U | (encode(out_rgb.red) << 16U) | (encode(out_rgb.green) << 8U) | encode(out_rgb.blue));
    }
    return true;
}
