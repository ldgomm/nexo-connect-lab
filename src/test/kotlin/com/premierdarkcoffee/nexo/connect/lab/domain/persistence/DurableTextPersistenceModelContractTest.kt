package com.premierdarkcoffee.nexo.connect.lab.domain.persistence

import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DurableAcknowledgementBoundary
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DurablePersistenceConstraint
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DurableTextWriteContract
import com.premierdarkcoffee.nexo.connect.lab.application.persistence.DurableTextWriteStage
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationCapability
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationParticipantStatus
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationStatus
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationType
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.message.ClientMessageIdentity
import com.premierdarkcoffee.nexo.connect.lab.domain.message.ConversationSequence
import com.premierdarkcoffee.nexo.connect.lab.domain.message.MessagePayloadFingerprint
import com.premierdarkcoffee.nexo.connect.lab.domain.message.TextMessageBody
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class DurableTextPersistenceModelContractTest {
    private val acceptedAt = Instant.parse("2026-08-11T17:00:00Z")

    @Test
    fun `freezes the single transaction order and acknowledgement boundary`() {
        assertEquals(
            listOf(
                DurableTextWriteStage.LOCK_CONVERSATION,
                DurableTextWriteStage.LOCK_SENDER_PARTICIPANT,
                DurableTextWriteStage.RECHECK_AUTHORIZATION,
                DurableTextWriteStage.RESOLVE_IDEMPOTENCY,
                DurableTextWriteStage.ALLOCATE_NEXT_SEQUENCE,
                DurableTextWriteStage.INSERT_MESSAGE,
                DurableTextWriteStage.INSERT_IDEMPOTENCY_BINDING,
                DurableTextWriteStage.INSERT_NOTIFICATION_OUTBOX_INTENTS,
                DurableTextWriteStage.COMMIT,
            ),
            DurableTextWriteContract.transactionStages,
        )
        assertEquals(
            DurableAcknowledgementBoundary.AFTER_TRANSACTION_COMMIT,
            DurableTextWriteContract.acknowledgementBoundary,
        )
        assertEquals(
            setOf(
                DurablePersistenceConstraint.CONVERSATION_REF_PRIMARY_KEY,
                DurablePersistenceConstraint.PARTICIPANT_CONVERSATION_SUBJECT_UNIQUE,
                DurablePersistenceConstraint.SERVER_MESSAGE_REF_PRIMARY_KEY,
                DurablePersistenceConstraint.CONVERSATION_SEQUENCE_UNIQUE,
                DurablePersistenceConstraint.IDEMPOTENCY_PLATFORM_SENDER_KEY_UNIQUE,
                DurablePersistenceConstraint.CLIENT_MESSAGE_PLATFORM_SENDER_REF_UNIQUE,
                DurablePersistenceConstraint.MESSAGE_IDENTITY_BINDING_ONE_TO_ONE,
                DurablePersistenceConstraint.MESSAGE_SENDER_PARTICIPANT_FOREIGN_KEY,
                DurablePersistenceConstraint.IDENTITY_MESSAGE_FOREIGN_KEY,
                DurablePersistenceConstraint.NOTIFICATION_MESSAGE_TARGET_UNIQUE,
                DurablePersistenceConstraint.NOTIFICATION_MESSAGE_FOREIGN_KEY,
                DurablePersistenceConstraint.NOTIFICATION_REGISTRATION_FOREIGN_KEY,
            ),
            DurableTextWriteContract.persistenceConstraints,
        )
        assertFalse(DurableTextWriteContract.claimsExactlyOnceDelivery)
        assertFalse(DurableTextWriteContract.claimsGlobalMessageOrder)
    }

    @Test
    fun `accepts one coherent post-write durable text bundle`() {
        val bundle = bundle()

        assertEquals("conversation-alpha", bundle.conversation.conversationRef)
        assertEquals(ConversationSequence(1), bundle.message.sequence)
        assertEquals(bundle.message.payloadFingerprint, bundle.identityBinding.payloadFingerprint)
    }

    @Test
    fun `rejects every cross-conversation or cross-platform binding`() {
        assertFailsWith<IllegalArgumentException> {
            bundle(participant = participant(conversationRef = "conversation-other"))
        }
        assertFailsWith<IllegalArgumentException> {
            bundle(message = message(conversationRef = "conversation-other"))
        }
        assertFailsWith<IllegalArgumentException> {
            bundle(identityBinding = identityBinding(platformScopeRef = "platform-other"))
        }
        assertFailsWith<IllegalArgumentException> {
            bundle(identityBinding = identityBinding(conversationRef = "conversation-other"))
        }
    }

    @Test
    fun `rejects divergent sender sequence message and payload identity`() {
        assertFailsWith<IllegalArgumentException> {
            bundle(message = message(senderSubjectRef = "client-other"))
        }
        assertFailsWith<IllegalArgumentException> {
            bundle(message = message(senderActorType = ConnectActorType.BUSINESS))
        }
        assertFailsWith<IllegalArgumentException> {
            bundle(conversation = conversation(sequence = 2))
        }
        assertFailsWith<IllegalArgumentException> {
            bundle(identityBinding = identityBinding(serverMessageRef = "message-other"))
        }
        assertFailsWith<IllegalArgumentException> {
            bundle(identityBinding = identityBinding(sequence = 2))
        }
        assertFailsWith<IllegalArgumentException> {
            bundle(
                identityBinding =
                identityBinding(
                    payloadFingerprint = MessagePayloadFingerprint.forText(TextMessageBody("Changed")),
                ),
            )
        }
    }

    @Test
    fun `rejects a non-writable conversation or unauthorized persisted sender`() {
        assertFailsWith<IllegalArgumentException> {
            bundle(conversation = conversation(status = ConversationStatus.BLOCKED))
        }
        assertFailsWith<IllegalArgumentException> {
            bundle(
                participant =
                participant(
                    status = ConversationParticipantStatus.LEFT,
                    leftAt = acceptedAt,
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            bundle(participant = participant(capabilities = emptySet()))
        }
        assertFailsWith<IllegalArgumentException> {
            bundle(participant = participant(actorType = ConnectActorType.SUPERADMIN))
        }
    }

    @Test
    fun `scopes both uniqueness identities by platform and sender`() {
        val canonical = identityBinding()
        val anotherConversation = identityBinding(conversationRef = "conversation-other")
        val anotherSender = identityBinding(senderSubjectRef = "client-other")
        val anotherPlatform = identityBinding(platformScopeRef = "platform-other")

        assertEquals(canonical.idempotencyLookupKey(), anotherConversation.idempotencyLookupKey())
        assertEquals(canonical.clientMessageLookupKey(), anotherConversation.clientMessageLookupKey())
        assertNotEquals(canonical.idempotencyLookupKey(), anotherSender.idempotencyLookupKey())
        assertNotEquals(canonical.clientMessageLookupKey(), anotherSender.clientMessageLookupKey())
        assertNotEquals(canonical.idempotencyLookupKey(), anotherPlatform.idempotencyLookupKey())
        assertNotEquals(canonical.clientMessageLookupKey(), anotherPlatform.clientMessageLookupKey())
    }

    @Test
    fun `rejects future conversation persistence and invalid participant lifecycle`() {
        assertFailsWith<IllegalArgumentException> {
            conversation(type = ConversationType.SUPERADMIN_BUSINESS)
        }
        assertFailsWith<IllegalArgumentException> {
            participant(status = ConversationParticipantStatus.LEFT, leftAt = null)
        }
        assertFailsWith<IllegalArgumentException> {
            participant(status = ConversationParticipantStatus.ACTIVE, leftAt = acceptedAt)
        }
        assertFailsWith<IllegalArgumentException> {
            participant(
                status = ConversationParticipantStatus.LEFT,
                leftAt = acceptedAt.minusSeconds(2),
            )
        }
    }

    @Test
    fun `bounds opaque references and indexed identities by exact UTF-8 bytes`() {
        assertFailsWith<IllegalArgumentException> {
            conversation(conversationRef = "ñ".repeat(129))
        }
        assertFailsWith<IllegalArgumentException> {
            identityBinding(idempotencyKey = "ñ".repeat(129))
        }
        assertFailsWith<IllegalArgumentException> {
            identityBinding(clientMessageRef = "\u0000")
        }
    }

    private fun bundle(
        conversation: ConversationPersistenceRecord = conversation(),
        participant: ConversationParticipantPersistenceRecord = participant(),
        message: TextMessagePersistenceRecord = message(),
        identityBinding: MessageIdentityPersistenceRecord = identityBinding(),
    ): DurableTextPersistenceBundle = DurableTextPersistenceBundle(
        conversation = conversation,
        senderParticipant = participant,
        message = message,
        identityBinding = identityBinding,
    )

    private fun conversation(
        conversationRef: String = "conversation-alpha",
        type: ConversationType = ConversationType.BUSINESS_CLIENT,
        status: ConversationStatus = ConversationStatus.ACTIVE,
        sequence: Long = 1,
    ): ConversationPersistenceRecord = ConversationPersistenceRecord(
        conversationRef = conversationRef,
        type = type,
        platformScopeRef = "platform-alpha",
        organizationScopeRef = "organization-alpha",
        businessScopeRef = "business-alpha",
        status = status,
        createdAt = acceptedAt.minusSeconds(60),
        lastMessageSequence = ConversationSequence(sequence),
        version = 1,
    )

    private fun participant(
        conversationRef: String = "conversation-alpha",
        status: ConversationParticipantStatus = ConversationParticipantStatus.ACTIVE,
        capabilities: Set<ConversationCapability> = setOf(ConversationCapability.SEND_TEXT),
        leftAt: Instant? = null,
        actorType: ConnectActorType = ConnectActorType.CLIENT,
    ): ConversationParticipantPersistenceRecord = ConversationParticipantPersistenceRecord(
        conversationRef = conversationRef,
        subjectRef = "client-alpha",
        actorType = actorType,
        status = status,
        capabilities = capabilities,
        joinedAt = acceptedAt.minusSeconds(1),
        leftAt = leftAt,
    )

    private fun message(
        conversationRef: String = "conversation-alpha",
        sequence: Long = 1,
        senderSubjectRef: String = "client-alpha",
        senderActorType: ConnectActorType = ConnectActorType.CLIENT,
    ): TextMessagePersistenceRecord = TextMessagePersistenceRecord(
        serverMessageRef = "message-alpha",
        conversationRef = conversationRef,
        sequence = ConversationSequence(sequence),
        senderSubjectRef = senderSubjectRef,
        senderActorType = senderActorType,
        body = TextMessageBody("Hello"),
        acceptedAtServer = acceptedAt,
    )

    private fun identityBinding(
        platformScopeRef: String = "platform-alpha",
        conversationRef: String = "conversation-alpha",
        senderSubjectRef: String = "client-alpha",
        serverMessageRef: String = "message-alpha",
        sequence: Long = 1,
        payloadFingerprint: MessagePayloadFingerprint =
            MessagePayloadFingerprint.forText(TextMessageBody("Hello")),
        clientMessageRef: String = "client-message-alpha",
        idempotencyKey: String = "idempotency-alpha",
    ): MessageIdentityPersistenceRecord = MessageIdentityPersistenceRecord(
        platformScopeRef = platformScopeRef,
        conversationRef = conversationRef,
        senderSubjectRef = senderSubjectRef,
        identity =
        ClientMessageIdentity(
            clientMessageRef = clientMessageRef,
            idempotencyKey = idempotencyKey,
        ),
        payloadFingerprint = payloadFingerprint,
        serverMessageRef = serverMessageRef,
        sequence = ConversationSequence(sequence),
    )
}
