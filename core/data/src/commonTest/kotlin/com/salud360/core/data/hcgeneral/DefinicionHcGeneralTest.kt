package com.salud360.core.data.hcgeneral

import com.salud360.core.model.especialidad.CatalogoHc
import com.salud360.core.model.especialidad.CategoriaAntecedentes
import com.salud360.core.model.especialidad.EspecialidadDefinition
import com.salud360.core.model.especialidad.LaboratorioDef
import com.salud360.core.model.especialidad.PerfilHc
import com.salud360.core.model.especialidad.PestaniaDef
import com.salud360.core.model.especialidad.PestaniaPerfil
import com.salud360.core.model.especialidad.SeccionCatalogo
import com.salud360.core.model.especialidad.SeccionDef
import com.salud360.core.model.especialidad.SeccionPerfil
import com.salud360.core.model.especialidad.SeccionesComunes
import com.salud360.core.model.especialidad.TipoConsultaHc.CONSULTA
import com.salud360.core.model.especialidad.TipoConsultaHc.TELEMEDICINA
import com.salud360.core.model.especialidad.TipoConsultaDef
import com.salud360.core.model.especialidad.TipoSeccion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Definición de la HC general armada con el perfil del médico (RF-7, RF-19 a RF-21, RF-24, RF-27, RF-28). */
class DefinicionHcGeneralTest {
    private val catalogo = CatalogoHc(
        base = EspecialidadDefinition("clinica", "Clínica", "hclinica", emptyList(), emptyList()),
        secciones = listOf(
            SeccionCatalogo(
                SeccionDef("antecedentes_familiares", "Antecedentes familiares", TipoSeccion.ANTECEDENTES, categoriaAntecedentes = "familiares"),
                antecedentes = listOf(CategoriaAntecedentes("familiares", "Antecedentes familiares", emptyList())),
            ),
            SeccionCatalogo(SeccionDef("motivo_consulta", "Motivo de consulta", TipoSeccion.TEXTO)),
            SeccionCatalogo(
                SeccionDef("laboratorio_general", "Laboratorio general", TipoSeccion.LABORATORIO, laboratorioTipo = "laboratorio_general"),
                laboratorios = listOf(LaboratorioDef("laboratorio_general", "Laboratorio general", emptyList())),
            ),
            SeccionCatalogo(SeccionDef("datos_subjetivos", "Datos subjetivos", TipoSeccion.TEXTO)),
            SeccionCatalogo(SeccionDef("examen_fisico", "Examen físico", TipoSeccion.EXAMEN_FISICO)),
            SeccionCatalogo(SeccionDef("conductas", "Conductas", TipoSeccion.TEXTO)),
        ),
        legadas = listOf(SeccionCatalogo(SeccionDef("diagnostico", "Diagnósticos", TipoSeccion.DIAGNOSTICOS))),
    )

    // Pestaña "Antecedentes" en los dos tipos; "Laboratorio" solo en Consulta (vacía en telemedicina); una sección que
    // esta versión no conoce.
    private val perfil = PerfilHc(
        id = 7,
        nombre = "Perfil de prueba",
        pestanias = listOf(
            PestaniaPerfil("Antecedentes", listOf(SeccionPerfil("antecedentes_familiares", "Familia", setOf(CONSULTA, TELEMEDICINA)))),
            PestaniaPerfil(
                "Consulta del día",
                listOf(
                    SeccionPerfil("motivo_consulta", "Por qué viene", setOf(CONSULTA)),
                    SeccionPerfil("seccion_del_futuro", "Algo nuevo", setOf(CONSULTA, TELEMEDICINA)),
                    SeccionPerfil("datos_subjetivos", "Datos subjetivos", setOf(TELEMEDICINA)),
                    SeccionPerfil("conductas", "Indicaciones", setOf(CONSULTA, TELEMEDICINA)),
                ),
            ),
            PestaniaPerfil("Laboratorio", listOf(SeccionPerfil("laboratorio_general", "Laboratorio general", setOf(CONSULTA)))),
        ),
    )

    private fun armada(p: PerfilHc? = perfil) = armarDefinicion(catalogo, p)

    private fun grupos(tipo: String, p: PerfilHc? = perfil) = assertNotNull(armada(p).definicion.tipoConsulta(tipo)).grupos

