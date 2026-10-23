package com.salud360.core.data.hcgeneral

import com.salud360.core.model.auth.PerfilMedico
import com.salud360.core.model.auth.PerfilSecretaria
import com.salud360.core.model.auth.Rol
import com.salud360.core.model.auth.Sesion
import com.salud360.core.model.auth.Usuario
import com.salud360.core.model.especialidad.CatalogoHc
import com.salud360.core.model.especialidad.EspecialidadDefinition
import com.salud360.core.model.especialidad.PerfilHc
import com.salud360.core.model.especialidad.PestaniaPerfil
import com.salud360.core.model.especialidad.SeccionCatalogo
import com.salud360.core.model.especialidad.SeccionDef
import com.salud360.core.model.especialidad.SeccionPerfil
import com.salud360.core.model.especialidad.SeccionesComunes.TIPO_CONTROL
import com.salud360.core.model.especialidad.SeccionesComunes.TIPO_TELEMEDICINA
import com.salud360.core.model.especialidad.TipoConsultaDef
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
 * Quién puede ver y editar la sección "Enfermedades" según la sesión (spec 003, RF-4 y RF-35). El médico de la sección
 * es el de la **sesión**, no el que recibe la pantalla (para la secretaria es el médico que eligió). Datos inventados.
 */
class AccesoEnfermedadesTest {
    private fun usuario(rol: Rol) = Usuario("u-1", "prueba@prueba.test", "Prueba", "Salud360", rol)

    private val perfilMedico = PerfilMedico(medicoId = "m-propio", especialidades = listOf("clinica"), tieneTurnos = true)

    private fun sesionMedico() = Sesion(usuario(Rol.MEDICO), token = "t", medico = perfilMedico)

    private fun sesionSecretaria(medico: PerfilMedico? = null) = Sesion(
        usuario(Rol.SECRETARIA), token = "t", medico = medico,
        secretaria = PerfilSecretaria("s-1", listOf("c-1"), listOf("m-elegido")),
    )

    private fun sesionAdmin(medico: PerfilMedico? = null) = Sesion(usuario(Rol.ADMIN), token = "t", medico = medico)

    // ---- medicoParaEnfermedades (RF-4) ----

    @Test
    fun el_medico_usa_su_propio_medico_id() {
        assertEquals("m-propio", medicoParaEnfermedades(sesionMedico()))
    }

    @Test
    fun la_secretaria_no_tiene_medico_propio_con_o_sin_medico_cargado() {
        assertNull(medicoParaEnfermedades(sesionSecretaria()))
        assertNull(medicoParaEnfermedades(sesionSecretaria(medico = PerfilMedico("m-elegido", listOf("clinica"), true))))
    }

    @Test
    fun el_administrador_no_tiene_medico_propio_aunque_tenga_un_medico_cargado() {
        assertNull(medicoParaEnfermedades(sesionAdmin()))
        assertNull(medicoParaEnfermedades(sesionAdmin(medico = PerfilMedico("m-elegido", listOf("clinica"), true))))
    }

    @Test
    fun un_medico_sin_perfil_de_medico_no_tiene_medico_propio() {
        assertNull(medicoParaEnfermedades(Sesion(usuario(Rol.MEDICO), token = "t", medico = null)))
    }

    // ---- consultaEditable (RF-35) ----

    @Test
    fun abierta_y_propia_se_edita() {
        assertTrue(consultaEditable(soloLectura = false, medicoDeLaConsulta = "m-propio", medicoPropio = "m-propio"))
    }

    @Test
    fun cerrada_no_se_edita_aunque_sea_propia() {
        assertFalse(consultaEditable(soloLectura = true, medicoDeLaConsulta = "m-propio", medicoPropio = "m-propio"))
    }

    @Test
    fun la_de_otro_medico_no_se_edita() {
        assertFalse(consultaEditable(soloLectura = false, medicoDeLaConsulta = "m-otro", medicoPropio = "m-propio"))
    }

    @Test
    fun sin_medico_propio_no_se_edita_ni_con_la_consulta_abierta() {
        assertFalse(consultaEditable(soloLectura = false, medicoDeLaConsulta = "m-elegido", medicoPropio = null))
    }

    // ---- visibleParaSesion (RF-4) ----

    @Test
    fun enfermedades_solo_se_ve_con_medico_propio() {
        assertTrue(visibleParaSesion(SECCION_ENFERMEDADES, "m-propio"))
        assertFalse(visibleParaSesion(SECCION_ENFERMEDADES, null))
    }

    @Test
    fun las_demas_secciones_se_ven_siempre() {
        for (id in listOf("antecedentes_familiares", "motivo_consulta", "diagnostico", "child_pugh", "enfermedades_legada")) {
            assertTrue(visibleParaSesion(id, "m-propio"), id)
            assertTrue(visibleParaSesion(id, null), id)
        }
    }

    @Test
    fun el_id_de_la_seccion_es_el_del_catalogo() {
        assertEquals("enfermedades", SECCION_ENFERMEDADES)
    }

    // ---- enfermedadesEnDatosDeConsulta (RF-4, RF-6, RF-7, RF-57) ----

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

    private val sinPerfil = armarDefinicion(catalogo, null).definicion

