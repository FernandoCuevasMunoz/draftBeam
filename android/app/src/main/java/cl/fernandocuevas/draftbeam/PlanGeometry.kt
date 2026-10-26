package cl.fernandocuevas.draftbeam

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToLong
import kotlin.math.sin

enum class WallDirection(val label: String, val angleDegrees: Double) {
    X_POSITIVE("X+", 0.0),
    Y_POSITIVE("Y+", 90.0),
    X_NEGATIVE("X−", 180.0),
    Y_NEGATIVE("Y−", 270.0)
}

data class PointMm(val x: Double, val y: Double)

enum class WallGrowthSide(val label: String) {
    LEFT("Izquierda"),
    RIGHT("Derecha");

    companion object {
        fun orderedForAngle(angleDegrees: Double): List<WallGrowthSide> = entries.sortedBy { side ->
            when (side.axisLabel(angleDegrees)) {
                "Arriba", "Izquierda" -> 0
                "Abajo", "Derecha" -> 1
                else -> 2
            }
        }
    }

    fun axisLabel(angleDegrees: Double): String {
        val normalized = PlanGeometry.normalizeAngle(angleDegrees)
        return when (normalized) {
            0.0 -> if (this == LEFT) "Arriba" else "Abajo"
            90.0 -> if (this == LEFT) "Izquierda" else "Derecha"
            180.0 -> if (this == LEFT) "Abajo" else "Arriba"
            270.0 -> if (this == LEFT) "Derecha" else "Izquierda"
            else -> label
        }
    }
}

data class WallSegment(
    val start: PointMm,
    val end: PointMm,
    val lengthMm: Long,
    val angleDegrees: Double,
    val widthMm: Long = 0L,
    val growthSide: WallGrowthSide = WallGrowthSide.LEFT,
    val openings: List<WallOpening> = emptyList()
)

enum class OpeningKind { DOOR, WINDOW }
enum class OpeningAlignment { CENTER, NEAR_FACE, FAR_FACE }

/** Distances are local to the wall's start and near face, independent of its world angle. */
data class WallOpening(
    val kind: OpeningKind,
    val offsetMm: Long,
    val lengthMm: Long,
    val alignment: OpeningAlignment = OpeningAlignment.CENTER,
    val frameWidthMm: Long = 0L,
    val hingeAtStart: Boolean = true,
    val swingTowardWall: Boolean = false,
    val openingAngleDegrees: Int = 90
)

data class PlanNote(
    val text: String,
    val position: PointMm,
    val heightMm: Double = 100.0,
    val rotationDegrees: Double = 0.0
) {
    val textWidthMm: Double get() = text.length * heightMm * 0.62

    init {
        require(text.isNotBlank()) { "La nota no puede estar vacía." }
        require(text.length <= 255 && '\n' !in text && '\r' !in text) { "La nota debe tener hasta 255 caracteres en una sola línea." }
        require(position.x.isFinite() && position.y.isFinite()) { "La posición de la nota no es válida." }
        require(heightMm.isFinite() && heightMm > 0.0) { "El tamaño de texto de la nota debe ser mayor que cero." }
        require(rotationDegrees.isFinite()) { "La rotación de la nota no es válida." }
    }
}

enum class WallEndpoint {
    START,
    END
}

data class DraftSnapOptions(
    val endpointTrackingEnabled: Boolean = true,
    val endpointMagnetEnabled: Boolean = true
)

enum class DraftSnapType {
    ENDPOINT,
    TRACKING
}

data class DraftSnapGuide(val anchor: PointMm, val angleDegrees: Double)

data class DraftSnapResult(
    val point: PointMm,
    val type: DraftSnapType? = null,
    val target: PointMm? = null,
    val capturedAnchor: PointMm? = null,
    val guides: List<DraftSnapGuide> = emptyList()
)

object PlanGeometry {
    private val origin = PointMm(0.0, 0.0)

    fun createWall(
        existingSegments: List<WallSegment>,
        angleDegrees: Double,
        lengthMm: Long,
        startPoint: PointMm?,
        widthMm: Long,
        growthSide: WallGrowthSide
    ): WallSegment {
        require(widthMm >= 0) { "El ancho del muro no puede ser negativo." }
        val start = startPoint ?: existingSegments.lastOrNull()?.end ?: origin
        return segmentFromLength(start, normalizeAngle(angleDegrees), lengthMm).copy(
            widthMm = widthMm,
            growthSide = growthSide
        )
    }