    @Test
    fun definicion_clinica_con_todas_las_secciones_que_la_app_conoce() {
        val def = armada().definicion
        assertEquals("clinica", def.codigo)
        assertEquals(
            listOf("antecedentes_familiares", "motivo_consulta", "laboratorio_general", "datos_subjetivos", "examen_fisico", "conductas", "diagnostico"),
            def.secciones.map { it.id },
        )
        assertEquals(listOf("familiares"), def.antecedentes.map { it.categoria })
        assertEquals(listOf("laboratorio_general"), def.laboratorios.map { it.tipo })
    }

    @Test
    fun titulos_del_perfil_y_por_defecto_si_no_esta() {
        val def = armada().definicion
        assertEquals("Familia", def.seccion("antecedentes_familiares")?.titulo)
        assertEquals("Por qué viene", def.seccion("motivo_consulta")?.titulo)
        assertEquals("Indicaciones", def.seccion("conductas")?.titulo)
        assertEquals("Examen físico", def.seccion("examen_fisico")?.titulo)
        assertEquals("Diagnósticos", def.seccion("diagnostico")?.titulo)
    }

    @Test
    fun consulta_con_las_pestanias_del_perfil_en_orden_y_con_sus_nombres() {
        assertEquals(
            listOf(
                PestaniaDef("pestania_0", "Antecedentes", listOf("antecedentes_familiares")),
                PestaniaDef("pestania_1", "Consulta del día", listOf("motivo_consulta", "conductas")),
                PestaniaDef("pestania_2", "Laboratorio", listOf("laboratorio_general")),
            ),
            grupos(SeccionesComunes.TIPO_CONTROL),
        )
    }

    @Test
    fun telemedicina_sin_la_pestania_que_no_tiene_secciones_para_ese_tipo() {
        assertEquals(
            listOf(
                PestaniaDef("pestania_0", "Antecedentes", listOf("antecedentes_familiares")),
                PestaniaDef("pestania_1", "Consulta del día", listOf("datos_subjetivos", "conductas")),
            ),
            grupos(SeccionesComunes.TIPO_TELEMEDICINA),
        )
    }

    @Test
    fun la_ficha_es_la_unica_pestania_fija_y_las_secciones_del_tipo_son_las_de_sus_grupos() {
        val tipo = assertNotNull(armada().definicion.tipoConsulta(SeccionesComunes.TIPO_CONTROL))
        assertEquals(listOf(SeccionesComunes.DATOS_PACIENTE), tipo.pestanias)
        assertEquals(listOf("antecedentes_familiares", "motivo_consulta", "conductas", "laboratorio_general"), tipo.secciones)
    }

    @Test
    fun id_desconocido_omitido_y_listado() {
        val a = armada()
        assertEquals(listOf("seccion_del_futuro"), a.desconocidas)
        assertTrue(a.definicion.tiposConsulta.flatMap { t -> t.grupos.flatMap { it.secciones } }.none { it == "seccion_del_futuro" })
        assertEquals(null, a.definicion.seccion("seccion_del_futuro"))
        assertEquals(false, a.sinPerfil)
    }

    @Test
    fun una_legada_en_el_perfil_cuenta_como_desconocida() {
        val conLegada = perfil.copy(
            pestanias = listOf(PestaniaPerfil("Otra", listOf(SeccionPerfil("diagnostico", "Diagnósticos del perfil", setOf(CONSULTA))))),
        )
        val a = armada(conLegada)
        assertEquals(listOf("diagnostico"), a.desconocidas)
        assertEquals("Diagnósticos", a.definicion.seccion("diagnostico")?.titulo)
        assertEquals(emptyList(), grupos(SeccionesComunes.TIPO_CONTROL, conLegada))
    }

    @Test
    fun sin_perfil_tipos_sin_grupos_ni_secciones() {
        val a = armada(null)
        assertTrue(a.sinPerfil)
        assertEquals(emptyList(), a.desconocidas)
        a.definicion.tiposConsulta.forEach { t ->
            assertEquals(emptyList(), t.grupos, t.codigo)
            assertEquals(emptyList(), t.secciones, t.codigo)
        }
        assertEquals("Motivo de consulta", a.definicion.seccion("motivo_consulta")?.titulo)
    }

