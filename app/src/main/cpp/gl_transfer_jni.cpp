#include "jni_common.h"
#include <GLES3/gl3.h>

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_hinnka_mycamera_raw_RawFloatTextureTransfer_nativeUpload(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jint pbo,
    jint texture,
    jint width,
    jint height) {
    if (pbo == 0 || texture == 0 || width <= 0 || height <= 0) return JNI_FALSE;

    glBindBuffer(GL_PIXEL_UNPACK_BUFFER, static_cast<GLuint>(pbo));
    glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(texture));
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RGBA, GL_HALF_FLOAT, nullptr);
    glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);

    return glGetError() == GL_NO_ERROR ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
