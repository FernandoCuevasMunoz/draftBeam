package cl.fernandocuevas.draftbeam

import java.math.BigDecimal
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToLong

data class DxfImportResult(
    val segments: List<WallSegment>,
    val notes: List<PlanNote>,
    val warnings: List<String>
)

/** Small ASCII DXF R2000 codec using standard 2D geometry, hatches, and dimensions. */
object DxfCodec {
    private const val MAX_FILE_CHARS = 16 * 1024 * 1024
    private const val MAX_ENTITIES = 100_000
    private const val AXIS_LAYER_PREFIX = "FDXL_AXIS_"
    private const val WALL_LAYER_PREFIX = "FDXL_WALL_"

    fun encode(segments: List<WallSegment>, notes: List<PlanNote> = emptyList()): String = buildString {
        val dimensions = segments.mapIndexedNotNull { index, segment ->
            dimensionGeometry(index, segment)
        }
        val blockNames = listOf("*Model_Space", "*Paper_Space") + dimensions.map { it.blockName }
        val layers = buildList {
            add("0" to 7)
            if (dimensions.isNotEmpty()) add(DIMENSION_LAYER to 7)
            if (notes.isNotEmpty()) add(NOTE_LAYER to 7)
            segments.forEachIndexed { index, segment ->
                add(axisLayer(index) to 5) // ACI blue
                if (segment.widthMm > 0L) add(wallLayer(index) to 30) // ACI orange
            }
        }

        pair(0, "SECTION")
        pair(2, "HEADER")
        pair(9, "\$ACADVER")
        pair(1, "AC1015")
        pair(9, "\$INSUNITS")
        pair(70, "4") // millimeters
        // Match the LibreCAD drawing preferences saved in the user's sample.
        pair(9, "\$DIMTXT")
        pair(40, number(DIMENSION_TEXT_HEIGHT_MM))
        pair(9, "\$DIMASZ")
        pair(40, number(DIMENSION_ARROW_SIZE_MM))
        pair(9, "\$DIMGAP")
        pair(40, number(DIMENSION_TEXT_GAP_MM))
        pair(9, "\$DIMEXO")
        pair(40, number(DIMENSION_EXTENSION_OFFSET_MM))
        pair(9, "\$DIMEXE")
        pair(40, number(DIMENSION_EXTENSION_OVERSHOOT_MM))
        pair(9, "\$DIMSCALE")
        pair(40, "1")
        pair(9, "\$DIMLFAC")
        pair(40, "1")
        pair(9, "\$DIMDEC")
        pair(70, "0")
        pair(9, "\$DIMTAD")
        pair(70, "1")
        pair(9, "\$DIMTIH")
        pair(70, "0") // keep aligned text parallel to the dimension line inside
        pair(9, "\$DIMTOH")
        pair(70, "0") // and outside the extension lines
        pair(9, "\$DIMTXSTY")
        pair(7, DIMENSION_TEXT_STYLE)
        pair(9, "\$TEXTSTYLE")
        pair(7, "Standard")
        pair(0, "ENDSEC")

        pair(0, "SECTION")
        pair(2, "TABLES")
        pair(0, "TABLE")
        pair(2, "LTYPE")
        pair(70, "1")
        pair(0, "LTYPE")
        pair(100, "AcDbSymbolTableRecord")
        pair(100, "AcDbLinetypeTableRecord")
        pair(2, "CONTINUOUS")
        pair(70, "0")
        pair(3, "Solid line")
        pair(72, "65")
        pair(73, "0")
        pair(40, "0.0")
        pair(0, "ENDTAB")
        pair(0, "TABLE")
        pair(2, "STYLE")
        pair(70, "2")
        pair(0, "STYLE")
        pair(100, "AcDbSymbolTableRecord")
        pair(100, "AcDbTextStyleTableRecord")
        pair(2, "Standard")
        pair(70, "0")
        pair(40, "0")
        pair(41, "1")
        pair(50, "0")
        pair(71, "0")
        pair(42, "1")
        pair(3, "txt")
        pair(4, "")
        pair(0, "STYLE")
        pair(100, "AcDbSymbolTableRecord")
        pair(100, "AcDbTextStyleTableRecord")
        pair(2, DIMENSION_TEXT_STYLE)
        pair(70, "0")
        pair(40, "0")
        pair(41, "1")
        pair(50, "0")
        pair(71, "0")
        pair(42, "1")
        pair(3, "unicode")
        pair(4, "")
        pair(0, "ENDTAB")
        pair(0, "TABLE")
        pair(2, "LAYER")
        pair(70, layers.size.toString())
        layers.forEach { (name, color) ->
            pair(0, "LAYER")
            pair(100, "AcDbSymbolTableRecord")
            pair(100, "AcDbLayerTableRecord")
            pair(2, name)
            pair(70, "0")
            pair(62, color.toString())
            pair(6, "CONTINUOUS")
        }
        pair(0, "ENDTAB")
        pair(0, "TABLE")
        pair(2, "DIMSTYLE")
        pair(70, "1")
        pair(0, "DIMSTYLE")
        pair(100, "AcDbSymbolTableRecord")
        pair(100, "AcDbDimStyleTableRecord")
        pair(2, "Standard")
        pair(70, "0")
        pair(3, "")
        pair(4, "")
        pair(40, "1")
        pair(41, number(DIMENSION_ARROW_SIZE_MM))
        pair(42, number(DIMENSION_EXTENSION_OFFSET_MM))
        pair(43, "0.38")
        pair(44, number(DIMENSION_EXTENSION_OVERSHOOT_MM))
        pair(140, number(DIMENSION_TEXT_HEIGHT_MM))
        pair(147, number(DIMENSION_TEXT_GAP_MM))
        pair(71, "0")
        pair(72, "0")
        pair(73, "0")
        pair(74, "1")
        pair(77, "0")
        pair(78, "1")
        pair(271, DIMENSION_DECIMALS.toString()) // linear precision: whole millimeters
        pair(0, "ENDTAB")
        pair(0, "TABLE")
        pair(2, "BLOCK_RECORD")
        pair(70, blockNames.size.toString())
        blockNames.forEach { name ->
            pair(0, "BLOCK_RECORD")
            pair(100, "AcDbSymbolTableRecord")
            pair(100, "AcDbBlockTableRecord")
            pair(2, name)
            pair(70, "0")
        }
        pair(0, "ENDTAB")
        pair(0, "ENDSEC")

        pair(0, "SECTION")
        pair(2, "BLOCKS")
        appendBlock("*Model_Space", 0)
        appendBlock("*Paper_Space", 0)
        dimensions.forEach { geometry ->
            appendBlock(geometry.blockName, 1) {
                appendDimensionGraphics(geometry)
            }
        }
        pair(0, "ENDSEC")

        pair(0, "SECTION")
        pair(2, "ENTITIES")
        val footprints = PlanGeometry.wallFootprints(segments)
        segments.forEachIndexed { index, segment ->
            if (segment.widthMm > 0L) {
                footprints[index].forEach { polygon ->
                    if (polygon.size >= 3) {
                        appendArConcHatch(wallLayer(index), polygon)
                        pair(0, "LWPOLYLINE")
                        pair(100, "AcDbEntity")
                        pair(8, wallLayer(index))
                        pair(100, "AcDbPolyline")
                        pair(90, polygon.size.toString())
                        pair(70, "1") // closed outline, real wall geometry
                        polygon.forEach { point ->
                            pair(10, number(point.x))
                            pair(20, number(point.y))
                        }
                    }
                }
            }
        }
        // Write measured axes last so they remain visible above the wall hatch.
        segments.forEachIndexed { index, segment ->
            pair(0, "LINE")
            pair(100, "AcDbEntity")
            pair(8, axisLayer(index))
            pair(100, "AcDbLine")
            pair(10, number(segment.start.x))
            pair(20, number(segment.start.y))
            pair(30, "0")
            pair(11, number(segment.end.x))
            pair(21, number(segment.end.y))
            pair(31, "0")
        }
        dimensions.forEach { geometry -> appendAlignedDimension(geometry) }
        notes.forEach { note ->
            pair(0, "TEXT")
            pair(100, "AcDbEntity")
            pair(8, NOTE_LAYER)
            pair(100, "AcDbText")
            pair(10, number(note.position.x))
            pair(20, number(note.position.y))
            pair(30, "0")
            pair(40, number(note.heightMm))
            pair(1, note.text)
            pair(50, number(note.rotationDegrees))
            pair(7, "Standard")
        }
        pair(0, "ENDSEC")
        pair(0, "EOF")
    }

