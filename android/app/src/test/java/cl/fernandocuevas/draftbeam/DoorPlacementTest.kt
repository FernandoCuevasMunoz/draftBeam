package cl.fernandocuevas.draftbeam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADT2 flow: two taps measure the opening on the wall mid-line, the pointer sets side and angle. */
class DoorPlacementTest {
    private val wall = WallSegment(PointMm(0.0, 0.0), PointMm(3_000.0, 0.0), 3_000, 0.0, 200)

    private fun ready(first: PointMm, second: PointMm, onWall: WallSegment = wall): PlanGeometry.DoorSpan =
        when (val result = PlanGeometry.doorSpan(onWall, first, second)) {
            is PlanGeometry.DoorSpanResult.Ready -> result.span
            is PlanGeometry.DoorSpanResult.Rejected -> throw AssertionError(result.message)
        }

    private fun rejected(first: PointMm, second: PointMm, onWall: WallSegment = wall): String =
        when (val result = PlanGeometry.doorSpan(onWall, first, second)) {
            is PlanGeometry.DoorSpanResult.Ready -> throw AssertionError("Se esperaba un rechazo.")
            is PlanGeometry.DoorSpanResult.Rejected -> result.message
        }

    @Test
    fun twoTapsMeasureTheOpeningOnTheWallMidLine() {
        // Taps sit on opposite wall faces; only the projection on the axis matters.
        val span = ready(PointMm(1_100.0, 20.0), PointMm(1_900.0, 180.0))
        assertEquals(PlanGeometry.DoorSpan(1_100, 800, hingeAtStart = true), span)

        val reversed = ready(PointMm(1_900.0, 180.0), PointMm(1_100.0, 20.0))
        assertEquals(PlanGeometry.DoorSpan(1_100, 800, hingeAtStart = false), reversed)
    }

    @Test
    fun tapsAreRoundedToTenMillimeterSteps() {
        assertEquals(PlanGeometry.DoorSpan(1_100, 800, true), ready(PointMm(1_104.0, 0.0), PointMm(1_897.0, 0.0)))
        assertEquals(PlanGeometry.DoorSpan(0, 800, true), ready(PointMm(-40.0, 0.0), PointMm(795.0, 0.0)))
    }

    @Test
    fun narrowOversizedAndOverlappingOpeningsAreRejected() {
        assertTrue(rejected(PointMm(1_000.0, 0.0), PointMm(1_050.0, 0.0)).contains("al menos 100 mm"))
        assertTrue(rejected(PointMm(2_950.0, 0.0), PointMm(3_400.0, 0.0)).contains("al menos 100 mm"))
        val withDoor = wall.copy(openings = listOf(DoorOpening(1_000, 800)))
        assertTrue(
            rejected(PointMm(1_500.0, 0.0), PointMm(2_000.0, 0.0), withDoor)
                .contains("no pueden superponerse")
        )
        assertTrue(rejected(PointMm(0.0, 0.0), PointMm(3_000.0, 0.0)).contains("muro completo"))
    }

    @Test
    fun theWholeWallIsRejectedWithItsOwnMessageInsteadOfASizeError() {
        // Tapping both wall ends is a common mistake: the message must say why it fails.
        val message = rejected(PointMm(0.0, 0.0), PointMm(3_000.0, 0.0))
        assertTrue(message.contains("dejá muro en los dos extremos"))
        assertEquals(
            PlanGeometry.DoorSpan(10, 2_980, true),
            ready(PointMm(10.0, 0.0), PointMm(2_990.0, 0.0))
        )
    }

    @Test
    fun wallWithoutThicknessCannotHoldADoor() {
        val thin = wall.copy(widthMm = 0)
        assertTrue(rejected(PointMm(500.0, 0.0), PointMm(1_500.0, 0.0), thin).contains("no tiene espesor"))
    }

    @Test
    fun pointerSetsOpeningSideAndAngle() {
        val span = PlanGeometry.DoorSpan(1_000, 800, hingeAtStart = true)

        val straight = PlanGeometry.doorToward(wall, span, PointMm(1_000.0, 900.0))
        assertEquals(90.0, straight.openAngleDegrees, 0.0001)
        assertEquals(false, straight.swingTowardNearFace)
        assertEquals(1_000L, straight.offsetMm)
        assertEquals(800L, straight.holeLengthMm)

        val oppositeSide = PlanGeometry.doorToward(wall, span, PointMm(1_000.0, -700.0))
        assertEquals(90.0, oppositeSide.openAngleDegrees, 0.0001)
        assertEquals(true, oppositeSide.swingTowardNearFace)

        val diagonal = PlanGeometry.doorToward(wall, span, PointMm(1_400.0, 500.0))
        assertEquals(45.0, diagonal.openAngleDegrees, 0.0001)

        val obtuse = PlanGeometry.doorToward(wall, span, PointMm(600.0, 500.0))
        assertEquals(135.0, obtuse.openAngleDegrees, 0.0001)

        val clamped = PlanGeometry.doorToward(wall, span, PointMm(-5_000.0, 100.5))
        assertEquals(180.0, clamped.openAngleDegrees, 0.0001)
    }

    @Test
    fun hingeAtTheEndMeasuresTheAngleFromTheOtherEnd() {
        val span = PlanGeometry.DoorSpan(1_000, 800, hingeAtStart = false)
        val pointer = PointMm(1_800.0, 900.0)
        val door = PlanGeometry.doorToward(wall, span, pointer)
        assertEquals(90.0, door.openAngleDegrees, 0.0001)
        assertEquals(false, door.hingeAtStart)
    }
}
