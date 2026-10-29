package com.danemadsen.curie.data

/** A message of a chat's tree with its attachments, in no particular order. */
internal class TreeNode(
    val message: Message,
    val attachments: List<Attachment>,
)

/** Siblings are numbered oldest first; the id breaks ties so the order is stable. */
private val SIBLING_ORDER = compareBy<TreeNode>({ node -> node.message.createdAt }, { node -> node.message.id })

/**
 * The visible thread of a chat's tree: from the selected first message, follow each message's
 * selected child. A selection that names no child of the message (which the repository never
 * writes) falls back to the newest child, so a damaged pointer cannot hide the rest of the chat.
 *
 * Each step moves to a child of the previous message, starting from the chat itself, so the walk
 * ends even on damaged data: a message whose parent pointers loop is never reached.
 */
internal fun visibleThread(selectedFirstMessageId: String?, nodes: List<TreeNode>): List<ThreadMessage> {
    val unsortedChildren = nodes.groupBy { node -> node.message.parentId }
    val childrenByParent = unsortedChildren.mapValues { (_, children) -> children.sortedWith(SIBLING_ORDER) }

    val thread = mutableListOf<ThreadMessage>()
    var parentId: String? = null
    var selectedId = selectedFirstMessageId

    while (true) {
        val siblings = childrenByParent[parentId].orEmpty()
        val selectedIndex = siblings.indexOfFirst { node -> node.message.id == selectedId }
        val index = if (selectedIndex >= 0) selectedIndex else siblings.lastIndex
        val node = siblings.getOrNull(index) ?: break

        val threadMessage = ThreadMessage(
            message = node.message,
            attachments = node.attachments,
            siblingIndex = index,
            siblingCount = siblings.size,
        )
        thread.add(threadMessage)

        parentId = node.message.id
        selectedId = node.message.selectedChildId
    }
    return thread
}
