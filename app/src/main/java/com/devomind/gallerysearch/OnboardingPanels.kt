package com.devomind.gallerysearch

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.devomind.gallerysearch.databinding.ItemOnboardingBenefitRowBinding
import com.devomind.gallerysearch.databinding.ItemOnboardingPanelBinding
import com.devomind.gallerysearch.databinding.ItemOnboardingPermRowBinding
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * One page of the first-run tour: a glyph over eyebrow / headline / body copy, or — for the pages that
 * carry a list instead of a glyph alone — that same header with [benefits] or [permissions] ruled
 * beneath it.
 *
 * Every size here is stated in the reference mock's own units (it is 360dp wide), never in dp, and
 * [OnboardingMetrics] turns them into real pixels for the screen in hand.
 */
class OnboardingPanel(
    @DrawableRes val iconRes: Int,
    val iconSize: Float,
    @StringRes val eyebrow: Int,
    @StringRes val title: Int,
    @StringRes val body: Int,
    val permissions: List<OnboardingPermission> = emptyList(),
    val benefits: List<OnboardingBenefit> = emptyList()
)

/** One line of the Pro page's list: the thing the purchase adds, said once. */
class OnboardingBenefit(@StringRes val label: Int)

/** Which runtime grant a row stands for, and therefore how the tour asks for it. */
enum class OnboardingPermissionKind { Media, AllFiles, Notifications }

/** What can still be done about a permission, as of the last time the page was drawn. */
enum class OnboardingPermissionState {
    /** Already held: the row reads its status and is no longer a button. */
    GRANTED,

    /** Not held, and asking would do something: the row is a button. */
    OUTSTANDING,

    /** Not held, and asking here cannot help — this Android has no such request, or the system has
     *  stopped offering the dialog. Never holds the tour back. */
    UNAVAILABLE
}

/** A permission the app asks for, with the reason shown beside it. [required] rows are the ones the
 *  tour will not let the user finish past; the rest are offered where they are wanted later on. */
class OnboardingPermission(
    @DrawableRes val iconRes: Int,
    @StringRes val label: Int,
    @StringRes val summary: Int,
    val kind: OnboardingPermissionKind,
    val required: Boolean
)

/** One row's live readout: its state plus the short word drawn at the row's end. */
data class OnboardingPermissionStatus(
    val state: OnboardingPermissionState,
    @StringRes val labelRes: Int
)

/**
 * The tour answers for its own permission rows: it is asked what each one's state is right now, and
 * it is told to go ask the system. Implemented by [FirstRunActivity], which owns the launchers.
 */
interface OnboardingPermissionHost {
    fun statusOf(kind: OnboardingPermissionKind): OnboardingPermissionStatus
    fun request(kind: OnboardingPermissionKind)
}

/**
 * The tour's contents: what Pixa does, what costs extra, then what it needs. The permissions page stays
 * last because the tour's own chrome treats the final page specially — "Get started" and the blocked
 * finish both hang on it, and the dismiss button jumps to it. The Pro page therefore sits before it,
 * and lists the limits [FreeTier] actually enforces rather than a general idea of premium.
 *
 * The permissions page lists what this build actually requests — media access gates every feature,
 * all-files access is what lets Safe and the recycle bin move or delete originals, and notifications
 * are the optional one. Fingerprint is not listed: it has no runtime request and is enrolled inside Safe.
 */
