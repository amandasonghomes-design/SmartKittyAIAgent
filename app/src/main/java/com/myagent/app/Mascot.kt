package com.myagent.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min

// Palette for the dreamy light-blue look
val SkyInk = Color(0xFF1B2B45)        // text
val SkyLine = Color(0xFF6FB3E8)       // outlines and grid
private val SkyTop = Color(0xFFE6F5FF)
private val SkyMid = Color(0xFFCBE8FF)
private val SkyLow = Color(0xFFDDF0FF)

enum class KittyFace { CALM, BLINK, SMILE }

/**
 * Small kitty badge for the top bar: detailed vector art (see res/drawable/kitty_*.xml)
 * that blinks and smiles, with twinkling sparkles around it.
 */
/** The animated kitty face: blinks, smiles every few seconds, bobs, with orbiting sparkles. */
@Composable
fun KittyFaceImage(boxSize: Dp, busy: Boolean, modifier: Modifier = Modifier) {
    var face by remember { mutableStateOf(KittyFace.CALM) }
    LaunchedEffect(Unit) {
        while (true) {
            face = KittyFace.CALM; delay(2600)
            face = KittyFace.BLINK; delay(140)
            face = KittyFace.CALM; delay(1500)
            face = KittyFace.SMILE; delay(1400)
        }
    }
    val t = rememberInfiniteTransition(label = "kitty")
    val bob by t.animateFloat(
        initialValue = -1.5f, targetValue = 1.5f,
        animationSpec = infiniteRepeatable(tween(if (busy) 600 else 1800), RepeatMode.Reverse),
        label = "bob",
    )
    val spin by t.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing)),
        label = "spin",
    )
    val twinkle by t.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "twinkle",
    )
    Box(modifier.size(boxSize).offset(y = (bob * boxSize.value / 88f).dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2, size.height / 2)
            val r = size.minDimension * 0.47f
            for (k in 0 until 4) {
                val a = Math.toRadians((spin + k * 90).toDouble())
                val p = Offset(c.x + (r * cos(a)).toFloat(), c.y + (r * sin(a)).toFloat())
                val alpha = if (k % 2 == 0) twinkle else 1.3f - twinkle
                drawSparkle(p, size.minDimension * (if (k % 2 == 0) 0.07f else 0.05f),
                    Color(0xFFFFD24D).copy(alpha = alpha.coerceIn(0f, 1f)))
            }
        }
        Image(
            painter = painterResource(
                when (face) {
                    KittyFace.CALM -> R.drawable.cat_calm
                    KittyFace.BLINK -> R.drawable.cat_blink
                    KittyFace.SMILE -> R.drawable.cat_smile
                }
            ),
            contentDescription = "Kitty",
            modifier = Modifier.size(boxSize * 0.91f),
        )
    }
}

/** Compact kitty badge shown above the chat once a conversation has started. */
@Composable
fun KittyMascot(name: String, status: String, busy: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = Color(0xFFCFE9FF).copy(alpha = 0.85f),
        border = BorderStroke(1.dp, Color(0xFFA9D4F7)),
    ) {
        Row(
            Modifier.padding(start = 4.dp, end = 22.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KittyFaceImage(88.dp, busy)
            Column {
                Text(name, color = SkyInk, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 24.sp)
                Text(status, color = Color(0xFF3D7BB8), fontSize = 13.sp, lineHeight = 16.sp)
            }
        }
    }
}

/** Big kitty with its name badge underneath, for the start page. */
@Composable
fun KittyHero(name: String, status: String, busy: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        KittyFaceImage(170.dp, busy)
        Surface(
            modifier = Modifier.offset(y = (-18).dp),
            shape = RoundedCornerShape(32.dp),
            color = Color.White.copy(alpha = 0.75f),
            border = BorderStroke(1.dp, Color(0xFFDDEBFA)),
        ) {
            Column(
                Modifier.padding(horizontal = 30.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(name, color = DeepNavy, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp)
                Text(status, color = Color(0xFF3D6E9E), fontSize = 15.sp, lineHeight = 20.sp)
            }
        }
    }
}

val DeepNavy = Color(0xFF13306B)