    fun appendSegment(
        existingSegments: List<WallSegment>,
        angleDegrees: Double,
        lengthMm: Long,
        startPoint: PointMm? = null
    ): List<WallSegment> {
        require(lengthMm > 0) { "La longitud del muro debe ser mayor que cero." }

        val start = startPoint ?: existingSegments.lastOrNull()?.end ?: origin
        val segment = segmentFromLength(start, normalizeAngle(angleDegrees), lengthMm)
        return existingSegments + segment
    }

    /** Turns a finger drag into a provisional wall, snapping angles near the four axes. */
    fun segmentFromDrag(
        start: PointMm,
        draggedEnd: PointMm,
        snapToleranceDegrees: Double = DEFAULT_SNAP_TOLERANCE_DEGREES,
        preserveEndPoint: Boolean = false
    ): WallSegment {
        val deltaX = draggedEnd.x - start.x
        val deltaY = draggedEnd.y - start.y
        val draggedLength = hypot(deltaX, deltaY)
        require(draggedLength >= MIN_DRAG_LENGTH_MM) { "Arrastra un poco más para crear el muro." }

        val rawAngle = normalizeAngle(Math.toDegrees(atan2(deltaY, deltaX)))
        if (preserveEndPoint) {
            return WallSegment(start, draggedEnd, draggedLength.roundToLong().coerceAtLeast(1), rawAngle)
        }
        val nearestAxis = WallDirection.entries.minBy { angularDistance(rawAngle, it.angleDegrees) }
        val angle = if (angularDistance(rawAngle, nearestAxis.angleDegrees) <= snapToleranceDegrees) {
            nearestAxis.angleDegrees
        } else {
            rawAngle
        }

        return segmentFromLength(start, angle, draggedLength.roundToLong().coerceAtLeast(1))
    }

    /** Resolves endpoint capture and orthogonal guides acquired from nearby wall endpoints. */
    fun resolveDraftSnap(
        start: PointMm,
        cursor: PointMm,
        segments: List<WallSegment>,
        options: DraftSnapOptions,
        acquiredAnchors: List<PointMm>,
        toleranceMm: Double
    ): DraftSnapResult {
        require(toleranceMm >= 0.0 && toleranceMm.isFinite()) { "La tolerancia de captura no es válida." }
        val endpoints = (listOf(origin) + wallInteractionPoints(segments))
            .distinct()
            .filterNot { it == start }
        val nearbyEndpoint = endpoints.map { point -> point to distance(point, cursor) }
            .filter { (_, distance) -> distance <= toleranceMm }
            .minByOrNull { it.second }?.first
        val anchors = (acquiredAnchors + listOfNotNull(nearbyEndpoint))
            .distinct()
            .takeLast(MAX_TRACKING_ANCHORS)
        val trackingGuides = if (options.endpointTrackingEnabled) {
            anchors.flatMap { anchor ->
                listOf(DraftSnapGuide(anchor, 0.0), DraftSnapGuide(anchor, 90.0))
            }
        } else {
            emptyList()
        }

        if (nearbyEndpoint != null && options.endpointMagnetEnabled) {
            return DraftSnapResult(
                point = nearbyEndpoint,
                type = DraftSnapType.ENDPOINT,
                target = nearbyEndpoint,
                capturedAnchor = if (options.endpointTrackingEnabled) nearbyEndpoint else null,
                guides = trackingGuides
            )
        }

        val trackCandidates = mutableListOf<Pair<PointMm, DraftSnapGuide>>()
        trackingGuides.forEach { guide ->
            val angle = Math.toRadians(guide.angleDegrees)
            val unitX = cos(angle)
            val unitY = sin(angle)
            val projection = projectToLine(cursor, guide.anchor, unitX, unitY)
            if (distance(projection, cursor) <= toleranceMm) trackCandidates += projection to guide
        }
        if (trackingGuides.isNotEmpty()) {
            val verticals = trackingGuides.filter { it.angleDegrees == 90.0 }
            val horizontals = trackingGuides.filter { it.angleDegrees == 0.0 }
            verticals.forEach { vertical ->
                horizontals.forEach { horizontal ->
                    val intersection = PointMm(vertical.anchor.x, horizontal.anchor.y)
                    if (distance(intersection, cursor) <= toleranceMm) {
                        trackCandidates += intersection to vertical
                    }
                }
            }
        }

        val nearestTrack = trackCandidates.minByOrNull { distance(it.first, cursor) }
        return if (nearestTrack == null) {
            DraftSnapResult(
                point = cursor,
                capturedAnchor = if (options.endpointTrackingEnabled) nearbyEndpoint else null,
                guides = trackingGuides
            )
        } else {
            DraftSnapResult(
                point = nearestTrack.first,
                type = DraftSnapType.TRACKING,
                target = nearestTrack.first,
                capturedAnchor = if (options.endpointTrackingEnabled) nearbyEndpoint else null,
                guides = trackingGuides
            )
        }
    }

