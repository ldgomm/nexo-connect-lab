package com.premierdarkcoffee.nexo.connect.lab.application.realtime

import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.DurableMessageCreatedEvent
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.DurableReceiptCursorEvent
import com.premierdarkcoffee.nexo.connect.lab.domain.realtime.EphemeralTypingSignal
import kotlinx.coroutines.CancellationException
import java.time.Duration
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean

class MultiInstanceRealtimeFanout(
    private val localHub: AuthorizedConversationEventHub,
    private val transport: EphemeralRealtimeFanoutTransport?,
    private val payloadLoader: () -> AuthorisedDurableFanoutPayloadLoader?,
    private val codec: RealtimeFanoutEnvelopeCodec,
    private val typingCodec: TypingSignalEnvelopeCodec? = null,
    dedupe: BoundedRealtimeFanoutDedupe = BoundedRealtimeFanoutDedupe(),
) : MessageCreatedEventPublisher {
    private val envelopeFactory = transport?.let { DurableRealtimeFanoutEnvelopeFactory(it.localInstanceRef) }
    private val started = AtomicBoolean()
    private val dedupe = dedupe
    val localInstanceRef: String = transport?.localInstanceRef ?: "single-instance"

    fun start() {
        val activeTransport = transport ?: return
        check(started.compareAndSet(false, true)) { "Multi-instance realtime fan-out is already started" }
        activeTransport.start(::consume)
    }

    override suspend fun publish(event: DurableMessageCreatedEvent): MessageCreatedPublicationReport {
        val envelope = envelopeFactory?.messageCreated(event)
        if (envelope != null) {
            publishEphemeral(RealtimeFanoutChannel.MESSAGE_CREATED, codec.encode(envelope))
        }
        return localHub.publish(event)
    }

    suspend fun publishReceipt(
        event: DurableReceiptCursorEvent,
        excludedRegistration: RealtimeConnectionRegistration? = null,
    ): ReceiptCursorPublicationReport {
        val envelope = envelopeFactory?.receiptAdvanced(event)
        if (envelope != null) {
            publishEphemeral(RealtimeFanoutChannel.RECEIPT_ADVANCED, codec.encode(envelope))
        }
        return localHub.publishReceipt(event, excludedRegistration)
    }

    suspend fun publishTyping(
        signal: EphemeralTypingSignal,
        excludedRegistration: RealtimeConnectionRegistration? = null,
    ): TypingSignalPublicationReport {
        val encoded = typingCodec?.encode(signal)
        if (encoded != null) {
            publishEphemeral(RealtimeFanoutChannel.TYPING_STATE_CHANGED, encoded)
        }
        return localHub.publishTyping(signal, excludedRegistration)
    }

    private suspend fun publishEphemeral(channel: RealtimeFanoutChannel, payload: String) {
        try {
            transport?.publish(channel, payload)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // PostgreSQL already committed; authorised catch-up repairs missed live fan-out.
        }
    }

    private suspend fun consume(delivery: EphemeralRealtimeFanoutDelivery) {
        val activeTransport = transport ?: return
        if (delivery.channel == RealtimeFanoutChannel.TYPING_STATE_CHANGED) {
            val signal = typingCodec?.decode(delivery) ?: return
            if (signal.originInstanceRef == activeTransport.localInstanceRef) return
            if (!dedupe.markIfNew(signal.eventId)) return
            localHub.publishTyping(signal)
            return
        }
        val loader = payloadLoader() ?: return
        val envelope = codec.decode(delivery) ?: return
        if (envelope.originInstanceRef == activeTransport.localInstanceRef) return
        if (!dedupe.markIfNew(envelope.eventId)) return

        when (delivery.channel) {
            RealtimeFanoutChannel.MESSAGE_CREATED -> localHub.publishRemoteMessage(envelope, loader)
            RealtimeFanoutChannel.RECEIPT_ADVANCED -> localHub.publishRemoteReceipt(envelope, loader)
            RealtimeFanoutChannel.TYPING_STATE_CHANGED -> Unit
        }
    }
}

class BoundedRealtimeFanoutDedupe(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val ttlNanos: Long = DEFAULT_TTL.toNanos(),
    private val monotonicNanos: () -> Long = System::nanoTime,
) {
    private val seenAt = LinkedHashMap<String, Long>()

    init {
        require(capacity in 1..MAX_CAPACITY) { "capacity must be between 1 and $MAX_CAPACITY" }
        require(ttlNanos > 0) { "ttlNanos must be positive" }
    }

    @Synchronized
    fun markIfNew(eventId: String): Boolean {
        val now = monotonicNanos()
        val iterator = seenAt.entries.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value >= ttlNanos) iterator.remove()
        }
        if (seenAt.containsKey(eventId)) return false
        while (seenAt.size >= capacity) {
            seenAt.remove(seenAt.keys.first())
        }
        seenAt[eventId] = now
        return true
    }

    private companion object {
        const val DEFAULT_CAPACITY = 10_000
        const val MAX_CAPACITY = 100_000
        val DEFAULT_TTL: Duration = Duration.ofMinutes(5)
    }
}
