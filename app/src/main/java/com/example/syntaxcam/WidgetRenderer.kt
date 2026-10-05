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
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

const val THEME_LIGHT = 1      // solid white
const val THEME_DARK = 2       // solid near-black

data class WidgetConfig(
    val theme: Int = THEME_LIGHT,
    val roundness: Float = 0.17f,   // corner radius as a share of the short side (0.17 = reference tile)
    val showLabel: Boolean = true,
    val shuffle: Boolean = false
)

object WidgetStore {
    private fun p(ctx: Context) = ctx.getSharedPreferences("syntaxcam_widgets", Context.MODE_PRIVATE)
    fun load(ctx: Context, id: Int): WidgetConfig {
        val s = p(ctx); val d = WidgetConfig()
        // older versions stored glass / gradient themes: anything that is not dark becomes white
        val theme = if (s.getInt("$id.theme", d.theme) == THEME_DARK) THEME_DARK else THEME_LIGHT
        return WidgetConfig(theme, s.getFloat("$id.round", d.roundness), s.getBoolean("$id.label", d.showLabel), s.getBoolean("$id.shuffle", d.shuffle))
    }
    fun save(ctx: Context, id: Int, c: WidgetConfig) {
        p(ctx).edit().putInt("$id.theme", c.theme).putFloat("$id.round", c.roundness)
            .putBoolean("$id.label", c.showLabel).putBoolean("$id.shuffle", c.shuffle).apply()
    }
    fun delete(ctx: Context, id: Int) {
        val e = p(ctx).edit()
        listOf("theme", "a", "b", "round", "label", "shuffle").forEach { e.remove("$id.$it") }
        e.apply()
    }
}

/**
 * Minimal solid widget: one rounded photo on a flat white or near-black card,
 * with the date in bold italic and a quiet shot count. Corners follow the reference tile.
 */
object WidgetRenderer {
    private const val AA = Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG

    class Photo(val bmp: Bitmap?, val date: LocalDate, val count: Int)

    fun pickPhoto(ctx: Context, shuffle: Boolean, maxDim: Int = 900): Photo {
        val files = Shots.list(ctx)
        if (files.isEmpty()) return Photo(null, LocalDate.now(), 0)
        val f = if (shuffle) files[Random.nextInt(files.size)] else files.first()
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        while (max(o.outWidth, o.outHeight) / (s * 2) >= maxDim) s *= 2
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
        return Photo(bmp, Shots.date(f), files.size)
    }

