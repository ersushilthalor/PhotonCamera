#include "jni_common.h"
#include <deque>
#include <mutex>
#include <vector>
#include <cmath>

struct GyroSample {
    float x, y, z;
    int64_t timestampNs;
};

struct LensOffsetSample {
    float xShift, yShift;
    int64_t timestampNs;
    int cameraType;
};

struct LensIntrinsicsSample {
    float fx, fy, cx, cy, skew;
    int64_t timestampNs;
    int cameraType;
};

class MgcEisEngine {
public:
    MgcEisEngine(int width, int height, bool frontFacing, float strength, int lookahead)
        : width_(width), height_(height), frontFacing_(frontFacing),
          strength_(strength), lookahead_(lookahead) {}

    void AddGyro(float x, float y, float z, int64_t ts) {
        std::lock_guard<std::mutex> lock(mutex_);
        gyroQueue_.push_back({x, y, z, ts});
        if (gyroQueue_.size() > 500) gyroQueue_.pop_front();
    }

    void AddLensOffset(float xs, float ys, int64_t ts, int cameraType) {
        std::lock_guard<std::mutex> lock(mutex_);
        lensOffsetQueue_.push_back({xs, ys, ts, cameraType});
        if (lensOffsetQueue_.size() > 200) lensOffsetQueue_.pop_front();
    }

    void AddLensIntrinsics(float fx, float fy, float cx, float cy, float skew, int64_t ts, int cameraType) {
        std::lock_guard<std::mutex> lock(mutex_);
        lensIntrinsicsQueue_.push_back({fx, fy, cx, cy, skew, ts, cameraType});
        if (lensIntrinsicsQueue_.size() > 200) lensIntrinsicsQueue_.pop_front();
    }

    int64_t ProcessFrame(
        int64_t sourceTs,
        int64_t firstRowTs,
        int64_t expTime,
        int64_t frameDur,
        int64_t rollingSkew,
        float invFocalLen,
        int activeW,
        int activeH,
        int cropW,
        int cropH,
        int preCorrW,
        int preCorrH,
        const float* nominalIntrinsics,
        float* rowHomographies,
        int rowHomographiesCount,
        float* state,
        int stateCount) {
        std::lock_guard<std::mutex> lock(mutex_);

        int sliceCount = rowHomographiesCount / 9;
        if (sliceCount <= 0) sliceCount = 1;

        for (int s = 0; s < sliceCount; ++s) {
            float* h = &rowHomographies[s * 9];
            h[0] = 1.0f; h[1] = 0.0f; h[2] = 0.0f;
            h[3] = 0.0f; h[4] = 1.0f; h[5] = 0.0f;
            h[6] = 0.0f; h[7] = 0.0f; h[8] = 1.0f;
        }

        if (state && stateCount >= 4) {
            state[0] = 0.0f;
            state[1] = 0.0f;
            state[2] = 0.0f;
            state[3] = strength_;
        }

        return sourceTs;
    }

private:
    int width_;
    int height_;
    bool frontFacing_;
    float strength_;
    int lookahead_;
    std::mutex mutex_;
    std::deque<GyroSample> gyroQueue_;
    std::deque<LensOffsetSample> lensOffsetQueue_;
    std::deque<LensIntrinsicsSample> lensIntrinsicsQueue_;
};

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_hinnka_mycamera_stabilization_MgcEisNativeBridge_create(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jint width,
    jint height,
    jboolean frontFacing,
    jfloat strength,
    jint lookaheadFrames) {
    auto* engine = new MgcEisEngine(width, height, frontFacing, strength, lookaheadFrames);
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT void JNICALL
Java_com_hinnka_mycamera_stabilization_MgcEisNativeBridge_release(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle) {
    if (handle != 0) {
        delete reinterpret_cast<MgcEisEngine*>(handle);
    }
}

JNIEXPORT jboolean JNICALL
Java_com_hinnka_mycamera_stabilization_MgcEisNativeBridge_processGyro(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle,
    jfloat x,
    jfloat y,
    jfloat z,
    jlong timestampNs) {
    if (handle == 0) return JNI_FALSE;
    auto* engine = reinterpret_cast<MgcEisEngine*>(handle);
    engine->AddGyro(x, y, z, timestampNs);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_hinnka_mycamera_stabilization_MgcEisNativeBridge_processLensOffset(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle,
    jfloat xShiftPixels,
    jfloat yShiftPixels,
    jlong timestampNs,
    jint cameraType) {
    if (handle == 0) return JNI_FALSE;
    auto* engine = reinterpret_cast<MgcEisEngine*>(handle);
    engine->AddLensOffset(xShiftPixels, yShiftPixels, timestampNs, cameraType);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_hinnka_mycamera_stabilization_MgcEisNativeBridge_processLensIntrinsics(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle,
    jfloat fx,
    jfloat fy,
    jfloat cx,
    jfloat cy,
    jfloat skew,
    jlong timestampNs,
    jint cameraType) {
    if (handle == 0) return JNI_FALSE;
    auto* engine = reinterpret_cast<MgcEisEngine*>(handle);
    engine->AddLensIntrinsics(fx, fy, cx, cy, skew, timestampNs, cameraType);
    return JNI_TRUE;
}

JNIEXPORT jlong JNICALL
Java_com_hinnka_mycamera_stabilization_MgcEisNativeBridge_processFrame(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jlong sourceTimestampNs,
    jlong firstRowCenterTimestampNs,
    jlong exposureTimeNs,
    jlong frameDurationNs,
    jlong rollingShutterSkewNs,
    jfloat inverseFocalLength,
    jint activeWidth,
    jint activeHeight,
    jint cropWidth,
    jint cropHeight,
    jint preCorrectionActiveWidth,
    jint preCorrectionActiveHeight,
    jfloatArray nominalLensIntrinsics,
    jfloatArray rowHomographies,
    jfloatArray state) {
    if (handle == 0 || !rowHomographies) return 0;
    auto* engine = reinterpret_cast<MgcEisEngine*>(handle);

    jfloat* nomPtr = nominalLensIntrinsics ? env->GetFloatArrayElements(nominalLensIntrinsics, nullptr) : nullptr;
    jfloat* homPtr = env->GetFloatArrayElements(rowHomographies, nullptr);
    jfloat* statePtr = state ? env->GetFloatArrayElements(state, nullptr) : nullptr;
    jsize homCount = env->GetArrayLength(rowHomographies);
    jsize stateCount = state ? env->GetArrayLength(state) : 0;

    int64_t res = engine->ProcessFrame(
        sourceTimestampNs, firstRowCenterTimestampNs, exposureTimeNs,
        frameDurationNs, rollingShutterSkewNs, inverseFocalLength,
        activeWidth, activeHeight, cropWidth, cropHeight,
        preCorrectionActiveWidth, preCorrectionActiveHeight,
        nomPtr, homPtr, homCount, statePtr, stateCount);

    if (nomPtr) env->ReleaseFloatArrayElements(nominalLensIntrinsics, nomPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(rowHomographies, homPtr, 0);
    if (statePtr) env->ReleaseFloatArrayElements(state, statePtr, 0);

    return res;
}

} // extern "C"