    fun decode(contents: String): DxfImportResult {
        require(contents.length <= MAX_FILE_CHARS) { "El archivo DXF supera el límite de 16 MB." }
        val pairs = parsePairs(contents)
        require(pairs.contains(DxfPair(0, "SECTION")) && pairs.contains(DxfPair(0, "EOF"))) {
            "El archivo no parece ser un DXF válido."
        }
        val unitScale = unitScaleToMillimeters(pairs)
        val entities = parseEntities(pairs)
        require(entities.size <= MAX_ENTITIES) { "El DXF contiene demasiadas entidades." }

        val axesById = linkedMapOf<Int, DxfLine>()
        val outlinesById = mutableMapOf<Int, MutableList<DxfPolyline>>()
        val genericEntities = mutableListOf<DxfEntity>()
        var ignored = 0
        var decomposedPolylines = 0

        entities.forEach { entity ->
            when (entity) {
                is DxfLine -> {
                    val id = numberedLayerId(entity.layer, AXIS_LAYER_PREFIX)
                    if (id == null) genericEntities += entity
                    else axesById[id] = entity
                }
                is DxfPolyline -> {
                    val id = numberedLayerId(entity.layer, WALL_LAYER_PREFIX)
                    if (id == null) genericEntities += entity
                    else outlinesById.getOrPut(id) { mutableListOf() }.add(entity)
                }
                is DxfText -> genericEntities += entity
                is DxfUnsupported -> if (!isAppGeneratedLayer(entity.layer)) ignored++
            }
        }

        val result = mutableListOf<WallSegment>()
        val notes = mutableListOf<PlanNote>()
        axesById.toSortedMap().forEach { (id, axis) ->
            val line = toMillimeterLine(axis, unitScale) ?: run {
                ignored++
                return@forEach
            }
            val widthAndSide = inferWallWidth(line, outlinesById[id].orEmpty(), unitScale)
            result += line.toWallSegment(widthAndSide.first, widthAndSide.second)
        }

        genericEntities.forEach { entity ->
            when (entity) {
                is DxfLine -> toMillimeterLine(entity, unitScale)?.toWallSegment(0L, WallGrowthSide.LEFT)
                    ?.let(result::add) ?: run { ignored++ }
                is DxfPolyline -> {
                    if (entity.curved || entity.nonPlanar) {
                        ignored++
                    } else {
                        if (entity.points.size >= 2) decomposedPolylines++
                        val points = entity.points.map { point -> point.scale(unitScale) }
                        val edgeCount = if (entity.closed) points.size else points.size - 1
                        for (index in 0 until edgeCount.coerceAtLeast(0)) {
                            val start = points[index]
                            val end = points[(index + 1) % points.size]
                            val line = DxfLine("0", start, end, false)
                            toMillimeterLine(line, 1.0)?.toWallSegment(0L, WallGrowthSide.LEFT)
                                ?.let(result::add) ?: run { ignored++ }
                        }
                    }
                }
                is DxfText -> {
                    val position = entity.position.scale(unitScale)
                    val heightMm = entity.heightMm * unitScale
                    val isInRange = abs(position.x) <= MAX_COORDINATE_MM && abs(position.y) <= MAX_COORDINATE_MM &&
                        heightMm <= MAX_COORDINATE_MM
                    if (!isInRange) {
                        ignored++
                    } else {
                        try {
                            notes += PlanNote(entity.text, position, heightMm, entity.rotationDegrees)
                        } catch (_: IllegalArgumentException) {
                            ignored++
                        }
                    }
                }
                is DxfUnsupported -> if (!isAppGeneratedLayer(entity.layer)) ignored++
            }
        }

        val warnings = buildList {
            if (ignored > 0) add("Se omitieron $ignored entidades no compatibles o inválidas.")
            if (decomposedPolylines > 0) {
                add("Se descompusieron $decomposedPolylines polilíneas externas en líneas editables; no se puede deducir su ancho de muro automáticamente.")
            }
            if (pairs.none { it.code == 9 && it.value.equals("\$INSUNITS", ignoreCase = true) }) {
                add("El DXF no declara unidades; se interpretaron las coordenadas como milímetros.")
            }
        }
        return DxfImportResult(result, notes, warnings)
    }

