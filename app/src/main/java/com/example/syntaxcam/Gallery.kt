package com.example.syntaxcam

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.TextStyle as JTextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/** Photos taken in the app are kept here (app-private) so the gallery can show them. */
object Shots {
    private fun dir(ctx: Context) = File(ctx.filesDir, "shots").apply { mkdirs() }
    fun add(ctx: Context, bmp: Bitmap) {
        val m = maxOf(bmp.width, bmp.height)
        val b = if (m > 1600) {
            val s = 1600f / m
            Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true)
        } else bmp
        val f = File(dir(ctx), "${System.currentTimeMillis()}.jpg")
        f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        runCatching { SyntaxWidgetProvider.refreshAll(ctx) }
    }
    fun list(ctx: Context): List<File> =
        dir(ctx).listFiles()?.filter { it.extension == "jpg" }?.sortedByDescending { it.name } ?: emptyList()
    fun date(f: File): LocalDate =
        Instant.ofEpochMilli(f.nameWithoutExtension.toLongOrNull() ?: f.lastModified())
            .atZone(ZoneId.systemDefault()).toLocalDate()
}

// =====================================================================================
// Gallery: dotted white page, a column of photo circles that morph into squircles (goo),
// black capsule + side tab, EXPLORE slider bar, full-screen view with typed analysis HUD.
// =====================================================================================

private val Ink = Color(0xFF111114)
private val Paper = Color(0xFFF8F7FA)
private val Mono = FontFamily.Monospace

private class Analysis(val avgLum: Int, val topLum: Float, val green: ImageBitmap, val w: Int, val h: Int)

private fun ss(a: Float, b: Float, x: Float): Float {
    val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3f - 2f * t)
}
private fun lerpf(a: Float, b: Float, t: Float) = a + (b - a) * t
private fun easeOutBack(t: Float): Float {
    val c1 = 1.70158f; val c3 = c1 + 1f; val x = t - 1f
    return 1f + c3 * x * x * x + c1 * x * x
}

private fun decodeSampled(file: File, maxDim: Int): Bitmap? = runCatching {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, o)
    var s = 1
    while (max(o.outWidth, o.outHeight) / (s * 2) >= maxDim) s *= 2
    BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = s })
}.getOrNull()

private fun analyze(bmp: Bitmap): Analysis {
    val w = 120; val h = max(1, bmp.height * w / bmp.width)
    val small = Bitmap.createScaledBitmap(bmp, w, h, true)
    val px = IntArray(w * h); small.getPixels(px, 0, w, 0, 0, w, h)
    val gpx = IntArray(w * h)
    var sum = 0.0; var top = 0.0; var topN = 0
    for (y in 0 until h) for (x in 0 until w) {
        val p = px[y * w + x]
        val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
        val l = 0.30 * r + 0.59 * g + 0.11 * b
        sum += l
        if (y < h * 0.3 && x < w * 0.6) { top += l; topN++ }
        val gv = ((g - max(r, b) * 1.1f - 6f) / 40f).coerceIn(0f, 1f)   // "green-ish" pixels only
        gpx[y * w + x] = ((gv * 230).toInt() shl 24) or (0x22 shl 16) or (0xD0 shl 8) or 0x5A
    }
    val gb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    gb.setPixels(gpx, 0, w, 0, 0, w, h)
    return Analysis(((sum / (w * h)) / 255.0 * 99).roundToInt(), (top / max(1, topN)).toFloat(), gb.asImageBitmap(), bmp.width, bmp.height)
}

