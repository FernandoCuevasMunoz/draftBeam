package cl.fernandocuevas.draftbeam

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanSessionCodecTest {
    @Test
    fun draftRoundTripsExactWallsNotesAndDocumentAssociation() {
        val session = PlanSession(
            segments = listOf(
                WallSegment(
                    start = PointMm(0.0, 0.0),
                    end = PointMm(2_000.0, 0.0),
                    lengthMm = 2_000,
                    angleDegrees = 0.0,
                    widthMm = 200,
                    growthSide = WallGrowthSide.RIGHT,
                    openings = listOf(
                        WindowOpening(1_200, 500, OpeningAlignment.FAR_FACE, 60)
                    )
                )
            ),
            notes = listOf(PlanNote("Cocina", PointMm(15.5, 8.25), 75.0, 32.0)),
            selectedStartPoint = PointMm(2_000.0, 0.0),
            documentUri = "content://example.documents/document/plan.dxf",
            doors = listOf(
                PlanDoor(
                    hinge = PointMm(1_000.0, 0.0),
                    latch = PointMm(1_800.0, 0.0),
                    frameWidthMm = 50,
                    depthMm = 100,
                    openAngleDegrees = 45.5,
                    swingSign = -1,
                    leafThicknessMm = 40
                )
            )
        )

        assertEquals(session, PlanSessionCodec.decode(PlanSessionCodec.encode(session)))
    }

    @Test
    fun truncatedOrUnknownVersionDraftIsRejected() {
        val valid = PlanSessionCodec.encode(PlanSession(emptyList(), emptyList(), null, null))
        assertThrows(Exception::class.java) { PlanSessionCodec.decode(valid.copyOf(6)) }
        assertThrows(IllegalArgumentException::class.java) {
            PlanSessionCodec.decode(valid.copyOf().also { it[3] = 6 })
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

    @Test
    fun versionFourDraftDropsAttachedDoorsButKeepsWindows() {
        val bytes = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(4)
                output.writeBoolean(false)
                output.writeBoolean(false)
                output.writeInt(1) // walls
                output.writeDouble(0.0); output.writeDouble(0.0)
                output.writeDouble(1_000.0); output.writeDouble(0.0)
                output.writeLong(1_000)
                output.writeDouble(0.0)
                output.writeLong(200)
                output.writeInt(WallGrowthSide.LEFT.ordinal)
                output.writeInt(2) // openings
                output.writeInt(1) // attached door of the removed design
                output.writeLong(10); output.writeLong(800)
                output.writeBoolean(true); output.writeDouble(90.0); output.writeBoolean(false)
                output.writeLong(40)
                output.writeInt(2) // window
                output.writeLong(800); output.writeLong(100)
                output.writeInt(OpeningAlignment.NEAR_FACE.ordinal)
                output.writeLong(50)
                output.writeInt(0) // notes
            }
        }.toByteArray()

        val restored = PlanSessionCodec.decode(bytes)
        val openings = restored.segments.single().openings
        assertEquals(1, openings.size)
        assertEquals(WindowOpening(800, 100, OpeningAlignment.NEAR_FACE, 50), openings.single())
        assertTrue(restored.doors.isEmpty())
        assertTrue(restored.warnings.single().contains("1 puertas"))
    }

    @Test
    fun versionThreeDraftDropsFramedDoorsButKeepsWindows() {
        val bytes = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(3)
                output.writeBoolean(false)
                output.writeBoolean(false)
                output.writeInt(1) // walls
                output.writeDouble(0.0); output.writeDouble(0.0)
                output.writeDouble(1_000.0); output.writeDouble(0.0)
                output.writeLong(1_000)
                output.writeDouble(0.0)
                output.writeLong(200)
                output.writeInt(WallGrowthSide.LEFT.ordinal)
                output.writeInt(2) // openings
                output.writeInt(1) // framed door of the removed design
                output.writeLong(10); output.writeLong(40); output.writeLong(100)
                output.writeLong(700); output.writeLong(40)
                output.writeInt(OpeningAlignment.CENTER.ordinal)
                output.writeBoolean(true); output.writeBoolean(false)
                output.writeInt(2) // window
                output.writeLong(800); output.writeLong(100)
                output.writeInt(OpeningAlignment.NEAR_FACE.ordinal)
                output.writeLong(50)
                output.writeInt(0) // notes
            }
        }.toByteArray()

        val restored = PlanSessionCodec.decode(bytes)
        val openings = restored.segments.single().openings
        assertEquals(1, openings.size)
        assertEquals(WindowOpening(800, 100, OpeningAlignment.NEAR_FACE, 50), openings.single())
        assertTrue(restored.warnings.single().contains("1 puertas"))
    }

    @Test
    fun versionTwoDraftDropsFramelessDoorsButKeepsWindows() {
        val bytes = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(2)
                output.writeBoolean(false)
                output.writeBoolean(false)
                output.writeInt(1) // walls
                output.writeDouble(0.0); output.writeDouble(0.0)
                output.writeDouble(1_000.0); output.writeDouble(0.0)
                output.writeLong(1_000)
                output.writeDouble(0.0)
                output.writeLong(200)
                output.writeInt(WallGrowthSide.LEFT.ordinal)
                output.writeInt(2) // openings
                output.writeInt(0) // legacy DOOR
                output.writeLong(10); output.writeLong(700)
                output.writeInt(OpeningAlignment.CENTER.ordinal)
                output.writeLong(0)
                output.writeBoolean(true); output.writeBoolean(false); output.writeInt(90)
                output.writeInt(1) // legacy WINDOW
                output.writeLong(800); output.writeLong(100)
                output.writeInt(OpeningAlignment.NEAR_FACE.ordinal)
                output.writeLong(50)
                output.writeBoolean(false); output.writeBoolean(false); output.writeInt(90)
                output.writeInt(0) // notes
            }
        }.toByteArray()

        val restored = PlanSessionCodec.decode(bytes)
        val openings = restored.segments.single().openings
        assertEquals(1, openings.size)
        assertEquals(WindowOpening(800, 100, OpeningAlignment.NEAR_FACE, 50), openings.single())
        assertTrue(restored.warnings.single().contains("1 puertas"))
    }
}
