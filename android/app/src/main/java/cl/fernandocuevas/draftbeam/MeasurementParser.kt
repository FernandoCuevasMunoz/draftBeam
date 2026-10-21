package cl.fernandocuevas.draftbeam

/** Decodes the measurement format observed on the meter's FFB2 characteristic. */
object MeasurementParser {
    private val measurementPattern = Regex("^([0-9]+)\\.([0-9]{3})m\\r\\n\\u0000$")

    fun parse(payload: ByteArray): MeasurementParseResult {
        val text = payload.toString(Charsets.US_ASCII)
        val match = measurementPattern.matchEntire(text)
            ?: return MeasurementParseResult.Invalid(
                "Formato inesperado; se esperaba, por ejemplo, 1.975m\\r\\n\\0."
            )

        val wholeMeters = match.groupValues[1].toLongOrNull()
            ?: return MeasurementParseResult.Invalid("La medida excede el rango permitido.")
        val fractionalMillimeters = match.groupValues[2].toLong()

        if (wholeMeters > (Long.MAX_VALUE - fractionalMillimeters) / MILLIMETERS_PER_METER) {
            return MeasurementParseResult.Invalid("La medida excede el rango permitido.")
        }

        val millimeters = wholeMeters * MILLIMETERS_PER_METER + fractionalMillimeters
        return MeasurementParseResult.Success(text, millimeters)
    }

    private const val MILLIMETERS_PER_METER = 1_000L
}

sealed interface MeasurementParseResult {
    data class Success(val rawText: String, val millimeters: Long) : MeasurementParseResult
    data class Invalid(val reason: String) : MeasurementParseResult
}

data class MeasurementNotification(
    val rawHex: String,
    val millimeters: Long? = null,
    val parseError: String? = null
)
