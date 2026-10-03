package nz.skull.vitalibre

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import nz.skull.vitalibre.core.ScanSession
import nz.skull.vitalibre.core.Sex
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/** A full-screen themed sheet. */
@Composable
fun FullDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(p.backgroundGradient)) {
            androidx.compose.foundation.layout.Box(Modifier.padding(top = 24.dp)) { content() }
        }
    }
}

// ---------------------------------------------------------------------------------------------- Readings

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReadingsScreen() {
    val env = LocalEnv.current
    val p = LocalPalette.current
    val store = env.readings
    var starredOnly by remember { mutableStateOf(false) }
    val shown = if (starredOnly) store.savedReadings.filter { it.starred } else store.savedReadings
    val df = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    var openGraph by remember { mutableStateOf<Reading?>(null) }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((label, on) in listOf("All" to !starredOnly, "⭐ Starred" to starredOnly)) {
                Box(
                    Modifier.console(on).pressable({ Sounds.play(Sounds.CLICK); starredOnly = label != "All" }).padding(horizontal = 16.dp, vertical = 13.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(label, color = if (on) p.readout else p.readoutSecondary, style = Fonts.chakra(13.sp, Fonts.Face.MEDIUM)) }
            }
            Box(Modifier.weight(1f))
            Box(Modifier.size(46.dp).console().pressable({ env.activity.shareText(store.exportText()) }, enabled = store.savedReadings.isNotEmpty()), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Share, "Export all readings as text", tint = p.readoutSecondary, modifier = Modifier.size(20.dp))
            }
        }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
            if (shown.isEmpty()) {
                item {
                    Text(
                        if (starredOnly) "Nothing starred yet."
                        else "Nothing kept yet. A reading is filed on Measure the moment it finishes — tap it there to keep it.",
                        color = p.readoutSecondary, style = Fonts.rajdhani(17.sp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp),
                    )
                }
            }
            items(shown, key = { it.id }) { r ->
                val state = rememberSwipeToDismissBoxState(confirmValueChange = {
                    if (it == SwipeToDismissBoxValue.EndToStart) { store.delete(r.id); true } else false
                })
                SwipeToDismissBox(state, backgroundContent = {}, enableDismissFromStartToEnd = false) {
                    val shape = InstrumentShape()
                    Column(
                        Modifier.fillMaxWidth().background(p.surface, shape).border(1.dp, p.edge, shape).padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // Tap the reading to star it; the band below is its own target, because one tap
                        // cannot both star a reading and open its graph.
                        Column(Modifier.fillMaxWidth().clickable { store.toggleStar(r.id) }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("${r.heartRate.roundToInt()}", color = p.readout, style = Fonts.rajdhani(48.sp))
                                Text("BPM", color = p.readoutSecondary, style = Fonts.chakra(11.sp, Fonts.Face.MEDIUM), modifier = Modifier.padding(bottom = 10.dp).weight(1f))
                                Text(if (r.starred) "⭐" else "☆", color = p.led, fontSize = 20.sp)
                            }
                            Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
                            DataRow(df.format(Date(r.epochMillis)), r.bp.text)
                            // The mark a reading carries instead of being thrown away.
                            r.note?.let { Text(it, color = p.led, style = Fonts.chakra(11.sp, Fonts.Face.MEDIUM)) }
                        }
                        r.trace?.takeIf { it.size > 3 }?.let { trace ->
                            TraceBand(
                                trace,
                                Modifier.pressable({ Sounds.play(Sounds.CLICK); openGraph = r })
                                    .semantics { contentDescription = "Heart rate graph for ${df.format(Date(r.epochMillis))}. Opens full screen." },
                            )
                        }
                    }
                }
            }
        }
    }
    openGraph?.let { ReadingGraphDialog(it) { openGraph = null } }
}

// ---------------------------------------------------------------------------------------------- Help

private class Source(val title: String, val url: String)

