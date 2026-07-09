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
 *  - Not configured → password setup dialog → SAF folder pick → [SafeManager.configure].
 *  - Configured     → lock screen (biometric first, password fallback) → grid of decrypted thumbs.
 * The activity is [WindowManager.LayoutParams.FLAG_SECURE] (no screenshots / recents preview) and
 * relocks whenever it goes to the background. Moving photos in from the gallery is done by launching
 * this activity with [ExtraImportUris]; the successfully imported originals are returned via
 * [ExtraImportedUris] so [MainActivity] can delete them through its normal delete-consent flow.
 */
class SafeActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySafeBinding
    private lateinit var adapter: SafeItemAdapter

    private val thumbCache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtLeast(4 * 1024 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private var pendingSetupPassword: String? = null
    private var pendingImportUris: List<Uri> = emptyList()
    private val importedForDeletion = ArrayList<Uri>()
    /** Set before launching external system UI so onStop doesn't relock mid-flow. */
    private var suppressRelock = false

    private val pickPhotosLauncher = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris -> if (uris.isNotEmpty()) runImport(uris, isMove = false) }

    private val storageAccessLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { finishPendingSetupIfPossible() }

    override fun onCreate(savedInstanceState: Bundle?) {
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

        binding.backBtn.setOnClickListener { finish() }
        binding.overflowBtn.setOnClickListener { showOverflow() }
        binding.addPhotosBtn.visibility = View.GONE
        binding.fingerprintBtn.setOnClickListener { authenticateToUnlock() }
        binding.unlockBtn.setOnClickListener { onPasswordUnlock() }
        binding.lockPasswordInput.setOnEditorActionListener { _, _, _ -> onPasswordUnlock(); true }

        if (!SafeManager.isConfigured(this)) startSetup() else showLock()
    }

    override fun onStop() {
        super.onStop()
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

        val view = layoutInflater.inflate(R.layout.dialog_safe_setup, null)
        val pw = view.findViewById<EditText>(R.id.safePassword)
        val confirm = view.findViewById<EditText>(R.id.safePasswordConfirm)
        val dialog = AlertDialog.Builder(this, R.style.Theme_GallerySearch_Dialog)
            .setView(view)
            .setCancelable(false)
            .create()

        view.findViewById<TextView>(R.id.safeSetupCancel).setOnClickListener {
            dialog.dismiss()
            finish()
        }
        view.findViewById<TextView>(R.id.safeSetupContinue).setOnClickListener {
            val password = pw.text.toString()
            val confirmText = confirm.text.toString()
            when {
                password.length < 4 ->
                    Toast.makeText(this, "Use at least 4 characters", Toast.LENGTH_SHORT).show()
                password != confirmText ->
                    Toast.makeText(this, "Passwords don't match", Toast.LENGTH_SHORT).show()
                else -> {
                    pendingSetupPassword = password
                    dialog.dismiss()
                    finishPendingSetupIfPossible()
                }
            }
        }
        dialog.show()
    }

    private fun finishPendingSetupIfPossible() {
        val setupPassword = pendingSetupPassword
        if (setupPassword == null || SafeManager.isConfigured(this)) return
        if (!StoragePermissions.hasAllFilesAccess(this)) {
            suppressRelock = true
            Toast.makeText(
                this,
                "Allow all-files access to create ${SafeManager.vaultLocationLabel()}",
                Toast.LENGTH_LONG
            ).show()
            storageAccessLauncher.launch(StoragePermissions.manageAllFilesIntent(this))
            return
        }
        pendingSetupPassword = null
        binding.busySpinner.visibility = View.VISIBLE
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                SafeManager.setUpVault(this@SafeActivity, setupPassword)
            }
            binding.busySpinner.visibility = View.GONE
            when (outcome) {
                SafeManager.SetupOutcome.NO_ACCESS -> {
                    pendingSetupPassword = setupPassword
                    Toast.makeText(this@SafeActivity, "All-files access is required for Safe", Toast.LENGTH_LONG)
                        .show()
                    finishPendingSetupIfPossible()
                }
                SafeManager.SetupOutcome.WRONG_PASSWORD -> {
                    Toast.makeText(
                        this@SafeActivity,
                        "That password doesn't match the Safe archive",
                        Toast.LENGTH_LONG
                    ).show()
                    startSetup()
                }
                SafeManager.SetupOutcome.ADOPTED -> {
                    Toast.makeText(this@SafeActivity, "Safe unlocked", Toast.LENGTH_SHORT).show()
                    showContent()
                    offerBiometricEnroll { processPendingImport() }
                }
                SafeManager.SetupOutcome.CREATED -> {
                    Toast.makeText(this@SafeActivity, "Safe created", Toast.LENGTH_SHORT).show()
                    showContent()
                    offerBiometricEnroll { processPendingImport() }
                }
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
                Toast.makeText(this@SafeActivity, "Incorrect password", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "Fingerprint changed — enter your password", Toast.LENGTH_LONG).show()
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
                        Toast.makeText(this@SafeActivity, "Couldn't read password", Toast.LENGTH_SHORT).show()
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
        binding.busySpinner.visibility = View.VISIBLE
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { SafeManager.listItems(this@SafeActivity) }
            binding.busySpinner.visibility = View.GONE
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
        binding.busySpinner.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { SafeManager.importPhotos(this@SafeActivity, uris) }
            binding.busySpinner.visibility = View.GONE
            val msg = buildString {
                append("Added ${result.imported} to Safe")
                if (result.failed > 0) append(" · ${result.failed} failed")
            }
            Toast.makeText(this@SafeActivity, msg, Toast.LENGTH_SHORT).show()
            if (isMove && result.importedSources.isNotEmpty()) {
                importedForDeletion.addAll(result.importedSources)
                setResult(RESULT_OK, Intent().putParcelableArrayListExtra(ExtraImportedUris, importedForDeletion))
            }
            loadItems()
        }
    }

    // ---- Item actions ----

    private fun showPhoto(item: SafeManager.VaultItem) {
        binding.busySpinner.visibility = View.VISIBLE
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { SafeManager.decryptToBitmap(this@SafeActivity, item) }
            binding.busySpinner.visibility = View.GONE
            if (bmp == null) {
                Toast.makeText(this@SafeActivity, "Couldn't open photo", Toast.LENGTH_SHORT).show()
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
        AlertDialog.Builder(this, R.style.Theme_GallerySearch_Dialog)
            .setItems(arrayOf("Save back to gallery", "Remove from Safe")) { _, which ->
                when (which) {
                    0 -> restoreItem(item)
                    1 -> confirmRemove(item)
                }
            }
            .show()
    }

    private fun restoreItem(item: SafeManager.VaultItem) {
        binding.busySpinner.visibility = View.VISIBLE
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val restored = SafeManager.restoreToGallery(this@SafeActivity, item)
                if (restored != null) SafeManager.removeItem(this@SafeActivity, item) else false
            }
            binding.busySpinner.visibility = View.GONE
            Toast.makeText(
                this@SafeActivity,
                if (ok) "Saved back to gallery" else "Couldn't restore",
                Toast.LENGTH_SHORT
            ).show()
            if (ok) loadItems()
        }
    }

    private fun confirmRemove(item: SafeManager.VaultItem) {
        AlertDialog.Builder(this, R.style.Theme_GallerySearch_Dialog)
            .setTitle("Remove from Safe?")
            .setMessage("This permanently deletes the encrypted copy. Save it back to your gallery first if you want to keep it.")
            .setPositiveButton("Remove") { _, _ ->
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) { SafeManager.removeItem(this@SafeActivity, item) }
                    if (ok) {
                        thumbCache.remove(item.entryName)
                        loadItems()
                    } else {
                        Toast.makeText(this@SafeActivity, "Couldn't remove", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
            val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clip.setPrimaryClip(ClipData.newPlainText("Safe password", password))
            Toast.makeText(this, "Password copied", Toast.LENGTH_SHORT).show()
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
        AlertDialog.Builder(this, R.style.Theme_GallerySearch_Dialog)
            .setTitle("Enable fingerprint unlock?")
            .setMessage("Open your Safe with a fingerprint instead of typing the password each time.")
            .setPositiveButton("Enable") { _, _ -> enrollBiometric(); next() }
            .setNegativeButton("Not now") { _, _ -> next() }
            .setOnCancelListener { next() }
            .show()
    }

    private fun turnOnFingerprint() {
        if (!SafeManager.isUnlocked) {
            Toast.makeText(this, "Unlock the Safe first", Toast.LENGTH_SHORT).show()
            return
        }
        if (!biometricAvailable()) {
            Toast.makeText(this, "No fingerprint set up on this device", Toast.LENGTH_LONG).show()
            return
        }
        enrollBiometric()
    }

    private fun enrollBiometric() {
        val password = SafeManager.currentPassword() ?: return
        val cipher = try {
            SafeKeystore.encryptCipher()
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't set up fingerprint", Toast.LENGTH_SHORT).show()
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
                        Toast.makeText(this@SafeActivity, "Fingerprint unlock enabled", Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        Toast.makeText(this@SafeActivity, "Couldn't enable fingerprint", Toast.LENGTH_SHORT).show()
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
        Toast.makeText(this, "Fingerprint unlock turned off", Toast.LENGTH_SHORT).show()
    }

    private fun confirmRemoveSafe() {
        AlertDialog.Builder(this, R.style.Theme_GallerySearch_Dialog)
            .setTitle("Remove Safe from this device?")
            .setMessage("Your encrypted file stays in its folder — you can reopen it later with your password. Fingerprint and app settings are cleared from this device.")
            .setPositiveButton("Remove") { _, _ ->
                SafeStore.reset(this)
                SafeManager.lock(this)
                java.io.File(filesDir, "safe_thumbs").deleteRecursively()
                Toast.makeText(this, "Safe removed from this device", Toast.LENGTH_LONG).show()
                finish()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val ExtraImportUris = "safe_import_uris"
        const val ExtraImportedUris = "safe_imported_uris"
    }
}
