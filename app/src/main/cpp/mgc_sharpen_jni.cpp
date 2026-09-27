#include "jni_common.h"

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_hinnka_mycamera_raw_MgcSharpen_nativeSharpenRgbaFloat(
    JNIEnv* env,
    jobject /* thiz */,
    jobject rgba,
    jobject /* scratch */,
    jint width,
    jint height,
    jfloat /* strength */) {
    if (!rgba || width <= 0 || height <= 0) return JNI_FALSE;
    return JNI_TRUE;
}

} // extern "C"
