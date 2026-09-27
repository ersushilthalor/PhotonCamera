#include "jni_common.h"

extern "C" {

JNIEXPORT jobject JNICALL
Java_com_hinnka_mycamera_utils_DirectBufferAllocator_allocateNative(
    JNIEnv* env,
    jobject /* thiz */,
    jlong capacity) {
    if (capacity <= 0) return nullptr;
    void* ptr = std::malloc(static_cast<size_t>(capacity));
    if (!ptr) return nullptr;
    return env->NewDirectByteBuffer(ptr, capacity);
}

JNIEXPORT void JNICALL
Java_com_hinnka_mycamera_utils_DirectBufferAllocator_freeNative(
    JNIEnv* env,
    jobject /* thiz */,
    jobject buffer) {
    if (!buffer) return;
    void* ptr = env->GetDirectBufferAddress(buffer);
    if (ptr) {
        std::free(ptr);
    }
}

JNIEXPORT jboolean JNICALL
Java_com_hinnka_mycamera_utils_DirectBufferPixelPacker_unpackRgba16TileToRgb16(
    JNIEnv* env,
    jobject /* thiz */,
    jobject srcBuffer,
    jint srcX,
    jint srcY,
    jint srcWidth,
    jint srcHeight,
    jobject dstBuffer,
    jint dstWidth,
    jint dstHeight) {
    if (!srcBuffer || !dstBuffer) return JNI_FALSE;
    auto* src = static_cast<const uint16_t*>(env->GetDirectBufferAddress(srcBuffer));
    auto* dst = static_cast<uint16_t*>(env->GetDirectBufferAddress(dstBuffer));
    if (!src || !dst) return JNI_FALSE;

    for (int y = 0; y < dstHeight; ++y) {
        int sy = srcY + y;
        if (sy >= srcHeight) break;
        for (int x = 0; x < dstWidth; ++x) {
            int sx = srcX + x;
            if (sx >= srcWidth) break;
            int srcIdx = (sy * srcWidth + sx) * 4;
            int dstIdx = (y * dstWidth + x) * 3;
            dst[dstIdx + 0] = src[srcIdx + 0];
            dst[dstIdx + 1] = src[srcIdx + 1];
            dst[dstIdx + 2] = src[srcIdx + 2];
        }
    }
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_hinnka_mycamera_utils_DirectBufferPixelPacker_packRgba16fToRgb16(
    JNIEnv* env,
    jobject /* thiz */,
    jobject srcBuffer,
    jobject dstBuffer,
    jint width,
    jint height) {
    if (!srcBuffer || !dstBuffer || width <= 0 || height <= 0) return JNI_FALSE;
    auto* src = static_cast<const uint16_t*>(env->GetDirectBufferAddress(srcBuffer));
    auto* dst = static_cast<uint16_t*>(env->GetDirectBufferAddress(dstBuffer));
    if (!src || !dst) return JNI_FALSE;

    int pixelCount = width * height;
    for (int i = 0; i < pixelCount; ++i) {
        dst[i * 3 + 0] = src[i * 4 + 0];
        dst[i * 3 + 1] = src[i * 4 + 1];
        dst[i * 3 + 2] = src[i * 4 + 2];
    }
    return JNI_TRUE;
}

} // extern "C"
