package cl.fernandocuevas.draftbeam

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Private, versioned autosave; never round-trips drafts through the lossy DXF importer. */
data class PlanSession(
    val segments: List<WallSegment>,
    val notes: List<PlanNote>,
    val selectedStartPoint: PointMm?,
    val documentUri: String?
)

object PlanSessionCodec {
    private const val VERSION = 2
    private const val MAX_ITEMS = 100_000

    fun encode(session: PlanSession): ByteArray {
        require(session.segments.size <= MAX_ITEMS && session.notes.size <= MAX_ITEMS)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(VERSION)
            output.writeBoolean(session.documentUri != null)
            session.documentUri?.let(output::writeUTF)
            output.writeBoolean(session.selectedStartPoint != null)
            session.selectedStartPoint?.let { output.writePoint(it) }
            output.writeInt(session.segments.size)
            session.segments.forEach { segment ->
                PlanGeometry.validateOpenings(segment)
                require(segment.openings.size <= 1000) { "Demasiados vanos en el borrador." }
                output.writePoint(segment.start)
                output.writePoint(segment.end)
                output.writeLong(segment.lengthMm)
                output.writeDouble(segment.angleDegrees)
                output.writeLong(segment.widthMm)
                output.writeInt(segment.growthSide.ordinal)
                output.writeInt(segment.openings.size)
                segment.openings.forEach { opening ->
                    output.writeInt(opening.kind.ordinal)
                    output.writeLong(opening.offsetMm)
                    output.writeLong(opening.lengthMm)
                    output.writeInt(opening.alignment.ordinal)
                    output.writeLong(opening.frameWidthMm)
                    output.writeBoolean(opening.hingeAtStart)
                    output.writeBoolean(opening.swingTowardWall)
                    output.writeInt(opening.openingAngleDegrees)
                }
            }
            output.writeInt(session.notes.size)
            session.notes.forEach { note ->
                output.writeUTF(note.text)
                output.writePoint(note.position)
                output.writeDouble(note.heightMm)
                output.writeDouble(note.rotationDegrees)
            }
        }
        return bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): PlanSession = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        val version = input.readInt()
        require(version in 1..VERSION) { "Versión de sesión desconocida." }
        val uri = if (input.readBoolean()) input.readUTF() else null
        val selected = if (input.readBoolean()) input.readPoint() else null
        val segments = List(input.readCount()) {
            val start = input.readPoint()
            val end = input.readPoint()
            val length = input.readLong()
            val angle = input.readDouble()
            val width = input.readLong()
            val side = WallGrowthSide.entries[input.readSide()]
            val openings = if (version == 1) emptyList() else List(input.readCount().also {
                require(it <= 1000) { "Demasiados vanos en el borrador." }
            }) {
                WallOpening(OpeningKind.entries[input.readOpeningKind()], input.readLong(), input.readLong(),
                    OpeningAlignment.entries[input.readOpeningAlignment()], input.readLong(), input.readBoolean(),
                    input.readBoolean(), input.readInt())
            }
            WallSegment(
                start = start, end = end, lengthMm = length, angleDegrees = angle,
                widthMm = width, growthSide = side, openings = openings
            ).also(PlanGeometry::validateOpenings)
        }
        val notes = List(input.readCount()) {
            PlanNote(input.readUTF(), input.readPoint(), input.readDouble(), input.readDouble())
        }
        require(input.read() == -1) { "La sesión contiene datos adicionales." }
        PlanSession(segments, notes, selected, uri)
    }

    private fun DataOutputStream.writePoint(point: PointMm) {
        writeDouble(point.x)
        writeDouble(point.y)
    }

    private fun DataInputStream.readPoint(): PointMm = PointMm(readDouble(), readDouble()).also {
        require(it.x.isFinite() && it.y.isFinite()) { "La sesión contiene coordenadas inválidas." }
    }

    private fun DataInputStream.readCount(): Int = readInt().also {
        require(it in 0..MAX_ITEMS) { "La sesión excede el límite de entidades." }
    }

    private fun DataInputStream.readSide(): Int = readInt().also {
        require(it in WallGrowthSide.entries.indices) { "Lado de muro inválido." }
    }

    private fun DataInputStream.readOpeningKind(): Int = readInt().also {
        require(it in OpeningKind.entries.indices) { "Tipo de vano inválido." }
    }

    private fun DataInputStream.readOpeningAlignment(): Int = readInt().also {
        require(it in OpeningAlignment.entries.indices) { "Alineación de vano inválida." }
    }
}