private fun share(ctx: Context, f: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** All the layout numbers in one place (so drawing and touch use identical geometry). */
private class Geo(val w: Float, val h: Float, val top: Float, val d: Float) {
    val cx = 0.52f * w
    val itemR = 0.14f * w
    val bigR = 0.20f * w
    val y0 = 0.175f * h
    val spacing = 0.152f * h
    // capsule
    val capW = 0.16f * w
    val capH = capW * 2.5f
    val capL = w - 0.045f * w - capW
    val capT = top + 14f * d
    // side tab
    val tabW = 0.10f * w
    val tabT = 0.50f * h
    val tabB = 0.77f * h
    // bottom bar
    val barL = 0.045f * w
    val barR = 0.955f * w
    val barT = 0.872f * h
    val barB = 0.950f * h
    val barCy = (barT + barB) / 2f
    val trackL = 0.36f * w
    val trackR = 0.80f * w
    fun slotY(slot: Float) = y0 + slot * spacing
    fun avatarCenter() = Offset(capL + capW / 2f, capT + capW * 0.5f + capW * 0.06f)
    fun boltCenter(capHNow: Float = capH) = Offset(capL + capW / 2f, capT + capHNow - capW * 0.5f - capW * 0.04f)
    fun tabIcon(k: Int) = Offset(w - tabW / 2f, tabT + (tabB - tabT) * (0.2f + 0.3f * k))
    fun sliderHit(p: Offset) = p.x in (trackL - 24f * d)..(trackR + 24f * d) && p.y in barT..barB
}

@Composable
fun GalleryScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val haptics = remember(view) { Haptics(view) }
    val density = LocalDensity.current
    val d = density.density
    val scope = rememberCoroutineScope()
    val tm = rememberTextMeasurer(cacheSize = 96)
    val topInset = WindowInsets.displayCutout.getTop(density).toFloat()

    var files by remember { mutableStateOf(Shots.list(ctx)) }
    val thumbs = remember { mutableStateMapOf<String, ImageBitmap>() }
    LaunchedEffect(files) {
        for (f in files) if (!thumbs.containsKey(f.path)) {
            val b = withContext(Dispatchers.IO) { decodeSampled(f, 420) }
            if (b != null) thumbs[f.path] = b.asImageBitmap()
        }
    }

    var size by remember { mutableStateOf(IntSize.Zero) }
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val t0 = withFrameNanos { it }
        while (true) withFrameNanos { time = (it - t0) / 1_000_000_000f }
    }

    val focus = remember { Animatable(0f) }        // which photo is the squircle (float = goo in between)
    val scroll = remember { Animatable(0f) }       // first visible slot
    val open = remember { Animatable(0f) }         // 0 = list, 1 = full-screen photo
    val entr = remember { Animatable(0f) }         // entrance timeline
    val shift = remember { Animatable(0f) }        // slide-up after a delete
    var shiftFrom by remember { mutableIntStateOf(Int.MAX_VALUE) }
    var hudOn by remember { mutableStateOf(true) }
    var heroIndex by remember { mutableIntStateOf(-1) }
    var heroBmp by remember { mutableStateOf<ImageBitmap?>(null) }
    var analysis by remember { mutableStateOf<Analysis?>(null) }
    var hudT0 by remember { mutableFloatStateOf(-1f) }

    val n = files.size
    val focusIdx = { focus.value.roundToInt().coerceIn(0, max(0, files.size - 1)) }

    LaunchedEffect(Unit) { entr.animateTo(1f, tween(2600, easing = LinearEasing)) }
    LaunchedEffect(n) {
        snapshotFlow { (focus.value.roundToInt() - 2).coerceIn(0, max(0, n - 5)) }.distinctUntilChanged().collect { t ->
            scroll.animateTo(t.toFloat(), spring(dampingRatio = 0.75f, stiffness = 90f))
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { open.value > 0.85f }.collect { if (it && hudT0 < 0f) hudT0 = time }
    }

    val openHero: (Int) -> Unit = { idx ->
        if (idx in files.indices && open.value < 0.05f) {
            heroIndex = idx; heroBmp = null; analysis = null; hudT0 = -1f
            haptics.gestureStart()
            scope.launch { open.animateTo(1f, spring(dampingRatio = 0.78f, stiffness = 110f)) }
            scope.launch {
                val b = withContext(Dispatchers.IO) { decodeSampled(files[idx], 1400) }
                if (b != null && heroIndex == idx) {
                    analysis = withContext(Dispatchers.Default) { analyze(b) }
                    heroBmp = b.asImageBitmap()
                }
            }
        }
    }
    val closeHero: () -> Unit = {
        if (heroIndex >= 0) scope.launch {
            haptics.gestureEnd()
            open.animateTo(0f, spring(dampingRatio = 0.85f, stiffness = 130f))
            heroIndex = -1; heroBmp = null; analysis = null; hudT0 = -1f
        }
    }
    val deleteFocused: () -> Unit = {
        if (files.isNotEmpty()) scope.launch {
            haptics.heavy()
            val idx = focusIdx()
            if (open.value > 0.05f) {
                open.animateTo(0f, spring(dampingRatio = 0.9f, stiffness = 170f))
                heroIndex = -1; heroBmp = null; analysis = null; hudT0 = -1f
            }
            files[idx].delete()
            files = Shots.list(ctx)
            launch(Dispatchers.IO) { runCatching { SyntaxWidgetProvider.refreshAll(ctx) } }
            shiftFrom = idx; shift.snapTo(1f)
            focus.snapTo(focus.value.coerceAtMost(max(0, files.size - 1).toFloat()))
            shift.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 140f))
        }
    }
    val focusTo: (Int) -> Unit = { i ->
        scope.launch { focus.animateTo(i.toFloat(), spring(dampingRatio = 0.5f, stiffness = 170f)) }   // low damping = gooey overshoot
    }

    BackHandler { if (heroIndex >= 0) closeHero() else onClose() }

    val dots = remember(size) {
        val out = ArrayList<Offset>()
        val step = 24f * d
        var y = step / 2f
        while (y < size.height) { var x = step / 2f; while (x < size.width) { out.add(Offset(x, y)); x += step }; y += step }
        out
    }

    Box(
        Modifier.fillMaxSize().background(Paper).onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    val g = Geo(this.size.width.toFloat(), this.size.height.toFloat(), topInset, d)
                    val inHero = open.value > 0.5f
                    val count = files.size
                    fun near(c: Offset, r: Float) = (pos - c).getDistance() <= r
                    val capR = g.capW * 0.5f
                    when {
                        near(g.boltCenter(), capR) -> { if (inHero) hudOn = !hudOn else onClose() }
                        near(g.avatarCenter(), capR) -> { if (!inHero && count > 0) { haptics.tick(); focusTo(0) } }
                        near(g.tabIcon(0), g.tabW * 0.6f) -> { if (inHero) closeHero() else if (count > 0) openHero(focusIdx()) }
                        near(g.tabIcon(1), g.tabW * 0.6f) -> { if (count > 0) { haptics.tick(); share(ctx, files[focusIdx()]) } }
                        near(g.tabIcon(2), g.tabW * 0.6f) -> { deleteFocused() }
                        pos.y in g.barT..g.barB && pos.x < g.trackL - 30f * d -> { if (!inHero && count > 0) { haptics.tick(); focusTo(0) } }
                        !inHero -> {
                            val sc = scroll.value
                            for (i in 0 until count) {
                                val slot = i - sc + (if (i >= shiftFrom) shift.value else 0f)
                                if (slot < -0.4f || slot > 4.6f) continue
                                val m = (1f - abs(i - focus.value)).coerceIn(0f, 1f)
                                val r = g.itemR * (1f + 0.42f * m)
                                if (near(Offset(g.cx, g.slotY(slot)), r)) {
                                    if (i == focusIdx()) openHero(i) else { haptics.tick(); focusTo(i) }
                                    break
                                }
                            }
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                var mode = 0
                var acc = 0f
                var last = 0f
                detectDragGestures(
                    onDragStart = { pos ->
                        val g = Geo(this.size.width.toFloat(), this.size.height.toFloat(), topInset, d)
                        mode = when {
                            open.value > 0.5f -> 3
                            g.sliderHit(pos) -> 1
                            pos.y < g.barT && pos.x < g.w - g.tabW -> 2
                            else -> 0
                        }
                        acc = 0f; last = 0f
                    },
                    onDragEnd = {
                        val m = mode
                        if (m == 1 || m == 2) scope.launch {
                            val count = files.size
                            val t = (focus.value + (if (m == 2) -last / 70f else 0f)).roundToInt().coerceIn(0, max(0, count - 1))
                            haptics.tick()
                            focus.animateTo(t.toFloat(), spring(dampingRatio = 0.5f, stiffness = 170f))
                        }
                        mode = 0
                    },
                    onDragCancel = { mode = 0 },
                    onDrag = { change, drag ->
                        val g = Geo(this.size.width.toFloat(), this.size.height.toFloat(), topInset, d)
                        val count = files.size
                        when (mode) {
                            1 -> {
                                val t = ((change.position.x - g.trackL) / (g.trackR - g.trackL)).coerceIn(0f, 1f)
                                scope.launch { focus.snapTo(t * max(0, count - 1)) }
                            }
                            2 -> {
                                last = drag.y
                                scope.launch { focus.snapTo((focus.value - drag.y / g.spacing).coerceIn(0f, max(0, count - 1).toFloat())) }
                            }
                            3 -> { acc += drag.y; if (acc > 150f * d) { acc = -1e9f; closeHero() } }
                        }
                    }
                )
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = this.size.width; val h = this.size.height
            val g = Geo(w, h, topInset, d)
            val oh = open.value.coerceIn(0f, 1.03f)
            val listA = 1f - ss(0f, 0.45f, oh)
            val e = entr.value

            // ---- dotted page ----
            drawPoints(dots, PointMode.Points, Color(0x30000000), 1.6f * d, StrokeCap.Round)

            // ---- left decorations ----
            val decoA = ss(0.15f, 0.55f, e) * listA
            if (decoA > 0.01f) {
                val cal = LocalDateTime.now()
                val dayName = cal.dayOfWeek.getDisplayName(JTextStyle.FULL, Locale.ENGLISH).uppercase()
                val mon = cal.month.getDisplayName(JTextStyle.SHORT, Locale.ENGLISH).uppercase()
                val small = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 8.5.sp, letterSpacing = 0.8.sp,
                    lineHeight = 12.sp, color = Ink.copy(alpha = decoA))
                drawText(tm, "$dayName\n${cal.dayOfMonth.toString().padStart(2, '0')}\n$mon", Offset(0.07f * w, 0.165f * h), small)
                drawGlobe(Offset(0.075f * w, 0.405f * h), 0.085f * w, decoA)
                val h12 = (cal.hour % 12).let { if (it == 0) 12 else it }
                drawDotText("$h12:${if (cal.hour >= 12) "PM" else "AM"}", Offset(0.05f * w, 0.545f * h), 3.1f * d,
                    Color(0xFF2B2B31).copy(alpha = 0.85f * decoA), Color(0xFF000000).copy(alpha = 0.07f * decoA), true)
                drawText(tm, "PROJECT\nSYNTAX_\nCAM", Offset(0.065f * w, 0.705f * h), small)
            }

            // ---- empty state ----
            if (n == 0) {
                drawText(tm, "NO PHOTOS YET", Offset(0.30f * w, 0.45f * h),
                    TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.sp, color = Ink.copy(alpha = 0.7f)))
            }

            // ---- photo column (goo morph circle <-> squircle) ----
            val fv = focus.value
            val sc = scroll.value
            val sv = (focus.velocity * 0.010f).coerceIn(-0.2f, 0.2f)
            fun drawItem(i: Int) {
                val slot = i - sc + (if (i >= shiftFrom) shift.value else 0f)
                if (slot < -1.2f || slot > 5.2f) return
                val local = ((e - min(i, 6) * 0.07f) / 0.40f).coerceIn(0f, 1f)
                if (local <= 0f) return
                val prog = easeOutBack(local)
                val edge = ss(-0.9f, -0.1f, slot) * (1f - ss(4.2f, 5.0f, slot))
                val m0 = (1f - abs(i - fv)).coerceIn(0f, 1f)
                val m = m0 * m0 * (3f - 2f * m0)
                val r = g.itemR * (1f + 0.42f * m) * (0.55f + 0.45f * ss(0f, 0.6f, local))
                val yT = g.slotY(slot)
                val y = lerpf(h + g.itemR * 1.6f, yT, prog)
                val stretch = 0.22f * (1f - local) + abs(sv) * m
                val a = r * (1f - stretch * 0.45f); val b = r * (1f + stretch)
                val nExp = 2f + 1.4f * m
                val path = superellipse(g.cx, y, a, b, nExp)
                val alpha = (listA * edge * ss(0f, 0.25f, local)).coerceIn(0f, 1f)
                if (alpha <= 0.01f) return
                val img = files.getOrNull(i)?.let { thumbs[it.path] }
                clipPath(path) {
                    if (img != null) drawCover(img, g.cx - a, y - b, 2f * a, 2f * b, alpha)
                    else drawRect(Color(0xFFDAD8DF).copy(alpha = alpha), Offset(g.cx - a, y - b), Size(2f * a, 2f * b))
                }
            }
            val fi = fv.roundToInt().coerceIn(0, max(0, n - 1))
            for (i in 0 until n) if (i != fi && !(heroIndex == i && oh > 0.02f)) drawItem(i)
            if (n > 0 && !(heroIndex == fi && oh > 0.02f)) drawItem(fi)

            // ---- hero: the focused squircle grows into the full-screen photo ----
            if (heroIndex in 0 until n && oh > 0.005f) {
                val slot = heroIndex - sc + (if (heroIndex >= shiftFrom) shift.value else 0f)
                val cy0 = g.slotY(slot); val r0 = g.bigR
                val t = oh
                val l = lerpf(g.cx - r0, 0f, t); val tp = lerpf(cy0 - r0, 0f, t)
                val rr = lerpf(g.cx + r0, w, t); val bt = lerpf(cy0 + r0, h, t)
                val rad = lerpf(r0 * 0.62f, 0.075f * w, t.coerceIn(0f, 1f))
                val rp = Path().apply { addRoundRect(RoundRect(l, tp, rr, bt, CornerRadius(rad))) }
                val img = heroBmp ?: files.getOrNull(heroIndex)?.let { thumbs[it.path] }
                val an = analysis
                clipPath(rp) {
                    if (img != null) drawCover(img, l, tp, rr - l, bt - tp, 1f)
                    else drawRect(Color(0xFFDAD8DF), Offset(l, tp), Size(rr - l, bt - tp))
                    if (an != null && hudT0 >= 0f && oh > 0.9f) drawScan(an, img, time - hudT0, w, h, d, if (hudOn) 1f else 0.6f)
                }
                if (oh > 0.9f && an != null && hudT0 >= 0f && heroIndex in files.indices) {
                    drawHud(tm, an, files[heroIndex], heroIndex, n, time - hudT0, time, w, h, topInset, d,
                        ss(0.9f, 1f, oh) * (if (hudOn) 1f else 0f))
                }
            }

            // ---- side tab (slides in from the right) ----
            val tabP = ss(0.35f, 0.75f, e)
            if (tabP > 0.01f) drawSideTab(g, tabP)

            // ---- capsule (thin line grows into a capsule) ----
            drawCapsule(g, e, oh, files.firstOrNull()?.let { thumbs[it.path] })

            // ---- bottom bar ----
            val barA = ss(0.25f, 0.65f, e)
            if (barA > 0.01f) drawBar(tm, g, barA, if (n > 1) fv / (n - 1) else 0f, fi, n)
        }
    }
}

