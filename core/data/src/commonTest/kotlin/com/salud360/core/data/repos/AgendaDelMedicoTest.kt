package com.salud360.core.data.repos

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Las reglas de la agenda por médico (spec 005): quién tiene agenda, quién se ve para pedir turno, qué muestra cada
 * pantalla y qué pedido puede salir hacia turnos. Todo puro: ni red, ni base, ni reloj.
 */
class AgendaDelMedicoTest {

    private val conConsultorio = "tobb-c3"
    private val hc = listOf("pediatria")

    // ---- Los cuatro tipos de médico de la spec ----

    private data class Perfil(val nombre: String, val tieneAgenda: Boolean, val visible: Boolean)

    private val sinAgenda = Perfil("sin agenda", tieneAgenda = false, visible = false)
    private val agendaInterna = Perfil("agenda interna", tieneAgenda = true, visible = false)
    private val agendaPublica = Perfil("agenda pública", tieneAgenda = true, visible = true)
    private val visibleSinAgenda = Perfil("visible sin agenda (anómalo)", tieneAgenda = false, visible = true)

    // ---- tieneAgendaDe ----

    @Test
    fun tieneAgendaDe_siTurnosLoInformaManda_aunqueLosHorariosDigan_otraCosa() {
        assertTrue(tieneAgendaDe(tieneAgenda = 1, tieneHorarios = 0), "informa 1 y no tiene horarios: tiene agenda")
        assertTrue(tieneAgendaDe(tieneAgenda = 1, tieneHorarios = 1))
        assertFalse(tieneAgendaDe(tieneAgenda = 0, tieneHorarios = 1), "informa 0 con horarios cargados: no tiene agenda")
        assertFalse(tieneAgendaDe(tieneAgenda = 0, tieneHorarios = 0))
    }

    @Test
    fun tieneAgendaDe_servidorViejo_caeEnLosHorarios() {
        assertTrue(tieneAgendaDe(tieneAgenda = null, tieneHorarios = 1), "RF-13: el criterio de hoy")
        assertFalse(tieneAgendaDe(tieneAgenda = null, tieneHorarios = 0))
    }

    @Test
    fun tieneAgendaDe_soloElUnoCuentaComoVerdadero() {
        // El servidor manda 0/1; cualquier otro entero no es «tiene agenda».
        assertFalse(tieneAgendaDe(tieneAgenda = 2, tieneHorarios = 1))
    }

    // ---- visibleDe ----

    @Test
    fun visibleDe_siTurnosLoInforma_manda_sinMirarElPrevio() {
        assertTrue(visibleDe(visible = 1, previo = false))
        assertTrue(visibleDe(visible = 1, previo = true))
        assertTrue(visibleDe(visible = 1, previo = null))
        assertFalse(visibleDe(visible = 0, previo = true))
        assertFalse(visibleDe(visible = 0, previo = false))
        assertFalse(visibleDe(visible = 0, previo = null))
    }

    @Test
    fun visibleDe_servidorViejo_conservaElPrevio_ySinPrevioEsTrue() {
        assertTrue(visibleDe(visible = null, previo = true))
        assertFalse(visibleDe(visible = null, previo = false), "no vuelve a true: es el defecto que pisaba el interruptor")
        assertTrue(visibleDe(visible = null, previo = null), "sin previo, lo de hoy")
    }

    // ---- tieneConsultorio ----

    @Test
    fun tieneConsultorio_nuloVacioYElCeroDeTurnos_noCuentan() {
        assertFalse(tieneConsultorio(null))
        assertFalse(tieneConsultorio(""))
        assertFalse(tieneConsultorio("   "))
        assertFalse(tieneConsultorio("tobb-c0"), "el consultorio 0 de turnos es «sin consultorio»")
        assertTrue(tieneConsultorio("tobb-c3"))
        assertTrue(tieneConsultorio("tobb-c10"), "«tobb-c10» no es el cero")
    }

    // ---- Qué ve cada uno ----

    @Test
    fun sinAgenda_noVeNadaDeTurnos_yConHcSiguePudiendoVerConfig() {
        val a = sinAgenda.tieneAgenda
        assertFalse(puedeVerAgenda(a, conConsultorio))
        assertFalse(puedeVerHorarios(a, conConsultorio), "es el síntoma del pedido original")
        assertFalse(puedeVerConfigDeAgenda(a))
        assertFalse(puedeVerRecetas(a))
        assertTrue(puedeVerMenuConfig(a, hc), "RF-16: la configuración de la HC se queda")
        assertFalse(puedeVerMenuConfig(a, emptyList()), "sin agenda y sin HC no hay nada para configurar")
    }

    @Test
    fun conAgenda_veTodoLoDeTurnos_tengaOnoHorarios_conConsultorio() {
        for (p in listOf(agendaInterna, agendaPublica)) {
            val a = p.tieneAgenda
            assertTrue(puedeVerAgenda(a, conConsultorio), p.nombre)
            assertTrue(puedeVerHorarios(a, conConsultorio), p.nombre)
            assertTrue(puedeVerConfigDeAgenda(a), p.nombre)
            assertTrue(puedeVerRecetas(a), p.nombre)
            assertTrue(puedeVerMenuConfig(a, emptyList()), "${p.nombre}: la agenda alcanza para el ítem Config")
            assertTrue(puedeVerMenuConfig(a, hc), "${p.nombre}: con HC también")
        }
    }