fun onboardingPanels(): List<OnboardingPanel> = listOf(
    OnboardingPanel(
        iconRes = R.drawable.ic_onboarding_mark,
        iconSize = 100f,
        eyebrow = R.string.onboarding_welcome_eyebrow,
        title = R.string.onboarding_welcome_title,
        body = R.string.onboarding_welcome_body
    ),
    OnboardingPanel(
        iconRes = R.drawable.ic_onboarding_search,
        iconSize = 72f,
        eyebrow = R.string.onboarding_search_eyebrow,
        title = R.string.onboarding_search_title,
        body = R.string.onboarding_search_body
    ),
    OnboardingPanel(
        iconRes = R.drawable.ic_onboarding_people,
        iconSize = 72f,
        eyebrow = R.string.onboarding_people_eyebrow,
        title = R.string.onboarding_people_title,
        body = R.string.onboarding_people_body
    ),
    OnboardingPanel(
        iconRes = R.drawable.ic_onboarding_smart_album,
        iconSize = 72f,
        eyebrow = R.string.onboarding_smart_album_eyebrow,
        title = R.string.onboarding_smart_album_title,
        body = R.string.onboarding_smart_album_body
    ),
    OnboardingPanel(
        iconRes = R.drawable.ic_onboarding_cleanup,
        iconSize = 72f,
        eyebrow = R.string.onboarding_cleanup_eyebrow,
        title = R.string.onboarding_cleanup_title,
        body = R.string.onboarding_cleanup_body
    ),
    OnboardingPanel(
        iconRes = R.drawable.ic_onboarding_edit,
        iconSize = 72f,
        eyebrow = R.string.onboarding_edit_eyebrow,
        title = R.string.onboarding_edit_title,
        body = R.string.onboarding_edit_body
    ),
    OnboardingPanel(
        iconRes = R.drawable.ic_onboarding_safe,
        iconSize = 72f,
        eyebrow = R.string.onboarding_safe_eyebrow,
        title = R.string.onboarding_safe_title,
        body = R.string.onboarding_safe_body
    ),
    OnboardingPanel(
        iconRes = R.drawable.ic_onboarding_pro,
        iconSize = 84f,
        eyebrow = R.string.onboarding_pro_eyebrow,
        title = R.string.onboarding_pro_title,
        body = R.string.onboarding_pro_body,
        benefits = listOf(
            OnboardingBenefit(R.string.onboarding_pro_benefit_people),
            OnboardingBenefit(R.string.onboarding_pro_benefit_accents),
            OnboardingBenefit(R.string.onboarding_pro_benefit_safe),
            OnboardingBenefit(R.string.onboarding_pro_benefit_cleanup),
            OnboardingBenefit(R.string.onboarding_pro_benefit_compression),
            OnboardingBenefit(R.string.onboarding_pro_benefit_albums)
        )
    ),
    OnboardingPanel(
        iconRes = 0,
        iconSize = 0f,
        eyebrow = R.string.onboarding_perm_eyebrow,
        title = R.string.onboarding_perm_title,
        body = R.string.onboarding_perm_body,
        permissions = listOf(
            OnboardingPermission(
                R.drawable.ic_onboarding_perm_media,
                R.string.onboarding_perm_media,
                R.string.onboarding_perm_media_summary,
                OnboardingPermissionKind.Media,
                required = true
            ),
            OnboardingPermission(
                R.drawable.ic_onboarding_perm_files,
                R.string.onboarding_perm_files,
                R.string.onboarding_perm_files_summary,
                OnboardingPermissionKind.AllFiles,
                required = false
            ),
            OnboardingPermission(
                R.drawable.ic_onboarding_perm_notifications,
                R.string.onboarding_perm_notifications,
                R.string.onboarding_perm_notifications_summary,
                OnboardingPermissionKind.Notifications,
                required = false
            )
        )
    )
)

/**
 * Turns the reference mock's units into this screen's pixels. [factor] is the ratio the tour is
 * drawn at (see FirstRunActivity), so a wider phone gets a proportionally wider tour and a short
 * or landscape screen gets a proportionally smaller one instead of a clipped layout. It is redrawn
 * when the window changes shape, which is why it is a var and not a constructor-time constant.
 */
class OnboardingMetrics(var factor: Float, val density: Float) {

    /** A mock-space length (its dp value at 360dp wide) as real pixels. */
    fun units(mockDp: Float): Int = (mockDp * factor * density).roundToInt()

    /** A hairline: as thin as the screen can draw, but never invisible. */
    fun hairline(): Int = max(1, units(1f))

    /** A mock-space font size, as scaled sp. */
    fun textSp(mockPx: Float): Float = mockPx * factor
}