private val sources = listOf(
    Source("Blood pressure measurement using only a smartphone (Frey, Menon, Elgendi 2022)", "https://doi.org/10.1038/s41746-022-00629-2"),
    Source("The same study, PDF on ResearchGate", "https://www.researchgate.net/publication/361819163_Blood_pressure_measurement_using_only_a_smartphone/link/62c6fd02d7bd92231f9e50cd/download?_tp=eyJjb250ZXh0Ijp7ImZpcnN0UGFnZSI6InB1YmxpY2F0aW9uIiwicGFnZSI6InB1YmxpY2F0aW9uIn19"),
    Source("ppg-vitals, camera PPG code this work draws on (markolalovic, MIT)", "https://github.com/markolalovic/ppg-vitals"),
    Source("Open-source photoplethysmogram projects on GitHub", "https://github.com/topics/photoplethysmogram"),
    Source("Elgendi et al. 2013, systolic peak detection in PPG (PLoS ONE)", "https://doi.org/10.1371/journal.pone.0076585"),
    Source("Liang et al. 2018, PPG database and filter choice (Scientific Data)", "https://doi.org/10.1038/sdata.2018.20"),
    Source("Finger-camera heart rate accuracy against ECG (PMC5368348)", "https://pmc.ncbi.nlm.nih.gov/articles/PMC5368348/"),
    Source("Calibration-free PPG blood pressure benchmark (PMC10030661)", "https://pmc.ncbi.nlm.nih.gov/articles/PMC10030661/"),
    Source("ISO 81060-2:2018 blood pressure device validation", "https://www.iso.org/standard/73339.html"),
)

@Composable
private fun Card(title: String, paragraphs: List<String>) {
    SectionPanel(title) { paragraphs.forEach { BodyText(it) } }
}

@Composable
private fun StepLine(number: String, title: String, detail: String) {
    val p = LocalPalette.current
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(number, color = p.led, style = Fonts.rajdhani(25.sp))
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = p.readout, style = Fonts.chakra(16.sp, Fonts.Face.REGULAR))
            Text(detail, color = p.readoutSecondary, style = Fonts.rajdhani(16.sp))
        }
    }
}

@Composable
fun HelpScreen() {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        ScreenHeading("A little guidance", "A steady finger. A clearer signal.")
        SectionPanel("Taking a reading") {
            StepLine("01", "Sit comfortably", "Rest your hand and allow a moment to settle.")
            StepLine("02", "Cover the camera", "Use your fingertip, with light pressure. Keep the hand at heart height and don't talk.")
            StepLine("03", "Tap the orb", "Hold still for ${ScanSession.TARGET_SECONDS.toInt()} seconds.")
        }
        Card("How it works", listOf(
            "Each time your heart beats, a little more blood fills the fingertip. Blood absorbs green light, so the fingertip lets through very slightly less green on every beat.",
            "With your finger over the rear camera and the flash on, the app averages the green channel of a square in the middle of every frame. That gives one number per frame. The pulse is only about 1% of that number, so most of the work is recovering it.",
            "The signal is resampled to an even rate and band-passed between 0.5 and 5 Hz forwards and backwards, which removes slow drift and fast noise without shifting the beats in time.",
            "Beats are found with a two-moving-average detector (Elgendi 2013). Intervals outside the range a fingertip can show — 30 to 240 beats a minute — are thrown away, and the rate is the median of the rest, so a missed beat or an extra one cannot swing it.",
        ))
        Card("Quality, and a rhythm that swings", listOf(
            "Each scan is scored on the shape of the pulse (skewness), on how closely every beat matches the average beat, and on the strength of the pulse compared with the light level. A pulse too weak to read is reported as an error instead of a value; anything else is kept.",
            "A rhythm that comes unevenly is noted on the reading — low quality or arrhythmia — rather than thrown away. A camera cannot tell a poor signal from an irregular rhythm, and the app does not try to diagnose either. That note is what it is: a reason to treat the numbers on that reading with more caution.",
        ))
        Card("Blood pressure", listOf(
            "The blood pressure figure is an estimate derived from pulse-shape features. Before calibration it is shown as a range; after calibration, as a single figure for each component. A camera cannot measure blood pressure on its own. Without calibration, version 1 starts from typical values for your age and sex and adjusts them by a small, capped amount. It has not been clinically validated.",
            "For reference, published calibration-free camera methods have a typical error of about 13–16 mmHg systolic and 7–9 mmHg diastolic. That is two to three times worse than the ISO 81060-2 criterion (mean difference within 5 mmHg, standard deviation within 8 mmHg). Finger-camera heart rate is typically within about 2 beats per minute of an ECG at rest.",
            "Do not use these results to make medical decisions. For an accurate blood pressure reading, use a clinically validated blood pressure monitor.",
        ))
        Card(Publisher.calibrationHowTitle, Publisher.calibrationHow.split("\n\n"))
        Card("Getting a good reading", listOf(
            "Sit still for a few minutes first. Rest your fingertip lightly over the lens and flash together, with no pressure. Keep the hand at heart height and don't talk.",
            "Cold hands, pressing hard, movement, bright sunlight, dark skin tones and some devices all reduce accuracy. Optical pulse sensing is less reliable on darker skin, and blood pressure error is larger at high and low pressures and in older people.",
        ))
        Card(Publisher.freeForeverTitle, listOf(Publisher.freeForever))
        Card(Publisher.nothingSentTitle, listOf(Publisher.nothingSent))
        Text("Privacy policy ↗", color = p.led, style = Fonts.rajdhani(17.sp),
            modifier = Modifier.clickable { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Publisher.privacyURL))) })
        SectionPanel("Further reading") {
            for (s in sources) {
                Text("${s.title}  ↗", color = p.led, style = Fonts.rajdhani(17.sp),
                    modifier = Modifier.clickable { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.url))) })
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------- About