    private fun parsePairs(contents: String): List<DxfPair> {
        val lines = contents.removePrefix("\uFEFF").trimEnd('\r', '\n').lineSequence().toList()
        require(lines.size % 2 == 0) { "El DXF está incompleto: faltan valores de grupo." }
        return lines.chunked(2).mapIndexed { index, pair ->
            val code = pair[0].trim().toIntOrNull()
                ?: throw IllegalArgumentException("Código DXF inválido en la línea ${index * 2 + 1}.")
            DxfPair(code, pair[1].trim())
        }
    }

    private fun unitScaleToMillimeters(pairs: List<DxfPair>): Double {
        val unitsIndex = pairs.indexOfFirst { it.code == 9 && it.value.equals("\$INSUNITS", ignoreCase = true) }
        if (unitsIndex < 0) return 1.0
        val unitCode = pairs.drop(unitsIndex + 1).firstOrNull { it.code == 70 }?.value?.toIntOrNull()
            ?: throw IllegalArgumentException("El DXF declara unidades inválidas.")
        return when (unitCode) {
            0, 4 -> 1.0 // unitless is assumed millimeters
            1 -> 25.4 // inches
            2 -> 304.8 // feet
            3 -> 1_609_344.0 // miles
            5 -> 10.0 // centimeters
            6 -> 1_000.0 // meters
            7 -> 1_000_000.0 // kilometers
            else -> throw IllegalArgumentException("Unidad DXF no compatible (código $unitCode).")
        }
    }

