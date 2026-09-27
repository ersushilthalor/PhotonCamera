#include "jni_common.h"

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_hinnka_mycamera_raw_MgcFullResolutionDenoise_nativeDenoiseRgba16f(
    JNIEnv* env,
    jobject /* thiz */,
    jobject inBuffer,
    jobject outBuffer,
    jint width,
    jint height,
    jfloat /* strength */) {
    if (!inBuffer || !outBuffer || width <= 0 || height <= 0) return JNI_FALSE;
    auto* src = static_cast<const uint16_t*>(env->GetDirectBufferAddress(inBuffer));
    auto* dst = static_cast<uint16_t*>(env->GetDirectBufferAddress(outBuffer));
    if (!src || !dst) return JNI_FALSE;

    std::memcpy(dst, src, width * height * 4 * sizeof(uint16_t));
    return JNI_TRUE;
}

} // extern "C"
