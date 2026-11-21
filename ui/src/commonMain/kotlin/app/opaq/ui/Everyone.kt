package app.opaq.ui

import app.opaq.core.model.AppState
import app.opaq.core.model.Circle
import app.opaq.core.model.MemberLocation

/**
 * The circle picker's "Everyone" choice (#147): the people from every circle on one map. It's a
 * view only; invites, sharing and settings stay with each circle. Real circle ids are hex, so this
 * can't clash with one.
 */
const val EVERYONE = "everyone"

/** Everyone from all circles as one view-only circle, each person once. */
internal fun everyoneCircle(state: AppState): Circle = Circle(
    id = EVERYONE,
    name = "Everyone",
    keyHex = "",
    epoch = 0,
    adminPubkey = "",
    members = state.circles.flatMap { it.members }.distinctBy { it.pubkey },
)

/** Locations to show for [circleId]: for [EVERYONE], each person's newest from any circle. */
internal fun locationsFor(state: AppState, circleId: String): Map<String, MemberLocation> =
    if (circleId != EVERYONE) state.locations[circleId].orEmpty()
    else buildMap {
        state.circles.forEach { c ->
            state.locations[c.id].orEmpty().forEach { (pubkey, l) ->
                val seen = get(pubkey)
                if (seen == null || l.time > seen.time) put(pubkey, l)
            }
        }
    }

/** The circle to open [pubkey]'s profile in: [circleId], or for [EVERYONE] the one with their newest location. */
internal fun profileCircleFor(state: AppState, circleId: String, pubkey: String): String =
    if (circleId != EVERYONE) circleId
    else state.circles
        .filter { c -> c.members.any { it.pubkey == pubkey } }
        .maxByOrNull { state.locations[it.id]?.get(pubkey)?.time ?: -1L }?.id ?: circleId
