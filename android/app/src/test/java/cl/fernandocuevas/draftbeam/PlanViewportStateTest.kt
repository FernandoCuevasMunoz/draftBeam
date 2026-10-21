package cl.fernandocuevas.draftbeam

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanViewportStateTest {
    @Test
    fun dimensionLabelsScaleWithZoomWithinReadableLimits() {
        val density = 2f

        assertEquals(10f, scaledDimensionTextSize(0.05f, density), 0.001f)
        assertEquals(26f, scaledDimensionTextSize(0.2f, density), 0.001f)
        assertEquals(48f, scaledDimensionTextSize(1f, density), 0.001f)
        assertEquals(10f, scaledDimensionTextSize(0.001f, density), 0.001f)
        assertEquals(4f, scaledDimensionLabelOffset(0.001f, density), 0.001f)
        assertEquals(16f, scaledDimensionLabelOffset(0.2f, density), 0.001f)
    }

    @Test
    fun scaleDivisionsAdaptToZoomUsingReadableMetricIntervals() {
        val closeGrid = selectGridStepMm(minimumSpacingPx = 30f, pixelsPerMillimeter = 0.1f)
        val farGrid = selectGridStepMm(minimumSpacingPx = 30f, pixelsPerMillimeter = 0.01f)

        assertEquals(500.0, closeGrid, 0.001)
        assertEquals(5_000.0, farGrid, 0.001)
        assertTrue(closeGrid < farGrid)
        assertEquals("50 cm", formatScaleCoordinate(500.0, closeGrid))
        assertEquals("5m", formatScaleCoordinate(5_000.0, farGrid))
        assertEquals("1 cm", formatScaleCoordinate(10.0, 10.0))
        assertEquals("5 mm", formatScaleCoordinate(5.0, 5.0))
    }

    @Test
    fun emptyPlanStartsCenteredAtOriginWithTenMeterSquare() {
        val viewport = PlanViewportState()
        viewport.updateCanvasSize(canvasSize, emptyList())

        assertEquals(0f, viewport.centerXMillimeters, 0.01f)
        assertEquals(0f, viewport.centerYMillimeters, 0.01f)
        val bottomLeft = viewport.project(PointMm(-5_000.0, -5_000.0), canvasSize)
        val topRight = viewport.project(PointMm(5_000.0, 5_000.0), canvasSize)
        assertEquals(canvasSize.width.toFloat() - bottomLeft.x, topRight.x, 0.1f)
        assertEquals(canvasSize.height.toFloat() - bottomLeft.y, topRight.y, 0.1f)
        assertTrue(bottomLeft.x > 0f && topRight.x < canvasSize.width)
        assertTrue(topRight.y > 0f && bottomLeft.y < canvasSize.height)
    }

    @Test
    fun screenAndWorldCoordinatesRoundTrip() {
        val viewport = createViewport()
        val expectedWorldPoint = PointMm(1_250.0, -640.0)

        val screenPoint = viewport.project(expectedWorldPoint, canvasSize)
        val actualWorldPoint = viewport.unproject(screenPoint, canvasSize)

        assertEquals(expectedWorldPoint.x, actualWorldPoint.x, 0.01)
        assertEquals(expectedWorldPoint.y, actualWorldPoint.y, 0.01)
    }

    @Test
    fun panningMovesThePlanWithTheFinger() {
        val viewport = createViewport()
        val point = PointMm(0.0, 0.0)
        val beforePan = viewport.project(point, canvasSize)

        viewport.panBy(Offset(80f, -35f))

        val afterPan = viewport.project(point, canvasSize)
        assertEquals(beforePan.x + 80f, afterPan.x, 0.01f)
        assertEquals(beforePan.y - 35f, afterPan.y, 0.01f)
    }

    @Test
    fun pinchKeepsTheMapPointUnderTheGestureCentroid() {
        val viewport = createViewport()
        val oldCentroid = Offset(320f, 220f)
        val newCentroid = Offset(350f, 260f)
        val worldPointAtCentroid = viewport.unproject(oldCentroid, canvasSize)

        viewport.zoomAround(oldCentroid, newCentroid, zoomFactor = 1.8f, size = canvasSize)

        val newScreenPoint = viewport.project(worldPointAtCentroid, canvasSize)
        assertEquals(newCentroid.x, newScreenPoint.x, 0.01f)
        assertEquals(newCentroid.y, newScreenPoint.y, 0.01f)
    }

    @Test
    fun fitToContentCentersAllSegmentEndpoints() {
        val viewport = createViewport()
        val segments = listOf(
            WallSegment(PointMm(1_000.0, 2_000.0), PointMm(3_000.0, 2_000.0), 2_000, 0.0),
            WallSegment(PointMm(3_000.0, 2_000.0), PointMm(3_000.0, 6_000.0), 4_000, 90.0)
        )

        viewport.fitToContent(segments)

        assertEquals(2_000f, viewport.centerXMillimeters, 0.01f)
        assertEquals(4_000f, viewport.centerYMillimeters, 0.01f)
        val points = segments.flatMap { listOf(it.start, it.end) }
        points.forEach { point ->
            val projected = viewport.project(point, canvasSize)
            assertTrue(projected.x in 0f..canvasSize.width.toFloat())
            assertTrue(projected.y in 0f..canvasSize.height.toFloat())
        }
    }

    @Test
    fun fittingOneHorizontalWallUsesNearlyTheFullCanvasWidth() {
        val viewport = createViewport()
        val wall = WallSegment(PointMm(0.0, 0.0), PointMm(6_000.0, 0.0), 6_000, 0.0)

        viewport.fitToContent(listOf(wall))

        val start = viewport.project(wall.start, canvasSize)
        val end = viewport.project(wall.end, canvasSize)
        val projectedWidth = end.x - start.x
        assertTrue(projectedWidth > canvasSize.width * 0.85f)
        assertTrue(projectedWidth < canvasSize.width)
        assertEquals(canvasSize.height / 2f, start.y, 0.01f)
        assertEquals(start.y, end.y, 0.01f)
    }

    private fun createViewport(): PlanViewportState = PlanViewportState().also {
        it.updateCanvasSize(canvasSize, emptyList())
    }

    private companion object {
        val canvasSize = IntSize(width = 1_000, height = 600)
    }
}