    private fun parseEntities(pairs: List<DxfPair>): List<DxfEntity> {
        val entities = mutableListOf<DxfEntity>()
        var inEntities = false
        var index = 0
        while (index < pairs.size) {
            val pair = pairs[index]
            if (pair.code == 0 && pair.value.equals("SECTION", ignoreCase = true) && pairs.getOrNull(index + 1)?.let {
                    it.code == 2 && it.value.equals("ENTITIES", ignoreCase = true)
                } == true
            ) {
                inEntities = true
                index += 2
                continue
            }
            if (pair.code == 0 && pair.value.equals("ENDSEC", ignoreCase = true)) {
                inEntities = false
                index++
                continue
            }
            if (!inEntities || pair.code != 0) {
                index++
                continue
            }

            val type = pair.value.uppercase(Locale.ROOT)
            index++
            when (type) {
                "LINE", "LWPOLYLINE" -> {
                    val end = nextEntityIndex(pairs, index)
                    val fields = pairs.subList(index, end)
                    entities += if (type == "LINE") parseLine(fields) else parseLwPolyline(fields)
                    index = end
                }
                "TEXT" -> {
                    val end = nextEntityIndex(pairs, index)
                    val fields = pairs.subList(index, end)
                    entities += parseText(fields)
                    index = end
                }
                "POLYLINE" -> {
                    val headerEnd = nextEntityIndex(pairs, index)
                    val header = pairs.subList(index, headerEnd)
                    val vertices = mutableListOf<List<DxfPair>>()
                    index = headerEnd
                    while (pairs.getOrNull(index)?.let { it.code == 0 && it.value.equals("VERTEX", true) } == true) {
                        val vertexEnd = nextEntityIndex(pairs, index + 1)
                        vertices += pairs.subList(index + 1, vertexEnd)
                        index = vertexEnd
                    }
                    if (pairs.getOrNull(index)?.let { it.code == 0 && it.value.equals("SEQEND", true) } == true) {
                        index = nextEntityIndex(pairs, index + 1)
                    }
                    entities += parseClassicPolyline(header, vertices)
                }
                "HATCH" -> {
                    val end = nextEntityIndex(pairs, index)
                    val fields = pairs.subList(index, end)
                    entities += DxfUnsupported(type, fields.firstOrNull { it.code == 8 }?.value ?: "0")
                    index = end
                }
                "ENDSEC", "EOF" -> Unit
                else -> {
                    val end = nextEntityIndex(pairs, index)
                    val fields = pairs.subList(index, end)
                    entities += DxfUnsupported(type, fields.firstOrNull { it.code == 8 }?.value ?: "0")
                    index = end
                }
            }
            require(entities.size <= MAX_ENTITIES) { "El DXF contiene demasiadas entidades." }
        }
        return entities
    }

    private fun nextEntityIndex(pairs: List<DxfPair>, from: Int): Int {
        var index = from
        while (index < pairs.size && pairs[index].code != 0) index++
        return index
    }

    private fun parseText(fields: List<DxfPair>): DxfText {
        val layer = fields.firstOrNull { it.code == 8 }?.value ?: "0"
        val text = fields.firstOrNull { it.code == 1 }?.value
            ?: throw IllegalArgumentException("Falta el contenido de una entidad de texto DXF.")
        return DxfText(
            layer = layer,
            text = text,
            position = PointMm(fields.number(10), fields.number(20)),
            heightMm = fields.numberOrNull(40) ?: 100.0,
            rotationDegrees = fields.numberOrNull(50) ?: 0.0
        )
    }

