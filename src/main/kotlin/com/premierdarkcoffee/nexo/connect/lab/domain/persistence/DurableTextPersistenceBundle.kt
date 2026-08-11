package com.premierdarkcoffee.nexo.connect.lab.domain.persistence

import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationCapability
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationParticipantStatus

data class DurableTextPersistenceBundle(
    val conversation: ConversationPersistenceRecord,
    val senderParticipant: ConversationParticipantPersistenceRecord,
    val message: TextMessagePersistenceRecord,
    val identityBinding: MessageIdentityPersistenceRecord,
) {
    init {
        require(conversation.status.acceptsDurableText) {
            "Conversation state does not permit durable text"
        }
        require(conversation.version > 0) {
            "The post-write conversation version must be positive"
        }
        require(senderParticipant.status == ConversationParticipantStatus.ACTIVE) {
            "Only an active participant may be the persisted sender"
        }
        require(ConversationCapability.SEND_TEXT in senderParticipant.capabilities) {
            "The persisted sender requires SEND_TEXT"
        }
        require(senderParticipant.conversationRef == conversation.conversationRef) {
            "Participant conversation must match the persisted conversation"
        }
        require(message.conversationRef == conversation.conversationRef) {
            "Message conversation must match the persisted conversation"
        }
        require(message.senderSubjectRef == senderParticipant.subjectRef) {
            "Message sender subject must match the persisted participant"
        }
        require(message.senderActorType == senderParticipant.actorType) {
            "Message sender actor type must match the persisted participant"
        }
        require(senderParticipant.actorType in conversation.type.participantActorTypes) {
            "Persisted sender actor type must belong to the conversation topology"
        }
        require(conversation.lastMessageSequence == message.sequence) {
            "The post-write conversation sequence must match the message sequence"
        }
        require(!message.acceptedAtServer.isBefore(conversation.createdAt)) {
            "Message acceptance cannot precede conversation creation"
        }
        require(!message.acceptedAtServer.isBefore(senderParticipant.joinedAt)) {
            "Message acceptance cannot precede participant membership"
        }
        require(identityBinding.platformScopeRef == conversation.platformScopeRef) {
            "Identity platform scope must match the persisted conversation"
        }
        require(identityBinding.conversationRef == message.conversationRef) {
            "Identity conversation must match the persisted message"
        }
        require(identityBinding.senderSubjectRef == message.senderSubjectRef) {
            "Identity sender must match the persisted message"
        }
        require(identityBinding.serverMessageRef == message.serverMessageRef) {
            "Identity binding must reference the persisted server message"
        }
        require(identityBinding.sequence == message.sequence) {
            "Identity binding sequence must match the persisted message"
        }
        require(identityBinding.payloadFingerprint == message.payloadFingerprint) {
            "Identity fingerprint must match the persisted message payload"
        }
    }
}
