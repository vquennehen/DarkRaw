// File: app/src/main/java/com/darkroom/raw/gl/RawGlRenderer.kt
package com.darkroom.raw.gl

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import com.darkroom.raw.model.DevelopParams
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class RawGlRenderer : GLSurfaceView.Renderer {

    private var shaderProgram = 0
    private var rawTextureId = 0
    private var curveLutTextureId = 0
    private var imageWidth = 1
    private var imageHeight = 1
    private var viewportWidth = 1
    private var viewportHeight = 1

    private val pendingParams = AtomicReference(DevelopParams())
    private var pendingRawBuffer: ByteBuffer? = null
    private var pendingRawWidth = 0
    private var pendingRawHeight = 0

    private val quadVertices: FloatBuffer = ByteBuffer
        .allocateDirect(16 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(
                floatArrayOf(
                    // X,    Y,   U,   V
                    -1f, -1f, 0f, 1f,
                     1f, -1f, 1f, 1f,
                    -1f,  1f, 0f, 0f,
                     1f,  1f, 1f, 0f
                )
            )
            position(0)
        }

    fun updateParams(params: DevelopParams) {
        pendingParams.set(params)
    }

    @Synchronized
    fun setRawImageBuffer(buffer: ByteBuffer, width: Int, height: Int) {
        pendingRawBuffer = buffer.apply { position(0) }
        pendingRawWidth = width
        pendingRawHeight = height
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f) // True #000000 OLED Darkroom background
        shaderProgram = buildShaderProgram(VERTEX_SHADER_ES30, FRAGMENT_SHADER_ES30)

        val texIds = IntArray(2)
        GLES30.glGenTextures(2, texIds, 0)
        rawTextureId = texIds[0]
        curveLutTextureId = texIds[1]

        // Configure 16-bit half-float RAW sensor texture
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rawTextureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        // Configure 256x1 16-bit float Luminance Tone Curve LUT texture
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, curveLutTextureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        synchronized(this) {
            pendingRawBuffer?.let { buf ->
                imageWidth = pendingRawWidth
                imageHeight = pendingRawHeight
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rawTextureId)
                GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 2)
                GLES30.glTexImage2D(
                    GLES30.GL_TEXTURE_2D,
                    0,
                    GLES30.GL_RGBA16F,
                    imageWidth,
                    imageHeight,
                    0,
                    GLES30.GL_RGBA,
                    GLES30.GL_HALF_FLOAT,
                    buf
                )
                pendingRawBuffer = null
            }
        }

        val params = pendingParams.get()
        uploadToneCurveLut(params)
        drawPipelineQuad(params, isOffscreenExport = false)
    }

    private fun uploadToneCurveLut(params: DevelopParams) {
        val lutFloats = DevelopParams.buildMonotoneCubicLut(params.toneCurve, 256)
        val lutBuffer = ByteBuffer.allocateDirect(256 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(lutFloats)
                position(0)
            }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, curveLutTextureId)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_R16F,
            256,
            1,
            0,
            GLES30.GL_RED,
            GLES30.GL_FLOAT,
            lutBuffer
        )
    }

    fun drawPipelineQuad(params: DevelopParams, isOffscreenExport: Boolean) {
        GLES30.glUseProgram(shaderProgram)

        // Compute aspect-ratio preserving scale for screen preview, or 1:1 full frame for FBO export
        var scaleX = 1f
        var scaleY = 1f
        if (!isOffscreenExport && viewportWidth > 0 && viewportHeight > 0 && imageWidth > 0 && imageHeight > 0) {
            val imgAspect = imageWidth.toFloat() / imageHeight.toFloat()
            val viewAspect = viewportWidth.toFloat() / viewportHeight.toFloat()
            if (imgAspect > viewAspect) {
                scaleY = viewAspect / imgAspect
            } else {
                scaleX = imgAspect / viewAspect
            }
        }

        GLES30.glUniform2f(GLES30.glGetUniformLocation(shaderProgram, "uScale"), scaleX, scaleY)
        GLES30.glUniform2f(
            GLES30.glGetUniformLocation(shaderProgram, "uTexelSize"),
            1.0f / imageWidth.toFloat(),
            1.0f / imageHeight.toFloat()
        )
        GLES30.glUniform1f(GLES30.glGetUniformLocation(shaderProgram, "uExposureEv"), params.exposureEv)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(shaderProgram, "uContrast"), params.contrast / 100f)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(shaderProgram, "uHighlights"), params.highlights / 100f)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(shaderProgram, "uShadows"), params.shadows / 100f)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(shaderProgram, "uKelvin"), params.temperatureKelvin)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(shaderProgram, "uTint"), params.tint / 100f)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(shaderProgram, "uSaturation"), params.saturation / 100f)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(shaderProgram, "uVibrance"), params.vibrance / 100f)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(shaderProgram, "uSharpening"), params.sharpening / 100f)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rawTextureId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(shaderProgram, "uRawTex"), 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, curveLutTextureId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(shaderProgram, "uCurveLut"), 1)

        quadVertices.position(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, quadVertices)
        GLES30.glEnableVertexAttribArray(0)

        quadVertices.position(2)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, quadVertices)
        GLES30.glEnableVertexAttribArray(1)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun buildShaderProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vertexSrc)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSrc)
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vs)
        GLES30.glAttachShader(prog, fs)
        GLES30.glLinkProgram(prog)
        return prog
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        return shader
    }

    companion object {
        private const val VERTEX_SHADER_ES30 = """
            #version 300 es
            layout(location = 0) in vec2 aPosition;
            layout(location = 1) in vec2 aTexCoord;
            uniform vec2 uScale;
            out vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition * uScale, 0.0, 1.0);
                vTexCoord = aTexCoord;
            }
        """

        private const val FRAGMENT_SHADER_ES30 = """
            #version 300 es
            precision highp float;
            precision highp sampler2D;

            in vec2 vTexCoord;
            out vec4 fragColor;

            uniform sampler2D uRawTex;
            uniform sampler2D uCurveLut;
            uniform vec2 uTexelSize;

            uniform float uExposureEv;
            uniform float uContrast;
            uniform float uHighlights;
            uniform float uShadows;
            uniform float uKelvin;
            uniform float uTint;
            uniform float uSaturation;
            uniform float uVibrance;
            uniform float uSharpening;

            const vec3 LUMA_WEIGHTS = vec3(0.2126, 0.7152, 0.0722);

            // Planckian locus approximation for Kelvin White Balance + Green/Magenta Tint
            vec3 computeWhiteBalanceMultipliers(float kelvin, float tint) {
                float temp = clamp(kelvin, 2000.0, 10000.0) / 100.0;
                float r, g, b;
                if (temp <= 66.0) {
                    r = 1.0;
                    g = clamp(0.39008157 * log(temp) - 0.63184144, 0.1, 1.0);
                } else {
                    r = clamp(1.29293618 * pow(temp - 60.0, -0.13320476), 0.1, 1.0);
                    g = clamp(1.12989086 * pow(temp - 60.0, -0.07551484), 0.1, 1.0);
                }
                if (temp >= 66.0) {
                    b = 1.0;
                } else if (temp <= 19.0) {
                    b = 0.05;
                } else {
                    b = clamp(0.54320678 * log(temp - 10.0) - 1.19625408, 0.05, 1.0);
                }
                // Reference neutral 5500K normalization
                vec3 ref5500 = vec3(1.0, 0.938, 0.912);
                vec3 illuminant = vec3(r, g, b) / ref5500;
                // Inverse illuminant shifts warm/cool + Green/Magenta tint
                vec3 gain = 1.0 / max(illuminant, vec3(0.1));
                gain.g *= (1.0 - tint * 0.35);
                gain.rb *= (1.0 + tint * 0.18);
                return gain / dot(gain, LUMA_WEIGHTS);
            }

            // Linear to sRGB transfer function
            vec3 linearToSrgb(vec3 lin) {
                vec3 lo = lin * 12.92;
                vec3 hi = 1.055 * pow(max(lin, vec3(0.0)), vec3(1.0 / 2.4)) - 0.055;
                return mix(lo, hi, step(vec3(0.0031308), lin));
            }

            void main() {
                // 1. Sample 16-bit Half-Float Linear RAW & 4-neighbor Laplacian for Luminance Unsharp Mask
                vec3 center = texture(uRawTex, vTexCoord).rgb;
                if (uSharpening > 0.001) {
                    vec3 n = texture(uRawTex, vTexCoord + vec2(0.0, -uTexelSize.y)).rgb;
                    vec3 s = texture(uRawTex, vTexCoord + vec2(0.0,  uTexelSize.y)).rgb;
                    vec3 e = texture(uRawTex, vTexCoord + vec2( uTexelSize.x, 0.0)).rgb;
                    vec3 w = texture(uRawTex, vTexCoord + vec2(-uTexelSize.x, 0.0)).rgb;
                    float lumaCenter = dot(center, LUMA_WEIGHTS);
                    float lumaBlur = dot((n + s + e + w) * 0.25, LUMA_WEIGHTS);
                    float highPass = lumaCenter - lumaBlur;
                    center = max(vec3(0.0), center + highPass * (uSharpening * 1.8));
                }

                // 2. White Balance (Kelvin + Tint) in linear sensor space
                vec3 rgb = center * computeWhiteBalanceMultipliers(uKelvin, uTint);

                // 3. Exposure in EV stops (-4.0 to +4.0 EV -> 2^EV)
                rgb *= exp2(uExposureEv);

                // 4. Highlight & Shadow Recovery using smooth luminance masks in perceptual space
                vec3 percept = linearToSrgb(clamp(rgb, 0.0, 4.0));
                float luma = clamp(dot(percept, LUMA_WEIGHTS), 0.0, 1.0);

                // Shadows lift/darken low luminance regions without washing out midtones
                float shadowMask = pow(1.0 - luma, 2.2);
                // Highlights recover compressed upper stops
                float highlightMask = pow(luma, 2.2);

                float recoveredLuma = clamp(
                    luma + (uShadows * 0.38 * shadowMask) + (uHighlights * 0.38 * highlightMask),
                    0.0, 1.0
                );
                percept *= (recoveredLuma / max(luma, 1e-4));

                // 5. Contrast around 0.5 perceptual midpoint
                float contrastFactor = 1.0 + uContrast * 0.75;
                percept = clamp((percept - 0.5) * contrastFactor + 0.5, 0.0, 1.0);

                // 6. Interactive Bezier Tone Curve via 256x1 1D LUT texture
                float preCurveLuma = clamp(dot(percept, LUMA_WEIGHTS), 0.0, 1.0);
                float curvedLuma = texture(uCurveLut, vec2(preCurveLuma, 0.5)).r;
                percept = clamp(percept * (curvedLuma / max(preCurveLuma, 1e-4)), 0.0, 1.0);

                // 7. Skin-Tone Aware Vibrance + Global Saturation
                float maxCh = max(percept.r, max(percept.g, percept.b));
                float minCh = min(percept.r, min(percept.g, percept.b));
                float currentSat = maxCh - minCh;
                // Protect warm skin-tone hues (R > G > B) from over-saturation clipping
                float skinProtection = 1.0 - 0.45 * smoothstep(0.0, 0.25, percept.r - percept.g)
                                                  * smoothstep(0.0, 0.25, percept.g - percept.b);
                float vibranceBoost = uVibrance * (1.0 - currentSat) * skinProtection;
                float totalSatScale = max(0.0, 1.0 + uSaturation + vibranceBoost);

                float finalLuma = dot(percept, LUMA_WEIGHTS);
                vec3 finalRgb = clamp(mix(vec3(finalLuma), percept, totalSatScale), 0.0, 1.0);

                fragColor = vec4(finalRgb, 1.0);
            }
        """
    }
}
