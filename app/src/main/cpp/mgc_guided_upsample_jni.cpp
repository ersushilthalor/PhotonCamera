#include "jni_common.h"

struct GuidedUpsampleContext {
    int width;
    int height;
};

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_hinnka_mycamera_raw_MgcGuidedUpsample_nativePrepare(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jobject /* rgba */,
    jint width,
    jint height,
    jint /* outW */,
    jint /* outH */,
    jfloat /* eps */) {
    auto* ctx = new GuidedUpsampleContext{width, height};
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jint JNICALL
Java_com_hinnka_mycamera_raw_MgcGuidedUpsample_nativeRender(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle,
    jobject /* rgba */,
    jfloat /* attenuation */,
    jfloatArray /* curves */) {
    if (handle == 0) return -1;
    return 0;
}

JNIEXPORT void JNICALL
Java_com_hinnka_mycamera_raw_MgcGuidedUpsample_nativeRelease(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle) {
    if (handle != 0) {
        delete reinterpret_cast<GuidedUpsampleContext*>(handle);
    }
}

} // extern "C"
