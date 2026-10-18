// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.drawscope.rotate
import com.tailscale.ipn.ui.theme.onBackgroundLogoDotDisabled
import com.tailscale.ipn.ui.theme.onBackgroundLogoDotEnabled
import com.tailscale.ipn.ui.theme.standaloneLogoDotDisabled
import com.tailscale.ipn.ui.theme.standaloneLogoDotEnabled
import com.tailscale.ipn.ui.util.set
import kotlin.concurrent.timer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// DotsMatrix represents the state of the progress indicator.
typealias DotsMatrix = List<List<Boolean>>

// The initial DotsMatrix that represents the Tailscale logo (T-shaped).
val logoDotsMatrix: DotsMatrix =
    listOf(
        listOf(false, false, false),
        listOf(true, true, true),
        listOf(false, true, false),
    )

// Victorious Mesh: the original dot-matrix logo is replaced by a neon
// cyan-to-magenta ring. (Function name kept to minimise upstream diff.)
@Composable
fun TailscaleLogoView(
    animated: Boolean = false,
    usesOnBackgroundColors: Boolean = false,
    modifier: Modifier,
) {
  val rotation =
      if (animated) {
        val t = rememberInfiniteTransition(label = "ring")
        t.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec =
                    infiniteRepeatable(tween<Float>(1600)),
                label = "ringRotation",
            )
            .value
      } else 0f
  val brush =
      androidx.compose.ui.graphics.Brush.sweepGradient(
          listOf(NeonCyan, NeonMagenta, NeonCyan))
  Canvas(modifier = modifier) {
    val stroke = size.minDimension * 0.12f
    rotate(rotation) {
      drawCircle(
          brush = brush,
          radius = (size.minDimension - stroke) / 2f,
          style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
      )
    }
    drawCircle(color = NeonCyan, radius = stroke * 0.55f)
  }
}

val NeonCyan = Color(0xFF00E5FF)
val NeonMagenta = Color(0xFFFF2BD6)

val gameOfLife: List<DotsMatrix> =
    listOf(
        listOf(
            listOf(false, true, true),
            listOf(true, false, true),
            listOf(false, false, true),
        ),
        listOf(
            listOf(false, true, true),
            listOf(false, false, true),
            listOf(false, true, false),
        ),
        listOf(
            listOf(false, true, true),
            listOf(false, false, false),
            listOf(false, false, true),
        ),
        listOf(
            listOf(false, false, true),
            listOf(false, true, false),
            listOf(false, false, false),
        ),
        listOf(
            listOf(false, true, false),
            listOf(false, false, false),
            listOf(false, false, false),
        ),
        listOf(
            listOf(false, false, false),
            listOf(false, false, true),
            listOf(false, false, false),
        ),
        listOf(
            listOf(false, false, false),
            listOf(false, false, false),
            listOf(false, false, false),
        ),
        listOf(
            listOf(false, false, true),
            listOf(false, false, false),
            listOf(false, false, false),
        ),
        listOf(
            listOf(false, false, false),
            listOf(false, false, false),
            listOf(true, false, false),
        ),
        listOf(listOf(false, false, false), listOf(false, false, false), listOf(true, true, false)),
        listOf(listOf(false, false, false), listOf(true, false, false), listOf(true, true, false)),
        listOf(listOf(false, false, false), listOf(true, true, false), listOf(false, true, false)),
        listOf(listOf(false, false, false), listOf(true, true, false), listOf(false, true, true)),
        listOf(listOf(false, false, false), listOf(true, true, true), listOf(false, false, true)),
        listOf(listOf(false, true, false), listOf(true, true, true), listOf(true, false, true)),
    )
