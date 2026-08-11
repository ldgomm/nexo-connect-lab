package com.premierdarkcoffee.nexo.connect.lab.application.conversation

import com.premierdarkcoffee.nexo.connect.lab.domain.conversation.ConversationAccessScope
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorRole
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
        if (principal.subjectRef !in scope.participantSubjectRefs) {
            return ConversationAccessDecision.DENY
        }

        return when (principal.role) {
            ConnectActorRole.CLIENT -> ConversationAccessDecision.ALLOW
            ConnectActorRole.BUSINESS_AGENT ->
                if (principal.businessScopeRef == scope.publicBusinessRef) {
                    ConversationAccessDecision.ALLOW
                } else {
                    ConversationAccessDecision.DENY
                }
        }
    }
}
