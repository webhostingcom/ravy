package com.ravy.assistant.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ravy.assistant.core.RavyState
import kotlin.math.cos
import kotlin.math.sin

/**
 * A real (not decorative-only) wireframe-sphere orb. `amplitude` (0f..1f) is
 * real mic RMS while listening; while speaking it's a synthetic animated
 * pulse (true per-sample TTS waveform amplitude isn't implemented — that's
 * a genuinely more involved audio-processing feature, flagged here rather
 * than silently approximated as something it isn't). [state] comes directly
 * from RavyCore's real pipeline (core/RavyCore.kt) — this orb never invents
 * a state the engine didn't actually report. [serious] overrides the
 * palette to the red/dark Serious Mode look, persisted and real
 * (RavySettings.isSeriousMode), not just a color filter with no state behind it.
 */
@Composable
fun VoiceOrb(
    amplitude: Float,
    state: RavyState = RavyState.IDLE,
    serious: Boolean = false,
    modifier: Modifier = Modifier
) {
    val infinite = rememberInfiniteTransition(label = "orbPhase")

    val phase by infinite.animateFloat(
        initialValue = 0f, targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(if (serious) 5000 else 9000, easing = LinearEasing)),
        label = "phase"
    )
    val breathing by infinite.animateFloat(
        initialValue = 0.94f, targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(if (serious) 1400 else 2200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathing"
    )
    // Synthetic pulse for states with no real waveform to drive off (thinking/executing),
    // so the orb still visibly moves to show something real is happening.
    val syntheticPulse by infinite.animateFloat(
        initialValue = 0.2f, targetValue = 0.6f,
        animationSpec = infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse),
        label = "syntheticPulse"
    )

    val effectiveAmp = when (state) {
        RavyState.THINKING, RavyState.EXECUTING -> syntheticPulse
        else -> amplitude
    }.coerceIn(0f, 1f)

    val scale = breathing * (1f + effectiveAmp * 0.18f)
    val wobbleStrength = (0.06f + effectiveAmp * 0.22f) * (if (serious) 1.6f else 1f)
    val pinkBoost = effectiveAmp

    // State tint blended on top of the base palette — real feedback per
    // RavyCore's actual reported state, not decoration.
    val (tintColor, tintStrength) = when (state) {
        RavyState.THINKING -> Color(0xFFFFC94A) to 0.35f   // amber: processing
        RavyState.EXECUTING -> Color(0xFF4AD9FF) to 0.4f   // cyan: touching a real Android API
        RavyState.SUCCESS -> Color(0xFF4AFF8F) to 0.45f    // green: real success
        RavyState.ERROR -> Color(0xFFFF4A4A) to 0.5f       // red: real failure
        else -> Color.Transparent to 0f
    }

    Box(modifier = modifier.size(240.dp)) {
        Canvas(modifier = Modifier.size(240.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = (size.minDimension / 2.6f) * scale

            val latitudes = 9
            val longitudes = 12
            val segments = 28

            fun baseColor(t: Float, u: Float): Color {
                return if (serious) {
                    val dark = Color(0xFF1A0000)
                    val red = Color(0xFFB00020)
                    lerpColor(dark, red, (t * 0.5f + u * 0.5f).coerceIn(0f, 1f))
                } else {
                    val blue = Color(0xFF3B82F6)
                    val purple = Color(0xFF9333EA)
                    val pink = Color(0xFFEC4899)
                    val vertMix = lerpColor(blue, purple, t)
                    lerpColor(vertMix, pink, (u * (0.4f + pinkBoost * 0.6f)).coerceIn(0f, 1f))
                }
            }

            fun project(lat: Double, lon: Double): Triple<Offset, Float, Color> {
                var x = cos(lat) * sin(lon)
                var y = sin(lat)
                var z = cos(lat) * cos(lon)

                val noise = (sin(lon * 3 + phase) * cos(lat * 2 - phase) * wobbleStrength).toFloat()
                val r = (1f + noise)
                x *= r; y *= r; z *= r

                val rotLon = lon + phase * 0.15
                val rx = (x * cos(rotLon) + z * sin(rotLon))
                val rz = (-x * sin(rotLon) + z * cos(rotLon))

                val screenX = center.x + (rx * radius).toFloat()
                val screenY = center.y + (y * radius).toFloat()
                val depth = ((rz + 1) / 2).toFloat()

                val t = ((y + 1) / 2).toFloat()
                val u = ((rx + 1) / 2).toFloat()
                val base = baseColor(t, u)
                val finalColor = if (tintStrength > 0f) lerpColor(base, tintColor, tintStrength) else base

                return Triple(Offset(screenX, screenY), depth, finalColor)
            }

            val glowColor = if (serious) Color(0xFFB00020) else Color(0xFF9333EA)
            drawCircle(
                color = glowColor.copy(alpha = 0.10f + effectiveAmp * 0.12f),
                radius = radius * 1.35f,
                center = center
            )

            for (li in 0 until longitudes) {
                val lon = (li.toDouble() / longitudes) * 2 * Math.PI
                var prev: Offset? = null
                for (si in 0..segments) {
                    val lat = -Math.PI / 2 + (si.toDouble() / segments) * Math.PI
                    val (pt, depth, color) = project(lat, lon)
                    if (prev != null) {
                        drawLine(color = color.copy(alpha = 0.18f + depth * 0.5f), start = prev, end = pt, strokeWidth = 1f)
                    }
                    prev = pt
                }
            }

            for (ki in 1 until latitudes) {
                val lat = -Math.PI / 2 + (ki.toDouble() / latitudes) * Math.PI
                var prev: Offset? = null
                for (si in 0..segments) {
                    val lon = (si.toDouble() / segments) * 2 * Math.PI
                    val (pt, depth, color) = project(lat, lon)
                    if (prev != null) {
                        drawLine(color = color.copy(alpha = 0.18f + depth * 0.5f), start = prev, end = pt, strokeWidth = 1f)
                    }
                    prev = pt
                }
            }

            val dotCount = 40
            for (i in 0 until dotCount) {
                val a = (i.toDouble() / dotCount) * 2 * Math.PI + phase * 0.3
                val distance = radius * (1.15f + (i % 5) * 0.08f)
                val dx = center.x + (cos(a) * distance).toFloat()
                val dy = center.y + (sin(a) * distance * 0.9f).toFloat()
                val dotColor = if (serious) Color(0xFFB00020) else if (i % 3 == 0) Color(0xFFEC4899) else Color(0xFF60A5FA)
                drawCircle(
                    color = dotColor.copy(alpha = 0.35f + effectiveAmp * 0.4f),
                    radius = 1.5f + (i % 3),
                    center = Offset(dx, dy)
                )
            }
        }
    }
}

private fun lerpColor(a: Color, b: Color, t: Float): Color {
    val tt = t.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * tt,
        green = a.green + (b.green - a.green) * tt,
        blue = a.blue + (b.blue - a.blue) * tt,
        alpha = 1f
    )
}
