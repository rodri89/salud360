package com.salud360.core.data.network.tobb

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * El alta de usuario viaja con los nombres que lee turnosonlinebb.
 *
 * Es lo único de este camino que no se ve al probar: un nombre de campo equivocado no rompe nada,
 * llega como vacío y el alta sale mal (un médico sin consultorio, o publicado para pedir turno sin
 * quererlo). Del otro lado se leen así, en `AltaUsuarioController`.
 */
class TobbAltaUsuarioTest {

    private fun altaPublicada() = TobbAltaUsuario(
        nombre = "Ana", apellido = "Vera", email = "av@ejemplo.com", password = "secreta1",
        rol = "medico", especialidadId = 1, consultorioId = 2, mostrarEnTurnos = true,
    )

    private fun jsonDe(alta: TobbAltaUsuario) =
        tobbJson.encodeToJsonElement(TobbAltaUsuario.serializer(), alta).jsonObject

    @Test
    fun elAltaDeMedicoViajaConLosNombresQueEsperaTurnos() {
        val cuerpo = jsonDe(
            TobbAltaUsuario(
                nombre = "Valentina", apellido = "Romano", email = "v@ejemplo.com", password = "secreta1",
                rol = "medico", especialidadId = 1, consultorioId = 2,
                mostrarEnTurnos = false, historiasClinicas = listOf("pediatria"),
            ),
        )
        assertEquals("medico", cuerpo["rol"]?.jsonPrimitive?.content, "el rol va en minúsculas, como lo lee turnos")
        assertEquals("true", jsonDe(altaPublicada()).get("mostrar_en_turnos")?.jsonPrimitive?.content, "publicarlo sí tiene que viajar")
        assertEquals("1", cuerpo["especialidad_id"]?.jsonPrimitive?.content)
        assertEquals("2", cuerpo["consultorio_id"]?.jsonPrimitive?.content)
        // Los valores por omisión no viajan (`encodeDefaults = false`), y del otro lado "no vino" se
        // lee como que no: el médico que no se publica es justamente el caso normal de este alta.
        assertTrue("mostrar_en_turnos" !in cuerpo, "false es el valor por omisión y no se manda")
        assertEquals("v@ejemplo.com", cuerpo["email"]?.jsonPrimitive?.content)
        assertTrue(cuerpo.containsKey("historias_clinicas"), "sin esto el médico queda sin historia clínica habilitada")
    }

    @Test
    fun laSecretariaViajaSinEspecialidadNiConsultorio() {
        val cuerpo = jsonDe(
            TobbAltaUsuario(nombre = "Ana", apellido = "Gomez", email = "a@ejemplo.com", password = "secreta1", rol = "secretaria"),
        )
        // Sin especialidad ni consultorio, que son del médico. Del otro lado no se piden para la
        // secretaria, así que no viajan.
        assertTrue("especialidad_id" !in cuerpo)
        assertTrue("consultorio_id" !in cuerpo)
        assertEquals("secretaria", cuerpo["rol"]?.jsonPrimitive?.content)
    }

    private fun medicoConAgenda(tieneAgenda: Boolean?) = TobbAltaUsuario(
        nombre = "Ana", apellido = "Vera", email = "av@ejemplo.com", password = "secreta1",
        rol = "medico", especialidadId = 1, consultorioId = 2, tieneAgenda = tieneAgenda,
    )

    @Test
    fun tieneAgendaViajaConTrueYTambienConFalse() {
        // Con `encodeDefaults = false` solo se omite el valor por omisión (`null`): un `false` explícito viaja,
        // y es lo que distingue «sin agenda» de «el alta no dijo nada» (turnos toma entonces `mostrar_en_turnos`).
        assertEquals("true", jsonDe(medicoConAgenda(true))["tiene_agenda"]?.jsonPrimitive?.content)
        assertEquals("false", jsonDe(medicoConAgenda(false))["tiene_agenda"]?.jsonPrimitive?.content)
    }

    @Test
    fun tieneAgendaNoViajaSiEsNull() {
        assertTrue("tiene_agenda" !in jsonDe(medicoConAgenda(null)))
        val secretaria = jsonDe(
            TobbAltaUsuario(nombre = "Ana", apellido = "Gomez", email = "a@ejemplo.com", password = "secreta1", rol = "secretaria"),
        )
        assertTrue("tiene_agenda" !in secretaria, "la secretaria no lleva el campo")
    }

    @Test
    fun mostrarEnTurnosSigueComoHoyConLaAgenda() {
        val conAgendaVisible = jsonDe(medicoConAgenda(true).copy(mostrarEnTurnos = true))
        assertEquals("true", conAgendaVisible["tiene_agenda"]?.jsonPrimitive?.content)
        assertEquals("true", conAgendaVisible["mostrar_en_turnos"]?.jsonPrimitive?.content)
        val sinAgenda = jsonDe(medicoConAgenda(false))
        assertTrue("mostrar_en_turnos" !in sinAgenda, "false es el valor por omisión y no se manda")
    }
}