    fun updateSegmentLength(
        existingSegments: List<WallSegment>,
        index: Int,
        newLengthMm: Long
    ): List<WallSegment> {
        require(index in existingSegments.indices) { "No existe el muro seleccionado." }
        require(newLengthMm > 0) { "La longitud del muro debe ser mayor que cero." }

        return existingSegments.mapIndexed { segmentIndex, segment ->
            if (segmentIndex == index) {
                segmentFromLength(segment.start, segment.angleDegrees, newLengthMm).copy(
                    widthMm = segment.widthMm,
                    growthSide = segment.growthSide,
                    openings = segment.openings
                )
            } else {
                segment
            }
        }
    }

    fun updateSegmentWall(
        existingSegments: List<WallSegment>,
        index: Int,
        widthMm: Long,
        growthSide: WallGrowthSide
    ): List<WallSegment> {
        require(index in existingSegments.indices) { "No existe el muro seleccionado." }
        require(widthMm >= 0) { "El ancho del muro no puede ser negativo." }
        return existingSegments.mapIndexed { segmentIndex, segment ->
            if (segmentIndex == index) segment.copy(widthMm = widthMm, growthSide = growthSide) else segment
        }
    }

    fun endpointUseCount(segments: List<WallSegment>, point: PointMm): Int =
        segments.sumOf { segment ->
            (if (segment.start == point) 1 else 0) + (if (segment.end == point) 1 else 0)
        }

    /** Moves one endpoint, or every endpoint that shares its exact junction coordinate. */
    fun moveEndpoint(
        existingSegments: List<WallSegment>,
        index: Int,
        endpoint: WallEndpoint,
        destination: PointMm,
        moveConnectedEndpoints: Boolean = false
    ): List<WallSegment> {
        require(index in existingSegments.indices) { "No existe el muro seleccionado." }
        val selectedSegment = existingSegments[index]
        val origin = if (endpoint == WallEndpoint.START) selectedSegment.start else selectedSegment.end
        val moveAll = moveConnectedEndpoints && endpointUseCount(existingSegments, origin) > 1
        val anchor = if (endpoint == WallEndpoint.START) selectedSegment.end else selectedSegment.start
        val snappedDestination = snapEndpointToAxis(anchor, destination)

        return existingSegments.mapIndexed { segmentIndex, segment ->
            val moveStart = if (moveAll) segment.start == origin
                else segmentIndex == index && endpoint == WallEndpoint.START
            val moveEnd = if (moveAll) segment.end == origin
                else segmentIndex == index && endpoint == WallEndpoint.END
            if (!moveStart && !moveEnd) segment else {
                val newStart = if (moveStart) snappedDestination else segment.start
                val newEnd = if (moveEnd) snappedDestination else segment.end
                val dx = newEnd.x - newStart.x
                val dy = newEnd.y - newStart.y
                val length = hypot(dx, dy)
                require(length >= MIN_DRAG_LENGTH_MM) { "El muro debe conservar una longitud mayor que cero." }
                segment.copy(
                    start = newStart,
                    end = newEnd,
                    lengthMm = length.roundToLong().coerceAtLeast(1),
                    angleDegrees = normalizeAngle(Math.toDegrees(atan2(dy, dx)))
                )
            }
        }
    }