    @Test
    fun tipos_consulta_telemedicina_y_hc_digitalizada_solo_para_leer() {
        val def = armada().definicion
        assertEquals(
            listOf(SeccionesComunes.TIPO_CONTROL, SeccionesComunes.TIPO_TELEMEDICINA, SeccionesComunes.TIPO_FOTO),
            def.tiposConsulta.map { it.codigo },
        )
        // Sin secciones ni grupos: la pantalla no lo ofrece para consultas nuevas; sus fotos salen como solo lectura.
        val foto = assertNotNull(def.tipoConsulta(SeccionesComunes.TIPO_FOTO))
        assertEquals(emptyList(), foto.secciones)
        assertEquals(emptyList(), foto.grupos)
    }

    @Test
    fun mismo_id_en_dos_perfiles_con_nombres_distintos() {
        val otro = PerfilHc(
            id = 8,
            nombre = "Otro perfil",
            pestanias = listOf(PestaniaPerfil("Hoy", listOf(SeccionPerfil("motivo_consulta", "Motivo", setOf(CONSULTA))))),
        )
        assertEquals("Por qué viene", armada(perfil).definicion.seccion("motivo_consulta")?.titulo)
        assertEquals("Motivo", armada(otro).definicion.seccion("motivo_consulta")?.titulo)
        assertEquals("Por qué viene", armada(perfil).definicion.seccion("motivo_consulta")?.titulo)
        assertEquals(listOf(PestaniaDef("pestania_0", "Hoy", listOf("motivo_consulta"))), grupos(SeccionesComunes.TIPO_CONTROL, otro))
    }

    @Test
    fun visibles_sin_las_ocultas_y_sin_pestanias_que_quedan_vacias() {
        val tipo = assertNotNull(armada().definicion.tipoConsulta(SeccionesComunes.TIPO_CONTROL))
        assertEquals(
            listOf(
                PestaniaDef("pestania_0", "Antecedentes", listOf("antecedentes_familiares")),
                PestaniaDef("pestania_1", "Consulta del día", listOf("conductas")),
            ),
            seccionesVisibles(tipo, setOf("motivo_consulta", "laboratorio_general")),
        )
    }

    @Test
    fun ocultas_fuera_del_perfil_se_ignoran_y_nunca_se_muestra_algo_fuera_del_perfil() {
        val tipo = assertNotNull(armada().definicion.tipoConsulta(SeccionesComunes.TIPO_CONTROL))
        // "examen_fisico" la conoce la app pero no está en el perfil; "" sale de un csv vacío.
        val ocultas = setOf("examen_fisico", "seccion_vieja", "")
        assertEquals(tipo.grupos, seccionesVisibles(tipo, ocultas))
        assertTrue(seccionesVisibles(tipo, emptySet()).flatMap { it.secciones }.none { it == "examen_fisico" })
    }

    @Test
    fun oculta_que_vuelve_al_perfil_sigue_oculta() {
        // El administrador quita "conductas" y la vuelve a poner: la preferencia del médico no se tocó, sigue oculta.
        val ocultas = setOf("conductas")
        val sinConductas = perfil.copy(pestanias = perfil.pestanias.map { p -> p.copy(secciones = p.secciones.filter { it.seccion != "conductas" }) })
        val tipoSin = assertNotNull(armada(sinConductas).definicion.tipoConsulta(SeccionesComunes.TIPO_CONTROL))
        assertTrue(seccionesVisibles(tipoSin, ocultas).flatMap { it.secciones }.none { it == "conductas" })
        val tipoCon = assertNotNull(armada(perfil).definicion.tipoConsulta(SeccionesComunes.TIPO_CONTROL))
        assertTrue(seccionesVisibles(tipoCon, ocultas).flatMap { it.secciones }.none { it == "conductas" })
        assertEquals(setOf("conductas"), ocultas)
    }

    // ---- pestañas de la consulta (RF-20, RF-21, "Decidido" 7) ----

    private val datos = PestaniaDef(SeccionesComunes.DATOS_PACIENTE, "Datos", emptyList())

    @Test
    fun consulta_con_datos_primero_y_despues_las_pestanias_del_perfil() {
        val tipo = assertNotNull(armada().definicion.tipoConsulta(SeccionesComunes.TIPO_CONTROL))
        // Abre en la primera: "Datos".
        assertEquals(listOf(datos) + tipo.grupos, pestaniasConsulta(tipo, emptySet()))
    }

    @Test
    fun telemedicina_solo_con_las_pestanias_que_tienen_secciones_de_telemedicina() {
        val tipo = assertNotNull(armada().definicion.tipoConsulta(SeccionesComunes.TIPO_TELEMEDICINA))
        assertEquals(listOf("Datos", "Antecedentes", "Consulta del día"), pestaniasConsulta(tipo, emptySet()).map { it.titulo })
    }

