package com.salud360.core.data.hcgeneral

import com.salud360.core.model.especialidad.CatalogoHc
import com.salud360.core.model.especialidad.EspecialidadDefinition
import com.salud360.core.model.especialidad.PerfilHc
import com.salud360.core.model.especialidad.PestaniaPerfil
import com.salud360.core.model.especialidad.SeccionCatalogo
import com.salud360.core.model.especialidad.SeccionDef
import com.salud360.core.model.especialidad.SeccionPerfil
import com.salud360.core.model.especialidad.SeccionesComunes.TIPO_CONTROL
import com.salud360.core.model.especialidad.SeccionesComunes.TIPO_TELEMEDICINA
import com.salud360.core.model.especialidad.TipoConsultaHc
import com.salud360.core.model.especialidad.TipoConsultaHc.CONSULTA
import com.salud360.core.model.especialidad.TipoConsultaHc.TELEMEDICINA
import com.salud360.core.model.especialidad.TipoSeccion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Estado y vista de la sección "Enfermedades" (spec 003): grilla y "Otras" (RF-15 a RF-19), estado actual en las
 * cerradas (RF-35) y cuándo se ve la sección o lo del paciente en "Datos" (RF-5 a RF-7). RF-36 no tiene código: la
 * sección no guarda nada por consulta (se prueba en el controlador, F2.8).
 */
class EnfermedadesSeccionTest {
    private val catalogo = CatalogoHc(
        base = EspecialidadDefinition("clinica", "Clínica", "hclinica", emptyList(), emptyList()),
        secciones = listOf(
            SeccionCatalogo(SeccionDef("motivo_consulta", "Motivo de consulta", TipoSeccion.TEXTO)),
            SeccionCatalogo(SeccionDef(SECCION_ENFERMEDADES, "Enfermedades", TipoSeccion.CUSTOM, renderKey = SECCION_ENFERMEDADES)),
        ),
    )

    private fun definicion(vararg tipos: TipoConsultaHc) = armarDefinicion(
        catalogo,
        PerfilHc(
            id = 3,
            nombre = "Perfil de prueba",
            pestanias = listOf(
                PestaniaPerfil(
                    "Consulta",
                    listOfNotNull(
                        SeccionPerfil("motivo_consulta", "Motivo", setOf(CONSULTA, TELEMEDICINA)),
                        if (tipos.isEmpty()) null else SeccionPerfil(SECCION_ENFERMEDADES, "Enfermedades", tipos.toSet()),
                    ),
                ),
            ),
        ),
    ).definicion

    private val enLosDos = definicion(CONSULTA, TELEMEDICINA)
    private val soloConsulta = definicion(CONSULTA)
    private val fueraDelPerfil = definicion()
    private val sinPerfil = armarDefinicion(catalogo, null).definicion

    private fun e(id: Long, nombre: String) = Enfermedad(id, nombre)
    private fun m(id: Long, nombre: String, tiene: Boolean) = Marcable(id, nombre, tiene)

    // RF-15: una casilla por marcable, con su marca.

    @Test
    fun abierta_muestra_una_casilla_por_marcable_con_su_marca() {
        val estado = EstadoSeccionEnfermedades(
            marcables = listOf(m(1, "Asma", true), m(2, "Diabetes", false)),
            otras = emptyList(),
        )
        val vista = vistaSeccion(estado, editable = true) as VistaSeccionEnfermedades.Grilla
        assertEquals(listOf(m(1, "Asma", true), m(2, "Diabetes", false)), vista.marcables)
        assertNull(vista.sinMarcables)
    }

    // RF-16: orden por clave (sin tildes ni mayúsculas) y, a igual clave, por id; como `ordenar` de hclinica.

    @Test
    fun orden_por_clave_con_desempate_por_id_como_el_php() {
        // Los mismos datos que `testOrdenPorClaveYDesempatePorId` de `EnfermedadesReglasTest.php`.
        val lista = listOf(
            e(7, "diabetes"), e(2, "Úlcera"), e(10, "Asma"), e(3, "DIABETES"), e(9, "ácido úrico"), e(1, "Bronquitis"),
        )
        assertEquals(listOf(9L, 10L, 1L, 3L, 7L, 2L), ordenAlfabetico(lista).map { it.id })
    }

