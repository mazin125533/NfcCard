package io.github.mazin125533.nfccard

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** 相手が URL を読み取ったときの振動（「ブブッ」と短く2回） */
object Haptics {
    private val PATTERN_MS = longArrayOf(0, 60, 80, 60)   // 待ち, 振動, 休み, 振動
    private val AMPLITUDES = intArrayOf(0, 255, 0, 255)

    fun sent(context: Context) {
        val vibrator = vibrator(context) ?: return
        if (!vibrator.hasVibrator()) return
        val effect = if (vibrator.hasAmplitudeControl()) {
            VibrationEffect.createWaveform(PATTERN_MS, AMPLITUDES, -1)
        } else {
            VibrationEffect.createWaveform(PATTERN_MS, -1)
        }
        vibrator.vibrate(effect)
    }

    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
}