@Composable
fun AboutScreen() {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val version = remember { ctx.packageManager.getPackageInfo(ctx.packageName, 0).let { "${it.versionName} (${it.longVersionCode})" } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 12.dp).padding(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SectionPanel(Publisher.displayName) {
            DataRow("Version", version)
            DataRow("Model", Publisher.model.version)
            DataRow("Licence", "GPL-3.0-or-later")
        }
        SectionPanel("Regulatory") { BodyText(Publisher.regulatory) }
        Text("Support ↗", color = p.led, style = Fonts.rajdhani(17.sp),
            modifier = Modifier.clickable { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Publisher.supportURL))) })
        SectionPanel("Third-party notices") {
            BodyText("Chakra Petch, © 2018 The Chakra Petch Project Authors, SIL Open Font License 1.1.")
            BodyText("Rajdhani, by Indian Type Foundry, SIL Open Font License 1.1.")
            BodyText("Jetpack Compose, CameraX and the other AndroidX libraries, © The Android Open Source Project, Apache License 2.0.")
            BodyText("The beat detector reimplements the published algorithm of Elgendi et al. (PLoS ONE 2013). The blood pressure approach follows the survey by Frey, Menon and Elgendi (npj Digital Medicine 2022, CC BY 4.0). No code or figures are copied from either.")
            BodyText("The dial click is from the owner's own dashboard sound set.")
            if (Publisher.sourceURL.isNotEmpty()) {
                Text("Source code ↗", color = p.led, style = Fonts.rajdhani(17.sp),
                    modifier = Modifier.clickable { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Publisher.sourceURL))) })
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------- Donate

@Composable
fun DonateScreen() {
    val p = LocalPalette.current
    val billing = LocalEnv.current.activity.donationBilling
    val titles = listOf("A little support", "A generous contribution", "Something extraordinary")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        ScreenHeading("Support VitaLibre", "Optional. Appreciated. Never required.")
        SectionPanel("Free forever") {
            Text("No ads. No subscription. Nothing to unlock.", color = p.readout, style = Fonts.chakra(22.sp, Fonts.Face.REGULAR))
            BodyText("If this app is useful to you, a one-time donation helps support its development. The app works exactly the same whether you donate or not.")
        }
        Publisher.donations.forEachIndexed { i, d ->
            val enabled = billing.prices.containsKey(d.tier) && billing.state != DonationBilling.State.BUYING && billing.state != DonationBilling.State.LOADING && billing.state != DonationBilling.State.PENDING
            Row(Modifier.fillMaxWidth().console().then(if (enabled) Modifier.clickable { billing.buy(d.tier) } else Modifier.alpha(0.6f)).padding(horizontal = 12.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(titles.getOrElse(i) { "Donation" }, color = p.readout, style = Fonts.chakra(15.sp, Fonts.Face.REGULAR))
                    Text("One-time donation", color = p.readoutSecondary, style = Fonts.rajdhani(15.sp))
                }
                Text(billing.prices[d.tier] ?: "US ${d.fallbackPrice}", color = p.readout, style = Fonts.rajdhani(27.sp))
            }
        }
        if (billing.message.isNotEmpty()) Text(billing.message, color = p.readoutSecondary, style = Fonts.rajdhani(17.sp))
        if (billing.state == DonationBilling.State.UNAVAILABLE || billing.state == DonationBilling.State.FAILED) {
            Text("Retry Google Play", color = p.led, style = Fonts.rajdhani(17.sp), modifier = Modifier.clickable { billing.refresh() })
        }
    }
}