/** Soft, dreamy start-page backdrop: pale blue with a lavender hint and flowing light waves. */
@Composable
fun DreamyBackground(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        drawRect(Brush.verticalGradient(listOf(Color(0xFFF3F8FF), Color(0xFFE6F0FC), Color(0xFFDDEAFB))))
        listOf(
            Triple(0.85f, 0.05f, 0.6f) to Color(0x33C9C2F5),   // lavender glow, top right
            Triple(0.10f, 0.30f, 0.55f) to Color(0x40FFFFFF),
            Triple(0.50f, 0.95f, 0.70f) to Color(0x40C7E2FB),
        ).forEach { (o, col) ->
            val c = Offset(w * o.first, h * o.second)
            drawCircle(Brush.radialGradient(listOf(col, Color.Transparent), c, w * o.third), w * o.third, c)
        }
        // flowing waves near the bottom
        fun wave(y0: Float, amp: Float, alpha: Float, stroke: Float) {
            val p = Path().apply {
                moveTo(-w * 0.1f, h * y0)
                cubicTo(w * 0.25f, h * (y0 - amp), w * 0.55f, h * (y0 + amp), w * 1.1f, h * (y0 - amp * 0.6f))
            }
            drawPath(p, Color.White.copy(alpha = alpha), style = Stroke(stroke))
        }
        wave(0.74f, 0.06f, 0.9f, 3.dp.toPx())
        wave(0.78f, 0.08f, 0.6f, 2.dp.toPx())
        wave(0.83f, 0.05f, 0.5f, 6.dp.toPx())
        val fill = Path().apply {
            moveTo(0f, h * 0.86f)
            cubicTo(w * 0.3f, h * 0.80f, w * 0.65f, h * 0.92f, w, h * 0.84f)
            lineTo(w, h); lineTo(0f, h); close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(Color(0x66FFFFFF), Color(0x22D6E8FB)), h * 0.8f, h))
    }
}

/** A four-point twinkle star. */
private fun DrawScope.drawSparkle(center: Offset, r: Float, color: Color) {
    val p = Path().apply {
        moveTo(center.x, center.y - r)
        quadraticBezierTo(center.x, center.y, center.x + r, center.y)
        quadraticBezierTo(center.x, center.y, center.x, center.y + r)
        quadraticBezierTo(center.x, center.y, center.x - r, center.y)
        quadraticBezierTo(center.x, center.y, center.x, center.y - r)
        close()
    }
    drawPath(p, color)
}

/** Dreamy light-blue sci-fi backdrop: soft sky, glowing orbs, stars and a perspective grid floor. */
@Composable
fun SciFiBackground(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "sky")
    val twinkle by t.animateFloat(
        initialValue = 0.35f, targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Reverse),
        label = "twinkle",
    )
    Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        drawRect(Brush.verticalGradient(listOf(SkyTop, SkyMid, SkyLow)))

        // Glowing orbs
        listOf(
            Triple(0.15f, 0.18f, 0.35f), Triple(0.88f, 0.10f, 0.28f),
            Triple(0.80f, 0.48f, 0.40f), Triple(0.10f, 0.62f, 0.30f),
        ).forEach { (x, y, r) ->
            val c = Offset(w * x, h * y)
            drawCircle(
                Brush.radialGradient(listOf(Color.White.copy(alpha = 0.55f), Color(0x00BFE6FF)), c, w * r),
                radius = w * r, center = c,
            )
        }

        // A faint planet ring
        drawOval(
            SkyLine.copy(alpha = 0.18f),
            Offset(w * 0.55f, h * 0.22f), Size(w * 0.55f, h * 0.06f),
            style = Stroke(2.dp.toPx()),
        )
        drawCircle(Color.White.copy(alpha = 0.45f), w * 0.07f, Offset(w * 0.82f, h * 0.25f))

        // Stars
        val stars = listOf(
            0.08f to 0.06f, 0.32f to 0.12f, 0.55f to 0.05f, 0.72f to 0.16f, 0.93f to 0.30f,
            0.20f to 0.36f, 0.45f to 0.28f, 0.66f to 0.40f, 0.05f to 0.45f, 0.38f to 0.50f,
        )
        stars.forEachIndexed { i, (x, y) ->
            val a = if (i % 2 == 0) twinkle else 1.25f - twinkle
            drawCircle(Color.White.copy(alpha = a.coerceIn(0f, 1f)), 2.2.dp.toPx(), Offset(w * x, h * y))
        }

        // Perspective grid floor
        val horizon = h * 0.62f
        val grid = SkyLine.copy(alpha = 0.22f)
        val stroke = 1.dp.toPx()
        for (i in -10..10) {
            drawLine(grid, Offset(w / 2 + i * w * 0.02f, horizon), Offset(w / 2 + i * w * 0.22f, h), stroke)
        }
        var y = horizon
        var gap = 6.dp.toPx()
        while (y < h) {
            drawLine(grid, Offset(0f, y), Offset(w, y), stroke)
            y += gap
            gap *= 1.35f
        }
        drawLine(Color.White.copy(alpha = 0.7f), Offset(0f, horizon), Offset(w, horizon), 2.dp.toPx())
    }
}
