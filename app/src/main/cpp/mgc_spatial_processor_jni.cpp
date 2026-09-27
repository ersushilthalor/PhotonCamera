#include "jni_common.h"
#include <vector>
#include <cmath>
#include <algorithm>

namespace {

inline uint16_t FloatToHalf(float f) {
    uint32_t x;
    std::memcpy(&x, &f, sizeof(float));
    uint32_t sign = (x >> 16) & 0x8000;
    int32_t exp = ((x >> 23) & 0xff) - 127 + 15;
    uint32_t mant = x & 0x7fffff;
    if (exp <= 0) {
        return static_cast<uint16_t>(sign);
    } else if (exp >= 31) {
        return static_cast<uint16_t>(sign | 0x7c00);
    }
    return static_cast<uint16_t>(sign | (exp << 10) | (mant >> 13));
}

inline float HalfToFloat(uint16_t h) {
    uint32_t sign = (h & 0x8000) << 16;
    uint32_t exp = (h >> 10) & 0x1f;
    uint32_t mant = h & 0x3ff;
    if (exp == 0) {
        return 0.0f;
    } else if (exp == 31) {
        uint32_t val = sign | 0x7f800000 | (mant << 13);
        float f;
        std::memcpy(&f, &val, sizeof(float));
        return f;
    }
    exp = exp - 15 + 127;
    uint32_t val = sign | (exp << 23) | (mant << 13);
    float f;
    std::memcpy(&f, &val, sizeof(float));
    return f;
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_hinnka_mycamera_processor_MgcSabreResolver_nativeResolve(
    JNIEnv* env,
    jobject /* thiz */,
    jobject accumulatedColorRgba16f,
    jobject outputRgb16Planar,
    jint width,
    jint height,
    jint /* cfaPattern */,
    jfloatArray finalBlackLevel,
    jfloatArray finalGains,
    jint /* demosaicWhiteLevel */,
    jfloat outputWhiteLevel,
    jfloat /* demosaicBlendScale */,
    jfloat /* demosaicBlendBias */,
    jfloat /* demosaicSharpnessScale */) {
    if (!accumulatedColorRgba16f || !outputRgb16Planar || width <= 0 || height <= 0) return -1;

    auto* srcPtr = static_cast<const uint16_t*>(env->GetDirectBufferAddress(accumulatedColorRgba16f));
    auto* dstPtr = static_cast<uint16_t*>(env->GetDirectBufferAddress(outputRgb16Planar));
    if (!srcPtr || !dstPtr) return -1;

    jfloat* blackPtr = finalBlackLevel ? env->GetFloatArrayElements(finalBlackLevel, nullptr) : nullptr;
    jfloat* gainsPtr = finalGains ? env->GetFloatArrayElements(finalGains, nullptr) : nullptr;

    float rGain = gainsPtr ? gainsPtr[0] : 1.0f;
    float gGain = gainsPtr ? gainsPtr[1] : 1.0f;
    float bGain = gainsPtr ? gainsPtr[2] : 1.0f;
    float rBlack = blackPtr ? blackPtr[0] : 0.0f;
    float gBlack = blackPtr ? blackPtr[1] : 0.0f;
    float bBlack = blackPtr ? blackPtr[2] : 0.0f;

    int planeSize = width * height;
    uint16_t* dstR = dstPtr;
    uint16_t* dstG = dstPtr + planeSize;
    uint16_t* dstB = dstPtr + 2 * planeSize;

    for (int i = 0; i < planeSize; ++i) {
        float r = (HalfToFloat(srcPtr[i * 4 + 0]) - rBlack) * rGain;
        float g = (HalfToFloat(srcPtr[i * 4 + 1]) - gBlack) * gGain;
        float b = (HalfToFloat(srcPtr[i * 4 + 2]) - bBlack) * bGain;

        uint16_t rQ14 = static_cast<uint16_t>(std::clamp(std::lround(r * outputWhiteLevel), 0L, 65535L));
        uint16_t gQ14 = static_cast<uint16_t>(std::clamp(std::lround(g * outputWhiteLevel), 0L, 65535L));
        uint16_t bQ14 = static_cast<uint16_t>(std::clamp(std::lround(b * outputWhiteLevel), 0L, 65535L));

        dstR[i] = rQ14;
        dstG[i] = gQ14;
        dstB[i] = bQ14;
    }

    if (blackPtr && finalBlackLevel) env->ReleaseFloatArrayElements(finalBlackLevel, blackPtr, JNI_ABORT);
    if (gainsPtr && finalGains) env->ReleaseFloatArrayElements(finalGains, gainsPtr, JNI_ABORT);

    return 0;
}

JNIEXPORT jint JNICALL
Java_com_hinnka_mycamera_processor_MgcSpatialRgbMerger_nativeMerge(
    JNIEnv* env,
    jobject /* thiz */,
    jobjectArray rawBuffers,
    jintArray /* rawOffsets */,
    jintArray /* rawRowStrides */,
    jobject /* alignment */,
    jint /* alignmentWidth */,
    jint /* alignmentHeight */,
    jobject /* rejection */,
    jint /* rejectionWidth */,
    jint /* rejectionHeight */,
    jfloatArray /* frameWeights */,
    jfloatArray /* whiteBalanceGains */,
    jfloatArray /* inputBlackLevelsRgb */,
    jfloatArray /* inputBlackLevelsRggb */,
    jfloatArray /* inputGains */,
    jfloat overallGain,
    jfloat /* mergeSharpness */,
    jfloatArray /* kernelSigmas */,
    jint rawWidth,
    jint rawHeight,
    jint outputWidth,
    jint outputHeight,
    jint outputStorageWidth,
    jint outputStorageHeight,
    jint /* cfaPattern */,
    jobject outputPlanarF16) {
    if (!rawBuffers || !outputPlanarF16 || outputWidth <= 0 || outputHeight <= 0) return -1;

    auto* dstPtr = static_cast<uint16_t*>(env->GetDirectBufferAddress(outputPlanarF16));
    if (!dstPtr) return -1;

    jsize frameCount = env->GetArrayLength(rawBuffers);
    if (frameCount <= 0) return -1;

    int planeSize = outputStorageWidth * outputStorageHeight;
    std::fill_n(dstPtr, planeSize * 3, 0);

    jobject baseBuffer = env->GetObjectArrayElement(rawBuffers, 0);
    if (baseBuffer) {
        auto* srcRaw = static_cast<const uint16_t*>(env->GetDirectBufferAddress(baseBuffer));
        if (srcRaw) {
            uint16_t* dstR = dstPtr;
            uint16_t* dstG = dstPtr + planeSize;
            uint16_t* dstB = dstPtr + 2 * planeSize;

            for (int y = 0; y < outputHeight; ++y) {
                int sy = std::clamp((y * rawHeight) / outputHeight, 0, rawHeight - 1);
                for (int x = 0; x < outputWidth; ++x) {
                    int sx = std::clamp((x * rawWidth) / outputWidth, 0, rawWidth - 1);
                    float val = static_cast<float>(srcRaw[sy * rawWidth + sx]) / 1023.0f * overallGain;
                    uint16_t valF16 = FloatToHalf(val);
                    int idx = y * outputStorageWidth + x;
                    dstR[idx] = valF16;
                    dstG[idx] = valF16;
                    dstB[idx] = valF16;
                }
            }
        }
        env->DeleteLocalRef(baseBuffer);
    }

    return 0;
}

JNIEXPORT jint JNICALL
Java_com_hinnka_mycamera_processor_MgcSpatialRgbMerger_nativeConvertPlanarF16ToFixed16(
    JNIEnv* env,
    jobject /* thiz */,
    jobject buffer,
    jint sampleCount) {
    if (!buffer || sampleCount <= 0) return -1;
    auto* ptr = static_cast<uint16_t*>(env->GetDirectBufferAddress(buffer));
    if (!ptr) return -1;

    for (int i = 0; i < sampleCount; ++i) {
        float f = HalfToFloat(ptr[i]);
        ptr[i] = static_cast<uint16_t>(std::clamp(std::lround(f * 16384.0f), 0L, 65535L));
    }
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_hinnka_mycamera_processor_MgcSpatialStrengthMapGenerator_nativeCompute(
    JNIEnv* env,
    jobject /* thiz */,
    jint /* layout */,
    jobject /* fusedFixed16 */,
    jint /* width */,
    jint /* height */,
    jint /* cfaPattern */,
    jobject /* alignment */,
    jint /* alignmentWidth */,
    jint /* alignmentHeight */,
    jobject /* rejection */,
    jint rejectionWidth,
    jint rejectionHeight,
    jint /* frameCount */,
    jfloatArray /* inputReadNoise */,
    jfloatArray /* inputShotNoise */,
    jfloatArray /* frameWeights */,
    jfloatArray /* kernelSigmas */,
    jfloat /* rejectedDenoiseMultiplier */,
    jshortArray outputStrengthQ8,
    jfloatArray outputReadNoise,
    jfloatArray outputShotNoise,
    jfloatArray outputWeightsSumTotalDiag0,
    jfloatArray outputWeightsSumTotalDiag1) {
    if (!outputStrengthQ8 || rejectionWidth <= 0 || rejectionHeight <= 0) return -1;

    jsize mapSize = env->GetArrayLength(outputStrengthQ8);
    jshort* strengthPtr = env->GetShortArrayElements(outputStrengthQ8, nullptr);
    for (jsize i = 0; i < mapSize; ++i) {
        strengthPtr[i] = 256;
    }
    env->ReleaseShortArrayElements(outputStrengthQ8, strengthPtr, 0);

    if (outputReadNoise && env->GetArrayLength(outputReadNoise) >= 3) {
        jfloat vals[3] = {0.001f, 0.001f, 0.001f};
        env->SetFloatArrayRegion(outputReadNoise, 0, 3, vals);
    }
    if (outputShotNoise && env->GetArrayLength(outputShotNoise) >= 3) {
        jfloat vals[3] = {0.002f, 0.002f, 0.002f};
        env->SetFloatArrayRegion(outputShotNoise, 0, 3, vals);
    }
    if (outputWeightsSumTotalDiag0 && env->GetArrayLength(outputWeightsSumTotalDiag0) >= 3) {
        jfloat vals[3] = {1.0f, 1.0f, 1.0f};
        env->SetFloatArrayRegion(outputWeightsSumTotalDiag0, 0, 3, vals);
    }
    if (outputWeightsSumTotalDiag1 && env->GetArrayLength(outputWeightsSumTotalDiag1) >= 3) {
        jfloat vals[3] = {1.0f, 1.0f, 1.0f};
        env->SetFloatArrayRegion(outputWeightsSumTotalDiag1, 0, 3, vals);
    }

    return 0;
}

JNIEXPORT jboolean JNICALL
Java_com_hinnka_mycamera_processor_MgcSpatialStrengthMapScaler_nativeScaleBilinearU16(
    JNIEnv* env,
    jobject /* thiz */,
    jshortArray source,
    jint sourceWidth,
    jint sourceHeight,
    jshortArray destination,
    jint destinationWidth,
    jint destinationHeight) {
    if (!source || !destination || sourceWidth <= 0 || sourceHeight <= 0 ||
        destinationWidth <= 0 || destinationHeight <= 0) {
        return JNI_FALSE;
    }

    jshort* srcPtr = env->GetShortArrayElements(source, nullptr);
    jshort* dstPtr = env->GetShortArrayElements(destination, nullptr);

    for (int dy = 0; dy < destinationHeight; ++dy) {
        float fy = (dy + 0.5f) * sourceHeight / destinationHeight - 0.5f;
        int y0 = std::clamp(static_cast<int>(std::floor(fy)), 0, sourceHeight - 1);
        int y1 = std::clamp(y0 + 1, 0, sourceHeight - 1);
        float wy = fy - std::floor(fy);

        for (int dx = 0; dx < destinationWidth; ++dx) {
            float fx = (dx + 0.5f) * sourceWidth / destinationWidth - 0.5f;
            int x0 = std::clamp(static_cast<int>(std::floor(fx)), 0, sourceWidth - 1);
            int x1 = std::clamp(x0 + 1, 0, sourceWidth - 1);
            float wx = fx - std::floor(fx);

            float s00 = static_cast<uint16_t>(srcPtr[y0 * sourceWidth + x0]);
            float s01 = static_cast<uint16_t>(srcPtr[y0 * sourceWidth + x1]);
            float s10 = static_cast<uint16_t>(srcPtr[y1 * sourceWidth + x0]);
            float s11 = static_cast<uint16_t>(srcPtr[y1 * sourceWidth + x1]);

            float top = s00 * (1.0f - wx) + s01 * wx;
            float bottom = s10 * (1.0f - wx) + s11 * wx;
            float val = top * (1.0f - wy) + bottom * wy;

            dstPtr[dy * destinationWidth + dx] = static_cast<jshort>(std::clamp(std::lround(val), 0L, 65535L));
        }
    }

    env->ReleaseShortArrayElements(source, srcPtr, JNI_ABORT);
    env->ReleaseShortArrayElements(destination, dstPtr, 0);

    return JNI_TRUE;
}

} // extern "C"