// ---------------------------------------------------------------- drawing helpers

private fun superellipse(cx: Float, cy: Float, a: Float, b: Float, n: Float): Path {
    val p = Path(); val steps = 72; val ex = 2f / n
    for (k in 0..steps) {
        val t = (k.toFloat() / steps) * 6.2831853f
        val c = cos(t); val s = sin(t)
        val x = a * sign(c) * abs(c).pow(ex); val y = b * sign(s) * abs(s).pow(ex)
        if (k == 0) p.moveTo(cx + x, cy + y) else p.lineTo(cx + x, cy + y)
    }
    p.close(); return p
}

/** Draw an image "cover"-cropped into the given rectangle. */
private fun DrawScope.drawCover(img: ImageBitmap, left: Float, top: Float, w: Float, h: Float, alpha: Float) {
    if (w <= 1f || h <= 1f) return
    val sw = img.width; val sh = img.height
    val dstA = w / h; val srcA = sw.toFloat() / sh
    var cw = sw; var ch = sh
    if (srcA > dstA) cw = (sh * dstA).toInt().coerceAtLeast(1) else ch = (sw / dstA).toInt().coerceAtLeast(1)
    drawImage(
        img, IntOffset((sw - cw) / 2, (sh - ch) / 2), IntSize(cw, ch),
        IntOffset(left.roundToInt(), top.roundToInt()), IntSize(w.roundToInt(), h.roundToInt()),
        alpha = alpha, filterQuality = FilterQuality.Medium
    )
}

