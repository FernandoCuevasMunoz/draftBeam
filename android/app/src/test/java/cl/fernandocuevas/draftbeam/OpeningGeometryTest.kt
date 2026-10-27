package cl.fernandocuevas.draftbeam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpeningGeometryTest {
    private val wall = WallSegment(PointMm(0.0, 0.0), PointMm(3_000.0, 0.0), 3_000, 0.0, 200)
    private val door = DoorOpening(offsetMm = 1_000, holeLengthMm = 800)

    @Test
    fun doorCutsTheMeasuredOpeningAndEmbedsItsFrame() {
        val withDoor = wall.copy(openings = listOf(door))
        PlanGeometry.validateOpenings(withDoor)
        assertEquals(listOf(0.0 to 1_000.0, 1_800.0 to 3_000.0), PlanGeometry.solidRanges(withDoor))

        val polygons = PlanGeometry.wallFootprints(listOf(withDoor)).single()
        assertEquals(2, polygons.size)
        assertEquals(440_000.0, area(polygons), 0.0001) // 2 200 x 200 of remaining wall

        val geometry = OpeningGeometry.doorGeometry(withDoor, door)
        // The frame sits on both opening ends and spans the whole wall thickness.
        assertEquals(2, geometry.frameLines.size)
        assertEquals(PointMm(1_000.0, 0.0), geometry.frameLines[0].start)
        assertEquals(PointMm(1_000.0, 200.0), geometry.frameLines[0].end)
        assertEquals(PointMm(1_800.0, 0.0), geometry.frameLines[1].start)
        assertEquals(PointMm(1_800.0, 200.0), geometry.frameLines[1].end)

        // The leaf keeps the full measured opening: 800 mm long, 40 mm thick.
        assertEquals(PointMm(1_000.0, 100.0), geometry.hinge)
        assertEquals(800.0, geometry.radiusMm, 0.0001)
        assertEquals(32_000.0, area(listOf(geometry.leaf)), 0.0001)
        assertEquals(960.0, geometry.leaf.minOf { it.x }, 0.0001)
        assertEquals(1_000.0, geometry.leaf.maxOf { it.x }, 0.0001)
        assertEquals(100.0, geometry.leaf.minOf { it.y }, 0.0001)
        assertEquals(900.0, geometry.leaf.maxOf { it.y }, 0.0001)
    }

    @Test
    fun openAngleTurnsTheLeafAroundItsHinge() {
        val quarter = OpeningGeometry.doorGeometry(wall, door)
        assertEquals(PointMm(1_800.0, 100.0), quarter.arc.first())
        assertEquals(PointMm(1_000.0, 900.0), quarter.arc.last())

        val halfOpen = OpeningGeometry.doorGeometry(wall, door.copy(openAngleDegrees = 45.0))
        val root = kotlin.math.sqrt(2.0) / 2.0
        assertEquals(1_000.0 + 800.0 * root, halfOpen.arc.last().x, 0.0001)
        assertEquals(100.0 + 800.0 * root, halfOpen.arc.last().y, 0.0001)

        val closed = OpeningGeometry.doorGeometry(wall, door.copy(openAngleDegrees = 1.0))
        assertEquals(1_799.9, closed.arc.last().x, 0.05)
        assertThrows(IllegalArgumentException::class.java) { door.copy(openAngleDegrees = 0.0) }
        assertThrows(IllegalArgumentException::class.java) { door.copy(openAngleDegrees = 181.0) }
    }

    @Test
    fun fourMirrorsMoveHingeAndSwingSide() {
        val onWall = wall.copy(openings = listOf(door))
        val farStart = OpeningGeometry.doorGeometry(onWall, door)
        val nearStart = OpeningGeometry.doorGeometry(onWall, door.copy(swingTowardNearFace = true))
        val farEnd = OpeningGeometry.doorGeometry(onWall, door.copy(hingeAtStart = false))
        val nearEnd = OpeningGeometry.doorGeometry(
            onWall,
            door.copy(hingeAtStart = false, swingTowardNearFace = true)
        )

        assertEquals(PointMm(1_000.0, 900.0), farStart.arc.last())
        assertEquals(PointMm(1_000.0, -700.0), nearStart.arc.last())
        assertEquals(PointMm(1_800.0, 900.0), farEnd.arc.last())
        assertEquals(PointMm(1_800.0, -700.0), nearEnd.arc.last())
        assertEquals(4, setOf(farStart.arc.last(), nearStart.arc.last(), farEnd.arc.last(), nearEnd.arc.last()).size)
    }

    @Test
    fun dxfArcAnglesAlwaysRunCounterClockwise() {
        val onWall = wall.copy(openings = listOf(door))
        assertEquals(0.0, OpeningGeometry.doorGeometry(onWall, door).arcStartDegrees, 0.0001)
        assertEquals(90.0, OpeningGeometry.doorGeometry(onWall, door).arcEndDegrees, 0.0001)

        val nearSwing = OpeningGeometry.doorGeometry(onWall, door.copy(swingTowardNearFace = true))
        assertEquals(270.0, nearSwing.arcStartDegrees, 0.0001)
        assertEquals(0.0, nearSwing.arcEndDegrees, 0.0001)

        val endHinge = OpeningGeometry.doorGeometry(onWall, door.copy(hingeAtStart = false))
        assertEquals(90.0, endHinge.arcStartDegrees, 0.0001)
        assertEquals(180.0, endHinge.arcEndDegrees, 0.0001)
    }

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
    fun rejectsOverlapsOversizeAndInexpressibleLeaves() {
        val window = WindowOpening(1_000, 900, frameDepthMm = 80)
        assertThrows(IllegalArgumentException::class.java) {
            PlanGeometry.validateOpenings(wall.copy(openings = listOf(door, window)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlanGeometry.validateOpenings(wall.copy(widthMm = 50, openings = listOf(window)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlanGeometry.validateOpenings(wall.copy(openings = listOf(door.copy(offsetMm = 2_500))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlanGeometry.validateOpenings(wall.copy(widthMm = 30, openings = listOf(door)))
        }
        assertThrows(IllegalArgumentException::class.java) { door.copy(holeLengthMm = 0) }
        assertThrows(IllegalArgumentException::class.java) { door.copy(leafThicknessMm = 0) }
    }

    @Test
    fun lengthAndEndpointChangesPreserveAttachedOpeningData() {
        val original = wall.copy(openings = listOf(door))
        val extended = PlanGeometry.updateSegmentLength(listOf(original), 0, 4_000).single()
        assertEquals(listOf(door), extended.openings)
        val moved = PlanGeometry.moveEndpoint(listOf(original), 0, WallEndpoint.END, PointMm(3_500.0, 0.0)).single()
        assertEquals(listOf(door), moved.openings)
        val shortened = PlanGeometry.updateSegmentLength(listOf(original), 0, 1_000).single()
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

    private fun area(polygons: List<List<PointMm>>): Double = polygons.sumOf { polygon ->
        val doubleArea = polygon.indices.sumOf { index ->
            val a = polygon[index]
            val b = polygon[(index + 1) % polygon.size]
            a.x * b.y - a.y * b.x
        }
        kotlin.math.abs(doubleArea / 2.0)
    }
}
