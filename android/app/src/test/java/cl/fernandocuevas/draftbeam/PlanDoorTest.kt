package cl.fernandocuevas.draftbeam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Door placed in a gap that already exists: 1000 mm wall, 800 mm gap, 1000 mm wall,
 * 100 mm thick. The gap is the opening, the frame takes 50 mm per side, the depth is
 * the wall thickness and the leaf turns counter-clockwise in 90° steps.
 */
class PlanDoorTest {
    private val hinge = PointMm(1_000.0, 0.0)
    private val latch = PointMm(1_800.0, 0.0)
    private val door = PlanDoor(
        hinge = hinge,
        latch = latch,
        frameWidthMm = 50,
        depthMm = 100
    )

    @Test
    fun theGapIsTheOpeningAndTheFrameShortensOnlyTheLeaf() {
        assertEquals(800.0, door.gapLengthMm, 0.0001)
        assertEquals(700.0, door.leafLengthMm, 0.0001)

        val geometry = PlanDoors.geometry(door)
        assertEquals(2, geometry.frames.size)
        // Frames sit inside the gap, one at each wall end, as deep as the wall.
        assertEquals(1_000.0, geometry.frames[0].minOf { it.x }, 0.0001)
        assertEquals(1_050.0, geometry.frames[0].maxOf { it.x }, 0.0001)
        assertEquals(-50.0, geometry.frames[0].minOf { it.y }, 0.0001)
        assertEquals(50.0, geometry.frames[0].maxOf { it.y }, 0.0001)
        assertEquals(1_750.0, geometry.frames[1].minOf { it.x }, 0.0001)
        assertEquals(1_800.0, geometry.frames[1].maxOf { it.x }, 0.0001)

        assertEquals(PointMm(1_050.0, 0.0), geometry.pivot)
        assertEquals(700.0, geometry.radiusMm, 0.0001)
        assertEquals(PointMm(1_750.0, 0.0), geometry.arc.first())
        assertEquals(PointMm(1_050.0, 700.0), geometry.arc.last())
        assertEquals(28_000.0, area(listOf(geometry.leaf)), 0.0001) // 700 x 40
        assertEquals(OpeningLine(hinge, latch), geometry.opening)
    }

    @Test
    fun theWallEndsOfThePlanBecomeTheMeasuredGap() {
        val first = WallSegment(PointMm(0.0, 0.0), PointMm(1_000.0, 0.0), 1_000, 0.0, 100)
        val second = WallSegment(PointMm(1_800.0, 0.0), PointMm(2_800.0, 0.0), 1_000, 0.0, 100)
        val walls = listOf(first, second)

        val measured = PlanDoors.fromGap(first.end, second.start, frameWidthMm = 50, depthMm = 100)
        assertTrue(measured is DoorGapResult.Ready)
        val placed = (measured as DoorGapResult.Ready).door
        assertEquals(800.0, placed.gapLengthMm, 0.0001)
        assertEquals(700.0, placed.leafLengthMm, 0.0001)

        // Walls are reported, never modified: the plan keeps 1000 + gap + 1000.
        assertEquals(listOf(1_000L, 1_000L), walls.map { it.lengthMm })
        assertEquals(listOf(0.0, 1_800.0), walls.map { it.start.x })
        assertEquals(listOf(1_000.0, 2_800.0), walls.map { it.end.x })
        assertEquals(listOf(emptyList<WallOpening>(), emptyList()), walls.map { it.openings })
    }

    @Test
    fun theDepthIsTheWallThickness() {
        val first = WallSegment(PointMm(0.0, 0.0), PointMm(1_000.0, 0.0), 1_000, 0.0, 100)
        val second = WallSegment(PointMm(1_800.0, 0.0), PointMm(2_800.0, 0.0), 1_000, 0.0, 100)
        assertEquals(100L, PlanDoors.depthAt(listOf(first, second), first.end, second.start))

        // Different walls keep one continuous depth: the thickest one.
        val thick = second.copy(widthMm = 160)
        assertEquals(160L, PlanDoors.depthAt(listOf(first, thick), first.end, thick.start))

        // Lines without width fall back to the default depth.
        val line = first.copy(widthMm = 0)
        assertEquals(
            PlanDoor.DEFAULT_DEPTH_MM,
            PlanDoors.depthAt(listOf(line), line.end, PointMm(1_800.0, 0.0))
        )
    }

