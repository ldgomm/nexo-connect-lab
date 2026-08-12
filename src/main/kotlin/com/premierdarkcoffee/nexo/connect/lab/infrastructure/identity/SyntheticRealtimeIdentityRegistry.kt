package com.premierdarkcoffee.nexo.connect.lab.infrastructure.identity

import com.premierdarkcoffee.nexo.connect.lab.application.identity.IdentityVerifier
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal

object SyntheticRealtimeIdentityRegistry {
    private const val BUSINESS_TOKEN_KEY = "CONNECT_LAB_SYNTHETIC_BUSINESS_TOKEN"
    private const val CLIENT_TOKEN_KEY = "CONNECT_LAB_SYNTHETIC_CLIENT_TOKEN"

    fun fromEnvironment(environment: Map<String, String> = System.getenv()): IdentityVerifier {
        fun requiredToken(key: String): String =
            environment[key]?.trim()?.takeIf { it.length >= 32 }
                ?: error("Missing or unsafe synthetic realtime token: $key")

        val businessToken = requiredToken(BUSINESS_TOKEN_KEY)
        val clientToken = requiredToken(CLIENT_TOKEN_KEY)
        require(businessToken != clientToken) { "Synthetic realtime tokens must be distinct" }

        return SyntheticTokenVerifier(
            mapOf(
                businessToken to
                    ConnectPrincipal(
                        subjectRef = "synthetic-business-c1",
                        actorType = ConnectActorType.BUSINESS,
                        platformScopeRef = "synthetic-platform-c1",
                        organizationScopeRef = "synthetic-organization-c1",
                        businessScopeRef = "synthetic-business-scope-c1",
                    ),
                clientToken to
                    ConnectPrincipal(
                        subjectRef = "synthetic-client-c1",
                        actorType = ConnectActorType.CLIENT,
                        platformScopeRef = "synthetic-platform-c1",
                    ),
            ),
        )
    }
}
