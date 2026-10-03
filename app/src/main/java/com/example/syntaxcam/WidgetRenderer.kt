package com.example.syntaxcam

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.*
import android.widget.RemoteViews
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

const val THEME_GLASS = 0      // iOS 26-style frosted glass
const val THEME_LIGHT = 1      // solid white
const val THEME_DARK = 2       // solid dark
const val THEME_GRADIENT = 3   // gradient + progressive blur

data class WidgetConfig(
    val theme: Int = THEME_GLASS,
    val colorA: Int = 0xFFFFFFFF.toInt(),   // glass tint / gradient start
    val colorB: Int = 0xFF9B5CFF.toInt(),   // gradient end
    val roundness: Float = 0.17f,           // corner radius as a share of the short side (0.17 = reference tile)
    val showLabel: Boolean = true,
    val shuffle: Boolean = false
)

object WidgetStore {
    private fun p(ctx: Context) = ctx.getSharedPreferences("syntaxcam_widgets", Context.MODE_PRIVATE)
    fun load(ctx: Context, id: Int): WidgetConfig {
        val s = p(ctx); val d = WidgetConfig()
        return WidgetConfig(
            s.getInt("$id.theme", d.theme), s.getInt("$id.a", d.colorA), s.getInt("$id.b", d.colorB),
            s.getFloat("$id.round", d.roundness), s.getBoolean("$id.label", d.showLabel), s.getBoolean("$id.shuffle", d.shuffle)
        )
    }
    fun save(ctx: Context, id: Int, c: WidgetConfig) {
        p(ctx).edit().putInt("$id.theme", c.theme).putInt("$id.a", c.colorA).putInt("$id.b", c.colorB)
            .putFloat("$id.round", c.roundness).putBoolean("$id.label", c.showLabel).putBoolean("$id.shuffle", c.shuffle).apply()
    }
    fun delete(ctx: Context, id: Int) {
        val e = p(ctx).edit()
        listOf("theme", "a", "b", "round", "label", "shuffle").forEach { e.remove("$id.$it") }
        e.apply()
    }
}

object WidgetRenderer {
    private const val AA = Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG

    class Photo(val bmp: Bitmap?, val dateText: String, val count: Int)

    fun pickPhoto(ctx: Context, shuffle: Boolean, maxDim: Int = 900): Photo {
        val files = Shots.list(ctx)
        if (files.isEmpty()) return Photo(null, "", 0)
        val f = if (shuffle) files[Random.nextInt(files.size)] else files.first()
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        while (max(o.outWidth, o.outHeight) / (s * 2) >= maxDim) s *= 2
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
        val text = Shots.date(f).format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
        return Photo(bmp, text, files.size)
    }

