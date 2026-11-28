package com.aatorque.stats

import org.junit.Assert.*
import org.junit.Test

class AplRegressionTest {
    private fun high(t: Long) = AplEventDetector.Input(3000.0, 80.0, 90.0, t, t, t)
    private fun low(t: Long) = AplEventDetector.Input(1500.0, 10.0, 20.0, t, t, t)
    private val none = AplEventDetector.Action.NONE
    private val start = AplEventDetector.Action.START
    private val finish = AplEventDetector.Action.FINISH

    @Test fun requiresReadinessAndDistinctTokens() {
        val d = AplEventDetector()
        assertEquals(none, d.update(0, high(1), false))
        assertEquals(none, d.update(200, high(1), true))
        assertEquals(none, d.update(600, high(1), true))
        assertEquals(start, d.update(800, high(2), true))
    }

    @Test fun releaseAndPostWindow() {
        val d = AplEventDetector()
        d.update(0, high(1), true)
        assertEquals(start, d.update(400, high(2), true))
        d.update(600, low(3), true)
        assertEquals(none, d.update(1600, low(4), true))
        assertEquals(none, d.update(6599, low(5), true))
        assertEquals(finish, d.update(6600, low(6), true))
        assertFalse(d.recording)
    }

    @Test fun retriggerExtendsEvent() {
        val d = AplEventDetector()
        d.update(0, high(1), true)
        d.update(400, high(2), true)
        d.update(600, low(3), true)
        d.update(1600, low(4), true)
        d.update(2000, high(5), true)
        d.update(2400, high(6), true)
        assertEquals(none, d.update(6600, high(7), true))
        assertTrue(d.recording)
    }

    @Test fun capRequiresReleaseBeforeRestart() {
        val d = AplEventDetector()
        d.update(0, high(1), true)
        d.update(400, high(2), true)
        assertEquals(finish, d.update(120400, high(3), true))
        assertEquals(none, d.update(121000, high(4), true))
        d.update(121200, low(5), true)
        d.update(121400, high(6), true)
        assertEquals(start, d.update(121800, high(7), true))
    }

    @Test fun missingInputInterruptsTriggerConfirmation() {
        val d = AplEventDetector()
        d.update(0, high(1), true)
        d.update(200, null, true)
        assertEquals(none, d.update(400, high(2), true))
        assertEquals(start, d.update(800, high(3), true))
    }

    @Test fun cacheDoesNotRefreshRepeatedOpaqueTokenOrCrossSessions() {
        val a = PidSampleCache.beginSession()
        val pid = AplPidInventory.RPM
        val q = PidValidator.Quality.VALID
        PidSampleCache.publish(a, pid, 1000.0, 1, q, 100, 1000)
        assertNull(PidSampleCache.snapshot(a)[pid]!!.ageMs(100))
        PidSampleCache.publish(a, pid, 1100.0, 2, q, 200, 1000)
        PidSampleCache.publish(a, pid, 1100.0, 2, q, 500, 1000)
        assertEquals(300L, PidSampleCache.snapshot(a)[pid]!!.ageMs(500))
        PidSampleCache.endSession(a)
        val b = PidSampleCache.beginSession()
        PidSampleCache.publish(a, pid, 1200.0, 3, q, 600, 1000)
        assertTrue(PidSampleCache.snapshot(b).isEmpty())
        PidSampleCache.endSession(b)
    }

    @Test fun learnedCadenceUsesDistinctEpochTokensAndNeverRefreshesRepeatedAge() {
        val session = PidSampleCache.beginSession()
        try {
            val pid = "49,0"
            val q = PidValidator.Quality.VALID
            val epoch = 1_800_000_000_000L
            for (i in 0L..3L) {
                PidSampleCache.publish(session, pid, 70.0, epoch + i * 600L, q, i * 600L, epoch + i * 600L)
            }
            val first = PidSampleCache.snapshot(session).getValue(pid)
            assertEquals(600L, first.sourceIntervalMs)
            assertEquals(800L, first.freshnessLimitMs)
            PidSampleCache.publish(session, pid, 70.0, epoch + 1800L, q, 2800L, epoch + 2800L)
            val repeat = PidSampleCache.snapshot(session).getValue(pid)
            assertEquals(1000L, repeat.ageMs(2800L))
            assertEquals(800L, repeat.freshnessLimitMs)
            assertTrue(repeat.ageMs(2800L)!! > repeat.freshnessLimitMs)
        } finally { PidSampleCache.endSession(session) }
    }

    @Test fun regularGaugeLeaseExpiresAfterHandoff() {
        val session = PidSampleCache.beginSession()
        try {
            val pid = AplPidInventory.RPM
            PidSampleCache.request(session, pid, "gauge", 1000L)
            assertTrue(PidSampleCache.regularPolling(session, pid, 1300L, 100L))
            assertFalse(PidSampleCache.regularPolling(session, pid, 1400L, 100L))
            assertFalse(PidSampleCache.regularPolling(session, "49,0", 1300L, 100L))
            assertEquals(2000L, PidSampleCache.triggerAgeLimit(pid, 5000L))
            assertEquals(400L, PidSampleCache.triggerAgeLimit(pid, null))
        } finally { PidSampleCache.endSession(session) }
    }

    @Test fun engineStateDoesNotRenewRepeatedRpm() {
        val epoch = 1_800_000_000_000L
        EngineState.connection(false)
        EngineState.connection(true)
        try {
            assertEquals(EngineState.State.UNKNOWN, EngineState.state(0))
            EngineState.observe(0.0, epoch, 0, epoch)
            assertEquals(EngineState.State.OFF, EngineState.state(0))
            EngineState.observe(600.0, epoch + 1000, 1000, epoch + 1000)
            assertEquals(EngineState.State.UNKNOWN, EngineState.state(1000))
            EngineState.observe(600.0, epoch + 1500, 1500, epoch + 1500)
            assertEquals(EngineState.State.RUNNING, EngineState.state(1500))
            EngineState.observe(600.0, epoch + 1500, 7000, epoch + 7000)
            assertEquals(EngineState.State.UNKNOWN, EngineState.state(7000))
        } finally { EngineState.connection(false) }
    }

    @Test fun headerStatusHasSinglePriority() {
        assertEquals(HeaderStatus.Icon.BROKEN_LINK, HeaderStatus.select(true, EngineState.State.OFF, true))
        assertEquals(HeaderStatus.Icon.ENGINE_OFF, HeaderStatus.select(false, EngineState.State.OFF, true))
        assertEquals(HeaderStatus.Icon.ENGINE_OFF, HeaderStatus.select(false, EngineState.State.OFF, false))
        assertEquals(HeaderStatus.Icon.ENGINE_UNKNOWN, HeaderStatus.select(false, EngineState.State.UNKNOWN, true))
        assertEquals(HeaderStatus.Icon.APL, HeaderStatus.select(false, EngineState.State.RUNNING, true))
        assertEquals(HeaderStatus.Icon.NONE, HeaderStatus.select(false, EngineState.State.RUNNING, false))
    }

    @Test fun inventoryRequiresMatchingPedalMetadata() {
        val statuses = AplPidInventory.verify(listOf("49,0"), listOf("Accelerator Pedal D,Pedal,%,100,0"))
        assertEquals("metadata_present", statuses["pedal"])
        assertEquals("missing", statuses["throttle"])
        assertEquals(10, AplPidInventory.ids.size)
    }
}
