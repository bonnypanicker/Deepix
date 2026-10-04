package com.devomind.gallerysearch

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.recyclerview.widget.RecyclerView
import com.devomind.gallerysearch.databinding.ItemOnboardingPanelBinding
import com.devomind.gallerysearch.databinding.ItemOnboardingPermRowBinding
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * One page of the first-run tour: a glyph over eyebrow / headline / body copy, or — for the last
 * page — that same header with [permissions] listed under it instead of a glyph.
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
    val permissions: List<OnboardingPermission> = emptyList()
)

/** A permission the app asks for at runtime, with the reason shown beside it. */
class OnboardingPermission(
    @DrawableRes val iconRes: Int,
    @StringRes val label: Int,
    @StringRes val summary: Int
)

/**
 * The tour's contents: what Pixa does, then what it needs. The permissions page lists what this
 * build actually requests — media access gates every feature, all-files access is what lets Safe
 * and the recycle bin move or delete originals, and notifications and biometrics are the two
 * optional ones.
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
        iconRes = 0,
        iconSize = 0f,
        eyebrow = R.string.onboarding_perm_eyebrow,
        title = R.string.onboarding_perm_title,
        body = R.string.onboarding_perm_body,
        permissions = listOf(
            OnboardingPermission(
                R.drawable.ic_onboarding_perm_media,
                R.string.onboarding_perm_media,
                R.string.onboarding_perm_media_summary
            ),
            OnboardingPermission(
                R.drawable.ic_onboarding_perm_files,
                R.string.onboarding_perm_files,
                R.string.onboarding_perm_files_summary
            ),
            OnboardingPermission(
                R.drawable.ic_onboarding_perm_notifications,
                R.string.onboarding_perm_notifications,
                R.string.onboarding_perm_notifications_summary
            ),
            OnboardingPermission(
                R.drawable.ic_onboarding_perm_biometric,
                R.string.onboarding_perm_biometric,
                R.string.onboarding_perm_biometric_summary
            )
        )
    )
)

/**
 * Turns the reference mock's units into this screen's pixels. [factor] is the ratio the tour is
 * drawn at (see FirstRunActivity), so a wider phone gets a proportionally wider tour and a short
 * or landscape screen gets a proportionally smaller one instead of a clipped layout.
 */
class OnboardingMetrics(val factor: Float, val density: Float) {

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
    private val metrics: OnboardingMetrics
) : RecyclerView.Adapter<OnboardingPanelAdapter.PanelVH>() {

    override fun getItemCount(): Int = panels.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PanelVH =
        PanelVH(ItemOnboardingPanelBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: PanelVH, position: Int) = holder.bind(panels[position])

    inner class PanelVH(private val b: ItemOnboardingPanelBinding) : RecyclerView.ViewHolder(b.root) {

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

            if (panel.permissions.isEmpty()) bindFeaturePage(panel) else bindPermissionsPage(panel)
        }

        /** Glyph in the flexible area above the copy, which sits at the bottom as drawn. */
        private fun bindFeaturePage(panel: OnboardingPanel) {
            b.panelIconArea.visibility = View.VISIBLE
            b.panelIcon.visibility = View.VISIBLE
            b.panelIcon.setImageResource(panel.iconRes)
            b.panelIcon.layoutParams = (b.panelIcon.layoutParams as FrameLayout.LayoutParams).apply {
                width = metrics.units(panel.iconSize)
                height = metrics.units(panel.iconSize)
            }
            b.panelIconArea.layoutParams =
                (b.panelIconArea.layoutParams as LinearLayout.LayoutParams).apply {
                    height = 0
                    weight = 1f
                }
            b.permTopDivider.visibility = View.GONE
            b.permList.visibility = View.GONE
            b.permList.removeAllViews()
        }

        /** The flexible area collapses to the reference's header gap and the ruled list takes it. */
        private fun bindPermissionsPage(panel: OnboardingPanel) {
            b.panelIcon.visibility = View.GONE
            b.panelIconArea.layoutParams =
                (b.panelIconArea.layoutParams as LinearLayout.LayoutParams).apply {
                    height = metrics.units(38f)
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
            r.permRowTitle.text = context.getString(row.label)
            r.permRowSummary.text = context.getString(row.summary)
            r.permRowDivider.layoutParams = (r.permRowDivider.layoutParams as LinearLayout.LayoutParams).apply {
                height = metrics.hairline()
            }
            r.permRowContent.setPadding(0, metrics.units(13f), 0, metrics.units(13f))
            r.permRowCopy.margin { marginStart = metrics.units(16f) }
            r.permRowSummary.margin { topMargin = metrics.units(2f) }
            return r.root
        }
    }

    /** Rescales one view's own margins in the layout params it already has. */
    private inline fun View.margin(block: ViewGroup.MarginLayoutParams.() -> Unit) {
        (layoutParams as ViewGroup.MarginLayoutParams).apply(block)
        requestLayout()
    }
}
