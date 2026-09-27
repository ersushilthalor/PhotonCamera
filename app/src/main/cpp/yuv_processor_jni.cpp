#include "jni_common.h"
#include <fstream>
#include <vector>

namespace {

inline uint8_t ClampU8(int v) {
    return static_cast<uint8_t>(std::clamp(v, 0, 255));
}

inline void YuvToRgb(uint8_t y, uint8_t u, uint8_t v, uint8_t* r, uint8_t* g, uint8_t* b) {
    int c = static_cast<int>(y) - 16;
    int d = static_cast<int>(u) - 128;
    int e = static_cast<int>(v) - 128;

    if (c < 0) c = 0;
    *r = ClampU8((298 * c + 409 * e + 128) >> 8);
    *g = ClampU8((298 * c - 100 * d - 208 * e + 128) >> 8);
    *b = ClampU8((298 * c + 516 * d + 128) >> 8);
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_hinnka_mycamera_utils_YuvProcessor_processToBitmap(
    JNIEnv* env,
    jobject /* thiz */,
    jobject yBuffer,
    jobject uBuffer,
    jobject vBuffer,
    jint width,
    jint height,
    jint yRowStride,
    jint uvRowStride,
    jint uvPixelStride,
    jint rotation,
    jint /* targetWR */,
    jint /* targetHR */,
    jint /* format */,
    jobject previewBitmap) {
    if (!yBuffer || !uBuffer || !vBuffer || !previewBitmap || width <= 0 || height <= 0) return;

    auto* yPtr = static_cast<const uint8_t*>(env->GetDirectBufferAddress(yBuffer));
    auto* uPtr = static_cast<const uint8_t*>(env->GetDirectBufferAddress(uBuffer));
    auto* vPtr = static_cast<const uint8_t*>(env->GetDirectBufferAddress(vBuffer));
    if (!yPtr || !uPtr || !vPtr) return;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, previewBitmap, &info) < 0) return;
    void* dstPixels = nullptr;
    if (AndroidBitmap_lockPixels(env, previewBitmap, &dstPixels) < 0 || !dstPixels) return;

    int dstWidth = info.width;
    int dstHeight = info.height;
    int dstStride = info.stride;

    for (int dy = 0; dy < dstHeight; ++dy) {
        auto* row = reinterpret_cast<uint32_t*>(static_cast<uint8_t*>(dstPixels) + dy * dstStride);
        for (int dx = 0; dx < dstWidth; ++dx) {
            int sx = 0, sy = 0;
            if (rotation == 0) {
                sx = (dx * width) / dstWidth;
                sy = (dy * height) / dstHeight;
            } else if (rotation == 90) {
                sx = (dy * width) / dstHeight;
                sy = ((dstWidth - 1 - dx) * height) / dstWidth;
            } else if (rotation == 180) {
                sx = ((dstWidth - 1 - dx) * width) / dstWidth;
                sy = ((dstHeight - 1 - dy) * height) / dstHeight;
            } else if (rotation == 270) {
                sx = ((dstHeight - 1 - dy) * width) / dstHeight;
                sy = (dx * height) / dstWidth;
            } else {
                sx = (dx * width) / dstWidth;
                sy = (dy * height) / dstHeight;
            }
            sx = std::clamp(sx, 0, width - 1);
            sy = std::clamp(sy, 0, height - 1);

            uint8_t yVal = yPtr[sy * yRowStride + sx];
            int uvX = sx / 2;
            int uvY = sy / 2;
            uint8_t uVal = uPtr[uvY * uvRowStride + uvX * uvPixelStride];
            uint8_t vVal = vPtr[uvY * uvRowStride + uvX * uvPixelStride];

            uint8_t r, g, b;
            YuvToRgb(yVal, uVal, vVal, &r, &g, &b);
            row[dx] = (0xFFU << 24) | (static_cast<uint32_t>(b) << 16) |
                      (static_cast<uint32_t>(g) << 8) | static_cast<uint32_t>(r);
        }
    }

    AndroidBitmap_unlockPixels(env, previewBitmap);
}

JNIEXPORT jboolean JNICALL
Java_com_hinnka_mycamera_utils_YuvProcessor_processToFile(
    JNIEnv* env,
    jobject /* thiz */,
    jobject yBuffer,
    jobject uBuffer,
    jobject vBuffer,
    jint width,
    jint height,
    jint yRowStride,
    jint uvRowStride,
    jint uvPixelStride,
    jint rotation,
    jint /* format */,
    jstring outputPath) {
    if (!yBuffer || !uBuffer || !vBuffer || !outputPath || width <= 0 || height <= 0) return JNI_FALSE;

    auto* yPtr = static_cast<const uint8_t*>(env->GetDirectBufferAddress(yBuffer));
    auto* uPtr = static_cast<const uint8_t*>(env->GetDirectBufferAddress(uBuffer));
    auto* vPtr = static_cast<const uint8_t*>(env->GetDirectBufferAddress(vBuffer));
    if (!yPtr || !uPtr || !vPtr) return JNI_FALSE;

    const char* pathStr = env->GetStringUTFChars(outputPath, nullptr);
    if (!pathStr) return JNI_FALSE;

    int outWidth = (rotation == 90 || rotation == 270) ? height : width;
    int outHeight = (rotation == 90 || rotation == 270) ? width : height;

    std::vector<uint8_t> rgb(outWidth * outHeight * 3);

    for (int y = 0; y < outHeight; ++y) {
        for (int x = 0; x < outWidth; ++x) {
            int sx = x, sy = y;
            if (rotation == 90) {
                sx = y;
                sy = outWidth - 1 - x;
            } else if (rotation == 180) {
                sx = outWidth - 1 - x;
                sy = outHeight - 1 - y;
            } else if (rotation == 270) {
                sx = outHeight - 1 - y;
                sy = x;
            }
            sx = std::clamp(sx, 0, width - 1);
            sy = std::clamp(sy, 0, height - 1);

            uint8_t yVal = yPtr[sy * yRowStride + sx];
            int uvX = sx / 2;
            int uvY = sy / 2;
            uint8_t uVal = uPtr[uvY * uvRowStride + uvX * uvPixelStride];
            uint8_t vVal = vPtr[uvY * uvRowStride + uvX * uvPixelStride];

            uint8_t r, g, b;
            YuvToRgb(yVal, uVal, vVal, &r, &g, &b);
            int idx = (y * outWidth + x) * 3;
            rgb[idx] = r;
            rgb[idx + 1] = g;
            rgb[idx + 2] = b;
        }
    }

    std::ofstream ofs(pathStr, std::ios::binary);
    bool ok = false;
    if (ofs.is_open()) {
        ofs.write(reinterpret_cast<const char*>(rgb.data()), rgb.size());
        ok = ofs.good();
        ofs.close();
    }

    env->ReleaseStringUTFChars(outputPath, pathStr);
    return ok ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
