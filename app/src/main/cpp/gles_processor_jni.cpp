#define EGL_EGLEXT_PROTOTYPES
#define GL_GLEXT_PROTOTYPES

#include "jni_common.h"
#include <GLES3/gl3.h>
#include <GLES3/gl32.h>
#include <GLES2/gl2ext.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>

#ifndef GL_TIME_ELAPSED_EXT
#define GL_TIME_ELAPSED_EXT 0x88BF
#endif

#ifndef GL_GPU_DISJOINT_EXT
#define GL_GPU_DISJOINT_EXT 0x8FBB
#endif

#ifndef GL_QUERY_RESULT_EXT
#define GL_QUERY_RESULT_EXT 0x8866
#endif

#ifndef GL_QUERY_RESULT_AVAILABLE_EXT
#define GL_QUERY_RESULT_AVAILABLE_EXT 0x8867
#endif

#ifndef EGL_NATIVE_BUFFER_ANDROID
#define EGL_NATIVE_BUFFER_ANDROID 0x3140
#endif

typedef void (GL_APIENTRY *PFNGLGETQUERYOBJECTUI64VEXTPROC) (GLuint id, GLenum pname, GLuint64 *params);
typedef EGLClientBuffer (EGLAPIENTRY *PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC) (const struct AHardwareBuffer *buffer);
typedef EGLImageKHR (EGLAPIENTRY *PFNEGLCREATEIMAGEKHRPROC) (EGLDisplay dpy, EGLContext ctx, EGLenum target, EGLClientBuffer buffer, const EGLint *attrib_list);
typedef EGLBoolean (EGLAPIENTRY *PFNEGLDESTROYIMAGEKHRPROC) (EGLDisplay dpy, EGLImageKHR image);
typedef void (GL_APIENTRY *PFNGLEGLIMAGETARGETTEXTURE2DOESPROC) (GLenum target, GLeglImageOES image);

static PFNGLGETQUERYOBJECTUI64VEXTPROC fn_glGetQueryObjectui64vEXT = nullptr;
static PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC fn_eglGetNativeClientBufferANDROID = nullptr;
static PFNEGLCREATEIMAGEKHRPROC fn_eglCreateImageKHR = nullptr;
static PFNEGLDESTROYIMAGEKHRPROC fn_eglDestroyImageKHR = nullptr;
static PFNGLEGLIMAGETARGETTEXTURE2DOESPROC fn_glEGLImageTargetTexture2DOES = nullptr;
static bool extensions_initialized = false;

static void InitExtensions() {
    if (extensions_initialized) return;
    fn_glGetQueryObjectui64vEXT = reinterpret_cast<PFNGLGETQUERYOBJECTUI64VEXTPROC>(eglGetProcAddress("glGetQueryObjectui64vEXT"));
    fn_eglGetNativeClientBufferANDROID = reinterpret_cast<PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC>(eglGetProcAddress("eglGetNativeClientBufferANDROID"));
    fn_eglCreateImageKHR = reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(eglGetProcAddress("eglCreateImageKHR"));
    fn_eglDestroyImageKHR = reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(eglGetProcAddress("eglDestroyImageKHR"));
    fn_glEGLImageTargetTexture2DOES = reinterpret_cast<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC>(eglGetProcAddress("glEGLImageTargetTexture2DOES"));
    extensions_initialized = true;
}

struct HardwareBufferImageWrapper {
    AHardwareBuffer* buffer = nullptr;
    EGLImageKHR eglImage = EGL_NO_IMAGE_KHR;
};

