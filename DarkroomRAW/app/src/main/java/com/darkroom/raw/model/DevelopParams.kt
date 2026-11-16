// File: app/src/main/java/com/darkroom/raw/model/DevelopParams.kt
package com.darkroom.raw.model

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

@Serializable
data class CurvePoint(
    val x: Float, // Normalized input luminance [0.0 .. 1.0]
    val y: Float  // Normalized output luminance [0.0 .. 1.0]
)

@Serializable
data class DevelopParams(
    val exposureEv: Float = 0.0f,        // -4.0 to +4.0 EV
    val contrast: Float = 0.0f,          // -100 to +100
    val highlights: Float = 0.0f,        // -100 to +100
    val shadows: Float = 0.0f,           // -100 to +100
    val temperatureKelvin: Float = 5500f,// 2000K to 10000K
    val tint: Float = 0.0f,              // -100 (Green) to +100 (Magenta)
    val saturation: Float = 0.0f,        // -100 to +100
    val vibrance: Float = 0.0f,          // -100 to +100 (skin-tone aware smart saturation)
    val sharpening: Float = 15.0f,       // 0 to 100 (Luminance Unsharp Mask)
    val toneCurve: List<CurvePoint> = DEFAULT_CURVE
) {
    companion object {
        // 5-anchor curve: Black Point, Shadows, Midtones, Highlights, White Point
        val DEFAULT_CURVE = listOf(
            CurvePoint(0.00f, 0.00f),
            CurvePoint(0.25f, 0.25f),
            CurvePoint(0.50f, 0.50f),
            CurvePoint(0.75f, 0.75f),
            CurvePoint(1.00f, 1.00f)
        )

        // Starter Analog Preset 1: Portra 400 Warm (Creamy highlights, lifted shadows, warm skin tones)
        val PORTRA_WARM = DevelopParams(
            exposureEv = 0.25f,
            contrast = -8.0f,
            highlights = -35.0f,
            shadows = 28.0f,
            temperatureKelvin = 6150f,
            tint = 6.0f,
            saturation = -5.0f,
            vibrance = 24.0f,
            sharpening = 18.0f,
            toneCurve = listOf(
                CurvePoint(0.00f, 0.04f),
                CurvePoint(0.25f, 0.27f),
                CurvePoint(0.50f, 0.53f),
                CurvePoint(0.75f, 0.78f),
                CurvePoint(1.00f, 0.96f)
            )
        )

        // Starter Analog Preset 2: Kodak Tri-X 400 B&W (Punchy silver-gelatin S-curve, crisp grain/acutance)
        val TRI_X_BW = DevelopParams(
            exposureEv = 0.10f,
            contrast = 32.0f,
            highlights = -15.0f,
            shadows = -12.0f,
            temperatureKelvin = 5500f,
            tint = 0.0f,
            saturation = -100.0f,
            vibrance = 0.0f,
            sharpening = 42.0f,
            toneCurve = listOf(
                CurvePoint(0.00f, 0.02f),
                CurvePoint(0.25f, 0.19f),
                CurvePoint(0.50f, 0.51f),
                CurvePoint(0.75f, 0.82f),
                CurvePoint(1.00f, 0.98f)
            )
        )

        // Starter Analog Preset 3: CineStill 800T Cool (Tungsten balanced, halation-style highlight roll-off)
        val CINESTILL_COOL = DevelopParams(
            exposureEv = 0.0f,
            contrast = 14.0f,
            highlights = -45.0f,
            shadows = 18.0f,
            temperatureKelvin = 4150f,
            tint = -12.0f,
            saturation = 8.0f,
            vibrance = 18.0f,
            sharpening = 22.0f,
            toneCurve = listOf(
                CurvePoint(0.00f, 0.03f),
                CurvePoint(0.25f, 0.23f),
                CurvePoint(0.50f, 0.50f),
                CurvePoint(0.75f, 0.74f),
                CurvePoint(1.00f, 0.94f)
            )
        )

        /**
         * Evaluates a 256-entry 1D LUT using Fritsch-Carlson Monotone Cubic Hermite Interpolation
         * so the Tone Curve never overshoots or causes luminance inversions on the GPU.
         */
        fun buildMonotoneCubicLut(points: List<CurvePoint>, size: Int = 256): FloatArray {
            val sorted = points.sortedBy { it.x }
            val n = sorted.size
            val lut = FloatArray(size)
            if (n < 2) {
                for (i in 0 until size) lut[i] = i.toFloat() / (size - 1)
                return lut
            }

            val dx = FloatArray(n - 1)
            val dy = FloatArray(n - 1)
            val m = FloatArray(n - 1)
            for (i in 0 until n - 1) {
                dx[i] = max(1e-5f, sorted[i + 1].x - sorted[i].x)
                dy[i] = sorted[i + 1].y - sorted[i].y
                m[i] = dy[i] / dx[i]
            }

            val tangents = FloatArray(n)
            tangents[0] = m[0]
            tangents[n - 1] = m[n - 2]
            for (i in 1 until n - 1) {
                if (m[i - 1] * m[i] <= 0f) {
                    tangents[i] = 0f
                } else {
                    tangents[i] = (m[i - 1] + m[i]) * 0.5f
                }
            }

            // Enforce monotonicity constraints
            for (i in 0 until n - 1) {
                if (m[i] == 0f) {
                    tangents[i] = 0f
                    tangents[i + 1] = 0f
                } else {
                    val alpha = tangents[i] / m[i]
                    val beta = tangents[i + 1] / m[i]
                    val hypot = sqrt(alpha * alpha + beta * beta)
                    if (hypot > 3f) {
                        val tau = 3f / hypot
                        tangents[i] = tau * alpha * m[i]
                        tangents[i + 1] = tau * beta * m[i]
                    }
                }
            }

            for (step in 0 until size) {
                val x = step.toFloat() / (size - 1)
                val clampedY = when {
                    x <= sorted.first().x -> sorted.first().y
                    x >= sorted.last().x -> sorted.last().y
                    else -> {
                        var seg = 0
                        for (i in 0 until n - 1) {
                            if (x >= sorted[i].x && x <= sorted[i + 1].x) {
                                seg = i
                                break
                            }
                        }
                        val h = dx[seg]
                        val t = (x - sorted[seg].x) / h
                        val t2 = t * t
                        val t3 = t2 * t
                        val h00 = 2f * t3 - 3f * t2 + 1f
                        val h10 = t3 - 2f * t2 + t
                        val h01 = -2f * t3 + 3f * t2
                        val h11 = t3 - t2
                        h00 * sorted[seg].y + h10 * h * tangents[seg] +
                            h01 * sorted[seg + 1].y + h11 * h * tangents[seg + 1]
                    }
                }
                lut[step] = min(1.0f, max(0.0f, clampedY))
            }
            return lut
        }
    }
}