    /** Returns the four corners, with the centerline acting as the chosen wall edge. */
    fun wallCorners(segment: WallSegment): List<PointMm> {
        if (segment.widthMm <= 0L) return emptyList()
        val dx = segment.end.x - segment.start.x
        val dy = segment.end.y - segment.start.y
        val length = hypot(dx, dy)
        require(length > 0.0) { "El muro debe tener longitud para representar su ancho." }
        val sideSign = if (segment.growthSide == WallGrowthSide.LEFT) 1.0 else -1.0
        val offsetX = -dy / length * segment.widthMm * sideSign
        val offsetY = dx / length * segment.widthMm * sideSign
        return listOf(
            segment.start,
            segment.end,
            PointMm(segment.end.x + offsetX, segment.end.y + offsetY),
            PointMm(segment.start.x + offsetX, segment.start.y + offsetY)
        )
    }

    /** Endpoints available for continuation, including the parallel edge of each wall. */
    fun wallInteractionPoints(segments: List<WallSegment>): List<PointMm> =
        segments.flatMap { segment ->
            listOf(segment.start, segment.end) + wallCorners(segment)
        }.distinct()

    /**
     * Calculates visible wall footprints. At a shared endpoint, the wider wall is
     * trimmed by the narrower wall so their areas do not overlap. Non-connected
     * crossings are deliberately left untouched.
     */
    fun wallFootprints(segments: List<WallSegment>): List<List<List<PointMm>>> =
        segments.mapIndexed { index, segment ->
            if (segment.widthMm <= 0L) return@mapIndexed emptyList()
            var pieces = solidRanges(segment).map { (from, to) ->
                listOf(wallPoint(segment, from), wallPoint(segment, to),
                    wallPoint(segment, to, segment.widthMm.toDouble()),
                    wallPoint(segment, from, segment.widthMm.toDouble()))
            }
            segments.forEachIndexed { otherIndex, other ->
                if (index == otherIndex || other.widthMm <= 0L || !shareEndpoint(segment, other)) {
                    return@forEachIndexed
                }
                val shouldTrimThisWall = segment.widthMm > other.widthMm ||
                    (segment.widthMm == other.widthMm && index > otherIndex)
                if (!shouldTrimThisWall) return@forEachIndexed

                val cutter = wallCorners(other)
                pieces = pieces.flatMap { piece -> subtractConvexPolygon(piece, cutter) }
            }
            pieces
        }

    fun wallPoint(segment: WallSegment, alongMm: Double, acrossMm: Double = 0.0): PointMm {
        val dx = segment.end.x - segment.start.x
        val dy = segment.end.y - segment.start.y
        val length = hypot(dx, dy)
        require(length > 0.0) { "El muro no tiene longitud." }
        val side = if (segment.growthSide == WallGrowthSide.LEFT) 1.0 else -1.0
        return PointMm(segment.start.x + dx / length * alongMm - dy / length * acrossMm * side,
            segment.start.y + dy / length * alongMm + dx / length * acrossMm * side)
    }

    fun solidRanges(segment: WallSegment): List<Pair<Double, Double>> {
        val length = hypot(segment.end.x - segment.start.x, segment.end.y - segment.start.y)
        var start = 0.0
        val ranges = mutableListOf<Pair<Double, Double>>()
        segment.openings.sortedBy { it.offsetMm }.forEach { opening ->
            if (opening.offsetMm > start) ranges += start to opening.offsetMm.toDouble()
            start = opening.offsetMm.toDouble() + opening.lengthMm
        }
        if (start < length) ranges += start to length
        return ranges
    }

    fun frameOffset(segment: WallSegment, opening: WallOpening): Double = when (opening.alignment) {
        OpeningAlignment.NEAR_FACE -> 0.0
        OpeningAlignment.FAR_FACE -> (segment.widthMm - opening.frameWidthMm).toDouble()
        OpeningAlignment.CENTER -> (segment.widthMm - opening.frameWidthMm) / 2.0
    }

