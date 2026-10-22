package cl.fernandocuevas.draftbeam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanGeometryTest {
    @Test
    fun firstWallStartsAtOriginAndMovesAlongSelectedAxis() {
        val segments = PlanGeometry.appendSegment(emptyList(), angleDegrees = 0.0, lengthMm = 6_207)

        assertEquals(PointMm(0.0, 0.0), segments.single().start)
        assertEquals(PointMm(6_207.0, 0.0), segments.single().end)
    }

    @Test
    fun manuallyCreatedWallUsesActiveStartDirectionWidthAndGrowthSide() {
        val wall = PlanGeometry.createWall(
            existingSegments = emptyList(),
            angleDegrees = 90.0,
            lengthMm = 2_400,
            startPoint = PointMm(500.0, 250.0),
            widthMm = 120,
            growthSide = WallGrowthSide.RIGHT
        )

        assertEquals(PointMm(500.0, 250.0), wall.start)
        assertEquals(PointMm(500.0, 2_650.0), wall.end)
        assertEquals(2_400L, wall.lengthMm)
        assertEquals(90.0, wall.angleDegrees, 0.0)
        assertEquals(120L, wall.widthMm)
        assertEquals(WallGrowthSide.RIGHT, wall.growthSide)
    }

    @Test
    fun eachNewWallStartsAtPreviousEndpoint() {
        val first = PlanGeometry.appendSegment(emptyList(), 0.0, 4_000)
        val second = PlanGeometry.appendSegment(first, 90.0, 3_000)
        val third = PlanGeometry.appendSegment(second, 180.0, 1_500)

        assertEquals(PointMm(4_000.0, 0.0), second.last().start)
        assertEquals(PointMm(4_000.0, 3_000.0), second.last().end)
        assertEquals(PointMm(4_000.0, 3_000.0), third.last().start)
        assertEquals(PointMm(2_500.0, 3_000.0), third.last().end)
    }

    @Test
    fun newWallCanStartFromAChosenExistingEndpoint() {
        val first = PlanGeometry.appendSegment(emptyList(), 0.0, 4_000)
        val second = PlanGeometry.appendSegment(
            existingSegments = first,
            angleDegrees = 90.0,
            lengthMm = 2_000,
            startPoint = first.single().start
        )

        assertEquals(PointMm(0.0, 0.0), second.last().start)
        assertEquals(PointMm(0.0, 2_000.0), second.last().end)
        assertEquals(PointMm(4_000.0, 0.0), second.first().end)
    }

    @Test
    fun fingerDragSnapsNearAnAxisAndPreservesOtherAngles() {
        val snapped = PlanGeometry.segmentFromDrag(PointMm(0.0, 0.0), PointMm(1_000.0, 50.0))
        val diagonal = PlanGeometry.segmentFromDrag(PointMm(0.0, 0.0), PointMm(1_000.0, 1_000.0))

        assertEquals(0.0, snapped.angleDegrees, 0.0)
        assertEquals(0.0, snapped.end.y, 0.0)
        assertEquals(45.0, diagonal.angleDegrees, 0.01)
    }

    @Test
    fun updatingLengthKeepsDirectionAndDoesNotMoveOtherWalls() {
        val first = PlanGeometry.appendSegment(emptyList(), 0.0, 4_000)
        val diagonal = PlanGeometry.appendSegment(first, 45.0, 1_414)
        val updated = PlanGeometry.updateSegmentLength(diagonal, index = 1, newLengthMm = 2_000)

        assertEquals(first.single(), updated.first())
        assertEquals(2_000L, updated[1].lengthMm)
        assertEquals(45.0, updated[1].angleDegrees, 0.0)
        assertEquals(4_000.0 + 2_000.0 / kotlin.math.sqrt(2.0), updated[1].end.x, 0.001)
        assertEquals(2_000.0 / kotlin.math.sqrt(2.0), updated[1].end.y, 0.001)
    }

    @Test
    fun movingOneEndpointUpdatesLengthAndAngleWithoutMovingOtherWalls() {
        val first = PlanGeometry.appendSegment(emptyList(), 0.0, 1_000)
        val second = PlanGeometry.appendSegment(first, 90.0, 1_000)
        val destination = PointMm(1_200.0, 300.0)

        val updated = PlanGeometry.moveEndpoint(
            existingSegments = second,
            index = 0,
            endpoint = WallEndpoint.END,
            destination = destination
        )

        assertEquals(destination, updated[0].end)
        assertEquals(1_237L, updated[0].lengthMm)
        assertEquals(Math.toDegrees(kotlin.math.atan2(300.0, 1_200.0)), updated[0].angleDegrees, 0.001)
        assertEquals(second[1], updated[1])
    }

    @Test
    fun movingEndpointSnapsToAxisWithinEightDegrees() {
        val segment = PlanGeometry.appendSegment(emptyList(), 0.0, 1_000)

        val updated = PlanGeometry.moveEndpoint(
            existingSegments = segment,
            index = 0,
            endpoint = WallEndpoint.END,
            destination = PointMm(1_200.0, 100.0)
        ).single()

        assertEquals(PointMm(1_204.0, 0.0), updated.end)
        assertEquals(0.0, updated.angleDegrees, 0.0)
    }

    @Test
    fun movingStartEndpointAlsoSnapsRelativeToTheFixedEnd() {
        val segment = PlanGeometry.appendSegment(emptyList(), 0.0, 1_000)

        val updated = PlanGeometry.moveEndpoint(
            existingSegments = segment,
            index = 0,
            endpoint = WallEndpoint.START,
            destination = PointMm(-200.0, 30.0)
        ).single()

        assertEquals(PointMm(-200.0, 0.0), updated.start)
        assertEquals(0.0, updated.angleDegrees, 0.0)
    }

    @Test
    fun movingEndpointOutsideEightDegreeToleranceKeepsFreeAngle() {
        val segment = PlanGeometry.appendSegment(emptyList(), 0.0, 1_000)
        val destination = PointMm(1_200.0, 250.0)

        val updated = PlanGeometry.moveEndpoint(
            existingSegments = segment,
            index = 0,
            endpoint = WallEndpoint.END,
            destination = destination
        ).single()

        assertEquals(destination, updated.end)
        assertEquals(Math.toDegrees(kotlin.math.atan2(250.0, 1_200.0)), updated.angleDegrees, 0.001)
    }

    @Test
    fun movingWholeJunctionMovesEveryExactlyConnectedEndpoint() {
        val first = PlanGeometry.appendSegment(emptyList(), 0.0, 1_000)
        val second = PlanGeometry.appendSegment(first, 90.0, 1_000)
        val third = PlanGeometry.appendSegment(
            second,
            angleDegrees = 180.0,
            lengthMm = 500,
            startPoint = second[1].end
        )
        val junction = second[1].end
        val destination = PointMm(1_030.0, 2_100.0)
        val snappedDestination = PointMm(1_000.0, 2_100.0)

        assertEquals(2, PlanGeometry.endpointUseCount(third, junction))
        val updated = PlanGeometry.moveEndpoint(
            existingSegments = third,
            index = 1,
            endpoint = WallEndpoint.END,
            destination = destination,
            moveConnectedEndpoints = true
        )

        assertEquals(snappedDestination, updated[1].end)
        assertEquals(snappedDestination, updated[2].start)
        assertEquals(third[0], updated[0])
        assertEquals(third[1].start, updated[1].start)
        assertEquals(third[2].end, updated[2].end)
    }

    @Test
    fun movingEndpointCannotCollapseWallToZeroLength() {
        val segment = PlanGeometry.appendSegment(emptyList(), 0.0, 1_000)

        assertThrows(IllegalArgumentException::class.java) {
            PlanGeometry.moveEndpoint(segment, 0, WallEndpoint.END, segment.single().start)
        }
    }

    @Test
    fun rejectsZeroOrNegativeWallLength() {
        assertThrows(IllegalArgumentException::class.java) {
            PlanGeometry.appendSegment(emptyList(), 0.0, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlanGeometry.appendSegment(emptyList(), 0.0, -10)
        }
    }

    @Test
    fun deletingMiddleWallKeepsOtherWallsAtTheirOriginalCoordinates() {
        val first = PlanGeometry.appendSegment(emptyList(), 0.0, 4_000)
        val second = PlanGeometry.appendSegment(first, 90.0, 3_000)
        val third = PlanGeometry.appendSegment(second, 180.0, 4_000)

        val remaining = PlanGeometry.removeSegment(third, index = 1)

        assertEquals(2, remaining.size)
        assertEquals(third[0], remaining[0])
        assertEquals(third[2], remaining[1])
        assertEquals(PointMm(4_000.0, 3_000.0), remaining[1].start)
        assertEquals(PointMm(0.0, 3_000.0), remaining[1].end)
    }

    @Test
    fun rejectsDeletingAnUnknownWall() {
        assertThrows(IllegalArgumentException::class.java) {
            PlanGeometry.removeSegment(emptyList(), index = 0)
        }
    }

    @Test
    fun wallWidthGrowsTowardSelectedSideForHorizontalWall() {
        val wall = WallSegment(
            start = PointMm(0.0, 0.0),
            end = PointMm(1_000.0, 0.0),
            lengthMm = 1_000,
            angleDegrees = 0.0,
            widthMm = 100,
            growthSide = WallGrowthSide.LEFT
        )

        assertEquals(
            listOf(PointMm(0.0, 0.0), PointMm(1_000.0, 0.0), PointMm(1_000.0, 100.0), PointMm(0.0, 100.0)),
            PlanGeometry.wallCorners(wall)
        )
        assertEquals(
            PointMm(1_000.0, -100.0),
            PlanGeometry.wallCorners(wall.copy(growthSide = WallGrowthSide.RIGHT))[2]
        )
    }

    @Test
    fun wallInteractionPointsIncludeBothEdgesOfWallsWithWidth() {
        val wall = WallSegment(
            start = PointMm(0.0, 0.0),
            end = PointMm(1_000.0, 0.0),
            lengthMm = 1_000,
            angleDegrees = 0.0,
            widthMm = 100
        )

        assertEquals(
            listOf(PointMm(0.0, 0.0), PointMm(1_000.0, 0.0), PointMm(1_000.0, 100.0), PointMm(0.0, 100.0)),
            PlanGeometry.wallInteractionPoints(listOf(wall))
        )
    }

    @Test
    fun diagonalWallWidthUsesPerpendicularOffset() {
        val wall = WallSegment(
            start = PointMm(0.0, 0.0),
            end = PointMm(1_000.0, 1_000.0),
            lengthMm = 1_414,
            angleDegrees = 45.0,
            widthMm = 200,
            growthSide = WallGrowthSide.LEFT
        )

        val offsetCorner = PlanGeometry.wallCorners(wall)[3]
        assertEquals(-200.0 / kotlin.math.sqrt(2.0), offsetCorner.x, 0.001)
        assertEquals(200.0 / kotlin.math.sqrt(2.0), offsetCorner.y, 0.001)
    }

    @Test
    fun changingLengthPreservesWallWidthAndGrowthSide() {
        val wall = WallSegment(
            start = PointMm(0.0, 0.0),
            end = PointMm(1_000.0, 0.0),
            lengthMm = 1_000,
            angleDegrees = 0.0,
            widthMm = 150,
            growthSide = WallGrowthSide.RIGHT
        )

        val updated = PlanGeometry.updateSegmentLength(listOf(wall), 0, 2_000).single()

        assertEquals(150L, updated.widthMm)
        assertEquals(WallGrowthSide.RIGHT, updated.growthSide)
        assertEquals(PointMm(2_000.0, 0.0), updated.end)
    }

    @Test
    fun connectedWallsTrimOverlapFromTheWiderFootprint() {
        val narrow = WallSegment(
            PointMm(0.0, 0.0), PointMm(1_000.0, 0.0), 1_000, 0.0,
            widthMm = 100, growthSide = WallGrowthSide.LEFT
        )
        val wide = WallSegment(
            PointMm(1_000.0, 0.0), PointMm(1_000.0, 1_000.0), 1_000, 90.0,
            widthMm = 200, growthSide = WallGrowthSide.LEFT
        )

        val footprints = PlanGeometry.wallFootprints(listOf(narrow, wide))

        assertEquals(100_000.0, totalArea(footprints[0]), 0.01)
        assertEquals(180_000.0, totalArea(footprints[1]), 0.01)
    }

    @Test
    fun crossingWallsWithoutSharedEndpointsAreNotTrimmed() {
        val horizontal = WallSegment(
            PointMm(0.0, 0.0), PointMm(1_000.0, 0.0), 1_000, 0.0,
            widthMm = 100, growthSide = WallGrowthSide.LEFT
        )
        val crossing = WallSegment(
            PointMm(500.0, -500.0), PointMm(500.0, 500.0), 1_000, 90.0,
            widthMm = 200, growthSide = WallGrowthSide.LEFT
        )

        val footprints = PlanGeometry.wallFootprints(listOf(horizontal, crossing))

        assertEquals(100_000.0, totalArea(footprints[0]), 0.01)
        assertEquals(200_000.0, totalArea(footprints[1]), 0.01)
    }

    @Test
    fun diagonalConnectionTrimsOnlyTheSharedJunctionOverlap() {
        val horizontal = WallSegment(
            PointMm(0.0, 0.0), PointMm(1_000.0, 0.0), 1_000, 0.0,
            widthMm = 100, growthSide = WallGrowthSide.LEFT
        )
        val diagonal = WallSegment(
            PointMm(1_000.0, 0.0), PointMm(2_000.0, 1_000.0), 1_414, 45.0,
            widthMm = 200, growthSide = WallGrowthSide.LEFT
        )

        val footprints = PlanGeometry.wallFootprints(listOf(horizontal, diagonal))

        assertEquals(100_000.0, totalArea(footprints[0]), 0.01)
        val fullDiagonalArea = diagonal.lengthMm * diagonal.widthMm.toDouble()
        val visibleDiagonalArea = totalArea(footprints[1])
        assertTrue(visibleDiagonalArea > 0.0 && visibleDiagonalArea < fullDiagonalArea)
    }

    @Test
    fun orthogonalGrowthSidesUseScreenDirectionLabels() {
        assertEquals("Arriba", WallGrowthSide.LEFT.axisLabel(0.0))
        assertEquals("Abajo", WallGrowthSide.RIGHT.axisLabel(0.0))
        assertEquals("Izquierda", WallGrowthSide.LEFT.axisLabel(90.0))
        assertEquals("Derecha", WallGrowthSide.RIGHT.axisLabel(90.0))
        assertEquals("Abajo", WallGrowthSide.LEFT.axisLabel(180.0))
        assertEquals("Derecha", WallGrowthSide.LEFT.axisLabel(270.0))
    }

    @Test
    fun newWallDefaultsToTheSideOppositeItsLabel() {
        assertEquals(WallGrowthSide.RIGHT, PlanGeometry.defaultGrowthSide(0.0))
        assertEquals(WallGrowthSide.LEFT, PlanGeometry.defaultGrowthSide(180.0))
        assertEquals(WallGrowthSide.RIGHT, PlanGeometry.defaultGrowthSide(90.0))
        assertEquals(WallGrowthSide.LEFT, PlanGeometry.defaultGrowthSide(270.0))
        assertEquals(WallGrowthSide.RIGHT, PlanGeometry.defaultGrowthSide(45.0))
    }

    @Test
    fun growthSideOptionsStayInVisualLeftToRightOrder() {
        val verticalOptions = WallGrowthSide.orderedForAngle(270.0)
        val horizontalOptions = WallGrowthSide.orderedForAngle(180.0)

        assertEquals(listOf("Izquierda", "Derecha"), verticalOptions.map { it.axisLabel(270.0) })
        assertEquals(listOf("Arriba", "Abajo"), horizontalOptions.map { it.axisLabel(180.0) })
    }

    @Test
    fun diagonalLabelsStayUprightInBothDirections() {
        assertEquals(45.0, PlanGeometry.uprightLabelRotationDegrees(-135.0), 0.0)
        assertEquals(45.0, PlanGeometry.uprightLabelRotationDegrees(45.0), 0.0)
        assertEquals(-45.0, PlanGeometry.uprightLabelRotationDegrees(135.0), 0.0)
    }

    private fun totalArea(polygons: List<List<PointMm>>): Double = polygons.sumOf { polygon ->
        kotlin.math.abs(polygon.indices.sumOf { index ->
            val current = polygon[index]
            val next = polygon[(index + 1) % polygon.size]
            current.x * next.y - next.x * current.y
        } / 2.0)
    }
}
