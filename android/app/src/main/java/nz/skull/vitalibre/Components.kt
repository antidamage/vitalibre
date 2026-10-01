package nz.skull.vitalibre

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A non-illuminating surface: the panel colour, the border at its theme opacity, and an inset shadow only. */
fun Modifier.panel(radius: Int = 16): Modifier = composed {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(radius.dp)
    this
        .shadow(if (p.isLight) 5.dp else 8.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
        .background(p.panel, shape)
        .drawBehind {
            // Inset shadow: a dark band along the top edge.
            drawRoundRect(
                Brush.verticalGradient(listOf(Color.Black.copy(alpha = if (p.isLight) 0.10f else 0.45f), Color.Transparent), endY = 12.dp.toPx()),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius.dp.toPx()),
            )
        }
        .border(1.dp, p.border.copy(alpha = p.borderOpacity), shape)
}

/** Sinks a little when pressed, like a moulded key. */
fun Modifier.pressable(onClick: () -> Unit, enabled: Boolean = true): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed && enabled) 0.965f else 1f, label = "press")
    this.scale(s).clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Text(text.uppercase(), modifier.fillMaxWidth(), color = p.clock, style = Fonts.chakra(12.sp, Fonts.Face.MEDIUM), letterSpacing = 2.sp)
}

@Composable
fun BodyText(text: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Text(text, modifier.fillMaxWidth(), color = p.readoutSecondary, style = Fonts.rajdhani(17.sp), lineHeight = 22.sp)
}

enum class BarIcon { PULSE, LIST, HELP, INFO, HEART }

@Composable
private fun BarGlyph(icon: BarIcon, color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        val st = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        when (icon) {
            BarIcon.PULSE -> drawPath(Path().apply {
                moveTo(0f, h * 0.55f); lineTo(w * 0.28f, h * 0.55f); lineTo(w * 0.4f, h * 0.15f); lineTo(w * 0.58f, h * 0.9f)
                lineTo(w * 0.7f, h * 0.5f); lineTo(w, h * 0.5f)
            }, color, style = st)
            BarIcon.LIST -> for (i in 0..2) {
                val y = h * (0.2f + 0.3f * i)
                drawCircle(color, 1.6.dp.toPx(), Offset(w * 0.08f, y))
                drawLine(color, Offset(w * 0.28f, y), Offset(w, y), 1.8.dp.toPx(), StrokeCap.Round)
            }
            BarIcon.HELP -> {
                drawCircle(color, w * 0.46f, center, style = st)
                drawPath(Path().apply {
                    moveTo(w * 0.36f, h * 0.38f); cubicTo(w * 0.36f, h * 0.2f, w * 0.64f, h * 0.2f, w * 0.64f, h * 0.38f)
                    cubicTo(w * 0.64f, h * 0.5f, w * 0.5f, h * 0.52f, w * 0.5f, h * 0.64f)
                }, color, style = st)
                drawCircle(color, 1.2.dp.toPx(), Offset(w * 0.5f, h * 0.77f))
            }
            BarIcon.INFO -> {
                drawCircle(color, w * 0.46f, center, style = st)
                drawCircle(color, 1.3.dp.toPx(), Offset(w * 0.5f, h * 0.3f))
                drawLine(color, Offset(w * 0.5f, h * 0.45f), Offset(w * 0.5f, h * 0.74f), 1.8.dp.toPx(), StrokeCap.Round)
            }
            BarIcon.HEART -> drawPath(Path().apply {
                moveTo(w * 0.5f, h * 0.88f)
                cubicTo(w * 0.05f, h * 0.55f, w * 0.1f, h * 0.12f, w * 0.34f, h * 0.12f)
                cubicTo(w * 0.44f, h * 0.12f, w * 0.5f, h * 0.2f, w * 0.5f, h * 0.28f)
                cubicTo(w * 0.5f, h * 0.2f, w * 0.56f, h * 0.12f, w * 0.66f, h * 0.12f)
                cubicTo(w * 0.9f, h * 0.12f, w * 0.95f, h * 0.55f, w * 0.5f, h * 0.88f)
            }, color, style = st)
        }
    }
}

class BarItem<T>(val tab: T, val title: String, val icon: BarIcon)

/** The bottom navigation: console buttons with a glyph, a label and an indicator that lights for the current tab. */
@Composable
fun <T> ConsoleBar(items: List<BarItem<T>>, selection: T, onSelect: (T) -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(p.surface)
            .drawBehind { drawLine(p.edge, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1.dp.toPx()) }
            .navigationBarsPadding()
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        for (item in items) {
            val on = item.tab == selection
            Column(
                Modifier.weight(1f).console(on).pressable({ onSelect(item.tab) }).padding(vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                BarGlyph(item.icon, if (on) p.readout else p.readoutSecondary)
                Text(item.title, color = if (on) p.readout else p.readoutSecondary, style = Fonts.chakra(10.sp, Fonts.Face.MEDIUM), maxLines = 1)
                Box(Modifier.size(13.dp, 2.dp).background(if (on) p.led else p.line, CircleShape))
            }
        }
    }
}

