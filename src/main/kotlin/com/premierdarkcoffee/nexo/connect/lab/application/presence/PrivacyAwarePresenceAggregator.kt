package com.premierdarkcoffee.nexo.connect.lab.application.presence

import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal
import kotlinx.serialization.Serializable

data class PresenceSubjectTarget(
    val subjectRef: String,
    val actorType: ConnectActorType,
    val platformScopeRef: String,
) {
    init {
        requireBoundedRef(subjectRef, "subjectRef", MAX_SUBJECT_REF_BYTES)
        requireBoundedRef(platformScopeRef, "platformScopeRef", MAX_SCOPE_REF_BYTES)
    }

    private fun requireBoundedRef(value: String, name: String, maximumBytes: Int) {
        require(value.isNotBlank() && '\u0000' !in value && value.toByteArray(Charsets.UTF_8).size <= maximumBytes) {
            "$name must be non-blank, NUL-free, and bounded"
        }
    }

    private companion object {
        const val MAX_SUBJECT_REF_BYTES = 256
        const val MAX_SCOPE_REF_BYTES = 128
    }
}

data class PresenceProjectionRequest(val viewer: ConnectPrincipal, val target: PresenceSubjectTarget)

enum class PresenceRelationship {
    SELF,
    ACTIVE_CONVERSATION_PARTICIPANT,
    DENIED,
}

enum class PresenceVisibilityMode {
    SHARE_COARSE,
    HIDE,
}

enum class PresenceActivitySnapshot {
    ONLINE,
    RECENTLY_ONLINE,
    OFFLINE,
    UNAVAILABLE,
}

@Serializable
enum class CoarsePresenceState {
    ONLINE,
    RECENTLY_ONLINE,
    OFFLINE,
    HIDDEN,
}

@Serializable
data class PresenceProjectionFrame(
    val schemaVersion: Int,
    val frameType: String,
    val subjectRef: String,
    val state: CoarsePresenceState,
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) { "Unsupported presence projection schema" }
        require(frameType == FRAME_TYPE) { "Unsupported presence projection frame type" }
        require(subjectRef.isNotBlank() && '\u0000' !in subjectRef) { "subjectRef must be safe and non-blank" }
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val FRAME_TYPE = "PRESENCE_CHANGED"
    }
}

sealed interface PresenceProjectionResult {
    data class Visible(val frame: PresenceProjectionFrame) : PresenceProjectionResult

    data object SilentNoFrame : PresenceProjectionResult
}

fun interface PresenceRelationshipPolicy {
    suspend fun relationship(request: PresenceProjectionRequest): PresenceRelationship
}

fun interface PresenceBlockPolicy {
    suspend fun isBlocked(request: PresenceProjectionRequest): Boolean
}

fun interface PresenceMutePolicy {
    suspend fun isMuted(request: PresenceProjectionRequest): Boolean
}

fun interface PresenceVisibilityPolicy {
    suspend fun visibility(request: PresenceProjectionRequest): PresenceVisibilityMode
}

fun interface EphemeralPresenceSnapshotReader {
    suspend fun read(target: PresenceSubjectTarget): PresenceActivitySnapshot
}

class PrivacyAwarePresenceAggregator(
    private val relationshipPolicy: PresenceRelationshipPolicy,
    private val blockPolicy: PresenceBlockPolicy,
    private val mutePolicy: PresenceMutePolicy,
    private val visibilityPolicy: PresenceVisibilityPolicy,
    private val snapshotReader: EphemeralPresenceSnapshotReader,
) {
    suspend fun project(request: PresenceProjectionRequest): PresenceProjectionResult {
        val relationship = relationshipPolicy.relationship(request)
        val blocked = blockPolicy.isBlocked(request)
        val muted = mutePolicy.isMuted(request)
        val visibility = visibilityPolicy.visibility(request)
        val activity = snapshotReader.read(request.target)

        if (relationship == PresenceRelationship.DENIED || blocked || muted) {
            return PresenceProjectionResult.SilentNoFrame
        }
        val state = when (visibility) {
            PresenceVisibilityMode.HIDE -> CoarsePresenceState.HIDDEN

            PresenceVisibilityMode.SHARE_COARSE -> activity.toVisibleState()
                ?: return PresenceProjectionResult.SilentNoFrame
        }
        return PresenceProjectionResult.Visible(
            PresenceProjectionFrame(
                schemaVersion = PresenceProjectionFrame.SCHEMA_VERSION,
                frameType = PresenceProjectionFrame.FRAME_TYPE,
                subjectRef = request.target.subjectRef,
                state = state,
            ),
        )
    }

    private fun PresenceActivitySnapshot.toVisibleState(): CoarsePresenceState? = when (this) {
        PresenceActivitySnapshot.ONLINE -> CoarsePresenceState.ONLINE
        PresenceActivitySnapshot.RECENTLY_ONLINE -> CoarsePresenceState.RECENTLY_ONLINE
        PresenceActivitySnapshot.OFFLINE -> CoarsePresenceState.OFFLINE
        PresenceActivitySnapshot.UNAVAILABLE -> null
    }
}

object DenyAllPresenceRelationships : PresenceRelationshipPolicy {
    override suspend fun relationship(request: PresenceProjectionRequest): PresenceRelationship =
        PresenceRelationship.DENIED
}

object NoBlockedPresenceRelationships : PresenceBlockPolicy {
    override suspend fun isBlocked(request: PresenceProjectionRequest): Boolean = false
}

object NoMutedPresenceRelationships : PresenceMutePolicy {
    override suspend fun isMuted(request: PresenceProjectionRequest): Boolean = false
}

object ShareCoarsePresenceVisibility : PresenceVisibilityPolicy {
    override suspend fun visibility(request: PresenceProjectionRequest): PresenceVisibilityMode =
        PresenceVisibilityMode.SHARE_COARSE
}