    fun validateOpenings(segment: WallSegment) {
        require(segment.openings.isEmpty() || segment.widthMm > 0L) { "Los vanos requieren un muro con ancho." }
        require(segment.openings.size <= 1000) { "El muro contiene demasiados vanos." }
        val length = hypot(segment.end.x - segment.start.x, segment.end.y - segment.start.y)
        var previousEnd = 0.0
        segment.openings.sortedBy { it.offsetMm }.forEach { opening ->
            require(opening.lengthMm > 0 && opening.offsetMm >= 0 &&
                opening.lengthMm < length && opening.offsetMm.toDouble() + opening.lengthMm <= length + 1e-6) {
                "El vano debe caber dentro del muro."
            }
            require(opening.offsetMm >= previousEnd - 1e-6) { "Los vanos no pueden superponerse." }
            require(opening.frameWidthMm >= 0 && opening.frameWidthMm <= segment.widthMm &&
                (opening.kind != OpeningKind.WINDOW || opening.frameWidthMm > 0) &&
                (opening.kind != OpeningKind.DOOR || opening.frameWidthMm == 0L)) {
                "El ancho de la ventana debe caber entre las caras del muro."
            }
            require(opening.openingAngleDegrees in 1..180) { "El ángulo de apertura debe estar entre 1° y 180°." }
            previousEnd = opening.offsetMm.toDouble() + opening.lengthMm
        }
    }

    private fun shareEndpoint(first: WallSegment, second: WallSegment): Boolean =
        first.start == second.start || first.start == second.end ||
            first.end == second.start || first.end == second.end

    /** Subtracts a convex clip polygon, returning disjoint convex pieces. */
    private fun subtractConvexPolygon(
        subject: List<PointMm>,
        clip: List<PointMm>
    ): List<List<PointMm>> {
        if (subject.size < 3 || clip.size < 3) return listOf(subject)
        val orientation = if (signedArea(clip) >= 0.0) 1.0 else -1.0
        var remaining = subject
        val outsidePieces = mutableListOf<List<PointMm>>()
        clip.indices.forEach { edgeIndex ->
            if (remaining.size < 3) return@forEach
            val edgeStart = clip[edgeIndex]
            val edgeEnd = clip[(edgeIndex + 1) % clip.size]
            outsidePieces += clipToHalfPlane(remaining, edgeStart, edgeEnd, orientation, keepInside = false)
                .takeIf(::hasArea)
                ?.let(::listOf)
                .orEmpty()
            remaining = clipToHalfPlane(remaining, edgeStart, edgeEnd, orientation, keepInside = true)
        }
        return outsidePieces
    }

    private fun clipToHalfPlane(
        polygon: List<PointMm>,
        edgeStart: PointMm,
        edgeEnd: PointMm,
        orientation: Double,
        keepInside: Boolean
    ): List<PointMm> {
        if (polygon.isEmpty()) return emptyList()
        fun distance(point: PointMm): Double = orientation *
            ((edgeEnd.x - edgeStart.x) * (point.y - edgeStart.y) -
                (edgeEnd.y - edgeStart.y) * (point.x - edgeStart.x))

        val output = mutableListOf<PointMm>()
        var previous = polygon.last()
        var previousDistance = distance(previous)
        var previousKept = if (keepInside) previousDistance >= -GEOMETRY_EPSILON else previousDistance <= GEOMETRY_EPSILON
        polygon.forEach { current ->
            val currentDistance = distance(current)
            val currentKept = if (keepInside) currentDistance >= -GEOMETRY_EPSILON else currentDistance <= GEOMETRY_EPSILON
            if (currentKept != previousKept) {
                val denominator = previousDistance - currentDistance
                if (kotlin.math.abs(denominator) > GEOMETRY_EPSILON) {
                    val fraction = previousDistance / denominator
                    output += PointMm(
                        previous.x + (current.x - previous.x) * fraction,
                        previous.y + (current.y - previous.y) * fraction
                    )
                }
            }
            if (currentKept) output += current
            previous = current
            previousDistance = currentDistance
            previousKept = currentKept
        }
        return removeDuplicateAdjacentPoints(output)
    }

    private fun removeDuplicateAdjacentPoints(points: List<PointMm>): List<PointMm> {
        val result = mutableListOf<PointMm>()
        points.forEach { point ->
            if (result.lastOrNull()?.let { distance(it, point) > GEOMETRY_EPSILON } != false) result += point
        }
        if (result.size > 1 && distance(result.first(), result.last()) <= GEOMETRY_EPSILON) result.removeAt(result.lastIndex)
        return result
    }

