package com.myagent.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sin

/** Bottom panel shown during voice conversation: status, live words, glowing orb, and a close button. */
@Composable
fun VoicePanel(vm: AgentViewModel) {
    val state = vm.voiceState
    val status = when {
        vm.waitingForApproval -> "Tap a button above to answer"
        state == AgentViewModel.VoiceState.LISTENING -> "Listening…"
        state == AgentViewModel.VoiceState.SPEAKING -> "Speaking… tap the orb to interrupt"
        else -> "Thinking… tap the orb to stop"
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        shape = RoundedCornerShape(34.dp),
        color = Color.White.copy(alpha = 0.8f),
        border = BorderStroke(1.dp, Color(0xFFDDEBFA)),
    ) {
        Box(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(status, color = DeepNavy, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(
                    vm.voicePartial,
                    color = Color(0xFF3D6E9E),
                    fontStyle = FontStyle.Italic,
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 56.dp).heightIn(min = 18.dp),
                )
                VoiceOrb(state, vm.voiceLevel, onTap = { vm.tapOrb() })
            }
            // Close (end voice mode)
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 12.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF13306B))
                    .clickable { vm.stopVoiceMode() },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(14.dp)) {
                    val w = 2.2.dp.toPx()
                    drawLine(Color.White, Offset.Zero, Offset(size.width, size.height), w, StrokeCap.Round)
                    drawLine(Color.White, Offset(size.width, 0f), Offset(0f, size.height), w, StrokeCap.Round)
                }
            }
        }
    }
}

/** A glossy blue orb: swells with your voice, breathes while thinking, pulses while speaking. */
@Composable
fun VoiceOrb(state: AgentViewModel.VoiceState, level: Float, onTap: () -> Unit) {
    val t = rememberInfiniteTransition(label = "orb")
    val phase by t.animateFloat(
        0f, (2 * Math.PI).toFloat(),
        infiniteRepeatable(tween(if (state == AgentViewModel.VoiceState.SPEAKING) 900 else 2600, easing = LinearEasing)),
        label = "phase",
    )
    val swirl by t.animateFloat(0f, 360f, infiniteRepeatable(tween(7000, easing = LinearEasing)), label = "swirl")
    val voice by animateFloatAsState(if (state == AgentViewModel.VoiceState.LISTENING) level else 0f, tween(120), label = "voice")
    val scale = when (state) {
        AgentViewModel.VoiceState.LISTENING -> 0.9f + 0.3f * voice + 0.02f * sin(phase)
        AgentViewModel.VoiceState.SPEAKING -> 0.95f + 0.07f * sin(phase)
        else -> 0.88f + 0.04f * sin(phase)
    }
    val hover by t.animateFloat(-3f, 3f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "hover")
    Box(
        Modifier
            .size(72.dp)
            .offset(y = hover.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(72.dp)) {
            val c = Offset(size.width / 2, size.height / 2)
            val r = size.minDimension / 2 * 0.78f * scale
            // soft outer glow
            drawCircle(
                Brush.radialGradient(listOf(Color(0x556F8FFF), Color.Transparent), c, r * 1.35f),
                r * 1.35f, c,
            )
            // orb body
            drawCircle(
                Brush.radialGradient(
                    listOf(Color(0xFFE9F1FF), Color(0xFF8FB0FF), Color(0xFF4F63E6), Color(0xFF3443C9)),
                    Offset(c.x - r * 0.25f, c.y - r * 0.35f), r * 1.45f,
                ),
                r, c,
            )
            // drifting cloud band
            rotate(swirl, c) {
                drawOval(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, Color.White.copy(alpha = 0.75f), Color.Transparent),
                        c.x - r, c.x + r,
                    ),
                    Offset(c.x - r * 0.9f, c.y - r * 0.12f), Size(r * 1.8f, r * 0.42f),
                )
            }
            // glossy highlight
            drawOval(
                Color.White.copy(alpha = 0.55f),
                Offset(c.x - r * 0.55f, c.y - r * 0.75f), Size(r * 0.7f, r * 0.32f),
            )
        }
        // smiling kitty (eyes closed) in the middle, growing and shrinking with the orb
        Image(
            painter = painterResource(R.drawable.cat_blink),
            contentDescription = "Kitty",
            modifier = Modifier
                .size(46.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale },
        )
    }
}

/** White sound-wave bars for the "talk" button. */
@Composable
fun WaveIcon() = Canvas(Modifier.size(24.dp)) {
    val heights = listOf(0.35f, 0.75f, 1f, 0.6f, 0.3f)
    val gap = size.width / (heights.size + 1)
    heights.forEachIndexed { i, h ->
        val x = gap * (i + 1)
        val half = size.height * 0.42f * h
        drawLine(Color.White, Offset(x, size.height / 2 - half), Offset(x, size.height / 2 + half), 2.6.dp.toPx(), StrokeCap.Round)
    }
}
