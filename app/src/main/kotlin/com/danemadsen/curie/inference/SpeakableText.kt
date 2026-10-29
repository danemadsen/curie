package com.danemadsen.curie.inference

/**
 * The text of an answer as it should be read aloud: markdown markup removed (code blocks, emphasis,
 * headings, links, list markers, tables), then split into pieces short enough for one text to
 * speech run each.
 */
object SpeakableText {
    /** About 40 seconds of speech: well inside the speech conversation's 2,048 positions. */
    const val MAX_CHUNK_CHARS = 500

    private val CODE_BLOCK = Regex("```[\\s\\S]*?(```|$)")
    private val INLINE_CODE = Regex("`([^`]*)`")
    private val IMAGE = Regex("!\\[([^\\]]*)]\\([^)]*\\)")
    private val LINK = Regex("\\[([^\\]]*)]\\([^)]*\\)")
    private val HEADING = Regex("(?m)^[ \\t]{0,3}#{1,6}[ \\t]*")
    private val QUOTE = Regex("(?m)^[ \\t]*>[ \\t]?")
    private val BULLET = Regex("(?m)^[ \\t]*[-*+][ \\t]+")
    private val TABLE_RULE = Regex("(?m)^[ \\t]*\\|?[ \\t]*:?-{3,}:?[ \\t]*(\\|[ \\t]*:?-{3,}:?[ \\t]*)*\\|?[ \\t]*(\\n|$)")
    private val TABLE_PIPE = Regex("\\s*\\|\\s*")
    private val HORIZONTAL_RULE = Regex("(?m)^[ \\t]*([-*_])[ \\t]*(\\1[ \\t]*){2,}$")
    private val EMPHASIS = Regex("(\\*\\*|__|\\*|_|~~)(?=\\S)(.+?)(?<=\\S)\\1")
    private val HTML_TAG = Regex("<[^>]+>")
    private val BLANK_LINES = Regex("\\n{2,}")
    private val SPACES = Regex("[ \\t]+")
    private val SENTENCE_END = Regex("(?<=[.!?…])\\s+")

    /** [markdown] as plain text, paragraphs kept apart by blank lines. */
    fun plainText(markdown: String): String {
        var text = markdown.replace("\r\n", "\n")
        text = CODE_BLOCK.replace(text, " ")
        text = IMAGE.replace(text, "$1")
        text = LINK.replace(text, "$1")
        text = INLINE_CODE.replace(text, "$1")
        text = HORIZONTAL_RULE.replace(text, "")
        text = TABLE_RULE.replace(text, "")
        text = HEADING.replace(text, "")
        text = QUOTE.replace(text, "")
        text = BULLET.replace(text, "")
        text = EMPHASIS.replace(text, "$2")
        text = HTML_TAG.replace(text, "")

        val lines = text.lines()
        val cleanedLines = lines.map { line -> cleanLine(line) }
        val joined = cleanedLines.joinToString("\n")
        val paragraphs = BLANK_LINES.replace(joined, "\n\n")
        return paragraphs.trim()
    }

    /**
     * [markdown] read aloud in order, as pieces of at most [maxChars] characters that end at a
     * paragraph or sentence boundary where they can (a sentence longer than that is cut between
     * words). Empty when nothing is left to say.
     */
    fun chunks(markdown: String, maxChars: Int = MAX_CHUNK_CHARS): List<String> {
        val text = plainText(markdown)
        if (text.isEmpty()) {
            return emptyList()
        }

        val sentences = ArrayList<String>()
        for (paragraph in text.split("\n\n")) {
            val paragraphSentences = paragraph.split(SENTENCE_END)
            for (sentence in paragraphSentences) {
                val flattened = sentence.replace('\n', ' ').trim()
                if (flattened.isNotEmpty()) {
                    sentences.addAll(splitLongSentence(flattened, maxChars))
                }
            }
        }

        val chunks = ArrayList<String>()
        val current = StringBuilder()
        for (sentence in sentences) {
            val separatorLength = if (current.isEmpty()) 0 else 1
            if (current.length + separatorLength + sentence.length > maxChars) {
                chunks.add(current.toString())
                current.clear()
            }
            if (current.isNotEmpty()) {
                current.append(' ')
            }
            current.append(sentence)
        }
        if (current.isNotEmpty()) {
            chunks.add(current.toString())
        }
        return chunks
    }

    private fun cleanLine(line: String): String {
        val withoutPipes = if (line.contains('|')) TABLE_PIPE.replace(line, ", ") else line
        val trimmedPipes = withoutPipes.trim().removePrefix(",").removeSuffix(",")
        return SPACES.replace(trimmedPipes, " ").trim()
    }

    private fun splitLongSentence(sentence: String, maxChars: Int): List<String> {
        if (sentence.length <= maxChars) {
            return listOf(sentence)
        }

        val pieces = ArrayList<String>()
        var remaining = sentence
        while (remaining.length > maxChars) {
            val lastSpace = remaining.lastIndexOf(' ', startIndex = maxChars)
            val cut = if (lastSpace > 0) lastSpace else maxChars
            pieces.add(remaining.substring(0, cut).trim())
            remaining = remaining.substring(cut).trim()
        }
        if (remaining.isNotEmpty()) {
            pieces.add(remaining)
        }
        return pieces
    }
}
