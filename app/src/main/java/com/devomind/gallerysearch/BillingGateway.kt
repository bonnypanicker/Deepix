package com.devomind.gallerysearch

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CopyOnWriteArraySet
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The Google Play Billing connection, held once for the process.
 *
 * Nothing here decides whether a feature is available — that is [Entitlements], which reads a persisted
 * verdict and works with no Play at all. This object only turns Play's answers into that verdict and back
 * again: it queries the catalogue for a price, asks what the account owns, acknowledges what Play has not
 * been told about yet, and puts up Play's own purchase sheet. Every entry point is `runCatching`-wrapped
 * and reports through state rather than exceptions, because a billing failure is a UI condition (the
 * gallery still has to open) and not a crash.
 *
 * Play Billing needs no `INTERNET` permission: it binds to `com.android.vending` over IPC, and the
 * `com.android.vending.BILLING` use-permission and the `<queries>` entry come merged in from the
 * library's own manifest.
 */
object BillingGateway {

    /**
     * The one-time product in Play Console. Renaming it here without creating the new product (and
     * keeping the old one owned by anyone who bought it) strands every existing purchase.
     */
    const val ProProductId = "pixa_pro_unlock"

    private const val Tag = "BillingGateway"

    /** One connection for the process: the library asks Play to bind, and rebinding per screen would
     *  serialize every query behind a fresh IPC handshake. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var appContext: Context? = null
    @Volatile private var client: BillingClient? = null
    @Volatile private var productDetails: ProductDetails? = null
    @Volatile private var lastMessage: String? = null

    /**
     * Screens that show billing state subscribe here; [notifyChanged] fires after any round-trip that can
     * move a price or a verdict. Resuming covers most of it — Play's sheet backgrounds the app, so
     * `onResume` runs after a purchase — but the acknowledge and catalogue calls can land a beat later,
     * and a row that still says "not purchased" beside a paid feature reads as a broken purchase.
     */
    fun observe(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun unobserve(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    private fun notifyChanged() {
        main.post {
            // A screen that fails to repaint must not take Play's callback down with it.
            listeners.forEach { runCatching { it() } }
        }
    }

    /** True while Play has the connection up; the paywall reads it to promise a price it cannot show. */
    val isReachable: Boolean get() = runCatching { client?.isReady == true }.getOrDefault(false)

    /** Play's own formatted price for the locale/currency it sells this product in, or null until the
     *  catalogue query has answered. */
    fun priceLabel(): String? = productDetails?.oneTimePurchaseOfferDetails?.formattedPrice

    /** Short human text for the last thing that went wrong, cleared by the next good round-trip. */
    fun statusMessage(): String? = lastMessage

    /**
     * Called once from [GallerySearchApp.onCreate]. The client must be built on a thread with a Looper
     * (Play delivers its callbacks there), which the application's main thread is; `startConnection`
     * itself is asynchronous, so this costs a bind request and nothing else at cold start.
     */
    fun connect(context: Context) {
        if (client != null) return
        val app = context.applicationContext
        appContext = app
        val created = runCatching {
            BillingClient.newBuilder(app)
                .setListener(purchasesUpdated)
                // Without this the library throws IllegalStateException the moment a pending purchase
                // (a payment the user has not finished, e.g. cash or carrier billing) reaches us.
                // Billing 8.0.0 deleted the no-arg overload, and the params object defaults to *off* for
                // one-time products — which is the only thing sold here, so it has to be named.
                .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                .build()
        }.onFailure { Log.w(Tag, "Could not build the billing client", it) }.getOrNull()
        if (created == null) {
            lastMessage = "Google Play billing is not available on this device"
            return
        }
        client = created
        startConnection(created)
    }

    /**
     * Re-read the catalogue and what the account owns. Screens call this when they come to the
     * foreground: it is how a purchase made in another app instance, a refund, and Play's own restart all
     * get noticed without a long-lived listener of our own.
     */
    fun refresh() {
        val target = client ?: return
        if (!isReachable) {
            startConnection(target)
            return
        }
        scope.launch { sync(target) }
    }

    /**
     * Put up Play's purchase sheet. False means nothing happened and [statusMessage] says why — no
     * connection, no catalogue answer yet, or the activity is gone; the caller keeps the dialog open
     * instead of pretending a purchase is in flight.
     */
    fun purchase(activity: Activity): Boolean {
        val target = client ?: return fail("Google Play is not connected yet")
        val details = productDetails ?: return fail("The price is still loading")
        if (!isReachable) {
            startConnection(target)
            return fail("Google Play is not reachable right now")
        }
        val params = runCatching {
            BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(
                    listOf(
                        // No offer token: those belong to subscriptions. A one-time product is bought
                        // straight off its ProductDetails.
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(details)
                            .build()
                    )
                )
                .build()
        }.getOrNull() ?: return fail("This purchase could not be prepared")
        val result = runCatching { target.launchBillingFlow(activity, params) }.getOrNull()
            ?: return fail("Google Play did not open the purchase screen")
        return if (result.responseCode == BillingClient.BillingResponseCode.OK) {
            lastMessage = null
            true
        } else {
            fail(describe(result))
        }
    }

    private val purchasesUpdated = PurchasesUpdatedListener { result, purchases ->
        val code = result.responseCode
        val target = client
        when (code) {
            BillingClient.BillingResponseCode.USER_CANCELED -> lastMessage = null
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED ->
                // The account does own it and our copy of that fact is what is missing. Re-querying lets
                // Play settle it instead of trusting the sheet's error, and heals an entitlement the
                // user paid for on another device.
                lastMessage = "Already purchased — checking with Google Play"
            else -> lastMessage = describe(result)
        }
        if (target == null) return@PurchasesUpdatedListener
        val records = purchases.orEmpty()
        scope.launch {
            acknowledgePending(target, records)
            val verdict = PurchaseRules.verdictOf(
                reply = if (code == BillingClient.BillingResponseCode.OK) QueryReply.Ok else QueryReply.Failed,
                facts = records.map { it.toFact() },
                productId = ProProductId
            )
            appContext?.let { Entitlements.record(it, verdict) }
            if (code == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) sync(target)
            notifyChanged()
        }
    }

    private suspend fun sync(target: BillingClient) {
        loadProductDetails(target)
        verifyOwnership(target)
        notifyChanged()
    }

    private suspend fun loadProductDetails(target: BillingClient) {
        val params = runCatching {
            QueryProductDetailsParams.newBuilder()
                .setProductList(
                    listOf(
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(ProProductId)
                            .setProductType(BillingClient.ProductType.INAPP)
                            .build()
                    )
                )
                .build()
        }.getOrNull() ?: return
        val result = runCatching { target.queryProductDetails(params) }.getOrNull()
        if (result == null) {
            lastMessage = "Google Play did not return the price"
            return
        }
        productDetails = result.productDetailsList?.firstOrNull { it.productId == ProProductId }
        // A catalogue miss is not an outage: an unconfigured or unpublished product id answers OK with an
        // empty list, and saying so is the only way a wrong id shows up anywhere but Logcat.
        if (productDetails == null && result.billingResult.responseCode ==
            BillingClient.BillingResponseCode.OK
        ) {
            lastMessage = "Play does not know the product \"$ProProductId\""
        } else if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            lastMessage = describe(result.billingResult)
        }
    }

    private suspend fun verifyOwnership(target: BillingClient) {
        val params = runCatching {
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        }.getOrNull() ?: return
        val result = runCatching { target.queryPurchasesAsync(params) }.getOrNull()
        val context = appContext ?: return
        if (result == null) {
            // Deliberately not recorded: an exception here is an outage, and Unreachable would say the
            // same while leaving the stored verdict alone.
            lastMessage = "Google Play could not confirm the purchase"
            return
        }
        val reply = when (result.billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> QueryReply.Ok
            BillingClient.BillingResponseCode.ITEM_NOT_OWNED -> QueryReply.NotOwned
            else -> QueryReply.Failed
        }
        val verdict = PurchaseRules.verdictOf(
            reply = reply,
            facts = result.purchasesList.map { it.toFact() },
            productId = ProProductId
        )
        if (reply == QueryReply.Failed) lastMessage = describe(result.billingResult)
        Entitlements.record(context, verdict)
        acknowledgePending(target, result.purchasesList)
    }

    /**
     * Play cancels an unacknowledged purchase a few days after it lands, so this is what keeps a purchase
     * rather than tidying up after it. Acknowledging a pending one would fail, so the rule in
     * [PurchaseRules.needsAcknowledgment] filters first and the call itself is fire-and-forget: a failed
     * acknowledge shows up on the next query, when Play still lists the purchase as unacknowledged.
     */
    private suspend fun acknowledgePending(target: BillingClient, purchases: List<Purchase>) {
        purchases.filter { PurchaseRules.needsAcknowledgment(it.toFact()) }.forEach { purchase ->
            runCatching {
                target.acknowledgePurchase(
                    AcknowledgePurchaseParams.newBuilder()
                        .setPurchaseToken(purchase.purchaseToken)
                        .build()
                )
            }.onFailure { Log.w(Tag, "Acknowledge failed for ${purchase.orderId}", it) }
        }
    }

    private fun startConnection(target: BillingClient) {
        runCatching {
            target.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        lastMessage = null
                        scope.launch { sync(target) }
                    } else {
                        lastMessage = describe(result)
                    }
                }

                override fun onBillingServiceDisconnected() {
                    // Play's own update or a low-memory kill drops this binder. Retry once after a beat:
                    // a loop here would spin against a Play that is genuinely absent, and every paying
                    // screen calls refresh() on resume anyway, which reconnects on demand.
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!isReachable) runCatching { startConnection(target) }
                    }, ReconnectDelayMillis)
                }
            })
        }.onFailure {
            Log.w(Tag, "startConnection threw", it)
            lastMessage = "Google Play did not answer"
        }
    }

    private fun Purchase.toFact() = PurchaseFact(
        productId = products.firstOrNull().orEmpty(),
        state = when (purchaseState) {
            Purchase.PurchaseState.PURCHASED -> PurchaseState.Purchased
            Purchase.PurchaseState.PENDING -> PurchaseState.Pending
            else -> PurchaseState.Unspecified
        },
        acknowledged = isAcknowledged
    )

    private fun fail(message: String): Boolean {
        lastMessage = message
        return false
    }

    /** Play's codes are numbered for its own docs; the paywall needs the reason, not the number. */
    private fun describe(result: BillingResult): String = when (result.responseCode) {
        BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
        BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
        BillingClient.BillingResponseCode.SERVICE_TIMEOUT,
        BillingClient.BillingResponseCode.NETWORK_ERROR -> "Google Play is unreachable right now"
        BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> "This device cannot buy from Google Play"
        BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED ->
            "Google Play is too old for in-app purchases here"
        BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> "That purchase is not on sale right now"
        BillingClient.BillingResponseCode.DEVELOPER_ERROR -> "The product id is not configured in Play"
        BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> "Already purchased on this account"
        BillingClient.BillingResponseCode.USER_CANCELED -> ""
        else -> result.debugMessage?.takeIf { it.isNotBlank() } ?: "Google Play returned an error"
    }

    private const val ReconnectDelayMillis = 3_000L
}
