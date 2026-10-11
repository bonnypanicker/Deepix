package com.devomind.gallerysearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules a paying user's access rests on, driven without a device, a Google account or a test
 * purchase. [PurchaseRules] exists precisely so these three questions — what counts as owned, what still
 * needs acknowledging, and what one round-trip proves — can be answered here rather than by reasoning
 * about Play's classes on a phone.
 */
class PurchaseRulesTest {

    private val other = PurchaseFact("pixa_other_product", PurchaseState.Purchased, acknowledged = true)

    @Test
    fun onlyASettledPurchaseOfThisProductIsOwnership() {
        assertTrue(PurchaseRules.owns(ownedFacts(), BillingGateway.ProProductId))
        assertFalse(PurchaseRules.owns(emptyList(), BillingGateway.ProProductId))
        assertFalse(
            PurchaseRules.owns(
                listOf(PurchaseFact(BillingGateway.ProProductId, PurchaseState.Pending, false)),
                BillingGateway.ProProductId
            )
        )
        // A different product the account owns — an old experiment, another app on the same key — must
        // not unlock this one.
        assertFalse(PurchaseRules.owns(listOf(other), BillingGateway.ProProductId))
    }

    @Test
    fun acknowledgmentIsAskedForOnceThePaymentSettled() {
        assertTrue(PurchaseRules.needsAcknowledgment(purchased(acknowledged = false)))
        assertFalse(PurchaseRules.needsAcknowledgment(purchased(acknowledged = true)))
        // Play acknowledges a pending purchase when its payment completes; doing it early is an error.
        assertFalse(
            PurchaseRules.needsAcknowledgment(
                PurchaseFact(BillingGateway.ProProductId, PurchaseState.Pending, false)
            )
        )
    }

    @Test
    fun aCleanAnswerIsJudgedByTheRecordsItCarried() {
        assertEquals(
            PurchaseVerdict.Owned,
            PurchaseRules.verdictOf(QueryReply.Ok, ownedFacts(), BillingGateway.ProProductId)
        )
        // Empty after a refund is a real negative, not an outage.
        assertEquals(
            PurchaseVerdict.NotOwned,
            PurchaseRules.verdictOf(QueryReply.Ok, emptyList(), BillingGateway.ProProductId)
        )
        assertEquals(
            PurchaseVerdict.NotOwned,
            PurchaseRules.verdictOf(QueryReply.NotOwned, ownedFacts(), BillingGateway.ProProductId)
        )
        assertEquals(
            PurchaseVerdict.Unreachable,
            PurchaseRules.verdictOf(QueryReply.Failed, ownedFacts(), BillingGateway.ProProductId)
        )
    }

    @Test
    fun anOutageNeverStripsAKnownPurchase() {
        assertEquals(Entitlement.Owned, Entitlements.next(Entitlement.Owned, PurchaseVerdict.Unreachable))
        assertEquals(Entitlement.Unknown, Entitlements.next(Entitlement.Unknown, PurchaseVerdict.Unreachable))
        assertEquals(Entitlement.NotOwned, Entitlements.next(Entitlement.NotOwned, PurchaseVerdict.Unreachable))
    }

    @Test
    fun aProofOverwritesWhateverWasKnown() {
        // The refund case: bought, then reversed on another device, and this copy has to catch up.
        assertEquals(Entitlement.NotOwned, Entitlements.next(Entitlement.Owned, PurchaseVerdict.NotOwned))
        assertEquals(Entitlement.Owned, Entitlements.next(Entitlement.Unknown, PurchaseVerdict.Owned))
        assertEquals(Entitlement.Owned, Entitlements.next(Entitlement.NotOwned, PurchaseVerdict.Owned))
    }

    private fun purchased(acknowledged: Boolean) =
        PurchaseFact(BillingGateway.ProProductId, PurchaseState.Purchased, acknowledged)

    private fun ownedFacts() = listOf(purchased(acknowledged = false), other)
}
