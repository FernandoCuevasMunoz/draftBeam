package cl.fernandocuevas.draftbeam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecentDxfPlansTest {
    @Test fun opensMostRecentFirstWithoutDuplicatingAndKeepsFive() {
        val plans = (1..5).map { RecentDxfPlan("content://plan/$it", "Plano $it.dxf") }
        val reopened = RecentDxfPlans.opened(plans, plans[3].copy(name = "Renombrado.dxf"))
        assertEquals(5, reopened.size)
        assertEquals("Renombrado.dxf", reopened.first().name)
        assertEquals(1, reopened.count { it.uri == plans[3].uri })
        val next = RecentDxfPlans.opened(reopened, RecentDxfPlan("content://plan/6", "Nuevo.dxf"))
        assertEquals(listOf("content://plan/6", "content://plan/4", "content://plan/1", "content://plan/2", "content://plan/3"), next.map { it.uri })
    }

    @Test fun filenameRequiresNameAndAddsDxfOnlyOnce() {
        assertEquals("Casa.dxf", RecentDxfPlans.filename(" Casa "))
        assertEquals("Casa.DXF", RecentDxfPlans.filename("Casa.DXF"))
        assertNull(RecentDxfPlans.filename("   "))
        assertNull(RecentDxfPlans.filename("../archivo"))
        assertNull(RecentDxfPlans.filename("carpeta\\archivo"))
        assertNull(RecentDxfPlans.filename("a\nb"))
    }
}
