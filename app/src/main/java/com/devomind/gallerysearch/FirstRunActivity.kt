package com.devomind.gallerysearch

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.viewpager2.widget.ViewPager2
import com.devomind.gallerysearch.databinding.ActivityFirstRunBinding
import kotlin.math.min

/**
 * First-run tour: what Pixa does, one page per feature, ending on a permissions page where each row
 * asks for its own grant and reports what the system said. Cloned from the reference mock, which lays
 * out at 360dp x 760dp, and redrawn at the actual screen's ratio — see [scaleFactor] and
 * [OnboardingMetrics].
 *
 * The tour runs *before* [MainActivity]'s own request flow on purpose: it promises the library is
 * never uploaded, and the system dialog for reading it is the very next thing the user sees. Asking
 * from the page that explains the ask keeps the two together. "Get started" stays dark while a grant
 * the app cannot work without is still outstanding; the optional rows report their state and never
 * hold the page shut, because a permission the user has refused is not a reason to trap them here.
 * The flag is marked only when the user leaves, so a process death mid-way shows the tour again.
 */
class FirstRunActivity : AppCompatActivity(), OnboardingPermissionHost {

    private lateinit var binding: ActivityFirstRunBinding
    private lateinit var metrics: OnboardingMetrics
    private lateinit var panelAdapter: OnboardingPanelAdapter
    private var pageCount = 0
    private var permissionRows: List<OnboardingPermission> = emptyList()

    /** A grant is only "blocked" once we have actually seen the dialog, never on first sight. */
    private var mediaAsked = false
    private var notificationsAsked = false

