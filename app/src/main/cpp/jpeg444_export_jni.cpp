#include "jni_common.h"
#include <fstream>
#include <vector>

namespace {

bool WriteJpegSimple(
    const uint8_t* rgba,
    int width,
    int height,
    int stride,
    const char* outputPath,
    int quality,
    const uint8_t* iccData,
    size_t iccSize) {
    std::ofstream ofs(outputPath, std::ios::binary);
    if (!ofs.is_open()) return false;

    uint8_t soi[2] = {0xFF, 0xD8};
    ofs.write(reinterpret_cast<const char*>(soi), 2);

    if (iccData && iccSize > 0) {
        uint16_t app2Len = static_cast<uint16_t>(iccSize + 16);
        uint8_t app2Header[16] = {
            0xFF, 0xE2,
            static_cast<uint8_t>((app2Len >> 8) & 0xff),
            static_cast<uint8_t>(app2Len & 0xff),
            'I', 'C', 'C', '_', 'P', 'R', 'O', 'F', 'I', 'L', 'E', 0x00
        };
        ofs.write(reinterpret_cast<const char*>(app2Header), 16);
        ofs.write(reinterpret_cast<const char*>(iccData), iccSize);
    }

    uint8_t jfif[18] = {
        0xFF, 0xE0, 0x00, 0x10,
        'J', 'F', 'I', 'F', 0x00,
        0x01, 0x01, 0x01,
        0x00, 0x48, 0x00, 0x48,
        0x00, 0x00
    };
    ofs.write(reinterpret_cast<const char*>(jfif), 18);

    uint16_t sofLen = 17;
    uint8_t sof[19] = {
        0xFF, 0xC0,
        static_cast<uint8_t>((sofLen >> 8) & 0xff),
        static_cast<uint8_t>(sofLen & 0xff),
        0x08,
        static_cast<uint8_t>((height >> 8) & 0xff),
        static_cast<uint8_t>(height & 0xff),
        static_cast<uint8_t>((width >> 8) & 0xff),
        static_cast<uint8_t>(width & 0xff),
        0x03,
        0x01, 0x11, 0x00,
        0x02, 0x11, 0x01,
        0x03, 0x11, 0x01
    };
    ofs.write(reinterpret_cast<const char*>(sof), 19);

    uint8_t dqt[134];
    dqt[0] = 0xFF; dqt[1] = 0xDB;
    dqt[2] = 0x00; dqt[3] = 0x84;
    dqt[4] = 0x00;
    for (int i = 0; i < 64; ++i) dqt[5 + i] = static_cast<uint8_t>(std::clamp((100 - quality) / 2 + 1, 1, 255));
    dqt[69] = 0x01;
    for (int i = 0; i < 64; ++i) dqt[70 + i] = static_cast<uint8_t>(std::clamp((100 - quality) / 2 + 1, 1, 255));
    ofs.write(reinterpret_cast<const char*>(dqt), 134);

    uint8_t dht[] = {
        0xFF, 0xC4, 0x00, 0x1F, 0x00,
        0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0,
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11
    };
    ofs.write(reinterpret_cast<const char*>(dht), sizeof(dht));

    uint8_t sos[] = {
        0xFF, 0xDA, 0x00, 0x0C, 0x03,
        0x01, 0x00, 0x02, 0x11, 0x03, 0x11,
        0x00, 0x3F, 0x00
    };
    ofs.write(reinterpret_cast<const char*>(sos), sizeof(sos));

    std::vector<uint8_t> scan;
    scan.reserve(width * height * 3);
    for (int y = 0; y < height; ++y) {
        const auto* row = reinterpret_cast<const uint32_t*>(rgba + y * stride);
        for (int x = 0; x < width; ++x) {
            uint32_t pixel = row[x];
            uint8_t r = pixel & 0xff;
            uint8_t g = (pixel >> 8) & 0xff;
            uint8_t b = (pixel >> 16) & 0xff;
            uint8_t yVal = static_cast<uint8_t>((299 * r + 587 * g + 114 * b) / 1000);
            scan.push_back(yVal == 0xff ? 0xfe : yVal);
        }
    }
    ofs.write(reinterpret_cast<const char*>(scan.data()), scan.size());

    uint8_t eoi[2] = {0xFF, 0xD9};
    ofs.write(reinterpret_cast<const char*>(eoi), 2);

    return ofs.good();
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_hinnka_mycamera_gallery_Jpeg444ExportEncoder_writeNative(
    JNIEnv* env,
    jobject /* thiz */,
    jobject bitmap,
    jstring outputPath,
    jint quality,
    jbyteArray iccProfile) {
    if (!bitmap || !outputPath) return JNI_FALSE;

    const char* pathStr = env->GetStringUTFChars(outputPath, nullptr);
    if (!pathStr) return JNI_FALSE;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) {
        env->ReleaseStringUTFChars(outputPath, pathStr);
        return JNI_FALSE;
    }

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0 || !pixels) {
        env->ReleaseStringUTFChars(outputPath, pathStr);
        return JNI_FALSE;
    }

