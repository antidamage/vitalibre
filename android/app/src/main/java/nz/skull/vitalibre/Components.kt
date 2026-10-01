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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    Text(text, modifier.fillMaxWidth(), color = p.readout.copy(alpha = 0.92f), style = Fonts.rajdhani(16.sp), lineHeight = 21.sp)
}

/** A vertical groove cut into the bar: a dark line with a light line beside it, fading at the ends. */
@Composable
fun SunkenDivider() {
    val p = LocalPalette.current
    Canvas(Modifier.width(2.dp).height(40.dp)) {
        val fade = listOf(Color.Transparent, Color.Black, Color.Black, Color.Transparent)
        drawLine(Brush.verticalGradient(fade.map { it.copy(alpha = if (p.isLight) 0.28f * it.alpha else 0.85f * it.alpha) }), Offset(0.5.dp.toPx(), 0f), Offset(0.5.dp.toPx(), size.height), 1.dp.toPx())
        drawLine(Brush.verticalGradient(fade.map { Color.White.copy(alpha = (if (p.isLight) 0.95f else 0.14f) * it.alpha) }), Offset(1.5.dp.toPx(), 0f), Offset(1.5.dp.toPx(), size.height), 1.dp.toPx())
    }
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

/** One label in the bottom bar: no plate of its own, the bar is the surface. */
@Composable
private fun ConsoleButton(title: String, icon: BarIcon, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    val color = if (active) p.led else p.clock
    Column(
        modifier.pressable(onClick).height(58.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.drawBehind {
            if (active) drawCircle(p.led.copy(alpha = 0.25f), radius = 16.dp.toPx())
        }) { BarGlyph(icon, color) }
        Text(title.uppercase(), color = color, style = Fonts.chakra(10.sp, Fonts.Face.MEDIUM), letterSpacing = 1.2.sp, maxLines = 1,
            modifier = Modifier.padding(top = 4.dp))
    }
}

class BarItem<T>(val tab: T, val title: String, val icon: BarIcon)

/**
 * A single bar across the whole width, square at the sides and bottom. Its top edge is a rounded bullnose,
 * shown by lighting: a lit crest, then falling away into shade. Buttons are divided by sunken grooves.
 */
@Composable
fun <T> ConsoleBar(items: List<BarItem<T>>, selection: T, onSelect: (T) -> Unit) {
    val p = LocalPalette.current
    val base = p.background.mixed(Color.Black, if (p.isLight) 0.04f else 0.28f)
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                // Shadow cast upward onto the content.
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = if (p.isLight) 0.18f else 0.6f)), startY = -14.dp.toPx(), endY = 0f), topLeft = Offset(0f, -14.dp.toPx()), size = androidx.compose.ui.geometry.Size(size.width, 14.dp.toPx()))
            }
            .background(Brush.verticalGradient(listOf(base.mixed(Color.White, if (p.isLight) 0.5f else 0.07f), base, base.mixed(Color.Black, if (p.isLight) 0.05f else 0.3f))))
            .drawBehind {
                // The rounded top: a bright crest line, then a soft band curving away.
                drawLine(Color.White.copy(alpha = if (p.isLight) 1f else 0.22f), Offset(0f, 0.5f), Offset(size.width, 0.5f), 1.dp.toPx())
                drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = if (p.isLight) 0.6f else 0.10f), Color.Transparent), endY = 14.dp.toPx()),
                    topLeft = Offset(0f, 1.dp.toPx()), size = androidx.compose.ui.geometry.Size(size.width, 14.dp.toPx()))
            }
            .navigationBarsPadding()
            .padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { i, item ->
            if (i > 0) SunkenDivider()
            ConsoleButton(item.title, item.icon, item.tab == selection, Modifier.weight(1f)) { onSelect(item.tab) }
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

/** A drop-down menu in place of a native spinner, themed. */
@Composable
fun <T> MenuPicker(label: String, items: List<Pair<String, T>>, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Text(
            "$label  ▾", color = p.led, style = Fonts.chakra(14.sp, Fonts.Face.MEDIUM),
            modifier = Modifier.clickable { open = true }.padding(horizontal = 6.dp, vertical = 8.dp),
        )
        DropdownMenu(open, { open = false }, containerColor = p.panel) {
            for ((text, value) in items) {
                DropdownMenuItem(
                    text = { Text(text, color = p.readout, style = Fonts.chakra(14.sp, Fonts.Face.MEDIUM)) },
                    onClick = { open = false; onSelect(value) },
                )
            }
        }
    }
}

@Composable
fun SettingRow(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = p.readout, style = Fonts.chakra(14.sp, Fonts.Face.MEDIUM))
        content()
    }
}