    private fun parseLine(fields: List<DxfPair>): DxfLine {
        val layer = fields.firstOrNull { it.code == 8 }?.value ?: "0"
        val start = PointMm(fields.number(10), fields.number(20))
        val end = PointMm(fields.number(11), fields.number(21))
        val nonPlanar = abs(fields.numberOrNull(30) ?: 0.0) > 1e-6 || abs(fields.numberOrNull(31) ?: 0.0) > 1e-6
        return DxfLine(layer, start, end, nonPlanar)
    }

    private fun parseLwPolyline(fields: List<DxfPair>): DxfPolyline {
        val layer = fields.firstOrNull { it.code == 8 }?.value ?: "0"
        val closed = (fields.firstOrNull { it.code == 70 }?.value?.toIntOrNull() ?: 0) and 1 != 0
        val points = pointsFromCoordinatePairs(fields)
        val curved = fields.any { it.code == 42 && (it.value.toDoubleOrNull() ?: 0.0) != 0.0 }
        val nonPlanar = abs(fields.numberOrNull(38) ?: 0.0) > 1e-6
        return DxfPolyline(layer, points, closed, curved, nonPlanar)
    }

    private fun parseClassicPolyline(header: List<DxfPair>, vertices: List<List<DxfPair>>): DxfPolyline {
        val layer = header.firstOrNull { it.code == 8 }?.value ?: "0"
        val closed = (header.firstOrNull { it.code == 70 }?.value?.toIntOrNull() ?: 0) and 1 != 0
        val points = vertices.map { vertex -> PointMm(vertex.number(10), vertex.number(20)) }
        val curved = vertices.any { vertex -> vertex.any { it.code == 42 && (it.value.toDoubleOrNull() ?: 0.0) != 0.0 } }
        val nonPlanar = vertices.any { vertex -> abs(vertex.numberOrNull(30) ?: 0.0) > 1e-6 }
        return DxfPolyline(layer, points, closed, curved, nonPlanar)
    }

    private fun pointsFromCoordinatePairs(fields: List<DxfPair>): List<PointMm> {
        val points = mutableListOf<PointMm>()
        var x: Double? = null
        fields.forEach { pair ->
            when (pair.code) {
                10 -> x = pair.value.toDoubleOrNull()
                    ?: throw IllegalArgumentException("Coordenada X inválida en polilínea DXF.")
                20 -> {
                    val coordinateX = x
                        ?: throw IllegalArgumentException("La polilínea DXF tiene una coordenada Y sin X.")
                    val coordinateY = pair.value.toDoubleOrNull()
                        ?: throw IllegalArgumentException("Coordenada Y inválida en polilínea DXF.")
                    points += PointMm(coordinateX, coordinateY)
                    x = null
                }
            }
        }
        require(x == null) { "La polilínea DXF termina con una coordenada X incompleta." }
        return points
    }

    private fun inferWallWidth(
        axis: DxfLine,
        outlines: List<DxfPolyline>,
        unitScale: Double
    ): Pair<Long, WallGrowthSide> {
        val dx = axis.end.x - axis.start.x
        val dy = axis.end.y - axis.start.y
        val length = hypot(dx, dy)
        if (length <= 0.0) return 0L to WallGrowthSide.LEFT
        val offsets = outlines.asSequence()
            .filter { it.closed && !it.curved && !it.nonPlanar }
            .flatMap { it.points.asSequence() }
            .map { point -> (dx * (point.y - axis.start.y) - dy * (point.x - axis.start.x)) / length * unitScale }
            .filter { abs(it) > 1e-6 }
            .toList()
        val farthestOffset = offsets.maxByOrNull(::abs) ?: return 0L to WallGrowthSide.LEFT
        return abs(farthestOffset).roundToLong() to if (farthestOffset > 0.0) WallGrowthSide.LEFT else WallGrowthSide.RIGHT
    }

    private fun toMillimeterLine(line: DxfLine, scale: Double): DxfLine? {
        if (line.nonPlanar) return null
        val start = line.start.scale(scale)
        val end = line.end.scale(scale)
        if (hypot(end.x - start.x, end.y - start.y) < 1e-6) return null
        require(listOf(start.x, start.y, end.x, end.y).all { it.isFinite() && abs(it) <= MAX_COORDINATE_MM }) {
            "El DXF contiene coordenadas fuera del rango permitido."
        }
        return DxfLine(line.layer, start, end, false)
    }

