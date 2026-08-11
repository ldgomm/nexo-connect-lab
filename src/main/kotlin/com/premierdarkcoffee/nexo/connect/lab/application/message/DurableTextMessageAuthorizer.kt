package com.premierdarkcoffee.nexo.connect.lab.application.message

import com.premierdarkcoffee.nexo.connect.lab.application.conversation.ConversationAccessDecision
import com.premierdarkcoffee.nexo.connect.lab.application.conversation.ConversationParticipantAuthorizer
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationCapability
import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.DurableTextAuthorizationContext
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal
import com.premierdarkcoffee.nexo.connect.lab.domain.message.SendTextMessageCommand

class DurableTextMessageAuthorizer(
    private val participantAuthorizer: ConversationParticipantAuthorizer = ConversationParticipantAuthorizer(),
) {
    fun decide(
        principal: ConnectPrincipal,
        command: SendTextMessageCommand,
        context: DurableTextAuthorizationContext,
    ): DurableTextAuthorizationDecision {
        if (
            command.conversationRef != context.scope.conversationRef ||
            command.senderSubjectRef != principal.subjectRef
        ) {
            return DurableTextAuthorizationDecision.DENY_COMMAND_SCOPE
        }

        if (participantAuthorizer.decide(principal, context.scope) != ConversationAccessDecision.ALLOW) {
            return DurableTextAuthorizationDecision.DENY_SCOPE_OR_MEMBERSHIP
        }

        if (!context.conversationStatus.acceptsDurableText) {
            return DurableTextAuthorizationDecision.DENY_CONVERSATION_STATE
        }

        val participantState =
            context.stateFor(principal)
                ?: return DurableTextAuthorizationDecision.DENY_SCOPE_OR_MEMBERSHIP

        if (!participantState.status.canSendDurableText) {
            return DurableTextAuthorizationDecision.DENY_PARTICIPANT_STATE
        }

        if (ConversationCapability.SEND_TEXT !in participantState.capabilities) {
            return DurableTextAuthorizationDecision.DENY_CAPABILITY
        }

        return DurableTextAuthorizationDecision.ALLOW
    }
}
