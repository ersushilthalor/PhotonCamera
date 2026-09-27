#include "jni_common.h"

extern "C" {

JNIEXPORT jshortArray JNICALL
Java_com_hinnka_mycamera_lut_LutProcessor_resampleLutNative(
    JNIEnv* env,
    jobject /* thiz */,
    jshortArray srcData,
    jint size,
    jint /* curveType */) {
    if (!srcData || size <= 0) return nullptr;
    jsize len = env->GetArrayLength(srcData);
    jshortArray result = env->NewShortArray(len);
    if (!result) return nullptr;

    jshort* srcPtr = env->GetShortArrayElements(srcData, nullptr);
    env->SetShortArrayRegion(result, 0, len, srcPtr);
    env->ReleaseShortArrayElements(srcData, srcPtr, JNI_ABORT);

    return result;
}

JNIEXPORT jshortArray JNICALL
Java_com_hinnka_mycamera_lut_LutProcessor_resampleSizeNative(
    JNIEnv* env,
    jobject /* thiz */,
    jshortArray srcData,
    jint srcSize,
    jint targetSize) {
    if (!srcData || srcSize <= 0 || targetSize <= 0) return nullptr;

    int totalDst = targetSize * targetSize * targetSize * 4;
    jshortArray result = env->NewShortArray(totalDst);
    if (!result) return nullptr;

    jshort* srcPtr = env->GetShortArrayElements(srcData, nullptr);
    std::vector<jshort> dst(totalDst, 0);

    for (int b = 0; b < targetSize; ++b) {
        float fb = static_cast<float>(b) * (srcSize - 1) / (targetSize - 1);
        int sb = std::clamp(static_cast<int>(fb), 0, srcSize - 1);
        for (int g = 0; g < targetSize; ++g) {
            float fg = static_cast<float>(g) * (srcSize - 1) / (targetSize - 1);
            int sg = std::clamp(static_cast<int>(fg), 0, srcSize - 1);
            for (int r = 0; r < targetSize; ++r) {
                float fr = static_cast<float>(r) * (srcSize - 1) / (targetSize - 1);
                int sr = std::clamp(static_cast<int>(fr), 0, srcSize - 1);

                int srcIdx = ((sb * srcSize + sg) * srcSize + sr) * 4;
                int dstIdx = ((b * targetSize + g) * targetSize + r) * 4;

                dst[dstIdx + 0] = srcPtr[srcIdx + 0];
                dst[dstIdx + 1] = srcPtr[srcIdx + 1];
                dst[dstIdx + 2] = srcPtr[srcIdx + 2];
                dst[dstIdx + 3] = srcPtr[srcIdx + 3];
            }
        }
    }

    env->SetShortArrayRegion(result, 0, totalDst, dst.data());
    env->ReleaseShortArrayElements(srcData, srcPtr, JNI_ABORT);

    return result;
}

} // extern "C"
