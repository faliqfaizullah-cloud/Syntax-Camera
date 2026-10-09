package com.example.syntaxcam

import android.graphics.*
import java.util.Calendar
import java.util.Locale
import kotlin.math.*
import kotlin.random.Random

enum class Effect(val label: String) {
    TIME("TIME"),         // blue clock wedge + live timestamp
    TILES("TILES"),       // scrambled checker mosaic
    CONFETTI("CONFETTI"), // scattered colour pixels
    STENCIL("STENCIL"),   // hard-contrast mono + blue stars
    BRICK("BRICK"),       // chunky pixel blocks + torn checker border
    TERMINAL("TERMINAL"), // green 1-bit dithered screen
    DOTS("DOTS"),         // round-dot colour mosaic
    WARP("WARP"),         // swirl / bulge distortion
    TRACK("TRACK"),       // red tracking boxes + redaction blocks
    FRAME("FRAME"),       // black canvas, tilted crop, dashed geometry
    MATRIX("MATRIX"),     // green 1-bit dither + falling 0/1 code rain
    SPIDE("SPIDE"),       // red bugs: photo-filled cutouts, or red bugs over the photo
    CAMCORDER("CAMCORDER 2005"), // MiniDV: soft low-res video, cool lavender cast, scan lines, tape noise
    GAMEBOY("GAME BOY CAM")      // 4-tone green palette with ordered dither, chunky pixels
}

object Effects {

    /** Applies several effects in order (the order you tapped them). */
    fun applyAll(src: Bitmap, list: List<Effect>, amount: Float, seed: Long): Bitmap {
        var bmp = src
        list.forEachIndexed { i, e -> bmp = apply(bmp, e, amount, seed + i) }
        return bmp
    }

    /** amount: 0f..1f (MIN..MAX). seed changes the random layout (shuffle button). */
    fun apply(src: Bitmap, effect: Effect, amount: Float, seed: Long): Bitmap {
        when (effect) {
            Effect.TERMINAL -> return terminal(src, amount)
            Effect.DOTS -> return dots(src, amount)
            Effect.WARP -> return warp(src, amount, seed)
            Effect.MATRIX -> return matrix(src, amount, seed)
            Effect.SPIDE -> return spide(src, amount, seed)
            Effect.CAMCORDER -> return camcorder(src, amount, seed)
            Effect.GAMEBOY -> return gameboy(src, amount)
            else -> {}
        }
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        when (effect) {
            Effect.TIME -> time(src, c, amount)
            Effect.TILES -> tiles(src, c, amount, seed)
            Effect.CONFETTI -> confetti(c, out.width, out.height, amount, seed)
            Effect.STENCIL -> stencil(src, c, amount, seed)
            Effect.BRICK -> brick(src, c, amount)
            Effect.TRACK -> track(c, out.width, out.height, amount, seed)
            Effect.FRAME -> frame(src, c, amount, seed)
            else -> {}
        }
        return out
    }

    // ---------- original four ----------

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

    // ---------- new six ----------