private val font5x7 = mapOf(
    '0' to "01110,10001,10011,10101,11001,10001,01110", '1' to "00100,01100,00100,00100,00100,00100,01110",
    '2' to "01110,10001,00001,00010,00100,01000,11111", '3' to "11110,00001,00001,01110,00001,00001,11110",
    '4' to "00010,00110,01010,10010,11111,00010,00010", '5' to "11111,10000,11110,00001,00001,10001,01110",
    '6' to "00110,01000,10000,11110,10001,10001,01110", '7' to "11111,00001,00010,00100,01000,01000,01000",
    '8' to "01110,10001,10001,01110,10001,10001,01110", '9' to "01110,10001,10001,01111,00001,00010,01100",
    '+' to "00000,00100,00100,11111,00100,00100,00000", ':' to "00000,00100,00000,00000,00000,00100,00000",
    'A' to "01110,10001,10001,11111,10001,10001,10001", 'P' to "11110,10001,10001,11110,10000,10000,10000",
    'M' to "10001,11011,10101,10101,10001,10001,10001", '-' to "00000,00000,00000,11111,00000,00000,00000"
)

/** Dot-matrix text. Lit dots in [on], unlit dots in [off] when [grid] is true. */
private fun DrawScope.drawDotText(text: String, at: Offset, cell: Float, on: Color, off: Color, grid: Boolean) {
    var x = at.x
    for (ch in text) {
        val rows = font5x7[ch]?.split(",")
        if (rows != null) {
            for (ry in 0 until 7) for (rx in 0 until 5) {
                val lit = rows[ry][rx] == '1'
                if (lit || grid) drawRect(if (lit) on else off, Offset(x + rx * cell, at.y + ry * cell), Size(cell * 0.72f, cell * 0.72f))
            }
        }
        x += 6f * cell
    }
}