    private val mediaLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshPermissions() }

    private val notificationsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshPermissions() }

    /** All-files access and the app's settings page are screens, not dialogs — Back is a real exit. */
    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshPermissions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        AccentPalette.apply(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK
        binding = ActivityFirstRunBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()

        metrics = OnboardingMetrics(scaleFactor(), resources.displayMetrics.density)
        applyChromeScale()

        val panels = onboardingPanels()
        pageCount = panels.size
        permissionRows = panels.firstOrNull { it.permissions.isNotEmpty() }?.permissions.orEmpty()
        buildProgressTrack(panels.size)
        // ViewPager2 doesn't report its opening page, so paint the first segment here and let the
        // post below settle on the restored page if this is a rotation.
        syncChrome(0)
        panelAdapter = OnboardingPanelAdapter(panels, metrics, this)
        binding.panels.adapter = panelAdapter
        binding.panels.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = syncChrome(position)
        })

        binding.btnNext.setOnClickListener {
            val current = binding.panels.currentItem
            if (current >= pageCount - 1) complete() else goTo(current + 1)
        }
        // The reference's X jumps to the permissions page rather than closing the tour: that page is
        // where the asks happen, so it is the one thing not to skip past.
        binding.dismissBtn.setOnClickListener { goTo(pageCount - 1) }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val current = binding.panels.currentItem
                if (current > 0) goTo(current - 1) else complete()
            }
        })
        // A rotation restores the pager's page only after its first layout, so re-sync the footer to
        // whatever it lands on rather than leaving "Next" over a filled bar.
        binding.panels.post { syncChrome(binding.panels.currentItem) }
    }

    /** Covers a settings screen left through Back, which never reaches the launcher's callback. */
    override fun onResume() {
        super.onResume()
        if (::panelAdapter.isInitialized) refreshPermissions()
    }

    private fun goTo(position: Int) {
        binding.panels.setCurrentItem(position.coerceIn(0, pageCount - 1), true)
    }

    private fun complete() {
        IndexPreferences.setFirstRunDone(this, true)
        finish()
    }

    // ---------------------------------------------------------------------------------------------
    // Permission rows
    // ---------------------------------------------------------------------------------------------

    override fun statusOf(kind: OnboardingPermissionKind): OnboardingPermissionStatus = when (kind) {
        OnboardingPermissionKind.Media -> when {
            StoragePermissions.hasMediaAccess(this) -> granted()
            // Denied outright twice and the system won't show the dialog again; the only way left is
            // the app's settings page, so the row offers that instead of a dead button.
            mediaBlocked() -> OnboardingPermissionStatus(
                OnboardingPermissionState.OUTSTANDING,
                R.string.onboarding_perm_state_open_settings
            )
            else -> outstanding(R.string.onboarding_perm_state_required)
        }

        OnboardingPermissionKind.AllFiles ->
            if (StoragePermissions.hasAllFilesAccess(this)) granted() else outstanding()

        OnboardingPermissionKind.Notifications -> when {
            Build.VERSION.SDK_INT < 33 -> OnboardingPermissionStatus(
                OnboardingPermissionState.UNAVAILABLE,
                R.string.onboarding_perm_state_not_needed
            )
            notificationGranted() -> granted()
            permissionBlocked(Manifest.permission.POST_NOTIFICATIONS, notificationsAsked) ->
                OnboardingPermissionStatus(
                    OnboardingPermissionState.UNAVAILABLE,
                    R.string.onboarding_perm_state_blocked
                )
            else -> outstanding()
        }
    }

    override fun request(kind: OnboardingPermissionKind) {
        when (kind) {
            OnboardingPermissionKind.Media -> {
                if (mediaBlocked()) {
                    openAppSettings()
                } else {
                    mediaAsked = true
                    mediaLauncher.launch(StoragePermissions.requiredMediaPermissions())
                }
            }

            OnboardingPermissionKind.AllFiles -> runCatching {
                settingsLauncher.launch(StoragePermissions.manageAllFilesIntent(this))
            }.onFailure { MetroBanner.show(this, "Couldn't open storage access settings") }

            OnboardingPermissionKind.Notifications -> {
                if (Build.VERSION.SDK_INT < 33) return
                notificationsAsked = true
                notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    /**
     * Whether the tour still can't be finished: a required grant we could still go and get is enough.
     * [OnboardingPermissionState.UNAVAILABLE] rows are excluded by design — the system has taken them
     * out of play, and holding the only exit shut would strand the user on the last page.
     */
    private fun finishBlocked(): Boolean = permissionRows.any { row ->
        row.required && statusOf(row.kind).state == OnboardingPermissionState.OUTSTANDING
    }

    private fun refreshPermissions() {
        panelAdapter.refreshPermissions()
        syncChrome(binding.panels.currentItem)
    }

    private fun mediaBlocked(): Boolean =
        permissionBlocked(StoragePermissions.requiredMediaPermissions()[0], mediaAsked)

    /**
     * True once we have shown the dialog and the OS has taken it away: no grant, and no rationale
     * offer left to make. Before the first ask the rationale is false too, which is why [asked] is
     * part of the test — otherwise a fresh install reads as blocked.
     */
    private fun permissionBlocked(permission: String, asked: Boolean): Boolean =
        asked &&
            ContextCompat.checkSelfPermission(this, permission) !=
            PackageManager.PERMISSION_GRANTED &&
            !ActivityCompat.shouldShowRequestPermissionRationale(this, permission)

    private fun notificationGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun granted() = OnboardingPermissionStatus(
        OnboardingPermissionState.GRANTED, R.string.onboarding_perm_state_granted
    )

    private fun outstanding(
        @StringRes labelRes: Int = R.string.onboarding_perm_state_optional
    ) = OnboardingPermissionStatus(OnboardingPermissionState.OUTSTANDING, labelRes)

    private fun openAppSettings() {
        runCatching {
            settingsLauncher.launch(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        }.onFailure { MetroBanner.show(this, "Couldn't open the app's settings") }
    }

    // ---------------------------------------------------------------------------------------------
    // Chrome
    // ---------------------------------------------------------------------------------------------

    /** Segments fill left to right as the tour is read; the CTA relabels, and waits, on the last page. */
    private fun syncChrome(position: Int) {
        val accent = DesignTokens.accent(this)
        val resting = ContextCompat.getColor(this, R.color.metroBgCard)
        for (index in 0 until binding.progressTrack.childCount) {
            binding.progressTrack.getChildAt(index)
                .setBackgroundColor(if (index <= position) accent else resting)
        }
        val lastPage = position >= pageCount - 1
        binding.btnNext.setText(
            if (lastPage) R.string.onboarding_get_started else R.string.onboarding_next
        )
        val canFinish = !lastPage || !finishBlocked()
        binding.btnNext.isEnabled = canFinish
        binding.btnNext.alpha = if (canFinish) 1f else 0.35f
    }

    /** One segment per page, weighted equally with the reference's 3-unit gap between them. */
    private fun buildProgressTrack(count: Int) {
        binding.progressTrack.removeAllViews()
        val gap = metrics.units(3f)
        val height = metrics.units(2f)
        repeat(count) { index ->
            binding.progressTrack.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, height, 1f).apply {
                    if (index < count - 1) marginEnd = gap
                }
            })
        }
    }

    /** The brand line, its dismiss target, the footer block and the CTA at this screen's ratio. */
    private fun applyChromeScale() {
        binding.brandRow.layoutParams = binding.brandRow.layoutParams.apply {
            height = metrics.units(46f)
        }
        binding.brandRow.setPadding(
            metrics.units(20f), metrics.units(14f), metrics.units(12f), 0
        )
        binding.brandRow.requestLayout()
        binding.brandText.setTextSize(metrics.textSp(11f))

        binding.dismissBtn.layoutParams = binding.dismissBtn.layoutParams.apply {
            width = metrics.units(40f)
            height = metrics.units(40f)
        }
        val dismissPad = metrics.units(12f)
        binding.dismissBtn.setPadding(dismissPad, dismissPad, dismissPad, dismissPad)
        binding.dismissBtn.requestLayout()

        binding.footer.setPadding(
            metrics.units(28f), metrics.units(4f), metrics.units(28f), metrics.units(26f)
        )
        binding.btnNext.layoutParams = (binding.btnNext.layoutParams as LinearLayout.LayoutParams).apply {
            height = metrics.units(48f)
            topMargin = metrics.units(22f)
        }
        binding.btnNext.setTextSize(metrics.textSp(13f))
        binding.btnNext.requestLayout()
    }

    /**
     * Scale with the tighter axis: width on a narrow phone, and the panel column's height on a short
     * or landscape one — the ~150 units of brand row plus footer come off first, because those are
     * scaled by the same factor. Clamped so a tablet reads as a generous tour rather than a blown-up
     * phone, and a small phone never clips a page.
     */
    private fun scaleFactor(): Float {
        val density = resources.displayMetrics.density
        val widthDp = resources.displayMetrics.widthPixels / density
        val panelHeightDp = resources.displayMetrics.heightPixels / density - 150f
        return min(widthDp / 360f, panelHeightDp / 600f).coerceIn(0.72f, 1.3f)
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.root.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
    }

    companion object {
        /** Until the user has been through the tour once, it owns the cold start. */
        fun shouldShow(context: Context): Boolean = !IndexPreferences.hasSeenFirstRun(context)

        fun intent(context: Context): Intent = Intent(context, FirstRunActivity::class.java)
    }
}
