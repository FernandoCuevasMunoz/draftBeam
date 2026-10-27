package cl.fernandocuevas.draftbeam

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Door placed in a gap that already exists between two wall ends: wall, opening,
 * wall. The gap is measured between the two snapped wall vertices, the frame
 * consumes [frameWidthMm] at each end (so the leaf is shorter than the gap) and
 * the depth comes from the wall thickness measured across the gap.
 *
 * The leaf turns on the frame face at [hinge] and opens toward the side given by
 * [swingSign], where +1 is the counter-clockwise normal of hinge → latch.
 */
data class PlanDoor(
    val hinge: PointMm,
    val latch: PointMm,
    val frameWidthMm: Long,
    val depthMm: Long,
    val openAngleDegrees: Double = 90.0,
    val swingSign: Int = 1,
    val leafThicknessMm: Long = DEFAULT_LEAF_THICKNESS_MM
) {
    val gapLengthMm: Double get() = hypot(latch.x - hinge.x, latch.y - hinge.y)
    val leafLengthMm: Double get() = gapLengthMm - 2.0 * frameWidthMm

    init {
        require(hinge != latch) { "La puerta necesita dos extremos distintos." }
        require(gapLengthMm.isFinite() && gapLengthMm > 0.0) { "El vano no tiene largo." }
        require(frameWidthMm > 0L) { "El ancho de marco debe ser mayor que cero." }
        require(depthMm > 0L) { "La profundidad de la puerta debe ser mayor que cero." }
        require(leafLengthMm >= MIN_LEAF_LENGTH_MM - 1e-6) {
            "El marco deja la hoja con menos de $MIN_LEAF_LENGTH_MM mm."
        }
        require(openAngleDegrees.isFinite() && openAngleDegrees > 0.0 && openAngleDegrees <= 180.0) {
            "El ángulo de apertura debe estar entre 1° y 180°."
        }
        require(swingSign == 1 || swingSign == -1) { "El lado de apertura es inválido." }
        require(leafThicknessMm > 0L && leafThicknessMm <= depthMm) {
            "El espesor de la hoja debe caber en la profundidad."
        }
    }

    companion object {
        const val DEFAULT_FRAME_WIDTH_MM = 50L
        const val DEFAULT_LEAF_THICKNESS_MM = 40L
        const val DEFAULT_DEPTH_MM = 100L
        const val MIN_LEAF_LENGTH_MM = 100.0
        const val MIN_DEPTH_MM = 20L
        const val TAP_ROUNDING_MM = 10L
    }
}

/** Frame blocks, open leaf, swing arc and the hinge, in plan coordinates. */
data class DoorPlanGeometry(
    val frames: List<List<PointMm>>,
    val leaf: List<PointMm>,
    val arc: List<PointMm>,
    val opening: OpeningLine,
    val pivot: PointMm,
    val radiusMm: Double,
    val arcStartDegrees: Double,
    val arcEndDegrees: Double
)

/** Outcome of measuring a gap between two wall ends. */
sealed interface DoorGapResult {
    data class Ready(val door: PlanDoor) : DoorGapResult
    data class Rejected(val message: String) : DoorGapResult
}

/** Pure rules and geometry for doors placed in an existing gap between two walls. */
object PlanDoors {
    private const val ARC_STEPS = 18

    /** Unit vectors of the door: [along] runs hinge → latch, [across] is its left normal. */
    private class Plane(val along: PointMm, val across: PointMm, val door: PlanDoor) {
        fun at(u: Double, v: Double): PointMm = PointMm(
            door.hinge.x + along.x * u + across.x * v,
            door.hinge.y + along.y * u + across.y * v
        )

        fun coordinatesOf(point: PointMm): Pair<Double, Double> {
            val dx = point.x - door.hinge.x
            val dy = point.y - door.hinge.y
            return (dx * along.x + dy * along.y) to (dx * across.x + dy * across.y)
        }
    }

    private fun planeOf(door: PlanDoor): Plane {
        val length = door.gapLengthMm
        val along = PointMm(
            (door.latch.x - door.hinge.x) / length,
            (door.latch.y - door.hinge.y) / length
        )
        return Plane(along, PointMm(-along.y, along.x), door)
    }

    /** Hinge point of the open leaf: the inner face of the frame at the hinge end. */
    fun pivot(door: PlanDoor): PointMm = planeOf(door).at(door.frameWidthMm.toDouble(), 0.0)

    /**
     * Frame depth measured with two taps across the gap. The door stays centred on
     * the wall axis that joins both vertices, so only the distance between the taps
     * matters and any asymmetry is discarded.
     */
    fun depthFromTaps(door: PlanDoor, first: PointMm, second: PointMm): Long? {
        val plane = planeOf(door)
        val distance = abs(plane.coordinatesOf(first).second - plane.coordinatesOf(second).second)
        val rounded = (distance / PlanDoor.TAP_ROUNDING_MM).roundToLong() * PlanDoor.TAP_ROUNDING_MM
        return rounded.takeIf { it >= PlanDoor.MIN_DEPTH_MM }
    }

