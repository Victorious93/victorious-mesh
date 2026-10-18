package com.victorious.mesh

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.victorious.mesh.ui.theme.Charcoal
import com.victorious.mesh.ui.theme.NeonCyan
import com.victorious.mesh.ui.theme.NeonMagenta
import com.victorious.mesh.ui.theme.SoftWhite
import com.victorious.mesh.ui.theme.VictoriousMeshTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VictoriousMeshTheme {
                MeshStatusScreen(status = "Mesh: not connected")
            }
        }
    }
}

@Composable
fun MeshStatusScreen(status: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Charcoal),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            GlowRing(modifier = Modifier.size(220.dp))
            Spacer(Modifier.height(32.dp))
            Text(
                text = "Victorious Mesh",
                color = SoftWhite,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(text = status, color = NeonCyan, fontSize = 16.sp)
        }
    }
}

/** Placeholder cyan-to-magenta glowing ring; will reflect live tunnel state later. */
@Composable
fun GlowRing(modifier: Modifier = Modifier) {
    val sweep = Brush.sweepGradient(listOf(NeonCyan, NeonMagenta, NeonCyan))
    Canvas(modifier = modifier) {
        val base = size.minDimension
        // Soft outer glow: stacked translucent strokes of decreasing width.
        listOf(48f to 0.06f, 32f to 0.10f, 20f to 0.18f).forEach { (w, a) ->
            drawCircle(
                brush = sweep,
                radius = base / 2 - 28.dp.toPx(),
                alpha = a,
                style = Stroke(width = w.dp.toPx()),
            )
        }
        // Crisp core ring.
        drawCircle(
            brush = sweep,
            radius = base / 2 - 28.dp.toPx(),
            style = Stroke(width = 6.dp.toPx()),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF121216)
@Composable
private fun MeshStatusPreview() {
    VictoriousMeshTheme { MeshStatusScreen(status = "Mesh: not connected") }
}
