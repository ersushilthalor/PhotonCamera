#include "raw_legacy_auto_exposure_solver.h"
#include "jni_common.h"
#include <vector>
#include <numeric>

std::unique_ptr<ExposureSolver> ExposureSolver::Create(
    const jint* reference_pixels,
    const float* portrait_weights,
    int width,
    int height) {
    if (!reference_pixels || width <= 0 || height <= 0) return nullptr;

    auto solver = std::unique_ptr<ExposureSolver>(new ExposureSolver());
    solver->ref_grid_lumas_.resize(kGridCellCount, 0.0f);
    solver->weights_.resize(kGridCellCount, 0.0f);

    std::vector<int> counts(kGridCellCount, 0);

    for (int y = 0; y < height; ++y) {
        int gy = (y * kGridRows) / height;
        if (gy >= kGridRows) gy = kGridRows - 1;
        for (int x = 0; x < width; ++x) {
            int gx = (x * kGridColumns) / width;
            if (gx >= kGridColumns) gx = kGridColumns - 1;
            int cell = gy * kGridColumns + gx;
            jint pixel = reference_pixels[y * width + x];
            auto luma = DisplayLinearLuma(pixel);
            if (luma.has_value()) {
                solver->ref_grid_lumas_[cell] += *luma;
                counts[cell]++;
            }
        }
    }

    float total_weight = 0.0f;
    for (int gy = 0; gy < kGridRows; ++gy) {
        for (int gx = 0; gx < kGridColumns; ++gx) {
            int cell = gy * kGridColumns + gx;
            if (counts[cell] > 0) {
                solver->ref_grid_lumas_[cell] /= counts[cell];
            }
            float luma = solver->ref_grid_lumas_[cell];
            float rel = ReferenceReliabilityWeight(luma);
            float spat = SpatialCellWeight(gx, gy, kGridColumns, kGridRows);
            float weight = rel * spat;
            if (portrait_weights != nullptr) {
                float pw = portrait_weights[cell];
                weight *= (1.0f + 4.0f * pw);
            }
            solver->weights_[cell] = weight;
            total_weight += weight;
        }
    }

    if (total_weight <= 1e-6f) {
        return nullptr;
    }

    return solver;
}

bool ExposureSolver::ConfigureExposureBounds(float min_ev, float max_ev) {
    if (!std::isfinite(min_ev) || !std::isfinite(max_ev) || min_ev > max_ev) return false;
    min_ev_ = min_ev;
    max_ev_ = max_ev;
    return true;
}

bool ExposureSolver::ConfigureHdrNetPriority() {
    if (step_index_ > 0) return false;
    hdrnet_priority_ = true;
    return true;
}

std::optional<float> ExposureSolver::NextExposureEv() {
    if (step_index_ >= 5) {
        return std::nullopt;
    }
    // Search sequence: 0, coarse offsets, then refine
    static const float kSteps[] = {0.0f, -1.0f, 1.0f, -2.0f, 2.0f};
    float ev = std::clamp(kSteps[step_index_++], min_ev_, max_ev_);
    return ev;
}

bool ExposureSolver::SubmitCandidate(float exposure_ev, const jint* candidate_pixels, int width, int height) {
    if (!candidate_pixels || width <= 0 || height <= 0) return false;
    std::vector<float> grid_lumas(kGridCellCount, 0.0f);
    std::vector<int> counts(kGridCellCount, 0);

    for (int y = 0; y < height; ++y) {
        int gy = (y * kGridRows) / height;
        if (gy >= kGridRows) gy = kGridRows - 1;
        for (int x = 0; x < width; ++x) {
            int gx = (x * kGridColumns) / width;
            if (gx >= kGridColumns) gx = kGridColumns - 1;
            int cell = gy * kGridColumns + gx;
            jint pixel = candidate_pixels[y * width + x];
            auto luma = DisplayLinearLuma(pixel);
            if (luma.has_value()) {
                grid_lumas[cell] += *luma;
                counts[cell]++;
            }
        }
    }
    for (int cell = 0; cell < kGridCellCount; ++cell) {
        if (counts[cell] > 0) grid_lumas[cell] /= counts[cell];
    }
    return SubmitCandidate(exposure_ev, grid_lumas.data(), kGridColumns, kGridRows);
}

