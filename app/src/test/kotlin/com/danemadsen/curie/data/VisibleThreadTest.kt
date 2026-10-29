package com.danemadsen.curie.data

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VisibleThreadTest {
    private fun node(id: String, parentId: String?, selectedChildId: String?, createdAt: Long): TreeNode {
        val message = Message(
            id = id,
            chatId = "chat",
            parentId = parentId,
            selectedChildId = selectedChildId,
            role = MessageRole.USER,
            text = id,
            createdAt = createdAt,
            updatedAt = createdAt,
            toolCalls = emptyList(),
            speechFile = null,
            error = null,
        )
        return TreeNode(message, emptyList())
    }

    private fun ids(thread: List<ThreadMessage>): List<String> = thread.map { item -> item.message.id }

    @Test
    fun followsTheSelectionsFromTheSelectedFirstMessage() {
        val nodes = listOf(
            node("a2", null, "c", createdAt = 2),
            node("a1", null, "b", createdAt = 1),
            node("b", "a1", null, createdAt = 3),
            node("c", "a2", "d2", createdAt = 4),
            node("d1", "c", null, createdAt = 5),
            node("d2", "c", null, createdAt = 6),
        )

        val thread = visibleThread("a2", nodes)

        assertEquals(listOf("a2", "c", "d2"), ids(thread))
        assertEquals(listOf(1, 0, 1), thread.map { item -> item.siblingIndex })
        assertEquals(listOf(2, 1, 2), thread.map { item -> item.siblingCount })
    }

    @Test
    fun numbersSiblingsOldestFirstWithTheIdBreakingTies() {
        val nodes = listOf(
            node("b", null, null, createdAt = 7),
            node("c", null, null, createdAt = 3),
            node("a", null, null, createdAt = 7),
        )

        val thread = visibleThread("b", nodes)

        assertEquals(listOf("b"), ids(thread))
        assertEquals(2, thread.single().siblingIndex)
    }

    @Test
    fun fallsBackToTheNewestChildWhenTheSelectionNamesNoChild() {
        val nodes = listOf(
            node("a", null, "missing", createdAt = 1),
            node("b1", "a", null, createdAt = 2),
            node("b2", "a", null, createdAt = 3),
        )

        val withMissingFirst = visibleThread("gone", nodes)
        val withoutSelection = visibleThread(null, nodes)

        assertEquals(listOf("a", "b2"), ids(withMissingFirst))
        assertEquals(listOf("a", "b2"), ids(withoutSelection))
    }

    @Test
    fun ignoresASelectionThatPointsOutsideTheMessagesChildren() {
        val nodes = listOf(
            node("a", null, "x", createdAt = 1),
            node("b", "a", null, createdAt = 2),
            node("x", null, null, createdAt = 3),
        )

        val thread = visibleThread("a", nodes)

        assertEquals(listOf("a", "b"), ids(thread))
    }

    @Test
    fun endsOnParentPointersThatLoop() {
        val nodes = listOf(
            node("a", null, "loop", createdAt = 1),
            node("loop", "loop", "loop", createdAt = 2),
            node("x", "y", "y", createdAt = 3),
            node("y", "x", "x", createdAt = 4),
        )

        val thread = visibleThread("a", nodes)

        assertEquals(listOf("a"), ids(thread))
    }

    @Test
    fun anEmptyTreeHasAnEmptyThread() {
        assertTrue(visibleThread(null, emptyList()).isEmpty())
        assertTrue(visibleThread("a", emptyList()).isEmpty())
    }
}
