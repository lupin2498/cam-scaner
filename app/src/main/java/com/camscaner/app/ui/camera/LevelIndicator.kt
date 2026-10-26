package com.camscaner.app.ui.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/** Gravity along the phone's axes, in g. */
private data class Gravity(val x: Float, val y: Float, val z: Float)

/**
 * Bubble level for shooting documents lying flat: a bubble drifts off the centre ring by the
 * phone's tilt and turns green when the phone is level. Hidden while the phone is held upright
 * (a wall or a screen is being photographed), and on devices without a sensor.
 */
@Composable
fun LevelIndicator(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var gravity by remember { mutableStateOf<Gravity?>(null) }

    DisposableEffect(Unit) {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val g = SensorManager.GRAVITY_EARTH
                // a little smoothing keeps the bubble from trembling with the hand
                val prev = gravity
                val nx = event.values[0] / g
                val ny = event.values[1] / g
                val nz = event.values[2] / g
                gravity = if (prev == null) Gravity(nx, ny, nz)
                else Gravity(prev.x * 0.7f + nx * 0.3f, prev.y * 0.7f + ny * 0.3f, prev.z * 0.7f + nz * 0.3f)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (manager != null && sensor != null) manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { manager?.unregisterListener(listener) }
    }

    val g = gravity ?: return
    // pointing the camera down: gravity runs along the screen normal
    if (g.z < 0.6f) return
    val tilt = hypot(g.x, g.y)
    val level = tilt < LEVEL_TOLERANCE
    val color = if (level) Color(0xFF3DDC97) else Color.White
    Canvas(modifier.size(64.dp)) {
        val r = size.minDimension / 2f
        val center = Offset(r, r)
        drawCircle(Color.Black.copy(alpha = 0.35f), r, center)
        drawCircle(color.copy(alpha = 0.9f), r * 0.42f, center, style = Stroke(2.dp.toPx()))
        // the bubble moves against the tilt, like in a real level; a quarter g reaches the rim
        val k = r / 0.25f
        val dx = (-g.x * k).coerceIn(-r * 0.8f, r * 0.8f)
        val dy = (g.y * k).coerceIn(-r * 0.8f, r * 0.8f)
        val bubble = if (hypot(dx, dy) > r * 0.8f) Offset(dx, dy) * (r * 0.8f / hypot(dx, dy)) else Offset(dx, dy)
        drawCircle(color, min(r * 0.3f, 10.dp.toPx()), center + bubble)
        if (!level) drawLine(color.copy(alpha = 0.5f), center, center + bubble, 1.dp.toPx())
        if (abs(dx) < 1f && abs(dy) < 1f) drawCircle(color, 2.dp.toPx(), center)
    }
}

/** Tilt (in g) up to which the phone counts as level: about 4.5 degrees. */
private const val LEVEL_TOLERANCE = 0.08f
