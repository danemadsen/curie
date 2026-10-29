package com.danemadsen.curie.assistant

/** The "Hey Curie" prominent disclosure, as a dialog: a title, the text and its two buttons. */
data class WakeWordDisclosure(
    val title: String,
    val text: String,
    val acceptLabel: String,
    val declineLabel: String,
)
