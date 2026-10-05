package com.example.syntaxcam

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
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

@Composable
private fun ConfigScreen(id: Int, onAdd: (WidgetConfig) -> Unit) {
    val ctx = LocalContext.current
    val saved = remember { WidgetStore.load(ctx, id) }
    var theme by remember { mutableIntStateOf(saved.theme) }
    var round by remember { mutableFloatStateOf(saved.roundness) }
    var label by remember { mutableStateOf(saved.showLabel) }
    var shuffle by remember { mutableStateOf(saved.shuffle) }
    val size = remember { SyntaxWidgetProvider.sizePx(ctx, AppWidgetManager.getInstance(ctx), id, 520) }
    val photo = remember { WidgetRenderer.pickPhoto(ctx, false, 700) }
    val preview = remember(theme, round, label) {
        WidgetRenderer.render(size.first, size.second, WidgetConfig(theme, round, label, shuffle), photo).asImageBitmap()
    }

    Column(
        Modifier.fillMaxSize().background(Color(0xFF09090D)).displayCutoutPadding().statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text("Widget", color = Color.White, fontSize = 26.sp)
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Color(0xFF15151B)).padding(22.dp), contentAlignment = Alignment.Center) {
            Image(preview, null, Modifier.fillMaxWidth(0.8f).aspectRatio(size.first / size.second.toFloat()), contentScale = ContentScale.Fit)
        }

        Cap("STYLE")
        TwoWay("White", "Dark", theme == THEME_DARK) { theme = if (it) THEME_DARK else THEME_LIGHT }

        Cap("CORNERS")
        Slider(round, { round = it }, valueRange = 0.06f..0.32f,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color(0x44FFFFFF)))
        Text("Default follows your reference tile.", color = Color(0xFF7C7C88), fontSize = 12.sp)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Cap("DATE + COUNT"); Spacer(Modifier.weight(1f)); Switch(label, { label = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cap("SHUFFLE PHOTOS"); Spacer(Modifier.weight(1f)); Switch(shuffle, { shuffle = it })
        }
        Text("Resize the widget on your home screen to change its size.", color = Color(0xFF7C7C88), fontSize = 12.sp)

        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White)
                .clickable { onAdd(WidgetConfig(theme, round, label, shuffle)) }.padding(vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) { Text("Add widget", color = Color.Black, fontSize = 16.sp) }
    }
}

@Composable
private fun Cap(t: String) = Text(t, color = Color(0xFF8A8A96), fontSize = 11.sp, letterSpacing = 1.5.sp)

@Composable
private fun TwoWay(a: String, b: String, second: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF1C1C24)).padding(3.dp)) {
        listOf(a to false, b to true).forEach { (label, isSecond) ->
            val on = second == isSecond
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).background(if (on) Color(0xFF2F3340) else Color.Transparent)
                    .clickable { onChange(isSecond) }.padding(vertical = 11.dp),
                contentAlignment = Alignment.Center
            ) { Text(label, fontSize = 14.sp, color = if (on) Color.White else Color(0xFF9A9AA6)) }
        }
    }
}
