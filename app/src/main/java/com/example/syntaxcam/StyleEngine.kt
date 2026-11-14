package com.example.syntaxcam

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A "style" = NUANCE (colour grade) + MATERIAL (surface texture), each with its own strength. */
data class StyleSpec(
    val nuance: Int = 0,
    val nuanceAmt: Float = 0.5f,
    val material: Int = 0,
    val materialAmt: Float = 0.4f
) {
    val label: String
        get() {
            val parts = ArrayList<String>()
            if (nuance != 0) parts.add(Styles.nuanceNames[nuance])
            if (material != 0) parts.add(Styles.materialNames[material])
            return if (parts.isEmpty()) "STANDARD STYLE" else parts.joinToString(" \u00B7 ") + " STYLE"
        }
}

object Styles {
    val nuanceNames = listOf("STANDARD", "COOL", "ROSE", "MOSS", "MONO", "AMBER")
    val nuanceColors = listOf(
        Color(0xFFB8734F), Color(0xFF6B67C9), Color(0xFFB0539A), Color(0xFF4E8F66), Color(0xFF6E6E74), Color(0xFFD59A3A)
    )
    val materialNames = listOf("STANDARD", "GRID", "LINES", "DITHER", "GRAIN", "CLEAN")

    private class Grade(val gr: Float, val gg: Float, val gb: Float, val lr: Float, val lg: Float, val lb: Float, val sat: Float)

    private val grades = listOf(
        Grade(1f, 1f, 1f, 0f, 0f, 0f, 1f),               // STANDARD: no colour change
        Grade(0.88f, 0.98f, 1.12f, -4f, 2f, 12f, 1.05f), // COOL
        Grade(1.08f, 0.92f, 1.02f, 12f, -2f, 8f, 1.10f), // ROSE
        Grade(0.92f, 1.06f, 0.90f, -2f, 8f, -2f, 1.05f), // MOSS
        Grade(1f, 1f, 1f, 0f, 0f, 0f, 0f),               // MONO
        Grade(1.10f, 1.00f, 0.82f, 10f, 4f, -6f, 1.08f)  // AMBER
    )

    /** True when the style would not change the picture at all. */
    fun isNoop(s: StyleSpec) = s.nuance == 0 && s.material == 5

    private val bayer = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
    private val noise: FloatArray by lazy {
        val r = java.util.Random(11)
        FloatArray(2048) { r.nextGaussian().toFloat() }
    }

    private fun dq(v: Float, thr: Float, ma: Float): Float {
        val lv = 5f
        val q = floor((v / 255f + thr * ma * 0.6f) * (lv - 1f) + 0.5f) / (lv - 1f)
        val out = q.coerceIn(0f, 1f) * 255f
        return v + (out - v) * ma
    }

    fun apply(src: Bitmap, s: StyleSpec): Bitmap {
        if (isNoop(s)) return src
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val k = w / 540f

        val g = grades[s.nuance.coerceIn(0, grades.size - 1)]
        val na = (s.nuanceAmt * 1.5f).coerceIn(0f, 1.5f)
        val gainR = 1f + (g.gr - 1f) * na; val gainG = 1f + (g.gg - 1f) * na; val gainB = 1f + (g.gb - 1f) * na
        val liftR = g.lr * na; val liftG = g.lg * na; val liftB = g.lb * na
        val satF = (1f + (g.sat - 1f) * na).coerceAtLeast(0f)
        val colorOn = s.nuance != 0

        val ma = s.materialAmt.coerceIn(0f, 1f)
        val cell = max(3, (4.2f * k).roundToInt())
        val cell2 = max(4, (8f * k).roundToInt())
        val per = max(3, (4f * k).roundToInt())
        val bs = max(1, k.roundToInt())
        val mat = s.material

        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val p = px[row + x]
                var r = ((p shr 16) and 255).toFloat()
                var gg = ((p shr 8) and 255).toFloat()
                var b = (p and 255).toFloat()

                if (colorOn) {
                    val l0 = 0.30f * r + 0.59f * gg + 0.11f * b
                    r = l0 + (r - l0) * satF; gg = l0 + (gg - l0) * satF; b = l0 + (b - l0) * satF
                    r = r * gainR + liftR; gg = gg * gainG + liftG; b = b * gainB + liftB
                }

                if (mat != 5 && ma > 0f) {
                    val lum = ((0.30f * r + 0.59f * gg + 0.11f * b) / 255f).coerceIn(0f, 1f)
                    when (mat) {
                        0 -> { // halftone dot screen: bigger dots in the shadows
                            val fx = ((x % cell) + 0.5f) / cell - 0.5f
                            val fy = ((y % cell) + 0.5f) / cell - 0.5f
                            val d = sqrt(fx * fx + fy * fy)
                            val rad = 0.50f * sqrt(1f - lum * 0.8f)
                            val t = ((rad + 0.08f - d) / 0.16f).coerceIn(0f, 1f)
                            val f = 1f - ma * 0.55f * t + ma * 0.10f * (1f - t)
                            r *= f; gg *= f; b *= f
                        }
                        1 -> { // light dots on a grid
                            val fx = ((x % cell2) + 0.5f) / cell2 - 0.5f
                            val fy = ((y % cell2) + 0.5f) / cell2 - 0.5f
                            val d = sqrt(fx * fx + fy * fy)
                            val t = ((0.26f - d) / 0.12f).coerceIn(0f, 1f)
                            val add = ma * 80f * t
                            r += add; gg += add; b += add
                        }
                        2 -> { // engraved lines, thicker in the shadows
                            val ph = ((y % per) + 0.5f) / per
                            val th = 0.22f + 0.5f * (1f - lum)
                            if (ph < th) { val f = 1f - ma * 0.6f; r *= f; gg *= f; b *= f }
                        }
                        3 -> { // ordered dither, posterised
                            val thr = (bayer[((y / bs) and 3) * 4 + ((x / bs) and 3)] + 0.5f) / 16f - 0.5f
                            r = dq(r, thr, ma); gg = dq(gg, thr, ma); b = dq(b, thr, ma)
                        }
                        4 -> { // film grain
                            val n = noise[(x * 7 + y * 13) and 2047] * ma * 28f
                            r += n; gg += n; b += n
                        }
                    }
                }

                px[row + x] = (255 shl 24) or
                    (r.coerceIn(0f, 255f).toInt() shl 16) or (gg.coerceIn(0f, 255f).toInt() shl 8) or b.coerceIn(0f, 255f).toInt()
            }
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }
}