    @Test
    fun pestania_sin_secciones_visibles_por_ocultas_o_por_condicion_no_se_muestra() {
        val tipo = assertNotNull(armada().definicion.tipoConsulta(SeccionesComunes.TIPO_CONTROL))
        assertEquals(
            listOf(datos, PestaniaDef("pestania_1", "Consulta del día", listOf("motivo_consulta"))),
            pestaniasConsulta(tipo, setOf("laboratorio_general", "conductas")) { it != "antecedentes_familiares" },
        )
    }

    @Test
    fun todo_oculto_queda_solo_datos() {
        val tipo = assertNotNull(armada().definicion.tipoConsulta(SeccionesComunes.TIPO_CONTROL))
        assertEquals(listOf(datos), pestaniasConsulta(tipo, tipo.secciones.toSet()))
    }

    @Test
    fun sin_grupos_ninguna_pestania_y_queda_la_pantalla_de_siempre() {
        // Sin perfil, HC digitalizada y las especialidades de siempre (RF-33).
        armada(null).definicion.tiposConsulta.forEach { t -> assertEquals(emptyList(), pestaniasConsulta(t, emptySet()), t.codigo) }
        conDiagnosticoEnUnTipo.tiposConsulta.forEach { t -> assertEquals(emptyList(), pestaniasConsulta(t, emptySet()), t.codigo) }
    }

    // ---- consultas viejas y sin configuración ("Decidido" 5 y 8, C10) ----

    @Test
    fun hc_digitalizada_de_la_hc_general_solo_para_leer_y_sin_reabrir() {
        val r = resolverDefinicion(catalogo, EstadoConfigHc.ConPerfil(perfil))
        assertTrue(r.soloLecturaPorTipo(SeccionesComunes.TIPO_FOTO))
        assertFalse(r.puedeReabrir(SeccionesComunes.TIPO_FOTO))
        assertFalse(r.soloLecturaPorTipo(SeccionesComunes.TIPO_CONTROL))
        assertTrue(r.puedeReabrir(SeccionesComunes.TIPO_CONTROL))
        assertTrue(r.puedeReabrir(SeccionesComunes.TIPO_TELEMEDICINA))
    }

    @Test
    fun sin_perfil_o_primera_vez_las_cerradas_no_se_reabren_y_la_abierta_se_sigue() {
        listOf(
            resolverDefinicion(catalogo, EstadoConfigHc.SinPerfil),
            resolverDefinicion(catalogo, EstadoConfigHc.PrimeraVezSinConexion("red", "Sin conexión")),
        ).forEach { r ->
            assertFalse(r.puedeReabrir(SeccionesComunes.TIPO_CONTROL))
            // La que ya estaba abierta no queda en lectura: se sigue en "Datos" y se cierra (C10).
            assertFalse(r.soloLecturaPorTipo(SeccionesComunes.TIPO_CONTROL))
        }
    }

    @Test
    fun especialidad_de_siempre_igual_que_antes() {
        val r = DefinicionResuelta.estatica(conDiagnosticoEnUnTipo.copy(tiposConsulta = conDiagnosticoEnUnTipo.tiposConsulta + TipoConsultaDef(SeccionesComunes.TIPO_FOTO, "Foto", "foto", emptyList())))
        assertFalse(r.soloLecturaPorTipo(SeccionesComunes.TIPO_FOTO))
        assertTrue(r.puedeReabrir(SeccionesComunes.TIPO_FOTO))
        assertTrue(r.puedeReabrir(SeccionesComunes.TIPO_CONTROL))
    }

    // ---- copia de diagnósticos ("Decidido" C2) ----

    /** Como hematología: diagnósticos solo en Control, no en Telemedicina. */
    private val conDiagnosticoEnUnTipo = EspecialidadDefinition(
        "hemato", "Hemato", "hemato",
        tiposConsulta = listOf(
            TipoConsultaDef(SeccionesComunes.TIPO_CONTROL, "Control", "control", secciones = listOf("diagnostico", "motivo_consulta")),
            TipoConsultaDef(SeccionesComunes.TIPO_TELEMEDICINA, "Telemedicina", "telemedicina", secciones = listOf("motivo_consulta")),
        ),
        secciones = listOf(
            SeccionDef("diagnostico", "Diagnósticos", TipoSeccion.DIAGNOSTICOS),
            SeccionDef("motivo_consulta", "Motivo de consulta", TipoSeccion.TEXTO),
        ),
    )