/** A raised surface in the background's own colours: a lit top edge, a dark lower edge and a soft shadow. */
@Composable
fun Modifier.embossed(radius: Int): Modifier {
    val p = LocalPalette.current
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(radius.dp)
    return this
        .softShadow(shape, p)
        .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(
            p.background.mixed(Color.White, if (p.isLight) 0.55f else 0.07f), p.background.mixed(Color.Black, if (p.isLight) 0.07f else 0.35f))), shape)
        .embossBorder(shape, p)
}

private fun Modifier.softShadow(shape: androidx.compose.ui.graphics.Shape, p: Palette): Modifier {
    val c = Color.Black.copy(alpha = if (p.isLight) 0.22f else 0.6f)
    return this.shadow(7.dp, shape, ambientColor = c, spotColor = c)
}

private fun Modifier.embossBorder(shape: androidx.compose.ui.graphics.Shape, p: Palette): Modifier =
    this.border(
        1.5.dp,
        androidx.compose.ui.graphics.Brush.linearGradient(listOf(
            Color.White.copy(alpha = if (p.isLight) 0.95f else 0.22f), Color.Transparent, Color.Black.copy(alpha = if (p.isLight) 0.18f else 0.7f))),
        shape,
    )

// ---------------------------------------------------------------------------------------------- Settings

@Composable
fun SettingsDialog(onDismiss: () -> Unit) {
    val env = LocalEnv.current
    val p = LocalPalette.current
    val prefs = env.prefs
    var showCalibration by remember { mutableStateOf(false) }
    FullDialog(onDismiss) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Box(Modifier.weight(1f)) { ScreenHeading("Settings", "Make yourself comfortable.") }
                Text("Done", color = p.led, style = Fonts.chakra(14.sp, Fonts.Face.MEDIUM), modifier = Modifier.padding(top = 22.dp).clickable { onDismiss() })
            }
            SectionPanel("Appearance") {
                Text("Colour theme", color = p.readout, style = Fonts.chakra(18.sp, Fonts.Face.REGULAR))
                Text("Auto follows your device.", color = p.readoutSecondary, style = Fonts.rajdhani(16.sp))
                ConsoleChoice(ThemeMode.entries.map { it.label to it }, prefs.themeMode) { prefs.changeTheme(it) }
            }
            SectionPanel("About you") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Age", color = p.readout, style = Fonts.chakra(18.sp, Fonts.Face.REGULAR))
                    NumberWheel(listOf(0) + (18..100), prefs.age, labels = listOf("Not set") + (18..100).map { "$it" }) { prefs.changeAge(it) }
                }
                ConsoleChoice(Sex.entries.map { (if (it == Sex.UNSPECIFIED) "Not set" else it.name.lowercase().replaceFirstChar { c -> c.uppercase() }) to it }, prefs.sex) { prefs.changeSex(it) }
            }
            SectionPanel("Calibration") {
                Row(Modifier.fillMaxWidth().console().pressable({ showCalibration = true }).padding(horizontal = 12.dp, vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Typical pressure and cuff readings", color = p.readoutSecondary, style = Fonts.chakra(13.sp, Fonts.Face.MEDIUM))
                    Text("›", color = p.readoutSecondary, style = Fonts.chakra(18.sp))
                }
            }
            SectionPanel("Your data") {
                DataRow("Storage", "On this device")
                DataRow("Account", "Not required")
                BodyText("Readings stay here. Nothing is sent to the developer or third parties. You choose what to share using the share sheet.")
            }
            SectionPanel("Not a medical device") {
                BodyText(Publisher.disclaimerBody)
                Box(Modifier.fillMaxWidth().console().pressable({ prefs.setOnboarded(false); onDismiss() }).padding(14.dp), contentAlignment = Alignment.Center) {
                    Text("Show the intro again", color = p.readoutSecondary, style = Fonts.chakra(13.sp, Fonts.Face.MEDIUM))
                }
            }
        }
    }
    if (showCalibration) CalibrationDialog { showCalibration = false }
}