    jbyte* iccPtr = iccProfile ? env->GetByteArrayElements(iccProfile, nullptr) : nullptr;
    jsize iccLen = iccProfile ? env->GetArrayLength(iccProfile) : 0;

    bool ok = WriteJpegSimple(
        static_cast<const uint8_t*>(pixels),
        info.width, info.height, info.stride,
        pathStr, quality,
        reinterpret_cast<const uint8_t*>(iccPtr), iccLen);

    AndroidBitmap_unlockPixels(env, bitmap);
    if (iccPtr && iccProfile) env->ReleaseByteArrayElements(iccProfile, iccPtr, JNI_ABORT);
    env->ReleaseStringUTFChars(outputPath, pathStr);

    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_hinnka_mycamera_gallery_Jpeg444ExportEncoder_encodeGainmapNative(
    JNIEnv* env,
    jobject thiz,
    jobject bitmap,
    jstring outputPath,
    jint quality) {
    return Java_com_hinnka_mycamera_gallery_Jpeg444ExportEncoder_writeNative(
        env, thiz, bitmap, outputPath, quality, nullptr);
}

JNIEXPORT jboolean JNICALL
Java_com_hinnka_mycamera_gallery_Jpeg444ExportEncoder_packageJpegRNative(
    JNIEnv* env,
    jobject /* thiz */,
    jstring baseJpegPath,
    jstring gainmapJpegPath,
    jstring outputPath,
    jint /* baseColorGamut */,
    jfloatArray /* ratioMin */,
    jfloatArray /* ratioMax */,
    jfloatArray /* gamma */,
    jfloatArray /* epsilonSdr */,
    jfloatArray /* epsilonHdr */,
    jfloat /* displayRatioSdr */,
    jfloat /* displayRatioHdr */,
    jboolean /* useBaseColorSpace */) {
    if (!baseJpegPath || !gainmapJpegPath || !outputPath) return JNI_FALSE;

    const char* basePath = env->GetStringUTFChars(baseJpegPath, nullptr);
    const char* gainmapPath = env->GetStringUTFChars(gainmapJpegPath, nullptr);
    const char* outPath = env->GetStringUTFChars(outputPath, nullptr);

    std::ifstream baseFile(basePath, std::ios::binary);
    std::ifstream gainmapFile(gainmapPath, std::ios::binary);
    std::ofstream outFile(outPath, std::ios::binary);

    bool ok = false;
    if (baseFile.is_open() && gainmapFile.is_open() && outFile.is_open()) {
        outFile << baseFile.rdbuf();
        outFile << gainmapFile.rdbuf();
        ok = outFile.good();
    }

    env->ReleaseStringUTFChars(baseJpegPath, basePath);
    env->ReleaseStringUTFChars(gainmapJpegPath, gainmapPath);
    env->ReleaseStringUTFChars(outputPath, outPath);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_hinnka_mycamera_gallery_Jpeg444ExportEncoder_isJpegRNative(
    JNIEnv* env,
    jobject /* thiz */,
    jstring path) {
    if (!path) return JNI_FALSE;
    const char* filePath = env->GetStringUTFChars(path, nullptr);
    if (!filePath) return JNI_FALSE;

    std::ifstream ifs(filePath, std::ios::binary);
    bool isJpegR = false;
    if (ifs.is_open()) {
        std::string content((std::istreambuf_iterator<char>(ifs)),
                            (std::istreambuf_iterator<char>()));
        if (content.find("hdrgm") != std::string::npos ||
            content.find("GainMap") != std::string::npos ||
            content.find("http://ns.adobe.com/hdr-gain-map/1.0/") != std::string::npos) {
            isJpegR = true;
        }
    }
    env->ReleaseStringUTFChars(path, filePath);
    return isJpegR ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
