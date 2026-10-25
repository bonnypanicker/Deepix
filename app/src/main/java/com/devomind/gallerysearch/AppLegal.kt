package com.devomind.gallerysearch

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.view.View

object AppLegal {
    const val PRIVACY_POLICY_URL = "https://pixa-c0aa3.web.app/privacy.html"
    const val TERMS_CONDITIONS_URL = "https://pixa-c0aa3.web.app/terms.html"

    fun openUrl(context: Context, url: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(intent)
        }.onFailure {
            if (context is Activity) {
                MetroBanner.show(context, context.getString(R.string.cannot_open_link))
            }
        }
    }

    fun buildConsentSpannable(
        context: Context,
        accentColor: Int,
        onLinkClick: (String) -> Unit = { url -> openUrl(context, url) }
    ): SpannableString {
        val termsText = context.getString(R.string.terms_and_conditions)
        val privacyText = context.getString(R.string.privacy_policy)
        val fullText = context.getString(R.string.onboarding_legal_notice, termsText, privacyText)
        val spannable = SpannableString(fullText)

        val termsStart = fullText.indexOf(termsText)
        if (termsStart >= 0) {
            val termsSpan = object : ClickableSpan() {
                override fun onClick(widget: View) = onLinkClick(TERMS_CONDITIONS_URL)
                override fun updateDrawState(ds: TextPaint) {
                    super.updateDrawState(ds)
                    ds.color = accentColor
                    ds.isUnderlineText = true
                }
            }
            spannable.setSpan(
                termsSpan,
                termsStart,
                termsStart + termsText.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        val privacyStart = fullText.indexOf(privacyText)
        if (privacyStart >= 0) {
            val privacySpan = object : ClickableSpan() {
                override fun onClick(widget: View) = onLinkClick(PRIVACY_POLICY_URL)
                override fun updateDrawState(ds: TextPaint) {
                    super.updateDrawState(ds)
                    ds.color = accentColor
                    ds.isUnderlineText = true
                }
            }
            spannable.setSpan(
                privacySpan,
                privacyStart,
                privacyStart + privacyText.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        return spannable
    }
}