    fun render(w: Int, h: Int, c: WidgetConfig, photo: Photo): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        val r = c.roundness * min(w, h)
        val layer = cv.saveLayer(0f, 0f, w.toFloat(), h.toFloat(), null)
        when (c.theme) {
            THEME_LIGHT -> solid(cv, w, h, r, c, photo, false)
            THEME_DARK -> solid(cv, w, h, r, c, photo, true)
            THEME_GRADIENT -> gradient(cv, w, h, r, c, photo)
            else -> glass(cv, w, h, r, c, photo)
        }
        // anti-aliased rounded-corner mask (the "edge")
        cv.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        cv.restoreToCount(layer)
        return out
    }

    // ---------- themes ----------

    private fun glass(cv: Canvas, w: Int, h: Int, r: Float, c: WidgetConfig, ph: Photo) {
        val full = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val bmp = ph.bmp ?: placeholder(w, h)
        drawCover(cv, bmp, full)
        // feathered frosted band along the bottom (progressive blur + tint)
        val bandH = h * (if (c.showLabel) 0.52f else 0.34f)
        val band = RectF(0f, h - bandH, w.toFloat(), h.toFloat())
        val l = cv.saveLayer(band, null)
        drawCover(cv, blur(bmp), full, satPaint(1.5f))
        cv.drawRect(band, Paint().apply {
            shader = LinearGradient(0f, band.top, 0f, band.bottom, withAlpha(c.colorA, 40), withAlpha(c.colorA, 120), Shader.TileMode.CLAMP)
        })
        cv.drawRect(band, Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            shader = LinearGradient(0f, band.top, 0f, band.top + bandH * 0.5f, 0x00000000, 0xFF000000.toInt(), Shader.TileMode.CLAMP)
        })
        cv.restoreToCount(l)
        // soft top-left sheen
        cv.drawRect(full, Paint().apply {
            shader = RadialGradient(w * 0.2f, h * 0.1f, w * 0.75f, 0x55FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        })
        rim(cv, w, h, r)
        if (c.showLabel) label(cv, ph, min(w, h) * 0.07f, h - min(w, h) * 0.07f, w - min(w, h) * 0.14f, 0xFFFFFFFF.toInt(), true)
    }

    private fun solid(cv: Canvas, w: Int, h: Int, r: Float, c: WidgetConfig, ph: Photo, dark: Boolean) {
        val bg = if (dark) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt()
        val fg = if (dark) 0xFFFFFFFF.toInt() else 0xFF111111.toInt()
        cv.drawColor(bg)
        val unit = min(w, h)
        val pad = unit * 0.07f
        val wide = w > h * 1.6f
        val pr = when {
            !c.showLabel -> RectF(pad, pad, w - pad, h - pad)
            wide -> RectF(pad, pad, h - pad, h - pad)
            else -> RectF(pad, pad, w - pad, h - pad - h * 0.25f)
        }
        val ir = max(r - pad * 0.5f, 8f)
        val bmp = ph.bmp ?: placeholder(pr.width().toInt().coerceAtLeast(2), pr.height().toInt().coerceAtLeast(2))
        val l = cv.saveLayer(pr, null)
        drawCover(cv, bmp, pr)
        cv.drawRoundRect(pr, ir, ir, Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        cv.restoreToCount(l)
        cv.drawRoundRect(pr, ir, ir, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = max(1f, unit * 0.006f); color = withAlpha(fg, 30)
        })
        if (c.showLabel) {
            if (wide) label(cv, ph, pr.right + pad * 1.3f, h - pad, w - pr.right - pad * 2.3f, fg, false)
            else label(cv, ph, pad, h - pad, w - pad * 2, fg, false)
        }
    }

    private fun gradient(cv: Canvas, w: Int, h: Int, r: Float, c: WidgetConfig, ph: Photo) {
        val full = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val bmp = ph.bmp ?: placeholder(w, h)
        drawCover(cv, bmp, full)
        // progressive blur: sharp on top, blurred toward the bottom
        val l = cv.saveLayer(full, null)
        drawCover(cv, blur(bmp), full, satPaint(1.3f))
        cv.drawRect(full, Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(0x00000000, 0x00000000, 0xFF000000.toInt()),
                floatArrayOf(0f, 0.25f, 0.85f), Shader.TileMode.CLAMP)
        })
        cv.restoreToCount(l)
        // colour wash
        cv.drawRect(full, Paint().apply {
            shader = LinearGradient(0f, h.toFloat(), w * 0.7f, 0f,
                intArrayOf(withAlpha(c.colorB, 235), withAlpha(c.colorA, 150), withAlpha(c.colorA, 0)),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        })
        rim(cv, w, h, r)
        if (c.showLabel) label(cv, ph, min(w, h) * 0.07f, h - min(w, h) * 0.07f, w - min(w, h) * 0.14f, 0xFFFFFFFF.toInt(), true)
    }

    // ---------- helpers ----------

    private fun rim(cv: Canvas, w: Int, h: Int, r: Float) {
        val sw = max(2f, min(w, h) * 0.014f)
        cv.drawRoundRect(RectF(sw / 2, sw / 2, w - sw / 2, h - sw / 2), r - sw / 2, r - sw / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = sw
            shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
                intArrayOf(0xCCFFFFFF.toInt(), 0x10FFFFFF, 0x55FFFFFF), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        })
    }

    private fun label(cv: Canvas, ph: Photo, left: Float, baseline: Float, maxW: Float, color: Int, shadow: Boolean) {
        val unit = maxW
        val title = if (ph.bmp != null) ph.dateText else "SYNTAX CAM"
        val sub = if (ph.bmp != null) "${ph.count} shot${if (ph.count == 1) "" else "s"}" else "Take your first shot"
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; textSize = unit * 0.17f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            if (shadow) setShadowLayer(unit * 0.03f, 0f, unit * 0.008f, 0x66000000)
        }
        while (tp.measureText(title) > maxW && tp.textSize > 10f) tp.textSize *= 0.92f
        val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = withAlpha(color, 205); textSize = tp.textSize * 0.5f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            if (shadow) setShadowLayer(unit * 0.02f, 0f, unit * 0.005f, 0x55000000)
        }
        cv.drawText(sub, left, baseline, sp)
        cv.drawText(title, left, baseline - sp.textSize * 1.4f, tp)
    }

    private fun drawCover(cv: Canvas, bmp: Bitmap, dst: RectF, paint: Paint = Paint(AA)) {
        val sw = bmp.width.toFloat(); val sh = bmp.height.toFloat()
        val scale = max(dst.width() / sw, dst.height() / sh)
        val cw = dst.width() / scale; val ch = dst.height() / scale
        val sx = (sw - cw) / 2f; val sy = (sh - ch) / 2f
        cv.drawBitmap(bmp, Rect(sx.toInt(), sy.toInt(), (sx + cw).toInt().coerceAtMost(bmp.width), (sy + ch).toInt().coerceAtMost(bmp.height)), dst, paint)
    }

    private fun blur(src: Bitmap): Bitmap {
        var b = Bitmap.createScaledBitmap(src, max(src.width / 20, 8), max(src.height / 20, 8), true)
        b = Bitmap.createScaledBitmap(b, max(src.width / 5, 16), max(src.height / 5, 16), true)
        return b
    }

    private fun satPaint(s: Float) = Paint(AA).apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(s) }) }
    private fun withAlpha(color: Int, a: Int) = (color and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    /** Astral placeholder when there are no photos yet: dark sky, stars, the four-point star. */
    private fun placeholder(w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF05030F.toInt(), 0xFF22104A.toInt(), Shader.TileMode.CLAMP)
        })
        val rnd = Random(5)
        val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        repeat(40) { sp.alpha = 60 + rnd.nextInt(160); c.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * h, 1f + rnd.nextFloat() * 1.5f, sp) }
        val cx = w / 2f; val cy = h * 0.42f; val rr = min(w, h) * 0.22f
        val star = Path().apply {
            moveTo(cx, cy - rr); quadTo(cx, cy, cx + rr, cy); quadTo(cx, cy, cx, cy + rr)
            quadTo(cx, cy, cx - rr, cy); quadTo(cx, cy, cx, cy - rr); close()
        }
        c.drawPath(star, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        return b
    }
}

