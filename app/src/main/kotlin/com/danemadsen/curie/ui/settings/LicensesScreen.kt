package com.danemadsen.curie.ui.settings

import android.content.res.AssetManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.danemadsen.curie.R
import com.danemadsen.curie.ui.components.ScreenHeader
import com.danemadsen.curie.ui.components.SectionDivider

/** The asset folder holding one plain-text licence file per bundled component. */
const val LICENSES_ASSET_FOLDER = "licenses"

/** A licence's text is shown in blocks of whole paragraphs of at most about this many characters. */
private const val TEXT_BLOCK_CHARS = 2_000

private const val PARAGRAPH_SEPARATOR = "\n\n"

/** A bundled licence: its file name without ".txt", and its text in blocks (see [toTextBlocks]). */
@Immutable
data class LicenseUi(val name: String, val textBlocks: List<String>)

/**
 * Reads every .txt file of assets/licenses, sorted by name. Blocking file IO: call it off the main
 * thread.
 */
fun readBundledLicenses(assets: AssetManager): List<LicenseUi> {
    val fileNames = assets.list(LICENSES_ASSET_FOLDER).orEmpty()
    val textFiles = fileNames.filter { fileName -> fileName.endsWith(".txt") }

    return textFiles.sorted().map { fileName ->
        val path = "$LICENSES_ASSET_FOLDER/$fileName"
        val text = assets.open(path).bufferedReader().use { reader -> reader.readText() }
        val textBlocks = toTextBlocks(text)

        LicenseUi(name = fileName.removeSuffix(".txt"), textBlocks = textBlocks)
    }
}

/**
 * [text] in blocks of whole paragraphs, a blank line between the paragraphs of a block. A block
 * ends before it would grow past [maxChars] characters; a longer paragraph is a block of its own.
 * A lazy list then lays out only the blocks on screen, each one quickly, and text selection still
 * spans the paragraphs of a block.
 */
internal fun toTextBlocks(text: String, maxChars: Int = TEXT_BLOCK_CHARS): List<String> {
    val blocks = mutableListOf<String>()
    val block = StringBuilder()

    for (paragraph in splitParagraphs(text)) {
        val grownLength = block.length + PARAGRAPH_SEPARATOR.length + paragraph.length
        if (block.isNotEmpty() && grownLength > maxChars) {
            blocks.add(block.toString())
            block.clear()
        }

        if (block.isNotEmpty()) {
            block.append(PARAGRAPH_SEPARATOR)
        }
        block.append(paragraph)
    }

    if (block.isNotEmpty()) {
        blocks.add(block.toString())
    }

    return blocks
}

/**
 * The paragraphs of [text]: its runs of lines between blank (or whitespace-only) lines, joined
 * with "\n". The lines keep their indentation.
 */
internal fun splitParagraphs(text: String): List<String> {
    val paragraphs = mutableListOf<String>()
    val paragraphLines = mutableListOf<String>()

    fun endParagraph() {
        if (paragraphLines.isNotEmpty()) {
            paragraphs.add(paragraphLines.joinToString("\n"))
            paragraphLines.clear()
        }
    }

    for (line in text.lines()) {
        if (line.isBlank()) {
            endParagraph()
        } else {
            paragraphLines.add(line)
        }
    }
    endParagraph()

    return paragraphs
}

/**
 * The open-source licences: a list of the bundled licence files; tapping one shows its text, and
 * back returns to the list.
 */
@Composable
fun LicensesScreen(
    licenses: List<LicenseUi>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var openedName by rememberSaveable { mutableStateOf<String?>(null) }
    val openedLicense = licenses.firstOrNull { license -> license.name == openedName }
    val closeLicense = { openedName = null }

    BackHandler(enabled = openedLicense != null, onBack = closeLicense)

    val horizontalInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
    val screenModifier = modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.surface)
        .windowInsetsPadding(horizontalInsets)

    Column(modifier = screenModifier) {
        if (openedLicense == null) {
            ScreenHeader(title = stringResource(R.string.licenses_title), onBack = onBack)
            LicenseList(licenses = licenses, onOpen = { license -> openedName = license.name })
        } else {
            ScreenHeader(title = openedLicense.name, onBack = closeLicense)
            LicenseText(textBlocks = openedLicense.textBlocks)
        }
    }
}

@Composable
private fun LicenseList(licenses: List<LicenseUi>, onOpen: (LicenseUi) -> Unit) {
    val bottomInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
    val contentPadding = bottomInsets.asPaddingValues()

    LazyColumn(contentPadding = contentPadding) {
        itemsIndexed(items = licenses, key = { _, license -> license.name }) { index, license ->
            if (index > 0) {
                SectionDivider()
            }
            LicenseRow(name = license.name, onClick = { onOpen(license) })
        }
    }
}

@Composable
private fun LicenseRow(name: String, onClick: () -> Unit) {
    val rowModifier = Modifier
        .fillMaxWidth()
        .clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 18.dp, vertical = 12.dp)

    Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            painter = painterResource(R.drawable.tabler_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * A licence's text, one lazy item per block of paragraphs: the largest notices file is over 300 KB,
 * far too much to lay out at once on the main thread. The text of a block can be selected and copied.
 */
@Composable
private fun LicenseText(textBlocks: List<String>) {
    val textStyle = MaterialTheme.typography.bodySmall
    val textColor = MaterialTheme.colorScheme.onSurface

    // About a blank line between blocks, as between the paragraphs within them
    val blockSpacing = with(LocalDensity.current) { textStyle.fontSize.toDp() }

    val bottomInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
    val textPadding = WindowInsets(left = 16.dp, top = 8.dp, right = 16.dp, bottom = 8.dp)
    val contentPadding = bottomInsets.add(textPadding).asPaddingValues()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(blockSpacing),
    ) {
        items(items = textBlocks) { textBlock ->
            SelectionContainer {
                Text(text = textBlock, style = textStyle, color = textColor)
            }
        }
    }
}
