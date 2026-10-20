// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
// Victorious Mesh: tiny markdown parser for the bundled user manual.

package com.tailscale.ipn.ui.util

/** Asset path of the bundled user manual (copied from docs/MANUAL.md at build time). */
object ManualAsset {
  const val PATH = "docs/MANUAL.md"
}

sealed class MdSpan {
  data class Text(val text: String) : MdSpan()

  data class Bold(val text: String) : MdSpan()

  data class Italic(val text: String) : MdSpan()

  data class Code(val text: String) : MdSpan()

  data class Link(val text: String, val url: String) : MdSpan()
}

sealed class MdBlock {
  data class Heading(val level: Int, val text: String) : MdBlock()

  data class Paragraph(val spans: List<MdSpan>) : MdBlock()

  data class BulletList(val items: List<List<MdSpan>>) : MdBlock()

  data class OrderedList(val items: List<List<MdSpan>>) : MdBlock()

  data class Quote(val spans: List<MdSpan>) : MdBlock()

  data class Code(val text: String) : MdBlock()

  data class Table(val headers: List<String>, val rows: List<List<String>>) : MdBlock()

  data class Image(val alt: String, val path: String) : MdBlock()

  data object HorizontalRule : MdBlock()
}

object ManualMarkdown {
  fun parse(source: String): List<MdBlock> {
    val lines = source.replace("\r\n", "\n").split('\n')
    val blocks = mutableListOf<MdBlock>()
    var i = 0
    while (i < lines.size) {
      val line = lines[i]
      val trimmed = line.trim()
      when {
        trimmed.isEmpty() -> i++
        trimmed.startsWith("```") -> {
          val body = StringBuilder()
          i++
          while (i < lines.size && !lines[i].trim().startsWith("```")) {
            if (body.isNotEmpty()) body.append('\n')
            body.append(lines[i])
            i++
          }
          if (i < lines.size) i++
          blocks.add(MdBlock.Code(body.toString()))
        }
        headingLevel(trimmed) > 0 -> {
          val level = headingLevel(trimmed)
          blocks.add(MdBlock.Heading(level, trimmed.drop(level).trim()))
          i++
        }
        trimmed == "***" || trimmed == "---" || trimmed == "___" -> {
          blocks.add(MdBlock.HorizontalRule)
          i++
        }
        trimmed.startsWith("![") -> {
          val img = parseImage(trimmed)
          if (img != null) blocks.add(img) else blocks.add(MdBlock.Paragraph(parseSpans(trimmed)))
          i++
        }
        trimmed.startsWith("> ") || trimmed == ">" -> {
          val text = buildString {
            while (i < lines.size && (lines[i].trimStart().startsWith(">") || lines[i].isBlank())) {
              val t = lines[i].trimStart()
              if (t.startsWith(">")) {
                if (isNotEmpty()) append(' ')
                append(t.removePrefix(">").trim())
              } else if (t.isEmpty() && i + 1 < lines.size && lines[i + 1].trimStart().startsWith(">")) {
                append('\n')
              } else {
                break
              }
              i++
            }
          }
          blocks.add(MdBlock.Quote(parseSpans(text)))
        }
        trimmed.startsWith("|") -> {
          val tableLines = mutableListOf<String>()
          while (i < lines.size && lines[i].trim().startsWith("|")) {
            tableLines.add(lines[i].trim())
            i++
          }
          parseTable(tableLines)?.let { blocks.add(it) }
        }
        trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
          val items = mutableListOf<List<MdSpan>>()
          while (i < lines.size && (lines[i].trim().startsWith("- ") || lines[i].trim().startsWith("* "))) {
            val t = lines[i].trim()
            items.add(parseSpans(t.substring(2)))
            i++
          }
          blocks.add(MdBlock.BulletList(items))
        }
        orderedPrefix(trimmed) != null -> {
          val items = mutableListOf<List<MdSpan>>()
          while (i < lines.size) {
            val p = orderedPrefix(lines[i].trim()) ?: break
            items.add(parseSpans(lines[i].trim().substring(p)))
            i++
          }
          blocks.add(MdBlock.OrderedList(items))
        }
        else -> {
          val text = StringBuilder(trimmed)
          i++
          while (i < lines.size) {
            val n = lines[i]
            val nt = n.trim()
            if (nt.isEmpty() ||
                nt.startsWith("#") ||
                nt.startsWith("|") ||
                nt.startsWith("```") ||
                nt.startsWith(">") ||
                nt.startsWith("- ") ||
                nt.startsWith("* ") ||
                nt.startsWith("![") ||
                orderedPrefix(nt) != null ||
                nt == "---" ||
                nt == "***" ||
                nt == "___") {
              break
            }
            text.append(' ').append(nt)
            i++
          }
          val para = text.toString()
          if (para.startsWith("*") && para.endsWith("*") && !para.startsWith("**")) {
            // Caption line such as *Screenshot pending.*
            blocks.add(MdBlock.Paragraph(listOf(MdSpan.Italic(para.trim('*').trim()))))
          } else {
            blocks.add(MdBlock.Paragraph(parseSpans(para)))
          }
        }
      }
    }
    return blocks
  }

  private fun headingLevel(trimmed: String): Int {
    var n = 0
    while (n < trimmed.length && trimmed[n] == '#') n++
    return if (n in 1..6 && n < trimmed.length && trimmed[n] == ' ') n else 0
  }

  private fun orderedPrefix(trimmed: String): Int? {
    var i = 0
    while (i < trimmed.length && trimmed[i].isDigit()) i++
    if (i == 0 || i >= trimmed.length || trimmed[i] != '.') return null
    if (i + 1 >= trimmed.length || trimmed[i + 1] != ' ') return null
    return i + 2
  }

  private fun parseImage(trimmed: String): MdBlock.Image? {
    val match = Regex("""!\[([^\]]*)\]\(([^)]+)\)""").find(trimmed) ?: return null
    return MdBlock.Image(match.groupValues[1], match.groupValues[2])
  }

  private fun parseTable(lines: List<String>): MdBlock.Table? {
    if (lines.size < 2) return null
    fun cells(line: String): List<String> {
      return line.trim().trim('|').split('|').map { it.trim() }
    }
    val headers = cells(lines[0])
    val rows =
        lines.drop(2).map { cells(it) }.filter { row ->
          row.any { it.isNotBlank() }
        }
    return MdBlock.Table(headers, rows)
  }

  internal fun parseSpans(raw: String): List<MdSpan> {
    val out = mutableListOf<MdSpan>()
    var i = 0
    val s = raw
    fun flushText(from: Int, to: Int) {
      if (to > from) out.add(MdSpan.Text(s.substring(from, to)))
    }
    while (i < s.length) {
      when {
        s.startsWith("`", i) -> {
          val end = s.indexOf('`', i + 1)
          if (end < 0) {
            out.add(MdSpan.Text(s.substring(i)))
            break
          }
          out.add(MdSpan.Code(s.substring(i + 1, end)))
          i = end + 1
        }
        s.startsWith("**", i) -> {
          val end = s.indexOf("**", i + 2)
          if (end < 0) {
            out.add(MdSpan.Text(s.substring(i)))
            break
          }
          out.add(MdSpan.Bold(s.substring(i + 2, end)))
          i = end + 2
        }
        s.startsWith("[", i) -> {
          val link = Regex("""\[([^\]]+)\]\(([^)]+)\)""").matchAt(s, i)
          if (link != null) {
            out.add(MdSpan.Link(link.groupValues[1], link.groupValues[2]))
            i += link.value.length
          } else {
            out.add(MdSpan.Text("["))
            i++
          }
        }
        s.startsWith("*", i) && (i + 1 < s.length && s[i + 1] != ' ') -> {
          val end = s.indexOf('*', i + 1)
          if (end < 0) {
            out.add(MdSpan.Text(s.substring(i)))
            break
          }
          out.add(MdSpan.Italic(s.substring(i + 1, end)))
          i = end + 1
        }
        else -> {
          val next =
              listOf(
                      s.indexOf('`', i).takeIf { it >= 0 },
                      s.indexOf("**", i).takeIf { it >= 0 },
                      s.indexOf('[', i).takeIf { it >= 0 },
                      s.indexOf('*', i).takeIf { it >= 0 },
                  )
                  .filterNotNull()
                  .minOrNull() ?: s.length
          flushText(i, next)
          if (next == i) {
            out.add(MdSpan.Text(s.substring(i, i + 1)))
            i++
          } else {
            i = next
          }
        }
      }
    }
    return out.ifEmpty { listOf(MdSpan.Text("")) }
  }
}