/** Shared by the three widget sizes (2x2, 4x2, 4x4). All are resizable. */
open class SyntaxWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) { ids.forEach { update(ctx, mgr, it) } }
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, newOptions: android.os.Bundle) { update(ctx, mgr, id) }
    override fun onDeleted(ctx: Context, ids: IntArray) { ids.forEach { WidgetStore.delete(ctx, it) } }

    companion object {
        fun sizePx(ctx: Context, mgr: AppWidgetManager, id: Int, maxSide: Int = 640): Pair<Int, Int> {
            val o = mgr.getAppWidgetOptions(id)
            val dm = ctx.resources.displayMetrics
            val port = ctx.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
            val wDp = o.getInt(if (port) AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 160)
            val hDp = o.getInt(if (port) AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 160)
            var w = (wDp * dm.density).toInt().coerceAtLeast(120)
            var h = (hDp * dm.density).toInt().coerceAtLeast(120)
            val s = maxSide.toFloat() / max(w, h)
            if (s < 1f) { w = (w * s).toInt(); h = (h * s).toInt() }
            return w to h
        }

        fun update(ctx: Context, mgr: AppWidgetManager, id: Int) {
            runCatching {
                val (w, h) = sizePx(ctx, mgr, id)
                val cfg = WidgetStore.load(ctx, id)
                val photo = WidgetRenderer.pickPhoto(ctx, cfg.shuffle)
                val bmp = WidgetRenderer.render(w, h, cfg, photo)
                val rv = RemoteViews(ctx.packageName, R.layout.widget_main)
                rv.setImageViewBitmap(R.id.widget_img, bmp)
                val open = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                rv.setOnClickPendingIntent(R.id.widget_root,
                    PendingIntent.getActivity(ctx, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                mgr.updateAppWidget(id, rv)
            }
        }

        fun refreshAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            listOf(SmallWidget::class.java, WideWidget::class.java, LargeWidget::class.java).forEach { cls ->
                mgr.getAppWidgetIds(ComponentName(ctx, cls)).forEach { update(ctx, mgr, it) }
            }
        }
    }
}

class SmallWidget : SyntaxWidgetProvider()
class WideWidget : SyntaxWidgetProvider()
class LargeWidget : SyntaxWidgetProvider()
