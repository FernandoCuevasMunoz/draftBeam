package cl.fernandocuevas.draftbeam

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

data class OpeningLine(val start: PointMm, val end: PointMm)

/**
 * Plan-view pieces of an ADT2 door: the embedded frame lines across the wall
 * thickness, the leaf turned [openAngleDegrees] from closed, and its swing arc.
 * [arcStartDegrees] and [arcEndDegrees] are DXF-ready counter-clockwise angles.
 */
data class DoorPlanGeometry(
    val frameLines: List<OpeningLine>,
    val leaf: List<PointMm>,
    val arc: List<PointMm>,
    val hinge: PointMm,
    val radiusMm: Double,
    val arcStartDegrees: Double,
    val arcEndDegrees: Double
)

object OpeningGeometry {
    private const val ARC_STEPS = 18

    /** Window is closed: two frame edges and glazing on the wall's local axes. */
    fun windowLines(wall: WallSegment, opening: WindowOpening): List<OpeningLine> {
        val from = opening.offsetMm.toDouble()
        val to = from + opening.holeLengthMm
        val near = PlanGeometry.frameOffset(wall, opening)
        val far = near + opening.frameDepthMm
        return listOf(near, (near + far) / 2.0, far).map { depth ->
            OpeningLine(PlanGeometry.wallPoint(wall, from, depth), PlanGeometry.wallPoint(wall, to, depth))
        } + listOf(from, to).map { along ->
            OpeningLine(PlanGeometry.wallPoint(wall, along, near), PlanGeometry.wallPoint(wall, along, far))
        }
    }

    /**
     * The frame is embedded between both wall faces, so its lines sit exactly on
     * the opening ends and span the whole wall thickness; the leaf keeps the full
     * opening length. The leaf turns on the wall mid-line at its hinge end.
     */
    fun doorGeometry(wall: WallSegment, door: DoorOpening): DoorPlanGeometry {
        val holeStart = door.offsetMm.toDouble()
        val holeEnd = holeStart + door.holeLengthMm
        val hingeAlong = if (door.hingeAtStart) holeStart else holeEnd
        val alongSign = if (door.hingeAtStart) 1.0 else -1.0
        val swingSign = if (door.swingTowardNearFace) -1.0 else 1.0
        val midAcross = wall.widthMm / 2.0
        val width = door.holeLengthMm.toDouble()
        val thickness = door.leafThicknessMm.toDouble()
        val angle = Math.toRadians(door.openAngleDegrees)

        /** u runs along the closed leaf, r is its thickness, theta the opening rotation. */
        fun rotated(u: Double, r: Double, theta: Double): PointMm = PlanGeometry.wallPoint(
            wall,
            hingeAlong + alongSign * (u * cos(theta) - r * sin(theta)),
            midAcross + swingSign * (u * sin(theta) + r * cos(theta))
        )

        val leaf = listOf(
            rotated(0.0, 0.0, angle),
            rotated(width, 0.0, angle),
            rotated(width, thickness, angle),
            rotated(0.0, thickness, angle)
        )
        val arc = (0..ARC_STEPS).map { step -> rotated(width, 0.0, angle * step / ARC_STEPS) }

        // A positive product turns the leaf counter-clockwise, which is how DXF reads an arc.
        // Across-positive is counter-clockwise from the wall axis only when the wall grows left.
        val frameSide = if (wall.growthSide == WallGrowthSide.LEFT) 1.0 else -1.0
        val closedDegrees = Math.toDegrees(
            atan2(
                alongSign * (wall.end.y - wall.start.y),
                alongSign * (wall.end.x - wall.start.x)
            )
        )
        val sweep = Math.toDegrees(angle) * alongSign * swingSign * frameSide
        val frameLines = listOf(holeStart, holeEnd).map { along ->
            OpeningLine(
                PlanGeometry.wallPoint(wall, along, 0.0),
                PlanGeometry.wallPoint(wall, along, wall.widthMm.toDouble())
            )
        }

        return DoorPlanGeometry(
            frameLines = frameLines,
            leaf = leaf,
            arc = arc,
            hinge = PlanGeometry.wallPoint(wall, hingeAlong, midAcross),
            radiusMm = width,
            arcStartDegrees = PlanGeometry.normalizeAngle(if (sweep >= 0.0) closedDegrees else closedDegrees + sweep),
            arcEndDegrees = PlanGeometry.normalizeAngle(if (sweep >= 0.0) closedDegrees + sweep else closedDegrees)
        )
    }
}
