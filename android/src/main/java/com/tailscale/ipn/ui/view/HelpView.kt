// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
// Victorious Mesh: in-app Help screen that renders the bundled user manual.

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.theme.link
import com.tailscale.ipn.ui.util.ManualAsset
import com.tailscale.ipn.ui.util.ManualMarkdown
import com.tailscale.ipn.ui.util.MdBlock
import com.tailscale.ipn.ui.util.MdSpan

@Composable
fun HelpView(onBack: BackNavigation) {
  val context = LocalContext.current
  val markdown =
      remember {
        runCatching {
              context.assets.open(ManualAsset.PATH).bufferedReader().use { it.readText() }
            }
            .getOrDefault("")
      }
  val blocks = remember(markdown) { ManualMarkdown.parse(markdown) }
  val loadFailed = markdown.isEmpty()

  Scaffold(topBar = { Header(titleRes = R.string.help_title, onBack = onBack) }) { innerPadding ->
    LazyColumn(
        modifier =
            Modifier.fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
      if (loadFailed) {
        item {
          Text(
              stringResource(R.string.help_load_error),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.error,
          )
        }
      }
      itemsIndexed(blocks, key = { index, _ -> index }) { _, block -> MdBlockView(block) }
      item { Spacer(Modifier.height(24.dp)) }
    }
  }
}

@Composable
private fun MdBlockView(block: MdBlock) {
  when (block) {
    is MdBlock.Heading -> {
      val style =
          when (block.level) {
            1 -> MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold)
            2 -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
            else -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
          }
      val color =
          if (block.level <= 2) MaterialTheme.colorScheme.primary
          else MaterialTheme.colorScheme.onSurface
      val comingSoon = block.text.contains("Coming soon", ignoreCase = true)
      Text(
          text = block.text,
          style = style,
          color = if (comingSoon) MaterialTheme.colorScheme.secondary else color,
          modifier = Modifier.padding(top = if (block.level == 1) 8.dp else 20.dp, bottom = 8.dp),
      )
    }
    is MdBlock.Paragraph -> {
      Text(
          text = annotated(block.spans),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.padding(bottom = 10.dp),
      )
    }
    is MdBlock.BulletList -> {
      Column(modifier = Modifier.padding(bottom = 10.dp, start = 4.dp)) {
        block.items.forEach { item ->
          Row(modifier = Modifier.padding(bottom = 4.dp)) {
            Text("•", color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(16.dp))
            Text(
                text = annotated(item),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
          }
        }
      }
    }
    is MdBlock.OrderedList -> {
      Column(modifier = Modifier.padding(bottom = 10.dp, start = 4.dp)) {
        block.items.forEachIndexed { idx, item ->
          Row(modifier = Modifier.padding(bottom = 4.dp)) {
            Text(
                "${idx + 1}.",
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(24.dp),
            )
            Text(
                text = annotated(item),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
          }
        }
      }
    }
    is MdBlock.Quote -> {
      Box(
          modifier =
              Modifier.padding(bottom = 10.dp)
                  .fillMaxWidth()
                  .border(
                      width = 1.dp,
                      color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f),
                      shape = RoundedCornerShape(8.dp),
                  )
                  .background(
                      MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f),
                      RoundedCornerShape(8.dp),
                  )
                  .padding(12.dp),
      ) {
        Text(
            text = annotated(block.spans),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
      }
    }
    is MdBlock.Code -> {
      Text(
          text = block.text,
          style =
              MaterialTheme.typography.bodySmall.copy(
                  fontFamily = FontFamily.Monospace,
                  fontSize = 12.sp,
              ),
          color = MaterialTheme.colorScheme.onSurface,
          modifier =
              Modifier.padding(bottom = 10.dp)
                  .fillMaxWidth()
                  .background(
                      MaterialTheme.colorScheme.surfaceContainerHigh,
                      RoundedCornerShape(8.dp),
                  )
                  .padding(12.dp),
      )
    }
    is MdBlock.Table -> {
      TableView(block)
    }
    is MdBlock.Image -> {
      ImagePlaceholder(block)
    }
    is MdBlock.HorizontalRule -> {
      HorizontalDivider(
          modifier = Modifier.padding(vertical = 12.dp),
          color = MaterialTheme.colorScheme.outlineVariant,
      )
    }
  }
}

@Composable
private fun ImagePlaceholder(image: MdBlock.Image) {
  val isHeader = image.path.endsWith("header.svg", ignoreCase = true)
  if (isHeader) {
    Box(
        modifier =
            Modifier.fillMaxWidth()
                .height(140.dp)
                .background(Color(0xFF111215), RoundedCornerShape(12.dp))
                .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
      HeaderRing(modifier = Modifier.size(96.dp))
    }
    Spacer(Modifier.height(12.dp))
    return
  }
  Box(
      modifier =
          Modifier.fillMaxWidth()
              .padding(bottom = 4.dp)
              .height(96.dp)
              .border(
                  1.dp,
                  MaterialTheme.colorScheme.outline,
                  RoundedCornerShape(8.dp),
              )
              .background(
                  MaterialTheme.colorScheme.surfaceContainerLow,
                  RoundedCornerShape(8.dp),
              ),
      contentAlignment = Alignment.Center,
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Text(
          image.alt.ifBlank { stringResource(R.string.help_screenshot_pending) },
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
          stringResource(R.string.help_screenshot_pending),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
  Spacer(Modifier.height(8.dp))
}

@Composable
private fun HeaderRing(modifier: Modifier) {
  val cyan = Color(0xFF00E5FF)
  val magenta = Color(0xFFFF2BD6)
  Canvas(modifier) {
    val stroke = size.minDimension * 0.12f
    val brush = Brush.sweepGradient(listOf(cyan, magenta, cyan))
    drawCircle(
        brush = brush,
        radius = (size.minDimension - stroke) / 2f,
        style = Stroke(width = stroke),
    )
    drawCircle(color = cyan, radius = stroke * 0.55f)
  }
}

@Composable
private fun TableView(table: MdBlock.Table) {
  val scroll = rememberScrollState()
  Column(
      modifier =
          Modifier.padding(bottom = 12.dp)
              .horizontalScroll(scroll)
              .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
  ) {
    Row(
        modifier =
            Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
      table.headers.forEach { cell ->
        Text(
            cell,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(160.dp).padding(end = 8.dp),
        )
      }
    }
    table.rows.forEachIndexed { idx, row ->
      Row(
          modifier =
              Modifier.background(
                      if (idx % 2 == 0) Color.Transparent
                      else MaterialTheme.colorScheme.surfaceContainerLow
                  )
                  .padding(horizontal = 8.dp, vertical = 8.dp)
      ) {
        table.headers.indices.forEach { col ->
          Text(
              row.getOrElse(col) { "" },
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.width(160.dp).padding(end = 8.dp),
          )
        }
      }
    }
  }
}

@Composable
private fun annotated(spans: List<MdSpan>) = buildAnnotatedString {
  spans.forEach { span ->
    when (span) {
      is MdSpan.Text -> append(span.text)
      is MdSpan.Bold ->
          withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(span.text) }
      is MdSpan.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(span.text) }
      is MdSpan.Code ->
          withStyle(
              SpanStyle(
                  fontFamily = FontFamily.Monospace,
                  background = MaterialTheme.colorScheme.surfaceContainerHigh,
              )
          ) {
            append(span.text)
          }
      is MdSpan.Link ->
          withStyle(
              SpanStyle(
                  color = MaterialTheme.colorScheme.link,
                  textDecoration = TextDecoration.Underline,
              )
          ) {
            append(span.text)
          }
    }
  }
}
