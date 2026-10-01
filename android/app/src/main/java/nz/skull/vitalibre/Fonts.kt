package nz.skull.vitalibre

import android.content.res.AssetManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit

/**
 * Chakra Petch (UI, numerals) and Rajdhani (secondary), both OFL, read from the bundled font files
 * shared with the iOS build. A missing file falls back to the system font.
 */
object Fonts {
    enum class Face { LIGHT, REGULAR, MEDIUM, SEMIBOLD }

    private var assets: AssetManager? = null
    private val cache = HashMap<String, FontFamily>()

    fun init(assetManager: AssetManager) { assets = assetManager }

    private fun family(file: String, weight: FontWeight): FontFamily = cache.getOrPut(file) {
        val am = assets ?: return@getOrPut FontFamily.Default
        try {
            FontFamily(Font("Fonts/$file", am, weight))
        } catch (e: Exception) {
            FontFamily.Default
        }
    }

    fun chakra(size: TextUnit, face: Face = Face.REGULAR): TextStyle = when (face) {
        Face.LIGHT -> TextStyle(fontFamily = family("ChakraPetch-Light.ttf", FontWeight.Light), fontSize = size)
        Face.REGULAR -> TextStyle(fontFamily = family("ChakraPetch-Regular.ttf", FontWeight.Normal), fontSize = size)
        Face.MEDIUM -> TextStyle(fontFamily = family("ChakraPetch-Medium.ttf", FontWeight.Medium), fontSize = size)
        Face.SEMIBOLD -> TextStyle(fontFamily = family("ChakraPetch-SemiBold.ttf", FontWeight.SemiBold), fontSize = size)
    }

    fun rajdhani(size: TextUnit, semibold: Boolean = false): TextStyle =
        if (semibold) TextStyle(fontFamily = family("Rajdhani-SemiBold.ttf", FontWeight.SemiBold), fontSize = size)
        else TextStyle(fontFamily = family("Rajdhani-Medium.ttf", FontWeight.Medium), fontSize = size)
}
