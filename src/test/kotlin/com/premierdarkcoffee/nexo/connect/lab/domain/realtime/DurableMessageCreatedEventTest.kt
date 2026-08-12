package com.premierdarkcoffee.nexo.connect.lab.domain.realtime

import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.message.ConversationSequence
import com.premierdarkcoffee.nexo.connect.lab.domain.message.TextMessageBody
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFailsWith

class DurableMessageCreatedEventTest {
    @Test
    fun `requires a positive durable sequence and a participant sender`() {
        assertFailsWith<IllegalArgumentException> {
            event(sequence = ConversationSequence.INITIAL)
        }
        assertFailsWith<IllegalArgumentException> {
            event(senderActorType = ConnectActorType.ADMIN)
        }
    }

    private fun event(
        sequence: ConversationSequence = ConversationSequence(1),
        senderActorType: ConnectActorType = ConnectActorType.CLIENT,
    ) =
        DurableMessageCreatedEvent(
            conversationRef = "conversation-1",
            serverMessageRef = "message-1",
            sequence = sequence,
            senderSubjectRef = "subject-1",
            senderActorType = senderActorType,
            body = TextMessageBody("hello"),
            acceptedAtServer = Instant.parse("2026-08-12T09:45:00Z"),
        )
}
