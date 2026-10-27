package cl.fernandocuevas.draftbeam

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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

/** Values a placed door still allows editing: angle, hinge mirror, frame and leaf thickness. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoorEditSheet(
    door: PlanDoor,
    onDismiss: () -> Unit,
    onSave: (PlanDoor) -> String?,
    onDelete: () -> Unit
) {
    var draft by remember(door) { mutableStateOf(door) }
    var frameWidthText by remember(door) { mutableStateOf(door.frameWidthMm.toString()) }
    var thicknessText by remember(door) { mutableStateOf(door.leafThicknessMm.toString()) }
    var error by remember(door) { mutableStateOf<String?>(null) }

    fun candidate(): PlanDoor? {
        val frame = frameWidthText.toLongOrNull()?.takeIf { it in 1L..500L } ?: return null
        val thickness = thicknessText.toLongOrNull()?.takeIf { it in 1L..draft.depthMm } ?: return null
        return draft.copy(frameWidthMm = frame, leafThicknessMm = thickness)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = DraftBeamColors.Surface,
        contentColor = DraftBeamColors.TextPrimary,
        dragHandle = { BottomSheetDefaults.DragHandle(color = DraftBeamColors.TextMuted) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Editar puerta", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "Vano ${door.gapLengthMm.toLong()} mm · hoja ${door.leafLengthMm.toLong()} mm · " +
                    "profundidad ${door.depthMm} mm",
                style = MaterialTheme.typography.bodySmall,
                color = DraftBeamColors.TextSecondary
            )

            Text("Ángulo de la hoja", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.0, 90.0, 180.0, 270.0, 360.0).forEach { angle ->
                    Button(
                        onClick = { draft = draft.copy(openAngleDegrees = angle) },
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = if (draft.openAngleDegrees == angle) null else BorderStroke(1.dp, DraftBeamColors.Border),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (draft.openAngleDegrees == angle) {
                                DraftBeamColors.Primary
                            } else {
                                DraftBeamColors.SurfaceElevated
                            },
                            contentColor = DraftBeamColors.TextPrimary
                        ),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp)
                    ) { Text("${angle.toInt()}°", style = MaterialTheme.typography.labelMedium) }
                }
            }

            OutlinedButton(
                onClick = {
                    draft = PlanDoors.mirrored(draft)
                    error = null
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, DraftBeamColors.Border)
            ) { Text("Invertir hoja (espejo)", style = MaterialTheme.typography.labelMedium) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = frameWidthText,
                    onValueChange = {
                        frameWidthText = it.filter(Char::isDigit)
                        error = null
                    },
                    label = { Text("Ancho de marco (mm)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = thicknessText,
                    onValueChange = {
                        thicknessText = it.filter(Char::isDigit)
                        error = null
                    },
                    label = { Text("Espesor de hoja (mm)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, DraftBeamColors.Error.copy(alpha = 0.35f))
                ) { Text("Eliminar", maxLines = 1, style = MaterialTheme.typography.labelMedium) }
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, DraftBeamColors.Border)
                ) { Text("Cancelar", maxLines = 1, style = MaterialTheme.typography.labelMedium) }
                Button(
                    onClick = {
                        val updated = candidate()
                        if (updated == null) {
                            error = "Revisá el ancho de marco y el espesor de la hoja."
                        } else {
                            error = runCatching { onSave(updated) }
                                .getOrElse { it.message ?: "No se pudo guardar la puerta." }
                        }
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DraftBeamColors.Primary,
                        contentColor = DraftBeamColors.TextPrimary
                    )
                ) { Text("Guardar", maxLines = 1, style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}
