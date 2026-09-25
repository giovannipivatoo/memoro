// SPDX-License-Identifier: BSD-2-Clause
package io.github.giovannipivatoo.memoro.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.giovannipivatoo.memoro.R
import io.github.giovannipivatoo.memoro.data.Outcome

/** One shared mascot; reactions never move the surrounding controls. */
@Composable
internal fun MemoroPet(outcome: Outcome?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val reducedMotion = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    val reaction = remember { Animatable(0f) }
    val lift = with(LocalDensity.current) { 7.dp.toPx() }
    val mood = when (outcome) {
        Outcome.CORRECT -> "festeggia"
        Outcome.PARTIAL, Outcome.WRONG -> "incoraggia"
        Outcome.UNGRADABLE -> "pensieroso"
        null -> "attende"
    }
    LaunchedEffect(outcome, reducedMotion) {
        reaction.snapTo(0f)
        if (outcome != null && !reducedMotion) {
            reaction.animateTo(1f, tween(180))
            reaction.animateTo(0f, tween(260))
        }
    }
    Box(modifier.size(96.dp), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.memoro_cat), "Gattino $mood",
            Modifier.fillMaxSize().graphicsLayer {
                translationY = if (outcome == Outcome.CORRECT) -lift * reaction.value else 0f
                rotationZ = if (outcome == Outcome.CORRECT) 0f else -7f * reaction.value
            })
        if (outcome == Outcome.CORRECT) Icon(Icons.Rounded.AutoAwesome, null,
            Modifier.align(Alignment.TopEnd).size(21.dp), tint = MaterialTheme.colorScheme.tertiary)
        if (outcome == Outcome.UNGRADABLE) Icon(Icons.Rounded.MoreHoriz, null,
            Modifier.align(Alignment.TopEnd).size(24.dp), tint = MaterialTheme.colorScheme.primary)
        if (outcome == Outcome.PARTIAL || outcome == Outcome.WRONG) Icon(Icons.Rounded.FavoriteBorder, null,
            Modifier.align(Alignment.TopEnd).size(19.dp), tint = MaterialTheme.colorScheme.primary)
    }
}
