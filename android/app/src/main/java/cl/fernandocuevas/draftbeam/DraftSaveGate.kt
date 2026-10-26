package cl.fernandocuevas.draftbeam

/** Serializes draft writes/deletes and discards work captured before a newer edit or save. */
class DraftSaveGate {
    data class Token(val revision: Long, val generation: Long)

    private val lock = Any()
    private var revision = 0L
    private var generation = 0L
    private var dirty = false
    private var savedRevision: Long? = null

    fun changed() = synchronized(lock) {
        dirty = true
        revision++
    }

    fun restored() = synchronized(lock) {
        dirty = true
        savedRevision = revision
    }

    fun pending(): Token? = synchronized(lock) {
        if (dirty && savedRevision != revision) Token(revision, generation) else null
    }

    fun invalidatePending() = synchronized(lock) { generation++ }

    fun write(token: Token, action: () -> Unit): Boolean = synchronized(lock) {
        if (!dirty || savedRevision == revision || token.revision != revision || token.generation != generation) {
            return@synchronized false
        }
        action()
        savedRevision = revision
        true
    }

    fun clean(delete: () -> Unit) = synchronized(lock) {
        generation++
        dirty = false
        savedRevision = null
        delete()
    }
}
