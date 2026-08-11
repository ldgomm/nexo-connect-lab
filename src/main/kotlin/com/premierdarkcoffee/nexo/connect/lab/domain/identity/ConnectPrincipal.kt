package com.premierdarkcoffee.nexo.connect.lab.domain.identity

enum class ConnectActorRole {
    CLIENT,
    BUSINESS_AGENT,
}

data class ConnectPrincipal(
    val subjectRef: String,
    val role: ConnectActorRole,
    val businessScopeRef: String? = null,
) {
    init {
        require(subjectRef.isNotBlank()) { "subjectRef must not be blank" }

        when (role) {
            ConnectActorRole.CLIENT ->
                require(businessScopeRef == null) {
                    "Client identity must not claim a business scope"
                }

            ConnectActorRole.BUSINESS_AGENT ->
                require(!businessScopeRef.isNullOrBlank()) {
                    "Business agent identity requires a business scope"
                }
        }
    }
}
