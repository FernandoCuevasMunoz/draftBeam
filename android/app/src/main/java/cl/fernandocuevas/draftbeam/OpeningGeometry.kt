package cl.fernandocuevas.draftbeam

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

data class OpeningLine(val start: PointMm, val end: PointMm)

object OpeningGeometry {
    /** Window is closed: two frame edges and glazing on the wall's local axes. */
    fun windowLines(wall: WallSegment, opening: WallOpening): List<OpeningLine> {
        require(opening.kind == OpeningKind.WINDOW)
        val from = opening.offsetMm.toDouble()
        val to = from + opening.lengthMm
        val near = PlanGeometry.frameOffset(wall, opening)
        val far = near + opening.frameWidthMm
        return listOf(near, (near + far) / 2.0, far).map { depth ->
            OpeningLine(PlanGeometry.wallPoint(wall, from, depth), PlanGeometry.wallPoint(wall, to, depth))
        } + listOf(from, to).map { along ->
            OpeningLine(PlanGeometry.wallPoint(wall, along, near), PlanGeometry.wallPoint(wall, along, far))
        }
    }

    fun doorLeaf(wall: WallSegment, opening: WallOpening): OpeningLine {
        require(opening.kind == OpeningKind.DOOR)
        val points = doorArc(wall, opening)
        val hinge = opening.offsetMm.toDouble() + if (opening.hingeAtStart) 0.0 else opening.lengthMm.toDouble()
        return OpeningLine(PlanGeometry.wallPoint(wall, hinge, PlanGeometry.frameOffset(wall, opening)), points.last())
    }

    /** Points from closed to open position, forming the conventional swing arc. */
    fun doorArc(wall: WallSegment, opening: WallOpening): List<PointMm> {
        require(opening.kind == OpeningKind.DOOR)
        val hinge = opening.offsetMm.toDouble() + if (opening.hingeAtStart) 0.0 else opening.lengthMm.toDouble()
        val depth = PlanGeometry.frameOffset(wall, opening)
        val alongSign = if (opening.hingeAtStart) 1.0 else -1.0
        val acrossSign = if (opening.swingTowardWall) 1.0 else -1.0
        val steps = (opening.openingAngleDegrees / 5).coerceAtLeast(1)
        return (0..steps).map { step ->
            val radians = opening.openingAngleDegrees * PI / 180.0 * step / steps
            PlanGeometry.wallPoint(wall,
                hinge + opening.lengthMm * alongSign * cos(radians),
                depth + opening.lengthMm * acrossSign * sin(radians))
        }
    }
}
