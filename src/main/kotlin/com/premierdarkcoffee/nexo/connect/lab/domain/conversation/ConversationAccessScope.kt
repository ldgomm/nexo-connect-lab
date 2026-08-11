package com.premierdarkcoffee.nexo.connect.lab.domain.conversation

data class ConversationAccessScope(
    val conversationRef: String,
    val type: ConversationType,
    val platformScopeRef: String,
    val organizationScopeRef: String,
    val businessScopeRef: String? = null,
    val participants: Set<ConversationParticipant>,
) {
    init {
        require(conversationRef.isNotBlank()) { "conversationRef must not be blank" }
        require(platformScopeRef.isNotBlank()) { "platformScopeRef must not be blank" }
        require(organizationScopeRef.isNotBlank()) { "organizationScopeRef must not be blank" }
        require(businessScopeRef?.isNotBlank() != false) {
            "businessScopeRef must be null or non-blank"
        }
        require(participants.isNotEmpty()) { "At least one participant is required" }
        require(participants.map(ConversationParticipant::subjectRef).distinct().size == participants.size) {
            "A subject may appear only once in a conversation"
        }
        require(participants.map(ConversationParticipant::actorType).toSet() == type.participantActorTypes) {
            "Conversation participants must match the declared conversation type"
        }

        when (type) {
            ConversationType.SUPERADMIN_ADMIN ->
                require(businessScopeRef == null) {
                    "Superadmin-admin conversations must remain organization-scoped"
                }

            ConversationType.BUSINESS_CLIENT,
            ConversationType.SUPERADMIN_BUSINESS,
            ConversationType.ADMIN_BUSINESS,
            ->
                require(businessScopeRef != null) {
                    "This conversation type requires a business scope"
                }
        }
    }
}
