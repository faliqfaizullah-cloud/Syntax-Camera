package com.example.syntaxcam

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Frosted glass (the "Frosted" style): the blurred scene behind the element, a touch darker and milky,
 * a soft diagonal sheen, fine grain, and a thin light rim.
 * [backdrop] is the heavily blurred live frame; [screen] / [card] say where that picture sits (root coordinates)
 * for elements over the page and for elements over the preview card.
 */
class FrostCtx(val backdrop: ImageBitmap?, val screen: Rect, val card: Rect)

val LocalFrost = compositionLocalOf { FrostCtx(null, Rect.Zero, Rect.Zero) }

private val noiseBitmap: ImageBitmap by lazy {
    val s = 128
    val px = IntArray(s * s)
    val rnd = java.util.Random(7)
    for (i in px.indices) { val v = rnd.nextInt(256); px[i] = (9 shl 24) or (v shl 16) or (v shl 8) or v }
    val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    b.setPixels(px, 0, s, 0, 0, s, s)
    b.asImageBitmap()
}
private val noiseBrush: ShaderBrush by lazy { ShaderBrush(ImageShader(noiseBitmap, TileMode.Repeated, TileMode.Repeated)) }

@Composable
fun Modifier.frostedGlass(
    shape: Shape,
    inCard: Boolean = false,
    tint: Color = Color.Transparent,
    darken: Float = 0.14f,
    milk: Float = 0.05f
): Modifier {
    val ctx = LocalFrost.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    val rect = if (inCard) ctx.card else ctx.screen
    return this
        .clip(shape)
        .onGloballyPositioned { origin = it.positionInRoot() }
        .drawBehind {
            val bd = ctx.backdrop
            if (bd != null && rect.width > 1f && rect.height > 1f) {
                frostCover(bd, rect.left - origin.x, rect.top - origin.y, rect.width, rect.height)
            } else {
                drawRect(Color(0xFF26262B))
            }
            drawRect(Color.Black.copy(alpha = darken))
            drawRect(Color.White.copy(alpha = milk))
            if (tint.alpha > 0f) drawRect(tint)
            drawRect(
                Brush.linearGradient(
                    listOf(Color(0x30FFFFFF), Color.Transparent, Color(0x18000000)),
                    Offset.Zero, Offset(size.width, size.height)
                )
            )
            drawRect(noiseBrush)
            val outline = shape.createOutline(size, layoutDirection, this)
            drawOutline(
                outline,
                Brush.linearGradient(listOf(Color(0x88FFFFFF), Color(0x1FFFFFFF), Color(0x55FFFFFF)), Offset.Zero, Offset(size.width, size.height)),
                style = Stroke(1.3f * density)
            )
        }
}

/** Draw [img] "cover"-cropped into the given rectangle (the rectangle may extend outside the clip). */
fun DrawScope.frostCover(img: ImageBitmap, left: Float, top: Float, w: Float, h: Float) {
    if (w <= 1f || h <= 1f) return
    val sw = img.width; val sh = img.height
    val dstA = w / h; val srcA = sw.toFloat() / sh
    var cw = sw; var ch = sh
    if (srcA > dstA) cw = (sh * dstA).toInt().coerceAtLeast(1) else ch = (sw / dstA).toInt().coerceAtLeast(1)
    drawImage(
        img, IntOffset((sw - cw) / 2, (sh - ch) / 2), IntSize(cw, ch),
        IntOffset(left.roundToInt(), top.roundToInt()), IntSize(w.roundToInt(), h.roundToInt()),
        filterQuality = FilterQuality.High
    )
}

/** A circle with a wobbling, liquid edge (used for the fluid reveal when filters are combined). */
fun liquidBlob(c: Offset, r: Float, phase: Float, amp: Float): Path {
    val p = Path()
    val steps = 96
    for (k in 0..steps) {
        val t = k.toFloat() / steps * 6.2831855f
        val wob = 1f + amp * (0.07f * sin(3f * t + phase) + 0.045f * sin(5f * t - 1.3f * phase) + 0.03f * sin(2f * t + 2.1f * phase))
        val x = c.x + r * wob * cos(t); val y = c.y + r * wob * sin(t)
        if (k == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close()
    return p
}
