package com.devomind.gallerysearch

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Pro page's price line, driven without a phone and without Play.
 *
 * The interesting case is the one where the app knows nothing: a fresh install, no network, Play silent.
 * There is an honest sentence for that moment and a wrong one that is easier to reach — "Price unavailable"
 * speaks about the product, and what is actually true is that nobody has answered yet. Ownership is not
 * tested here because [ProOffer] does not decide it: [Entitlements.isUnlocked] is the one predicate, and
 * PurchaseRulesTest already pins how the verdict gets into that store in the first place.
 */
class ProOfferTest {

    @Test
    fun aPriceIsPrintedTheMomentPlayNamesOne() {
        assertEquals(
            ProOffer.PriceCaption.Known("$4.99"),
            ProOffer.priceCaption("$4.99", Entitlement.Unknown)
        )
        assertEquals(
            ProOffer.PriceCaption.Known("$4.99"),
            ProOffer.priceCaption("$4.99", Entitlement.NotOwned)
        )
    }

    @Test
    fun silenceAboutThePriceIsNotClaimedAsUnavailability() {
        // Play has not answered yet: "Price unavailable" would be a statement about the product rather
        // than about this moment, and the user would read it as the product costing nothing.
        assertEquals(
            ProOffer.PriceCaption.Checking,
            ProOffer.priceCaption(null, Entitlement.Unknown)
        )
        // Play answered, and this product carries no offer — that is a real absence, and gets said.
        assertEquals(
            ProOffer.PriceCaption.Unavailable,
            ProOffer.priceCaption(null, Entitlement.NotOwned)
        )
    }
}
