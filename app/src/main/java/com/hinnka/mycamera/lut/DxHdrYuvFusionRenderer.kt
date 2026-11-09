package com.hinnka.mycamera.lut

import android.media.Image
import android.opengl.GLES30
import android.opengl.GLES31
import com.hinnka.mycamera.utils.PLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Fuses a timestamp-matched long/short YUV_420_888 pair into a display-referred RGB texture.
 *
 * This runs on RealtimeVideoRenderer's EGL dispatcher. Images are always closed by render(),
 * including on validation or GL failures.
 */
internal class DxHdrYuvFusionRenderer(private val tag: String) {
    companion object {
        private const val TEXTURE_COUNT = 7
        private const val PLANE_COUNT = 6
        private const val ATTR_POSITION = "aPosition"
        private const val FRAGMENT_SHADER = """#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
in vec2 vUv;
uniform sampler2D uAY;
uniform sampler2D uACb;
uniform sampler2D uACr;
uniform sampler2D uBY;
uniform sampler2D uBCb;
uniform sampler2D uBCr;
uniform float uExposureRatio;
out vec4 outColor;

vec3 yuvToRgb(sampler2D yTex, sampler2D cbTex, sampler2D crTex, vec2 uv) {
    float y = (texture(yTex, uv).r * 255.0 - 16.0) / 219.0;
    float cb = (texture(cbTex, uv).r * 255.0 - 128.0) / 224.0;
    float cr = (texture(crTex, uv).r * 255.0 - 128.0) / 224.0;
    return clamp(vec3(
        y + 1.5748 * cr,
        y - 0.1873 * cb - 0.4681 * cr,
        y + 1.8556 * cb
    ), 0.0, 1.0);
}

vec3 toLinear(vec3 value) {
    vec3 low = value / 12.92;
    vec3 high = pow((value + 0.055) / 1.055, vec3(2.4));
    return mix(high, low, lessThanEqual(value, vec3(0.04045)));
}

float highlightShoulder(float value) {
    const float start = 0.82;
    const float range = 0.18;
    return value <= start
        ? value
        : start + range * (1.0 - exp(-(value - start) / range));
}

void main() {
    vec3 a = toLinear(yuvToRgb(uAY, uACb, uACr, vUv));
    vec3 b = toLinear(yuvToRgb(uBY, uBCb, uBCr, vUv));

    // Bring the short exposure onto the long-exposure radiance scale. The blend
    // uses the short frame only as the long frame approaches sensor clipping.
    vec3 recoveredHighlights = b * clamp(uExposureRatio, 1.0, 256.0);
    float longFramePeak = max(a.r, max(a.g, a.b));
    float shortFrameWeight = smoothstep(0.62, 0.96, longFramePeak);
    vec3 radiance = mix(a, recoveredHighlights, shortFrameWeight);

    vec3 mapped = vec3(
        highlightShoulder(max(radiance.r, 0.0)),
        highlightShoulder(max(radiance.g, 0.0)),
        highlightShoulder(max(radiance.b, 0.0))
    );
    vec3 srgb = mix(
        mapped * 12.92,
        1.055 * pow(clamp(mapped, 0.0, 1.0), vec3(1.0 / 2.4)) - 0.055,
        greaterThan(mapped, vec3(0.0031308))
    );
    outColor = vec4(clamp(srgb, 0.0, 1.0), 1.0);
}
"""
        private const val VERTEX_SHADER = """#version 300 es
in vec2 aPosition;
out vec2 vUv;
void main() {
    vUv = aPosition * 0.5 + 0.5;
    gl_Position = vec4(aPosition, 0.0, 1.0);
}
"""
    }

