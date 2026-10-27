package cl.fernandocuevas.draftbeam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpeningGeometryTest {
    private val wall = WallSegment(PointMm(0.0, 0.0), PointMm(3_000.0, 0.0), 3_000, 0.0, 200)

    @Test
    fun windowStaysClosedAndFrameCanSitAtEitherFace() {
        val window = WindowOpening(500, 1_200, OpeningAlignment.FAR_FACE, 60)
        val lines = OpeningGeometry.windowLines(wall, window)
        assertEquals(5, lines.size)
        assertEquals(140.0, lines.first().start.y, 0.0001)
        assertEquals(200.0, lines[2].start.y, 0.0001)
        assertEquals(1_200.0, lines.first().end.x - lines.first().start.x, 0.0001)
    }

    @Test
    fun rejectsOverlappingAndOversizedWindows() {
        val first = WindowOpening(500, 900, frameDepthMm = 80)
        val second = WindowOpening(1_000, 900, frameDepthMm = 80)
        assertThrows(IllegalArgumentException::class.java) {
            PlanGeometry.validateOpenings(wall.copy(openings = listOf(first, second)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlanGeometry.validateOpenings(wall.copy(openings = listOf(WindowOpening(2_500, 900, frameDepthMm = 80))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlanGeometry.validateOpenings(wall.copy(widthMm = 50, openings = listOf(WindowOpening(500, 900, frameDepthMm = 80))))
        }
        assertThrows(IllegalArgumentException::class.java) { WindowOpening(0, 0, frameDepthMm = 80) }
    }

    @Test
    fun lengthAndEndpointChangesPreserveAttachedOpeningData() {
        val window = WindowOpening(500, 900, frameDepthMm = 80)
        val original = wall.copy(openings = listOf(window))
        val extended = PlanGeometry.updateSegmentLength(listOf(original), 0, 4_000).single()
        assertEquals(listOf(window), extended.openings)
        val moved = PlanGeometry.moveEndpoint(listOf(original), 0, WallEndpoint.END, PointMm(3_500.0, 0.0)).single()
        assertEquals(listOf(window), moved.openings)
        val shortened = PlanGeometry.updateSegmentLength(listOf(original), 0, 500).single()
        assertThrows(IllegalArgumentException::class.java) { PlanGeometry.validateOpenings(shortened) }
    }

    @Test
    fun diagonalWallCoordinatesFollowLocalAxes() {
        val diagonal = wall.copy(end = PointMm(3_000.0, 3_000.0), angleDegrees = 45.0)
        val point = PlanGeometry.wallPoint(diagonal, 1_000.0, 100.0)
        assertTrue(point.y > point.x)
        assertEquals(900.0 / kotlin.math.sqrt(2.0), point.x, 0.0001)
        assertEquals(1_100.0 / kotlin.math.sqrt(2.0), point.y, 0.0001)
    }
}
