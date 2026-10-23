package cl.fernandocuevas.draftbeam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PlanSessionCodecTest {
    @Test
    fun draftRoundTripsExactWallsNotesAndDocumentAssociation() {
        val session = PlanSession(
            segments = listOf(
                WallSegment(
                    start = PointMm(0.25, -1.75),
                    end = PointMm(123.456, 88.5),
                    lengthMm = 152,
                    angleDegrees = 36.3,
                    widthMm = 90,
                    growthSide = WallGrowthSide.RIGHT
                )
            ),
            notes = listOf(PlanNote("Cocina", PointMm(15.5, 8.25), 75.0, 32.0)),
            selectedStartPoint = PointMm(123.456, 88.5),
            documentUri = "content://example.documents/document/plan.dxf"
        )

        assertEquals(session, PlanSessionCodec.decode(PlanSessionCodec.encode(session)))
    }

    @Test
    fun truncatedOrUnknownVersionDraftIsRejected() {
        val valid = PlanSessionCodec.encode(PlanSession(emptyList(), emptyList(), null, null))
        assertThrows(Exception::class.java) { PlanSessionCodec.decode(valid.copyOf(6)) }
        assertThrows(IllegalArgumentException::class.java) {
            PlanSessionCodec.decode(valid.copyOf().also { it[3] = 2 })
        }
    }
}
