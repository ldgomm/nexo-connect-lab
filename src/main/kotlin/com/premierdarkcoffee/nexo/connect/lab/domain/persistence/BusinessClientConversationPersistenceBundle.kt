package com.premierdarkcoffee.nexo.connect.lab.domain.persistence

import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationCapability
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationParticipantStatus
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationStatus
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationType
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.message.ConversationSequence

data class BusinessClientConversationPersistenceBundle(
    val conversation: ConversationPersistenceRecord,
    val businessParticipant: ConversationParticipantPersistenceRecord,
    val clientParticipant: ConversationParticipantPersistenceRecord,
    val directKey: BusinessClientConversationKeyPersistenceRecord,
) {
    init {
        require(conversation.type == ConversationType.BUSINESS_CLIENT) {
            "Only business-client conversations may use a direct key"
        }
        require(conversation.status == ConversationStatus.ACTIVE) {
            "A new business-client conversation must be active"
        }
        require(conversation.lastMessageSequence == ConversationSequence.INITIAL && conversation.version == 0L) {
            "A new conversation must start without messages or mutations"
        }
        require(businessParticipant.actorType == ConnectActorType.BUSINESS) {
            "The business participant must use the BUSINESS actor type"
        }
        require(clientParticipant.actorType == ConnectActorType.CLIENT) {
            "The client participant must use the CLIENT actor type"
        }
        require(businessParticipant.status == ConversationParticipantStatus.ACTIVE) {
            "The business participant must start active"
        }
        require(clientParticipant.status == ConversationParticipantStatus.ACTIVE) {
            "The client participant must start active"
        }
        require(ConversationCapability.SEND_TEXT in businessParticipant.capabilities) {
            "The business participant requires SEND_TEXT"
        }
        require(ConversationCapability.SEND_TEXT in clientParticipant.capabilities) {
            "The client participant requires SEND_TEXT"
        }
        require(businessParticipant.conversationRef == conversation.conversationRef) {
            "Business membership must belong to the conversation"
        }
        require(clientParticipant.conversationRef == conversation.conversationRef) {
            "Client membership must belong to the conversation"
        }
        require(businessParticipant.joinedAt == conversation.createdAt) {
            "Business membership must start with the conversation"
        }
        require(clientParticipant.joinedAt == conversation.createdAt) {
            "Client membership must start with the conversation"
        }
        require(directKey.conversationRef == conversation.conversationRef) {
            "Direct key must reference the conversation"
        }
        require(directKey.platformScopeRef == conversation.platformScopeRef) {
            "Direct key platform scope must match the conversation"
        }
        require(directKey.organizationScopeRef == conversation.organizationScopeRef) {
            "Direct key organization scope must match the conversation"
        }
        require(directKey.businessScopeRef == conversation.businessScopeRef) {
            "Direct key business scope must match the conversation"
        }
        require(directKey.businessSubjectRef == businessParticipant.subjectRef) {
            "Direct key business subject must match membership"
        }
        require(directKey.clientSubjectRef == clientParticipant.subjectRef) {
            "Direct key client subject must match membership"
        }
    }
}
