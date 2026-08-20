package com.premierdarkcoffee.nexo.connect.lab.application.push

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationDeliveryRuntimeTest {
    @Test
    fun `runtime owns one bounded schedule and closes it exactly once`() {
        val scheduler = RecordingScheduler()
        var cycles = 0
        val runtime =
            ScheduledNotificationDeliveryRuntime(
                cycle = NotificationDeliveryCycle {
                    cycles += 1
                    NotificationDeliveryRunSummary(0, 0, 0, 0, 0)
                },
                interval = Duration.ofSeconds(2),
                initialDelay = Duration.ofMillis(250),
                scheduler = scheduler,
            )

        runtime.start()
        runtime.start()
        scheduler.runScheduledTask()
        runtime.close()
        runtime.close()

        assertEquals(1, scheduler.scheduleCount)
        assertEquals(Duration.ofMillis(250), scheduler.initialDelay)
        assertEquals(Duration.ofSeconds(2), scheduler.delay)
        assertEquals(1, cycles)
        assertEquals(1, scheduler.scheduleCloseCount)
        assertEquals(1, scheduler.closeCount)
        assertFailsWith<IllegalStateException> { runtime.start() }
    }

    @Test
    fun `failed cycle is contained so a later poll can recover`() {
        val scheduler = RecordingScheduler()
        var attempts = 0
        val runtime =
            ScheduledNotificationDeliveryRuntime(
                cycle = NotificationDeliveryCycle {
                    attempts += 1
                    if (attempts == 1) error("injected provider outage")
                    NotificationDeliveryRunSummary(0, 0, 0, 0, 0)
                },
                scheduler = scheduler,
            )

        runtime.start()
        scheduler.runScheduledTask()
        assertFalse(scheduler.scheduleClosed)
        scheduler.runScheduledTask()

        assertEquals(2, attempts)
        assertFalse(scheduler.scheduleClosed)
        runtime.close()
        assertTrue(scheduler.scheduleClosed)
    }

    private class RecordingScheduler : NotificationDeliveryScheduler {
        var scheduleCount = 0
        var scheduleCloseCount = 0
        var closeCount = 0
        var scheduleClosed = false
        var initialDelay: Duration? = null
        var delay: Duration? = null
        private var task: (() -> Unit)? = null

        override fun scheduleWithFixedDelay(
            initialDelay: Duration,
            delay: Duration,
            task: () -> Unit,
        ): NotificationDeliverySchedule {
            scheduleCount += 1
            this.initialDelay = initialDelay
            this.delay = delay
            this.task = task
            return NotificationDeliverySchedule {
                if (!scheduleClosed) {
                    scheduleClosed = true
                    scheduleCloseCount += 1
                }
            }
        }

        fun runScheduledTask() = checkNotNull(task).invoke()

        override fun close() {
            closeCount += 1
        }
    }
}
