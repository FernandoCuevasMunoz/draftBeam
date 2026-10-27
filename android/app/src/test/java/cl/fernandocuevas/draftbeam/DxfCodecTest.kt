package cl.fernandocuevas.draftbeam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DxfCodecTest {
    @Test
    fun doorsInExistingGapsAndWindowsRoundTripWithoutExtraWalls() {
        val door = PlanDoor(
            hinge = PointMm(1_000.0, 0.0),
            latch = PointMm(1_800.0, 0.0),
            frameWidthMm = 50,
            depthMm = 100,
            openAngleDegrees = 45.0,
            swingSign = -1,
            leafThicknessMm = 40
        )
        val window = WindowOpening(1_200, 1_000, OpeningAlignment.FAR_FACE, 60)
        val wall = WallSegment(
            PointMm(0.0, 0.0), PointMm(4_000.0, 0.0), 4_000, 0.0, 200,
            WallGrowthSide.LEFT, listOf(window)
        )

        val dxf = DxfCodec.encode(listOf(wall), doors = listOf(door))
        val imported = DxfCodec.decode(dxf)

        assertEquals(listOf(wall), imported.segments)
        assertEquals(listOf(door), imported.doors)
        assertTrue(imported.warnings.isEmpty())
        assertTrue(dxf.contains("\n1001\nDRAFTBEAM\n1000\nDBO4;DOOR;1000;0;1800;0;50;100;45;-1;40"))
        assertTrue(dxf.contains("\nFDXL_DOORS\n"))
        assertEquals(1, entitiesOfType(dxf, "ARC").count { arc -> arc[8] == "FDXL_DOORS" && arc[40] == "700" })
        // Two frame blocks plus the open leaf.
        assertEquals(3, entitiesOfType(dxf, "LWPOLYLINE").count { polyline ->
            polyline[8] == "FDXL_DOORS" && polyline[90] == "4"
        })
        // A door whose metadata no longer fits its own gap is dropped with a notice.
        val corrupted = DxfCodec.decode(
            dxf.replace("DBO4;DOOR;1000;0;1800;0;50;100", "DBO4;DOOR;1000;0;1010;0;50;100")
        )
        assertTrue(corrupted.doors.isEmpty())
        assertTrue(corrupted.warnings.any { it.contains("1 puertas") })
    }

    @Test
    fun doorsOfTheRemovedDesignsAreDroppedAndReported() {
        val window = WindowOpening(1_200, 1_000, OpeningAlignment.FAR_FACE, 60)
        val wall = WallSegment(
            PointMm(0.0, 0.0), PointMm(4_000.0, 0.0), 4_000, 0.0, 200,
            WallGrowthSide.LEFT, listOf(window)
        )
        val dxf = DxfCodec.encode(listOf(wall)).replace(
            "DBO2;WINDOW;1200;1000;60;FAR_FACE",
            "DBO2;WINDOW;1200;1000;60;FAR_FACE" + "\n1000\nDBO3;DOOR;0;800;0;90;0;40"
        )

        val imported = DxfCodec.decode(dxf)

        assertEquals(listOf(window), imported.segments.single().openings)
        assertTrue(imported.doors.isEmpty())
        assertTrue(imported.warnings.any { it.contains("1 puertas") })
    }

    @Test
    fun alignedDimensionUsesIntegerMillimetersAndSitsBesideContinuousDimensionLine() {
        val segment = WallSegment(
            start = PointMm(0.0, 0.0),
            end = PointMm(1_234.5, 0.0),
            lengthMm = 1_235,
            angleDegrees = 0.0,
            growthSide = WallGrowthSide.LEFT
        )

        val dxf = DxfCodec.encode(listOf(segment))
        val dimension = entitiesOfType(dxf, "DIMENSION").single()
        val text = entitiesOfType(dxf, "MTEXT").single()
        val lines = entitiesOfType(dxf, "LINE")

        assertTrue(dxf.contains("\n\$DIMDEC\n70\n0\n"))
        assertTrue(dxf.contains("\n271\n0\n")) // DIMSTYLE linear precision
        assertEquals("1235", dimension[1]) // explicit integer text override, without grouping
        assertEquals("1235", text[1])
        assertEquals("617.25", text[10])
        assertEquals("360", text[20]) // 50 mm half-height + 10 mm clearance from y=300 dimension line
        assertEquals("0", text[50]) // keep the text aligned with the horizontal dimension
        assertEquals("5", text[71]) // centered attachment retained
        assertTrue(lines.any { line ->
            line[10] == "0" && line[20] == "300" && line[11] == "1234.5" && line[21] == "300"
        }) // dimension line remains continuous behind/beside the label
    }

    @Test
    fun diagonalDimensionTextKeepsItsAngleAndMovesBesideTheDimensionLine() {
        val segment = WallSegment(
            start = PointMm(0.0, 0.0),
            end = PointMm(1_000.0, 1_000.0),
            lengthMm = 1_414,
            angleDegrees = 45.0,
            growthSide = WallGrowthSide.LEFT
        )

        val text = entitiesOfType(DxfCodec.encode(listOf(segment)), "MTEXT").single()
        val textPosition = PointMm(text.getValue(10).toDouble(), text.getValue(20).toDouble())
        val rootTwo = kotlin.math.sqrt(2.0)
        val lineMidpoint = PointMm(500.0 - 300.0 / rootTwo, 500.0 + 300.0 / rootTwo)
        val distanceFromDimensionLine =
            ((textPosition.x - lineMidpoint.x) * -1.0 + (textPosition.y - lineMidpoint.y)) / rootTwo

        assertEquals(45.0, text.getValue(50).toDouble(), 0.000001)
        assertEquals(60.0, distanceFromDimensionLine, 0.000001)
    }

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
    fun notesRoundTripThroughStandardDxfTextEntities() {
        val note = PlanNote("Kitchen note", PointMm(-250.0, 800.0))
        val dxf = DxfCodec.encode(emptyList(), listOf(note))

        assertTrue(dxf.contains("\nTEXT\n"))
        assertEquals(listOf(note), DxfCodec.decode(dxf).notes)
        assertTrue(DxfCodec.decode(dxf).warnings.isEmpty())
    }

    private fun entitiesOfType(dxf: String, type: String): List<Map<Int, String>> {
        val lines = dxf.trim().lines()
        val entities = mutableListOf<Map<Int, String>>()
        var index = 0
        while (index + 1 < lines.size) {
            if (lines[index] != "0" || lines[index + 1] != type) {
                index++
                continue
            }
            index += 2
            val fields = linkedMapOf<Int, String>()
            while (index + 1 < lines.size && lines[index] != "0") {
                lines[index].toIntOrNull()?.let { code -> fields[code] = lines[index + 1] }
                index += 2
            }
            entities += fields
        }
        return entities
    }

    @Test
    fun externalTextWithMetersConvertsPositionAndHeightToMillimeters() {
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
            TEXT
            8
            0
            10
            0.25
            20
            0.8
            30
            0
            40
            0.1
            1
            Kitchen
            50
            30
            0
            ENDSEC
            0
            EOF
        """.trimIndent()

        val imported = DxfCodec.decode(dxf)

        assertEquals(listOf(PlanNote("Kitchen", PointMm(250.0, 800.0), 100.0, 30.0)), imported.notes)
        assertTrue(imported.warnings.isEmpty())
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
