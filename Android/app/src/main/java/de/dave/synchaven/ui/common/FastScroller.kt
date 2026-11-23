package de.dave.synchaven.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.roundToInt

private val RowHeight = 18.dp
private val MinRowHeight = 12.dp
private val BarWidth = 28.dp
private val BubbleSize = 56.dp

/**
 * Buchstabenleiste am rechten Rand einer gruppierten Liste: Tippen oder Ziehen springt zur
 * Gruppe, beim Ziehen zeigt eine Blase den aktuellen Buchstaben.
 *
 * @param current Index der gerade oben sichtbaren Gruppe (wird hervorgehoben)
 * @param onSelect wird mit dem Index der gewählten Gruppe aufgerufen
 */
@Composable
fun LetterFastScroller(
    letters: List<String>,
    current: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val select by rememberUpdatedState(onSelect)

    var active by remember { mutableStateOf<Int?>(null) }
    var touchY by remember { mutableFloatStateOf(0f) }
    var barTop by remember { mutableFloatStateOf(0f) }
    var barHeight by remember { mutableIntStateOf(0) }

    BoxWithConstraints(modifier.fillMaxHeight()) {
        // Passen nicht alle Buchstaben untereinander, wird nur jeder n-te angezeigt;
        // die Auswahl per Finger verteilt sich trotzdem auf alle Buchstaben.
        val step = if (letters.isEmpty()) 1 else ceil(letters.size * MinRowHeight.value / maxHeight.value).toInt().coerceAtLeast(1)
        val shown = letters.indices.filter { it % step == 0 }
        val rowHeight = (maxHeight / shown.size.coerceAtLeast(1)).coerceAtMost(RowHeight)

        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .width(BarWidth)
                .onPlaced {
                    barTop = it.positionInParent().y
                    barHeight = it.size.height
                }
                .pointerInput(letters) {
                    fun pick(y: Float) {
                        touchY = y.coerceIn(0f, barHeight.toFloat())
                        if (letters.isEmpty() || barHeight == 0) return
                        val i = (y / barHeight * letters.size).toInt().coerceIn(0, letters.lastIndex)
                        if (i != active) {
                            active = i
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            select(i)
                        }
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        pick(down.position.y)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            pick(change.position.y)
                        }
                        active = null
                    }
                }
                .background(
                    if (active != null) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent,
                    RoundedCornerShape(50),
                )
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            for (i in shown) {
                val highlighted = (active ?: current) in i until i + step
                Box(Modifier.height(rowHeight), contentAlignment = Alignment.Center) {
                    Text(
                        letters[i],
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Medium,
                        color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        active?.let { i ->
            val bubblePx = with(density) { BubbleSize.toPx() }
            val y = (barTop + touchY - bubblePx / 2).coerceIn(0f, with(density) { maxHeight.toPx() } - bubblePx)
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(-with(density) { (BarWidth + 8.dp).roundToPx() }, y.roundToInt()) }
                    .size(BubbleSize)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    letters[i],
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}