/** A pill button in the ring's colours (Save), or the large confirm button. */
@Composable
fun RingButton(title: String, modifier: Modifier = Modifier, big: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalPalette.current
    val shape = CircleShape
    Box(
        modifier
            .shadow(8.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .background(Brush.horizontalGradient(p.ringStops.map { it.mixed(Color.White, 0.08f) } + p.ringStops[0]), shape)
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.Transparent, Color.Black.copy(alpha = 0.35f))), shape)
            .border(1.dp, p.gridInk.copy(alpha = 0.35f), shape)
            .pressable(onClick, enabled)
            .padding(horizontal = if (big) 0.dp else 28.dp, vertical = if (big) 0.dp else 11.dp)
            .then(if (big) Modifier.height(66.dp).fillMaxWidth() else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(title.uppercase(), color = Color.White.copy(alpha = if (enabled) 0.95f else 0.5f),
            style = Fonts.chakra(if (big) 20.sp else 14.sp, Fonts.Face.SEMIBOLD), letterSpacing = if (big) 3.sp else 2.sp, textAlign = TextAlign.Center)
    }
}

/** A raised console key: surface, soft shadow, lit edge, and an led outline when selected. */
fun Modifier.console(selected: Boolean = false, radius: Int = 9): Modifier = composed {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(radius.dp)
    this
        .shadow(3.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
        .background(p.surface, shape)
        .border(1.dp, p.edge, shape)
        .then(if (selected) Modifier.border(1.dp, p.led.copy(alpha = 0.55f), shape) else Modifier)
}

/** A rectangle with the top right corner cut. */
class InstrumentShape(private val cut: Dp = 12.dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val c = with(density) { cut.toPx() }
        return Outline.Generic(Path().apply {
            moveTo(0f, 0f); lineTo(size.width - c, 0f); lineTo(size.width, c); lineTo(size.width, size.height); lineTo(0f, size.height); close()
        })
    }
}

/** A panel with a titled header, a rule, and the content. */
@Composable
fun SectionPanel(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    val shape = InstrumentShape()
    Column(
        modifier.fillMaxWidth().background(p.surface, shape).border(1.dp, p.edge, shape).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title.uppercase(), color = p.readout, style = Fonts.chakra(12.sp, Fonts.Face.MEDIUM), letterSpacing = 2.sp, modifier = Modifier.weight(1f))
            Box(Modifier.size(16.dp, 2.dp).background(p.led.copy(alpha = 0.7f), CircleShape))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
        content()
    }
}

@Composable
fun ScreenHeading(title: String, subtitle: String) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, color = p.readout, style = Fonts.chakra(28.sp, Fonts.Face.REGULAR))
        Text(subtitle, color = p.readoutSecondary, style = Fonts.rajdhani(17.sp))
    }
}

@Composable
fun DataRow(title: String, value: String) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, color = p.readoutSecondary, style = Fonts.rajdhani(17.sp))
        Text(value, color = p.readout, style = Fonts.rajdhani(17.sp))
    }
}

/** A row of console keys, one of them selected. */
@Composable
fun <T> ConsoleChoice(options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().background(p.background, RoundedCornerShape(12.dp)).padding(7.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        for ((label, value) in options) {
            val on = value == selected
            Column(
                Modifier.weight(1f).console(on).pressable({ Sounds.play(Sounds.CLICK); onSelect(value) }).padding(vertical = 13.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text(label, color = if (on) p.readout else p.readoutSecondary, style = Fonts.chakra(13.sp, Fonts.Face.MEDIUM), maxLines = 1)
                Box(Modifier.size(14.dp, 2.dp).background(if (on) p.led else p.line, CircleShape))
            }
        }
    }
}

/** The platform's own number wheel, themed. `labels` replaces the digits (for a "Not set" entry). */
@Composable
fun NumberWheel(values: List<Int>, value: Int, modifier: Modifier = Modifier, labels: List<String> = values.map { it.toString() }, onChange: (Int) -> Unit) {
    val p = LocalPalette.current
    val latest by rememberUpdatedState(onChange)
    val ink = p.readout.toArgb()
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = modifier.width(110.dp).height(120.dp),
        factory = { ctx ->
            android.widget.NumberPicker(ctx).apply {
                descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                wrapSelectorWheel = false
                minValue = 0
                maxValue = values.size - 1
                displayedValues = labels.toTypedArray()
                setOnValueChangedListener { _, _, new -> Sounds.play(Sounds.CLICK); latest(values[new]) }
            }
        },
        update = { v ->
            v.textColor = ink
            v.textSize = 17f * v.resources.displayMetrics.scaledDensity
            val i = values.indexOf(value).coerceAtLeast(0)
            if (v.value != i) v.value = i
        },
    )
}

@Composable
fun SettingRow(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = p.readout, style = Fonts.chakra(14.sp, Fonts.Face.MEDIUM))
        content()
    }
}