    private fun hasArea(polygon: List<PointMm>): Boolean =
        polygon.size >= 3 && kotlin.math.abs(signedArea(polygon)) > GEOMETRY_EPSILON

    private fun signedArea(polygon: List<PointMm>): Double = polygon.indices.sumOf { index ->
        val current = polygon[index]
        val next = polygon[(index + 1) % polygon.size]
        current.x * next.y - next.x * current.y
    } / 2.0

    private fun distance(first: PointMm, second: PointMm): Double =
        hypot(first.x - second.x, first.y - second.y)

    private fun projectToLine(point: PointMm, anchor: PointMm, unitX: Double, unitY: Double): PointMm {
        val along = (point.x - anchor.x) * unitX + (point.y - anchor.y) * unitY
        return PointMm(anchor.x + unitX * along, anchor.y + unitY * along)
    }

    private fun angularDistance(first: Double, second: Double): Double {
        val difference = kotlin.math.abs(normalizeAngle(first) - normalizeAngle(second))
        return minOf(difference, 360.0 - difference)
    }

    /** Removes only the selected entity; every other segment keeps its original coordinates. */
    fun removeSegment(existingSegments: List<WallSegment>, index: Int): List<WallSegment> {
        require(index in existingSegments.indices) { "No existe el muro seleccionado." }
        return existingSegments.filterIndexed { segmentIndex, _ -> segmentIndex != index }
    }

    fun normalizeAngle(angleDegrees: Double): Double = ((angleDegrees % 360.0) + 360.0) % 360.0

    /** Normalizes screen-space label rotation so diagonal text is never upside down. */
    fun uprightLabelRotationDegrees(screenAngleDegrees: Double): Double {
        var angle = ((screenAngleDegrees + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        if (angle > 90.0) angle -= 180.0
        if (angle < -90.0) angle += 180.0
        return angle
    }

    /** Picks the side opposite the segment label for a newly created wall. */
    fun defaultGrowthSide(angleDegrees: Double): WallGrowthSide = when (normalizeAngle(angleDegrees)) {
        180.0, 270.0 -> WallGrowthSide.LEFT
        else -> WallGrowthSide.RIGHT
    }

    private fun segmentFromLength(start: PointMm, angleDegrees: Double, lengthMm: Long): WallSegment {
        val radians = Math.toRadians(angleDegrees)
        val deltaX = when (angleDegrees) {
            0.0 -> lengthMm.toDouble()
            180.0 -> -lengthMm.toDouble()
            90.0, 270.0 -> 0.0
            else -> cos(radians) * lengthMm
        }
        val deltaY = when (angleDegrees) {
            90.0 -> lengthMm.toDouble()
            270.0 -> -lengthMm.toDouble()
            0.0, 180.0 -> 0.0
            else -> sin(radians) * lengthMm
        }

        return WallSegment(
            start = start,
            end = PointMm(start.x + deltaX, start.y + deltaY),
            lengthMm = lengthMm,
            angleDegrees = angleDegrees,
            growthSide = defaultGrowthSide(angleDegrees)
        )
    }

    private fun snapEndpointToAxis(anchor: PointMm, destination: PointMm): PointMm {
        val deltaX = destination.x - anchor.x
        val deltaY = destination.y - anchor.y
        val draggedLength = hypot(deltaX, deltaY)
        if (draggedLength < MIN_DRAG_LENGTH_MM) return destination

        val rawAngle = normalizeAngle(Math.toDegrees(atan2(deltaY, deltaX)))
        val nearestAxis = WallDirection.entries.minBy { angularDistance(rawAngle, it.angleDegrees) }
        if (angularDistance(rawAngle, nearestAxis.angleDegrees) > DEFAULT_SNAP_TOLERANCE_DEGREES) {
            return destination
        }

        return segmentFromLength(anchor, nearestAxis.angleDegrees, draggedLength.roundToLong().coerceAtLeast(1)).end
    }

    private const val MIN_DRAG_LENGTH_MM = 1.0
    private const val GEOMETRY_EPSILON = 1e-7
    const val DEFAULT_SNAP_TOLERANCE_DEGREES = 8.0
    private const val MAX_TRACKING_ANCHORS = 8
}
