package com.example.syntaxcam

import android.os.Build
import android.view.HapticFeedbackConstants as H
import android.view.View

class Haptics(private val view: View) {
    var enabled = true
    private fun go(c: Int) { if (enabled) view.performHapticFeedback(c) }
    private fun api30(c30: Int, fallback: Int) = go(if (Build.VERSION.SDK_INT >= 30) c30 else fallback)

    fun tick() = go(H.CLOCK_TICK)                       // effect taps, slider steps
    fun select() = go(H.VIRTUAL_KEY)                    // buttons
    fun heavy() = go(H.LONG_PRESS)                      // shuffle
    fun confirm() = api30(H.CONFIRM, H.LONG_PRESS)      // success / begin
    fun gestureStart() = api30(H.GESTURE_START, H.VIRTUAL_KEY)
    fun gestureEnd() = api30(H.GESTURE_END, H.CLOCK_TICK)
    fun toggle(on: Boolean) = if (on) confirm() else select()
    fun shutter() { heavy(); view.postDelayed({ confirm() }, 70L) }  // double-knock
}
