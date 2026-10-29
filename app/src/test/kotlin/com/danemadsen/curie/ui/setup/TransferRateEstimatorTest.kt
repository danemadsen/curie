package com.danemadsen.curie.ui.setup

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TransferRateEstimatorTest {
    @Test
    fun theSpeedIsReportedOnceTwoSecondsBackIt() {
        val estimator = TransferRateEstimator()

        assertNull(estimator.update(timeMs = 0, bytes = 0))
        assertNull(estimator.update(timeMs = 1_000, bytes = 1_000_000))
        assertEquals(1_000_000L, estimator.update(timeMs = 2_000, bytes = 2_000_000))
    }

    @Test
    fun onlyTheLastEightSecondsCount() {
        val estimator = TransferRateEstimator()
        estimator.update(timeMs = 0, bytes = 0)
        estimator.update(timeMs = 1_000, bytes = 10_000_000)

        // Slower now: the fast first second drops out of the window
        var rate: Long? = null
        for (second in 2..20) {
            rate = estimator.update(timeMs = second * 1_000L, bytes = 10_000_000L + (second - 1) * 1_000_000L)
        }
        assertEquals(1_000_000L, rate)
    }

    @Test
    fun aRestartedDownloadStartsOver() {
        val estimator = TransferRateEstimator()
        estimator.update(timeMs = 0, bytes = 5_000_000)
        estimator.update(timeMs = 3_000, bytes = 8_000_000)

        assertNull(estimator.update(timeMs = 4_000, bytes = 0))
    }
}
