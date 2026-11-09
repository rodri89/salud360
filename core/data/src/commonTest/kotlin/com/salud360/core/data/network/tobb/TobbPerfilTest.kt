package com.salud360.core.data.network.tobb

import com.salud360.core.model.tobb.TobbPerfil
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TobbPerfilTest {
    private fun perfil(medico: String): TobbPerfil = tobbJson.decodeFromJsonElement(
        TobbPerfil.serializer(),
        tobbJson.parseToJsonElement("""{"usuario":{"id":12,"nombre":"Dra","email":"d@m.com","tipo":2},"rol":"medico","medico":$medico}""").jsonObject,
    )

    @Test
    fun elPerfilTraeLasHistoriasClinicasHabilitadas() {
        val p = perfil("""{"id":3,"nombre":"Ana","apellido":"Perez","especialidad_id":5,"especialidad":"Pediatría","consultorio_id":1,"historias_clinicas":["pediatria","clinica"]}""")
        assertEquals(listOf("pediatria", "clinica"), p.medico?.historiasClinicas)
    }

    @Test
    fun sinElCampoLaListaQuedaEnNullYNoEsLoMismoQueVacia() {
        val p = perfil("""{"id":3,"nombre":"Ana","apellido":"Perez","consultorio_id":0}""")
        assertNull(p.medico?.historiasClinicas, "null = el servidor no informa el campo")
        assertEquals(emptyList(), perfil("""{"id":3,"nombre":"Ana","apellido":"Perez","historias_clinicas":[]}""").medico?.historiasClinicas)
        assertEquals(0L, p.medico?.consultorioId)
        assertNull(p.secretaria)
    }

    @Test
    fun elPerfilTomaLosCamposDeLaAgendaCuandoVienen() {
        val p = perfil("""{"id":3,"nombre":"Ana","apellido":"Perez","tiene_horarios":1,"tiene_agenda":0,"visible_en_turnos":1}""")
        assertEquals(0, p.medico?.tieneAgenda)
        assertEquals(1, p.medico?.visibleEnTurnos)
        assertEquals(1, p.medico?.tieneHorarios, "tiene_horarios se sigue leyendo: es el respaldo")
    }

    @Test
    fun sinLosCamposDeLaAgendaQuedanEnNull() {
        val p = perfil("""{"id":3,"nombre":"Ana","apellido":"Perez","tiene_horarios":0}""")
        assertNull(p.medico?.tieneAgenda, "null = el servidor no lo informa (distinto de 0)")
        assertNull(p.medico?.visibleEnTurnos)
        assertEquals(0, p.medico?.tieneHorarios)
    }

    @Test
    fun laSecretariaTraeLosCamposDeLaAgendaEnCadaMedico() {
        val json = """
            {"usuario":{"id":20,"nombre":"Sec","email":"s@m.com","tipo":3},"rol":"secretaria",
             "secretaria":{"id":4,"nombre":"Sec","apellido":"Prueba","medicos":[
               {"id":3,"nombre":"Ana","apellido":"Perez","tiene_horarios":1,"tiene_agenda":1,"visible_en_turnos":0},
               {"id":5,"nombre":"Luis","apellido":"Gomez","tiene_horarios":1,"tiene_agenda":0,"visible_en_turnos":0},
               {"id":6,"nombre":"Eva","apellido":"Diaz","tiene_horarios":0}
             ]}}
        """.trimIndent()
        val p = tobbJson.decodeFromJsonElement(TobbPerfil.serializer(), tobbJson.parseToJsonElement(json).jsonObject)
        val medicos = p.secretaria?.medicos.orEmpty()
        assertEquals(3, medicos.size)
        assertEquals(1, medicos[0].tieneAgenda)
        assertEquals(0, medicos[0].visibleEnTurnos)
        assertEquals(0, medicos[1].tieneAgenda, "sin agenda aunque tenga horarios: lo decide el administrador")
        assertNull(medicos[2].tieneAgenda, "el que no trae los campos queda en null")
        assertNull(medicos[2].visibleEnTurnos)
    }
}
