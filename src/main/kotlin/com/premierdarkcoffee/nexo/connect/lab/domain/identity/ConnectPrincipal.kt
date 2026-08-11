package com.premierdarkcoffee.nexo.connect.lab.domain.identity

data class ConnectPrincipal(
    val subjectRef: String,
    val actorType: ConnectActorType,
    val platformScopeRef: String,
    val organizationScopeRef: String? = null,
    val businessScopeRef: String? = null,
) {
    init {
        require(subjectRef.isNotBlank()) { "subjectRef must not be blank" }
        require(platformScopeRef.isNotBlank()) { "platformScopeRef must not be blank" }
        require(organizationScopeRef?.isNotBlank() != false) {
            "organizationScopeRef must be null or non-blank"
        }
        require(businessScopeRef?.isNotBlank() != false) {
            "businessScopeRef must be null or non-blank"
        }

        when (actorType) {
            ConnectActorType.SUPERADMIN -> {
                require(organizationScopeRef == null && businessScopeRef == null) {
                    "Superadmin identity must remain platform-scoped"
                }
            }

            ConnectActorType.ADMIN -> {
                require(organizationScopeRef != null && businessScopeRef == null) {
                    "Admin identity requires only an organization scope"
                }
            }

            ConnectActorType.BUSINESS -> {
                require(organizationScopeRef != null && businessScopeRef != null) {
                    "Business identity requires organization and business scopes"
                }
            }

            ConnectActorType.CLIENT -> {
                require(organizationScopeRef == null && businessScopeRef == null) {
                    "Client identity must remain participant-scoped"
                }
            }
        }
    }
}
