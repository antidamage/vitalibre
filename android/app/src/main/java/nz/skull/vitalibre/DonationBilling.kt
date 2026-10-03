package nz.skull.vitalibre

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/** Owns the Play connection for the activity lifetime. A payment never unlocks app content. */
class DonationBilling(private val activity: MainActivity) : PurchasesUpdatedListener {
    enum class State { LOADING, READY, UNAVAILABLE, BUYING, PENDING, THANKS, FAILED }

    var state by mutableStateOf(State.LOADING)
        private set
    var message by mutableStateOf("")
        private set
    val prices = mutableStateMapOf<Int, String>()
    private val products = mutableMapOf<Int, ProductDetails>()
    private val consuming = mutableSetOf<String>()
    private val completed = mutableSetOf<String>()
    private var buyingTier: Int? = null
    private var started = false

    private val client = BillingClient.newBuilder(activity)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    fun start() {
        if (started) return
        started = true
        state = State.LOADING
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    refresh()
                } else {
                    fail("Google Play Billing is unavailable. Please try again later.")
                }
            }

            override fun onBillingServiceDisconnected() {
                started = false
                products.clear()
                prices.clear()
                state = State.UNAVAILABLE
                message = "Google Play disconnected. Please retry."
            }
        })
    }

    fun refresh() {
        if (!client.isReady) {
            started = false
            start()
            return
        }
        state = State.LOADING
        val requested = Publisher.donations.map {
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(it.productId)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        }
        client.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(requested).build()) { result, found ->
            products.clear()
            prices.clear()
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                for (detail in found.productDetailsList) {
                    val tier = Publisher.donations.firstOrNull { it.productId == detail.productId }?.tier ?: continue
                    val buyOffer = detail.oneTimePurchaseOfferDetailsList?.firstOrNull() ?: continue
                    products[tier] = detail
                    prices[tier] = buyOffer.formattedPrice
                }
            }
            if (state != State.PENDING && state != State.THANKS && state != State.BUYING) {
                state = if (products.isEmpty()) State.UNAVAILABLE else State.READY
                message = if (products.isEmpty()) "Support payments are unavailable in Google Play. Every feature remains free." else ""
            }
        }
        queryOutstanding()
    }

    fun queryOutstanding() {
        if (!client.isReady) return
        val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        client.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                if (purchases.isEmpty() && state == State.PENDING) {
                    state = if (products.isEmpty()) State.UNAVAILABLE else State.READY
                    message = if (products.isEmpty()) "Support payments are unavailable in Google Play. Every feature remains free." else ""
                }
                purchases.forEach(::process)
            }
        }
    }

    fun buy(tier: Int) {
        if (buyingTier != null || state == State.LOADING) return
        val detail = products[tier] ?: return
        val offer = detail.oneTimePurchaseOfferDetailsList?.firstOrNull() ?: return
        buyingTier = tier
        state = State.BUYING
        message = ""
        val itemBuilder = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(detail)
        offer.offerToken?.let(itemBuilder::setOfferToken)
        val item = itemBuilder.build()
        val result = client.launchBillingFlow(activity, BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(item)).build())
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            buyingTier = null
            fail("Google Play could not start the payment. Please try again.")
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                if (purchases.isNullOrEmpty()) {
                    buyingTier = null
                    fail("Google Play returned no completed payment. Please retry.")
                } else purchases.forEach(::process)
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                buyingTier = null
                state = State.READY
                message = ""
            }
            else -> {
                buyingTier = null
                fail("Google Play could not complete the payment. Please try again.")
            }
        }
    }

    private fun process(purchase: Purchase) {
        if (purchase.products.none { id -> Publisher.donations.any { it.productId == id } }) return
        when (purchase.purchaseState) {
            Purchase.PurchaseState.PENDING -> {
                buyingTier = null
                state = State.PENDING
                message = "Payment is pending. Google Play will confirm it when approved."
            }
            Purchase.PurchaseState.PURCHASED -> {
                val token = purchase.purchaseToken
                if (token in completed || !consuming.add(token)) return
                val params = ConsumeParams.newBuilder().setPurchaseToken(token).build()
                client.consumeAsync(params) { result, _ ->
                    consuming.remove(token)
                    buyingTier = null
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        completed.add(token)
                        state = State.THANKS
                        message = "Thank you for your support."
                    } else {
                        fail("Google Play received the payment, but confirmation is still processing. Please retry to check it.")
                    }
                }
            }
        }
    }

    private fun fail(text: String) {
        state = State.FAILED
        message = text
    }

    fun close() { client.endConnection() }
}
