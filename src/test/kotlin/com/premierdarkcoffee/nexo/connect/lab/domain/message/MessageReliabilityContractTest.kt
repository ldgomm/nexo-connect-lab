package com.premierdarkcoffee.nexo.connect.lab.domain.message

import com.premierdarkcoffee.nexo.connect.lab.application.message.MessageAcceptanceDecision
import com.premierdarkcoffee.nexo.connect.lab.application.message.MessageConflictReason
import com.premierdarkcoffee.nexo.connect.lab.application.message.MessageIdempotencyEvaluator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MessageReliabilityContractTest {
    private val evaluator = MessageIdempotencyEvaluator()

    @Test
    fun `reserves every canonical message type but enables only text for the durable text slice`() {
        assertEquals(
            setOf(
                MessageType.TEXT,
                MessageType.IMAGE,
                MessageType.VOICE_NOTE,
                MessageType.VIDEO_FILE,
                MessageType.LOCATION,
                MessageType.PRODUCT_CARD,
                MessageType.SYSTEM,
            ),
            MessageType.entries.toSet(),
        )
        assertEquals(setOf(MessageType.TEXT), MessageType.entries.filter { it.isEnabledForDurableText }.toSet())
    }

    @Test
    fun `accepts only when neither durable idempotency identity already exists`() {
        assertEquals(
            MessageAcceptanceDecision.AcceptNew,
            evaluator.decide(command(), null, null),
        )
    }

    @Test
    fun `replays the same durable message for an identical retry`() {
        val existing = record()

        val decision = evaluator.decide(command(), existing, existing)

        val replay = assertIs<MessageAcceptanceDecision.ReplayExisting>(decision)
        assertEquals("message-alpha", replay.serverMessageRef)
        assertEquals(ConversationSequence(1), replay.sequence)
    }

    @Test
    fun `rejects reuse of an idempotency key with a different client message identity`() {
        val decision =
            evaluator.decide(
                command(clientMessageRef = "client-message-beta"),
                record(),
                null,
            )

        assertEquals(
            MessageAcceptanceDecision.Conflict(MessageConflictReason.IDEMPOTENCY_KEY_REUSED),
            decision,
        )
    }

    @Test
    fun `rejects reuse of a client message identity with a different idempotency key`() {
        val decision =
            evaluator.decide(
                command(idempotencyKey = "idempotency-beta"),
                null,
                record(),
            )

        assertEquals(
            MessageAcceptanceDecision.Conflict(MessageConflictReason.CLIENT_MESSAGE_REF_REUSED),
            decision,
        )
    }

    @Test
    fun `rejects an identical identity with a different payload fingerprint`() {
        val decision =
            evaluator.decide(
                command(body = "Changed"),
                record(),
                record(),
            )

        assertEquals(
            MessageAcceptanceDecision.Conflict(MessageConflictReason.PAYLOAD_MISMATCH),
            decision,
        )
    }

    @Test
    fun `rejects divergent records returned by the two durable uniqueness lookups`() {
        val decision =
            evaluator.decide(
                command(),
                record(),
                record(serverMessageRef = "message-beta", sequence = 2),
            )

        assertEquals(
            MessageAcceptanceDecision.Conflict(MessageConflictReason.DEDUPLICATION_STATE_DIVERGED),
            decision,
        )
    }

    @Test
    fun `keeps sequence monotonic and text bounded by UTF-8 bytes`() {
        assertEquals(ConversationSequence(1), ConversationSequence.INITIAL.next())
        assertEquals(ConversationSequence(2), ConversationSequence(1).next())
        assertFailsWith<IllegalArgumentException> { ConversationSequence(-1) }
        assertFailsWith<IllegalArgumentException> { TextMessageBody(" ") }
        assertFailsWith<IllegalArgumentException> { TextMessageBody("\u0000") }
        assertFailsWith<IllegalArgumentException> {
            TextMessageBody("ñ".repeat(TextMessageBody.MAX_UTF8_BYTES))
        }
        assertTrue(TextMessageBody("ñ".repeat(TextMessageBody.MAX_UTF8_BYTES / 2)).value.isNotEmpty())
    }

    @Test
    fun `computes the payload fingerprint on the server from message type and exact body bytes`() {
        val first = command(body = "Hello")
        val identical = command(body = "Hello")
        val changed = command(body = "hello")

        assertEquals(first.payloadFingerprint, identical.payloadFingerprint)
        assertTrue(first.payloadFingerprint != changed.payloadFingerprint)
        assertTrue(Regex("sha256:[0-9a-f]{64}").matches(first.payloadFingerprint.value))
    }

    private fun command(
        clientMessageRef: String = "client-message-alpha",
        idempotencyKey: String = "idempotency-alpha",
        body: String = "Hello",
    ) =
        SendTextMessageCommand(
            conversationRef = "conversation-alpha",
            senderSubjectRef = "client-alpha",
            identity =
                ClientMessageIdentity(
                    clientMessageRef = clientMessageRef,
                    idempotencyKey = idempotencyKey,
                ),
            body = TextMessageBody(body),
        )

    private fun record(
        serverMessageRef: String = "message-alpha",
        sequence: Long = 1,
    ) =
        MessageIdempotencyRecord(
            conversationRef = "conversation-alpha",
            senderSubjectRef = "client-alpha",
            identity =
                ClientMessageIdentity(
                    clientMessageRef = "client-message-alpha",
                    idempotencyKey = "idempotency-alpha",
                ),
            payloadFingerprint = command().payloadFingerprint,
            serverMessageRef = serverMessageRef,
            sequence = ConversationSequence(sequence),
        )
}
