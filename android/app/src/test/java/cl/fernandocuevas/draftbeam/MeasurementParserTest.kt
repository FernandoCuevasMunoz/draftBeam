package cl.fernandocuevas.draftbeam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementParserTest {
    @Test
    fun parsesObservedMeasurementsIntoMillimeters() {
        assertEquals(1_975L, parse("1.975m\r\n\u0000").millimetersOrFail())
        assertEquals(1_516L, parse("1.516m\r\n\u0000").millimetersOrFail())
        assertEquals(975L, parse("0.975m\r\n\u0000").millimetersOrFail())
    }

    @Test
    fun rejectsPayloadWithoutExpectedTerminator() {
        val result = parse("1.975m\r\n")

        assertTrue(result is MeasurementParseResult.Invalid)
    }

    @Test
    fun rejectsUnexpectedPrecisionOrUnit() {
        assertTrue(parse("1.97m\r\n\u0000") is MeasurementParseResult.Invalid)
        assertTrue(parse("1.975cm\r\n\u0000") is MeasurementParseResult.Invalid)
    }

    @Test
    fun rejectsMeasurementsOutsideLongRange() {
        assertTrue(
            parse("9223372036854776.000m\r\n\u0000") is MeasurementParseResult.Invalid
        )
    }

    private fun parse(text: String): MeasurementParseResult =
        MeasurementParser.parse(text.toByteArray(Charsets.US_ASCII))

    private fun MeasurementParseResult.millimetersOrFail(): Long {
        assertTrue("Expected successful parse, got $this", this is MeasurementParseResult.Success)
        return (this as MeasurementParseResult.Success).millimeters
    }
}
