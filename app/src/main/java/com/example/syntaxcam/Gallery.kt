package com.example.syntaxcam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle as JTextStyle
import java.util.Locale

/** Photos taken in the app are kept here (app-private) so the gallery can show them by day. */
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
    }
    fun list(ctx: Context): List<File> =
        dir(ctx).listFiles()?.filter { it.extension == "jpg" }?.sortedByDescending { it.name } ?: emptyList()
    fun date(f: File): LocalDate =
        Instant.ofEpochMilli(f.nameWithoutExtension.toLongOrNull() ?: f.lastModified())
            .atZone(ZoneId.systemDefault()).toLocalDate()
}

private val Heavy = FontFamily.SansSerif

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val haptics = remember(view) { Haptics(view) }
    var files by remember { mutableStateOf(Shots.list(ctx)) }
    var month by remember { mutableStateOf(YearMonth.now()) }
    var open by remember { mutableStateOf<List<File>?>(null) }
    val byDay = remember(files) { files.groupBy { Shots.date(it) } }
    val today = remember { LocalDate.now() }
    BackHandler { if (open != null) open = null else onClose() }

    Box(Modifier.fillMaxSize().background(Color(0xFF09090D))) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 12.dp)
                .pointerInput(month) {
                    var dx = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dx = 0f },
                        onDragEnd = {
                            if (dx > 140f) { month = month.minusMonths(1); haptics.tick() }
                            else if (dx < -140f) { month = month.plusMonths(1); haptics.tick() }
                        },
                        onHorizontalDrag = { _, d -> dx += d }
                    )
                }
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill("✕") { haptics.select(); onClose() }
                Spacer(Modifier.weight(1f))
                Text("${files.size} photos", color = Color(0xFF7C7C88), fontSize = 12.sp)
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text("‹", color = Color(0xFF8A8A96), fontSize = 30.sp,
                    modifier = Modifier.clickable { month = month.minusMonths(1); haptics.tick() }.padding(horizontal = 16.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(month.month.getDisplayName(JTextStyle.FULL, Locale.getDefault()),
                        color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.ExtraBold, fontFamily = Heavy)
                    Text("${month.year}", color = Color(0xFF6E6E7A), fontSize = 12.sp, letterSpacing = 2.sp)
                }
                Text("›", color = Color(0xFF8A8A96), fontSize = 30.sp,
                    modifier = Modifier.clickable { month = month.plusMonths(1); haptics.tick() }.padding(horizontal = 16.dp))
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                listOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT").forEach {
                    Box(
                        Modifier.weight(1f).padding(horizontal = 2.5.dp).clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF14141A)).padding(vertical = 5.dp),
                        contentAlignment = Alignment.Center
                    ) { Text(it, color = Color(0xFF8A8A96), fontSize = 9.sp, letterSpacing = 0.5.sp) }
                }
            }
            Spacer(Modifier.height(6.dp))

            val blanks = month.atDay(1).dayOfWeek.value % 7
            val len = month.lengthOfMonth()
            val rows = (blanks + len + 6) / 7
            repeat(rows) { r ->
                Row(Modifier.fillMaxWidth()) {
                    repeat(7) { c ->
                        val day = r * 7 + c - blanks + 1
                        Box(Modifier.weight(1f).aspectRatio(0.82f).padding(2.5.dp)) {
                            if (day in 1..len) {
                                val date = month.atDay(day)
                                val shots = byDay[date]
                                DayCell(day, shots, date == today) {
                                    if (shots != null) { haptics.select(); open = shots }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))

            val inMonth = byDay.filterKeys { YearMonth.from(it) == month }.values.sumOf { it.size }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Chip("All  ${files.size}", true)
                Spacer(Modifier.width(8.dp))
                Chip("This month  $inMonth", false)
                Spacer(Modifier.weight(1f))
                if (month != YearMonth.now()) Chip("This Month  →", false) {
                    haptics.tick(); month = YearMonth.now()
                }
            }
            if (files.isEmpty()) {
                Spacer(Modifier.weight(1f))
                Text("Take a photo and it lands on today", Modifier.fillMaxWidth(),
                    color = Color(0xFF6E6E7A), fontSize = 14.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.weight(1f))
            }
        }

        open?.let { list ->
            val pager = rememberPagerState { list.size }
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                HorizontalPager(pager, Modifier.fillMaxSize()) { i ->
                    Thumb(list[i], Modifier.fillMaxSize(), ContentScale.Fit, 2000)
                }
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp)) {
                    Pill("✕") { haptics.select(); open = null }
                    Spacer(Modifier.weight(1f))
                    Pill("🗑") {
                        haptics.heavy()
                        list[pager.currentPage].delete()
                        files = Shots.list(ctx)
                        open = list.filter { it.exists() }.ifEmpty { null }
                    }
                }
                Text("${pager.currentPage + 1} / ${list.size}", Modifier.align(Alignment.BottomCenter)
                    .navigationBarsPadding().padding(bottom = 24.dp), color = Color(0xFFAAAAB5), fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun DayCell(day: Int, shots: List<File>?, isToday: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier.fillMaxSize().clip(shape).background(Color(0xFF131318))
            .clickable(enabled = shots != null, onClick = onClick)
    ) {
        if (shots != null) {
            Thumb(shots.first(), Modifier.fillMaxSize(), ContentScale.Crop)
            Box(Modifier.fillMaxSize().border(1.5.dp, Color(0xE6FFFFFF), shape))
            Text("$day", Modifier.padding(start = 7.dp, top = 5.dp), color = Color.White,
                fontSize = 12.sp, fontWeight = FontWeight.Bold)
            if (shots.size > 1) Box(
                Modifier.align(Alignment.BottomEnd).padding(5.dp).clip(CircleShape)
                    .background(Color(0xCC000000)).padding(horizontal = 6.dp, vertical = 2.dp)
            ) { Text("${shots.size}", color = Color.White, fontSize = 10.sp) }
        } else {
            Text("$day", Modifier.padding(start = 8.dp, top = 6.dp), color = Color(0xFFE8E8EE),
                fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        if (isToday) Box(Modifier.fillMaxSize().border(1.5.dp, Color(0xFF2F7BFF), shape))
    }
}

@Composable
private fun Thumb(file: File, modifier: Modifier, scale: ContentScale, maxDim: Int = 600) {
    val bmp by produceState<Bitmap?>(null, file) {
        value = withContext(Dispatchers.IO) {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, o)
            var s = 1
            while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= maxDim) s *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = s })
        }
    }
    bmp?.let { Image(it.asImageBitmap(), null, modifier, contentScale = scale) }
}

@Composable
private fun Pill(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(Color(0xFF17171D)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(label, color = Color.White, fontSize = 17.sp) }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: (() -> Unit)? = null) {
    Box(
        Modifier.clip(RoundedCornerShape(18.dp))
            .background(if (selected) Color(0xFF22222A) else Color(0xFF14141A))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) { Text(label, color = if (selected) Color.White else Color(0xFF9A9AA6), fontSize = 12.sp) }
}
