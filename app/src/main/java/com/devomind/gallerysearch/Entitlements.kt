package com.devomind.gallerysearch

import android.app.Activity
import android.content.Context

/**
 * The verdict a gated feature reads: whether this device's user owns the paid unlock. Three states,
 * because "Play says no" and "Play did not answer" are different facts and only the first one is allowed
 * to lock something away.
 *
 * [Unknown] is a fresh install. It gates as locked — nothing was bought — and it is also what a device
 * keeps if Play was never reachable, which is why the paywall offers a restore rather than assuming.
 */
enum class Entitlement { Unknown, Owned, NotOwned }

/**
 * Single place the app asks "may I". The billing round-trips live in [BillingGateway]; this object only
 * holds what they proved, persists it, and hands features the answer — so an entitlement bought on this
 * device keeps working with no network, no Google app, and no Play service running.
 */
object Entitlements {

    fun state(context: Context): Entitlement = IndexPreferences.getProEntitlement(context)

    fun isUnlocked(context: Context): Boolean = state(context) == Entitlement.Owned

    /**
     * Folds one round-trip's verdict into what is known and persists the result. A successful query
     * overwrites — that is how a refund on another device eventually locks the feature again — and a
     * failed one leaves the record untouched, because a service outage must not read as "you never
     * bought this".
     */
    fun record(context: Context, verdict: PurchaseVerdict): Entitlement =
        next(state(context), verdict).also { changed ->
            IndexPreferences.setProEntitlement(context, changed)
        }

    /** The rule [record] persists, split out so a host test can drive it directly. */
    internal fun next(current: Entitlement, verdict: PurchaseVerdict): Entitlement = when (verdict) {
        PurchaseVerdict.Owned -> Entitlement.Owned
        PurchaseVerdict.NotOwned -> Entitlement.NotOwned
        PurchaseVerdict.Unreachable -> current
    }

    /**
     * The seam every gated feature calls: run [onUnlocked] when the user owns the unlock, otherwise show
     * [ProPaywall] and report false. Features stay ignorant of billing — they hand over a body and a
     * one-line [reason] for what they were reaching for, and never see a product id, a price, or a dialog.
     */
    fun guard(activity: Activity, reason: String? = null, onUnlocked: () -> Unit): Boolean {
        if (isUnlocked(activity)) {
            onUnlocked()
            return true
        }
        ProPaywall.show(activity, reason)
        return false
    }
}
