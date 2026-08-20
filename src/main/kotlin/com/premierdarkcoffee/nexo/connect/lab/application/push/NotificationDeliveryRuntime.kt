package com.premierdarkcoffee.nexo.connect.lab.application.push

import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

fun interface NotificationDeliveryCycle {
    fun run(): NotificationDeliveryRunSummary
}

fun interface NotificationDeliverySchedule : AutoCloseable {
    override fun close()
}

interface NotificationDeliveryScheduler : AutoCloseable {
    fun scheduleWithFixedDelay(initialDelay: Duration, delay: Duration, task: () -> Unit): NotificationDeliverySchedule
}

class ExecutorNotificationDeliveryScheduler(
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "connect-notification-delivery").apply { isDaemon = true }
        },
) : NotificationDeliveryScheduler {
    override fun scheduleWithFixedDelay(
        initialDelay: Duration,
        delay: Duration,
        task: () -> Unit,
    ): NotificationDeliverySchedule {
        val future: ScheduledFuture<*> =
            executor.scheduleWithFixedDelay(
                task,
                initialDelay.toMillis(),
                delay.toMillis(),
                TimeUnit.MILLISECONDS,
            )
        return NotificationDeliverySchedule { future.cancel(false) }
    }

    override fun close() {
        executor.shutdownNow()
        try {
            executor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private companion object {
        const val SHUTDOWN_WAIT_SECONDS = 5L
    }
}

class ScheduledNotificationDeliveryRuntime(
    private val cycle: NotificationDeliveryCycle,
    private val interval: Duration = Duration.ofSeconds(1),
    private val initialDelay: Duration = Duration.ZERO,
    private val scheduler: NotificationDeliveryScheduler = ExecutorNotificationDeliveryScheduler(),
) : AutoCloseable {
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private var schedule: NotificationDeliverySchedule? = null

    init {
        require(interval in MIN_INTERVAL..MAX_INTERVAL) {
            "Notification delivery interval must stay within the bounded runtime range"
        }
        require(!initialDelay.isNegative && initialDelay <= MAX_INITIAL_DELAY) {
            "Notification delivery initial delay is invalid"
        }
    }

    @Synchronized
    fun start() {
        check(!closed.get()) { "Notification delivery runtime is closed" }
        if (!started.compareAndSet(false, true)) return
        try {
            schedule =
                scheduler.scheduleWithFixedDelay(initialDelay, interval) {
                    try {
                        cycle.run()
                    } catch (_: Exception) {
                        // A failed cycle leaves PostgreSQL truth intact and must not stop later polls.
                    }
                }
        } catch (failure: Throwable) {
            started.set(false)
            throw failure
        }
    }

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        schedule?.close()
        schedule = null
        scheduler.close()
    }

    private companion object {
        val MIN_INTERVAL: Duration = Duration.ofMillis(100)
        val MAX_INTERVAL: Duration = Duration.ofMinutes(1)
        val MAX_INITIAL_DELAY: Duration = Duration.ofMinutes(1)
    }
}
