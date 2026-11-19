package com.salud360.core.data.hcgeneral

import com.salud360.core.model.especialidad.PerfilHc
import com.salud360.core.model.especialidad.PestaniaPerfil
import com.salud360.core.model.especialidad.SeccionCatalogoRemota
import com.salud360.core.model.especialidad.SeccionPerfil
import com.salud360.core.model.especialidad.TipoConsultaHc.CONSULTA
import com.salud360.core.model.especialidad.TipoConsultaHc.TELEMEDICINA
import kotlin.test.Test
import kotlin.test.assertEquals

/** Edición del perfil en borrador (RF-5 a RF-8, RF-16). Perfiles y secciones inventados. */
class EditorPerfilTest {
    private val motivo = SeccionPerfil("motivo_consulta", "Motivo", setOf(CONSULTA))
    private val examen = SeccionPerfil("examen_fisico", "Examen", setOf(CONSULTA, TELEMEDICINA))
    private val nota = SeccionPerfil("nota", "Nota", setOf(CONSULTA))
    private val labo = SeccionPerfil("laboratorio_general", "Laboratorio", setOf(TELEMEDICINA))

    /** Una sección que esta versión de la app no conoce (RF-16): tiene que quedar tal cual. */
    private val desconocida = SeccionPerfil("seccion_futura", "Futura", setOf(TELEMEDICINA))

    private val perfil = PerfilHc(
        id = 7,
        nombre = "Perfil de prueba",
        pestanias = listOf(
            PestaniaPerfil("Uno", listOf(motivo, examen, nota)),
            PestaniaPerfil("Dos", listOf(labo)),
            PestaniaPerfil("Tres", listOf(desconocida)),
        ),
    )

    private val catalogo = listOf(
        SeccionCatalogoRemota("motivo_consulta", "Motivo de consulta"),
        SeccionCatalogoRemota("conductas", "Conductas", "Indicaciones"),
        SeccionCatalogoRemota("nota", "Nota"),
    )

    private fun PerfilHc.nombresPestanias() = pestanias.map { it.nombre }
    private fun PerfilHc.ids(i: Int) = pestanias[i].secciones.map { it.seccion }

    // ---- Pestañas ----

    @Test
    fun agregar_pestania_va_al_final_con_nombre_por_defecto() {
        val cambiado = perfil.agregarPestania()
        assertEquals(listOf("Uno", "Dos", "Tres", "Pestaña 1"), cambiado.nombresPestanias())
        assertEquals(emptyList(), cambiado.pestanias.last().secciones)
        assertEquals(7, cambiado.id)
    }

    @Test
    fun agregar_pestania_con_pestania_1_ya_usada() {
        // "pestana  1" es el mismo nombre para `claveDeNombre`.
        val conUsada = perfil.copy(pestanias = perfil.pestanias + PestaniaPerfil("pestana  1", emptyList()))
        assertEquals("Pestaña 2", conUsada.agregarPestania().pestanias.last().nombre)

        val dosUsadas = conUsada.agregarPestania()
        assertEquals("Pestaña 3", dosUsadas.agregarPestania().pestanias.last().nombre)
    }

    @Test
    fun agregar_pestania_a_un_perfil_vacio() {
        val vacio = PerfilHc(nombre = "", pestanias = emptyList())
        assertEquals(listOf("Pestaña 1"), vacio.agregarPestania().nombresPestanias())
    }

    @Test
    fun renombrar_pestania() {
        val cambiado = perfil.renombrarPestania(1, "Estudios")
        assertEquals(listOf("Uno", "Estudios", "Tres"), cambiado.nombresPestanias())
        assertEquals(listOf(labo), cambiado.pestanias[1].secciones)
    }

    @Test
    fun mover_pestania_arriba_y_abajo() {
        assertEquals(listOf("Dos", "Uno", "Tres"), perfil.moverPestania(1, -1).nombresPestanias())
        assertEquals(listOf("Uno", "Tres", "Dos"), perfil.moverPestania(1, +1).nombresPestanias())
    }

    @Test
    fun subir_la_primera_o_bajar_la_ultima_pestania_no_cambia_nada() {
        assertEquals(perfil, perfil.moverPestania(0, -1))
        assertEquals(perfil, perfil.moverPestania(2, +1))
    }

    @Test
    fun quitar_pestania_se_lleva_sus_secciones() {
        val cambiado = perfil.quitarPestania(0)
        assertEquals(listOf("Dos", "Tres"), cambiado.nombresPestanias())
        assertEquals(listOf("laboratorio_general"), cambiado.ids(0))
    }

    @Test
    fun quitar_la_pestania_con_una_seccion_desconocida_se_la_lleva() {
        val cambiado = perfil.quitarPestania(2)
        assertEquals(listOf("Uno", "Dos"), cambiado.nombresPestanias())
        assertEquals(false, cambiado.pestanias.any { p -> p.secciones.any { it.seccion == "seccion_futura" } })
    }

    @Test
    fun mover_o_renombrar_otras_no_toca_la_seccion_desconocida() {
        val cambiado = perfil
            .renombrarPestania(0, "Primera")
            .moverPestania(0, +1)
            .moverSeccion(1, 0, +1)
            .renombrarSeccion(1, 0, "Exámenes")
            .moverSeccionAPestania(0, 0, 1)
            .quitarSeccion(1, 2)
        assertEquals(listOf("Dos", "Primera", "Tres"), cambiado.nombresPestanias())
        assertEquals(PestaniaPerfil("Tres", listOf(desconocida)), cambiado.pestanias[2])
    }

