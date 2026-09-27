#include "jni_common.h"

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_hinnka_mycamera_raw_MgcRaisrUpscale_nativeUpscaleBitmap(
    JNIEnv* env,
    jobject /* thiz */,
    jobject srcBitmap,
    jobject dstBitmap,
    jbyteArray /* filterData */) {
    if (!srcBitmap || !dstBitmap) return JNI_FALSE;

    AndroidBitmapInfo srcInfo, dstInfo;
    if (AndroidBitmap_getInfo(env, srcBitmap, &srcInfo) < 0) return JNI_FALSE;
    if (AndroidBitmap_getInfo(env, dstBitmap, &dstInfo) < 0) return JNI_FALSE;

    void *srcPixels = nullptr, *dstPixels = nullptr;
    if (AndroidBitmap_lockPixels(env, srcBitmap, &srcPixels) < 0 || !srcPixels) return JNI_FALSE;
    if (AndroidBitmap_lockPixels(env, dstBitmap, &dstPixels) < 0 || !dstPixels) {
        AndroidBitmap_unlockPixels(env, srcBitmap);
        return JNI_FALSE;
    }

    for (int y = 0; y < static_cast<int>(dstInfo.height); ++y) {
        int sy = (y * srcInfo.height) / dstInfo.height;
        const auto* srcRow = reinterpret_cast<const uint32_t*>(static_cast<const uint8_t*>(srcPixels) + sy * srcInfo.stride);
        auto* dstRow = reinterpret_cast<uint32_t*>(static_cast<uint8_t*>(dstPixels) + y * dstInfo.stride);
        for (int x = 0; x < static_cast<int>(dstInfo.width); ++x) {
            int sx = (x * srcInfo.width) / dstInfo.width;
            dstRow[x] = srcRow[sx];
        }
    }

    AndroidBitmap_unlockPixels(env, srcBitmap);
    AndroidBitmap_unlockPixels(env, dstBitmap);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_hinnka_mycamera_raw_MgcRaisrUpscale_nativeUpscaleTile(
    JNIEnv* env,
    jobject /* thiz */,
    jobject srcBuffer,
    jobject dstBuffer,
    jint srcWidth,
    jint srcHeight,
    jint dstWidth,
    jint dstHeight,
    jbyteArray /* filterData */) {
    if (!srcBuffer || !dstBuffer || srcWidth <= 0 || srcHeight <= 0 || dstWidth <= 0 || dstHeight <= 0) {
        return JNI_FALSE;
    }

    auto* src = static_cast<const uint8_t*>(env->GetDirectBufferAddress(srcBuffer));
    auto* dst = static_cast<uint8_t*>(env->GetDirectBufferAddress(dstBuffer));
    if (!src || !dst) return JNI_FALSE;

    for (int y = 0; y < dstHeight; ++y) {
        int sy = (y * srcHeight) / dstHeight;
        for (int x = 0; x < dstWidth; ++x) {
            int sx = (x * srcWidth) / dstWidth;
            int srcIdx = (sy * srcWidth + sx) * 4;
            int dstIdx = (y * dstWidth + x) * 4;
            dst[dstIdx + 0] = src[srcIdx + 0];
            dst[dstIdx + 1] = src[srcIdx + 1];
            dst[dstIdx + 2] = src[srcIdx + 2];
            dst[dstIdx + 3] = src[srcIdx + 3];
        }
    }
    return JNI_TRUE;
}

} // extern "C"