    /** BRICK: big hard-edged pixel blocks + two-row green/white checker border. */
    private fun brick(src: Bitmap, c: Canvas, amount: Float) {
        val w = src.width; val h = src.height
        val block = ((6 + amount * 34) * (w / 540f)).toInt().coerceAtLeast(2)
        val small = Bitmap.createScaledBitmap(src, (w / block).coerceAtLeast(1), (h / block).coerceAtLeast(1), false)
        c.drawBitmap(small, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), Paint().apply { isFilterBitmap = false })
        val sz = w * 0.025f
        val g = Paint().apply { color = 0xFF2E9E4F.toInt() }
        val wh = Paint().apply { color = 0xFFEDEDED.toInt() }
        for (row in 0..1) {
            var i = 0; var x = 0f
            while (x < w) {
                val p = if ((i + row) % 2 == 0) g else wh
                c.drawRect(x, row * sz, x + sz, (row + 1) * sz, p)
                c.drawRect(x, h - (row + 1) * sz, x + sz, h - row * sz, p)
                x += sz; i++
            }
            i = 0; var y = 0f
            while (y < h) {
                val p = if ((i + row) % 2 == 0) wh else g
                c.drawRect(row * sz, y, (row + 1) * sz, y + sz, p)
                c.drawRect(w - (row + 1) * sz, y, w - row * sz, y + sz, p)
                y += sz; i++
            }
        }
    }

    /** TERMINAL: grayscale -> Bayer ordered dither -> bright green on black. */
    private fun terminal(src: Bitmap, amount: Float): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val bayer = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
        val bias = (amount - 0.5f) * 140f
        val ks = (w / 540f).toInt().coerceAtLeast(1)
        val on = 0xFF2BFF4F.toInt(); val off = 0xFF020A04.toInt()
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = px[y * w + x]
                val lum = 0.30f * ((p shr 16) and 255) + 0.59f * ((p shr 8) and 255) + 0.11f * (p and 255)
                val thr = (bayer[((y / ks) and 3) * 4 + ((x / ks) and 3)] + 0.5f) / 16f * 255f
                px[y * w + x] = if (lum + bias > thr) on else off
            }
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /** CAMCORDER 2005 (MiniDV, scan lines): soft low-res video, chroma bleed, cool lavender cast, scan lines, tape noise. */
    private fun camcorder(src: Bitmap, amount: Float, seed: Long): Bitmap {
        val w = src.width; val h = src.height
        val k = w / 540f
        // 1) MiniDV softness: shrink, then enlarge with filtering
        val f = 0.80f - 0.35f * amount
        val lw = (w * f).toInt().coerceAtLeast(16); val lh = (h * f).toInt().coerceAtLeast(16)
        val soft = Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(src, lw, lh, true), w, h, true)
        val px = IntArray(w * h); soft.getPixels(px, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        val bleed = max(1, (2f * k * (0.6f + amount)).roundToInt())      // colour smear sideways
        val ls = max(1, k.roundToInt())                                   // scan-line thickness
        val lineStrength = 0.14f + 0.26f * amount
        val rnd = java.util.Random(seed)
        val noiseAmp = 5f + 14f * amount
        val noise = IntArray(4096) { (rnd.nextGaussian() * noiseAmp).toInt() }
        val off = (((System.currentTimeMillis() / 33L) * 997L) % 4096L).toInt()   // tape noise moves every frame
        for (y in 0 until h) {
            val row = y * w
            val line = if (((y / ls) and 1) == 1) 1f - lineStrength else 1f
            for (x in 0 until w) {
                var r = ((px[row + min(w - 1, x + bleed)] shr 16) and 255).toFloat()   // red bleeds right
                var g = ((px[row + x] shr 8) and 255).toFloat()
                var b = (px[row + max(0, x - bleed)] and 255).toFloat()                // blue bleeds left
                val l0 = 0.30f * r + 0.59f * g + 0.11f * b
                r = l0 + (r - l0) * 1.25f; g = l0 + (g - l0) * 1.25f; b = l0 + (b - l0) * 1.25f   // saturate
                r = (r - 128f) * 1.18f + 128f; g = (g - 128f) * 1.18f + 128f; b = (b - 128f) * 1.18f + 128f
                val lum = 0.30f * r + 0.59f * g + 0.11f * b
                r = r * 0.88f + 10f * (lum / 255f)       // lavender highlights
                g *= 0.97f
                b = b * 1.20f + 12f                       // cool blue cast
                if (lum < 90f) { g += 7f; b += 6f }       // teal-ish shadows
                val n = noise[(x * 7 + y * 13 + off) and 4095]
                r = r * line + n; g = g * line + n; b = b * line + n
                out[row + x] = (0xFF shl 24) or
                    (r.coerceIn(0f, 255f).toInt() shl 16) or (g.coerceIn(0f, 255f).toInt() shl 8) or b.coerceIn(0f, 255f).toInt()
            }
        }
        val res = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        res.setPixels(out, 0, w, 0, 0, w, h)
        return res
    }

    /** GAME BOY CAM (4-tone green dither): low chunky resolution, 4 classic greens, Bayer ordered dither. */
    private fun gameboy(src: Bitmap, amount: Float): Bitmap {
        val w = src.width; val h = src.height
        val cols = min(w, (176 - 80 * amount).toInt())            // slider: finer -> chunkier pixels
        val rows = max(1, (h.toLong() * cols / w).toInt())
        val small = Bitmap.createScaledBitmap(src, cols, rows, true)
        val px = IntArray(cols * rows); small.getPixels(px, 0, cols, 0, 0, cols, rows)
        val pal = intArrayOf(0xFF0F380F.toInt(), 0xFF306230.toInt(), 0xFF8BAC0F.toInt(), 0xFF9BBC0F.toInt())
        val bayer = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
        for (y in 0 until rows) for (x in 0 until cols) {
            val p = px[y * cols + x]
            var lum = 0.30f * ((p shr 16) and 255) + 0.59f * ((p shr 8) and 255) + 0.11f * (p and 255)
            lum = ((lum - 128f) * 1.3f + 128f).coerceIn(0f, 255f)    // punchy contrast like the real camera
            val t = (bayer[(y and 3) * 4 + (x and 3)] + 0.5f) / 16f - 0.5f
            px[y * cols + x] = pal[(lum / 255f * 3f + t).roundToInt().coerceIn(0, 3)]
        }
        val pix = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888)
        pix.setPixels(px, 0, cols, 0, 0, cols, rows)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(pix, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), Paint().apply { isFilterBitmap = false })
        return out
    }

    /** SPIDE: red bugs. Even seed = red bugs over the photo; odd seed (shuffle) = red field, photo only inside the bugs. */
    private fun spide(src: Bitmap, amount: Float, seed: Long): Bitmap {
        val w = src.width; val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val rnd = Random(seed)
        val red = 0xFFFF3B30.toInt()
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = red; style = Paint.Style.FILL }
        val leg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = red; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        val n = (6 + amount * 24).toInt()
        fun scatter() {
            repeat(n) {
                val s = w * (0.07f + rnd.nextFloat() * 0.07f)
                bug(c, rnd.nextFloat() * w, rnd.nextFloat() * h, s, rnd.nextFloat() * 360f, body, leg)
            }
        }
        if (seed % 2L == 0L) {
            c.drawBitmap(src, 0f, 0f, null)
            scatter()
        } else {
            c.drawColor(red)
            val layer = c.saveLayer(0f, 0f, w.toFloat(), h.toFloat(), null)
            scatter()
            c.drawBitmap(src, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN) })
            c.restoreToCount(layer)
        }
        return out
    }

    private fun bug(c: Canvas, cx: Float, cy: Float, s: Float, rot: Float, body: Paint, leg: Paint) {
        c.save(); c.translate(cx, cy); c.rotate(rot)
        leg.strokeWidth = s * 0.10f
        val p = Path()
        for (side in intArrayOf(-1, 1)) for (i in 0..2) {
            val y0 = (i - 1) * s * 0.20f
            p.reset()
            p.moveTo(side * s * 0.10f, y0)
            p.lineTo(side * s * 0.36f, y0 - s * 0.14f + i * s * 0.05f)
            p.lineTo(side * s * 0.52f, y0 + s * 0.10f + i * s * 0.07f)
            c.drawPath(p, leg)
        }
        c.drawLine(-s * 0.05f, -s * 0.36f, -s * 0.18f, -s * 0.52f, leg)
        c.drawLine(s * 0.05f, -s * 0.36f, s * 0.18f, -s * 0.52f, leg)
        c.drawOval(RectF(-s * 0.22f, s * 0.04f, s * 0.22f, s * 0.46f), body)
        c.drawOval(RectF(-s * 0.15f, -s * 0.20f, s * 0.15f, s * 0.12f), body)
        c.drawOval(RectF(-s * 0.12f, -s * 0.38f, s * 0.12f, -s * 0.16f), body)
        c.restore()
    }

    /** MATRIX: TERMINAL dither + animated falling 0/1 columns. */
    private fun matrix(src: Bitmap, amount: Float, seed: Long): Bitmap {
        val out = terminal(src, amount)
        val c = Canvas(out)
        val w = out.width; val h = out.height
        val cell = w / 34f
        val cols = (w / cell).toInt().coerceAtLeast(1)
        val rows = (h / cell).toInt().coerceAtLeast(1)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE; textSize = cell * 1.1f; textAlign = Paint.Align.CENTER
        }
        val rnd = Random(seed)
        val tick = (System.currentTimeMillis() / 90).toInt()
        repeat((4 + amount * cols * 0.6f).toInt()) {
            val col = rnd.nextInt(cols)
            val len = 6 + rnd.nextInt(18)
            val head = (rnd.nextInt(rows) + tick) % rows
            for (k in 0 until len) {
                val row = head - k
                if (row < 0) continue
                paint.color = if (k == 0) Color.argb(255, 215, 255, 225)
                              else Color.argb((255 * (1f - k / len.toFloat())).toInt(), 0, 255, 65)
                c.drawText(if (rnd.nextBoolean()) "1" else "0", (col + 0.5f) * cell, (row + 1) * cell, paint)
            }
        }
        return out
    }

    /** DOTS: one coloured circle per cell on a dark background. */
    private fun dots(src: Bitmap, amount: Float): Bitmap {
        val cell = ((8 + amount * 32) * (src.width / 540f)).toInt().coerceAtLeast(2)
        val cols = (src.width / cell).coerceAtLeast(1)
        val rows = (src.height / cell).coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, cols, rows, true)
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.rgb(8, 8, 12))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val cw = src.width / cols.toFloat(); val ch = src.height / rows.toFloat()
        for (y in 0 until rows) for (x in 0 until cols) {
            p.color = small.getPixel(x, y)
            c.drawCircle((x + 0.5f) * cw, (y + 0.5f) * ch, min(cw, ch) * 0.47f, p)
        }
        return out
    }

    /** WARP: swirl + bulge around a random point, drawn with a bitmap mesh. */
    private fun warp(src: Bitmap, amount: Float, seed: Long): Bitmap {
        val n = 28
        val w = src.width.toFloat(); val h = src.height.toFloat()
        val k = 0.3f + amount * 1.4f
        val rnd = Random(seed)
        val cxN = 0.35f + rnd.nextFloat() * 0.3f
        val cyN = 0.35f + rnd.nextFloat() * 0.3f
        val verts = FloatArray((n + 1) * (n + 1) * 2)
        var i = 0
        for (j in 0..n) for (x in 0..n) {
            val dx = x / n.toFloat() - cxN
            val dy = j / n.toFloat() - cyN
            val r = hypot(dx, dy)
            val fall = exp(-r * r * 8f)
            val ang = k * 2.2f * fall
            val s = 1f + k * 0.9f * fall
            val cs = cos(ang); val sn = sin(ang)
            verts[i++] = (cxN + (dx * cs - dy * sn) * s) * w
            verts[i++] = (cyN + (dx * sn + dy * cs) * s) * h
        }
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.BLACK)
        c.drawBitmapMesh(src, n, n, verts, 0, null, 0, null)
        return out
    }

    /** TRACK: red outlined boxes with corner tags; some are blacked out. */
    private fun track(c: Canvas, w: Int, h: Int, amount: Float, seed: Long) {
        val rnd = Random(seed)
        val red = 0xFFFF2B2B.toInt()
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = w * 0.004f + 1f; color = red }
        val fill = Paint().apply { color = Color.argb(235, 8, 8, 8) }
        val tag = Paint().apply { color = red }
        repeat((3 + amount * 14).toInt()) {
            val bw = w * (0.10f + rnd.nextFloat() * 0.25f)
            val bh = h * (0.04f + rnd.nextFloat() * 0.14f)
            val x = rnd.nextFloat() * (w - bw); val y = rnd.nextFloat() * (h - bh)
            if (rnd.nextFloat() < 0.4f) c.drawRect(x, y, x + bw, y + bh, fill)
            c.drawRect(x, y, x + bw, y + bh, stroke)
            val t = w * 0.02f
            c.drawRect(x + 4f, y + 4f, x + 4f + t, y + 4f + t, tag)
        }
    }

    /** FRAME: black canvas, tilted crop of the photo, white dashed circles + lines. */
    private fun frame(src: Bitmap, c: Canvas, amount: Float, seed: Long) {
        val rnd = Random(seed)
        val w = src.width.toFloat(); val h = src.height.toFloat()
        c.drawColor(Color.BLACK)
        val ang = (rnd.nextFloat() - 0.5f) * 16f
        val s = 0.45f + amount * 0.5f
        val cw = w * s; val ch = h * s * 0.9f
        c.save()
        c.rotate(ang, w / 2, h / 2)
        c.clipRect(RectF(w / 2 - cw / 2, h / 2 - ch / 2, w / 2 + cw / 2, h / 2 + ch / 2))
        c.rotate(-ang, w / 2, h / 2)
        c.drawBitmap(src, 0f, 0f, null)
        c.restore()
        val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = w * 0.003f + 1f
            pathEffect = DashPathEffect(floatArrayOf(12f * w / 540f, 10f * w / 540f), 0f)
        }
        repeat((2 + amount * 4).toInt()) {
            val r = w * (0.08f + rnd.nextFloat() * 0.22f)
            c.drawCircle(w * (0.25f + rnd.nextFloat() * 0.5f), h * (0.25f + rnd.nextFloat() * 0.5f), r, dash)
        }
        repeat(3) {
            c.drawLine(rnd.nextFloat() * w, rnd.nextFloat() * h * 0.5f, rnd.nextFloat() * w, h * (0.5f + rnd.nextFloat() * 0.5f), dash)
        }
    }
}
