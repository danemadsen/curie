package com.danemadsen.curie.engine.onnx

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Closing waits for the runs on the sessions, never frees them under a run, and happens once. */
class SessionGuardTest {
    @Test
    fun runsWhileOpen() {
        val guard = SessionGuard()

        val result = guard.whileOpen { 42 }

        assertEquals(42, result)
    }

    @Test
    fun closeStopsTheRunsAndReleasesOnlyAfterTheyReturn() {
        val guard = SessionGuard()
        val events = Collections.synchronizedList(ArrayList<String>())
        val running = CountDownLatch(1)
        val stop = CountDownLatch(1)

        val run = Thread {
            guard.whileOpen {
                running.countDown()
                // A long run that returns soon after it is asked to stop
                stop.await(10, TimeUnit.SECONDS)
                events.add("run returned")
            }
        }
        run.start()
        assertTrue(running.await(10, TimeUnit.SECONDS))

        val stopRuns: () -> Unit = {
            events.add("stop requested")
            stop.countDown()
        }
        val release: () -> Unit = { events.add("released") }
        guard.close(stopRuns, release)
        run.join()

        assertEquals(listOf("stop requested", "run returned", "released"), events)
    }

    @Test
    fun aClosedGuardRefusesNewRuns() {
        val guard = SessionGuard()
        guard.checkOpen()
        guard.close(stopRuns = {}, release = {})

        assertTrue(guard.isClosing)
        assertThrows<IllegalStateException> { guard.whileOpen {} }
        assertThrows<IllegalStateException> { guard.checkOpen() }
    }

    @Test
    fun runsAreRefusedFromTheMomentClosingStarts() {
        val guard = SessionGuard()
        var refused = false

        val stopRuns = {
            refused = runCatching { guard.whileOpen { "too late" } }.isFailure
        }
        guard.close(stopRuns, release = {})

        assertTrue(refused)
    }

    @Test
    fun closingTwiceReleasesOnce() {
        val guard = SessionGuard()
        var releases = 0

        guard.close(stopRuns = {}, release = { releases++ })
        guard.close(stopRuns = {}, release = { releases++ })

        assertEquals(1, releases)
    }

    @Test
    fun closingFromInsideARunFailsInsteadOfWaitingForItself() {
        val guard = SessionGuard()

        assertThrows<IllegalStateException> {
            guard.whileOpen {
                guard.close(stopRuns = {}, release = {})
            }
        }

        // The failed attempt left the guard open
        assertEquals("still open", guard.whileOpen { "still open" })
    }
}
