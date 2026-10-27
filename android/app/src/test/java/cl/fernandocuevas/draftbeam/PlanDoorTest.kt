package cl.fernandocuevas.draftbeam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Door placed in a gap that already exists: 1000 mm wall, 800 mm gap, 1000 mm wall,
 * 100 mm thick. The gap is the opening, the frame takes 50 mm per side and the depth
 * is measured across the walls.
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
        assertEquals(40L, door.leafThicknessMm)

        val geometry = PlanDoors.geometry(door)
        // Frames sit inside the gap, one at each wall end, as deep as the wall.
        assertEquals(2, geometry.frames.size)
        assertEquals(1_000.0, geometry.frames[0].minOf { it.x }, 0.0001)
        assertEquals(1_050.0, geometry.frames[0].maxOf { it.x }, 0.0001)
        assertEquals(-50.0, geometry.frames[0].minOf { it.y }, 0.0001)
        assertEquals(50.0, geometry.frames[0].maxOf { it.y }, 0.0001)
        assertEquals(1_750.0, geometry.frames[1].minOf { it.x }, 0.0001)
        assertEquals(1_800.0, geometry.frames[1].maxOf { it.x }, 0.0001)

        // The leaf turns on the inner frame face and keeps 700 mm.
        assertEquals(PointMm(1_050.0, 0.0), geometry.pivot)
        assertEquals(700.0, geometry.radiusMm, 0.0001)
        assertEquals(PointMm(1_750.0, 0.0), geometry.arc.first())
        assertEquals(PointMm(1_050.0, 700.0), geometry.arc.last())
        assertEquals(28_000.0, area(listOf(geometry.leaf)), 0.0001) // 700 x 40
        assertEquals(OpeningLine(hinge, latch), geometry.opening)
    }

    @Test
    fun theWallEndsOfThePlanBecomeTheMeasuredGap() {
        // Mirrors the user scenario: two 1000 x 100 walls with an 800 mm gap between them.
        val first = WallSegment(PointMm(0.0, 0.0), PointMm(1_000.0, 0.0), 1_000, 0.0, 100)
        val second = WallSegment(PointMm(1_800.0, 0.0), PointMm(2_800.0, 0.0), 1_000, 0.0, 100)
        val walls = listOf(first, second)

        val measured = PlanDoors.fromGap(first.end, second.start, frameWidthMm = 50, depthMm = 100)
        assertTrue(measured is DoorGapResult.Ready)
        val placed = (measured as DoorGapResult.Ready).door
        assertEquals(800.0, placed.gapLengthMm, 0.0001)
        assertEquals(700.0, placed.leafLengthMm, 0.0001)
        assertEquals(100L, PlanDoors.wallWidthAt(walls, first.end))
        assertEquals(100L, PlanDoors.wallWidthAt(walls, second.start))
        // Walls are reported, never modified: the plan keeps 1000 + gap + 1000.
        assertEquals(listOf(1_000L, 1_000L), walls.map { it.lengthMm })
        assertEquals(listOf(0.0, 1_800.0), walls.map { it.start.x })
        assertEquals(listOf(1_000.0, 2_800.0), walls.map { it.end.x })
        assertEquals(listOf(emptyList<WallOpening>(), emptyList()), walls.map { it.openings })
    }

    @Test
    fun depthComesFromTwoTapsAcrossTheGap() {
        assertEquals(100L, PlanDoors.depthFromTaps(door, PointMm(1_400.0, 60.0), PointMm(1_400.0, -40.0)))
        // Asymmetry around the axis is discarded: only the distance counts.
        assertEquals(100L, PlanDoors.depthFromTaps(door, PointMm(1_300.0, 250.0), PointMm(1_500.0, 150.0)))
        assertNull(PlanDoors.depthFromTaps(door, PointMm(1_400.0, 0.0), PointMm(1_400.0, 10.0)))
        assertEquals(30L, PlanDoors.depthFromTaps(door, PointMm(1_400.0, 25.0), PointMm(1_400.0, -5.0)))
    }

    @Test
    fun pointerSetsSideAndAngleOfTheLeaf() {
        val up = PlanDoors.toward(door, PointMm(1_050.0, 500.0))
        assertEquals(1, up.swingSign)
        assertEquals(90.0, up.openAngleDegrees, 0.0001)

        val down = PlanDoors.toward(door, PointMm(1_050.0, -500.0))
        assertEquals(-1, down.swingSign)
        assertEquals(90.0, down.openAngleDegrees, 0.0001)

        val diagonal = PlanDoors.toward(door, PointMm(1_750.0, 700.0))
        assertEquals(1, diagonal.swingSign)
        assertEquals(45.0, diagonal.openAngleDegrees, 0.0001)

        val obtuse = PlanDoors.toward(door, PointMm(350.0, 700.0))
        assertEquals(135.0, obtuse.openAngleDegrees, 0.0001)
    }

    @Test
    fun fourMirrorsMoveTheHingeAndTheSwingSide() {
        val farStart = PlanDoors.geometry(door)
        val nearStart = PlanDoors.geometry(door.copy(swingSign = -1))
        val farEnd = PlanDoors.geometry(door.copy(hinge = latch, latch = hinge))
        val nearEnd = PlanDoors.geometry(door.copy(hinge = latch, latch = hinge, swingSign = -1))

        // Reversing the endpoints flips the leaf side, so the four mirrors stay distinct.
        assertEquals(PointMm(1_050.0, 700.0), farStart.arc.last())
        assertEquals(PointMm(1_050.0, -700.0), nearStart.arc.last())
        assertEquals(PointMm(1_750.0, -700.0), farEnd.arc.last())
        assertEquals(PointMm(1_750.0, 700.0), nearEnd.arc.last())
    }

    @Test
    fun dxfArcAnglesAlwaysRunCounterClockwise() {
        assertEquals(0.0, PlanDoors.geometry(door).arcStartDegrees, 0.0001)
        assertEquals(90.0, PlanDoors.geometry(door).arcEndDegrees, 0.0001)
        val near = PlanDoors.geometry(door.copy(swingSign = -1))
        assertEquals(270.0, near.arcStartDegrees, 0.0001)
        assertEquals(0.0, near.arcEndDegrees, 0.0001)
        val reversed = PlanDoors.geometry(door.copy(hinge = latch, latch = hinge))
        assertEquals(180.0, reversed.arcStartDegrees, 0.0001)
        assertEquals(270.0, reversed.arcEndDegrees, 0.0001)
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
    fun leafThicknessNeverExceedsTheMeasuredDepth() {
        val thin = PlanDoors.fromGap(hinge, latch, frameWidthMm = 50, depthMm = 30)
        assertEquals(30L, (thin as DoorGapResult.Ready).door.leafThicknessMm)
        assertThrows(IllegalArgumentException::class.java) {
            door.copy(leafThicknessMm = 120)
        }
        assertThrows(IllegalArgumentException::class.java) {
            door.copy(openAngleDegrees = 181.0)
        }
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
