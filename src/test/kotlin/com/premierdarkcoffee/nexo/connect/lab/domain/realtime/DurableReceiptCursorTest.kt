package com.premierdarkcoffee.nexo.connect.lab.domain.realtime

import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFailsWith

class DurableReceiptCursorTest {
    @Test
    fun `rejects read progress beyond durable delivery`() {
        assertFailsWith<IllegalArgumentException> {
            DurableReceiptCursor(
                conversationRef = "conversation-1",
                subjectRef = "client-subject",
                actorType = ConnectActorType.CLIENT,
                highestDeliveredSequence = 3,
                highestReadSequence = 4,
                deliveredAt = Instant.parse("2026-08-12T14:20:00Z"),
                readAt = Instant.parse("2026-08-12T14:21:00Z"),
                updatedAt = Instant.parse("2026-08-12T14:21:00Z"),
                version = 2,
            )
        }
    }
}
