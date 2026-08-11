package com.premierdarkcoffee.nexo.connect.lab.application.message

enum class DurableTextAuthorizationDecision {
    ALLOW,
    DENY_COMMAND_SCOPE,
    DENY_SCOPE_OR_MEMBERSHIP,
    DENY_CONVERSATION_STATE,
    DENY_PARTICIPANT_STATE,
    DENY_CAPABILITY,
}
