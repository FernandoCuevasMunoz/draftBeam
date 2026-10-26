package cl.fernandocuevas.draftbeam

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
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
                    growthSide = WallGrowthSide.RIGHT,
                    openings = listOf(WallOpening(OpeningKind.DOOR, 5, 90,
                        OpeningAlignment.CENTER, hingeAtStart = false))
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
            PlanSessionCodec.decode(valid.copyOf().also { it[3] = 3 })
        }
    }

    @Test
    fun oldVersionOneDraftRemainsRecoverableWithoutOpenings() {
        val bytes = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(1)
                output.writeBoolean(false) // no URI
                output.writeBoolean(false) // no selected point
                output.writeInt(1) // walls
                output.writeDouble(0.0); output.writeDouble(0.0)
                output.writeDouble(1_000.0); output.writeDouble(0.0)
                output.writeLong(1_000)
                output.writeDouble(0.0)
                output.writeLong(200)
                output.writeInt(WallGrowthSide.LEFT.ordinal)
                output.writeInt(0) // notes
            }
        }.toByteArray()

        val restored = PlanSessionCodec.decode(bytes)
        assertEquals(1, restored.segments.size)
        assertEquals(emptyList<WallOpening>(), restored.segments.single().openings)
    }
}
