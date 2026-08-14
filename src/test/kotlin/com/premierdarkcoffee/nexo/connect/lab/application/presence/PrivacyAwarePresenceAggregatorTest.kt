package com.premierdarkcoffee.nexo.connect.lab.application.presence

import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectActorType
import com.premierdarkcoffee.nexo.connect.lab.domain.identity.ConnectPrincipal
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class PrivacyAwarePresenceAggregatorTest {
    @Test
    fun `maps only coarse Redis activity into topology-free visible frames`() = runBlocking {
        val expectedStates =
            mapOf(
                PresenceActivitySnapshot.ONLINE to CoarsePresenceState.ONLINE,
                PresenceActivitySnapshot.RECENTLY_ONLINE to CoarsePresenceState.RECENTLY_ONLINE,
                PresenceActivitySnapshot.OFFLINE to CoarsePresenceState.OFFLINE,
            )

        expectedStates.forEach { (activity, expected) ->
            val projected = aggregator(activity = activity).project(request())
            val visible = assertIs<PresenceProjectionResult.Visible>(projected)

            assertEquals(expected, visible.frame.state)
            assertEquals("target-client", visible.frame.subjectRef)
            assertTopologyFree(visible.frame)
        }
    }

    @Test
    fun `privacy override emits hidden without exposing activity or topology`() = runBlocking {
        val projected =
            aggregator(
                visibility = PresenceVisibilityMode.HIDE,
                activity = PresenceActivitySnapshot.UNAVAILABLE,
            ).project(request())
        val visible = assertIs<PresenceProjectionResult.Visible>(projected)

        assertEquals(CoarsePresenceState.HIDDEN, visible.frame.state)
        assertTopologyFree(visible.frame)
    }

    @Test
    fun `unavailable ephemeral state is unknown and never inferred as offline`() = runBlocking {
        val projected = aggregator(activity = PresenceActivitySnapshot.UNAVAILABLE).project(request())

        assertEquals(PresenceProjectionResult.SilentNoFrame, projected)
    }

    @Test
    fun `relationship block and mute denials share one result and one evaluation shape`() = runBlocking {
        val scenarios =
            listOf(
                Triple(PresenceRelationship.DENIED, false, false),
                Triple(PresenceRelationship.ACTIVE_CONVERSATION_PARTICIPANT, true, false),
                Triple(PresenceRelationship.SELF, false, true),
            )

        scenarios.forEach { (relationship, blocked, muted) ->
            val calls = mutableListOf<String>()
            val aggregator =
                PrivacyAwarePresenceAggregator(
                    relationshipPolicy = PresenceRelationshipPolicy {
                        calls += "relationship"
                        relationship
                    },
                    blockPolicy = PresenceBlockPolicy {
                        calls += "block"
                        blocked
                    },
                    mutePolicy = PresenceMutePolicy {
                        calls += "mute"
                        muted
                    },
                    visibilityPolicy = PresenceVisibilityPolicy {
                        calls += "visibility"
                        PresenceVisibilityMode.SHARE_COARSE
                    },
                    snapshotReader = EphemeralPresenceSnapshotReader {
                        calls += "snapshot"
                        PresenceActivitySnapshot.ONLINE
                    },
                )

            assertEquals(PresenceProjectionResult.SilentNoFrame, aggregator.project(request()))
            assertEquals(listOf("relationship", "block", "mute", "visibility", "snapshot"), calls)
        }
    }

    private fun aggregator(
        relationship: PresenceRelationship = PresenceRelationship.ACTIVE_CONVERSATION_PARTICIPANT,
        blocked: Boolean = false,
        muted: Boolean = false,
        visibility: PresenceVisibilityMode = PresenceVisibilityMode.SHARE_COARSE,
        activity: PresenceActivitySnapshot,
    ): PrivacyAwarePresenceAggregator = PrivacyAwarePresenceAggregator(
        relationshipPolicy = PresenceRelationshipPolicy { relationship },
        blockPolicy = PresenceBlockPolicy { blocked },
        mutePolicy = PresenceMutePolicy { muted },
        visibilityPolicy = PresenceVisibilityPolicy { visibility },
        snapshotReader = EphemeralPresenceSnapshotReader { activity },
    )

    private fun request(): PresenceProjectionRequest = PresenceProjectionRequest(
        viewer =
        ConnectPrincipal(
            subjectRef = "viewer-client",
            actorType = ConnectActorType.CLIENT,
            platformScopeRef = "platform-1",
        ),
        target =
        PresenceSubjectTarget(
            subjectRef = "target-client",
            actorType = ConnectActorType.CLIENT,
            platformScopeRef = "platform-1",
        ),
    )

    private fun assertTopologyFree(frame: PresenceProjectionFrame) {
        val encoded = Json.encodeToString(frame)
        assertEquals(
            setOf("schemaVersion", "frameType", "subjectRef", "state"),
            Json.parseToJsonElement(encoded).jsonObject.keys,
        )
        listOf(
            "deviceRef",
            "deviceCount",
            "sessionRef",
            "connectionRef",
            "instanceRef",
            "lastSeenAt",
            "leaseExpiresAt",
        ).forEach { forbidden -> assertFalse(forbidden in encoded) }
    }
}
