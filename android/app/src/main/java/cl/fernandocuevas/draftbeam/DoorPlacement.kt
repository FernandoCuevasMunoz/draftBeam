package cl.fernandocuevas.draftbeam

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import cl.fernandocuevas.draftbeam.ui.theme.DraftBeamColors

/** Steps of the door-in-a-gap flow: two wall ends, then side and angle. */
enum class DoorPhase { GAP_FIRST, GAP_SECOND, SWING }

/** Measurement state while the user marks the gap of the door. */
data class DoorFlow(
    val phase: DoorPhase,
    val frameWidthMm: Long,
    val hinge: PointMm? = null,
    val door: PlanDoor? = null,
    val message: String? = null,
    val messageIsError: Boolean = false
)

/** What the canvas has to show while the flow is running: the anchor and the preview. */
data class DoorPlacementOverlay(
    val anchor: PointMm? = null,
    val preview: PlanDoor? = null
)

/** Frame width for the new door, the only value asked before measuring the gap. */
@Composable
internal fun DoorFrameWidthDialog(
    frameWidthText: String,
    onFrameWidthChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onAccept: (Long) -> Unit
) {
    val frameWidth = frameWidthText.toLongOrNull()?.takeIf { it in 1L..500L }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Puerta en un vano existente") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "El vano se mide entre los extremos de dos muros ya dibujados. La profundidad del " +
                        "marco toma el ancho del muro y después elegís hacia dónde abre.",
                    style = MaterialTheme.typography.bodySmall,
                    color = DraftBeamColors.TextSecondary
                )
                OutlinedTextField(
                    value = frameWidthText,
                    onValueChange = { onFrameWidthChange(it.filter(Char::isDigit)) },
                    label = { Text("Ancho de marco por lado (mm)") },
                    supportingText = { Text("La hoja mide el vano menos el marco de los dos lados.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(enabled = frameWidth != null, onClick = { frameWidth?.let(onAccept) }) {
                Text("Medir vano")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}