/** The pages themselves, swiped through by ViewPager2. */
class OnboardingPanelAdapter(
    private val panels: List<OnboardingPanel>,
    private val metrics: OnboardingMetrics,
    private val host: OnboardingPermissionHost
) : RecyclerView.Adapter<OnboardingPanelAdapter.PanelVH>() {

    /** Re-reads every row's state. Called when a dialog or the settings screen is dismissed. */
    fun refreshPermissions() {
        permissionsPage?.let { notifyItemChanged(it) }
    }

    /**
     * Redraws the pages on screen after the tour's scale changed. A page's units are baked in at bind
     * time, so the ones being looked at need re-binding; the rest bind against the new scale the next
     * time they attach. A whole-adapter notify is deliberately not used — on ViewPager2 it can leave
     * the pager displaying a stale page, and only these few views carry stale pixels.
     */
    fun rescaleVisiblePages() {
        val pager = pager ?: return
        panels.indices.forEach { position ->
            (pager.findViewHolderForAdapterPosition(position) as? PanelVH)?.rebind()
        }
    }

    /** ViewPager2 hands out pages from its inner RecyclerView, which is the only way to reach it. */
    private var pager: RecyclerView? = null

    private val permissionsPage: Int?
        get() = panels.indexOfFirst { it.permissions.isNotEmpty() }.takeIf { it >= 0 }

    override fun getItemCount(): Int = panels.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PanelVH {
        pager = parent as? RecyclerView
        return PanelVH(ItemOnboardingPanelBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: PanelVH, position: Int) = holder.bind(panels[position])

    inner class PanelVH(private val b: ItemOnboardingPanelBinding) : RecyclerView.ViewHolder(b.root) {

        /** Binds this page again with the metrics the adapter now carries. */
        fun rebind() {
            val position = bindingAdapterPosition
            if (position in panels.indices) bind(panels[position])
        }

        fun bind(panel: OnboardingPanel) {
            val context = b.root.context
            b.panelRoot.setPadding(metrics.units(28f), 0, metrics.units(28f), metrics.units(4f))

            b.panelEyebrow.setTextSize(metrics.textSp(12f))
            b.panelEyebrow.margin { bottomMargin = metrics.units(10f) }
            b.panelTitle.setTextSize(metrics.textSp(28f))
            b.panelBody.setTextSize(metrics.textSp(13.5f))
            b.panelBody.margin { topMargin = metrics.units(12f) }
            b.panelBody.maxWidth = metrics.units(280f)

            b.panelEyebrow.text = context.getString(panel.eyebrow)
            b.panelTitle.text = context.getString(panel.title)
            b.panelBody.text = context.getString(panel.body)

            if (panel.permissions.isNotEmpty()) bindPermissionsPage(panel)
            else if (panel.benefits.isNotEmpty()) bindBenefitsPage(panel)
            else bindFeaturePage(panel)
        }

        /** Glyph in the larger share of the spare height, copy settled above a gap below it. */
        private fun bindFeaturePage(panel: OnboardingPanel) {
            b.panelIconArea.visibility = View.VISIBLE
            b.panelIcon.visibility = View.VISIBLE
            b.panelIcon.setImageResource(panel.iconRes)
            // Every glyph property is set here rather than "reset" here, because ViewPager2 hands a
            // recycled holder to whichever page needs one next: the Pro page paints its glyph on an
            // accent tile in white, and a search icon must not inherit that.
            b.panelIcon.background = null
            b.panelIcon.setPadding(0, 0, 0, 0)
            b.panelIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                DesignTokens.accent(b.root.context)
            )
            b.panelIcon.layoutParams = (b.panelIcon.layoutParams as FrameLayout.LayoutParams).apply {
                width = metrics.units(panel.iconSize)
                height = metrics.units(panel.iconSize)
            }
            b.panelIconArea.layoutParams =
                (b.panelIconArea.layoutParams as LinearLayout.LayoutParams).apply {
                    height = 0
                    weight = 1f
                }
            b.panelBottomSpacer.layoutParams =
                (b.panelBottomSpacer.layoutParams as LinearLayout.LayoutParams).apply {
                    height = 0
                    weight = 0.8f
                }
            b.permTopDivider.visibility = View.GONE
            b.permList.visibility = View.GONE
            b.permList.removeAllViews()
        }

        /**
         * The Pro page keeps its glyph but stops treating the page as a poster: the tile is given its
         * own fixed height and the ruled list takes the slack below the copy, the way the permissions
         * page does. Weighting both would have the glyph and the list compete for the same leftover,
         * and on a short screen the list is the part that must not be clipped.
         *
         * The glyph is drawn *on* the accent rather than in it — a flat tile is this app's one
         * promotional gesture, and white on accent is the pairing the CTA already uses.
         */
        private fun bindBenefitsPage(panel: OnboardingPanel) {
            val tile = metrics.units(panel.iconSize)
            val glyphPadding = metrics.units(panel.iconSize * 0.26f)
            b.panelIcon.visibility = View.VISIBLE
            b.panelIcon.setImageResource(panel.iconRes)
            b.panelIcon.setBackgroundResource(R.drawable.onboarding_pro_tile_bg)
            b.panelIcon.setPadding(glyphPadding, glyphPadding, glyphPadding, glyphPadding)
            b.panelIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(b.root.context, R.color.metroTextPrimary)
            )
            b.panelIcon.layoutParams = (b.panelIcon.layoutParams as FrameLayout.LayoutParams).apply {
                width = tile
                height = tile
            }
            b.panelIconArea.layoutParams =
                (b.panelIconArea.layoutParams as LinearLayout.LayoutParams).apply {
                    height = tile + metrics.units(8f)
                    weight = 0f
                }
            b.panelBottomSpacer.layoutParams =
                (b.panelBottomSpacer.layoutParams as LinearLayout.LayoutParams).apply {
                    height = 0
                    weight = 0f
                }
            b.permTopDivider.visibility = View.VISIBLE
            b.permTopDivider.layoutParams =
                (b.permTopDivider.layoutParams as LinearLayout.LayoutParams).apply {
                    topMargin = metrics.units(10f)
                    height = metrics.hairline()
                }
            b.permList.visibility = View.VISIBLE
            b.permList.removeAllViews()
            panel.benefits.forEach { b.permList.addView(benefitRowView(it)) }
        }

        /** One included thing: an accent check, the label, and the hairline that rules the list. */
        private fun benefitRowView(benefit: OnboardingBenefit): View {
            val context = b.root.context
            val r = ItemOnboardingBenefitRowBinding.inflate(LayoutInflater.from(context), b.permList, false)
            r.benefitRowIcon.layoutParams = (r.benefitRowIcon.layoutParams as LinearLayout.LayoutParams).apply {
                width = metrics.units(18f)
                height = metrics.units(18f)
            }
            r.benefitRowLabel.setTextSize(metrics.textSp(13f))
            r.benefitRowLabel.text = context.getString(benefit.label)
            r.benefitRowDivider.layoutParams = (r.benefitRowDivider.layoutParams as LinearLayout.LayoutParams).apply {
                height = metrics.hairline()
            }
            val rowPad = metrics.units(11f)
            r.benefitRowContent.setPadding(0, rowPad, 0, rowPad)
            r.benefitRowLabel.margin { marginStart = metrics.units(16f) }
            return r.root
        }

        /** The flexible area collapses to the reference's header gap and the ruled list takes it. */
        private fun bindPermissionsPage(panel: OnboardingPanel) {
            b.panelIcon.visibility = View.GONE
            b.panelIconArea.layoutParams =
                (b.panelIconArea.layoutParams as LinearLayout.LayoutParams).apply {
                    height = metrics.units(38f)
                    weight = 0f
                }
            b.panelBottomSpacer.layoutParams =
                (b.panelBottomSpacer.layoutParams as LinearLayout.LayoutParams).apply {
                    height = 0
                    weight = 0f
                }
            b.permTopDivider.visibility = View.VISIBLE
            b.permTopDivider.layoutParams =
                (b.permTopDivider.layoutParams as LinearLayout.LayoutParams).apply {
                    topMargin = metrics.units(18f)
                    height = metrics.hairline()
                }
            b.permList.visibility = View.VISIBLE
            b.permList.removeAllViews()
            panel.permissions.forEach { b.permList.addView(rowView(it)) }
        }

        private fun rowView(row: OnboardingPermission): View {
            val context = b.root.context
            val r = ItemOnboardingPermRowBinding.inflate(LayoutInflater.from(context), b.permList, false)
            r.permRowIcon.setImageResource(row.iconRes)
            r.permRowIcon.layoutParams = (r.permRowIcon.layoutParams as LinearLayout.LayoutParams).apply {
                width = metrics.units(20f)
                height = metrics.units(20f)
            }
            r.permRowTitle.setTextSize(metrics.textSp(13.5f))
            r.permRowSummary.setTextSize(metrics.textSp(11.5f))
            r.permRowStatus.setTextSize(metrics.textSp(11f))
            r.permRowTitle.text = context.getString(row.label)
            r.permRowSummary.text = context.getString(row.summary)
            r.permRowDivider.layoutParams = (r.permRowDivider.layoutParams as LinearLayout.LayoutParams).apply {
                height = metrics.hairline()
            }
            r.permRowContent.setPadding(0, metrics.units(13f), 0, metrics.units(13f))
            r.permRowCopy.margin { marginStart = metrics.units(16f) }
            r.permRowSummary.margin { topMargin = metrics.units(2f) }
            r.permRowStatus.margin { marginStart = metrics.units(12f) }

            // A row the user can still act on is a button in the app's one tactile language; a row
            // that only reports is flat text.
            val status = host.statusOf(row.kind)
            val actionable = status.state == OnboardingPermissionState.OUTSTANDING
            r.permRowStatus.text = context.getString(status.labelRes)
            r.permRowStatus.setTextColor(
                if (status.state == OnboardingPermissionState.GRANTED) DesignTokens.accent(context)
                else ContextCompat.getColor(context, R.color.metroTextTertiary)
            )
            r.permRowRoot.isClickable = actionable
            r.permRowRoot.background =
                if (actionable) ContextCompat.getDrawable(context, R.drawable.metro_row_pressed) else null
            r.permRowRoot.setOnClickListener { if (actionable) host.request(row.kind) }
            return r.root
        }
    }

    /** Rescales one view's own margins in the layout params it already has. */
    private inline fun View.margin(block: ViewGroup.MarginLayoutParams.() -> Unit) {
        (layoutParams as ViewGroup.MarginLayoutParams).apply(block)
        requestLayout()
    }
}
