package cl.fernandocuevas.draftbeam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftSaveGateTest {
    @Test fun savesOnlyAfterChangesAndSkipsUnchangedMinutes() {
        val gate = DraftSaveGate()
        assertNull(gate.pending())
        gate.changed()
        var writes = 0
        assertTrue(gate.write(gate.pending()!!) { writes++ })
        assertNull(gate.pending())
        gate.changed()
        assertTrue(gate.write(gate.pending()!!) { writes++ })
        assertEquals(2, writes)
    }

    @Test fun saveAndImportDiscardQueuedOldDrafts() {
        val gate = DraftSaveGate()
        gate.changed()
        val queued = gate.pending()!!
        var deleted = false
        gate.clean { deleted = true }
        assertTrue(deleted)
        assertFalse(gate.write(queued) { error("Recreó el borrador eliminado") })
        assertNull(gate.pending())
        gate.changed()
        assertNotNull(gate.pending())
    }

    @Test fun laterEditsAndStoppingInvalidateOlderSnapshots() {
        val gate = DraftSaveGate()
        gate.changed()
        val old = gate.pending()!!
        gate.changed()
        assertFalse(gate.write(old) { error("Guardó una revisión obsoleta") })
        val beforeStop = gate.pending()!!
        gate.invalidatePending()
        assertFalse(gate.write(beforeStop) { error("Guardó tras la salida") })
        assertTrue(gate.write(gate.pending()!!) {})
    }

    @Test fun recoveredDraftIsNotRewrittenUntilChanged() {
        val gate = DraftSaveGate()
        gate.restored()
        assertNull(gate.pending())
        gate.changed()
        assertNotNull(gate.pending())
    }
}
