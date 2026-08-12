package com.premierdarkcoffee.nexo.connect.lab.domain.persistence

data class BusinessClientConversationKeyPersistenceRecord(
    val platformScopeRef: String,
    val organizationScopeRef: String,
    val businessScopeRef: String,
    val businessSubjectRef: String,
    val clientSubjectRef: String,
    val conversationRef: String,
) {
    init {
        listOf(
            "platformScopeRef" to platformScopeRef,
            "organizationScopeRef" to organizationScopeRef,
            "businessScopeRef" to businessScopeRef,
            "businessSubjectRef" to businessSubjectRef,
            "clientSubjectRef" to clientSubjectRef,
            "conversationRef" to conversationRef,
        ).forEach { (fieldName, value) ->
            requireBoundedPersistenceValue(
                value,
                fieldName,
                PersistenceFieldLimits.OPAQUE_REF_MAX_UTF8_BYTES,
            )
        }
        require(businessSubjectRef != clientSubjectRef) {
            "Business and client subjects must be distinct"
        }
    }
}