    private fun DxfLine.toWallSegment(widthMm: Long, growthSide: WallGrowthSide): WallSegment {
        val dx = end.x - start.x
        val dy = end.y - start.y
        val length = hypot(dx, dy)
        return WallSegment(
            start = start,
            end = end,
            lengthMm = length.roundToLong().coerceAtLeast(1L),
            angleDegrees = PlanGeometry.normalizeAngle(Math.toDegrees(atan2(dy, dx))),
            widthMm = widthMm,
            growthSide = growthSide
        )
    }

    private fun numberedLayerId(layer: String, prefix: String): Int? {
        if (!layer.startsWith(prefix, ignoreCase = true)) return null
        return layer.substring(prefix.length).toIntOrNull()
    }

    private fun isAppGeneratedLayer(layer: String): Boolean =
        layer.startsWith(WALL_LAYER_PREFIX, ignoreCase = true) ||
            layer.equals(DIMENSION_LAYER, ignoreCase = true)

    private fun axisLayer(index: Int) = "${AXIS_LAYER_PREFIX}${(index + 1).toString().padStart(4, '0')}"
    private fun wallLayer(index: Int) = "${WALL_LAYER_PREFIX}${(index + 1).toString().padStart(4, '0')}"

    private fun number(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    private fun StringBuilder.pair(code: Int, value: String) {
        append(code).append('\n').append(value).append('\n')
    }

    private fun StringBuilder.appendArConcHatch(layer: String, polygon: List<PointMm>) {
        pair(0, "HATCH")
        pair(100, "AcDbEntity")
        pair(8, layer)
        pair(100, "AcDbHatch")
        pair(10, "0")
        pair(20, "0")
        pair(30, "0")
        pair(210, "0")
        pair(220, "0")
        pair(230, "1")
        pair(2, "ar-conc")
        pair(70, "0")
        pair(71, "0")
        pair(91, "1")
        pair(92, "3") // external polyline boundary
        pair(72, "0")
        pair(73, "1")
        pair(93, polygon.size.toString())
        polygon.forEach { point ->
            pair(10, number(point.x))
            pair(20, number(point.y))
        }
        pair(97, "0")
        pair(75, "0")
        pair(76, "1") // predefined LibreCAD ar-conc pattern
        pair(41, number(HATCH_SCALE))
        pair(78, "0")
        pair(98, "0")
    }

    private fun dimensionGeometry(index: Int, segment: WallSegment): DimensionGeometry? {
        val dx = segment.end.x - segment.start.x
        val dy = segment.end.y - segment.start.y
        val length = hypot(dx, dy)
        if (length <= 0.0) return null
        val normalX = -dy / length
        val normalY = dx / length
        val side = if (segment.growthSide == WallGrowthSide.LEFT) 1.0 else -1.0
        val offset = maxOf(DIMENSION_OFFSET_MM, segment.widthMm.toDouble() + DIMENSION_OFFSET_MM) * side
        val lineStart = PointMm(segment.start.x + normalX * offset, segment.start.y + normalY * offset)
        val lineEnd = PointMm(segment.end.x + normalX * offset, segment.end.y + normalY * offset)
        var textAngle = Math.toDegrees(atan2(dy, dx))
        if (textAngle > 90.0 || textAngle < -90.0) textAngle += 180.0
        return DimensionGeometry(
            blockName = "*D${index + 1}",
            start = segment.start,
            end = segment.end,
            lineStart = lineStart,
            lineEnd = lineEnd,
            midpoint = PointMm((lineStart.x + lineEnd.x) / 2.0, (lineStart.y + lineEnd.y) / 2.0),
            length = length,
            offset = offset,
            unitX = dx / length,
            unitY = dy / length,
            normalX = normalX,
            normalY = normalY,
            textAngle = textAngle
        )
    }

    private fun StringBuilder.appendAlignedDimension(geometry: DimensionGeometry) {
        pair(0, "DIMENSION")
        pair(100, "AcDbEntity")
        pair(8, DIMENSION_LAYER)
        pair(100, "AcDbDimension")
        pair(2, geometry.blockName)
        pair(10, number(geometry.midpoint.x))
        pair(20, number(geometry.midpoint.y))
        pair(30, "0")
        pair(11, number(geometry.midpoint.x))
        pair(21, number(geometry.midpoint.y))
        pair(31, "0")
        pair(1, String.format(Locale.US, "%.0f", geometry.length))
        pair(70, "33") // aligned dimension with an anonymous display block
        pair(3, "Standard")
        pair(100, "AcDbAlignedDimension")
        pair(13, number(geometry.start.x))
        pair(23, number(geometry.start.y))
        pair(33, "0")
        pair(14, number(geometry.end.x))
        pair(24, number(geometry.end.y))
        pair(34, "0")
        pair(42, number(geometry.length))
        pair(210, "0")
        pair(220, "0")
        pair(230, "1")
    }

    private fun StringBuilder.appendBlock(name: String, flags: Int, contents: StringBuilder.() -> Unit = {}) {
        pair(0, "BLOCK")
        pair(100, "AcDbEntity")
        pair(8, "0")
        pair(100, "AcDbBlockBegin")
        pair(2, name)
        pair(70, flags.toString())
        pair(10, "0")
        pair(20, "0")
        pair(30, "0")
        pair(3, name)
        pair(1, "")
        contents()
        pair(0, "ENDBLK")
        pair(100, "AcDbEntity")
        pair(8, "0")
        pair(100, "AcDbBlockEnd")
    }

    private fun StringBuilder.appendDimensionGraphics(geometry: DimensionGeometry) {
        val offsetDirection = if (geometry.offset >= 0.0) 1.0 else -1.0
        val extensionStart = PointMm(
            geometry.start.x + geometry.normalX * (geometry.offset - offsetDirection * DIMENSION_EXTENSION_OFFSET_MM),
            geometry.start.y + geometry.normalY * (geometry.offset - offsetDirection * DIMENSION_EXTENSION_OFFSET_MM)
        )
        val extensionEnd = PointMm(
            geometry.end.x + geometry.normalX * (geometry.offset - offsetDirection * DIMENSION_EXTENSION_OFFSET_MM),
            geometry.end.y + geometry.normalY * (geometry.offset - offsetDirection * DIMENSION_EXTENSION_OFFSET_MM)
        )
        val extensionStartBeyond = PointMm(
            geometry.lineStart.x + geometry.normalX * offsetDirection * DIMENSION_EXTENSION_OVERSHOOT_MM,
            geometry.lineStart.y + geometry.normalY * offsetDirection * DIMENSION_EXTENSION_OVERSHOOT_MM
        )
        val extensionEndBeyond = PointMm(
            geometry.lineEnd.x + geometry.normalX * offsetDirection * DIMENSION_EXTENSION_OVERSHOOT_MM,
            geometry.lineEnd.y + geometry.normalY * offsetDirection * DIMENSION_EXTENSION_OVERSHOOT_MM
        )
        appendBlockLine(extensionStart, extensionStartBeyond)
        appendBlockLine(extensionEnd, extensionEndBeyond)

        val text = String.format(Locale.US, "%.${DIMENSION_DECIMALS}f", geometry.length)
        val textPosition = PointMm(
            geometry.midpoint.x + geometry.normalX * offsetDirection * (DIMENSION_TEXT_HEIGHT_MM / 2.0 + DIMENSION_TEXT_LINE_GAP_MM),
            geometry.midpoint.y + geometry.normalY * offsetDirection * (DIMENSION_TEXT_HEIGHT_MM / 2.0 + DIMENSION_TEXT_LINE_GAP_MM)
        )
        appendBlockLine(geometry.lineStart, geometry.lineEnd)
        appendDimensionArrow(geometry.lineStart, geometry.unitX, geometry.unitY, geometry.normalX, geometry.normalY)
        appendDimensionArrow(geometry.lineEnd, -geometry.unitX, -geometry.unitY, geometry.normalX, geometry.normalY)

        pair(0, "MTEXT")
        pair(100, "AcDbEntity")
        pair(8, "0")
        pair(6, "BYBLOCK")
        pair(62, "0")
        pair(100, "AcDbMText")
        pair(10, number(textPosition.x))
        pair(20, number(textPosition.y))
        pair(30, "0")
        pair(40, number(DIMENSION_TEXT_HEIGHT_MM))
        pair(41, number(text.length * DIMENSION_TEXT_HEIGHT_MM * DIMENSION_TEXT_WIDTH_FACTOR))
        pair(71, "5") // centered, middle attachment; offset next to the dimension line
        pair(72, "1")
        pair(1, text)
        pair(7, DIMENSION_TEXT_STYLE)
        pair(210, "0")
        pair(220, "0")
        pair(230, "1")
        pair(50, number(geometry.textAngle))
        pair(73, "2")
        pair(44, "1")
    }

    private fun StringBuilder.appendBlockLine(start: PointMm, end: PointMm) {
        pair(0, "LINE")
        pair(100, "AcDbEntity")
        pair(8, "0")
        pair(6, "BYBLOCK")
        pair(62, "0")
        pair(100, "AcDbLine")
        pair(10, number(start.x))
        pair(20, number(start.y))
        pair(30, "0")
        pair(11, number(end.x))
        pair(21, number(end.y))
        pair(31, "0")
    }

    private fun StringBuilder.appendDimensionArrow(
        tip: PointMm,
        inwardX: Double,
        inwardY: Double,
        normalX: Double,
        normalY: Double
    ) {
        val baseCenter = PointMm(
            tip.x + inwardX * DIMENSION_ARROW_SIZE_MM,
            tip.y + inwardY * DIMENSION_ARROW_SIZE_MM
        )
        val halfWidth = DIMENSION_ARROW_SIZE_MM * DIMENSION_ARROW_HALF_WIDTH_FACTOR
        val first = PointMm(baseCenter.x + normalX * halfWidth, baseCenter.y + normalY * halfWidth)
        val second = PointMm(baseCenter.x - normalX * halfWidth, baseCenter.y - normalY * halfWidth)
        pair(0, "SOLID")
        pair(100, "AcDbEntity")
        pair(8, "0")
        pair(6, "BYBLOCK")
        pair(62, "0")
        pair(100, "AcDbTrace")
        pair(10, number(tip.x))
        pair(20, number(tip.y))
        pair(30, "0")
        pair(11, number(first.x))
        pair(21, number(first.y))
        pair(31, "0")
        pair(12, number(second.x))
        pair(22, number(second.y))
        pair(32, "0")
        pair(13, number(second.x))
        pair(23, number(second.y))
        pair(33, "0")
    }

    private fun List<DxfPair>.number(code: Int): Double = numberOrNull(code)
        ?: throw IllegalArgumentException("Falta una coordenada DXF obligatoria (grupo $code).")

    private fun List<DxfPair>.numberOrNull(code: Int): Double? = firstOrNull { it.code == code }?.value?.toDoubleOrNull()

    private fun PointMm.scale(factor: Double) = PointMm(x * factor, y * factor)

    private data class DxfPair(val code: Int, val value: String)
    private data class DimensionGeometry(
        val blockName: String,
        val start: PointMm,
        val end: PointMm,
        val lineStart: PointMm,
        val lineEnd: PointMm,
        val midpoint: PointMm,
        val length: Double,
        val offset: Double,
        val unitX: Double,
        val unitY: Double,
        val normalX: Double,
        val normalY: Double,
        val textAngle: Double
    )
    private sealed interface DxfEntity
    private data class DxfLine(val layer: String, val start: PointMm, val end: PointMm, val nonPlanar: Boolean) : DxfEntity
    private data class DxfText(
        val layer: String,
        val text: String,
        val position: PointMm,
        val heightMm: Double,
        val rotationDegrees: Double
    ) : DxfEntity
    private data class DxfPolyline(
        val layer: String,
        val points: List<PointMm>,
        val closed: Boolean,
        val curved: Boolean,
        val nonPlanar: Boolean
    ) : DxfEntity
    private data class DxfUnsupported(val type: String, val layer: String) : DxfEntity

    private const val MAX_COORDINATE_MM = 1_000_000_000.0
    private const val DIMENSION_TEXT_HEIGHT_MM = 100.0
    private const val DIMENSION_ARROW_SIZE_MM = 50.0
    private const val DIMENSION_TEXT_GAP_MM = 50.0
    private const val DIMENSION_TEXT_LINE_GAP_MM = 10.0
    private const val DIMENSION_EXTENSION_OFFSET_MM = 50.0
    private const val DIMENSION_EXTENSION_OVERSHOOT_MM = 100.0
    private const val HATCH_SCALE = 5.0
    private const val DIMENSION_LAYER = "FDXL_DIMS"
    private const val NOTE_LAYER = "FDXL_NOTES"
    private const val DIMENSION_OFFSET_MM = 300.0
    private const val DIMENSION_DECIMALS = 0
    private const val DIMENSION_TEXT_WIDTH_FACTOR = 0.68
    private const val DIMENSION_ARROW_HALF_WIDTH_FACTOR = 0.1667
    private const val DIMENSION_TEXT_STYLE = "unicode"
}
