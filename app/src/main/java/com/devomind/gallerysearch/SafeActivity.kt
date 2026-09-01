package com.devomind.gallerysearch

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.devomind.gallerysearch.databinding.ActivitySafeBinding
import com.github.chrisbanes.photoview.PhotoView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The encrypted photo locker ("Safe").
 *
 * Lifecycle:
 *  - Not configured → storage-access request → existing-vault choice (adopt the found archive with
 *    its password, or delete it and start over) → password → [SafeManager.setUpVault].
 *  - Configured     → lock screen (biometric first, password fallback) → grid of decrypted thumbs.
 * The activity is [WindowManager.LayoutParams.FLAG_SECURE] (no screenshots / recents preview) and
 * relocks whenever it goes to the background. Moving photos in from the gallery works even before
 * setup: launching with [ExtraImportUris] routes through the same setup chain, and the pending
 * import runs as soon as the vault is unlocked; the successfully imported originals are returned
 * via [ExtraImportedUris] so [MainActivity] can delete them through its normal delete-consent flow.
 */
class SafeActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySafeBinding
    private lateinit var adapter: SafeItemAdapter

    private val thumbCache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtLeast(4 * 1024 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private var pendingImportUris: List<Uri> = emptyList()
    private val importedForDeletion = ArrayList<Uri>()
    /** Set before launching external system UI so onStop doesn't relock mid-flow. */
    private var suppressRelock = false

    private val pickPhotosLauncher = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris -> if (uris.isNotEmpty()) runImport(uris, isMove = false) }

    private val storageAccessLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // The settings screen returns no useful result code — re-check the actual access state.
        // Re-prompt on denial instead of relaunching settings in a loop.
        if (StoragePermissions.hasAllFilesAccess(this)) beginSetupFlow() else promptStorageAccess()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AccentPalette.apply(this)
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK

        binding = ActivitySafeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()

        // Drop thumbnails cached under the old 320px naming so the grid regenerates them sharp.
        SafeManager.cleanupLegacyThumbs(this)

        @Suppress("UNCHECKED_CAST")
        pendingImportUris = (intent.getParcelableArrayListExtra<Uri>(ExtraImportUris) ?: arrayListOf())

        adapter = SafeItemAdapter(
            onClick = { pos -> if (pos >= 0) showPhoto(adapter.itemAt(pos)) },
            onLongClick = { item -> showItemOptions(item) },
            bindThumb = ::bindThumb
        )
        binding.safeGrid.layoutManager = GridLayoutManager(this, 3)
        binding.safeGrid.adapter = adapter
        binding.safeGrid.setHasFixedSize(true)

        // Anchor the accent line at the top bar's bottom border (above the vault title),
        // pinned on screen while the pull stretches the grid.
        PullToRefresh.bind(
            binding.safeGrid,
            onRefresh = { loadItems() },
            borderY = {
                val header = binding.safeGrid.parent as View
                binding.topBar.bottom - header.top - binding.safeGrid.translationY
            }
        )

        binding.backBtn.setOnClickListener { finish() }
        binding.overflowBtn.setOnClickListener { showOverflow() }
        binding.addPhotosBtn.visibility = View.GONE
        binding.fingerprintBtn.setOnClickListener { authenticateToUnlock() }
        binding.unlockBtn.setOnClickListener { onPasswordUnlock() }
        binding.lockPasswordInput.setOnEditorActionListener { _, _, _ -> onPasswordUnlock(); true }
        binding.forgotPasswordBtn.setOnClickListener { offerResetOrphanedVault() }

        if (!SafeManager.isConfigured(this)) startSetup() else showLock()
    }

    override fun onStop() {
        super.onStop()
        // Nothing to relock before the vault is configured, and re-showing the lock UI mid-setup
        // would strand the flow (e.g. while the all-files settings screen is up).
        if (!SafeManager.isConfigured(this)) return
        if (!suppressRelock && !isChangingConfigurations) {
            SafeManager.lock(this)
            showLock()
        }
    }

    override fun onResume() {
        super.onResume()
        suppressRelock = false
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.safeRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBar.updatePadding(top = bars.top + dp(8))
            binding.contentPanel.updatePadding(bottom = bars.bottom)
            binding.lockOverlay.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
    }

    // ---- Setup ----

    private fun startSetup() {
        binding.contentPanel.visibility = View.GONE
        binding.lockOverlay.visibility = View.VISIBLE
        binding.lockSubtitle.text = "Not set up"
        binding.fingerprintBtn.visibility = View.GONE
        binding.lockDivider.visibility = View.GONE
        binding.lockPasswordInput.visibility = View.GONE
        binding.unlockBtn.visibility = View.GONE
        binding.forgotPasswordBtn.visibility = View.GONE
        beginSetupFlow()
    }

    /**
     * Setup chain: storage access first (the archive check reads the vault's public folder, so
     * nothing can be detected before it's granted), then an adopt-or-delete choice when an
     * encrypted Safe file already exists on disk, then the password step.
     */
    private fun beginSetupFlow() {
        if (!StoragePermissions.hasAllFilesAccess(this)) {
            promptStorageAccess()
            return
        }
        showBusy()
        lifecycleScope.launch {
            val hasExisting = withContext(Dispatchers.IO) { SafeManager.archiveExists(this@SafeActivity) }
            hideBusy()
            if (hasExisting) offerExistingVaultChoice() else showPasswordSetupDialog(adoptMode = false)
        }
    }

    private fun promptStorageAccess() {
        MetroDialog.confirm(
            this,
            title = "Storage access needed",
            message = "Safe keeps your photos in one encrypted file in its own folder on disk. " +
                "All-files access lets the app create and protect that folder.",
            positive = "Grant access",
            negative = "Cancel",
            iconRes = R.drawable.ic_fluent_lock_closed_24_regular,
            cancelable = false,
            onNegative = { finish() },
            onCancel = { finish() }
        ) {
            suppressRelock = true
            storageAccessLauncher.launch(StoragePermissions.manageAllFilesIntent(this))
        }
    }

    /** Existing archive on disk: adopt it with its password, or erase it and start a new Safe. */
    private fun offerExistingVaultChoice() {
        MetroDialog.confirm(
            this,
            title = "Safe already exists",
            message = "An encrypted Safe file already exists on this device. " +
                "Use it with its existing password, or delete it and start a new Safe — deleting " +
                "permanently erases all photos inside it.",
            positive = "Use existing Safe",
            negative = "Delete & start over",
            iconRes = R.drawable.ic_fluent_lock_closed_24_regular,
            cancelable = false,
            onNegative = { confirmDeleteExistingVault() },
            onCancel = { finish() }
        ) {
            showPasswordSetupDialog(adoptMode = true)
        }
    }

    private fun confirmDeleteExistingVault() {
        MetroDialog.confirm(
            this,
            title = "Delete the existing Safe?",
            message = "Every photo inside it will be permanently erased. This cannot be undone.",
            positive = "Delete & start over",
            danger = true,
            iconRes = R.drawable.ic_fluent_lock_closed_24_regular,
            cancelable = false,
            onNegative = { offerExistingVaultChoice() },
            onCancel = { finish() }
        ) {
            showBusy("Deleting old Safe…")
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { SafeManager.purgeVault(this@SafeActivity) }
                hideBusy()
                MetroBanner.show(this@SafeActivity, "Old Safe deleted")
                showPasswordSetupDialog(adoptMode = false)
            }
        }
    }

    /** Password step: create-and-confirm for a fresh Safe, single enter field to adopt one. */
    private fun showPasswordSetupDialog(adoptMode: Boolean) {
        val view = layoutInflater.inflate(R.layout.dialog_safe_setup, null)
        val pw = view.findViewById<EditText>(R.id.safePassword)
        val confirm = view.findViewById<EditText>(R.id.safePasswordConfirm)
        val dialog = AlertDialog.Builder(this, R.style.Theme_GallerySearch_Dialog)
            .setView(view)
            .setCancelable(false)
            .create()

        if (adoptMode) {
            view.findViewById<TextView>(R.id.safeSetupTitle).text = "Use existing Safe"
            view.findViewById<TextView>(R.id.safeSetupNote).text =
                "Enter the password this Safe was created with."
            confirm.visibility = View.GONE
            view.findViewById<TextView>(R.id.safeSetupWarning).visibility = View.GONE
            pw.hint = getString(R.string.enter_password_hint)
            pw.setAutofillHints(android.view.View.AUTOFILL_HINT_PASSWORD)
        }

        view.findViewById<TextView>(R.id.safeSetupCancel).setOnClickListener {
            dialog.dismiss()
            if (adoptMode) offerExistingVaultChoice() else finish()
        }
        view.findViewById<TextView>(R.id.safeSetupContinue).setOnClickListener {
            val password = pw.text.toString()
            when {
                password.isEmpty() ->
                    MetroBanner.show(this, "Enter a password")
                !adoptMode && password.length < 4 ->
                    MetroBanner.show(this, "Use at least 4 characters")
                !adoptMode && password != confirm.text.toString() ->
                    MetroBanner.show(this, "Passwords don't match")
                else -> {
                    dialog.dismiss()
                    createOrAdoptVault(password, adoptMode)
                }
            }
        }
        dialog.show()
    }

    private fun createOrAdoptVault(password: String, adoptMode: Boolean) {
        if (!StoragePermissions.hasAllFilesAccess(this)) {
            MetroBanner.show(this, "All-files access is required for Safe")
            beginSetupFlow()
            return
        }
        showBusy()
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                SafeManager.setUpVault(this@SafeActivity, password)
            }
            hideBusy()
            when (outcome) {
                SafeManager.SetupOutcome.NO_ACCESS -> {
                    MetroBanner.show(this@SafeActivity, "All-files access is required for Safe")
                    beginSetupFlow()
                }
                SafeManager.SetupOutcome.WRONG_PASSWORD ->
                    if (adoptMode) {
                        // They chose to adopt — let them retry; cancel returns to the choice.
                        MetroBanner.show(this@SafeActivity, "Incorrect password")
                        showPasswordSetupDialog(adoptMode = true)
                    } else {
                        // An archive appeared since the choice was made — ask again.
                        offerExistingVaultChoice()
                    }
                SafeManager.SetupOutcome.ADOPTED -> {
                    MetroBanner.show(this@SafeActivity, "Safe unlocked")
                    showContent()
                    offerBiometricEnroll { processPendingImport() }
                }
                SafeManager.SetupOutcome.CREATED -> {
                    MetroBanner.show(this@SafeActivity, "Safe created")
                    showContent()
                    offerBiometricEnroll { processPendingImport() }
                }
            }
        }
    }

    // ---- Reset (forgotten password / orphaned vault) ----

    /**
     * Shown when an existing encrypted vault can't be opened with the entered password (reinstall
     * with a leftover zip, or a forgotten password). Offers to delete the vault and start fresh —
     * the only escape from the WRONG_PASSWORD setup loop.
     */
    private fun offerResetOrphanedVault() {
        MetroDialog.confirm(
            this,
            title = "Safe already exists",
            message = "An encrypted Safe file already exists but the password doesn't match. " +
                "If you've forgotten the password, you can delete it " +
                "and create a new Safe. This permanently erases all photos inside it.",
            positive = "Delete & start over",
            negative = "Try another password",
            danger = true,
            iconRes = R.drawable.ic_fluent_lock_closed_24_regular,
            cancelable = false,
            onNegative = { startSetup() }
        ) {
            showBusy()
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { SafeManager.purgeVault(this@SafeActivity) }
                hideBusy()
                MetroBanner.show(this@SafeActivity, "Old Safe deleted")
                startSetup()
            }
        }
    }

    // ---- Unlock ----

    private fun showLock() {
        binding.contentPanel.visibility = View.GONE
        binding.lockOverlay.visibility = View.VISIBLE
        binding.lockSubtitle.text = "Locked"
        binding.lockDivider.visibility = View.VISIBLE
        binding.lockPasswordInput.visibility = View.VISIBLE
        binding.lockPasswordInput.text?.clear()
        binding.unlockBtn.visibility = View.VISIBLE
        binding.forgotPasswordBtn.visibility = View.VISIBLE

        val useBiometric = canUseBiometric()
        binding.fingerprintBtn.visibility = if (useBiometric) View.VISIBLE else View.GONE
        if (useBiometric) authenticateToUnlock()
    }

    private fun onPasswordUnlock() {
        val password = binding.lockPasswordInput.text?.toString().orEmpty()
        if (password.isEmpty()) return
        unlockWith(password, fromTyping = true)
    }

    private fun unlockWith(password: String, fromTyping: Boolean) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { SafeManager.unlock(this@SafeActivity, password) }
            if (ok) {
                showContent()
                processPendingImport()
            } else if (fromTyping) {
                MetroBanner.show(this@SafeActivity, "Incorrect password")
            }
        }
    }

    private fun canUseBiometric(): Boolean =
        SafeStore.isBiometricEnabled(this) && biometricAvailable()

    private fun biometricAvailable(): Boolean =
        BiometricManager.from(this)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

    private fun authenticateToUnlock() {
        val iv = SafeStore.getBiometricIv(this)
        val blob = SafeStore.getBiometricBlob(this)
        if (iv == null || blob == null) return

        val cipher = try {
            SafeKeystore.decryptCipher(iv)
        } catch (e: KeyPermanentlyInvalidatedException) {
            SafeStore.clearBiometric(this)
            binding.fingerprintBtn.visibility = View.GONE
            MetroBanner.show(this, "Fingerprint changed — enter your password", durationMs = 6000)
            return
        } catch (e: Exception) {
            return
        }

        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val recovered = runCatching {
                        val out = result.cryptoObject?.cipher?.doFinal(blob) ?: return
                        String(out)
                    }.getOrNull() ?: run {
                        MetroBanner.show(this@SafeActivity, "Couldn't read password")
                        return
                    }
                    unlockWith(recovered, fromTyping = false)
                }
            })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Safe")
                .setSubtitle("Use your fingerprint to open the Safe")
                .setNegativeButtonText("Use password")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build(),
            BiometricPrompt.CryptoObject(cipher)
        )
    }

    // ---- Content ----

    private fun showContent() {
        binding.lockOverlay.visibility = View.GONE
        binding.contentPanel.visibility = View.VISIBLE
        loadItems()
    }

    private fun loadItems() {
        showBusy()
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { SafeManager.listItems(this@SafeActivity) }
            hideBusy()
            adapter.submit(items)
            binding.emptyState.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun bindThumb(item: SafeManager.VaultItem, imageView: ImageView) {
        thumbCache.get(item.entryName)?.let {
            imageView.setImageBitmap(it)
            return
        }
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { SafeManager.thumbnail(this@SafeActivity, item) }
            if (bmp != null) {
                thumbCache.put(item.entryName, bmp)
                if (imageView.tag == item.entryName) imageView.setImageBitmap(bmp)
            }
        }
    }

    // ---- Import (move-in from gallery, or copy-in from the picker) ----

    private fun onAddPhotos() {
        suppressRelock = true
        pickPhotosLauncher.launch(
            androidx.activity.result.PickVisualMediaRequest(
                ActivityResultContracts.PickVisualMedia.ImageOnly
            )
        )
    }

    private fun processPendingImport() {
        val uris = pendingImportUris
        if (uris.isEmpty()) return
        pendingImportUris = emptyList()
        runImport(uris, isMove = true)
    }

    private fun runImport(uris: List<Uri>, isMove: Boolean) {
        showBusy(if (uris.size > 1) "Encrypting 1 of ${uris.size}…" else "Encrypting…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                SafeManager.importPhotos(this@SafeActivity, uris) { done, total ->
                    if (total > 1) {
                        runOnUiThread { binding.busyLabel.text = "Encrypting ${done + 1} of $total…" }
                    }
                }
            }
            hideBusy()
            val noun = if (result.imported == 1) "photo" else "photos"
            val msg = buildString {
                append("${result.imported} $noun added to Safe")
                if (result.failed > 0) append(" · ${result.failed} failed")
            }
            MetroBanner.show(this@SafeActivity, msg)
            if (isMove && result.importedSources.isNotEmpty()) {
                importedForDeletion.addAll(result.importedSources)
                setResult(RESULT_OK, Intent().putParcelableArrayListExtra(ExtraImportedUris, importedForDeletion))
            }
            loadItems()
        }
    }

    // ---- Item actions ----

    private fun showPhoto(item: SafeManager.VaultItem) {
        showBusy()
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { SafeManager.decryptToBitmap(this@SafeActivity, item) }
            hideBusy()
            if (bmp == null) {
                MetroBanner.show(this@SafeActivity, "Couldn't open photo")
                return@launch
            }
            openFullscreen(bmp)
        }
    }

    private fun openFullscreen(bitmap: Bitmap) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val photoView = PhotoView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            setImageBitmap(bitmap)
            setOnClickListener { dialog.dismiss() }
        }
        dialog.window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        dialog.setContentView(photoView)
        dialog.show()
    }

    private fun showItemOptions(item: SafeManager.VaultItem) {
        MetroDialog.items(
            this,
            options = listOf("Save back to gallery", "Remove from Safe"),
            dangerIndices = setOf(1)
        ) { which ->
            when (which) {
                0 -> restoreItem(item)
                1 -> confirmRemove(item)
            }
        }
    }

    private fun restoreItem(item: SafeManager.VaultItem) {
        showBusy("Decrypting…")
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val restored = SafeManager.restoreToGallery(this@SafeActivity, item)
                if (restored != null) SafeManager.removeItem(this@SafeActivity, item) else false
            }
            hideBusy()
            MetroBanner.show(
                this@SafeActivity,
                if (ok) "Saved back to gallery" else "Couldn't restore"
            )
            if (ok) loadItems()
        }
    }

    private fun confirmRemove(item: SafeManager.VaultItem) {
        MetroDialog.confirm(
            this,
            title = "Remove from Safe?",
            message = "This permanently deletes the encrypted copy. Save it back to your gallery first if you want to keep it.",
            positive = "Remove",
            danger = true
        ) {
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) { SafeManager.removeItem(this@SafeActivity, item) }
                if (ok) {
                    thumbCache.remove(item.entryName)
                    loadItems()
                } else {
                    MetroBanner.show(this@SafeActivity, "Couldn't remove")
                }
            }
        }
    }

    // ---- Overflow menu ----

    private fun showOverflow() {
        val menu = PopupMenu(this, binding.overflowBtn)
        if (SafeManager.isUnlocked) menu.menu.add(0, 1, 0, "Show password")
        menu.menu.add(0, 2, 1, "Add photos")
        if (biometricAvailable()) {
            if (SafeStore.isBiometricEnabled(this)) {
                menu.menu.add(0, 3, 2, "Turn off fingerprint")
            } else {
                menu.menu.add(0, 4, 2, "Turn on fingerprint")
            }
        }
        menu.menu.add(0, 5, 3, "Lock now")
        menu.menu.add(0, 6, 4, "Remove Safe from this device")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> showPasswordDialog()
                2 -> onAddPhotos()
                3 -> turnOffFingerprint()
                4 -> turnOnFingerprint()
                5 -> { SafeManager.lock(this); showLock() }
                6 -> confirmRemoveSafe()
            }
            true
        }
        menu.show()
    }

    private fun showPasswordDialog() {
        val password = SafeManager.currentPassword() ?: return
        val view = layoutInflater.inflate(R.layout.dialog_safe_password, null)
        view.findViewById<TextView>(R.id.shownPassword).text = password
        val dialog = AlertDialog.Builder(this, R.style.Theme_GallerySearch_Dialog)
            .setView(view)
            .create()
        view.findViewById<TextView>(R.id.copyPasswordBtn).setOnClickListener {
            val clip = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clip.setPrimaryClip(ClipData.newPlainText("Safe password", password))
            MetroBanner.show(this, "Password copied")
        }
        view.findViewById<TextView>(R.id.closePasswordBtn).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /** Offers to enable fingerprint right after setup; [next] runs regardless of the choice. */
    private fun offerBiometricEnroll(next: () -> Unit) {
        if (!biometricAvailable() || SafeStore.isBiometricEnabled(this)) {
            next()
            return
        }
        MetroDialog.confirm(
            this,
            title = "Enable fingerprint unlock?",
            message = "Open your Safe with a fingerprint instead of typing the password each time.",
            positive = "Enable",
            negative = "Not now",
            iconRes = R.drawable.ic_fluent_fingerprint_24_regular,
            onNegative = { next() },
            onCancel = { next() }
        ) {
            enrollBiometric()
            next()
        }
    }

    private fun turnOnFingerprint() {
        if (!SafeManager.isUnlocked) {
            MetroBanner.show(this, "Unlock the Safe first")
            return
        }
        if (!biometricAvailable()) {
            MetroBanner.show(this, "No fingerprint set up on this device")
            return
        }
        enrollBiometric()
    }

    private fun enrollBiometric() {
        val password = SafeManager.currentPassword() ?: return
        val cipher = try {
            SafeKeystore.encryptCipher()
        } catch (e: Exception) {
            MetroBanner.show(this, "Couldn't set up fingerprint")
            return
        }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    runCatching {
                        val c = result.cryptoObject?.cipher ?: return
                        val blob = c.doFinal(password.toByteArray())
                        SafeStore.saveBiometricPassword(this@SafeActivity, blob, c.iv)
                    }.onSuccess {
                        MetroBanner.show(this@SafeActivity, "Fingerprint unlock enabled")
                    }.onFailure {
                        MetroBanner.show(this@SafeActivity, "Couldn't enable fingerprint")
                    }
                }
            })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Enable fingerprint unlock")
                .setSubtitle("Confirm your fingerprint to link it to your Safe")
                .setNegativeButtonText("Cancel")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build(),
            BiometricPrompt.CryptoObject(cipher)
        )
    }

    private fun turnOffFingerprint() {
        SafeStore.clearBiometric(this)
        MetroBanner.show(this, "Fingerprint unlock turned off")
    }

    private fun confirmRemoveSafe() {
        MetroDialog.confirm(
            this,
            title = "Remove Safe from this device?",
            message = "Your encrypted file stays in its folder — you can reopen it later with your password. Fingerprint and app settings are cleared from this device.",
            positive = "Remove",
            danger = true,
            iconRes = R.drawable.ic_fluent_lock_closed_24_regular
        ) {
            SafeStore.reset(this)
            SafeManager.lock(this)
            java.io.File(filesDir, "safe_thumbs").deleteRecursively()
            Toast.makeText(this, "Safe removed from this device", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    /** Shows the busy overlay; [label] adds a progress line ("Encrypting 3 of 12…"). */
    private fun showBusy(label: String? = null) {
        binding.busyPanel.visibility = View.VISIBLE
        binding.busyLabel.visibility = if (label != null) View.VISIBLE else View.GONE
        binding.busyLabel.text = label
    }

    private fun hideBusy() {
        binding.busyPanel.visibility = View.GONE
        binding.busyLabel.visibility = View.GONE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val ExtraImportUris = "safe_import_uris"
        const val ExtraImportedUris = "safe_imported_uris"
    }
}
