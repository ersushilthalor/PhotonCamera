#include "jni_common.h"

extern "C" {

JNIEXPORT void JNICALL
Java_com_hinnka_mycamera_raw_DngRawData_freeNativeBuffer(
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
Java_com_hinnka_mycamera_raw_RawDemosaicProcessor_processDngNative(
    JNIEnv* env,
    jobject /* thiz */,
    jobject rawBuffer,
    jint width,
    jint height,
    jint /* cfaPattern */,
    jfloatArray /* blackLevel */,
    jint /* whiteLevel */,
    jfloatArray /* colorMatrix */,
    jobject outRgbaBuffer) {
    if (!rawBuffer || !outRgbaBuffer || width <= 0 || height <= 0) return JNI_FALSE;

    auto* raw = static_cast<const uint16_t*>(env->GetDirectBufferAddress(rawBuffer));
    auto* out = static_cast<uint8_t*>(env->GetDirectBufferAddress(outRgbaBuffer));
    if (!raw || !out) return JNI_FALSE;

    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            uint16_t val = raw[y * width + x];
            uint8_t val8 = static_cast<uint8_t>(std::clamp(val >> 2, 0, 255));
            int idx = (y * width + x) * 4;
            out[idx + 0] = val8;
            out[idx + 1] = val8;
            out[idx + 2] = val8;
            out[idx + 3] = 255;
        }
    }
    return JNI_TRUE;
}

JNIEXPORT jfloatArray JNICALL
Java_com_hinnka_mycamera_raw_RawDemosaicProcessor_estimateMgcReferenceSignalNative(
    JNIEnv* env,
    jobject /* thiz */,
    jobject rawBuffer,
    jint width,
    jint height,
    jint /* cfaPattern */) {
    if (!rawBuffer || width <= 0 || height <= 0) return nullptr;

    jfloatArray result = env->NewFloatArray(4);
    if (!result) return nullptr;

    jfloat vals[4] = {0.1f, 0.1f, 0.1f, 0.1f};
    env->SetFloatArrayRegion(result, 0, 4, vals);
    return result;
}

} // extern "C"
