package app.opaq.core.nostr

/**
 * How long to leave a relay alone after it turns something down, going by the machine-readable
 * prefix NIP-01 puts at the start of CLOSED and OK messages ("auth-required: ...", "rate-limited: ...").
 *
 * Asking again straight away gets the same answer, costs the phone a network wake-up each time,
 * and to the relay looks like the abuse its limits are there to stop: relays ban addresses that
 * keep breaking their rate limits, and then everyone on that network (a whole household on one
 * Wi-Fi) loses the relay.
 */
internal object RelayReplies {
    /** Refusals that won't change by asking again soon: the relay wants sign-in (which Opaq doesn't do) or blocks us. */
    const val LONG_HOLD_MILLIS = 30 * 60_000L

    /** Anything else (rate limits, errors): try again in a few minutes. */
    const val SHORT_HOLD_MILLIS = 5 * 60_000L

    /** How long not to ask this relay for a subscription it just closed. */
    fun subscriptionHoldMillis(message: String): Long =
        if (prefix(message) in LASTING || isBan(message)) LONG_HOLD_MILLIS else SHORT_HOLD_MILLIS

    /**
     * How long not to send this relay any events after it rejected one, or null to carry on.
     * Only for refusals about us rather than the event: a duplicate or an invalid event says
     * nothing about the next one.
     */
    fun publishHoldMillis(message: String): Long? = when {
        isBan(message) || prefix(message) in LASTING -> LONG_HOLD_MILLIS
        prefix(message) == "rate-limited" -> SHORT_HOLD_MILLIS
        else -> null
    }

    private val LASTING = setOf("auth-required", "restricted", "blocked")

    private fun prefix(message: String): String = message.substringBefore(':').trim().lowercase()

    /**
     * Some relays say "banned: ..." or put it after "error:", outside the standard prefixes, and some
     * only accept people they already know ("Policy violated and pubkey is not in our web of trust"),
     * which Opaq's fresh hourly keys never are, so asking again soon is pointless.
     */
    private fun isBan(message: String): Boolean =
        listOf("banned", "web of trust", "policy violated", "not allowed", "whitelist", "allowlist", "paid relay", "pay to").any { message.contains(it, ignoreCase = true) }
}
