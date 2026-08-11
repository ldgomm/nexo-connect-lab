package com.premierdarkcoffee.nexo.connect.lab.domain.persistence

object PersistenceFieldLimits {
    const val OPAQUE_REF_MAX_UTF8_BYTES = 256
    const val INDEXED_IDENTITY_MAX_UTF8_BYTES = 256
}

internal fun requireBoundedPersistenceValue(
    value: String,
    fieldName: String,
    maxUtf8Bytes: Int,
) {
    require(value.isNotBlank()) { "$fieldName must not be blank" }
    require('\u0000' !in value) { "$fieldName must not contain NUL" }
    require(value.toByteArray(Charsets.UTF_8).size <= maxUtf8Bytes) {
        "$fieldName exceeds its UTF-8 byte limit"
    }
}
