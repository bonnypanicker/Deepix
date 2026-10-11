package com.devomind.gallerysearch

/**
 * The one decision the Pro page's footer makes that Android cannot answer, kept out of the Activity so a
 * test can drive it instead of a phone with its network switch off.
 *
 * It is an answer under uncertainty. The price is Play's, fetched at the same moment the verdict is: before
 * that answer comes back the page must not print "Price unavailable", because the price is not unavailable,
 * it is simply not here yet. Ownership is not this object's question — [Entitlements.isUnlocked] already
 * answers it, and `Unknown` is not ownership, which is why an install that has never reached Play is still
 * shown the purchase.
 */
object ProOffer {

    sealed class PriceCaption {
        /** Play named a number, in the user's currency. */
        data class Known(val price: String) : PriceCaption()

        /** Play answered the query and this product has no offer attached to it. */
        object Unavailable : PriceCaption()

        /** Nothing has come back from Play yet, so no claim about the price can be made. */
        object Checking : PriceCaption()
    }

    fun priceCaption(price: String?, state: Entitlement): PriceCaption = when {
        price != null -> PriceCaption.Known(price)
        state == Entitlement.Unknown -> PriceCaption.Checking
        else -> PriceCaption.Unavailable
    }
}
