package com.example.syntaxcam

import android.graphics.*
import java.util.Calendar
import java.util.Locale
import kotlin.math.*
import kotlin.random.Random

enum class Effect(val label: String) {
    TIME("TIME"),         // blue clock-wedge sweep + live timestamp
    TILES("TILES"),       // scrambled checker mosaic
    CONFETTI("CONFETTI"), // scattered colour pixels
    STENCIL("STENCIL")    // hard-contrast mono + blue stars
}

object Effects {

    /** amount: 0f..1f (MIN..MAX). seed changes the random layout (shuffle button). */
    fun apply(src: Bitmap, effect: Effect, amount: Float, seed: Long): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        when (effect) {
            Effect.TIME -> time(src, c, amount)
            Effect.TILES -> tiles(src, c, amount, seed)
            Effect.CONFETTI -> confetti(c, out.width, out.height, amount, seed)
            Effect.STENCIL -> stencil(src, c, amount, seed)
        }
        return out
    }

    private fun time(src: Bitmap, c: Canvas, amount: Float) {
        val w = src.width.toFloat(); val h = src.height.toFloat()
        val cx = w / 2f; val cy = h / 2f
        val r = hypot(w, h)
        val cal = Calendar.getInstance()
        val secAngle = cal.get(Calendar.SECOND) / 60f * 360f - 90f
        val sweep = 30f + 320f * amount
        val wedge = Path().apply {
            moveTo(cx, cy)
            arcTo(RectF(cx - r, cy - r, cx + r, cy + r), secAngle, sweep)
            close()
        }
        val tint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply {
                setSaturation(0f)
                postConcat(ColorMatrix(floatArrayOf(
                    0.35f, 0f, 0f, 0f, 0f,
                    0f, 0.55f, 0f, 0f, 20f,
                    0f, 0f, 1.1f, 0f, 60f,
                    0f, 0f, 0f, 1f, 0f)))
            })
        }
        c.save(); c.clipPath(wedge); c.drawBitmap(src, 0f, 0f, tint); c.restore()
        val text = String.format(Locale.US, "%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = w * 0.06f; typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER; setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }
        c.drawText(text, cx, cy, p)
        c.drawCircle(cx, cy + h * 0.03f, w * 0.012f, Paint().apply { color = Color.WHITE })
    }

    private fun tiles(src: Bitmap, c: Canvas, amount: Float, seed: Long) {
        val n = (3 + amount * 15).toInt().coerceAtLeast(2)
        val rnd = Random(seed)
        val order = MutableList(n * n) { it }
        repeat((n * n * (0.2f + amount * 0.8f)).toInt()) {
            val a = rnd.nextInt(n * n); val b = rnd.nextInt(n * n)
            val t = order[a]; order[a] = order[b]; order[b] = t
        }
        val tw = src.width / n; val th = src.height / n
        val shade = Paint().apply { color = Color.argb(70, 0, 0, 0) }
        for (i in 0 until n * n) {
            val dx = (i % n) * tw; val dy = (i / n) * th
            val sx = (order[i] % n) * tw; val sy = (order[i] / n) * th
            c.drawBitmap(src, Rect(sx, sy, sx + tw, sy + th), Rect(dx, dy, dx + tw, dy + th), null)
            if (((i % n) + (i / n)) % 2 == 0) c.drawRect(dx.toFloat(), dy.toFloat(), (dx + tw).toFloat(), (dy + th).toFloat(), shade)
        }
    }

    private fun confetti(c: Canvas, w: Int, h: Int, amount: Float, seed: Long) {
        val rnd = Random(seed)
        val palette = intArrayOf(0xFFFF3DCB.toInt(), 0xFF3D5AFF.toInt(), 0xFF2EE66B.toInt(),
            0xFFFF8A2B.toInt(), 0xFF8B3DFF.toInt(), Color.BLACK, Color.WHITE)
        val p = Paint()
        repeat((30 + amount * 900).toInt()) {
            val s = w * (0.006f + rnd.nextFloat() * 0.016f)
            val x = rnd.nextFloat() * w; val y = rnd.nextFloat() * h
            p.color = palette[rnd.nextInt(palette.size)]
            c.drawRect(x, y, x + s, y + s * (0.6f + rnd.nextFloat()), p)
        }
    }

    private fun stencil(src: Bitmap, c: Canvas, amount: Float, seed: Long) {
        val s = 2f + amount * 14f
        val t = (-0.5f * s + 0.5f) * 255f
        val cm = ColorMatrix().apply {
            setSaturation(0f)
            postConcat(ColorMatrix(floatArrayOf(
                s, 0f, 0f, 0f, t,
                0f, s, 0f, 0f, t,
                0f, 0f, s, 0f, t,
                0f, 0f, 0f, 1f, 0f)))
        }
        c.drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
        val rnd = Random(seed)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2F7BFF.toInt() }
        repeat((6 + amount * 30).toInt()) {
            star(c, rnd.nextFloat() * src.width, rnd.nextFloat() * src.height,
                src.width * (0.012f + rnd.nextFloat() * 0.03f), p)
        }
    }

    private fun star(c: Canvas, cx: Float, cy: Float, r: Float, p: Paint) {
        val path = Path()
        for (i in 0 until 10) {
            val rad = if (i % 2 == 0) r else r * 0.42f
            val a = Math.PI / 5 * i - Math.PI / 2
            val x = cx + (rad * cos(a)).toFloat(); val y = cy + (rad * sin(a)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close(); c.drawPath(path, p)
    }
}
