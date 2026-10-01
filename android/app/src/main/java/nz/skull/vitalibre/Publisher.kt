package nz.skull.vitalibre

import android.content.Context
import nz.skull.vitalibre.core.BPModel
import nz.skull.vitalibre.core.MiniJson

/**
 * Everything that belongs to the publisher (product ids, URLs, policy text) is read from the shared
 * publisher config in the assets, never written into app code. A fork replaces those files and nothing else.
 */
class Donation(val tier: Int, val productId: String, val fallbackPrice: String)

object Publisher {
    lateinit var displayName: String
    lateinit var sourceURL: String
    var donations: List<Donation> = emptyList()
    lateinit var freeForeverTitle: String
    lateinit var freeForever: String
    lateinit var nothingSentTitle: String
    lateinit var nothingSent: String
    lateinit var disclaimer: String
    lateinit var disclaimerBody: String
    lateinit var regulatory: String
    lateinit var model: BPModel

    fun init(context: Context) {
        val am = context.assets
        fun text(path: String) = am.open(path).bufferedReader().use { it.readText() }
        val store = MiniJson.parse(text("config/store.config.json")) as Map<*, *>
        displayName = store["displayName"] as String
        sourceURL = store["sourceURL"] as String
        donations = (store["donations"] as List<*>).map {
            val d = it as Map<*, *>
            Donation((d["tier"] as Number).toInt(), d["productId"] as String, d["fallbackPrice"] as String)
        }
        val policy = MiniJson.parse(text("config/policy.json")) as Map<*, *>
        freeForeverTitle = policy["freeForeverTitle"] as String
        freeForever = policy["freeForever"] as String
        nothingSentTitle = policy["nothingSentTitle"] as String
        nothingSent = policy["nothingSent"] as String
        disclaimer = policy["disclaimer"] as String
        disclaimerBody = policy["disclaimerBody"] as String
        regulatory = policy["regulatory"] as String
        model = try {
            BPModel.fromJson(text("bp-model.json"))
        } catch (e: Exception) {
            BPModel.prior1
        }
    }
}