/** Little dithered pixel-art globe. */
private fun DrawScope.drawGlobe(c: Offset, size: Float, a: Float) {
    val n = 20; val px = size / n; val r = 9.2f
    for (y in 0 until n) for (x in 0 until n) {
        val dx = x - 9.5f; val dy = y - 9.5f; val dist = sqrt(dx * dx + dy * dy)
        if (dist > r + 0.4f) continue
        val ring = abs(dist - r) < 0.9f
        val equator = abs(dy) < 0.6f || abs(abs(dy) - 4.6f) < 0.5f
        val meridian = abs(dx) < 0.6f || abs(sqrt((dx / 4.6f) * (dx / 4.6f) + (dy / r) * (dy / r)) - 1f) < 0.1f
        val dither = dx < 0 && dy > -2 && (x + y) % 2 == 0
        if (ring || equator || meridian || dither) drawRect(Ink.copy(alpha = a), Offset(c.x - size / 2f + x * px, c.y - size / 2f + y * px), Size(px * 0.92f, px * 0.92f))
    }
}

private fun DrawScope.drawSideTab(g: Geo, p: Float) {
    val dx = g.tabW * 1.2f * (1f - p)
    val w = g.w + dx; val xL = w - g.tabW
    val yT = g.tabT; val yB = g.tabB
    val fr = 0.04f * g.w; val rc = g.tabW * 0.5f
    val path = Path().apply {
        moveTo(w, yT - fr); quadraticBezierTo(w, yT, w - fr, yT)
        lineTo(xL + rc, yT); quadraticBezierTo(xL, yT, xL, yT + rc)
        lineTo(xL, yB - rc); quadraticBezierTo(xL, yB, xL + rc, yB)
        lineTo(w - fr, yB); quadraticBezierTo(w, yB, w, yB + fr)
        close()
    }
    drawPath(path, Color(0xFF0B0B0D))
    val cxI = xL + g.tabW / 2f - (g.tabW * 0.12f)
    val s = g.tabW * 0.16f; val st = Stroke(1.5f * g.d, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val white = Color.White.copy(alpha = 0.92f)
    // 1) expand brackets
    val c0 = Offset(cxI, g.tabIcon(0).y)
    for ((sx, sy) in listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)) {
        val p0 = Path().apply {
            moveTo(c0.x + sx * s, c0.y + sy * s * 0.4f); lineTo(c0.x + sx * s, c0.y + sy * s); lineTo(c0.x + sx * s * 0.4f, c0.y + sy * s)
        }
        drawPath(p0, white, style = st)
    }
    // 2) document
    val c1 = Offset(cxI, g.tabIcon(1).y)
    val doc = Path().apply {
        moveTo(c1.x - s * 0.85f, c1.y - s); lineTo(c1.x + s * 0.35f, c1.y - s); lineTo(c1.x + s * 0.85f, c1.y - s * 0.5f)
        lineTo(c1.x + s * 0.85f, c1.y + s); lineTo(c1.x - s * 0.85f, c1.y + s); close()
    }
    drawPath(doc, white, style = st)
    // 3) bin
    val c2 = Offset(cxI, g.tabIcon(2).y)
    drawLine(white, Offset(c2.x - s * 0.9f, c2.y - s * 0.6f), Offset(c2.x + s * 0.9f, c2.y - s * 0.6f), 1.5f * g.d, StrokeCap.Round)
    drawRoundRect(white, Offset(c2.x - s * 0.7f, c2.y - s * 0.6f), Size(s * 1.4f, s * 1.7f), CornerRadius(s * 0.2f), style = st)
    drawLine(white, Offset(c2.x - s * 0.3f, c2.y - s * 0.95f), Offset(c2.x + s * 0.3f, c2.y - s * 0.95f), 1.5f * g.d, StrokeCap.Round)
}

