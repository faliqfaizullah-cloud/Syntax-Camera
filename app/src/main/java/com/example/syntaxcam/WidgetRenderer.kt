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

const val CORNER_DP = 28f      // card corner radius, in dp

data class WidgetConfig(
    val showDate: Boolean = true,
    val shuffle: Boolean = false
)

object WidgetStore {
    private fun p(ctx: Context) = ctx.getSharedPreferences("syntaxcam_widgets", Context.MODE_PRIVATE)
    fun load(ctx: Context, id: Int): WidgetConfig {
        val s = p(ctx); val d = WidgetConfig()
        return WidgetConfig(s.getBoolean("$id.label", d.showDate), s.getBoolean("$id.shuffle", d.shuffle))
    }
    fun save(ctx: Context, id: Int, c: WidgetConfig) {
        p(ctx).edit().putBoolean("$id.label", c.showDate).putBoolean("$id.shuffle", c.shuffle).apply()
    }
    fun delete(ctx: Context, id: Int) {
        val e = p(ctx).edit()
        listOf("theme", "a", "b", "round", "label", "shuffle").forEach { e.remove("$id.$it") }
        e.apply()
    }
}

/** Plain photo widget: the photo fills the whole 28dp-rounded card, date in bold italic at the bottom left. */
object WidgetRenderer {
    private const val AA = Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG

    class Photo(val bmp: Bitmap?, val date: LocalDate)

    fun pickPhoto(ctx: Context, shuffle: Boolean, maxDim: Int = 900): Photo {
        val files = Shots.list(ctx)
        if (files.isEmpty()) return Photo(null, LocalDate.now())
        val f = if (shuffle) files[Random.nextInt(files.size)] else files.first()
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        while (max(o.outWidth, o.outHeight) / (s * 2) >= maxDim) s *= 2
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
        return Photo(bmp, Shots.date(f))
    }

    /** cornerPx = 28dp converted for this bitmap. */
    fun render(w: Int, h: Int, c: WidgetConfig, photo: Photo, cornerPx: Float): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        val unit = min(w, h).toFloat()
        val r = min(cornerPx, unit / 2f)
        val layer = cv.saveLayer(0f, 0f, w.toFloat(), h.toFloat(), null)
        drawCover(cv, photo.bmp ?: placeholder(w, h), RectF(0f, 0f, w.toFloat(), h.toFloat()))
        if (c.showDate) drawDate(cv, w, h, photo.date)
        // anti-aliased rounded-corner mask: this is the widget's edge
        cv.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        cv.restoreToCount(layer)
        return out
    }

    private fun drawDate(cv: Canvas, w: Int, h: Int, date: LocalDate) {
        val unit = min(w, h).toFloat()
        val pad = unit * 0.085f
        val text = date.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create("sans-serif", Typeface.BOLD_ITALIC)
            textSize = unit * 0.16f
        }
        while (p.measureText(text) > w - 2 * pad && p.textSize > 10f) p.textSize *= 0.92f
        val base = h - pad * 0.9f
        // soft shadow keeps white text readable on bright photos (drawn twice for body)
        p.setShadowLayer(unit * 0.05f, 0f, unit * 0.012f, 0xAA000000.toInt())
        cv.drawText(text, pad, base, p)
        cv.drawText(text, pad, base, p)
    }

    private fun drawCover(cv: Canvas, bmp: Bitmap, dst: RectF) {
        val sw = bmp.width.toFloat(); val sh = bmp.height.toFloat()
        val scale = max(dst.width() / sw, dst.height() / sh)
        val cw = dst.width() / scale; val ch = dst.height() / scale
        val sx = (sw - cw) / 2f; val sy = (sh - ch) / 2f
        cv.drawBitmap(bmp, Rect(sx.toInt(), sy.toInt(), (sx + cw).toInt().coerceAtMost(bmp.width), (sy + ch).toInt().coerceAtMost(bmp.height)), dst, Paint(AA))
    }

    /** No photos yet: a flat dark tile with a thin outlined four-point star. */
    private fun placeholder(w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(max(w, 2), max(h, 2), Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(0xFF1E1E21.toInt())
        val cx = b.width / 2f; val cy = b.height * 0.42f; val rr = min(b.width, b.height) * 0.14f
        val star = Path().apply {
            moveTo(cx, cy - rr); quadTo(cx, cy, cx + rr, cy); quadTo(cx, cy, cx, cy + rr)
            quadTo(cx, cy, cx - rr, cy); quadTo(cx, cy, cx, cy - rr); close()
        }
        c.drawPath(star, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = max(2f, rr * 0.12f); strokeJoin = Paint.Join.ROUND
            color = 0x50FFFFFF
        })
        return b
    }
}

class WidgetMetrics(val w: Int, val h: Int, val pxPerDp: Float)

/** The single 2x2 widget (not resizable). */
open class SyntaxWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) { ids.forEach { update(ctx, mgr, it) } }
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, newOptions: android.os.Bundle) { update(ctx, mgr, id) }
    override fun onDeleted(ctx: Context, ids: IntArray) { ids.forEach { WidgetStore.delete(ctx, it) } }

    companion object {
        /** Pixel size to draw at, plus how many bitmap pixels equal 1dp (so 28dp stays 28dp). */
        fun metrics(ctx: Context, mgr: AppWidgetManager, id: Int, maxSide: Int = 640): WidgetMetrics {
            val o = mgr.getAppWidgetOptions(id)
            val port = ctx.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
            val wDp = o.getInt(if (port) AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 150).coerceAtLeast(60)
            val hDp = o.getInt(if (port) AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 150).coerceAtLeast(60)
            var ppd = ctx.resources.displayMetrics.density
            val s = maxSide.toFloat() / max(wDp * ppd, hDp * ppd)
            if (s < 1f) ppd *= s
            return WidgetMetrics((wDp * ppd).toInt().coerceAtLeast(2), (hDp * ppd).toInt().coerceAtLeast(2), ppd)
        }

        fun update(ctx: Context, mgr: AppWidgetManager, id: Int) {
            runCatching {
                val m = metrics(ctx, mgr, id)
                val cfg = WidgetStore.load(ctx, id)
                val photo = WidgetRenderer.pickPhoto(ctx, cfg.shuffle)
                val bmp = WidgetRenderer.render(m.w, m.h, cfg, photo, CORNER_DP * m.pxPerDp)
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
            mgr.getAppWidgetIds(ComponentName(ctx, SmallWidget::class.java)).forEach { update(ctx, mgr, it) }
        }
    }
}

class SmallWidget : SyntaxWidgetProvider()
