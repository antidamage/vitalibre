package nz.skull.vitalibre

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

const val REST_SECONDS = 300

/**
 * The rest guide's text and the five-minute timer, as a panel. Used in the "Before you measure" dialog and in
 * Help. The timer never blocks a scan: it is there so the rest is easy to do, not a gate.
 */
@Composable
fun ReadingGuidePanel(modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    var endsAtMs by remember { mutableLongStateOf(0L) }
    var remaining by remember { mutableStateOf(REST_SECONDS) }
    var finished by remember { mutableStateOf(false) }
    LaunchedEffect(endsAtMs) {
        if (endsAtMs == 0L) return@LaunchedEffect
        while (true) {
            remaining = maxOf(0, Math.ceil((endsAtMs - android.os.SystemClock.elapsedRealtime()) / 1000.0).toInt())
            if (remaining == 0) { endsAtMs = 0L; finished = true; Sounds.play(Sounds.DONE); Haptics.end(); break }
            delay(250)
        }
    }
    Column(modifier.fillMaxWidth().panel().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionTitle(Publisher.readingGuideTitle)
        for (para in Publisher.readingGuide.split("\n\n")) {
            // The first sentence leads: "Rest first. Sit quietly ...".
            val i = para.indexOf(". ")
            val lead = if (i >= 0) para.substring(0, i + 1) + " " else ""
            val rest = if (i >= 0) para.substring(i + 2) else para
            Text(buildAnnotatedString {
                withStyle(SpanStyle(color = p.readout)) { append(lead) }
                withStyle(SpanStyle(color = p.readoutSecondary)) { append(rest) }
            }, style = Fonts.rajdhani(17.sp), lineHeight = 22.sp)
        }
        SectionTitle(Publisher.restTimerTitle, Modifier.padding(top = 6.dp))
        if (finished) BodyText(Publisher.restTimerDone)
        else if (endsAtMs != 0L) Text("%d:%02d".format(remaining / 60, remaining % 60), color = p.readout, style = Fonts.rajdhani(40.sp))
        if (endsAtMs == 0L) {
            RingButton(if (finished) "Rest again" else "Start the timer") {
                Sounds.play(Sounds.CLICK)
                finished = false; remaining = REST_SECONDS
                endsAtMs = android.os.SystemClock.elapsedRealtime() + REST_SECONDS * 1000L
            }
        } else {
            Text("Stop the timer", color = p.led, style = Fonts.chakra(13.sp, Fonts.Face.MEDIUM),
                modifier = Modifier.clickable { endsAtMs = 0L; remaining = REST_SECONDS })
        }
    }
}

/** "Before you measure": opened before the first scan, and from Measure afterwards. */
@Composable
fun ReadingGuideDialog(onDismiss: () -> Unit) {
    val p = LocalPalette.current
    FullDialog(onDismiss) {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(Publisher.readingGuideTitle.uppercase(), color = p.clock, style = Fonts.chakra(15.sp, Fonts.Face.MEDIUM), letterSpacing = 3.sp)
                Text("Done", color = p.led, style = Fonts.chakra(14.sp, Fonts.Face.MEDIUM), modifier = Modifier.clickable { onDismiss() })
            }
            Column(Modifier.verticalScroll(rememberScrollState())) { ReadingGuidePanel() }
        }
    }
}
