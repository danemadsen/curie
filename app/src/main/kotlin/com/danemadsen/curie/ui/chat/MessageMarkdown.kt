package com.danemadsen.curie.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownBlockQuote
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.rememberMarkdownState
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes

private val CODE_SHAPE_RADIUS = 4.dp
private val QUOTE_SHAPE = RoundedCornerShape(4.dp)
private val QUOTE_BAR_WIDTH = 4.dp

/** react-native-markdown-display gives paragraphs 10 dp margins above and below. */
private val PARAGRAPH_MARGIN = 10.dp

/** Maid's horizontal rules have 16 dp margins. */
private val RULE_MARGIN = 16.dp

/**
 * A message's markdown as Maid renders it (react-native-markdown-display with Maid's styles):
 * 14 sp text, primary links, code on the surface variant with 4 dp corners, quotes with a 4 dp
 * primary bar on the surface variant, and headings in the library's sizes (32 to 11 sp), which
 * keep the text's regular weight.
 *
 * The text is parsed off the main thread as it streams in; the previous rendering stays until the
 * new one is ready, so a growing answer does not flicker.
 */
@Composable
internal fun MessageMarkdown(text: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val body = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface)
    val code = body.copy(fontFamily = FontFamily.Monospace)

    val markdownState = rememberMarkdownState(content = text, retainState = true, immediate = true)

    val markdownColors = markdownColor(
        text = colors.onSurface,
        codeBackground = colors.surfaceVariant,
        inlineCodeBackground = colors.surfaceVariant,
        dividerColor = colors.outline,
    )
    val linkStyle = body.copy(color = colors.primary, textDecoration = TextDecoration.Underline)
    val markdownTypography = markdownTypography(
        h1 = body.copy(fontSize = 32.sp),
        h2 = body.copy(fontSize = 24.sp),
        h3 = body.copy(fontSize = 18.sp),
        h4 = body.copy(fontSize = 16.sp),
        h5 = body.copy(fontSize = 13.sp),
        h6 = body.copy(fontSize = 11.sp),
        text = body,
        code = code,
        inlineCode = code,
        quote = body,
        paragraph = body,
        ordered = body,
        bullet = body,
        list = body,
        textLink = TextLinkStyles(style = linkStyle.toSpanStyle()),
        table = body,
    )
    val padding = markdownPadding(
        block = 0.dp,
        codeBlock = PaddingValues(8.dp),
        blockQuote = PaddingValues(0.dp),
        blockQuoteText = PaddingValues(0.dp),
    )
    val dimens = markdownDimens(codeBackgroundCornerSize = CODE_SHAPE_RADIUS)

    // The library's own quote bar is drawn in the quote style's colour: hide it, QuoteBlock draws Maid's
    val hiddenBarStyle = body.copy(color = Color.Transparent)
    val components = markdownComponents(
        blockQuote = { model ->
            QuoteBlock(barColor = colors.primary, background = colors.surfaceVariant) {
                MarkdownBlockQuote(content = model.content, node = model.node, style = hiddenBarStyle)
            }
        },
    )

    Markdown(
        markdownState = markdownState,
        colors = markdownColors,
        typography = markdownTypography,
        modifier = modifier.fillMaxWidth(),
        padding = padding,
        dimens = dimens,
        components = components,
        success = { state, successComponents, successModifier ->
            // Top-level blocks with Maid's margins instead of the library's uniform spacers
            Column(modifier = successModifier) {
                for (node in state.node.children) {
                    if (node.type == MarkdownTokenTypes.EOL) {
                        continue
                    }

                    val margin = blockMargin(node.type)
                    Box(modifier = Modifier.padding(vertical = margin)) {
                        MarkdownElement(node, successComponents, state.content, includeSpacer = false)
                    }
                }
            }
        },
    )
}

private fun blockMargin(type: IElementType): Dp = when (type) {
    MarkdownElementTypes.PARAGRAPH -> PARAGRAPH_MARGIN
    MarkdownTokenTypes.HORIZONTAL_RULE -> RULE_MARGIN
    else -> 0.dp
}

/** Maid's blockquote: 8 dp in, a 4 dp primary bar on the left of a surface-variant box. */
@Composable
private fun QuoteBlock(barColor: Color, background: Color, content: @Composable () -> Unit) {
    val quoteModifier = Modifier
        .padding(start = 8.dp)
        .fillMaxWidth()
        .clip(QUOTE_SHAPE)
        .background(background)
        .drawBehind {
            val barSize = Size(QUOTE_BAR_WIDTH.toPx(), size.height)
            drawRect(color = barColor, topLeft = Offset.Zero, size = barSize)
        }
        .padding(start = QUOTE_BAR_WIDTH + 12.dp, top = 8.dp, end = 8.dp, bottom = 8.dp)

    Box(modifier = quoteModifier) {
        content()
    }
}