    /** Side and angle from the pointer that follows the leaf, like the ADT2 cursor flip. */
    fun toward(door: PlanDoor, pointer: PointMm): PlanDoor {
        val plane = planeOf(door)
        val (along, across) = plane.coordinatesOf(pointer)
            .let { (u, v) -> (u - door.frameWidthMm) to v }
        val swing = if (across < 0.0) -1 else 1
        val degrees = Math.toDegrees(atan2(abs(across), along)).coerceIn(1.0, 180.0)
        return door.copy(
            swingSign = swing,
            openAngleDegrees = (degrees * 10.0).roundToLong() / 10.0
        )
    }

    /** Frame blocks, leaf and arc; arc angles are DXF-ready counter-clockwise angles. */
    fun geometry(door: PlanDoor): DoorPlanGeometry {
        val plane = planeOf(door)
        val gap = door.gapLengthMm
        val frameWidth = door.frameWidthMm.toDouble()
        val halfDepth = door.depthMm / 2.0
        val leafLength = door.leafLengthMm
        val thickness = door.leafThicknessMm.toDouble()
        val swing = door.swingSign.toDouble()
        val angle = Math.toRadians(door.openAngleDegrees)

        val frames = listOf(
            framePolygon(plane, from = 0.0, to = frameWidth, halfDepth = halfDepth),
            framePolygon(plane, from = gap - frameWidth, to = gap, halfDepth = halfDepth)
        )
        // u runs along the leaf, w across it toward the side the door opens.
        fun rotated(u: Double, w: Double, rotation: Double): PointMm = plane.at(
            frameWidth + u * cos(rotation) - w * sin(rotation),
            swing * (u * sin(rotation) + w * cos(rotation))
        )

        val leaf = listOf(
            rotated(0.0, 0.0, angle),
            rotated(leafLength, 0.0, angle),
            rotated(leafLength, thickness, angle),
            rotated(0.0, thickness, angle)
        )
        val arc = (0..ARC_STEPS).map { step -> rotated(leafLength, 0.0, angle * step / ARC_STEPS) }

        // Rotating from the gap axis toward the left normal is counter-clockwise.
        val closedDegrees = Math.toDegrees(atan2(plane.along.y, plane.along.x))
        val sweep = Math.toDegrees(angle) * swing
        return DoorPlanGeometry(
            frames = frames,
            leaf = leaf,
            arc = arc,
            opening = OpeningLine(door.hinge, door.latch),
            pivot = pivot(door),
            radiusMm = leafLength,
            arcStartDegrees = PlanGeometry.normalizeAngle(if (sweep >= 0.0) closedDegrees else closedDegrees + sweep),
            arcEndDegrees = PlanGeometry.normalizeAngle(if (sweep >= 0.0) closedDegrees + sweep else closedDegrees)
        )
    }

    /** Width of a wall that starts or ends exactly on [point], or null when there is none. */
    fun wallWidthAt(segments: List<WallSegment>, point: PointMm): Long? = segments
        .filter { it.start == point || it.end == point }
        .map { it.widthMm }
        .firstOrNull { it > 0L }

    /**
     * Creates the door for a measured gap. The leaf thickness is clamped to the
     * measured depth so a thin wall never produces an impossible leaf.
     */
    fun fromGap(
        hinge: PointMm,
        latch: PointMm,
        frameWidthMm: Long,
        depthMm: Long
    ): DoorGapResult {
        if (hinge == latch) {
            return DoorGapResult.Rejected("El vano necesita dos extremos distintos.")
        }
        if (depthMm < PlanDoor.MIN_DEPTH_MM) {
            return DoorGapResult.Rejected(
                "La profundidad debe medir al menos ${PlanDoor.MIN_DEPTH_MM} mm."
            )
        }
        val gap = hypot(latch.x - hinge.x, latch.y - hinge.y)
        val leaf = gap - 2.0 * frameWidthMm
        if (leaf < PlanDoor.MIN_LEAF_LENGTH_MM) {
            return DoorGapResult.Rejected(
                "Con marco de $frameWidthMm mm, un vano de ${gap.roundToLong()} mm deja una hoja de " +
                    "${leaf.roundToLong()} mm; el mínimo es ${PlanDoor.MIN_LEAF_LENGTH_MM.roundToLong()} mm."
            )
        }
        return DoorGapResult.Ready(
            PlanDoor(
                hinge = hinge,
                latch = latch,
                frameWidthMm = frameWidthMm,
                depthMm = depthMm,
                leafThicknessMm = minOf(PlanDoor.DEFAULT_LEAF_THICKNESS_MM, depthMm)
            )
        )
    }

    private fun framePolygon(
        plane: Plane,
        from: Double,
        to: Double,
        halfDepth: Double
    ): List<PointMm> = listOf(
        plane.at(from, -halfDepth),
        plane.at(to, -halfDepth),
        plane.at(to, halfDepth),
        plane.at(from, halfDepth)
    )
}