    @Test
    fun thePointerSnapsTheLeafToNinetyDegreeSteps() {
        assertEquals(90.0, PlanDoors.toward(door, PointMm(1_050.0, 500.0)).openAngleDegrees, 0.0001)
        assertEquals(270.0, PlanDoors.toward(door, PointMm(1_050.0, -500.0)).openAngleDegrees, 0.0001)
        assertEquals(180.0, PlanDoors.toward(door, PointMm(350.0, 0.0)).openAngleDegrees, 0.0001)
        // 360 and 0 are the same direction: the closed leaf over the gap.
        assertEquals(0.0, PlanDoors.toward(door, PointMm(1_750.0, 20.0)).openAngleDegrees, 0.0001)
        assertEquals(0.0, PlanDoors.toward(door, PointMm(1_750.0, -20.0)).openAngleDegrees, 0.0001)
        assertEquals(360.0, door.copy(openAngleDegrees = 360.0).openAngleDegrees, 0.0001)
        // Snapping keeps a single step: no free angles any more.
        assertEquals(90.0, PlanDoors.toward(door, PointMm(1_750.0, 700.0)).openAngleDegrees, 0.0001)
    }

    @Test
    fun geometryFollowsTheChosenAngle() {
        assertPoint(1_050.0, 700.0, door.copy(openAngleDegrees = 90.0))
        assertPoint(350.0, 0.0, door.copy(openAngleDegrees = 180.0))
        assertPoint(1_050.0, -700.0, door.copy(openAngleDegrees = 270.0))
        assertPoint(1_750.0, 0.0, door.copy(openAngleDegrees = 0.0))
    }

    @Test
    fun dxfArcAnglesFollowTheCounterClockwiseRotation() {
        val quarter = PlanDoors.geometry(door)
        assertEquals(0.0, quarter.arcStartDegrees, 0.0001)
        assertEquals(90.0, quarter.arcEndDegrees, 0.0001)
        val half = PlanDoors.geometry(door.copy(openAngleDegrees = 180.0))
        assertEquals(0.0, half.arcStartDegrees, 0.0001)
        assertEquals(180.0, half.arcEndDegrees, 0.0001)
        val closed = PlanDoors.geometry(door.copy(openAngleDegrees = 0.0))
        assertEquals(closed.arcStartDegrees, closed.arcEndDegrees, 0.0001)
        // 270° opens down: the arc takes the short way, so it starts at 270 and ends at 0.
        val down = PlanDoors.geometry(door.copy(openAngleDegrees = 270.0))
        assertEquals(270.0, down.arcStartDegrees, 0.0001)
        assertEquals(0.0, down.arcEndDegrees, 0.0001)
        val reversed = PlanDoors.geometry(door.copy(hinge = latch, latch = hinge))
        assertEquals(180.0, reversed.arcStartDegrees, 0.0001)
        assertEquals(270.0, reversed.arcEndDegrees, 0.0001)
    }

    @Test
    fun theMirroredDoorDrawsAQuarterCircleAndNotThreeQuarters() {
        val mirrored = PlanDoors.mirrored(door.copy(openAngleDegrees = 90.0))
        val geometry = PlanDoors.geometry(mirrored)

        // The arc is a quarter circle: 90° of sweep, never crossing the wall line.
        val span = ((geometry.arcEndDegrees - geometry.arcStartDegrees) % 360.0 + 360.0) % 360.0
        assertEquals(90.0, span, 0.0001)
        geometry.arc.forEach { point ->
            assertTrue("el arco no debe cruzar el muro: ${point.y}", point.y >= -0.0001)
        }
        // ... and it ends exactly on the open leaf.
        assertEquals(geometry.leaf[1].x, geometry.arc.last().x, 0.0001)
        assertEquals(geometry.leaf[1].y, geometry.arc.last().y, 0.0001)
    }

    @Test
    fun gapsThatLeaveNoLeafAreRejectedWithAMessage() {
        val tooNarrow = PlanDoors.fromGap(hinge, PointMm(1_120.0, 0.0), frameWidthMm = 50, depthMm = 100)
        assertTrue(tooNarrow is DoorGapResult.Rejected)
        assertTrue((tooNarrow as DoorGapResult.Rejected).message.contains("hoja de 20 mm"))

        val samePoint = PlanDoors.fromGap(hinge, hinge, frameWidthMm = 50, depthMm = 100)
        assertTrue((samePoint as DoorGapResult.Rejected).message.contains("dos extremos distintos"))

        val flat = PlanDoors.fromGap(hinge, latch, frameWidthMm = 50, depthMm = 10)
        assertTrue((flat as DoorGapResult.Rejected).message.contains("al menos 20 mm"))
    }

