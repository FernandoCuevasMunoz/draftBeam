package cl.fernandocuevas.draftbeam

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class PlanPreviewNotesTest {
    @Test
    fun noteFontScalesWithViewportWithoutScreenSpaceMinimum() {
        val note = PlanNote("Room", PointMm(0.0, 0.0), heightMm = 100.0)

        val closeFontSize = noteFontSize(note, pixelsPerMillimeter = 0.2f)
        val farFontSize = noteFontSize(note, pixelsPerMillimeter = 0.02f)

        assertEquals(20f, closeFontSize, 0f)
        assertEquals(2f, farFontSize, 0f)
        assertTrue(farFontSize < 14f)
    }

    @Test
    fun cyanWallVerticesAreHiddenUntilAWallIsSelected() {
        val walls = listOf(
            WallSegment(PointMm(0.0, 0.0), PointMm(1_000.0, 0.0), 1_000, 0.0, widthMm = 100),
            WallSegment(PointMm(2_000.0, 0.0), PointMm(2_000.0, 1_000.0), 1_000, 90.0, widthMm = 200)
        )

        assertTrue(visibleWallInteractionPoints(walls, selectedSegmentIndex = null).isEmpty())
        assertEquals(4, visibleWallInteractionPoints(walls, selectedSegmentIndex = 1).size)
        assertEquals(
            PlanGeometry.wallInteractionPoints(listOf(walls[1])),
            visibleWallInteractionPoints(walls, selectedSegmentIndex = 1)
        )
    }

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
