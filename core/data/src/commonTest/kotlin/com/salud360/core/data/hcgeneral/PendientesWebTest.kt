package com.salud360.core.data.hcgeneral

import com.salud360.core.model.hc.Pendiente
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Los pendientes que vinieron de la web no se resuelven ni se reemplazan desde una consulta local (RF-62, Q16-A). */
class PendientesWebTest {
    private val web = Pendiente(idPendienteWeb(1797), "p1", "m1", "Pedir ecografía", null)
    private val local = Pendiente("3f2c9d1e-0a6b-4c11-9a77-5c1d2e3f4a5b", "p1", "m1", "Traer estudios", "c9")
    private val otroLocal = Pendiente("8b1a7c40-2d5e-4f33-8e90-1a2b3c4d5e6f", "p1", "m1", "Control de peso", null)

    @Test
    fun el_pendiente_con_id_de_la_web_es_de_la_web() {
        assertTrue(web.esDeLaWeb())
        assertFalse(web.puedeResolverse())
        assertFalse(local.esDeLaWeb())
        assertTrue(local.puedeResolverse())
    }

    @Test
    fun el_texto_para_el_cierre_es_solo_el_de_los_locales() {
        assertEquals("Traer estudios\nControl de peso", textoDePendientesLocales(listOf(web, local, otroLocal)))
        assertEquals("", textoDePendientesLocales(listOf(web)))
        assertEquals("", textoDePendientesLocales(emptyList()))
    }

    @Test
    fun al_cerrar_con_otro_texto_se_resuelven_los_locales_y_no_el_de_la_web() {
        val cambio = cambioDePendientes(listOf(web, local, otroLocal), "Nuevo pendiente")
        assertEquals(listOf(local.copy(resuelto = true), otroLocal.copy(resuelto = true)), cambio.resolver)
        assertEquals("Nuevo pendiente", cambio.crear)
        assertTrue(cambio.resolver.none { it.esDeLaWeb() })
    }

    @Test
    fun al_cerrar_con_el_mismo_texto_no_se_toca_nada() {
        val cambio = cambioDePendientes(listOf(web, local), "  Traer estudios \n")
        assertEquals(emptyList(), cambio.resolver)
        assertNull(cambio.crear)
    }

    @Test
    fun al_cerrar_con_el_texto_vacio_se_resuelven_los_locales_y_no_se_crea_nada() {
        val cambio = cambioDePendientes(listOf(web, local), "   ")
        assertEquals(listOf(local.copy(resuelto = true)), cambio.resolver)
        assertNull(cambio.crear)
    }

    @Test
    fun con_solo_un_pendiente_de_la_web_el_texto_nuevo_se_crea_y_el_de_la_web_queda() {
        val cambio = cambioDePendientes(listOf(web), "Llamar al paciente")
        assertEquals(emptyList(), cambio.resolver)
        assertEquals("Llamar al paciente", cambio.crear)
    }

    @Test
    fun con_solo_un_pendiente_de_la_web_y_el_texto_vacio_no_hay_cambio() {
        val cambio = cambioDePendientes(listOf(web), "")
        assertEquals(emptyList(), cambio.resolver)
        assertNull(cambio.crear)
    }

    @Test
    fun sin_pendientes_un_texto_nuevo_se_crea() {
        val cambio = cambioDePendientes(emptyList(), "  Algo  ")
        assertEquals(emptyList(), cambio.resolver)
        assertEquals("Algo", cambio.crear)
    }

    @Test
    fun los_locales_siguen_como_siempre() {
        // Mismo resultado que antes de E6.6 para quien no tiene nada de la web.
        val cambio = cambioDePendientes(listOf(local), "Otra cosa")
        assertEquals(listOf(local.copy(resuelto = true)), cambio.resolver)
        assertEquals("Otra cosa", cambio.crear)
    }

    @Test
    fun el_contador_de_pendientes_no_baja_por_los_de_la_web() {
        // Q16-A: lo no resuelto de la web sigue contando; aplicar el cambio deja sin resolver todo pendiente de la web.
        val antes = listOf(web, local, otroLocal)
        val cambio = cambioDePendientes(antes, "")
        val despues = antes.map { p -> cambio.resolver.firstOrNull { it.id == p.id } ?: p }
        assertEquals(listOf(web), despues.filter { !it.resuelto })
    }
}
