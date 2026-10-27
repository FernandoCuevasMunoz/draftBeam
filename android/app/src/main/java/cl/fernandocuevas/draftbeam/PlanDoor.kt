package cl.fernandocuevas.draftbeam

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Door placed in a gap that already exists between two wall ends: wall, opening,
 * wall. The gap is measured between the two snapped wall vertices, the frame
 * consumes [frameWidthMm] at each end (so the leaf is shorter than the gap) and
 * [depthMm] is the wall thickness, so the frame stays flush with both wall faces.
 *
 * The leaf turns on the frame face at [hinge] and [openAngleDegrees] is its
 * counter-clockwise rotation from the closed position (hinge → latch): 0 and 360
 * closed, 90 and 270 perpendicular on either side, 180 back over the gap.
 */
data class PlanDoor(
    val hinge: PointMm,
    val latch: PointMm,
    val frameWidthMm: Long,
    val depthMm: Long,
    val openAngleDegrees: Double = 90.0,
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
        require(openAngleDegrees.isFinite() && openAngleDegrees in 0.0..360.0) {
            "El ángulo de apertura debe estar entre 0° y 360°."
        }
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
        /** The pointer snaps the leaf to these directions, like a CAD ortho toggle. */
        const val ANGLE_STEP_DEGREES = 90.0
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

    /** Wall thickness that holds the gap, so the frame stays flush with both faces. */
    fun depthAt(segments: List<WallSegment>, hinge: PointMm, latch: PointMm): Long {
        val left = wallWidthAt(segments, hinge)
        val right = wallWidthAt(segments, latch)
        return when {
            left != null && right != null -> maxOf(left, right)
            left != null -> left
            right != null -> right
            else -> PlanDoor.DEFAULT_DEPTH_MM
        }
    }

    /**
     * Angle and side from the pointer that follows the leaf. The pointer declares
     * which way the leaf turns and the value snaps to [PlanDoor.ANGLE_STEP_DEGREES].
     */
    fun toward(door: PlanDoor, pointer: PointMm): PlanDoor {
        val plane = planeOf(door)
        val (u, v) = plane.coordinatesOf(pointer)
        val degrees = Math.toDegrees(atan2(v, u - door.frameWidthMm))
        val normalized = ((degrees % 360.0) + 360.0) % 360.0
        val snapped = (normalized / PlanDoor.ANGLE_STEP_DEGREES).roundToLong() *
            PlanDoor.ANGLE_STEP_DEGREES % 360.0
        return door.copy(openAngleDegrees = snapped)
    }

    /** Frame blocks, leaf and arc; arc angles are DXF-ready counter-clockwise angles. */
    fun geometry(door: PlanDoor): DoorPlanGeometry {
        val plane = planeOf(door)
        val gap = door.gapLengthMm
        val frameWidth = door.frameWidthMm.toDouble()
        val halfDepth = door.depthMm / 2.0
        val leafLength = door.leafLengthMm
        val thickness = door.leafThicknessMm.toDouble()
        val angle = Math.toRadians(door.openAngleDegrees)

        val frames = listOf(
            framePolygon(plane, from = 0.0, to = frameWidth, halfDepth = halfDepth),
            framePolygon(plane, from = gap - frameWidth, to = gap, halfDepth = halfDepth)
        )
        // u runs along the leaf and w across it; the leaf turns counter-clockwise.
        fun rotated(u: Double, w: Double, rotation: Double): PointMm = plane.at(
            frameWidth + u * cos(rotation) - w * sin(rotation),
            u * sin(rotation) + w * cos(rotation)
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
        val sweep = door.openAngleDegrees
        return DoorPlanGeometry(
            frames = frames,
            leaf = leaf,
            arc = arc,
            opening = OpeningLine(door.hinge, door.latch),
            pivot = pivot(door),
            radiusMm = leafLength,
            arcStartDegrees = PlanGeometry.normalizeAngle(closedDegrees),
            arcEndDegrees = PlanGeometry.normalizeAngle(closedDegrees + sweep)
        )
    }

    /**
     * Shift from the wall axis to the middle of its thickness. The axis is a face, not
     * the centerline: the wall grows toward [WallGrowthSide] from it, so a door measured
     * on wall vertices has to be moved half the wall thickness to stay flush with both
     * faces. Returns the origin vector when the point is not a wall end.
     */
    fun centerOffset(segments: List<WallSegment>, at: PointMm): PointMm {
        val wall = segments.firstOrNull { it.start == at || it.end == at } ?: return PointMm(0.0, 0.0)
        if (wall.widthMm <= 0L) return PointMm(0.0, 0.0)
        val dx = wall.end.x - wall.start.x
        val dy = wall.end.y - wall.start.y
        val length = hypot(dx, dy)
        if (length <= 0.0) return PointMm(0.0, 0.0)
        val side = if (wall.growthSide == WallGrowthSide.LEFT) 1.0 else -1.0
        val half = wall.widthMm / 2.0
        return PointMm(-dy / length * side * half, dx / length * side * half)
    }

    /**
     * Mirror copy: the leaf hangs from the other end while staying on the same side of
     * the wall. Reversing the axis flips the counter-clockwise direction, so the angle
     * is reflected as well.
     */
    fun mirrored(door: PlanDoor): PlanDoor = door.copy(
        hinge = door.latch,
        latch = door.hinge,
        openAngleDegrees = (360.0 - door.openAngleDegrees) % 360.0
    )

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