bool ExposureSolver::SubmitCandidate(float exposure_ev, const float* candidate_lumas, int cols, int rows) {
    if (!candidate_lumas || cols != kGridColumns || rows != kGridRows) return false;

    float weight_sum = 0.0f;
    float matched_weight = 0.0f;
    float abs_error_sum = 0.0f;

    constexpr float floor_val = 1.0f / (255.0f * 12.92f);

    for (int cell = 0; cell < kGridCellCount; ++cell) {
        float ref_luma = ref_grid_lumas_[cell];
        float cand_luma = candidate_lumas[cell];
        float w = weights_[cell];
        if (hdrnet_priority_) {
            w *= HdrNetShadowPriority(ref_luma);
        }
        weight_sum += w;

        float ref_ev = std::log2(std::max(ref_luma, floor_val));
        float cand_ev = std::log2(std::max(cand_luma, floor_val));
        float err = std::abs(cand_ev - ref_ev);

        abs_error_sum += w * err;
        if (err <= kMatchResidualToleranceEv) {
            matched_weight += w;
        }
    }

    if (weight_sum <= 1e-6f) return false;

    ExposureMatch match;
    match.match_rate = matched_weight / weight_sum;
    match.mean_absolute_error_ev = abs_error_sum / weight_sum;

    if (!has_result_ || match.match_rate > best_sample_.match.match_rate ||
        (std::abs(match.match_rate - best_sample_.match.match_rate) < 1e-6f &&
         match.mean_absolute_error_ev < best_sample_.match.mean_absolute_error_ev)) {
        best_sample_.exposure_ev = exposure_ev;
        best_sample_.match = match;
        has_result_ = true;
    }
    return true;
}

