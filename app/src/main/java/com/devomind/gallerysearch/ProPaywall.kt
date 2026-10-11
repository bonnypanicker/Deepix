package com.devomind.gallerysearch

import android.app.Activity

/**
 * The one place Pixa Pro is offered. Gated features reach it through [Entitlements.guard], the drawer and
 * the settings row reach it directly, and no feature code anywhere names a product id, a price, or a Play
 * response code — they hand over a sentence about what the user reached for and open the page.
 *
 * [reason] is that sentence: "the Safe holds 50 photos free" reads as an answer to their action, where a
 * generic offer would read as an advertisement. It is shown again on the page, above the price.
 *
 * It used to be a dialog. A purchase is the one decision in this app that costs money, and a popup the
 * user dismisses to get back to their photos was too small a place to make it.
 */
object ProPaywall {

    fun open(activity: Activity, reason: String? = null) {
        activity.startActivity(ProActivity.intent(activity, reason))
    }
}
