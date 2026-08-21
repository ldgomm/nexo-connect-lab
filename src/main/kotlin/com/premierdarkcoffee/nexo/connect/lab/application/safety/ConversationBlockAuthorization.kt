package com.premierdarkcoffee.nexo.connect.lab.application.safety

import com.premierdarkcoffee.nexo.connect.lab.domain.safety.ConversationSafetyParticipant
import com.premierdarkcoffee.nexo.connect.lab.domain.safety.ConversationSafetyScope

data class ConversationBlockAuthorizationRequest(
    val scope: ConversationSafetyScope,
    val first: ConversationSafetyParticipant,
    val second: ConversationSafetyParticipant,
) {
    init {
        require(first != second) { "Block authorization requires two distinct participants" }
    }
}

sealed interface ConversationBlockLookupResult {
    data object Clear : ConversationBlockLookupResult

    data object ActiveBlock : ConversationBlockLookupResult

    data object NotFoundOrDenied : ConversationBlockLookupResult

    data object Unavailable : ConversationBlockLookupResult
}

fun interface ConversationBlockLookupPort {
    fun lookup(request: ConversationBlockAuthorizationRequest): ConversationBlockLookupResult
}

enum class ConversationBlockAuthorizationDecision(val allowsCommunication: Boolean) {
    ALLOW(true),
    DENY_ACTIVE_BLOCK(false),
    DENY_NOT_FOUND_OR_SCOPE(false),
    DENY_AUTHORITY_UNAVAILABLE(false),
}

fun interface ConversationBlockAuthorizationPort {
    fun authorize(request: ConversationBlockAuthorizationRequest): ConversationBlockAuthorizationDecision
}

class DenyByDefaultConversationBlockAuthorizer(private val lookupPort: ConversationBlockLookupPort) :
    ConversationBlockAuthorizationPort {
    override fun authorize(request: ConversationBlockAuthorizationRequest): ConversationBlockAuthorizationDecision {
        val lookup = try {
            lookupPort.lookup(request)
        } catch (_: Exception) {
            ConversationBlockLookupResult.Unavailable
        }
        return when (lookup) {
            ConversationBlockLookupResult.Clear -> ConversationBlockAuthorizationDecision.ALLOW

            ConversationBlockLookupResult.ActiveBlock -> ConversationBlockAuthorizationDecision.DENY_ACTIVE_BLOCK

            ConversationBlockLookupResult.NotFoundOrDenied ->
                ConversationBlockAuthorizationDecision.DENY_NOT_FOUND_OR_SCOPE

            ConversationBlockLookupResult.Unavailable ->
                ConversationBlockAuthorizationDecision.DENY_AUTHORITY_UNAVAILABLE
        }
    }
}
