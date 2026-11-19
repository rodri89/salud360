package com.salud360.core.data.network.tobb

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `PUT medicos/{id}/historias-clinicas` de turnos (RF-18), con los nombres que lee y devuelve
 * `HistoriaClinicaController` del otro lado.
 */
class TobbHistoriasClinicasTest {

    private fun cuerpo(codigos: List<String>) =
        tobbJson.encodeToJsonElement(TobbHistoriasClinicas.serializer(), TobbHistoriasClinicas(codigos)).jsonObject

    @Test
    fun laListaViajaCompletaConElNombreQueEsperaTurnos() {
        val lista = cuerpo(listOf("pediatria", "clinica"))["historias_clinicas"] as JsonArray
        assertEquals(listOf("pediatria", "clinica"), lista.map { it.jsonPrimitive.content })
    }

    @Test
    fun laListaVaciaViajaIgual() {
        // Sin el campo, turnos contesta `422 datos`; vacía es "ninguna", que es lo que se quiso al destildar la última.
        assertEquals(JsonArray(emptyList()), cuerpo(emptyList())["historias_clinicas"])
    }

    @Test
    fun laRespuestaTraeLasQueQuedaronHabilitadas() {
        val r = tobbJson.decodeFromString(
            TobbHistoriasClinicasMedico.serializer(),
            """{"ok":true,"medico_id":42,"historias_clinicas":["clinica","pediatria"]}""",
        )
        assertEquals(42L, r.medicoId)
        assertEquals(listOf("clinica", "pediatria"), r.historiasClinicas)
    }

    @Test
    fun sinNingunaHabilitadaLaRespuestaQuedaVacia() {
        val r = tobbJson.decodeFromString(TobbHistoriasClinicasMedico.serializer(), """{"ok":true,"medico_id":42,"historias_clinicas":[]}""")
        assertEquals(emptyList(), r.historiasClinicas)
    }
}
