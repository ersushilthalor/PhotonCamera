#include "jni_common.h"
#include <cmath>
#include <algorithm>

extern "C" {

JNIEXPORT void JNICALL
Java_com_hinnka_mycamera_ml_DnCNNDenoiseEstimator_preprocessNative(
    JNIEnv* env,
    jobject /* thiz */,
    jobject bitmap,
    jint x,
    jint y,
    jint w,
    jint h,
    jobject outBuffer,
    jboolean isRgb,
    jboolean channelsFirst) {
    if (!bitmap || !outBuffer || w <= 0 || h <= 0) return;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) return;
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0 || !pixels) return;

    auto* outPtr = static_cast<float*>(env->GetDirectBufferAddress(outBuffer));
    if (!outPtr) {
        AndroidBitmap_unlockPixels(env, bitmap);
        return;
    }

    int stride = info.stride;
    int srcW = info.width;
    int srcH = info.height;

    for (int r = 0; r < h; ++r) {
        int sy = std::clamp(y + r, 0, srcH - 1);
        const auto* row = reinterpret_cast<const uint32_t*>(static_cast<const uint8_t*>(pixels) + sy * stride);
        for (int c = 0; c < w; ++c) {
            int sx = std::clamp(x + c, 0, srcW - 1);
            uint32_t pixel = row[sx];
            float red = (pixel & 0xff) / 255.0f;
            float green = ((pixel >> 8) & 0xff) / 255.0f;
            float blue = ((pixel >> 16) & 0xff) / 255.0f;

            if (isRgb) {
                if (channelsFirst) {
                    outPtr[0 * w * h + r * w + c] = red;
                    outPtr[1 * w * h + r * w + c] = green;
                    outPtr[2 * w * h + r * w + c] = blue;
                } else {
                    outPtr[(r * w + c) * 3 + 0] = red;
                    outPtr[(r * w + c) * 3 + 1] = green;
                    outPtr[(r * w + c) * 3 + 2] = blue;
                }
            } else {
                float luma = 0.299f * red + 0.587f * green + 0.114f * blue;
                outPtr[r * w + c] = luma;
            }
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap);
}

JNIEXPORT void JNICALL
Java_com_hinnka_mycamera_ml_DnCNNDenoiseEstimator_postprocessNative(
    JNIEnv* env,
    jobject /* thiz */,
    jobject inBuffer,
    jobject srcBitmap,
    jobject dstBitmap,
    jint patchX,
    jint patchY,
    jint srcX,
    jint srcY,
    jint dstX,
    jint dstY,
    jint w,
    jint h,
    jint patchW,
    jint patchH,
    jfloat strength,
    jboolean isRgb,
    jboolean channelsFirst) {
    if (!inBuffer || !srcBitmap || !dstBitmap || w <= 0 || h <= 0) return;

    auto* inPtr = static_cast<const float*>(env->GetDirectBufferAddress(inBuffer));
    if (!inPtr) return;

    AndroidBitmapInfo srcInfo, dstInfo;
    if (AndroidBitmap_getInfo(env, srcBitmap, &srcInfo) < 0) return;
    if (AndroidBitmap_getInfo(env, dstBitmap, &dstInfo) < 0) return;

    void *srcPixels = nullptr, *dstPixels = nullptr;
    if (AndroidBitmap_lockPixels(env, srcBitmap, &srcPixels) < 0 || !srcPixels) return;
    if (AndroidBitmap_lockPixels(env, dstBitmap, &dstPixels) < 0 || !dstPixels) {
        AndroidBitmap_unlockPixels(env, srcBitmap);
        return;
    }

    float blend = std::clamp(strength, 0.0f, 1.0f);

    for (int r = 0; r < h; ++r) {
        int sy = std::clamp(srcY + r, 0, static_cast<int>(srcInfo.height) - 1);
        int dy = std::clamp(dstY + r, 0, static_cast<int>(dstInfo.height) - 1);
        int py = std::clamp(patchY + r, 0, patchH - 1);

        const auto* srcRow = reinterpret_cast<const uint32_t*>(static_cast<const uint8_t*>(srcPixels) + sy * srcInfo.stride);
        auto* dstRow = reinterpret_cast<uint32_t*>(static_cast<uint8_t*>(dstPixels) + dy * dstInfo.stride);

        for (int c = 0; c < w; ++c) {
            int sx = std::clamp(srcX + c, 0, static_cast<int>(srcInfo.width) - 1);
            int dx = std::clamp(dstX + c, 0, static_cast<int>(dstInfo.width) - 1);
            int px = std::clamp(patchX + c, 0, patchW - 1);

            uint32_t srcPixel = srcRow[sx];
            float srcR = (srcPixel & 0xff) / 255.0f;
            float srcG = ((srcPixel >> 8) & 0xff) / 255.0f;
            float srcB = ((srcPixel >> 16) & 0xff) / 255.0f;

            float outR = srcR, outG = srcG, outB = srcB;
            if (isRgb) {
                float resR, resG, resB;
                if (channelsFirst) {
                    resR = inPtr[0 * patchW * patchH + py * patchW + px];
                    resG = inPtr[1 * patchW * patchH + py * patchW + px];
                    resB = inPtr[2 * patchW * patchH + py * patchW + px];
                } else {
                    resR = inPtr[(py * patchW + px) * 3 + 0];
                    resG = inPtr[(py * patchW + px) * 3 + 1];
                    resB = inPtr[(py * patchW + px) * 3 + 2];
                }
                outR = srcR * (1.0f - blend) + resR * blend;
                outG = srcG * (1.0f - blend) + resG * blend;
                outB = srcB * (1.0f - blend) + resB * blend;
            } else {
                float resLuma = inPtr[py * patchW + px];
                float srcLuma = 0.299f * srcR + 0.587f * srcG + 0.114f * srcB;
                float diff = (resLuma - srcLuma) * blend;
                outR = std::clamp(srcR + diff, 0.0f, 1.0f);
                outG = std::clamp(srcG + diff, 0.0f, 1.0f);
                outB = std::clamp(srcB + diff, 0.0f, 1.0f);
            }

            uint32_t rU8 = static_cast<uint32_t>(std::clamp(std::lround(outR * 255.0f), 0L, 255L));
            uint32_t gU8 = static_cast<uint32_t>(std::clamp(std::lround(outG * 255.0f), 0L, 255L));
            uint32_t bU8 = static_cast<uint32_t>(std::clamp(std::lround(outB * 255.0f), 0L, 255L));

            dstRow[dx] = (0xFFU << 24) | (bU8 << 16) | (gU8 << 8) | rU8;
        }
    }

    AndroidBitmap_unlockPixels(env, srcBitmap);
    AndroidBitmap_unlockPixels(env, dstBitmap);
}

} // extern "C"
