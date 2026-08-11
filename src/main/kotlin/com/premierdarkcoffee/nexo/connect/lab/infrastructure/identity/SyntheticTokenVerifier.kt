package com.premierdarkcoffee.nexo.connect.lab.infrastructure.identity

import com.premierdarkcoffee.nexo.connect.lab.application.identity.IdentityVerificationResult
import com.premierdarkcoffee.nexo.connect.lab.application.identity.IdentityVerifier
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal

class SyntheticTokenVerifier(
    private val principalsByToken: Map<String, ConnectPrincipal>,
) : IdentityVerifier {
    init {
        require(principalsByToken.keys.none(String::isBlank)) {
            "Synthetic token identities must not use blank tokens"
        }
    }

    override fun verify(bearerToken: String): IdentityVerificationResult {
        val principal = principalsByToken[bearerToken] ?: return IdentityVerificationResult.Denied
        return IdentityVerificationResult.Authenticated(principal)
    }
}
