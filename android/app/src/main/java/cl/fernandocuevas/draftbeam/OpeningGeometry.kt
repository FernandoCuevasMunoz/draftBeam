package cl.fernandocuevas.draftbeam

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

data class OpeningLine(val start: PointMm, val end: PointMm)

object OpeningGeometry {
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
}
