package nz.skull.vitalibre

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/** What the screens share. */
class AppEnv(val activity: MainActivity, val prefs: Prefs, val readings: ReadingStore, val measurer: Measurer)

val LocalEnv = staticCompositionLocalOf<AppEnv> { error("no AppEnv") }

enum class AppTab { MEASURE, READINGS, HELP, ABOUT, DONATE }

private val bar = listOf(
    BarItem(AppTab.MEASURE, "Measure", BarIcon.PULSE),
    BarItem(AppTab.READINGS, "Readings", BarIcon.LIST),
    BarItem(AppTab.HELP, "Help", BarIcon.HELP),
    BarItem(AppTab.ABOUT, "About", BarIcon.INFO),
    BarItem(AppTab.DONATE, "Donate", BarIcon.HEART),
)

@Composable
fun RootApp(env: AppEnv) {
    val prefs = env.prefs
    val systemDark = isSystemInDarkTheme()
    val palette = when (prefs.themeMode) {
        ThemeMode.DARK -> Palette.dark
        ThemeMode.LIGHT -> Palette.light
        ThemeMode.AUTO -> if (systemDark) Palette.dark else Palette.light
    }
    val activity = LocalContext.current as Activity
    SideEffect {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.isAppearanceLightStatusBars = palette.isLight
        controller.isAppearanceLightNavigationBars = palette.isLight
    }
    var tab by rememberSaveable { mutableStateOf(AppTab.MEASURE) }
    var showSettings by remember { mutableStateOf(false) }

    // A finished scan is filed the moment it lands, wherever the user is: this host
    // outlives the measure screen, and the screen is rebuilt on every tab change.
    // `file` is keyed by the scan, so seeing the same result again cannot add a
    // second reading.
    val phase = env.measurer.phase
    LaunchedEffect(phase) {
        if (phase is Measurer.Phase.Result) env.readings.file(phase.result, env.measurer.scanId)
    }

    CompositionLocalProvider(LocalPalette provides palette, LocalEnv provides env) {
        Box(Modifier.fillMaxSize().background(palette.backgroundGradient)) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 8.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("VITALIBRE", color = palette.clock, style = Fonts.chakra(15.sp, Fonts.Face.MEDIUM), letterSpacing = 3.sp, modifier = Modifier.weight(1f))
                    Box(Modifier.size(48.dp).clickable { Sounds.play(Sounds.CLICK); Haptics.click(); showSettings = true }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Settings, "Settings", tint = palette.clock, modifier = Modifier.size(22.dp))
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (tab) {
                        AppTab.MEASURE -> MeasureScreen()
                        AppTab.READINGS -> ReadingsScreen()
                        AppTab.HELP -> HelpScreen()
                        AppTab.ABOUT -> AboutScreen()
                        AppTab.DONATE -> DonateScreen()
                    }
                }
                ConsoleBar(bar, tab) { Sounds.play(Sounds.CLICK); Haptics.click(); tab = it }
            }
            if (showSettings) SettingsDialog { showSettings = false }
            if (prefs.onboardedAt == 0L) IntroScreen()
        }
    }
}
