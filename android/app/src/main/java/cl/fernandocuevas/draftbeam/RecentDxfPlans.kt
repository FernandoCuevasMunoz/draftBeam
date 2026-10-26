package cl.fernandocuevas.draftbeam

data class RecentDxfPlan(val uri: String, val name: String)

object RecentDxfPlans {
    const val LIMIT = 5

    fun opened(plans: List<RecentDxfPlan>, plan: RecentDxfPlan): List<RecentDxfPlan> =
        (listOf(plan) + plans.filterNot { it.uri == plan.uri }).take(LIMIT)

    fun filename(input: String): String? {
        val name = input.trim()
        if (name.isEmpty() || name == "." || name == ".." || name.any { it == '/' || it == '\\' || it.isISOControl() }) {
            return null
        }
        return if (name.endsWith(".dxf", ignoreCase = true)) name else "$name.dxf"
    }
}
