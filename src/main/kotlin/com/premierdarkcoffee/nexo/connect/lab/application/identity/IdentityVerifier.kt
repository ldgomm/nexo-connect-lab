package com.premierdarkcoffee.nexo.connect.lab.application.identity

import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal

sealed interface IdentityVerificationResult {
    data class Authenticated(
        val principal: ConnectPrincipal,
    ) : IdentityVerificationResult

    data object Denied : IdentityVerificationResult
}

fun interface IdentityVerifier {
    fun verify(bearerToken: String): IdentityVerificationResult
}