private fun DrawScope.drawCapsule(g: Geo, e: Float, oh: Float, avatar: ImageBitmap?) {
    val p = ss(0.30f, 0.75f, e)
    if (p <= 0.001f) return
    val prog = easeOutBack(p)
    val line = 3f * g.d
    val hNow = lerpf(line, g.capH, prog.coerceAtLeast(0f))
    drawRoundRect(Color(0xFF0B0B0D), Offset(g.capL, g.capT), Size(g.capW, hNow), CornerRadius(min(g.capW, hNow) / 2f))
    val ca = ss(0.55f, 1f, p)
    if (ca <= 0.01f || hNow < g.capH * 0.7f) return
    // avatar
    val ac = g.avatarCenter(); val ar = g.capW * 0.36f
    val circ = Path().apply { addOval(Rect(ac.x - ar, ac.y - ar, ac.x + ar, ac.y + ar)) }
    clipPath(circ) {
        if (avatar != null) drawCover(avatar, ac.x - ar, ac.y - ar, 2f * ar, 2f * ar, ca)
        else drawRect(Color(0xFF3A3A42).copy(alpha = ca), Offset(ac.x - ar, ac.y - ar), Size(2f * ar, 2f * ar))
    }
    drawCircle(Color.White.copy(alpha = 0.35f * ca), ar, ac, style = Stroke(1.2f * g.d))
    // button
    val bc = g.boltCenter(hNow); val br = g.capW * 0.36f
    drawCircle(Color.White.copy(alpha = ca), br, bc)
    val u = g.capW * 0.15f
    val boltA = (1f - ss(0.3f, 0.7f, oh)) * ca
    if (boltA > 0.01f) {
        val bolt = Path().apply {
            moveTo(bc.x + 0.3f * u, bc.y - u); lineTo(bc.x - 0.55f * u, bc.y + 0.1f * u); lineTo(bc.x - 0.05f * u, bc.y + 0.1f * u)
            lineTo(bc.x - 0.3f * u, bc.y + u); lineTo(bc.x + 0.55f * u, bc.y - 0.15f * u); lineTo(bc.x + 0.05f * u, bc.y - 0.15f * u); close()
        }
        drawPath(bolt, Ink.copy(alpha = boltA))
    }
    val eyeA = ss(0.3f, 0.7f, oh) * ca
    if (eyeA > 0.01f) {
        drawOval(Ink.copy(alpha = eyeA), Offset(bc.x - u * 1.0f, bc.y - u * 0.55f), Size(u * 2f, u * 1.1f), style = Stroke(1.6f * g.d))
        drawCircle(Ink.copy(alpha = eyeA), u * 0.34f, bc)
    }
}

