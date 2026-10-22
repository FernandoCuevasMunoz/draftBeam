package cl.fernandocuevas.draftbeam

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlanPreviewNotesTest {
    @Test
    fun noteBodyIsSelectableAndOutsideTapDoesNotSelectIt() {
        val size = IntSize(500, 400)
        val viewport = PlanViewportState().apply { updateCanvasSize(size, emptyList()) }
        val note = PlanNote("Kitchen", PointMm(0.0, 0.0))
        val anchor = viewport.project(note.position, size)

        assertEquals(0, findNoteAt(listOf(note), anchor + Offset(8f, 0f), size, viewport, density = 1f))
        assertNull(findNoteAt(listOf(note), anchor + Offset(100f, 100f), size, viewport, density = 1f))
    }

    @Test
    fun rotatedNoteRemainsSelectableAlongItsDisplayedText() {
        val size = IntSize(500, 400)
        val viewport = PlanViewportState().apply { updateCanvasSize(size, emptyList()) }
        val note = PlanNote("Kitchen", PointMm(0.0, 0.0), rotationDegrees = 90.0)
        val anchor = viewport.project(note.position, size)

        assertEquals(0, findNoteAt(listOf(note), anchor + Offset(0f, -20f), size, viewport, density = 1f))
    }
}