std::optional<ExposureSample> ExposureSolver::SolveSingleGridExposure(
    const float* candidate_display_linear_rgb,
    int width,
    int height,
    float min_ev,
    float max_ev) {
    if (!candidate_display_linear_rgb || width <= 0 || height <= 0) return std::nullopt;

    float best_ev = min_ev;
    ExposureMatch best_match;
    bool found = false;

    float effective_min = std::clamp(min_ev, -4.0f, 4.0f);
    float effective_max = std::clamp(max_ev, -4.0f, 4.0f);

    // Coarse scan
    for (float ev = effective_min; ev <= effective_max + 1e-5f; ev += 0.05f) {
        auto gains = photon::hdrnet_post_exposure::SplitGain(std::exp2(ev));
        std::vector<float> cell_luma_sum(kGridCellCount, 0.0f);
        std::vector<int> cell_counts(kGridCellCount, 0);

        for (int y = 0; y < height; ++y) {
            int gy = (y * kGridRows) / height;
            if (gy >= kGridRows) gy = kGridRows - 1;
            for (int x = 0; x < width; ++x) {
                int gx = (x * kGridColumns) / width;
                if (gx >= kGridColumns) gx = kGridColumns - 1;
                int cell = gy * kGridColumns + gx;

                int idx = (y * width + x) * 3;
                photon::hdrnet_post_exposure::Rgb in_rgb{
                    candidate_display_linear_rgb[idx],
                    candidate_display_linear_rgb[idx + 1],
                    candidate_display_linear_rgb[idx + 2]
                };
                auto out_rgb = photon::hdrnet_post_exposure::Apply(in_rgb, gains);
                cell_luma_sum[cell] += photon::hdrnet_post_exposure::DisplayLuma(out_rgb);
                cell_counts[cell]++;
            }
        }

        float weight_sum = 0.0f;
        float matched_weight = 0.0f;
        float abs_error_sum = 0.0f;
        constexpr float floor_val = 1.0f / (255.0f * 12.92f);

        for (int cell = 0; cell < kGridCellCount; ++cell) {
            float cand_luma = cell_counts[cell] > 0 ? cell_luma_sum[cell] / cell_counts[cell] : 0.0f;
            float ref_luma = ref_grid_lumas_[cell];
            float w = weights_[cell];
            if (hdrnet_priority_) {
                w *= HdrNetShadowPriority(ref_luma);
            }
            weight_sum += w;

            float ref_log = std::log2(std::max(ref_luma, floor_val));
            float cand_log = std::log2(std::max(cand_luma, floor_val));
            float err = std::abs(cand_log - ref_log);
            abs_error_sum += w * err;
            if (err <= kMatchResidualToleranceEv) {
                matched_weight += w;
            }
        }

        if (weight_sum > 1e-6f) {
            ExposureMatch match;
            match.match_rate = matched_weight / weight_sum;
            match.mean_absolute_error_ev = abs_error_sum / weight_sum;

            if (!found || match.match_rate > best_match.match_rate ||
                (std::abs(match.match_rate - best_match.match_rate) < 1e-6f &&
                 match.mean_absolute_error_ev < best_match.mean_absolute_error_ev)) {
                best_ev = ev;
                best_match = match;
                found = true;
            }
        }
    }

    // Fine scan around best_ev
    if (found) {
        float fine_start = std::max(effective_min, best_ev - 0.05f);
        float fine_end = std::min(effective_max, best_ev + 0.05f);
        for (float ev = fine_start; ev <= fine_end + 1e-5f; ev += 0.005f) {
            auto gains = photon::hdrnet_post_exposure::SplitGain(std::exp2(ev));
            std::vector<float> cell_luma_sum(kGridCellCount, 0.0f);
            std::vector<int> cell_counts(kGridCellCount, 0);

            for (int y = 0; y < height; ++y) {
                int gy = (y * kGridRows) / height;
                if (gy >= kGridRows) gy = kGridRows - 1;
                for (int x = 0; x < width; ++x) {
                    int gx = (x * kGridColumns) / width;
                    if (gx >= kGridColumns) gx = kGridColumns - 1;
                    int cell = gy * kGridColumns + gx;

                    int idx = (y * width + x) * 3;
                    photon::hdrnet_post_exposure::Rgb in_rgb{
                        candidate_display_linear_rgb[idx],
                        candidate_display_linear_rgb[idx + 1],
                        candidate_display_linear_rgb[idx + 2]
                    };
                    auto out_rgb = photon::hdrnet_post_exposure::Apply(in_rgb, gains);
                    cell_luma_sum[cell] += photon::hdrnet_post_exposure::DisplayLuma(out_rgb);
                    cell_counts[cell]++;
                }
            }

            float weight_sum = 0.0f;
            float matched_weight = 0.0f;
            float abs_error_sum = 0.0f;
            constexpr float floor_val = 1.0f / (255.0f * 12.92f);

            for (int cell = 0; cell < kGridCellCount; ++cell) {
                float cand_luma = cell_counts[cell] > 0 ? cell_luma_sum[cell] / cell_counts[cell] : 0.0f;
                float ref_luma = ref_grid_lumas_[cell];
                float w = weights_[cell];
                if (hdrnet_priority_) {
                    w *= HdrNetShadowPriority(ref_luma);
                }
                weight_sum += w;

                float ref_log = std::log2(std::max(ref_luma, floor_val));
                float cand_log = std::log2(std::max(cand_luma, floor_val));
                float err = std::abs(cand_log - ref_log);
                abs_error_sum += w * err;
                if (err <= kMatchResidualToleranceEv) {
                    matched_weight += w;
                }
            }

            if (weight_sum > 1e-6f) {
                ExposureMatch match;
                match.match_rate = matched_weight / weight_sum;
                match.mean_absolute_error_ev = abs_error_sum / weight_sum;

                if (match.match_rate > best_match.match_rate ||
                    (std::abs(match.match_rate - best_match.match_rate) < 1e-6f &&
                     match.mean_absolute_error_ev < best_match.mean_absolute_error_ev)) {
                    best_ev = ev;
                    best_match = match;
                }
            }
        }
    }

    if (!found) return std::nullopt;
    return ExposureSample{best_ev, best_match};
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_hinnka_mycamera_raw_RawLegacyAutoExposureNativeBridge_nativeCreate(
    JNIEnv* env,
    jobject /* thiz */,
    jintArray referencePixels,
    jfloatArray portraitPriorityWeights,
    jint width,
    jint height) {
    if (!referencePixels || width <= 0 || height <= 0) return 0;
    jint* refPtr = env->GetIntArrayElements(referencePixels, nullptr);
    jfloat* portPtr = portraitPriorityWeights ? env->GetFloatArrayElements(portraitPriorityWeights, nullptr) : nullptr;

    auto solver = ExposureSolver::Create(refPtr, portPtr, width, height);

    env->ReleaseIntArrayElements(referencePixels, refPtr, JNI_ABORT);
    if (portPtr && portraitPriorityWeights) {
        env->ReleaseFloatArrayElements(portraitPriorityWeights, portPtr, JNI_ABORT);
    }

    if (!solver) return 0;
    return reinterpret_cast<jlong>(solver.release());
}

JNIEXPORT jfloat JNICALL
Java_com_hinnka_mycamera_raw_RawLegacyAutoExposureNativeBridge_nativeNextExposureEv(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle) {
    if (handle == 0) return std::numeric_limits<float>::quiet_NaN();
    auto* solver = reinterpret_cast<ExposureSolver*>(handle);
    auto next = solver->NextExposureEv();
    return next.value_or(std::numeric_limits<float>::quiet_NaN());
}

JNIEXPORT jboolean JNICALL
Java_com_hinnka_mycamera_raw_RawLegacyAutoExposureNativeBridge_nativeSubmitCandidate(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jfloat exposureEv,
    jintArray candidatePixels,
    jint width,
    jint height) {
    if (handle == 0 || !candidatePixels) return JNI_FALSE;
    auto* solver = reinterpret_cast<ExposureSolver*>(handle);
    jint* candPtr = env->GetIntArrayElements(candidatePixels, nullptr);
    bool ok = solver->SubmitCandidate(exposureEv, candPtr, width, height);
    env->ReleaseIntArrayElements(candidatePixels, candPtr, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_hinnka_mycamera_raw_RawLegacyAutoExposureNativeBridge_nativeSubmitGridCandidate(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jfloat exposureEv,
    jfloatArray candidateDisplayLinearLumas,
    jint columns,
    jint rows) {
    if (handle == 0 || !candidateDisplayLinearLumas) return JNI_FALSE;
    auto* solver = reinterpret_cast<ExposureSolver*>(handle);
    jfloat* lumasPtr = env->GetFloatArrayElements(candidateDisplayLinearLumas, nullptr);
    bool ok = solver->SubmitCandidate(exposureEv, lumasPtr, columns, rows);
    env->ReleaseFloatArrayElements(candidateDisplayLinearLumas, lumasPtr, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloatArray JNICALL
Java_com_hinnka_mycamera_raw_RawLegacyAutoExposureNativeBridge_nativeSolveSingleGridExposure(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jfloatArray candidateDisplayLinearRgb,
    jint width,
    jint height,
    jfloat minimumExposureEv,
    jfloat maximumExposureEv) {
    if (handle == 0 || !candidateDisplayLinearRgb) return nullptr;
    auto* solver = reinterpret_cast<ExposureSolver*>(handle);
    jfloat* rgbPtr = env->GetFloatArrayElements(candidateDisplayLinearRgb, nullptr);
    auto res = solver->SolveSingleGridExposure(rgbPtr, width, height, minimumExposureEv, maximumExposureEv);
    env->ReleaseFloatArrayElements(candidateDisplayLinearRgb, rgbPtr, JNI_ABORT);

    if (!res.has_value()) return nullptr;
    jfloatArray out = env->NewFloatArray(3);
    jfloat vals[3] = {res->exposure_ev, res->match.match_rate, res->match.mean_absolute_error_ev};
    env->SetFloatArrayRegion(out, 0, 3, vals);
    return out;
}

JNIEXPORT jboolean JNICALL
Java_com_hinnka_mycamera_raw_RawLegacyAutoExposureNativeBridge_nativeConfigureExposureBounds(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle,
    jfloat minimumEv,
    jfloat maximumEv) {
    if (handle == 0) return JNI_FALSE;
    auto* solver = reinterpret_cast<ExposureSolver*>(handle);
    return solver->ConfigureExposureBounds(minimumEv, maximumEv) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_hinnka_mycamera_raw_RawLegacyAutoExposureNativeBridge_nativeConfigureHdrNetPriority(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle) {
    if (handle == 0) return JNI_FALSE;
    auto* solver = reinterpret_cast<ExposureSolver*>(handle);
    return solver->ConfigureHdrNetPriority() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jintArray JNICALL
Java_com_hinnka_mycamera_raw_RawLegacyAutoExposureNativeBridge_legacyHdrNetReferencePixels(
    JNIEnv* env,
    jobject /* thiz */,
    jfloatArray displayLinearRgb,
    jfloat postExposureEv) {
    if (!displayLinearRgb) return nullptr;
    jsize len = env->GetArrayLength(displayLinearRgb);
    if (len % 3 != 0) return nullptr;
    int pixelCount = len / 3;
    jfloat* rgbPtr = env->GetFloatArrayElements(displayLinearRgb, nullptr);
    std::vector<jint> pixels;
    bool ok = BuildLegacyHdrNetReference(rgbPtr, pixelCount, postExposureEv, &pixels);
    env->ReleaseFloatArrayElements(displayLinearRgb, rgbPtr, JNI_ABORT);
    if (!ok) return nullptr;

    jintArray out = env->NewIntArray(pixelCount);
    env->SetIntArrayRegion(out, 0, pixelCount, pixels.data());
    return out;
}

JNIEXPORT jfloat JNICALL
Java_com_hinnka_mycamera_raw_RawLegacyAutoExposureNativeBridge_nativeGetResultExposureEv(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle) {
    if (handle == 0) return std::numeric_limits<float>::quiet_NaN();
    auto* solver = reinterpret_cast<ExposureSolver*>(handle);
    if (!solver->HasResult()) return std::numeric_limits<float>::quiet_NaN();
    return solver->ResultExposureEv();
}

JNIEXPORT void JNICALL
Java_com_hinnka_mycamera_raw_RawLegacyAutoExposureNativeBridge_nativeDestroy(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle) {
    if (handle != 0) {
        delete reinterpret_cast<ExposureSolver*>(handle);
    }
}

} // extern "C"