/** Where the person's typical resting blood pressure is set, and where saved cuff calibrations are counted and reset. */
@Composable
fun CalibrationDialog(onDismiss: () -> Unit) {
    val env = LocalEnv.current
    val p = LocalPalette.current
    val prefs = env.prefs
    FullDialog(onDismiss) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Box(Modifier.weight(1f)) { ScreenHeading("Calibration", "Your typical resting pressure.") }
                Text("Done", color = p.led, style = Fonts.chakra(14.sp, Fonts.Face.MEDIUM), modifier = Modifier.padding(top = 22.dp).clickable { prefs.confirmUsual(); onDismiss() })
            }
            SectionPanel("Typical resting blood pressure") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("SYSTOLIC", color = p.readoutSecondary, style = Fonts.rajdhani(12.sp, true), letterSpacing = 1.5.sp)
                        NumberWheel((70..220).toList(), prefs.usualSystolic) { prefs.changeUsualSystolic(it) }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("DIASTOLIC", color = p.readoutSecondary, style = Fonts.rajdhani(12.sp, true), letterSpacing = 1.5.sp)
                        NumberWheel((40..140).toList(), prefs.usualDiastolic) { prefs.changeUsualDiastolic(it) }
                    }
                }
            }
            SectionPanel("Cuff calibrations") {
                DataRow("Saved", "${prefs.calibration.count}")
                if (prefs.calibration.count > 0 || prefs.usualConfirmed) {
                    Box(Modifier.fillMaxWidth().console().pressable({ prefs.resetAllCalibration() }).padding(14.dp), contentAlignment = Alignment.Center) {
                        Text("Reset", color = p.readoutSecondary, style = Fonts.chakra(13.sp, Fonts.Face.MEDIUM))
                    }
                }
            }
            SectionPanel(Publisher.calibrationHowTitle) { Publisher.calibrationHow.split("\n\n").forEach { BodyText(it) } }
        }
    }
}

// ---------------------------------------------------------------------------------------------- First load

/** Shown on first load, and again from Settings. Three steps, large text, one big button, the disclaimer at the bottom. */
@Composable
fun IntroScreen() {
    val env = LocalEnv.current
    val p = LocalPalette.current
    var showCalibration by remember { mutableStateOf(false) }
    val seconds = ScanSession.TARGET_SECONDS.toInt()
    Box(Modifier.fillMaxSize().background(p.backgroundGradient).clickable(enabled = false) {}) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp)) {
            Text("VITALIBRE", color = p.clock, style = Fonts.chakra(15.sp, Fonts.Face.MEDIUM), letterSpacing = 3.sp, modifier = Modifier.padding(top = 8.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Column(verticalArrangement = Arrangement.spacedBy(26.dp)) {
                    Step(1, buildAnnotatedString {
                        append("Set your typical resting blood pressure by tapping ")
                        withStyle(SpanStyle(color = p.led, textDecoration = TextDecoration.Underline)) { append("Calibration") }
                        append(". This can be updated at any time.")
                    }) { showCalibration = true }
                    Step(2, buildAnnotatedString { append("Press Start and cover the rear camera with your finger. Rest your hand on something so that you stay as still as possible.") })
                    Step(3, buildAnnotatedString { append("The reading will take $seconds seconds once you begin. Results are an estimate.") })
                }
            }
            RingButton("Get started", big = true) { Sounds.play(Sounds.CLICK); env.prefs.setOnboarded(true) }
            Text(Publisher.disclaimer, color = p.readout, style = Fonts.chakra(12.sp, Fonts.Face.MEDIUM), modifier = Modifier.padding(top = 18.dp))
            Text(Publisher.disclaimerBody, color = p.readoutSecondary, style = Fonts.rajdhani(12.sp), modifier = Modifier.padding(top = 4.dp))
        }
    }
    if (showCalibration) CalibrationDialog { showCalibration = false }
}

@Composable
private fun Step(n: Int, text: androidx.compose.ui.text.AnnotatedString, onClick: (() -> Unit)? = null) {
    val p = LocalPalette.current
    Row(Modifier.then(if (onClick != null) Modifier.clickable { onClick() } else Modifier), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(42.dp).embossedCircle(),
            contentAlignment = Alignment.Center,
        ) { Text("$n", color = p.led, style = Fonts.chakra(22.sp, Fonts.Face.MEDIUM)) }
        Text(text, color = p.readout, style = Fonts.chakra(21.sp, Fonts.Face.REGULAR), lineHeight = 28.sp)
    }
}

/** A raised disc: lit top edge, shaded lower edge, soft shadow. */
@Composable
private fun Modifier.embossedCircle(): Modifier {
    val p = LocalPalette.current
    val shape = androidx.compose.foundation.shape.CircleShape
    return this
        .softShadow(shape, p)
        .background(androidx.compose.ui.graphics.Brush.radialGradient(
            listOf(p.panel.mixed(Color.White, if (p.isLight) 0f else 0.10f), p.panel.mixed(Color.Black, if (p.isLight) 0.08f else 0.45f)),
            center = androidx.compose.ui.geometry.Offset(30f, 24f), radius = 90f), shape)
        .embossBorder(shape, p)
}