private fun DrawScope.drawBar(tm: TextMeasurer, g: Geo, a: Float, t: Float, idx: Int, n: Int) {
    val dy = (1f - a) * 30f * g.d
    for (k in 3 downTo 1) drawRoundRect(Color.Black.copy(alpha = 0.035f * a), Offset(g.barL - k * 2f * g.d, g.barT + dy + k * 2f * g.d),
        Size(g.barR - g.barL + k * 4f * g.d, g.barB - g.barT), CornerRadius(18f * g.d))
    drawRoundRect(Color.White.copy(alpha = a), Offset(g.barL, g.barT + dy), Size(g.barR - g.barL, g.barB - g.barT), CornerRadius(16f * g.d))
    val cy = g.barCy + dy
    val ink = Ink.copy(alpha = a)
    // menu icon
    drawLine(ink, Offset(0.085f * g.w, cy - 2.5f * g.d), Offset(0.085f * g.w + 0.045f * g.w, cy - 2.5f * g.d), 1.5f * g.d)
    drawLine(ink, Offset(0.085f * g.w, cy + 2.5f * g.d), Offset(0.085f * g.w + 0.045f * g.w, cy + 2.5f * g.d), 1.5f * g.d)
    val tiny = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 6.5.sp, letterSpacing = 0.6.sp, color = ink)
    drawText(tm, "EXPLORE", Offset(0.15f * g.w, cy - 4.5f * g.d), tiny)
    // slider
    drawLine(ink.copy(alpha = 0.7f * a), Offset(g.trackL, cy), Offset(g.trackR, cy), 1f * g.d)
    for (ex in listOf(g.trackL, g.trackR)) for (sy in listOf(-8f, 8f)) drawRect(ink, Offset(ex - 1.4f * g.d, cy + sy * g.d - 1.4f * g.d), Size(2.8f * g.d, 2.8f * g.d))
    val tx = g.trackL + (g.trackR - g.trackL) * t.coerceIn(0f, 1f)
    drawRect(ink, Offset(tx - 1.1f * g.d, cy - 9f * g.d), Size(2.2f * g.d, 18f * g.d))
    val num = if (n == 0) 0 else idx + 1
    drawText(tm, "${num.toString().padStart(2, '0')} x", Offset(0.855f * g.w, cy - 4.5f * g.d), tiny)
}

