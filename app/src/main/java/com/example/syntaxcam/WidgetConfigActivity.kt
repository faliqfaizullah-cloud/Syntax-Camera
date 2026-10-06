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
    var showDate by remember { mutableStateOf(saved.showDate) }
    var shuffle by remember { mutableStateOf(saved.shuffle) }
    val m = remember { SyntaxWidgetProvider.metrics(ctx, AppWidgetManager.getInstance(ctx), id, 520) }
    val photo = remember { WidgetRenderer.pickPhoto(ctx, false, 700) }
    val preview = remember(showDate) {
        WidgetRenderer.render(m.w, m.h, WidgetConfig(showDate, shuffle), photo, CORNER_DP * m.pxPerDp).asImageBitmap()
    }

    Column(
        Modifier.fillMaxSize().background(Color(0xFF09090D)).displayCutoutPadding().statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text("Widget", color = Color.White, fontSize = 26.sp)
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Color(0xFF15151B)).padding(22.dp), contentAlignment = Alignment.Center) {
            Image(preview, null, Modifier.fillMaxWidth(0.6f).aspectRatio(m.w / m.h.toFloat()), contentScale = ContentScale.Fit)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cap("SHOW DATE"); Spacer(Modifier.weight(1f)); Switch(showDate, { showDate = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cap("SHUFFLE PHOTOS"); Spacer(Modifier.weight(1f)); Switch(shuffle, { shuffle = it })
        }
        Text("2×2 photo widget with 28dp rounded corners.", color = Color(0xFF7C7C88), fontSize = 12.sp)
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White)
                .clickable { onAdd(WidgetConfig(showDate, shuffle)) }.padding(vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) { Text("Add widget", color = Color.Black, fontSize = 16.sp) }
    }
}

@Composable
private fun Cap(t: String) = Text(t, color = Color(0xFF8A8A96), fontSize = 11.sp, letterSpacing = 1.5.sp)
