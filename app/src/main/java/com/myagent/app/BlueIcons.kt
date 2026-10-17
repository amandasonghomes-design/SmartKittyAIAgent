package com.myagent.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp

/** Small blue line icons, drawn by hand so nothing in the app is gray. */
val IconBlue = Color(0xFF2F7FD1)
private val IconFill = Color(0xFFD6ECFF)

@Composable
private fun BlueIcon(enabled: Boolean, draw: DrawScope.(Color, Float) -> Unit) {
    val c = if (enabled) IconBlue else Color(0xFF9CC7EE)
    Canvas(Modifier.size(26.dp)) {
        scale(size.minDimension / 24f, pivot = Offset.Zero) { draw(c, 2f) }
    }
}

@Composable
fun CameraIcon(enabled: Boolean = true) = BlueIcon(enabled) { c, w ->
    drawRoundRect(IconFill, Offset(3f, 7f), Size(18f, 13f), CornerRadius(3f))
    drawRoundRect(c, Offset(3f, 7f), Size(18f, 13f), CornerRadius(3f), style = Stroke(w))
    drawRoundRect(c, Offset(8f, 4f), Size(7f, 3.5f), CornerRadius(1.2f), style = Stroke(w))
    drawCircle(Color.White, 3.8f, Offset(12f, 13.5f))
    drawCircle(c, 3.8f, Offset(12f, 13.5f), style = Stroke(w))
    drawCircle(c, 1f, Offset(17.5f, 10f))
}

@Composable
fun MicIcon(enabled: Boolean = true) = BlueIcon(enabled) { c, w ->
    drawRoundRect(IconFill, Offset(9f, 3f), Size(6f, 11f), CornerRadius(3f))
    drawRoundRect(c, Offset(9f, 3f), Size(6f, 11f), CornerRadius(3f), style = Stroke(w))
    val arc = Path().apply {
        moveTo(5.5f, 11f)
        cubicTo(5.5f, 19f, 18.5f, 19f, 18.5f, 11f)
    }
    drawPath(arc, c, style = Stroke(w, cap = StrokeCap.Round))
    drawLine(c, Offset(12f, 17f), Offset(12f, 21f), w, StrokeCap.Round)
    drawLine(c, Offset(8.5f, 21f), Offset(15.5f, 21f), w, StrokeCap.Round)
}

@Composable
fun SpeakerIcon(on: Boolean) = BlueIcon(true) { c, w ->
    val body = Path().apply {
        moveTo(3f, 9f); lineTo(7f, 9f); lineTo(12f, 4.5f); lineTo(12f, 19.5f); lineTo(7f, 15f); lineTo(3f, 15f); close()
    }
    drawPath(body, IconFill)
    drawPath(body, c, style = Stroke(w))
    if (on) {
        listOf(3.5f, 7f).forEach { r ->
            drawArc(c, -50f, 100f, false, Offset(13f - r, 12f - r), Size(r * 2, r * 2), style = Stroke(w, cap = StrokeCap.Round))
        }
    } else {
        drawLine(c, Offset(15.5f, 9f), Offset(21f, 15f), w, StrokeCap.Round)
        drawLine(c, Offset(21f, 9f), Offset(15.5f, 15f), w, StrokeCap.Round)
    }
}

@Composable
fun MailIcon() = BlueIcon(true) { c, w ->
    drawRoundRect(c, Offset(3f, 5.5f), Size(18f, 13f), CornerRadius(2.5f), style = Stroke(w))
    val v = Path().apply { moveTo(3.8f, 7f); lineTo(12f, 13f); lineTo(20.2f, 7f) }
    drawPath(v, c, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

@Composable
fun CalendarIcon() = BlueIcon(true) { c, w ->
    drawRoundRect(c, Offset(3.5f, 5f), Size(17f, 15.5f), CornerRadius(2.5f), style = Stroke(w))
    drawLine(c, Offset(3.5f, 10f), Offset(20.5f, 10f), w)
    drawLine(c, Offset(8f, 3f), Offset(8f, 7f), w, StrokeCap.Round)
    drawLine(c, Offset(16f, 3f), Offset(16f, 7f), w, StrokeCap.Round)
}

@Composable
fun HouseIcon() = BlueIcon(true) { c, w ->
    val house = Path().apply {
        moveTo(4f, 11f); lineTo(12f, 4f); lineTo(20f, 11f); lineTo(20f, 20f); lineTo(4f, 20f); close()
    }
    drawPath(house, IconFill)
    drawPath(house, c, style = Stroke(w, join = StrokeJoin.Round))
    drawRoundRect(c, Offset(10f, 14f), Size(4f, 6f), CornerRadius(1f), style = Stroke(w))
}

@Composable
fun ChevronIcon() = Canvas(Modifier.size(20.dp)) {
    scale(size.minDimension / 24f, pivot = Offset.Zero) {
        val p = Path().apply { moveTo(9f, 5f); lineTo(16f, 12f); lineTo(9f, 19f) }
        drawPath(p, IconBlue, style = Stroke(2.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** White paper plane for the round send button. */
@Composable
fun SendPlaneIcon() = Canvas(Modifier.size(24.dp)) {
    scale(size.minDimension / 24f, pivot = Offset.Zero) {
        val p = Path().apply {
            moveTo(3f, 4f); lineTo(21.5f, 12f); lineTo(3f, 20f); lineTo(6.5f, 12f); close()
        }
        drawPath(p, Color.White)
        drawLine(Color(0xFF7DBDF5), Offset(6.5f, 12f), Offset(13f, 12f), 1.8f, StrokeCap.Round)
    }
}

/** White rounded square for the Stop button. */
@Composable
fun StopIcon() = Canvas(Modifier.size(18.dp)) {
    drawRoundRect(Color.White, cornerRadius = CornerRadius(size.minDimension * 0.22f))
}
