package com.premierdarkcoffee.nexo.connect.lab.domain.message

data class ClientMessageIdentity(
    val clientMessageRef: String,
    val idempotencyKey: String,
) {
    init {
        require(clientMessageRef.isNotBlank()) { "clientMessageRef must not be blank" }
        require(idempotencyKey.isNotBlank()) { "idempotencyKey must not be blank" }
    }
}
