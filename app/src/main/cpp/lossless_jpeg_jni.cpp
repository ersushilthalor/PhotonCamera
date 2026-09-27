#include "jni_common.h"
#include <vector>

namespace {

std::vector<uint8_t> EncodeLosslessJpeg(
    const uint16_t* input,
    int width,
    int height,
    int bitsPerSample,
    int samplesPerPixel) {
    std::vector<uint8_t> output;
    output.reserve(width * height * samplesPerPixel * 2 + 1024);

    auto write16 = [&](uint16_t val) {
        output.push_back(static_cast<uint8_t>((val >> 8) & 0xff));
        output.push_back(static_cast<uint8_t>(val & 0xff));
    };

    write16(0xFFD8);
    write16(0xFFC3);
    int sofLength = 8 + 3 * samplesPerPixel;
    write16(static_cast<uint16_t>(sofLength));
    output.push_back(static_cast<uint8_t>(bitsPerSample));
    write16(static_cast<uint16_t>(height));
    write16(static_cast<uint16_t>(width));
    output.push_back(static_cast<uint8_t>(samplesPerPixel));

    for (int c = 1; c <= samplesPerPixel; ++c) {
        output.push_back(static_cast<uint8_t>(c));
        output.push_back(0x11);
        output.push_back(0x00);
    }

    write16(0xFFC4);
    uint8_t bits[16] = {0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0};
    uint8_t huffval[17] = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
    int dhtLength = 2 + 1 + 16 + 17;
    write16(static_cast<uint16_t>(dhtLength));
    output.push_back(0x00);
    for (int i = 0; i < 16; ++i) output.push_back(bits[i]);
    for (int i = 0; i < 17; ++i) output.push_back(huffval[i]);

    write16(0xFFDA);
    int sosLength = 6 + 2 * samplesPerPixel;
    write16(static_cast<uint16_t>(sosLength));
    output.push_back(static_cast<uint8_t>(samplesPerPixel));
    for (int c = 1; c <= samplesPerPixel; ++c) {
        output.push_back(static_cast<uint8_t>(c));
        output.push_back(0x00);
    }
    output.push_back(0x01);
    output.push_back(0x00);
    output.push_back(0x00);

    uint32_t bitBuf = 0;
    int bitCount = 0;

    auto putBits = [&](uint32_t val, int count) {
        bitBuf = (bitBuf << count) | (val & ((1U << count) - 1));
        bitCount += count;
        while (bitCount >= 8) {
            uint8_t byte = static_cast<uint8_t>((bitBuf >> (bitCount - 8)) & 0xff);
            output.push_back(byte);
            if (byte == 0xff) {
                output.push_back(0x00);
            }
            bitCount -= 8;
        }
    };

    std::vector<int> prev(samplesPerPixel, 1 << (bitsPerSample - 1));

    for (int y = 0; y < height; ++y) {
        for (int c = 0; c < samplesPerPixel; ++c) {
            prev[c] = (y == 0) ? (1 << (bitsPerSample - 1)) : input[((y - 1) * width * samplesPerPixel) + c];
        }
        for (int x = 0; x < width; ++x) {
            for (int c = 0; c < samplesPerPixel; ++c) {
                int val = input[(y * width + x) * samplesPerPixel + c];
                int diff = val - prev[c];
                prev[c] = val;

                int absDiff = std::abs(diff);
                int cat = 0;
                while (absDiff > 0) {
                    cat++;
                    absDiff >>= 1;
                }
                if (cat > 16) cat = 16;

                if (cat == 0) {
                    putBits(0, 2);
                } else {
                    putBits((1 << cat) - 1, cat + 1);
                    uint32_t diffBits = (diff >= 0) ? static_cast<uint32_t>(diff) : static_cast<uint32_t>((1 << cat) - 1 + diff);
                    putBits(diffBits, cat);
                }
            }
        }
    }

    if (bitCount > 0) {
        putBits((1U << (8 - bitCount)) - 1, 8 - bitCount);
    }

    write16(0xFFD9);
    return output;
}

} // namespace

extern "C" {

JNIEXPORT jbyteArray JNICALL
Java_com_hinnka_mycamera_utils_SuperResolutionDngWriter_encodeLosslessJpegNative(
    JNIEnv* env,
    jobject /* thiz */,
    jobject source,
    jint width,
    jint height,
    jint bitsPerSample,
    jint samplesPerPixel) {
    if (!source || width <= 0 || height <= 0 || bitsPerSample <= 0 || samplesPerPixel <= 0) {
        return nullptr;
    }

    auto* srcPtr = static_cast<const uint16_t*>(env->GetDirectBufferAddress(source));
    if (!srcPtr) return nullptr;

    auto encoded = EncodeLosslessJpeg(srcPtr, width, height, bitsPerSample, samplesPerPixel);
    if (encoded.empty()) return nullptr;

    jbyteArray result = env->NewByteArray(static_cast<jsize>(encoded.size()));
    if (!result) return nullptr;

    env->SetByteArrayRegion(result, 0, static_cast<jsize>(encoded.size()),
                            reinterpret_cast<const jbyte*>(encoded.data()));
    return result;
}

} // extern "C"