    // ---- Secciones ----

    @Test
    fun agregar_seccion_va_al_final_con_nombre_por_defecto_y_solo_consulta() {
        val cambiado = perfil.agregarSeccion(1, "conductas", catalogo)
        assertEquals(listOf("laboratorio_general", "conductas"), cambiado.ids(1))
        assertEquals(SeccionPerfil("conductas", "Conductas", setOf(CONSULTA)), cambiado.pestanias[1].secciones.last())
    }

    @Test
    fun agregar_seccion_fuera_del_catalogo_o_ya_en_el_perfil_no_cambia_nada() {
        assertEquals(perfil, perfil.agregarSeccion(1, "inventada", catalogo))
        // "nota" ya está en "Uno": una sección va una sola vez en el perfil (RF-9).
        assertEquals(perfil, perfil.agregarSeccion(1, "nota", catalogo))
    }

    @Test
    fun renombrar_seccion() {
        val cambiado = perfil.renombrarSeccion(0, 2, "Nota final")
        assertEquals(SeccionPerfil("nota", "Nota final", setOf(CONSULTA)), cambiado.pestanias[0].secciones[2])
        assertEquals(listOf("motivo_consulta", "examen_fisico", "nota"), cambiado.ids(0))
    }

    @Test
    fun marcar_y_desmarcar_tipos() {
        val conTele = perfil.marcarTipo(0, 0, TELEMEDICINA, true)
        assertEquals(setOf(CONSULTA, TELEMEDICINA), conTele.pestanias[0].secciones[0].tipos)

        val sinConsulta = conTele.marcarTipo(0, 0, CONSULTA, false)
        assertEquals(setOf(TELEMEDICINA), sinConsulta.pestanias[0].secciones[0].tipos)

        // Puede quedar sin tipos: eso lo marca `validarPerfil`, no el editor.
        assertEquals(emptySet(), sinConsulta.marcarTipo(0, 0, TELEMEDICINA, false).pestanias[0].secciones[0].tipos)
        // Marcar lo ya marcado no cambia nada.
        assertEquals(perfil, perfil.marcarTipo(0, 1, CONSULTA, true))
    }

    @Test
    fun mover_seccion_arriba_y_abajo() {
        assertEquals(listOf("examen_fisico", "motivo_consulta", "nota"), perfil.moverSeccion(0, 1, -1).ids(0))
        assertEquals(listOf("motivo_consulta", "nota", "examen_fisico"), perfil.moverSeccion(0, 1, +1).ids(0))
    }

    @Test
    fun subir_la_primera_o_bajar_la_ultima_seccion_no_cambia_nada() {
        assertEquals(perfil, perfil.moverSeccion(0, 0, -1))
        assertEquals(perfil, perfil.moverSeccion(0, 2, +1))
    }

    @Test
    fun mover_seccion_a_otra_pestania_va_al_final() {
        val cambiado = perfil.moverSeccionAPestania(0, 0, 1)
        assertEquals(listOf("examen_fisico", "nota"), cambiado.ids(0))
        assertEquals(listOf("laboratorio_general", "motivo_consulta"), cambiado.ids(1))
        // Con su nombre y sus tipos.
        assertEquals(motivo, cambiado.pestanias[1].secciones.last())
    }

    @Test
    fun mover_seccion_a_la_misma_pestania_no_cambia_nada() {
        assertEquals(perfil, perfil.moverSeccionAPestania(0, 1, 0))
    }

    @Test
    fun quitar_seccion() {
        assertEquals(listOf("motivo_consulta", "nota"), perfil.quitarSeccion(0, 1).ids(0))
        // La pestaña queda aunque se quede sin secciones.
        assertEquals(listOf("Uno", "Dos", "Tres"), perfil.quitarSeccion(1, 0).nombresPestanias())
        assertEquals(emptyList(), perfil.quitarSeccion(1, 0).ids(1))
    }

    @Test
    fun indices_fuera_de_rango_no_cambian_nada() {
        listOf(
            perfil.renombrarPestania(3, "X"),
            perfil.renombrarPestania(-1, "X"),
            perfil.moverPestania(5, -1),
            perfil.quitarPestania(3),
            perfil.agregarSeccion(3, "conductas", catalogo),
            perfil.renombrarSeccion(0, 3, "X"),
            perfil.renombrarSeccion(3, 0, "X"),
            perfil.marcarTipo(1, 1, TELEMEDICINA, true),
            perfil.moverSeccion(0, 3, -1),
            perfil.moverSeccion(3, 0, +1),
            perfil.moverSeccionAPestania(0, 3, 1),
            perfil.moverSeccionAPestania(0, 0, 3),
            perfil.moverSeccionAPestania(-1, 0, 1),
            perfil.quitarSeccion(0, 3),
            perfil.quitarSeccion(3, 0),
        ).forEach { assertEquals(perfil, it) }
    }

    @Test
    fun el_id_no_cambia_nunca() {
        val cambiado = perfil
            .agregarPestania()
            .agregarSeccion(3, "conductas", catalogo)
            .quitarPestania(0)
            .moverSeccionAPestania(0, 0, 2)
        assertEquals(7, cambiado.id)
        assertEquals(null, perfil.copy(id = null).agregarPestania().id)
    }
}
