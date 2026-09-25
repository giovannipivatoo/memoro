// SPDX-License-Identifier: BSD-2-Clause
package io.github.giovannipivatoo.memoro.ui

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.giovannipivatoo.memoro.R
import io.github.giovannipivatoo.memoro.data.Outcome

/** Plays the user's Luo Xiaohei pet sprites without moving the surrounding controls. */
@Composable
internal fun MemoroPet(outcome: Outcome?, modifier: Modifier = Modifier) {
    val motionEnabled = petMotionEnabled()
    val sprite = ImageBitmap.imageResource(R.drawable.luoxiaohei_sprites)
    val reaction = remember { Animatable(1f) }
    var lastOutcome by remember { mutableStateOf<Outcome?>(null) }
    val phase = if (motionEnabled) {
        val idle = rememberInfiniteTransition(label = "petIdle")
        idle.animateFloat(0f, 1f, infiniteRepeatable(tween(5600, easing = LinearEasing)), label = "petBlink")
    } else remember { mutableFloatStateOf(0f) }
    val mood = when (outcome) {
        Outcome.CORRECT -> "festeggia"
        Outcome.PARTIAL, Outcome.WRONG -> "incoraggia"
        Outcome.UNGRADABLE -> "pensieroso"
        null -> "attende"
    }
    LaunchedEffect(outcome, motionEnabled) {
        val changed = outcome != lastOutcome
        lastOutcome = outcome
        reaction.snapTo(1f)
        if (outcome != null && motionEnabled && changed) {
            reaction.snapTo(0f)
            reaction.animateTo(1f, tween(if (outcome == Outcome.CORRECT) 1050 else 1500, easing = LinearEasing))
        }
    }
    Box(modifier.size(96.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().semantics { contentDescription = "Gattino $mood" }) {
            // Eight 192 × 208 cells per row. Read animation state during drawing, not layout.
            val (row, frames) = when (outcome) {
                Outcome.CORRECT -> 4 to 5
                Outcome.PARTIAL -> 3 to 4
                Outcome.WRONG -> 5 to 8
                Outcome.UNGRADABLE -> 6 to 6
                null -> 0 to 6
            }
            val reacting = motionEnabled && outcome != null && reaction.value < 1f
            val staticReaction = !motionEnabled && outcome != null
            val column = when {
                reacting -> (reaction.value * frames).toInt().coerceIn(0, frames - 1)
                staticReaction -> if (outcome == Outcome.CORRECT || outcome == Outcome.PARTIAL) 2 else 1
                else -> when ((phase.value * 5600).toInt()) {
                    in 2200..2329 -> 1
                    in 2330..2439 -> 2
                    in 2440..2539 -> 3
                    in 2540..2659 -> 4
                    in 2660..2799 -> 5
                    else -> 0
                }
            }
            val scale = minOf(size.width / 192f, size.height / 208f)
            val target = IntSize((192 * scale).toInt(), (208 * scale).toInt())
            drawImage(sprite, srcOffset = IntOffset(column * 192, (if (reacting || staticReaction) row else 0) * 208),
                srcSize = IntSize(192, 208), dstOffset = IntOffset(((size.width - target.width) / 2).toInt(), ((size.height - target.height) / 2).toInt()),
                dstSize = target)
        }
        if (outcome == Outcome.CORRECT) Icon(Icons.Rounded.AutoAwesome, null,
            Modifier.align(Alignment.TopEnd).size(21.dp), tint = MaterialTheme.colorScheme.tertiary)
        if (outcome == Outcome.UNGRADABLE) Icon(Icons.Rounded.MoreHoriz, null,
            Modifier.align(Alignment.TopEnd).size(24.dp), tint = MaterialTheme.colorScheme.primary)
        if (outcome == Outcome.PARTIAL || outcome == Outcome.WRONG) Icon(Icons.Rounded.FavoriteBorder, null,
            Modifier.align(Alignment.TopEnd).size(19.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

/** Stop the idle loop in the background and react immediately to Android's reduced-motion setting. */
@Composable
private fun petMotionEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun allowed() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    var motion by remember { mutableStateOf(allowed()) }
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(resolver, lifecycle) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { motion = allowed() }
        }
        val lifecycleObserver = LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (resumed) motion = allowed()
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        lifecycle.addObserver(lifecycleObserver)
        onDispose { resolver.unregisterContentObserver(observer); lifecycle.removeObserver(lifecycleObserver) }
    }
    return motion && resumed
}
