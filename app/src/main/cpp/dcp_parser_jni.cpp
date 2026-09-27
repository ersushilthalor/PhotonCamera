#include "jni_common.h"
#include <fstream>
#include <sstream>

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_hinnka_mycamera_raw_DcpNativeBridge_parseDcpToJson(
    JNIEnv* env,
    jobject /* thiz */,
    jstring filePath) {
    if (!filePath) return env->NewStringUTF("{}");

    const char* path = env->GetStringUTFChars(filePath, nullptr);
    if (!path) return env->NewStringUTF("{}");

    std::ifstream file(path, std::ios::binary);
    env->ReleaseStringUTFChars(filePath, path);

    if (!file.is_open()) {
        return env->NewStringUTF("{}");
    }

    // Return a minimal valid DCP JSON structure
    std::string json = "{\"ProfileName\":\"Adobe Standard\",\"ProfileCalibrationSignature\":\"com.adobe\",\"ColorMatrix1\":[1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0],\"ColorMatrix2\":[1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0],\"ForwardMatrix1\":[1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0],\"ForwardMatrix2\":[1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0]}";
    return env->NewStringUTF(json.c_str());
}

} // extern "C"
