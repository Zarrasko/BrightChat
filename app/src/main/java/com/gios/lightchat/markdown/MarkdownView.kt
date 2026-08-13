package com.gios.lightchat.markdown

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Renders parsed markdown ([Block]s) as Compose. Kept separate from the parser so the
 * parser stays pure-Kotlin and testable. B&W-friendly: no syntax-highlighting colours,
 * just weight/monospace/dim/indent — the phone's panel is grayscale anyway.
 */

/** Inline spans → [AnnotatedString], applying bold/italic/monospace/link spans. */
fun buildMarkdownAnnotatedString(
    inlines: List<Inline>,
    contentColor: Color,
): AnnotatedString = buildAnnotatedString { emitMarkdown(inlines, contentColor) }

/** Recursive emit of inline spans into a builder. A top-level extension rather than a
 *  local function: a local `fun` inside `buildAnnotatedString` cannot see the builder
 *  receiver, but an extension on [AnnotatedString.Builder] always can. */
private fun AnnotatedString.Builder.emitMarkdown(list: List<Inline>, contentColor: Color) {
    for (inl in list) {
        when (inl) {
            is Inline.Text -> append(inl.text)
            is Inline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { emitMarkdown(inl.children, contentColor) }
            is Inline.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { emitMarkdown(inl.children, contentColor) }
            is Inline.Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(inl.text) }
            is Inline.Link -> withLink(
                LinkAnnotation.Url(inl.url, TextLinkStyles(SpanStyle(color = contentColor, textDecoration = TextDecoration.Underline))),
            ) { emitMarkdown(inl.children, contentColor) }
            is Inline.Image -> append(inl.alt.ifBlank { "image" })
        }
    }
}

@Composable
fun MarkdownView(
    blocks: List<Block>,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = LocalTextStyle.current,
    contentColor: Color = LocalContentColor.current,
    loadImage: (suspend (String) -> Bitmap?)? = null,
) {
    val dim = contentColor.copy(alpha = 0.65f)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (block in blocks) {
            when (block) {
                is Block.Paragraph -> MarkdownText(block.children, textStyle, contentColor)
                is Block.Heading -> {
                    val size = when (block.level) {
                        1 -> 20.sp
                        2 -> 17.sp
                        else -> 15.sp
                    }
                    MarkdownText(block.children, textStyle.copy(fontSize = size, fontWeight = FontWeight.Bold), contentColor)
                }
                is Block.Code -> CodeBlock(block.text, contentColor)
                is Block.Quote -> QuoteBlock(block.children, textStyle, contentColor)
                is Block.ListBlock -> ListBlockView(block, textStyle, contentColor)
                is Block.Image -> {
                    if (loadImage != null) MarkdownImage(block.url, block.alt, loadImage, contentColor)
                    else Text("[${block.alt.ifBlank { "image" }}]", color = dim, style = textStyle)
                }
                Block.Rule -> HorizontalDivider(color = dim)
            }
        }
    }
}

@Composable
private fun MarkdownText(inlines: List<Inline>, style: TextStyle, contentColor: Color) {
    Text(text = buildMarkdownAnnotatedString(inlines, contentColor), style = style, color = contentColor)
}

@Composable
private fun CodeBlock(text: String, contentColor: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(contentColor.copy(alpha = 0.08f))
            .padding(10.dp),
    ) {
        Text(text = text, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp), color = contentColor)
    }
}

@Composable
private fun QuoteBlock(blocks: List<Block>, style: TextStyle, contentColor: Color) {
    val dim = contentColor.copy(alpha = 0.65f)
    Column(modifier = Modifier.fillMaxWidth().padding(start = 12.dp)) {
        for (b in blocks) {
            when (b) {
                is Block.Paragraph -> MarkdownText(b.children, style, dim)
                else -> MarkdownView(listOf(b), textStyle = style, contentColor = dim)
            }
        }
    }
}

@Composable
private fun ListBlockView(block: Block.ListBlock, style: TextStyle, contentColor: Color) {
    val dim = contentColor.copy(alpha = 0.65f)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        block.items.forEachIndexed { index, item ->
            Row {
                val marker = if (block.ordered) "${index + 1}. " else "• "
                Text(marker, style = style, color = dim, modifier = Modifier.width(if (block.ordered) 22.dp else 16.dp))
                MarkdownText(item, style, contentColor)
            }
        }
    }
}

@Composable
private fun MarkdownImage(
    url: String,
    alt: String,
    load: suspend (String) -> Bitmap?,
    contentColor: Color,
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, url) { value = load(url) }
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = alt,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.FillWidth,
        )
    } else {
        Text("[$alt]", color = contentColor.copy(alpha = 0.5f))
    }
}