    fun render(w: Int, h: Int, c: WidgetConfig, photo: Photo): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        val unit = min(w, h).toFloat()
        val r = c.roundness * unit
        val dark = c.theme == THEME_DARK
        val layer = cv.saveLayer(0f, 0f, w.toFloat(), h.toFloat(), null)
        solid(cv, w, h, r, c, photo, dark)
        // anti-aliased rounded-corner mask: this is the widget's edge
        cv.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        cv.restoreToCount(layer)
        // hairline so a white card still reads on a white wallpaper
        cv.drawRoundRect(RectF(0.5f, 0.5f, w - 0.5f, h - 0.5f), r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = max(1f, unit * 0.004f)
            color = if (dark) 0x16FFFFFF else 0x1A000000
        })
        return out
    }

    private fun solid(cv: Canvas, w: Int, h: Int, r: Float, c: WidgetConfig, ph: Photo, dark: Boolean) {
        val bg = if (dark) 0xFF0E0E10.toInt() else 0xFFFFFFFF.toInt()
        val fg = if (dark) 0xFFFFFFFF.toInt() else 0xFF111111.toInt()
        val muted = withAlpha(fg, 130)
        cv.drawColor(bg)

        val unit = min(w, h).toFloat()
        val pad = unit * 0.075f
        val wide = w > h * 1.55f
        val ts = unit * 0.14f
        val pr = when {
            !c.showLabel -> RectF(pad, pad, w - pad, h - pad)
            wide -> RectF(pad, pad, h - pad, h - pad)
            else -> RectF(pad, pad, w - pad, max(h - pad - ts * 1.05f - pad * 0.6f, pad + unit * 0.4f))
        }
        val ir = max(r - pad, unit * 0.05f)          // concentric with the card's corner
        val bmp = ph.bmp ?: placeholder(pr.width().toInt(), pr.height().toInt(), dark)
        val l = cv.saveLayer(pr, null)
        drawCover(cv, bmp, pr)
        cv.drawRoundRect(pr, ir, ir, Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        cv.restoreToCount(l)

        if (!c.showLabel) return
        val locale = Locale.getDefault()
        val dateStr = ph.date.format(DateTimeFormatter.ofPattern("d MMM", locale))
        val weekday = ph.date.format(DateTimeFormatter.ofPattern("EEEE", locale)).uppercase(locale)
        val boldItalic = Typeface.create("sans-serif", Typeface.BOLD_ITALIC)
        val plain = Typeface.create("sans-serif", Typeface.NORMAL)
        val countFull = "${ph.count} shot${if (ph.count == 1) "" else "s"}"

        if (wide) {
            val x0 = pr.right + pad * 1.3f
            val avail = w - x0 - pad
            val dp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fg; typeface = boldItalic; textSize = unit * 0.2f }
            while (dp.measureText(dateStr) > avail && dp.textSize > 10f) dp.textSize *= 0.92f
            val cs = dp.textSize * 0.34f
            val cp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; typeface = plain; textSize = cs; letterSpacing = 0.02f }
            val wp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; typeface = plain; textSize = cs * 0.85f; letterSpacing = 0.14f }
            val base = h - pad
            cv.drawText(countFull, x0, base, cp)
            val by = base - cs * 1.9f
            cv.drawText(dateStr, x0, by, dp)
            cv.drawText(weekday, x0, by - dp.textSize * 1.2f, wp)
        } else {
            val dp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fg; typeface = boldItalic; textSize = ts }
            while (dp.measureText(dateStr) > w - 2 * pad && dp.textSize > 10f) dp.textSize *= 0.92f
            val base = h - pad * 0.95f
            cv.drawText(dateStr, pad, base, dp)
            val cp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = muted; typeface = plain; textSize = dp.textSize * 0.55f; textAlign = Paint.Align.RIGHT
            }
            val room = w - 2 * pad - dp.measureText(dateStr) - pad * 1.5f
            val str = if (cp.measureText(countFull) < room) countFull else "${ph.count}"
            if (cp.measureText(str) < room) cv.drawText(str, w - pad, base, cp)
        }
    }

    private fun drawCover(cv: Canvas, bmp: Bitmap, dst: RectF) {
        val sw = bmp.width.toFloat(); val sh = bmp.height.toFloat()
        val scale = max(dst.width() / sw, dst.height() / sh)
        val cw = dst.width() / scale; val ch = dst.height() / scale
        val sx = (sw - cw) / 2f; val sy = (sh - ch) / 2f
        cv.drawBitmap(bmp, Rect(sx.toInt(), sy.toInt(), (sx + cw).toInt().coerceAtMost(bmp.width), (sy + ch).toInt().coerceAtMost(bmp.height)), dst, Paint(AA))
    }

    private fun withAlpha(color: Int, a: Int) = (color and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    /** No photos yet: a flat tile with a thin outlined four-point star. */
    private fun placeholder(w: Int, h: Int, dark: Boolean): Bitmap {
        val b = Bitmap.createBitmap(max(w, 2), max(h, 2), Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(if (dark) 0xFF1E1E21.toInt() else 0xFFF0F0F2.toInt())
        val cx = b.width / 2f; val cy = b.height / 2f; val rr = min(b.width, b.height) * 0.14f
        val star = Path().apply {
            moveTo(cx, cy - rr); quadTo(cx, cy, cx + rr, cy); quadTo(cx, cy, cx, cy + rr)
            quadTo(cx, cy, cx - rr, cy); quadTo(cx, cy, cx, cy - rr); close()
        }
        c.drawPath(star, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = max(2f, rr * 0.12f); strokeJoin = Paint.Join.ROUND
            color = withAlpha(if (dark) Color.WHITE else Color.BLACK, 80)
        })
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
