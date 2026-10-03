package com.example.syntaxcam

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color as AColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val id = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ConfigScreen(id) { cfg ->
                    WidgetStore.save(this, id, cfg)
                    SyntaxWidgetProvider.update(this, AppWidgetManager.getInstance(this), id)
                    setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                    finish()
                }
            }
        }
    }
}

private val swatches = listOf(
    0xFFFFFFFF, 0xFFFF5A4F, 0xFFFF8A3D, 0xFFFFC857, 0xFF8CF0C4,
    0xFF3DDCFF, 0xFF5C7CFF, 0xFF9B5CFF, 0xFFFF4FD8, 0xFF111111
).map { it.toInt() }

@Composable
private fun ConfigScreen(id: Int, onAdd: (WidgetConfig) -> Unit) {
    val ctx = LocalContext.current
    val saved = remember { WidgetStore.load(ctx, id) }
    var theme by remember { mutableIntStateOf(saved.theme) }
    var colorA by remember { mutableIntStateOf(saved.colorA) }
    var colorB by remember { mutableIntStateOf(saved.colorB) }
    var round by remember { mutableFloatStateOf(saved.roundness) }
    var label by remember { mutableStateOf(saved.showLabel) }
    var shuffle by remember { mutableStateOf(saved.shuffle) }
    val size = remember { SyntaxWidgetProvider.sizePx(ctx, AppWidgetManager.getInstance(ctx), id, 520) }
    val photo = remember { WidgetRenderer.pickPhoto(ctx, false, 700) }
    val preview = remember(theme, colorA, colorB, round, label) {
        WidgetRenderer.render(size.first, size.second, WidgetConfig(theme, colorA, colorB, round, label, shuffle), photo).asImageBitmap()
    }

    Column(
        Modifier.fillMaxSize().background(Color(0xFF09090D)).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Widget style", color = Color.White, fontSize = 26.sp)
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color(0xFF15151B)).padding(18.dp), contentAlignment = Alignment.Center) {
            Image(preview, null, Modifier.fillMaxWidth(0.8f).aspectRatio(size.first / size.second.toFloat()), contentScale = ContentScale.Fit)
        }

        Cap("THEME")
        ThemeSeg(listOf("Glass", "White", "Dark", "Gradient"), theme) { theme = it }
        when (theme) {
            THEME_GLASS -> ColorPick("GLASS TINT", colorA) { colorA = it }
            THEME_GRADIENT -> {
                ColorPick("FROM", colorA) { colorA = it }
                ColorPick("TO", colorB) { colorB = it }
            }
            else -> Text("Solid themes use pure white or dark only.", color = Color(0xFF7C7C88), fontSize = 12.sp)
        }

        Cap("EDGE ROUNDNESS")
        Slider(round, { round = it }, valueRange = 0.06f..0.32f,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.DarkGray))
        Text("Default (0.17) matches your reference tile.", color = Color(0xFF7C7C88), fontSize = 12.sp)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Cap("SHOW DATE + COUNT"); Spacer(Modifier.weight(1f)); Switch(label, { label = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cap("SHUFFLE PHOTOS"); Spacer(Modifier.weight(1f)); Switch(shuffle, { shuffle = it })
        }
        Text("Resize the widget on your home screen to change its size.", color = Color(0xFF7C7C88), fontSize = 12.sp)

        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White)
                .clickable { onAdd(WidgetConfig(theme, colorA, colorB, round, label, shuffle)) }.padding(vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) { Text("Add widget", color = Color.Black, fontSize = 16.sp) }
    }
}

@Composable
private fun Cap(t: String) = Text(t, color = Color(0xFF8A8A96), fontSize = 11.sp, letterSpacing = 1.5.sp)

@Composable
private fun ThemeSeg(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF1C1C24)).padding(3.dp)) {
        options.forEachIndexed { i, s ->
            val on = i == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).background(if (on) Color(0xFF2F3340) else Color.Transparent)
                    .clickable { onSelect(i) }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) { Text(s, fontSize = 13.sp, color = if (on) Color.White else Color(0xFF9A9AA6)) }
        }
    }
}

@Composable
private fun ColorPick(title: String, color: Int, onChange: (Int) -> Unit) {
    var hex by remember(color) { mutableStateOf(String.format("%06X", color and 0xFFFFFF)) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Cap(title)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            swatches.forEach { s ->
                Box(
                    Modifier.size(28.dp).clip(CircleShape).background(Color(s))
                        .border(if (s == color) 2.dp else 1.dp, if (s == color) Color(0xFF2F7BFF) else Color(0x44FFFFFF), CircleShape)
                        .clickable { onChange(s) }
                )
            }
        }
        OutlinedTextField(
            value = hex,
            onValueChange = { v ->
                hex = v.trim().removePrefix("#").take(6).uppercase()
                if (hex.length == 6) runCatching { onChange(AColor.parseColor("#$hex")) }
            },
            label = { Text("Custom hex") }, singleLine = true, modifier = Modifier.width(180.dp)
        )
    }
}
