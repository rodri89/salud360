package com.salud360.core.data.network.tobb

import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Los DTO de los tipos de turno (`GET/POST tipos-turno`, `GET/PUT medicos/{id}/tipos-turno`). Lo delicado es lo que
 * viaja con `encodeDefaults = false`: la lista vacía `[]` y el `primer_control` nulo («Siempre») tienen que salir.
 */
class TobbTiposTurnoDtosTest {

    private fun pedido(vararg tipos: TobbTipoConfig) =
        tobbJson.encodeToString(TobbMedicoTiposRequest.serializer(), TobbMedicoTiposRequest(tipos.toList()))

    // ---- Lo que sale ----

    @Test
    fun elPedidoSinTiposViajaConLaListaVacia() {
        assertEquals("""{"tipos":[]}""", pedido())
    }

    @Test
    fun elPrimerControlNuloViajaComoNull() {
        assertEquals("""{"tipos":[{"id":26,"primer_control":null}]}""", pedido(TobbTipoConfig(26, null)))
    }

    @Test
    fun elPrimerControlUnoYCeroViajanTalCual() {
        assertEquals("""{"tipos":[{"id":26,"primer_control":1}]}""", pedido(TobbTipoConfig(26, 1)))
        assertEquals("""{"tipos":[{"id":26,"primer_control":0}]}""", pedido(TobbTipoConfig(26, 0)))
    }

    @Test
    fun lasOpcionesAusentesNoViajan() {
        assertEquals("""{"tipos":[{"id":26,"primer_control":null}]}""", pedido(TobbTipoConfig(26, null, opciones = null)))
    }

    @Test
    fun lasOpcionesVaciasViajanComoListaVacia() {
        assertEquals("""{"tipos":[{"id":26,"primer_control":1,"opciones":[]}]}""", pedido(TobbTipoConfig(26, 1, emptyList())))
    }

    @Test
    fun elPedidoCompletoCoincideConElContrato() {
        assertEquals(
            """{"tipos":[{"id":26,"primer_control":null,"opciones":[27,28]},{"id":30,"primer_control":0}]}""",
            pedido(TobbTipoConfig(26, null, listOf(27, 28)), TobbTipoConfig(30, 0)),
        )
    }

    @Test
    fun elAltaDeUnTipoViajaSoloConElNombre() {
        assertEquals("""{"nombre":"Control"}""", tobbJson.encodeToString(TobbCrearTipoTurnoRequest.serializer(), TobbCrearTipoTurnoRequest("Control")))
    }

    // ---- Lo que llega ----

    @Test
    fun elCatalogoDecodificaTiposActivosEInactivos() {
        val r = tobbJson.decodeFromString(
            TobbTiposTurnoResponse.serializer(),
            """{"ok":true,"tipos":[{"id":1,"nombre":"Consulta","activo":1},{"id":4,"nombre":"Viejo","activo":0}]}""",
        )
        assertEquals(listOf(TobbTipoTurno(1, "Consulta", 1), TobbTipoTurno(4, "Viejo", 0)), r.tipos)
    }

    @Test
    fun elCatalogoVacioEsValidoPeroSinLaListaFalla() {
        assertEquals(emptyList(), tobbJson.decodeFromString(TobbTiposTurnoResponse.serializer(), """{"ok":true,"tipos":[]}""").tipos)
        assertFailsWith<SerializationException> { tobbJson.decodeFromString(TobbTiposTurnoResponse.serializer(), """{"ok":true}""") }
    }

    @Test
    fun elAltaDecodificaElTipoCreado() {
        val r = tobbJson.decodeFromString(TobbCrearTipoTurnoResponse.serializer(), """{"ok":true,"tipo":{"id":26,"nombre":"Control","activo":1}}""")
        assertEquals(TobbTipoTurno(26, "Control", 1), r.tipo)
    }

    @Test
    fun elAltaSinElTipoFalla() {
        assertFailsWith<SerializationException> { tobbJson.decodeFromString(TobbCrearTipoTurnoResponse.serializer(), """{"ok":true}""") }
    }

    @Test
    fun losTiposDelMedicoDecodificanCondicionYOpciones() {
        val r = tobbJson.decodeFromString(
            TobbMedicoTiposResponse.serializer(),
            """{"ok":true,"medico_id":12,"tipos":[
                {"id":26,"nombre":"Consulta","primer_control":null,"opciones":[{"id":27,"nombre":"Con ecografía"}]},
                {"id":28,"nombre":"Control","primer_control":1,"opciones":[]},
                {"id":29,"nombre":"Seguimiento","primer_control":0,"opciones":[]}
            ]}""",
        )
        assertEquals(12L, r.medicoId)
        assertEquals(listOf(null, 1, 0), r.tipos.map { it.primerControl })
        assertEquals(listOf(TobbOpcionTipoTurno(27, "Con ecografía")), r.tipos[0].opciones)
        assertEquals(emptyList(), r.avisos, "sin `avisos` en la respuesta, no hay avisos")
    }

    @Test
    fun elPrimerControlAusenteSeLeeComoNulo() {
        val r = tobbJson.decodeFromString(TobbMedicoTiposResponse.serializer(), """{"ok":true,"medico_id":12,"tipos":[{"id":26,"nombre":"Consulta"}]}""")
        assertNull(r.tipos.single().primerControl)
        assertEquals(emptyList(), r.tipos.single().opciones)
    }

    @Test
    fun laRespuestaDelPutTraeLosAvisos() {
        val r = tobbJson.decodeFromString(
            TobbMedicoTiposResponse.serializer(),
            """{"ok":true,"medico_id":12,"tipos":[],"avisos":["El tipo Consulta sigue teniendo 17 horarios activos."]}""",
        )
        assertEquals(listOf("El tipo Consulta sigue teniendo 17 horarios activos."), r.avisos)
        assertEquals(emptyList(), r.tipos)
    }

    @Test
    fun losTiposDelMedicoSinLaListaFallan() {
        // Un 200 que no dice qué tipos quedaron habilitados no confirma nada.
        assertFailsWith<SerializationException> { tobbJson.decodeFromString(TobbMedicoTiposResponse.serializer(), """{"ok":true,"medico_id":12}""") }
    }
}
