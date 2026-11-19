package com.salud360.core.data.hcgeneral

import com.salud360.core.model.especialidad.PerfilHc
import com.salud360.core.model.especialidad.PestaniaPerfil
import com.salud360.core.model.especialidad.SeccionPerfil
import com.salud360.core.model.especialidad.TipoConsultaHc.CONSULTA
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Errores de validación (locales o del 422) ubicados junto a su campo en el editor (RF-4, RF-13). */
class ErroresPorCampoTest {
    private val nota = SeccionPerfil("nota", "Nota", setOf(CONSULTA))
    private val motivo = SeccionPerfil("motivo_consulta", "Motivo", emptySet())

    private val perfil = PerfilHc(
        id = 3,
        nombre = "Perfil de prueba",
        pestanias = listOf(
            PestaniaPerfil("Uno", listOf(nota)),
            PestaniaPerfil("Dos", listOf(nota, motivo)),
        ),
    )

    @Test
    fun sin_errores_esta_vacio() {
        val ubicados = ubicarErrores(emptyList(), perfil)
        assertTrue(ubicados.vacio)
        assertEquals(emptyList(), ubicados.nombre)
        assertEquals(emptyList(), ubicados.generales)
        assertEquals(emptyList(), ubicados.pestania(0))
        assertEquals(emptyList(), ubicados.seccion(1, 1))
    }

    @Test
    fun cada_forma_de_campo_va_a_su_lugar() {
        val ubicados = ubicarErrores(
            listOf(
                ErrorPerfil("nombre", "perfil"),
                ErrorPerfil("pestanias.1.nombre", "pestaña"),
                ErrorPerfil("pestanias.0.secciones.0.nombre", "nombre de sección"),
                ErrorPerfil("pestanias.1.secciones.0.seccion", "repetida"),
                ErrorPerfil("pestanias.1.secciones.1", "sin tipos"),
                ErrorPerfil("pestanias", "sin secciones"),
            ),
            perfil,
        )
        assertEquals(listOf("perfil"), ubicados.nombre)
        assertEquals(listOf("pestaña"), ubicados.pestania(1))
        assertEquals(emptyList(), ubicados.pestania(0))
        assertEquals(listOf("nombre de sección"), ubicados.nombreSeccion(0, 0))
        assertEquals(emptyList(), ubicados.seccion(0, 0))
        assertEquals(listOf("repetida"), ubicados.seccion(1, 0))
        assertEquals(listOf("sin tipos"), ubicados.seccion(1, 1))
        assertEquals(listOf("sin secciones"), ubicados.generales)
        assertTrue(!ubicados.vacio)
    }

    @Test
    fun varios_mensajes_del_mismo_campo_se_conservan_en_orden() {
        val ubicados = ubicarErrores(
            listOf(ErrorPerfil("pestanias.1.secciones.1", "a"), ErrorPerfil("pestanias.1.secciones.1.seccion", "b")),
            perfil,
        )
        assertEquals(listOf("a", "b"), ubicados.seccion(1, 1))
    }

    @Test
    fun otras_formas_bajo_una_pestania_o_seccion_van_a_ese_lugar() {
        // Por si el servidor suma campos (por ejemplo, un tipo): se muestran en la sección o pestaña, no se pierden.
        val ubicados = ubicarErrores(
            listOf(ErrorPerfil("pestanias.0.secciones.0.consulta", "tipo"), ErrorPerfil("pestanias.0.secciones", "lista")),
            perfil,
        )
        assertEquals(listOf("tipo"), ubicados.seccion(0, 0))
        assertEquals(listOf("lista"), ubicados.pestania(0))
    }

    @Test
    fun lo_que_no_tiene_lugar_en_el_perfil_va_a_generales() {
        // Índices fuera del borrador (por ejemplo, un 422 de antes de quitar una pestaña) o campos desconocidos.
        val ubicados = ubicarErrores(
            listOf(
                ErrorPerfil("pestanias.5.nombre", "pestaña que no está"),
                ErrorPerfil("pestanias.0.secciones.4", "sección que no está"),
                ErrorPerfil("id", "otro"),
            ),
            perfil,
        )
        assertEquals(listOf("pestaña que no está", "sección que no está", "otro"), ubicados.generales)
        assertEquals(emptyList(), ubicados.pestania(0))
        assertEquals(emptyList(), ubicados.seccion(0, 0))
    }
}