    @Test
    fun leafThicknessAndAngleStayInsideTheirLimits() {
        val thin = PlanDoors.fromGap(hinge, latch, frameWidthMm = 50, depthMm = 30)
        assertEquals(30L, (thin as DoorGapResult.Ready).door.leafThicknessMm)
        assertThrows(IllegalArgumentException::class.java) { door.copy(leafThicknessMm = 120) }
        assertThrows(IllegalArgumentException::class.java) { door.copy(openAngleDegrees = 361.0) }
        assertThrows(IllegalArgumentException::class.java) { door.copy(openAngleDegrees = -1.0) }
    }

    @Test
    fun theDoorSitsInTheMiddleOfTheWallThickness() {
        // The wall axis is its near face: the body grows 100 mm toward the growth side.
        val wall = WallSegment(PointMm(0.0, 0.0), PointMm(1_000.0, 0.0), 1_000, 0.0, 100)
        val shift = PlanDoors.centerOffset(listOf(wall), wall.end)
        assertEquals(0.0, shift.x, 0.0001)
        assertEquals(50.0, shift.y, 0.0001)

        // A wall that grows to the other side moves the door the other way.
        val mirroredSide = wall.copy(growthSide = WallGrowthSide.RIGHT)
        assertEquals(-50.0, PlanDoors.centerOffset(listOf(mirroredSide), mirroredSide.end).y, 0.0001)

        // A vertical wall moves the door along X.
        val vertical = WallSegment(PointMm(0.0, 0.0), PointMm(0.0, 1_000.0), 1_000, 90.0, 100)
        val verticalShift = PlanDoors.centerOffset(listOf(vertical), vertical.end)
        assertEquals(-50.0, verticalShift.x, 0.0001)
        assertEquals(0.0, verticalShift.y, 0.0001)

        // No wall or no thickness: nothing to shift.
        assertEquals(PointMm(0.0, 0.0), PlanDoors.centerOffset(listOf(wall.copy(widthMm = 0)), wall.end))
        assertEquals(PointMm(0.0, 0.0), PlanDoors.centerOffset(emptyList(), wall.end))
    }

    @Test
    fun theMirrorMovesTheHingeToTheOtherEndAndKeepsTheLeafOnItsSide() {
        val original = door.copy(openAngleDegrees = 90.0)
        val mirrored = PlanDoors.mirrored(original)

        assertEquals(original.latch, mirrored.hinge)
        assertEquals(original.hinge, mirrored.latch)
        assertEquals(270.0, mirrored.openAngleDegrees, 0.0001)
        assertEquals(original.gapLengthMm, mirrored.gapLengthMm, 0.0001)
        assertEquals(original.leafLengthMm, mirrored.leafLengthMm, 0.0001)

        // Both leaves stay on the same side of the wall, only the hinge changes end.
        val originalEnd = PlanDoors.geometry(original).arc.last()
        val mirroredEnd = PlanDoors.geometry(mirrored).arc.last()
        assertEquals(1_050.0, originalEnd.x, 0.0001)
        assertEquals(700.0, originalEnd.y, 0.0001)
        assertEquals(1_750.0, mirroredEnd.x, 0.0001)
        assertEquals(700.0, mirroredEnd.y, 0.0001)

        // Mirroring twice returns the original door.
        assertEquals(original, PlanDoors.mirrored(mirrored))
        // A closed or fully turned leaf survives the reflection.
        assertEquals(0.0, PlanDoors.mirrored(door.copy(openAngleDegrees = 0.0)).openAngleDegrees, 0.0001)
        assertEquals(180.0, PlanDoors.mirrored(door.copy(openAngleDegrees = 180.0)).openAngleDegrees, 0.0001)
    }

    private fun assertPoint(x: Double, y: Double, door: PlanDoor) {
        val end = PlanDoors.geometry(door).arc.last()
        assertEquals(x, end.x, 0.0001)
        assertEquals(y, end.y, 0.0001)
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