    @Test
    fun conAgendaSinConsultorio_noPuedeAbrirAgendaNiHorarios_peroVeElRestoDeLaConfiguracion() {
        for (consultorio in listOf(null, "", "tobb-c0")) {
            assertFalse(puedeVerAgenda(true, consultorio), "consultorio=$consultorio")
            assertFalse(puedeVerHorarios(true, consultorio), "consultorio=$consultorio")
        }
        assertTrue(puedeVerConfigDeAgenda(true))
        assertTrue(puedeVerRecetas(true))
        assertTrue(puedeVerMenuConfig(true, emptyList()))
    }

    @Test
    fun visibleSinAgenda_lasPantallasSigueDependiendoSoloDeLaAgenda() {
        // «Visible» no abre nada por sí solo: manda la agenda.
        val a = visibleSinAgenda.tieneAgenda
        assertFalse(puedeVerAgenda(a, conConsultorio))
        assertFalse(puedeVerHorarios(a, conConsultorio))
        assertFalse(puedeVerConfigDeAgenda(a))
        assertFalse(puedeVerRecetas(a))
    }

    // ---- puedeCambiarVisible ----

    @Test
    fun puedeCambiarVisible_conAgendaSiempre_sinAgendaSoloParaApagar() {
        assertTrue(puedeCambiarVisible(tieneAgenda = true, visible = false), "agenda interna: se puede prender")
        assertTrue(puedeCambiarVisible(tieneAgenda = true, visible = true), "agenda pública: se puede apagar")
        assertFalse(puedeCambiarVisible(tieneAgenda = false, visible = false), "sin agenda no se puede prender (RF-6)")
        assertTrue(puedeCambiarVisible(tieneAgenda = false, visible = true), "anómalo: siempre se puede apagar (D-P11)")
    }

    // ---- Alta de usuario: «Mostrar para pedir turno» (RF-14) ----

    @Test
    fun mostrarEnTurnosEnElAlta_soloElMedicoConAgendaQueLoPide() {
        // No médico: nunca, venga lo que venga.
        for (agenda in listOf(true, false)) for (mostrar in listOf(true, false)) {
            assertFalse(mostrarEnTurnosEnElAlta(esMedico = false, tieneAgenda = agenda, mostrarEnTurnos = mostrar), "no médico, agenda=$agenda, mostrar=$mostrar")
        }
        // Médico sin agenda: nunca, aunque la casilla haya quedado marcada.
        assertFalse(mostrarEnTurnosEnElAlta(esMedico = true, tieneAgenda = false, mostrarEnTurnos = true), "sin agenda no se ofrece para pedir turno")
        assertFalse(mostrarEnTurnosEnElAlta(esMedico = true, tieneAgenda = false, mostrarEnTurnos = false))
        // Médico con agenda: manda lo que se pidió.
        assertTrue(mostrarEnTurnosEnElAlta(esMedico = true, tieneAgenda = true, mostrarEnTurnos = true))
        assertFalse(mostrarEnTurnosEnElAlta(esMedico = true, tieneAgenda = true, mostrarEnTurnos = false))
    }

    @Test
    fun puedeMarcarMostrarEnElAlta_soloConAgenda() {
        assertTrue(puedeMarcarMostrarEnElAlta(tieneAgenda = true))
        assertFalse(puedeMarcarMostrarEnElAlta(tieneAgenda = false))
    }

    // ---- cambioDeAgenda ----

    private fun enviar(c: CambioDeAgenda): CambioDeAgenda.Enviar = assertIs<CambioDeAgenda.Enviar>(c)

    private fun rechazado(c: CambioDeAgenda): String {
        val r = assertIs<CambioDeAgenda.Rechazado>(c)
        assertTrue(r.motivo.isNotBlank(), "el rechazo tiene que decir por qué")
        return r.motivo
    }

    private fun estado(p: Perfil) = EstadoAgenda(p.tieneAgenda, p.visible)

    @Test
    fun cambioDeAgenda_apagarLaAgenda_mandaSoloTieneAgendaFalse() {
        for (p in listOf(agendaInterna, agendaPublica)) {
            val e = enviar(cambioDeAgenda(estado(p), tieneAgenda = false, visible = null))
            assertEquals(false, e.tieneAgenda, p.nombre)
            assertNull(e.visible, "${p.nombre}: turnos deja visible=false solo (RF-5); la app no lo manda")
        }
    }

    @Test
    fun cambioDeAgenda_apagarYPedirVisible_seRechaza() {
        val motivo = rechazado(cambioDeAgenda(estado(agendaPublica), tieneAgenda = false, visible = true))
        assertTrue(motivo.contains("agenda", ignoreCase = true), motivo)
    }

