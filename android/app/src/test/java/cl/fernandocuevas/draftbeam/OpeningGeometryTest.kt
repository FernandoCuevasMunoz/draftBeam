package cl.fernandocuevas.draftbeam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpeningGeometryTest {
    private val wall = WallSegment(PointMm(0.0, 0.0), PointMm(3_000.0, 0.0), 3_000, 0.0, 200)

    @Test fun doorHasRealEightHundredMillimeterGapAndMatchingLeaf() {
        val door = WallOpening(OpeningKind.DOOR, 1_100, 800)
        val withDoor = wall.copy(openings = listOf(door))
        PlanGeometry.validateOpenings(withDoor)
        assertEquals(listOf(0.0 to 1_100.0, 1_900.0 to 3_000.0), PlanGeometry.solidRanges(withDoor))
        val polygons = PlanGeometry.wallFootprints(listOf(withDoor)).single()
        assertEquals(2, polygons.size)
        val area = polygons.sumOf { polygon ->
            val doubleArea = polygon.indices.sumOf { index ->
                val a = polygon[index]; val b = polygon[(index + 1) % polygon.size]
                a.x * b.y - a.y * b.x
            }
            kotlin.math.abs(doubleArea / 2.0)
        }
        assertEquals(440_000.0, area, 0.0001)
        val leaf = OpeningGeometry.doorLeaf(withDoor, door)
        assertEquals(800.0, kotlin.math.hypot(leaf.end.x - leaf.start.x, leaf.end.y - leaf.start.y), 0.0001)
        assertEquals(PointMm(1_100.0, 100.0), leaf.start)
        assertEquals(1_100.0, leaf.end.x, 0.0001) // 90° open, not drawn closed over the gap
    }

    @Test fun windowStaysClosedAndFrameCanSitAtEitherFace() {
        val window = WallOpening(OpeningKind.WINDOW, 500, 1_200, OpeningAlignment.FAR_FACE, 60)
        val lines = OpeningGeometry.windowLines(wall, window)
        assertEquals(5, lines.size)
        assertEquals(140.0, lines.first().start.y, 0.0001)
        assertEquals(200.0, lines[2].start.y, 0.0001)
        assertEquals(1_200.0, lines.first().end.x - lines.first().start.x, 0.0001)
    }

    @Test fun rejectsOverlapsOversizeAndNarrowWallEdits() {
        val door = WallOpening(OpeningKind.DOOR, 500, 800)
        val window = WallOpening(OpeningKind.WINDOW, 1_100, 900, frameWidthMm = 80)
        assertThrows(IllegalArgumentException::class.java) {
            PlanGeometry.validateOpenings(wall.copy(openings = listOf(door, window)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlanGeometry.validateOpenings(wall.copy(widthMm = 50, openings = listOf(window)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlanGeometry.validateOpenings(wall.copy(openings = listOf(door.copy(offsetMm = 2_500))))
        }
    }

    @Test fun lengthAndEndpointChangesPreserveAttachedOpeningData() {
        val door = WallOpening(OpeningKind.DOOR, 500, 800)
        val original = wall.copy(openings = listOf(door))
        val extended = PlanGeometry.updateSegmentLength(listOf(original), 0, 4_000).single()
        assertEquals(listOf(door), extended.openings)
        val moved = PlanGeometry.moveEndpoint(listOf(original), 0, WallEndpoint.END, PointMm(3_500.0, 0.0)).single()
        assertEquals(listOf(door), moved.openings)
        val shortened = PlanGeometry.updateSegmentLength(listOf(original), 0, 1_000).single()
        assertThrows(IllegalArgumentException::class.java) { PlanGeometry.validateOpenings(shortened) }
    }

    @Test fun diagonalWallCoordinatesFollowLocalAxes() {
        val diagonal = wall.copy(end = PointMm(3_000.0, 3_000.0), angleDegrees = 45.0)
        val point = PlanGeometry.wallPoint(diagonal, 1_000.0, 100.0)
        assertTrue(point.y > point.x)
        assertEquals(900.0 / kotlin.math.sqrt(2.0), point.x, 0.0001)
        assertEquals(1_100.0 / kotlin.math.sqrt(2.0), point.y, 0.0001)
    }
}