    @Test
    fun orden_por_punto_de_codigo_como_strcmp() {
        // `strcmp` de PHP compara los bytes de UTF-8 (= puntos de código); `compareTo` de Kotlin, unidades de UTF-16, y
        // pondría el emoji (par sustituto, 0xD83D…) antes de la "Ａ" de ancho completo (U+FF21 → U+FF41 en la clave).
        val lista = listOf(e(1, "\uD83D\uDE00 tos"), e(2, "\uFF21sma"))
        assertEquals(listOf(2L, 1L), ordenAlfabetico(lista).map { it.id })
    }

    @Test
    fun la_grilla_y_las_otras_salen_ordenadas() {
        val estado = EstadoSeccionEnfermedades(
            marcables = listOf(m(5, "Úlcera", false), m(4, "asma", true), m(3, "Asma", false)),
            otras = listOf(e(8, "Diabetes"), e(6, "ácido úrico")),
        )
        val vista = vistaSeccion(estado, editable = true) as VistaSeccionEnfermedades.Grilla
        assertEquals(listOf(3L, 4L, 5L), vista.marcables.map { it.id })
        assertEquals(listOf(6L, 8L), vista.otras.map { it.id })
    }

    // RF-18 y RF-19: sin marcables, el texto en lugar de la grilla; "Otras" solo si hay. El botón "Administrar
    // enfermedades" no depende de la vista sino de `puedeAdministrar` (F2.5).

    @Test
    fun sin_marcables_muestra_el_texto_y_siguen_las_otras() {
        val estado = EstadoSeccionEnfermedades(marcables = emptyList(), otras = listOf(e(2, "Diabetes"), e(1, "Asma")))
        val vista = vistaSeccion(estado, editable = true) as VistaSeccionEnfermedades.Grilla
        assertEquals(
            "No tenés enfermedades en tu listado. Podés agregarlas con «Administrar enfermedades».",
            vista.sinMarcables,
        )
        assertEquals(emptyList(), vista.marcables)
        assertEquals("Otras enfermedades del paciente", vista.tituloOtras)
        assertEquals(listOf(1L, 2L), vista.otras.map { it.id })
    }

    @Test
    fun otras_solo_si_hay() {
        val sinOtras = vistaSeccion(EstadoSeccionEnfermedades(listOf(m(1, "Asma", true)), emptyList()), editable = true)
            as VistaSeccionEnfermedades.Grilla
        assertNull(sinOtras.tituloOtras)
        assertEquals(emptyList(), sinOtras.otras)
        val vacia = vistaSeccion(EstadoSeccionEnfermedades(emptyList(), emptyList()), editable = true)
            as VistaSeccionEnfermedades.Grilla
        assertNull(vacia.tituloOtras)
        assertEquals(TEXTO_SIN_MARCABLES, vacia.sinMarcables)
    }

    // RF-35: cerrada o solo para leer, el estado actual del paciente (marcadas + otras, juntas y ordenadas).

    @Test
    fun cerrada_muestra_marcadas_y_otras_juntas_y_ordenadas_sin_las_desmarcadas() {
        val estado = EstadoSeccionEnfermedades(
            marcables = listOf(m(1, "Diabetes", true), m(2, "Bronquitis", false), m(3, "Úlcera", true)),
            otras = listOf(e(4, "Asma"), e(5, "Celiaquía")),
        )
        val vista = vistaSeccion(estado, editable = false) as VistaSeccionEnfermedades.EstadoActual
        assertEquals("Estado actual del paciente", vista.leyenda)
        assertEquals(listOf("Asma", "Celiaquía", "Diabetes", "Úlcera"), vista.enfermedades.map { it.nombre })
        assertNull(vista.vacio)
    }

    @Test
    fun cerrada_sin_ninguna_dice_sin_enfermedades_registradas() {
        val soloDesmarcadas = EstadoSeccionEnfermedades(marcables = listOf(m(1, "Asma", false)), otras = emptyList())
        listOf(soloDesmarcadas, EstadoSeccionEnfermedades(emptyList(), emptyList())).forEach { estado ->
            val vista = vistaSeccion(estado, editable = false) as VistaSeccionEnfermedades.EstadoActual
            assertEquals(emptyList(), vista.enfermedades)
            assertEquals("Sin enfermedades registradas", vista.vacio)
        }
    }

    @Test
    fun enfermedades_del_paciente_para_datos_son_las_marcadas_y_las_otras_ordenadas() {
        val estado = EstadoSeccionEnfermedades(
            marcables = listOf(m(3, "diabetes", true), m(1, "Asma", false)),
            otras = listOf(e(2, "DIABETES"), e(4, "ácido úrico")),
        )
        assertEquals(listOf(4L, 2L, 3L), enfermedadesDelPaciente(estado).map { it.id })
        assertEquals(emptyList(), enfermedadesDelPaciente(EstadoSeccionEnfermedades(listOf(m(1, "Asma", false)), emptyList())))
    }

