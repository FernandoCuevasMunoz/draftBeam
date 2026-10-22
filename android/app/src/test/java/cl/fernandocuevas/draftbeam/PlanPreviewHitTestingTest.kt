package cl.fernandocuevas.draftbeam

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