    @Test
    fun la_legada_de_diagnosticos_en_la_definicion_armada_no_copia() {
        // Está en `secciones` para leer lo viejo, pero en ningún tipo.
        assertNotNull(armada().definicion.seccion("diagnostico"))
        assertFalse(copiaDiagnosticos(armada().definicion))
        assertFalse(copiaDiagnosticos(armada(null).definicion))
    }

    @Test
    fun diagnosticos_en_un_solo_tipo_copia() {
        assertTrue(copiaDiagnosticos(conDiagnosticoEnUnTipo))
    }

    @Test
    fun diagnosticos_en_una_pestania_de_un_tipo_copia() {
        val def = conDiagnosticoEnUnTipo.copy(
            tiposConsulta = listOf(TipoConsultaDef(SeccionesComunes.TIPO_CONTROL, "Control", "control", secciones = listOf("motivo_consulta"), pestanias = listOf("diagnostico"))),
        )
        assertTrue(copiaDiagnosticos(def))
    }

    @Test
    fun diagnosticos_en_secciones_pero_en_ningun_tipo_no_copia() {
        val def = conDiagnosticoEnUnTipo.copy(tiposConsulta = conDiagnosticoEnUnTipo.tiposConsulta.map { it.copy(secciones = it.secciones - "diagnostico") })
        assertFalse(copiaDiagnosticos(def))
    }

    // ---- definición resuelta (RF-19, RF-25, RF-26, RF-33) ----

    @Test
    fun resuelta_con_perfil_ofrece_los_tipos_con_pestanias() {
        val r = resolverDefinicion(catalogo, EstadoConfigHc.ConPerfil(perfil))
        assertEquals(armada().definicion, r.definicion)
        assertFalse(r.sinPerfil)
        assertNull(r.primeraVezSinConexion)
        assertEquals(listOf("seccion_del_futuro"), r.desconocidas)
        // Nunca "HC digitalizada".
        assertEquals(listOf(SeccionesComunes.TIPO_CONTROL, SeccionesComunes.TIPO_TELEMEDICINA), r.tiposNuevos.map { it.codigo })
    }

    @Test
    fun resuelta_con_un_perfil_sin_nada_en_telemedicina_ofrece_solo_consulta() {
        val soloConsulta = PerfilHc(9, "Prueba", listOf(PestaniaPerfil("Uno", listOf(SeccionPerfil("motivo_consulta", "Motivo", setOf(CONSULTA))))))
        val r = resolverDefinicion(catalogo, EstadoConfigHc.ConPerfil(soloConsulta))
        assertEquals(listOf(SeccionesComunes.TIPO_CONTROL), r.tiposNuevos.map { it.codigo })
    }

    @Test
    fun resuelta_sin_perfil_no_ofrece_consultas_nuevas() {
        val r = resolverDefinicion(catalogo, EstadoConfigHc.SinPerfil)
        assertTrue(r.sinPerfil)
        assertNull(r.primeraVezSinConexion)
        assertEquals(armada(null).definicion, r.definicion)
        assertEquals(emptyList(), r.tiposNuevos)
    }

    @Test
    fun resuelta_primera_vez_sin_conexion_con_el_motivo() {
        val estado = EstadoConfigHc.PrimeraVezSinConexion("no_vinculado", "Tu usuario no está vinculado")
        val r = resolverDefinicion(catalogo, estado)
        assertFalse(r.sinPerfil)
        assertEquals(estado, r.primeraVezSinConexion)
        // Las consultas guardadas se siguen leyendo: la definición conoce todos los tipos y secciones.
        assertEquals(armada(null).definicion, r.definicion)
        assertEquals(emptyList(), r.tiposNuevos)
    }

    @Test
    fun resuelta_estatica_ofrece_todos_sus_tipos_como_siempre() {
        val r = DefinicionResuelta.estatica(conDiagnosticoEnUnTipo)
        assertEquals(conDiagnosticoEnUnTipo, r.definicion)
        assertFalse(r.sinPerfil)
        assertNull(r.primeraVezSinConexion)
        assertEquals(conDiagnosticoEnUnTipo.tiposConsulta, r.tiposNuevos)
    }
}
