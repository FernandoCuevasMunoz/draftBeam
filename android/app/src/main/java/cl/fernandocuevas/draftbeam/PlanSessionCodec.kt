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
    private const val VERSION = 1
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
                output.writePoint(segment.start)
                output.writePoint(segment.end)
                output.writeLong(segment.lengthMm)
                output.writeDouble(segment.angleDegrees)
                output.writeLong(segment.widthMm)
                output.writeInt(segment.growthSide.ordinal)
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
        require(input.readInt() == VERSION) { "Versión de sesión desconocida." }
        val uri = if (input.readBoolean()) input.readUTF() else null
        val selected = if (input.readBoolean()) input.readPoint() else null
        val segments = List(input.readCount()) {
            WallSegment(
                start = input.readPoint(),
                end = input.readPoint(),
                lengthMm = input.readLong(),
                angleDegrees = input.readDouble(),
                widthMm = input.readLong(),
                growthSide = WallGrowthSide.entries[input.readSide()]
            )
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
}
