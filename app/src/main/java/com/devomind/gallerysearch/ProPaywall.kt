package com.devomind.gallerysearch

import android.app.Activity

/**
 * The one place Pixa Pro is offered. Gated features reach it through [Entitlements.guard] and the settings
 * row through [show], so no feature code ever names a product id, a price, or a Play response code.
 *
 * [reason] is what the user reached for — "the Safe holds 50 photos free" reads as an answer to their
 * action, where a generic offer would read as an advertisement. Keeping it here means a feature states one
 * sentence and nothing else about billing.
 *
 * Re-showing after a failed launch is deliberate: [MetroDialog] dismisses on any button press, so
 * "Google Play is unreachable" has nowhere to appear unless the dialog is rebuilt with the reason in it.
 */
object ProPaywall {

    fun show(activity: Activity, reason: String? = null) {
        if (Entitlements.isUnlocked(activity)) {
            MetroDialog.message(
                activity,
                activity.getString(R.string.pro_title),
                activity.getString(R.string.pro_owned_body),
                iconRes = R.drawable.ic_fluent_lock_closed_24_regular,
                positive = activity.getString(R.string.close)
            )
            return
        }
        val price = BillingGateway.priceLabel()
        val status = BillingGateway.statusMessage()?.takeIf { it.isNotBlank() }
        MetroDialog.confirm(
            activity,
            title = activity.getString(R.string.pro_title),
            message = buildString {
                append(activity.getString(R.string.pro_body))
                append("\n\n")
                append(
                    if (price == null) activity.getString(R.string.pro_price_unknown)
                    else activity.getString(R.string.pro_price_line, price)
                )
                if (!reason.isNullOrBlank()) append("\n\n").append(reason)
                if (status != null) append("\n\n").append(status)
            },
            positive = if (price == null) activity.getString(R.string.pro_buy)
            else activity.getString(R.string.pro_buy_price, price),
            negative = activity.getString(R.string.pro_restore),
            iconRes = R.drawable.ic_fluent_lock_closed_24_regular,
            // Restore is a re-query, not a separate flow: a one-time purchase belongs to the Play account,
            // so the same verifyOwnership that refreshes the settings row is what finds it on this device.
            onNegative = { BillingGateway.refresh() },
            onPositive = {
                if (!BillingGateway.purchase(activity)) show(activity, reason)
            }
        )
    }
}
