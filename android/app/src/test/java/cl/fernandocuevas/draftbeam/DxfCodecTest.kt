package cl.fernandocuevas.draftbeam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DxfCodecTest {
    @Test
    fun exportAndImportPreserveAxisWallWidthAndGrowthSide() {
        val source = listOf(
            WallSegment(
                start = PointMm(1_200.0, -300.0),
                end = PointMm(2_200.0, -300.0),
                lengthMm = 1_000,
                angleDegrees = 0.0,
                widthMm = 150,
                growthSide = WallGrowthSide.LEFT
            ),
            WallSegment(
                start = PointMm(2_200.0, -300.0),
                end = PointMm(2_200.0, 700.0),
                lengthMm = 1_000,
                angleDegrees = 90.0
            )
        )

        val dxf = DxfCodec.encode(source)
        val imported = DxfCodec.decode(dxf).segments

        assertEquals(2, imported.size)
        assertEquals(source[0].start, imported[0].start)
        assertEquals(source[0].end, imported[0].end)
        assertEquals(150L, imported[0].widthMm)
        assertEquals(WallGrowthSide.LEFT, imported[0].growthSide)
        assertEquals(0L, imported[1].widthMm)
        assertTrue(DxfCodec.decode(dxf).warnings.isEmpty())
        assertTrue(dxf.contains("\n5\n")) // blue axis layer
        assertTrue(dxf.contains("\n30\n")) // orange outline layer
        assertTrue(dxf.contains("\n2\nFDXL_AXIS_0001\n70\n0\n")) // visible axis layer
        assertTrue(dxf.contains("\nHATCH\n")) // ar-conc wall pattern
        assertTrue(dxf.contains("\nar-conc\n"))
        assertTrue(dxf.contains("\n76\n1\n41\n5\n")) // predefined ar-conc at scale 5
        assertTrue(dxf.contains("\n4\n")) // DXF millimeter unit code
        assertTrue(dxf.contains("\n\$DIMTXT\n40\n100\n")) // sample mm dimension text
        assertTrue(dxf.contains("\n\$DIMASZ\n40\n50\n")) // sample dimension arrows
        assertTrue(dxf.contains("\n\$DIMDEC\n70\n0\n")) // integer measurements
        assertTrue(dxf.contains("\n\$DIMTIH\n70\n0\n"))
        assertTrue(dxf.contains("\n\$DIMTOH\n70\n0\n"))
        assertTrue(dxf.contains("\n\$DIMTXSTY\n7\nunicode\n"))
        assertEquals(2, Regex("\\nDIMENSION\\n").findAll(dxf).count())
        assertTrue(dxf.contains("\nFDXL_DIMS\n"))
        assertTrue(dxf.contains("\n1000\n")) // dimensions display their measured value
        assertTrue(dxf.contains("\n2\nunicode\n")) // font style is defined in STYLE table
        assertTrue(dxf.contains("\n2\n*D1\n")) // anonymous dimension block
        assertTrue(dxf.contains("\n2\nBLOCK_RECORD\n"))
    }

    @Test
    fun externalLineWithMetersIsConvertedToMillimeters() {
        val dxf = """
            0
            SECTION
            2
            HEADER
            9
            ${'$'}INSUNITS
            70
            6
            0
            ENDSEC
            0
            SECTION
            2
            ENTITIES
            0
            LINE
            8
            0
            10
            1.25
            20
            -2
            30
            0
            11
            2.25
            21
            -2
            31
            0
            0
            ENDSEC
            0
            EOF
        """.trimIndent()

        val segment = DxfCodec.decode(dxf).segments.single()

        assertEquals(PointMm(1_250.0, -2_000.0), segment.start)
        assertEquals(PointMm(2_250.0, -2_000.0), segment.end)
        assertEquals(1_000L, segment.lengthMm)
    }

    @Test
    fun externalClosedLwPolylineBecomesEditableEdges() {
        val dxf = """
            0
            SECTION
            2
            ENTITIES
            0
            LWPOLYLINE
            8
            0
            90
            4
            70
            1
            10
            0
            20
            0
            10
            1000
            20
            0
            10
            1000
            20
            500
            10
            0
            20
            500
            0
            ENDSEC
            0
            EOF
        """.trimIndent()

        val imported = DxfCodec.decode(dxf)
        val segments = imported.segments

        assertEquals(4, segments.size)
        assertEquals(PointMm(0.0, 500.0), segments[2].end)
        assertEquals(PointMm(0.0, 0.0), segments.last().end)
        assertTrue(segments.all { it.widthMm == 0L })
        assertTrue(imported.warnings.any { it.contains("ancho de muro") })
    }
}
