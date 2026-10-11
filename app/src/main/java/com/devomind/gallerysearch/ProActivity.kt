package com.devomind.gallerysearch

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.devomind.gallerysearch.databinding.ActivityProBinding

/**
 * Pixa Pro, offered as a page rather than a dialog.
 *
 * A purchase is the one decision in this app that costs money, and a popup the user dismisses to get back
 * to their photos is a poor place to make it. This screen states the terms, the limits Pro lifts with the
 * free number beside each one, and what stays free after all — then puts a single price and a single
 * button at the bottom.
 *
 * The numbers are read from [FreeTier] on the way in instead of being typed into the layout, because a
 * page that prints "50" is a page that will still print "50" after the Safe's cap moves.
 */
class ProActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProBinding

    /** Held as a property so the same instance is registered and removed — see [SettingsActivity]. */
    private val billingListener: () -> Unit = { renderOffer() }

    override fun onCreate(savedInstanceState: Bundle?) {
        AccentPalette.apply(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK
        binding = ActivityProBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()
        applyResponsiveChrome()

        binding.backBtn.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.buyBtn.setOnClickListener {
            // Play's sheet opens on the user's word for it. When it cannot be opened the reason has to
            // appear on this page, because the button pressing it is the only feedback they get.
            if (!BillingGateway.purchase(this)) BillingGateway.refresh()
        }
        binding.restoreBtn.setOnClickListener {
            // Restore is a re-query, not a separate flow: a one-time purchase belongs to the Play account
            // that paid for it, so verifying ownership is what finds it on a new device.
            BillingGateway.refresh()
            renderOffer()
        }

        renderLimits()
        renderReason()
        renderOffer()
    }

    override fun onResume() {
        super.onResume()
        BillingGateway.observe(billingListener)
        renderOffer()
        // Play's sheet backgrounds the app, so returning here is also how a finished purchase is noticed.
        BillingGateway.refresh()
    }

    override fun onPause() {
        BillingGateway.unobserve(billingListener)
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyResponsiveChrome()
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.proRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBar.updatePadding(top = bars.top + dp(8))
            binding.proFooter.updatePadding(bottom = bars.bottom + dp(16))
            insets
        }
    }

    /**
     * The mosaic is drawn for this app's reference width, so it is given exactly that much room and no
     * more: on a wide window the gutters take 40dp and the rest of the width would only make the tiles
     * huge. A short window cannot afford 200dp of picture either, so the same clamp tightens there.
     */
    private fun applyResponsiveChrome() {
        Responsive.applyTitleText(binding.proTitle, this, HERO_TITLE_SP)
        val capDp = if (Responsive.cramped(this)) HERO_CRAMPED_WIDTH_DP else HERO_MAX_WIDTH_DP
        val params = binding.heroIllustration.layoutParams as LinearLayout.LayoutParams
        params.width = minOf(resources.displayMetrics.widthPixels - dp(40), dp(capDp))
        params.gravity = Gravity.CENTER_HORIZONTAL
        binding.heroIllustration.layoutParams = params
    }

    /** The free half of the offer, said in the same numbers the features enforce. */
    private fun renderLimits() {
        binding.proDetailAccents.text = getString(R.string.pro_add_accents_detail, FreeTier.FreeAccents)
        binding.proDetailSafe.text = getString(R.string.pro_add_safe_detail, FreeTier.FreeSafePhotos)
        binding.proDetailCompression.text =
            getString(R.string.pro_add_compression_detail, FreeTier.FreeCompressionPhotos)
        binding.proDetailAlbums.text = getString(R.string.pro_add_albums_detail, FreeTier.FreeSmartAlbums)
        binding.proFreeCompression.text =
            getString(R.string.pro_free_compression, FreeTier.FreeCompressionPhotos)
    }

    /** What the user reached for, when a gate sent them here rather than the drawer or the settings row. */
    private fun renderReason() {
        val reason = intent.getStringExtra(ExtraReason)
        if (reason.isNullOrBlank()) return
        binding.reasonText.text = reason
        binding.reasonText.visibility = View.VISIBLE
    }

    private fun renderOffer() {
        val owned = Entitlements.isUnlocked(this)
        binding.purchaseBar.visibility = if (owned) View.GONE else View.VISIBLE
        binding.ownedBar.visibility = if (owned) View.VISIBLE else View.GONE
        if (owned) {
            binding.statusCaption.visibility = View.GONE
            return
        }

        // One verdict, read once: the caption line and the button label are the same answer in two fonts,
        // so they cannot disagree about whether Play has named a price yet.
        val caption = ProOffer.priceCaption(BillingGateway.priceLabel(), Entitlements.state(this))
        binding.priceCaption.text = when (caption) {
            is ProOffer.PriceCaption.Known -> getString(R.string.pro_price_line, caption.price)
            ProOffer.PriceCaption.Unavailable -> getString(R.string.pro_price_unknown)
            ProOffer.PriceCaption.Checking -> getString(R.string.pro_checking)
        }
        binding.buyBtn.text = when (caption) {
            is ProOffer.PriceCaption.Known -> getString(R.string.pro_buy_price, caption.price)
            else -> getString(R.string.pro_buy)
        }

        val status = BillingGateway.statusMessage()?.takeIf { it.isNotBlank() }
        binding.statusCaption.text = status.orEmpty()
        binding.statusCaption.visibility = if (status == null) View.GONE else View.VISIBLE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        /** The sentence a gate showed, repeated here above the price. */
        const val ExtraReason = "pro_reason"

        private const val HERO_TITLE_SP = 40f
        private const val HERO_MAX_WIDTH_DP = 420
        private const val HERO_CRAMPED_WIDTH_DP = 220

        fun intent(context: Context, reason: String? = null): Intent =
            Intent(context, ProActivity::class.java).apply {
                if (!reason.isNullOrBlank()) putExtra(ExtraReason, reason)
            }
    }
}
