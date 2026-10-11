package com.devomind.gallerysearch

/**
 * The facts this app decides ownership from, lifted out of `com.android.billingclient.api.Purchase` and
 * stated as its own types. [BillingGateway] maps Play's records into these at the boundary, and every
 * verdict — "is this mine", "does Play still need an acknowledgment" — is made against them, so the rules
 * a paying user's access depends on can be run on a host JVM instead of only on a device with a Google
 * account and a test purchase behind it.
 */
data class PurchaseFact(
    val productId: String,
    val state: PurchaseState,
    val acknowledged: Boolean
)

/** Mirrors `Purchase.PurchaseState`, whose `Pending` is a payment the user has not finished. */
enum class PurchaseState { Unspecified, Purchased, Pending }

/**
 * How the query itself went, before its contents mean anything. [Failed] covers every code that is not a
 * clean answer — service disconnected, offline, timeout, this build's Play too old for the feature — and
 * is deliberately not folded into [NotOwned], because an outage is not a refund.
 */
enum class QueryReply { Ok, NotOwned, Failed }

/** What a round-trip proved about the one entitlement the app sells. */
enum class PurchaseVerdict { Owned, NotOwned, Unreachable }

object PurchaseRules {

    /**
     * Settled and matching the product; nothing else counts. A [PurchaseState.Pending] record is not
     * ownership: the payment is incomplete, and crediting it early would hand the gated feature away
     * before Play has the money and then take it back when the payment fails.
     */
    fun owns(facts: List<PurchaseFact>, productId: String): Boolean =
        facts.any { it.productId == productId && it.state == PurchaseState.Purchased }

    /**
     * Play auto-cancels a purchase that goes unacknowledged a few days after it lands, so acknowledging
     * is not bookkeeping but the act that keeps the purchase. A [PurchaseState.Pending] one must wait:
     * Play acknowledges it when the payment completes, and doing it early is an error.
     */
    fun needsAcknowledgment(fact: PurchaseFact): Boolean =
        fact.state == PurchaseState.Purchased && !fact.acknowledged

    /**
     * A reply plus the records it carried, reduced to one verdict. `Ok` is answered by the records —
     * Play lists what the account still owns, so an empty list really does mean not owned, including
     * after a refund. `NotOwned` is the one code that proves a negative on its own. Everything else is
     * [PurchaseVerdict.Unreachable], which [Entitlements] reads as "keep what we last knew".
     */
    fun verdictOf(reply: QueryReply, facts: List<PurchaseFact>, productId: String): PurchaseVerdict =
        when (reply) {
            QueryReply.Ok -> if (owns(facts, productId)) PurchaseVerdict.Owned else PurchaseVerdict.NotOwned
            QueryReply.NotOwned -> PurchaseVerdict.NotOwned
            QueryReply.Failed -> PurchaseVerdict.Unreachable
        }
}
