package com.premierdarkcoffee.nexo.connect.lab.domain.persistence

import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationCapability
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationParticipantStatus
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationStatus
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationType
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.message.ConversationSequence
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFailsWith

class BusinessClientConversationPersistenceBundleTest {
    @Test
    fun `accepts one internally consistent new business client conversation`() {
        bundle()
    }

    @Test
    fun `rejects a direct key bound to another client`() {
        val valid = bundle()

        assertFailsWith<IllegalArgumentException> {
            valid.copy(
                directKey = valid.directKey.copy(clientSubjectRef = "client-subject-other"),
            )
        }
    }

    @Test
    fun `rejects a new conversation that already consumed a sequence`() {
        val valid = bundle()

        assertFailsWith<IllegalArgumentException> {
            valid.copy(
                conversation = valid.conversation.copy(lastMessageSequence = ConversationSequence(1), version = 1),
            )
        }
    }

    private fun bundle(): BusinessClientConversationPersistenceBundle {
        val conversation =
            ConversationPersistenceRecord(
                conversationRef = "conversation-1",
                type = ConversationType.BUSINESS_CLIENT,
                platformScopeRef = "platform-1",
                organizationScopeRef = "organization-1",
                businessScopeRef = "business-1",
                status = ConversationStatus.ACTIVE,
                createdAt = CREATED_AT,
                lastMessageSequence = ConversationSequence.INITIAL,
                version = 0,
            )
        val businessParticipant = participant("business-subject-1", ConnectActorType.BUSINESS)
        val clientParticipant = participant("client-subject-1", ConnectActorType.CLIENT)
        val directKey =
            BusinessClientConversationKeyPersistenceRecord(
                platformScopeRef = conversation.platformScopeRef,
                organizationScopeRef = conversation.organizationScopeRef,
                businessScopeRef = conversation.businessScopeRef,
                businessSubjectRef = businessParticipant.subjectRef,
                clientSubjectRef = clientParticipant.subjectRef,
                conversationRef = conversation.conversationRef,
            )

        return BusinessClientConversationPersistenceBundle(
            conversation = conversation,
            businessParticipant = businessParticipant,
            clientParticipant = clientParticipant,
            directKey = directKey,
        )
    }

    private fun participant(
        subjectRef: String,
        actorType: ConnectActorType,
    ): ConversationParticipantPersistenceRecord =
        ConversationParticipantPersistenceRecord(
            conversationRef = "conversation-1",
            subjectRef = subjectRef,
            actorType = actorType,
            status = ConversationParticipantStatus.ACTIVE,
            capabilities = setOf(ConversationCapability.SEND_TEXT),
            joinedAt = CREATED_AT,
        )

    companion object {
        private val CREATED_AT = Instant.parse("2026-08-11T22:00:00Z")
    }
}
