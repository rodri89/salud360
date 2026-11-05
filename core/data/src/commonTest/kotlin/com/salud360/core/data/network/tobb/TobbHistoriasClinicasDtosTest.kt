package com.salud360.core.data.network.tobb

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Los DTO de `PUT medicos/{id}/historias-clinicas`: el pedido con la lista exacta y la respuesta con la lista final. */
class TobbHistoriasClinicasDtosTest {

    private fun cuerpoDe(pedido: TobbHistoriasClinicasRequest) =
        tobbJson.encodeToJsonElement(TobbHistoriasClinicasRequest.serializer(), pedido).jsonObject

    private fun respuesta(json: String) = tobbJson.decodeFromString(TobbHistoriasClinicasResponse.serializer(), json)

    @Test
    fun elPedidoViajaConLaListaExacta() {
        val cuerpo = cuerpoDe(TobbHistoriasClinicasRequest(listOf("pediatria", "clinica")))
        assertEquals(setOf("historias_clinicas"), cuerpo.keys)
        assertEquals(listOf("pediatria", "clinica"), cuerpo.getValue("historias_clinicas").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun laListaVaciaTambienViaja() {
        // `tobbJson` no codifica los valores por omisión: `[]` es «ninguna habilitada» y tiene que llegar a turnos.
        val texto = tobbJson.encodeToString(TobbHistoriasClinicasRequest.serializer(), TobbHistoriasClinicasRequest(emptyList()))
        assertEquals("""{"historias_clinicas":[]}""", texto)
    }

    @Test
    fun laRespuestaDecodificaElEjemploDelContrato() {
        val r = respuesta("""{"ok":true,"medico_id":12,"historias_clinicas":["pediatria","clinica"]}""")
        assertEquals(12L, r.medicoId)
        assertEquals(listOf("pediatria", "clinica"), r.historiasClinicas)
    }

    @Test
    fun laRespuestaConListaVaciaEsValida() {
        assertEquals(emptyList(), respuesta("""{"ok":true,"medico_id":12,"historias_clinicas":[]}""").historiasClinicas)
    }

    @Test
    fun laRespuestaSinLaListaFalla() {
        // Un 200 que no dice en qué quedó la lista no confirma nada: no se toma por buena.
        assertFailsWith<SerializationException> { respuesta("""{"ok":true,"medico_id":12}""") }
        assertFailsWith<SerializationException> { respuesta("""{}""") }
    }
}
