package com.lamdaai.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The "lamdλ" brand mark, both colors sampled directly from the wordmark
 * artwork: navy #161753 for the "lamd" letters, a navy→cyan gradient
 * (#171262 → #1DEAFB) for the λ chevron itself.
 */
private val LamdaNavy = Color(0xFF161753)
private val LambdaGradientTop = Color(0xFF171262)
private val LambdaGradientBottom = Color(0xFF1DEAFB)

/** Draws just the λ chevron (this is also the standalone app icon's mark). */
@Composable
fun LambdaMark(modifier: Modifier = Modifier, sizeDp: Dp = 64.dp) {
    Canvas(modifier = modifier.size(sizeDp)) {
        val w = size.width
        val h = size.height
        val strokeWidth = w * 0.24f
        val gradient = Brush.linearGradient(
            colors = listOf(LambdaGradientTop, LambdaGradientBottom),
            start = Offset(w * 0.3f, 0f),
            end = Offset(w, h),
        )
        // Two diagonal strokes meeting at the apex — a short left leg and a
        // longer right leg, matching the wordmark's asymmetric λ.
        val path = Path().apply {
            moveTo(w * 0.42f, h * 0.02f)
            lineTo(w * 0.08f, h * 0.98f)
            moveTo(w * 0.42f, h * 0.02f)
            lineTo(w * 0.92f, h * 0.98f)
        }
        drawPath(
            path = path,
            brush = gradient,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

@Composable
private fun WordmarkLetter(letter: String, modifier: Modifier = Modifier) {
    Text(
        text = letter,
        color = LamdaNavy,
        fontWeight = FontWeight.Black,
        fontSize = 44.sp,
        modifier = modifier,
    )
}

/**
 * Plays once per cold start: the λ mark assembles into the full "lamdλ"
 * wordmark (letters emerging from behind it), holds, disassembles back to a
 * lone centered λ, then rises to the top as [content] (the real app —
 * HomeScreen and its NavHost) fades in underneath, like a menu unfolding
 * below the mark.
 *
 * [isContentReady] gates the final "rise" phase — if the first-launch model
 * asset install is still running, the assembled/disassembled show simply
 * holds a beat longer (with a slow pulse) rather than cutting to a
 * half-loaded app.
 */
@Composable
fun IntroSequence(
    isContentReady: Boolean,
    content: @Composable () -> Unit,
) {
    var showIntro by remember { mutableFloatStateOf(1f) } // 1f = showing, 0f = done (avoids extra enum wiring)

    val scrimAlpha = remember { Animatable(1f) }
    val lambdaOffsetX = remember { Animatable(0f) }
    val lambdaOffsetY = remember { Animatable(0f) }
    val lambdaScale = remember { Animatable(1.7f) }
    val contentAlpha = remember { Animatable(0f) }
    val contentOffsetY = remember { Animatable(48f) }

    // l, a, m, d — each has its own horizontal slot offset + fade, so they
    // can animate independently "emerging from" and "behind" the λ mark.
    val letters = listOf("l", "a", "m", "d")
    val letterOffsets = remember { letters.map { Animatable(0f) } }
    val letterAlphas = remember { letters.map { Animatable(0f) } }

    // Final slot spacing, in dp, relative to the λ mark's resting x — mirrors
    // the wordmark: four letters to the left, λ anchoring the right edge.
    val slotSpacing = 40f
    val letterSlots = listOf(-4, -3, -2, -1).map { it * slotSpacing }
    val lambdaRestX = 58f

    LaunchedEffect(Unit) {
        // Phase 1 — assemble: λ shrinks to normal size and slides right into
        // the wordmark's layout while l/a/m/d fade in from behind it,
        // staggered so they visibly "emerge."
        coroutineScope {
            launch { lambdaScale.animateTo(1f, tween(650, easing = EaseOutBack)) }
            launch { lambdaOffsetX.animateTo(lambdaRestX, tween(700, easing = EaseInOutCubic)) }
            letters.indices.forEach { i ->
                launch {
                    delay(i * 70L)
                    launch { letterOffsets[i].animateTo(letterSlots[i], tween(550, easing = EaseInOutCubic)) }
                    letterAlphas[i].animateTo(1f, tween(400))
                }
            }
        }

        delay(500) // hold the full "lamdλ" wordmark on screen

        // Phase 2 — disassemble: letters slide back behind the λ and fade,
        // reverse stagger order (rightmost letter first) for a "sucked back
        // in" feel, λ returns to center with a small pop.
        coroutineScope {
            letters.indices.reversed().forEach { i ->
                launch {
                    delay((letters.size - 1 - i) * 60L)
                    launch { letterOffsets[i].animateTo(0f, tween(450, easing = EaseInOutCubic)) }
                    letterAlphas[i].animateTo(0f, tween(300))
                }
            }
            launch { lambdaOffsetX.animateTo(0f, tween(600, easing = EaseInOutCubic)) }
            launch {
                lambdaScale.animateTo(1.25f, tween(300, easing = EaseOutBack))
                lambdaScale.animateTo(1f, tween(250))
            }
        }

        // Gate: wait for first-launch model install (if still running)
        // before revealing the real app — a slow breathing pulse keeps the
        // λ mark alive rather than freezing on a static frame.
        while (!isContentReady) {
            lambdaScale.animateTo(1.08f, tween(500, easing = EaseInOutCubic))
            lambdaScale.animateTo(1f, tween(500, easing = EaseInOutCubic))
        }

        // Phase 3 — rise: λ moves up and shrinks into a header-sized mark
        // while the scrim clears and the real app fades/slides up beneath
        // it, "menu appearing below."
        coroutineScope {
            launch { lambdaOffsetY.animateTo(-190f, tween(700, easing = EaseInOutCubic)) }
            launch { lambdaScale.animateTo(0.55f, tween(700, easing = EaseInOutCubic)) }
            launch { scrimAlpha.animateTo(0f, tween(500, easing = EaseInOutCubic)) }
            launch {
                delay(150)
                launch { contentAlpha.animateTo(1f, tween(550)) }
                contentOffsetY.animateTo(0f, tween(550, easing = EaseOutBack))
            }
        }

        delay(150)
        showIntro = 0f
    }

    BoxWithConstraints(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
        // The real app, fading/sliding in underneath during phase 3, stays
        // mounted (not re-created) once revealed — no flash/pop on handoff.
        Box(
            modifier = Modifier
                .alpha(contentAlpha.value)
                .offset(y = contentOffsetY.value.dp)
        ) {
            content()
        }

        if (showIntro > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = scrimAlpha.value))
            )
            Box(modifier = Modifier.align(Alignment.Center)) {
                letters.indices.forEach { i ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .offset(x = letterOffsets[i].value.dp, y = lambdaOffsetY.value.dp)
                            .alpha(letterAlphas[i].value)
                    ) {
                        WordmarkLetter(letters[i])
                    }
                }
                LambdaMark(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(x = lambdaOffsetX.value.dp, y = lambdaOffsetY.value.dp)
                        .scale(lambdaScale.value),
                    sizeDp = 56.dp,
                )
            }
        }
    }
}
