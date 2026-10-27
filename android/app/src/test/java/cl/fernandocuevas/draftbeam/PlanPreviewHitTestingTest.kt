package cl.fernandocuevas.draftbeam

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanPreviewHitTestingTest {
    @Test
    fun tappingMiddleOfFortyMillimeterWallCanSelectWallInsteadOfEndpoint() {
        val pixelsPerMillimeter = 1f
        val fortyMillimeterWallOnScreen = 40f * pixelsPerMillimeter
        assertTrue(
            shouldSelectShortLineOnTap(
                lineLengthPx = fortyMillimeterWallOnScreen,
                nearestEndpointDistancePx = 20f,
                endpointTargetPx = 34f
            )
        )
    }

    @Test
    fun exactEndpointTapStillSelectsTheEndpoint() {
        assertFalse(
            shouldSelectShortLineOnTap(
                lineLengthPx = 40f,
                nearestEndpointDistancePx = 0f,
                endpointTargetPx = 34f
            )
        )
    }

    @Test
    fun longWallsKeepTheirExistingEndpointTapArea() {
        assertFalse(
            shouldSelectShortLineOnTap(
                lineLengthPx = 100f,
                nearestEndpointDistancePx = 20f,
                endpointTargetPx = 34f
            )
        )
    }
}

class DoorHitTestingTest {
    private val canvasSize = IntSize(800, 800)

    @Test
    fun tappingTheLeafOrFrameSelectsTheDoor() {
        val viewport = PlanViewportState()
        viewport.updateCanvasSize(canvasSize, emptyList())
        val door = PlanDoor(
            hinge = PointMm(1_000.0, 0.0),
            latch = PointMm(1_800.0, 0.0),
            frameWidthMm = 50,
            depthMm = 100
        )
        val doors = listOf(door)

        val pivot = viewport.project(PlanDoors.geometry(door).pivot, canvasSize)
        val frame = viewport.project(PointMm(1_025.0, 0.0), canvasSize)
        val empty = viewport.project(PointMm(2_800.0, 3_000.0), canvasSize)

        assertEquals(0, findDoorAt(doors, pivot, canvasSize, viewport, 18f))
        assertEquals(0, findDoorAt(doors, frame, canvasSize, viewport, 18f))
        assertEquals(null, findDoorAt(doors, empty, canvasSize, viewport, 18f))
    }

    @Test
    fun tappingAnEmptyPlanSelectsNothing() {
        val viewport = PlanViewportState()
        viewport.updateCanvasSize(canvasSize, emptyList())
        assertEquals(null, findDoorAt(emptyList(), Offset(10f, 10f), canvasSize, viewport, 18f))
    }
}
