package com.premierdarkcoffee.nexo.connect.lab.domain.conversation

import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType

enum class ConversationType(
    val participantActorTypes: Set<ConnectActorType>,
    val isImplemented: Boolean,
) {
    BUSINESS_CLIENT(
        participantActorTypes = setOf(ConnectActorType.BUSINESS, ConnectActorType.CLIENT),
        isImplemented = true,
    ),
    SUPERADMIN_ADMIN(
        participantActorTypes = setOf(ConnectActorType.SUPERADMIN, ConnectActorType.ADMIN),
        isImplemented = false,
    ),
    SUPERADMIN_BUSINESS(
        participantActorTypes = setOf(ConnectActorType.SUPERADMIN, ConnectActorType.BUSINESS),
        isImplemented = false,
    ),
    ADMIN_BUSINESS(
        participantActorTypes = setOf(ConnectActorType.ADMIN, ConnectActorType.BUSINESS),
        isImplemented = false,
    ),
}