    @Test
    fun cambioDeAgenda_apagarYPedirNoVisible_mandaSoloLaAgenda() {
        // Redundante con RF-5: apagar la agenda ya deja al médico no visible. No es un pedido contradictorio.
        val e = enviar(cambioDeAgenda(estado(agendaPublica), tieneAgenda = false, visible = false))
        assertEquals(false, e.tieneAgenda)
        assertNull(e.visible)
    }

    @Test
    fun cambioDeAgenda_visibleTrueSinAgenda_seRechaza() {
        val motivo = rechazado(cambioDeAgenda(estado(sinAgenda), tieneAgenda = null, visible = true))
        assertTrue(motivo.contains("agenda", ignoreCase = true), motivo)
        // También si la agenda fue pedida apagada o ya estaba apagada con la anomalía de visible.
        rechazado(cambioDeAgenda(estado(visibleSinAgenda), tieneAgenda = null, visible = true))
        rechazado(cambioDeAgenda(estado(sinAgenda), tieneAgenda = false, visible = true))
    }

    @Test
    fun cambioDeAgenda_agendaYVisibleJuntos_seEnvian() {
        val e = enviar(cambioDeAgenda(estado(sinAgenda), tieneAgenda = true, visible = true))
        assertEquals(true, e.tieneAgenda)
        assertEquals(true, e.visible)
    }

    @Test
    fun cambioDeAgenda_prenderLaAgendaSola_mandaSoloLaAgenda() {
        val e = enviar(cambioDeAgenda(estado(sinAgenda), tieneAgenda = true, visible = null))
        assertEquals(true, e.tieneAgenda)
        assertNull(e.visible)
    }

    @Test
    fun cambioDeAgenda_prenderAgendaYNoVisible_seEnvian() {
        val e = enviar(cambioDeAgenda(estado(sinAgenda), tieneAgenda = true, visible = false))
        assertEquals(true, e.tieneAgenda)
        assertEquals(false, e.visible)
    }

    @Test
    fun cambioDeAgenda_visibleConAgendaExistente_seEnvia() {
        val prender = enviar(cambioDeAgenda(estado(agendaInterna), tieneAgenda = null, visible = true))
        assertNull(prender.tieneAgenda)
        assertEquals(true, prender.visible)

        val apagar = enviar(cambioDeAgenda(estado(agendaPublica), tieneAgenda = null, visible = false))
        assertNull(apagar.tieneAgenda)
        assertEquals(false, apagar.visible)
    }

    @Test
    fun cambioDeAgenda_visibleFalseEnMedicoSinAgenda_seEnvia() {
        // Es la salida para limpiar la anomalía «visible sin agenda» (D-P11).
        val e = enviar(cambioDeAgenda(estado(visibleSinAgenda), tieneAgenda = null, visible = false))
        assertNull(e.tieneAgenda)
        assertEquals(false, e.visible)
        enviar(cambioDeAgenda(estado(sinAgenda), tieneAgenda = null, visible = false))
    }

    @Test
    fun cambioDeAgenda_sinCampos_seRechaza() {
        for (p in listOf(sinAgenda, agendaInterna, agendaPublica, visibleSinAgenda)) {
            rechazado(cambioDeAgenda(estado(p), tieneAgenda = null, visible = null))
        }
    }

    // ---- avisoDeAgendaGuardada ----

    private fun guardada(tieneAgenda: Boolean, visible: Boolean, futuros: Int) = AgendaGuardada(tieneAgenda, visible, futuros)

    @Test
    fun aviso_apagarConTurnosFuturos_informaLaCantidad() {
        val aviso = avisoDeAgendaGuardada(estado(agendaPublica), guardada(tieneAgenda = false, visible = false, futuros = 3))
        assertNotNull(aviso)
        assertTrue(aviso.contains("3"), aviso)
        assertTrue(aviso.contains("vigentes", ignoreCase = true), "tiene que aclarar que no se cancelan: $aviso")
    }

    @Test
    fun aviso_apagarConUnTurno_hablaEnSingular() {
        val aviso = avisoDeAgendaGuardada(estado(agendaInterna), guardada(tieneAgenda = false, visible = false, futuros = 1))
        assertNotNull(aviso)
        assertTrue(aviso.contains("1 turno futuro"), aviso)
        assertFalse(aviso.contains("turnos futuros"), aviso)
    }

    @Test
    fun aviso_apagarSinTurnosFuturos_noHayAviso() {
        assertNull(avisoDeAgendaGuardada(estado(agendaPublica), guardada(false, false, futuros = 0)))
    }

    @Test
    fun aviso_prenderLaAgenda_noHayAviso_aunqueVengaConTurnos() {
        assertNull(avisoDeAgendaGuardada(estado(sinAgenda), guardada(true, false, futuros = 5)))
    }

    @Test
    fun aviso_siLaAgendaYaEstabaApagada_noHayAviso() {
        assertNull(avisoDeAgendaGuardada(estado(sinAgenda), guardada(false, false, futuros = 4)))
        assertNull(avisoDeAgendaGuardada(estado(visibleSinAgenda), guardada(false, false, futuros = 4)))
    }

    @Test
    fun aviso_cambiarSoloVisible_noHayAviso() {
        assertNull(avisoDeAgendaGuardada(estado(agendaPublica), guardada(true, false, futuros = 2)))
    }
}