/** Full-screen "analysis" HUD: typed text, dot-matrix number, small readouts. */
private fun DrawScope.drawHud(
    tm: TextMeasurer, an: Analysis, file: File, idx: Int, n: Int, ht: Float, time: Float,
    w: Float, h: Float, topInset: Float, d: Float, a: Float
) {
    if (a <= 0.01f) return
    val txt = if (an.topLum > 135f) Ink else Color.White
    val big = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 1.2.sp, lineHeight = 19.sp, color = txt.copy(alpha = a))
    val deg = ((file.nameWithoutExtension.toLongOrNull() ?: 0L) % 360).toInt()
    val l1 = typed("CALCULATE", ht - 0.1f, 0.045f, time)
    val l2 = typed("D_AZIMU", ht - 0.1f - 9 * 0.045f, 0.045f, time)
    val l3 = typed("TH: $deg\u00B0", ht - 0.1f - 16 * 0.045f, 0.045f, time)
    drawText(tm, "$l1\n$l2\n$l3", Offset(0.07f * w, topInset + 0.045f * h), big)
    // dot-matrix number (counts up, then flickers slightly)
    val v = if (ht < 0.6f) 0 else (an.avgLum * ss(0.6f, 1.6f, ht)).roundToInt()
    val flick = if (ht > 1.7f && ((time * 6f).toInt() % 9 == 0)) 0.55f else 1f
    drawDotText("+${v.toString().padStart(2, '0')}", Offset(0.06f * w, 0.665f * h), 3.4f * d,
        Color.White.copy(alpha = 0.95f * a * flick), Color.White.copy(alpha = 0.16f * a), true)
    // small readouts
    val tiny = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 6.5.sp, letterSpacing = 0.7.sp, lineHeight = 9.sp, color = Color.White.copy(alpha = 0.9f * a))
    val blink = if ((time * 2f).toInt() % 2 == 0) "_" else " "
    drawText(tm, "ANALYZING_SURFACE$blink\nBIOME ...", Offset(0.065f * w, 0.793f * h), tiny)
    drawText(tm, "TARGET_ID: ${(idx + 1).toString().padStart(4, '0')}\nAREA: ${an.w}X${an.h}\nFRAME: $idx/$n",
        Offset(0.70f * w, 0.33f * h), tiny.copy(color = txt.copy(alpha = 0.75f * a)))
}

private fun typed(text: String, t: Float, perChar: Float, time: Float): String {
    if (t <= 0f) return ""
    val k = (t / perChar).toInt()
    if (k >= text.length + 2) return text
    val glyphs = "#%&@$/<>?*+="
    val sb = StringBuilder()
    for (i in text.indices) {
        sb.append(
            when {
                i < k -> text[i]
                i < k + 2 && text[i] != ' ' -> glyphs[((time * 40f).toInt() + i * 7) % glyphs.length]
                else -> ' '
            }
        )
    }
    return sb.toString()
}

/** Green scan: a band sweeps down the photo; vegetation-coloured pixels glow green and the band glitches sideways. */
private fun DrawScope.drawScan(an: Analysis, photo: ImageBitmap?, t: Float, w: Float, h: Float, d: Float, k: Float) {
    if (t < 1.4f) return
    val cycle = 5.2f
    val ts = ((t - 1.4f) % cycle + cycle) % cycle
    val dur = 2.2f
    if (ts > dur) return
    val p = ts / dur
    val bandH = 0.30f * h
    val cy = lerpf(-0.1f * h, 1.1f * h, p)
    val top = cy - bandH / 2f
    val weight = sin(p * 3.1415927f)
    // green glow on vegetation inside the band
    clipRect(0f, max(0f, top), w, min(h, top + bandH)) {
        drawImage(an.green, IntOffset.Zero, IntSize(an.green.width, an.green.height), IntOffset.Zero, IntSize(w.roundToInt(), h.roundToInt()),
            alpha = 0.9f * weight * k, filterQuality = FilterQuality.Low)
    }
    // sideways glitch strips of the photo inside the band
    if (photo != null) {
        val strips = 10; val sh = bandH / strips
        for (j in 0 until strips) {
            val sy = top + j * sh
            if (sy + sh < 0f || sy > h) continue
            val noise = sin((t * 23f) + j * 12.9898f) * 43758.547f
            val dx = ((noise - noise.toInt()) - 0.5f) * 26f * d * weight * k
            clipRect(0f, max(0f, sy), w, min(h, sy + sh)) {
                translate(dx, 0f) { drawCover(photo, 0f, 0f, w, h, 1f) }
            }
        }
    }
}
