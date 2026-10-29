package com.danemadsen.curie.tools

/**
 * Where a tool call comes from. Either way, app-switching actions come back as
 * [ToolOutcome.deferredLaunch] and start once Curie has finished answering: opening another app
 * mid-answer would cover the chat or the call screen.
 */
enum class ToolOrigin {
    /** A chat on screen: an app switch is refused up front when Curie is not visible. */
    CHAT,

    /** A voice call: its deferred launches start after Curie has finished speaking, then the call ends. */
    CALL,
}
