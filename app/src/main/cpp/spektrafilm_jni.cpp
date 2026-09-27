#include "jni_common.h"

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_hinnka_mycamera_raw_SpektrafilmRuntime_generate(
    JNIEnv* env,
    jobject /* thiz */,
    jobject srcBitmap,
    jobject dstBitmap,
    jstring /* filmProfileJson */) {
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

    std::memcpy(dstPixels, srcPixels, srcInfo.stride * srcInfo.height);

    AndroidBitmap_unlockPixels(env, srcBitmap);
    AndroidBitmap_unlockPixels(env, dstBitmap);
    return JNI_TRUE;
}

} // extern "C"