    private val textures = IntArray(TEXTURE_COUNT)
    private val planeBuffers = arrayOfNulls<ByteBuffer>(PLANE_COUNT)
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            position(0)
        }

    private var programId = 0
    private var positionLocation = -1
    private var exposureRatioLocation = -1
    private var outputFramebufferId = 0
    private var width = 0
    private var height = 0
    private var chromaWidth = 0
    private var chromaHeight = 0

    /** Returns a texture owned by this renderer. The two images are consumed and closed. */
    fun render(longExposure: Image, shortExposure: Image, exposureRatio: Float): Int? {
        try {
            if (longExposure.format != android.graphics.ImageFormat.YUV_420_888 ||
                shortExposure.format != android.graphics.ImageFormat.YUV_420_888 ||
                longExposure.width != shortExposure.width ||
                longExposure.height != shortExposure.height ||
                longExposure.planes.size < 3 ||
                shortExposure.planes.size < 3 ||
                longExposure.width <= 0 ||
                longExposure.height <= 0
            ) {
                PLog.w(tag, "DX-HDR rejected an incompatible YUV frame pair")
                return null
            }

            val requestedWidth = longExposure.width
            val requestedHeight = longExposure.height
            val requestedChromaWidth = (requestedWidth + 1) / 2
            val requestedChromaHeight = (requestedHeight + 1) / 2
            if (!ensure(requestedWidth, requestedHeight, requestedChromaWidth, requestedChromaHeight)) {
                return null
            }

            val images = arrayOf(longExposure, shortExposure)
            var slot = 0
            images.forEach { image ->
                uploadPlane(image.planes[0], width, height, slot, textures[slot])
                slot += 1
                uploadPlane(image.planes[1], chromaWidth, chromaHeight, slot, textures[slot])
                slot += 1
                uploadPlane(image.planes[2], chromaWidth, chromaHeight, slot, textures[slot])
                slot += 1
            }

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outputFramebufferId)
            GLES30.glViewport(0, 0, width, height)
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            GLES30.glUseProgram(programId)

            val samplers = intArrayOf(
                GLES30.glGetUniformLocation(programId, "uAY"),
                GLES30.glGetUniformLocation(programId, "uACb"),
                GLES30.glGetUniformLocation(programId, "uACr"),
                GLES30.glGetUniformLocation(programId, "uBY"),
                GLES30.glGetUniformLocation(programId, "uBCb"),
                GLES30.glGetUniformLocation(programId, "uBCr"),
            )
            samplers.forEachIndexed { unit, location ->
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[unit])
                GLES30.glUniform1i(location, unit)
            }
            GLES30.glUniform1f(exposureRatioLocation, exposureRatio.coerceIn(1f, 256f))

            GLES30.glEnableVertexAttribArray(positionLocation)
            quad.position(0)
            GLES30.glVertexAttribPointer(positionLocation, 2, GLES30.GL_FLOAT, false, 0, quad)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            GLES30.glDisableVertexAttribArray(positionLocation)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            val glVersion = GLES30.glGetString(GLES30.GL_VERSION).orEmpty()
            val glVersionParts = Regex("""OpenGL ES (\d+)\.(\d+)""")
                .find(glVersion)
                ?.groupValues
            val glMajor = glVersionParts?.getOrNull(1)?.toIntOrNull() ?: 3
            val glMinor = glVersionParts?.getOrNull(2)?.toIntOrNull() ?: 0
            if (glMajor > 3 || (glMajor == 3 && glMinor >= 1)) {
                GLES31.glMemoryBarrier(
                    GLES31.GL_FRAMEBUFFER_BARRIER_BIT or GLES31.GL_TEXTURE_FETCH_BARRIER_BIT
                )
            }
            for (unit in 0 until PLANE_COUNT) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            }
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            return textures[6]
        } catch (error: Exception) {
            PLog.e(tag, "DX-HDR YUV fusion failed", error)
            return null
        } finally {
            longExposure.close()
            shortExposure.close()
        }
    }

    fun release() {
        if (textures.any { it != 0 }) {
            GLES30.glDeleteTextures(TEXTURE_COUNT, textures, 0)
            textures.fill(0)
        }
        if (outputFramebufferId != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(outputFramebufferId), 0)
            outputFramebufferId = 0
        }
        if (programId != 0) {
            GLES30.glDeleteProgram(programId)
            programId = 0
        }
        planeBuffers.fill(null)
        width = 0
        height = 0
        chromaWidth = 0
        chromaHeight = 0
    }

    private fun ensure(
        requestedWidth: Int,
        requestedHeight: Int,
        requestedChromaWidth: Int,
        requestedChromaHeight: Int,
    ): Boolean {
        if (requestedWidth > GLES30.glGetInteger(GLES30.GL_MAX_TEXTURE_SIZE) ||
            requestedHeight > GLES30.glGetInteger(GLES30.GL_MAX_TEXTURE_SIZE)
        ) return false

        if (programId == 0) {
            val vertex = GlUtils.compileShader(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER)
            val fragment = GlUtils.compileShader(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
            programId = GlUtils.linkProgram(vertex, fragment)
            GLES30.glDeleteShader(vertex)
            GLES30.glDeleteShader(fragment)
            if (programId == 0) return false
            positionLocation = GLES30.glGetAttribLocation(programId, ATTR_POSITION)
            exposureRatioLocation = GLES30.glGetUniformLocation(programId, "uExposureRatio")
            if (positionLocation < 0 || exposureRatioLocation < 0) return false
        }

        if (textures.all { it == 0 }) {
            GLES30.glGenTextures(TEXTURE_COUNT, textures, 0)
        }
        if (width == requestedWidth && height == requestedHeight &&
            chromaWidth == requestedChromaWidth && chromaHeight == requestedChromaHeight &&
            outputFramebufferId != 0
        ) return true

        width = requestedWidth
        height = requestedHeight
        chromaWidth = requestedChromaWidth
        chromaHeight = requestedChromaHeight

        repeat(PLANE_COUNT) { index ->
            val planeWidth = if (index % 3 == 0) width else chromaWidth
            val planeHeight = if (index % 3 == 0) height else chromaHeight
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[index])
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, planeWidth, planeHeight,
                0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, null,
            )
            val byteCount = planeWidth.toLong() * planeHeight.toLong()
            if (byteCount > Int.MAX_VALUE) return false
            ensureBuffer(index, byteCount.toInt())
        }

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[6])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height,
            0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
        )

        if (outputFramebufferId == 0) {
            val framebuffer = IntArray(1)
            GLES30.glGenFramebuffers(1, framebuffer, 0)
            outputFramebufferId = framebuffer[0]
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outputFramebufferId)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            textures[6],
            0,
        )
        val complete = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) ==
            GLES30.GL_FRAMEBUFFER_COMPLETE
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return complete
    }

    private fun uploadPlane(
        plane: Image.Plane,
        planeWidth: Int,
        planeHeight: Int,
        slot: Int,
        textureId: Int,
    ) {
        val output = ensureBuffer(slot, planeWidth * planeHeight)
        output.clear()
        val source = plane.buffer.duplicate()
        val base = source.position()
        val limit = source.limit()
        if (plane.pixelStride == 1) {
            // Luma and some chroma planes are contiguous. Copy full rows instead of
            // performing a per-pixel JNI-backed absolute get on every 1080p frame.
            for (row in 0 until planeHeight) {
                val rowOffset = base + row * plane.rowStride
                val rowEnd = rowOffset + planeWidth
                require(rowEnd <= limit) { "YUV plane row exceeds the source buffer" }
                source.position(rowOffset)
                source.limit(rowEnd)
                output.put(source)
                source.limit(limit)
            }
        } else {
            for (row in 0 until planeHeight) {
                val rowOffset = base + row * plane.rowStride
                for (column in 0 until planeWidth) {
                    val index = rowOffset + column * plane.pixelStride
                    require(index < limit) { "YUV plane stride exceeds the source buffer" }
                    output.put(source.get(index))
                }
            }
        }
        output.flip()

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + slot)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexSubImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            0,
            0,
            planeWidth,
            planeHeight,
            GLES30.GL_RED,
            GLES30.GL_UNSIGNED_BYTE,
            output,
        )
    }

    private fun ensureBuffer(index: Int, minimumBytes: Int): ByteBuffer {
        val existing = planeBuffers[index]
        if (existing != null && existing.capacity() >= minimumBytes) {
            existing.clear()
            return existing
        }
        return ByteBuffer.allocateDirect(minimumBytes)
            .order(ByteOrder.nativeOrder())
            .also { planeBuffers[index] = it }
    }
}
