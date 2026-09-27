#include "jni_common.h"

extern "C" {

JNIEXPORT void JNICALL
Java_com_hinnka_mycamera_raw_Raw10Unpacker_unpackNative(
    JNIEnv* env,
    jobject /* thiz */,
    jobject srcBuffer,
    jobject dstBuffer,
    jint width,
    jint height,
    jint stride) {
    if (!srcBuffer || !dstBuffer || width <= 0 || height <= 0) return;

    auto* src = static_cast<const uint8_t*>(env->GetDirectBufferAddress(srcBuffer));
    auto* dst = static_cast<uint16_t*>(env->GetDirectBufferAddress(dstBuffer));
    if (!src || !dst) return;

    for (int y = 0; y < height; ++y) {
        const uint8_t* rowSrc = src + y * stride;
        uint16_t* rowDst = dst + y * width;
        int x = 0;
        int srcX = 0;

        while (x + 4 <= width) {
            uint8_t b0 = rowSrc[srcX + 0];
            uint8_t b1 = rowSrc[srcX + 1];
            uint8_t b2 = rowSrc[srcX + 2];
            uint8_t b3 = rowSrc[srcX + 3];
            uint8_t b4 = rowSrc[srcX + 4];

            rowDst[x + 0] = static_cast<uint16_t>((b0 << 2) | (b4 & 0x03));
            rowDst[x + 1] = static_cast<uint16_t>((b1 << 2) | ((b4 >> 2) & 0x03));
            rowDst[x + 2] = static_cast<uint16_t>((b2 << 2) | ((b4 >> 4) & 0x03));
            rowDst[x + 3] = static_cast<uint16_t>((b3 << 2) | ((b4 >> 6) & 0x03));

            x += 4;
            srcX += 5;
        }

        while (x < width) {
            rowDst[x] = static_cast<uint16_t>(rowSrc[srcX] << 2);
            x++;
            srcX++;
        }
    }
}

} // extern "C"
