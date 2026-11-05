package com.salud360.core.data.network.tobb

import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Los DTO de `PUT medicos/{id}/activo`: el pedido con el booleano y la respuesta con el estado final del médico. */
class TobbMedicoActivoDtosTest {

    private fun respuesta(json: String) = tobbJson.decodeFromString(TobbMedicoActivoResponse.serializer(), json)

    @Test
    fun elPedidoDeDesactivarViajaConFalse() {
        // `tobbJson` no codifica los valores por omisión: `false` es el pedido de desactivar y tiene que llegar a turnos.
        val texto = tobbJson.encodeToString(TobbMedicoActivoRequest.serializer(), TobbMedicoActivoRequest(activo = false))
        assertEquals("""{"activo":false}""", texto)
    }

    @Test
    fun elPedidoDeActivarViajaConTrue() {
        val texto = tobbJson.encodeToString(TobbMedicoActivoRequest.serializer(), TobbMedicoActivoRequest(activo = true))
        assertEquals("""{"activo":true}""", texto)
    }

    @Test
    fun laRespuestaDecodificaElEjemploDelContrato() {
        val r = respuesta("""{"ok":true,"medico_id":12,"activo":false}""")
        assertEquals(12L, r.medicoId)
        assertEquals(false, r.activo)
        assertEquals(true, respuesta("""{"ok":true,"medico_id":12,"activo":true}""").activo)
    }

    @Test
    fun laRespuestaSinElBooleanoFalla() {
        // Un 200 que no dice en qué quedó el médico no confirma nada: no se toma por bueno.
        assertFailsWith<SerializationException> { respuesta("""{"ok":true,"medico_id":12}""") }
        assertFailsWith<SerializationException> { respuesta("""{}""") }
    }
}