    /** Una HC de las de siempre (pediatría y las demás): sin perfil, o sea sin pestañas con la sección. */
    private val pediatria = EspecialidadDefinition(
        "pediatria", "Pediatría", "hcpediatria",
        tiposConsulta = listOf(TipoConsultaDef("control", "Control", "control", listOf("motivo"))),
        secciones = listOf(SeccionDef("motivo", "Motivo", TipoSeccion.TEXTO)),
    )

    @Test
    fun el_titulo_de_datos_es_el_nombre_por_defecto_de_la_seccion() {
        assertEquals("Enfermedades", TITULO_ENFERMEDADES_EN_DATOS)
    }

    @Test
    fun pediatria_y_las_hc_sin_perfil_no_piden_enfermedades_aunque_su_definicion_no_tenga_la_seccion() {
        // `enfermedadesEnDatos` solo mira la definición y da true para pediatría: lo frena `porPerfil` (RF-57).
        assertTrue(enfermedadesEnDatos(pediatria, emptySet()))
        assertFalse(enfermedadesEnDatosDeConsulta(pediatria, emptySet(), porPerfil = false, medicoPropio = "m-propio"))
        // Ni con la sección "oculta" en las preferencias del médico (la clave de pediatría no es la de la HC general).
        assertFalse(enfermedadesEnDatosDeConsulta(pediatria, setOf(SECCION_ENFERMEDADES), porPerfil = false, medicoPropio = "m-propio"))
        // Ni siquiera una HC sin `porPerfil` que casualmente tuviera la sección en un grupo.
        assertFalse(enfermedadesEnDatosDeConsulta(definicion(CONSULTA), setOf(SECCION_ENFERMEDADES), porPerfil = false, medicoPropio = "m-propio"))
    }

    @Test
    fun la_secretaria_y_el_administrador_nunca_las_piden_en_datos() {
        // Sin médico propio (RF-4), en cualquiera de los casos en que un médico sí las vería.
        for (def in listOf(sinPerfil, definicion(), definicion(CONSULTA))) {
            assertFalse(enfermedadesEnDatosDeConsulta(def, emptySet(), porPerfil = true, medicoPropio = null))
            assertFalse(enfermedadesEnDatosDeConsulta(def, setOf(SECCION_ENFERMEDADES), porPerfil = true, medicoPropio = null))
        }
    }

    @Test
    fun el_medico_sin_perfil_las_ve_en_datos() {
        assertTrue(enfermedadesEnDatosDeConsulta(sinPerfil, emptySet(), porPerfil = true, medicoPropio = "m-propio"))
    }

    @Test
    fun el_medico_con_la_seccion_fuera_del_perfil_para_todos_los_tipos_las_ve_en_datos() {
        assertTrue(enfermedadesEnDatosDeConsulta(definicion(), emptySet(), porPerfil = true, medicoPropio = "m-propio"))
    }

    @Test
    fun el_medico_que_oculto_la_seccion_las_ve_en_datos() {
        val oculta = setOf(SECCION_ENFERMEDADES)
        assertTrue(enfermedadesEnDatosDeConsulta(definicion(CONSULTA, TELEMEDICINA), oculta, porPerfil = true, medicoPropio = "m-propio"))
        assertTrue(enfermedadesEnDatosDeConsulta(definicion(CONSULTA), oculta, porPerfil = true, medicoPropio = "m-propio"))
    }

    @Test
    fun la_seccion_solo_en_consulta_no_va_en_datos_ni_en_telemedicina() {
        // RF-6: "para ningún tipo de consulta"; si está para uno, el otro tipo tampoco las muestra en "Datos".
        assertFalse(enfermedadesEnDatosDeConsulta(definicion(CONSULTA), emptySet(), porPerfil = true, medicoPropio = "m-propio"))
        assertFalse(enfermedadesEnDatosDeConsulta(definicion(TELEMEDICINA), emptySet(), porPerfil = true, medicoPropio = "m-propio"))
    }

    @Test
    fun en_el_perfil_y_visible_no_va_en_datos_porque_se_ve_en_la_seccion() {
        // RF-7: nunca a la vez en "Datos" y en la sección.
        val def = definicion(CONSULTA, TELEMEDICINA)
        assertFalse(enfermedadesEnDatosDeConsulta(def, emptySet(), porPerfil = true, medicoPropio = "m-propio"))
        assertTrue(seccionEnfermedadesVisible(def, TIPO_CONTROL, emptySet()))
    }

    @Test
    fun nunca_en_datos_y_en_la_seccion_a_la_vez_con_medico_propio() {
        val definiciones = listOf(sinPerfil, definicion(), definicion(CONSULTA), definicion(TELEMEDICINA), definicion(CONSULTA, TELEMEDICINA))
        val ocultas = listOf(emptySet(), setOf(SECCION_ENFERMEDADES), setOf("motivo_consulta"))
        for (def in definiciones) for (o in ocultas) {
            val enDatos = enfermedadesEnDatosDeConsulta(def, o, porPerfil = true, medicoPropio = "m-propio")
            for (tipo in listOf(TIPO_CONTROL, TIPO_TELEMEDICINA)) {
                assertFalse(seccionEnfermedadesVisible(def, tipo, o) && enDatos, "tipo=$tipo ocultas=$o")
            }
        }
    }
}