extern "C" {

// =========================================================================
// GlesGpuTimerQuery
// =========================================================================

JNIEXPORT jint JNICALL
Java_com_hinnka_mycamera_processor_GlesGpuTimerQuery_counterBits(
    JNIEnv* /* env */,
    jobject /* thiz */) {
    GLint bits = 0;
    glGetQueryiv(GL_TIME_ELAPSED_EXT, GL_QUERY_COUNTER_BITS_EXT, &bits);
    return static_cast<jint>(bits);
}

JNIEXPORT jint JNICALL
Java_com_hinnka_mycamera_processor_GlesGpuTimerQuery_begin(
    JNIEnv* /* env */,
    jobject /* thiz */) {
    GLuint query = 0;
    glGenQueries(1, &query);
    if (query != 0) {
        glBeginQuery(GL_TIME_ELAPSED_EXT, query);
    }
    return static_cast<jint>(query);
}

JNIEXPORT void JNICALL
Java_com_hinnka_mycamera_processor_GlesGpuTimerQuery_end(
    JNIEnv* /* env */,
    jobject /* thiz */) {
    glEndQuery(GL_TIME_ELAPSED_EXT);
}

JNIEXPORT jlong JNICALL
Java_com_hinnka_mycamera_processor_GlesGpuTimerQuery_poll(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jint query) {
    if (query == 0) return -1;
    InitExtensions();

    GLuint available = 0;
    glGetQueryObjectuiv(static_cast<GLuint>(query), GL_QUERY_RESULT_AVAILABLE_EXT, &available);
    if (!available) return -1;

    if (fn_glGetQueryObjectui64vEXT != nullptr) {
        GLuint64 timeNs = 0;
        fn_glGetQueryObjectui64vEXT(static_cast<GLuint>(query), GL_QUERY_RESULT_EXT, &timeNs);
        return static_cast<jlong>(timeNs);
    } else {
        GLuint timeNs = 0;
        glGetQueryObjectuiv(static_cast<GLuint>(query), GL_QUERY_RESULT_EXT, &timeNs);
        return static_cast<jlong>(timeNs);
    }
}

JNIEXPORT jboolean JNICALL
Java_com_hinnka_mycamera_processor_GlesGpuTimerQuery_isDisjoint(
    JNIEnv* /* env */,
    jobject /* thiz */) {
    GLint disjoint = 0;
    glGetIntegerv(GL_GPU_DISJOINT_EXT, &disjoint);
    return disjoint ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_hinnka_mycamera_processor_GlesGpuTimerQuery_delete(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jint query) {
    if (query != 0) {
        GLuint q = static_cast<GLuint>(query);
        glDeleteQueries(1, &q);
    }
}

// =========================================================================
// GlesHardwareBufferImage
// =========================================================================

JNIEXPORT jlong JNICALL
Java_com_hinnka_mycamera_processor_GlesHardwareBufferImage_create(
    JNIEnv* env,
    jobject /* thiz */,
    jobject hardwareBuffer) {
    if (!hardwareBuffer) return 0;
    InitExtensions();

    AHardwareBuffer* aBuf = AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer);
    if (!aBuf) return 0;
    AHardwareBuffer_acquire(aBuf);

    EGLDisplay display = eglGetCurrentDisplay();
    if (display == EGL_NO_DISPLAY || fn_eglGetNativeClientBufferANDROID == nullptr || fn_eglCreateImageKHR == nullptr) {
        AHardwareBuffer_release(aBuf);
        return 0;
    }

    EGLClientBuffer clientBuf = fn_eglGetNativeClientBufferANDROID(aBuf);
    EGLint attrs[] = {
        EGL_IMAGE_PRESERVED_KHR, EGL_TRUE,
        EGL_NONE
    };
    EGLImageKHR image = fn_eglCreateImageKHR(display, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, clientBuf, attrs);
    if (image == EGL_NO_IMAGE_KHR) {
        AHardwareBuffer_release(aBuf);
        return 0;
    }

    auto* wrapper = new HardwareBufferImageWrapper{aBuf, image};
    return reinterpret_cast<jlong>(wrapper);
}

JNIEXPORT jboolean JNICALL
Java_com_hinnka_mycamera_processor_GlesHardwareBufferImage_bind(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle,
    jint textureId) {
    if (handle == 0) return JNI_FALSE;
    InitExtensions();
    auto* wrapper = reinterpret_cast<HardwareBufferImageWrapper*>(handle);
    if (!wrapper || wrapper->eglImage == EGL_NO_IMAGE_KHR || fn_glEGLImageTargetTexture2DOES == nullptr) return JNI_FALSE;

    glBindTexture(GL_TEXTURE_EXTERNAL_OES, static_cast<GLuint>(textureId));
    fn_glEGLImageTargetTexture2DOES(GL_TEXTURE_EXTERNAL_OES, wrapper->eglImage);
    return glGetError() == GL_NO_ERROR ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_hinnka_mycamera_processor_GlesHardwareBufferImage_destroy(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle) {
    if (handle == 0) return;
    InitExtensions();
    auto* wrapper = reinterpret_cast<HardwareBufferImageWrapper*>(handle);
    if (wrapper) {
        EGLDisplay display = eglGetCurrentDisplay();
        if (display != EGL_NO_DISPLAY && wrapper->eglImage != EGL_NO_IMAGE_KHR && fn_eglDestroyImageKHR != nullptr) {
            fn_eglDestroyImageKHR(display, wrapper->eglImage);
        }
        if (wrapper->buffer) {
            AHardwareBuffer_release(wrapper->buffer);
        }
        delete wrapper;
    }
}

// =========================================================================
// GlesPixelBufferTransfer
// =========================================================================

JNIEXPORT jboolean JNICALL
Java_com_hinnka_mycamera_processor_GlesPixelBufferTransfer_uploadRgba16fPboToTexture(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jint pixelBufferObject,
    jint textureId,
    jint width,
    jint height) {
    if (pixelBufferObject == 0 || textureId == 0 || width <= 0 || height <= 0) return JNI_FALSE;

    glBindBuffer(GL_PIXEL_UNPACK_BUFFER, static_cast<GLuint>(pixelBufferObject));
    glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(textureId));
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RGBA, GL_HALF_FLOAT, nullptr);
    glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);

    return glGetError() == GL_NO_ERROR ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
