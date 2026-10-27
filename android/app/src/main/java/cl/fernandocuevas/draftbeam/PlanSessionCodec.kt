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
    val documentUri: String?,
    val warnings: List<String> = emptyList()
)

object PlanSessionCodec {
    private const val VERSION = 4
    private const val MAX_ITEMS = 100_000
    private const val TYPE_DOOR = 1
    private const val TYPE_WINDOW = 2

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
                    when (opening) {
                        is DoorOpening -> {
                            output.writeInt(TYPE_DOOR)
                            output.writeLong(opening.offsetMm)
                            output.writeLong(opening.holeLengthMm)
                            output.writeBoolean(opening.hingeAtStart)
                            output.writeDouble(opening.openAngleDegrees)
                            output.writeBoolean(opening.swingTowardNearFace)
                            output.writeLong(opening.leafThicknessMm)
                        }
                        is WindowOpening -> {
                            output.writeInt(TYPE_WINDOW)
                            output.writeLong(opening.offsetMm)
                            output.writeLong(opening.holeLengthMm)
                            output.writeInt(opening.alignment.ordinal)
                            output.writeLong(opening.frameDepthMm)
                        }
                    }
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
        var droppedDoors = 0
        val segments = List(input.readCount()) {
            val start = input.readPoint()
            val end = input.readPoint()
            val length = input.readLong()
            val angle = input.readDouble()
            val width = input.readLong()
            val side = WallGrowthSide.entries[input.readSide()]
            val openings = when (version) {
                1 -> emptyList()
                else -> readOpenings(input, version).also { droppedDoors += it.count { opening -> opening == null } }
                    .filterNotNull()
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
        val warnings = if (droppedDoors > 0) {
            listOf("Se descartaron $droppedDoors puertas del formato anterior.")
        } else {
            emptyList()
        }
        PlanSession(segments, notes, selected, uri, warnings)
    }

    private fun readOpenings(input: DataInputStream, version: Int): List<WallOpening?> =
        List(input.readCount().also {
            require(it <= 1000) { "Demasiados vanos en el borrador." }
        }) {
            when (version) {
                2 -> readLegacyOpening(input)
                3 -> readVersion3Opening(input)
                else -> readOpening(input)
            }
        }

    /** Reads a version-2 opening; doors are no longer compatible and return null. */
    private fun readLegacyOpening(input: DataInputStream): WallOpening? {
        val kind = input.readInt()
        require(kind in 0..1) { "Tipo de vano inválido." }
        val offset = input.readLong()
        val length = input.readLong()
        val alignment = OpeningAlignment.entries[input.readOpeningAlignment()]
        val frameWidth = input.readLong()
        input.readBoolean() // hingeAtStart, unused by windows
        input.readBoolean() // swingTowardWall, unused by windows
        input.readInt() // openingAngleDegrees
        return if (kind == 1) {
            WindowOpening(offset, length, alignment, frameWidth)
        } else {
            null
        }
    }

    /** Reads a version-3 opening; the framed door of that version is no longer compatible. */
    private fun readVersion3Opening(input: DataInputStream): WallOpening? {
        val type = input.readInt()
        require(type == TYPE_DOOR || type == TYPE_WINDOW) { "Tipo de vano inválido." }
        return if (type == TYPE_DOOR) {
            input.readLong() // offset
            input.readLong() // frameWidth
            input.readLong() // frameDepth
            input.readLong() // leafWidth
            input.readLong() // leafThickness
            input.readInt() // alignment
            input.readBoolean() // hingeAtStart
            input.readBoolean() // swingTowardNearFace
            null
        } else {
            WindowOpening(
                offsetMm = input.readLong(),
                holeLengthMm = input.readLong(),
                alignment = OpeningAlignment.entries[input.readOpeningAlignment()],
                frameDepthMm = input.readLong()
            )
        }
    }

    private fun readOpening(input: DataInputStream): WallOpening = when (val type = input.readInt()) {
        TYPE_DOOR -> DoorOpening(
            offsetMm = input.readLong(),
            holeLengthMm = input.readLong(),
            hingeAtStart = input.readBoolean(),
            openAngleDegrees = input.readDouble(),
            swingTowardNearFace = input.readBoolean(),
            leafThicknessMm = input.readLong()
        )
        TYPE_WINDOW -> WindowOpening(
            offsetMm = input.readLong(),
            holeLengthMm = input.readLong(),
            alignment = OpeningAlignment.entries[input.readOpeningAlignment()],
            frameDepthMm = input.readLong()
        )
        else -> throw IllegalArgumentException("Tipo de vano desconocido: $type.")
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

    private fun DataInputStream.readOpeningAlignment(): Int = readInt().also {
        require(it in OpeningAlignment.entries.indices) { "Alineación de vano inválida." }
    }
}