    // RF-5 a RF-7: en la consulta si está en el perfil para ese tipo y no está oculta; si no está para ningún tipo, o
    // está oculta, o no hay perfil, lo del paciente va en "Datos".

    @Test
    fun visible_en_consulta_y_no_en_telemedicina_si_solo_esta_en_consulta() {
        assertTrue(seccionEnfermedadesVisible(soloConsulta, TIPO_CONTROL, emptySet()))
        assertFalse(seccionEnfermedadesVisible(soloConsulta, TIPO_TELEMEDICINA, emptySet()))
        assertTrue(seccionEnfermedadesVisible(enLosDos, TIPO_TELEMEDICINA, emptySet()))
    }

    @Test
    fun en_el_perfil_solo_para_consulta_no_va_en_datos() {
        assertFalse(enfermedadesEnDatos(soloConsulta, emptySet()))
        assertFalse(enfermedadesEnDatos(enLosDos, emptySet()))
    }

    @Test
    fun sin_perfil_fuera_del_perfil_u_oculta_no_se_ve_y_va_en_datos() {
        listOf(
            Triple("sin perfil", sinPerfil, emptySet()),
            Triple("fuera del perfil", fueraDelPerfil, emptySet()),
            Triple("oculta", enLosDos, setOf(SECCION_ENFERMEDADES)),
            Triple("oculta, solo en Consulta", soloConsulta, setOf(SECCION_ENFERMEDADES)),
        ).forEach { (caso, def, ocultas) ->
            assertFalse(seccionEnfermedadesVisible(def, TIPO_CONTROL, ocultas), caso)
            assertFalse(seccionEnfermedadesVisible(def, TIPO_TELEMEDICINA, ocultas), caso)
            assertTrue(enfermedadesEnDatos(def, ocultas), caso)
        }
    }

    @Test
    fun ocultar_otra_seccion_no_cambia_nada() {
        assertTrue(seccionEnfermedadesVisible(enLosDos, TIPO_CONTROL, setOf("motivo_consulta")))
        assertFalse(enfermedadesEnDatos(enLosDos, setOf("motivo_consulta")))
    }

    @Test
    fun nunca_visible_y_en_datos_a_la_vez() {
        val definiciones = listOf(sinPerfil, fueraDelPerfil, soloConsulta, definicion(TELEMEDICINA), enLosDos)
        val ocultas = listOf(emptySet(), setOf(SECCION_ENFERMEDADES), setOf("motivo_consulta"))
        for (def in definiciones) for (o in ocultas) {
            val enDatos = enfermedadesEnDatos(def, o)
            for (tipo in listOf(TIPO_CONTROL, TIPO_TELEMEDICINA)) {
                assertFalse(seccionEnfermedadesVisible(def, tipo, o) && enDatos, "tipo=$tipo ocultas=$o")
            }
            // Y si no se ve en ningún tipo, va en "Datos": lo del paciente siempre tiene un lugar.
            val enAlguno = listOf(TIPO_CONTROL, TIPO_TELEMEDICINA).any { seccionEnfermedadesVisible(def, it, o) }
            if (!enAlguno) assertTrue(enDatos, "ocultas=$o")
        }
    }

    @Test
    fun la_seccion_nunca_sale_al_final_de_la_consulta() {
        // Es CUSTOM y no deja valores en `seccion_valor`: fuera del perfil no sale al final (spec 001 RF-22), ni
        // siquiera con algo guardado a mano bajo su id vacío. Lo del paciente va solo en "Datos" (RF-7).
        val datos = DatosConsulta(valores = mapOf("motivo_consulta" to mapOf("texto" to "Control")))
        listOf(fueraDelPerfil, sinPerfil).forEach { def ->
            val r = soloLecturaConsulta(catalogo, def, TIPO_TELEMEDICINA, datos, emptyList(), emptySet())
            assertFalse(r.alFinal.any { it.id == SECCION_ENFERMEDADES })
            assertFalse(r.enDatos.any { it.id == SECCION_ENFERMEDADES })
        }
        val conVacio = DatosConsulta(valores = mapOf(SECCION_ENFERMEDADES to mapOf("x" to "")))
        val r = soloLecturaConsulta(catalogo, fueraDelPerfil, TIPO_CONTROL, conVacio, emptyList(), emptySet())
        assertEquals(emptyList(), r.alFinal)
    }
}
