package com.premierdarkcoffee.nexo.connect.lab.application.conversation

import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationAccessScope
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal

enum class ConversationAccessDecision {
    ALLOW,
    DENY,
}

class ConversationParticipantAuthorizer {
    fun decide(
        principal: ConnectPrincipal,
        scope: ConversationAccessScope,
    ): ConversationAccessDecision {
        if (!scope.type.isImplemented || principal.platformScopeRef != scope.platformScopeRef) {
            return ConversationAccessDecision.DENY
        }

        val participant =
            scope.participants.singleOrNull { candidate ->
                candidate.subjectRef == principal.subjectRef &&
                    candidate.actorType == principal.actorType
            } ?: return ConversationAccessDecision.DENY

        return when (participant.actorType) {
            ConnectActorType.CLIENT ->
                ConversationAccessDecision.ALLOW

            ConnectActorType.BUSINESS ->
                if (
                    principal.organizationScopeRef == scope.organizationScopeRef &&
                    principal.businessScopeRef == scope.businessScopeRef
                ) {
                    ConversationAccessDecision.ALLOW
                } else {
                    ConversationAccessDecision.DENY
                }

            ConnectActorType.SUPERADMIN,
            ConnectActorType.ADMIN,
            -> ConversationAccessDecision.DENY
        }
    }
}
