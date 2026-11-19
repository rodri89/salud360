package com.salud360.core.data.sync

import com.salud360.core.data.hcgeneral.PARTE_CREAR
import com.salud360.core.data.hcgeneral.PARTE_SECCIONES
import com.salud360.core.data.hcgeneral.ResultadoVinculo
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.network.TobbException
import com.salud360.core.data.network.hc.HcPacienteCandidato
import com.salud360.core.data.repos.HcClinicaPruebas
import com.salud360.core.data.repos.HcClinicaPruebas.PACIENTE
import com.salud360.core.data.repos.PacientesHcRepository
import com.salud360.core.model.especialidad.CodigosHc
import com.salud360.core.model.hc.CausaEnEspera
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.hc.EstadoEnvio
import com.salud360.core.model.pacientes.Paciente
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spec 005, T26 (RF-6, RF-13, RF-19): el vínculo del paciente con su ficha de la web **antes de enviar** la primera consulta. Se resuelve
 * con `PacientesHcRepository.vinculo` (la misma lógica de la 002 y la 003, sin duplicarla) y solo cuando hace falta crear la consulta. Si
 * no queda vinculado (hay que elegir ficha, el documento no sirve, la ficha es de otro paciente, o no se pudo preguntar), la creación queda
 * **«En espera: <motivo>»** nombrando la HC, no sale nada, y el médico lo resuelve desde la consulta. Un paciente dado de baja en la web
 * también deja «En espera» y **no** se vuelve a resolver ni se crea ninguna ficha por la vía del envío (RF-19). Contra un servidor falso con
 * memoria y un `pacientes/resolver` de mentira; sin red.
 */
class VinculoAlEnviarTest {

    private fun ambiguo() = TobbException(
        409, "paciente_ambiguo", "Hay varias fichas con ese documento",
        candidatos = listOf(HcPacienteCandidato(5003, "Ana", "Prueba", "99000100"), HcPacienteCandidato(5004, "Ana María", "Prueba", "99000100")),
    )

    // ---- Primera consulta de un paciente sin ficha ----

    @Test
    fun la_primera_consulta_resuelve_el_vinculo_antes_de_enviar_y_crea_con_esa_ficha() {
        val resolver = ResolverFalso()
        conServidor(vinculado = false, pacientesApi = resolver) {
            db.sembrarPropia("u1")
            db.sembrarTexto("u1")

            envio.empujarYa("u1").join()

            assertEquals(1, resolver.pedidos.size, "se resuelve una vez")
            assertEquals("99000100", resolver.pedidos.single().dni, "con los datos del paciente")
            assertEquals(5002L, servidor.creaciones.single().pacienteWebId, "y la consulta se crea con la ficha que contestó la web")
            assertEquals(listOf("crear c:u1", "secciones 7001 nota"), servidor.pedidos)
            assertEquals("7001", db.consultaLocal("u1")?.remotoId)
        }
    }

    @Test
    fun el_vinculo_queda_guardado_sin_pendiente_de_envio_y_no_se_vuelve_a_pedir() {
        val resolver = ResolverFalso()
        conServidor(vinculado = false, pacientesApi = resolver) {
            db.sembrarPropia("u1")
            db.sembrarPropia("u2")

            envio.empujarYa("u1").join()
            envio.empujarYa("u2").join()

            assertEquals(1, resolver.pedidos.size, "la segunda consulta del paciente usa el vínculo ya guardado (RF-35)")
            assertEquals(5002L, pacientes.fichaConocida(PACIENTE, CodigosHc.GENERAL))
            assertEquals(
                listOf("0"),
                HcClinicaPruebas.textos(driver, "SELECT dirty FROM paciente_extra WHERE paciente_id = '$PACIENTE' AND clave = '${PacientesHcRepository.EXTRA_HC_PACIENTE_ID}'"),
                "el dueño de la ficha es la web: el vínculo no queda pendiente",
            )
            assertEquals(listOf(5002L, 5002L), servidor.creaciones.map { it.pacienteWebId })
        }
    }

    @Test
    fun un_paciente_ya_vinculado_no_pregunta_nada() {
        val resolver = ResolverFalso()
        conServidor(vinculado = true, pacientesApi = resolver) {
            db.sembrarPropia("u1")

            envio.empujarYa("u1").join()

            assertEquals(emptyList(), resolver.pedidos)
            assertEquals(5001L, servidor.creaciones.single().pacienteWebId)
        }
    }

    @Test
    fun solo_la_creacion_necesita_el_vinculo() {
        val resolver = ResolverFalso()
        conServidor(vinculado = false, pacientesApi = resolver) {
            // Ya está creada en la web: lo que sigue (textos, cierre) va por el id de la consulta, no por la ficha.
            db.sembrarPropia("u1", remotoId = "7001", dirty = false)
            servidor.consultas[7001] = ServidorFalso.ConsultaWeb(7001, "c:u1", 5001, "control")
            db.sembrarTexto("u1")

            envio.empujarYa("u1").join()

            assertEquals(emptyList(), resolver.pedidos)
            assertEquals(listOf("secciones 7001 nota"), servidor.pedidos)
        }
    }

    // ---- Lo que deja «En espera» ----

    @Test
    fun una_ficha_ambigua_deja_la_creacion_en_espera_sin_enviar_y_sin_vincular() {
        val resolver = ResolverFalso().apply { respuesta = { _, _ -> throw ambiguo() } }
        conServidor(vinculado = false, pacientesApi = resolver) {
            db.sembrarPropia("u1", estado = EstadoConsulta.CERRADA)
            db.sembrarTexto("u1")

            envio.empujarYa("u1").join()

            assertEquals(emptyList(), servidor.pedidos, "no sale nada")
            val espera = assertIs<EstadoEnvio.EnEspera>(db.estadosDe("u1").getValue(PARTE_CREAR).estado)
            assertEquals(CausaEnEspera.ELEGIR_FICHA, espera.causa)
            assertTrue(espera.motivo.startsWith("Historia clínica de Clínica general: "), espera.motivo)
            assertNull(pacientes.fichaConocida(PACIENTE, CodigosHc.GENERAL), "no se vinculó a ninguna")
            // Todo sigue en el dispositivo, sin perderse ni modificarse.
            assertEquals(setOf("nota/texto"), db.textosSucios("u1"))
            assertTrue(db.consultaSucia("u1"))
            assertEquals(1, envio.contarPendientes().enEspera)
        }
    }

    @Test
    fun el_medico_elige_la_ficha_y_la_consulta_en_espera_sale() {
        val resolver = ResolverFalso().apply {
            respuesta = { pedido, elegida -> if (elegida == null) throw ambiguo() else ResolverFalso.vinculada(elegida, pedido.dni) }
        }
        conServidor(vinculado = false, pacientesApi = resolver) {
            db.sembrarPropia("u1")
            envio.empujarYa("u1").join()
            assertIs<EstadoEnvio.EnEspera>(db.estadosDe("u1").getValue(PARTE_CREAR).estado)

            // La pantalla de elegir ficha de la 003: guarda el vínculo con la ficha elegida.
            assertEquals(ResultadoVinculo.Vinculado(5004), pacientes.elegirFicha(PACIENTE, CodigosHc.GENERAL, 5004))
            envio.empujarYa("u1").join()

            assertEquals(5004L, servidor.creaciones.single().pacienteWebId)
            assertEquals(emptyMap(), db.estadosDe("u1"), "ya no está en espera")
            assertEquals("7001", db.consultaLocal("u1")?.remotoId)
        }
    }

    @Test
    fun un_documento_invalido_deja_la_creacion_en_espera_sin_preguntarle_nada_a_la_web() {
        val resolver = ResolverFalso()
        conServidor(vinculado = false, pacientesApi = resolver) {
            db.pacientesQueries.upsertPaciente(Paciente(PACIENTE, "12", "Salud360", "Prueba").toRow(0, dirty = false))
            db.sembrarPropia("u1")

            envio.empujarYa("u1").join()

            assertEquals(emptyList(), resolver.pedidos, "RF-12: con un documento que no sirve no se pide nada")
            assertEquals(emptyList(), servidor.pedidos)
            val espera = assertIs<EstadoEnvio.EnEspera>(db.estadosDe("u1").getValue(PARTE_CREAR).estado)
            assertEquals(CausaEnEspera.DOCUMENTO_INVALIDO, espera.causa)
            assertTrue("«12»" in espera.motivo && "no sirve" in espera.motivo, espera.motivo)

            // El médico corrige el documento en la ficha del paciente: el próximo intento sale solo.
            db.pacientesQueries.upsertPaciente(Paciente(PACIENTE, "99000100", "Salud360", "Prueba").toRow(1, dirty = false))
            envio.empujarYa("u1").join()

            assertEquals(1, servidor.cantidadDeConsultas)
            assertEquals(emptyMap(), db.estadosDe("u1"))
        }
    }

    @Test
    fun una_ficha_de_otro_paciente_de_turnos_deja_la_creacion_en_espera_sin_crear_otra_ficha() {
        val resolver = ResolverFalso().apply {
            respuesta = { _, _ -> throw TobbException(409, "paciente_ya_vinculado", "Esa ficha es de otro paciente") }
        }
        conServidor(vinculado = false, pacientesApi = resolver) {
            db.sembrarPropia("u1")

            envio.empujarYa("u1").join()

            assertEquals(emptyList(), servidor.pedidos)
            val espera = assertIs<EstadoEnvio.EnEspera>(db.estadosDe("u1").getValue(PARTE_CREAR).estado)
            assertEquals(CausaEnEspera.FICHA_DE_OTRO_PACIENTE, espera.causa)
            assertTrue("otro paciente de turnosonlinebb" in espera.motivo, espera.motivo)
            assertNull(pacientes.fichaConocida(PACIENTE, CodigosHc.GENERAL))
            assertEquals(1, resolver.pedidos.size, "se preguntó una vez y no se insistió con otra ficha")
        }
    }

    @Test
    fun sin_conexion_al_resolver_el_vinculo_la_creacion_queda_en_espera_y_nada_se_pierde() {
        val resolver = ResolverFalso().apply { respuesta = { _, _ -> throw TobbException(0, "red", "Sin conexión") } }
        conServidor(vinculado = false, pacientesApi = resolver) {
            db.sembrarPropia("u1")
            db.sembrarTexto("u1")
            val antes = db.filasSucias()

            envio.empujarYa("u1").join()

            assertEquals(CausaEnEspera.SIN_CONEXION, assertIs<EstadoEnvio.EnEspera>(db.estadosDe("u1").getValue(PARTE_CREAR).estado).causa)
            assertEquals(antes, db.filasSucias(), "lo sucio queda exactamente igual")
            assertEquals(emptyList(), servidor.pedidos)
        }
    }

    @Test
    fun un_error_que_no_es_de_la_api_al_resolver_tambien_deja_en_espera_y_no_rompe_la_cola() {
        val resolver = ResolverFalso().apply { respuesta = { _, _ -> throw IllegalStateException("JSON cortado") } }
        conServidor(vinculado = false, pacientesApi = resolver) {
            db.sembrarPropia("u1")

            envio.empujarYa("u1").join()

            assertEquals(CausaEnEspera.SERVIDOR, assertIs<EstadoEnvio.EnEspera>(db.estadosDe("u1").getValue(PARTE_CREAR).estado).causa)
            // La cola sigue andando: un segundo intento (ahora anda) sale.
            resolver.respuesta = { pedido, _ -> ResolverFalso.vinculada(5002, pedido.dni) }
            envio.empujarYa("u1").join()
            assertEquals(1, servidor.cantidadDeConsultas)
        }
    }

    @Test
    fun un_paciente_dado_de_baja_en_la_web_deja_en_espera_y_no_se_resuelve_de_nuevo_ni_se_crea_ninguna_ficha() {
        val resolver = ResolverFalso()
        conServidor(vinculado = true, pacientesApi = resolver) {
            db.sembrarPropia("u1")
            db.sembrarTexto("u1")
            servidor.falla = { if (it == "crear") TobbException(404, "paciente_no_encontrado", "Paciente no encontrado.") else null }

            envio.empujarYa("u1").join()

            val espera = assertIs<EstadoEnvio.EnEspera>(db.estadosDe("u1").getValue(PARTE_CREAR).estado)
            assertEquals(CausaEnEspera.PACIENTE_DE_BAJA, espera.causa)
            assertEquals(emptyList(), resolver.pedidos, "RF-19: no se pide `pacientes/resolver` (que podría crear otra ficha)")
            assertEquals(5001L, pacientes.fichaConocida(PACIENTE, CodigosHc.GENERAL), "y el vínculo que había se conserva")
            assertEquals(setOf("nota/texto"), db.textosSucios("u1"), "la consulta y su texto siguen en el dispositivo")
            // Lo que faltaba de la consulta queda igual en espera (RF-6): la ficha de baja vale para todo lo que sigue.
            assertEquals(CausaEnEspera.PACIENTE_DE_BAJA, assertIs<EstadoEnvio.EnEspera>(db.estadosDe("u1").getValue(PARTE_SECCIONES).estado).causa)

            // Otro intento con la ficha todavía de baja: igual, sin resolver. Cuando la web la reactiva, sale sola.
            envio.empujarYa("u1").join()
            assertEquals(emptyList(), resolver.pedidos)
            servidor.falla = null
            envio.empujarYa("u1").join()
            assertEquals(emptyList(), resolver.pedidos)
            assertEquals(5001L, servidor.creaciones.last().pacienteWebId)
            assertEquals(emptyMap(), db.estadosDe("u1"))
        }
    }

    @Test
    fun un_paciente_fuera_de_la_cartera_del_medico_deja_en_espera_sin_resolver() {
        val resolver = ResolverFalso()
        conServidor(vinculado = true, pacientesApi = resolver) {
            db.sembrarPropia("u1")
            servidor.falla = { if (it == "crear") TobbException(403, "fuera_de_cartera", "Ese paciente no está en tu cartera.") else null }

            envio.empujarYa("u1").join()

            assertEquals(CausaEnEspera.FUERA_DE_CARTERA, assertIs<EstadoEnvio.EnEspera>(db.estadosDe("u1").getValue(PARTE_CREAR).estado).causa)
            assertEquals(emptyList(), resolver.pedidos)
        }
    }

    @Test
    fun el_dato_del_paciente_no_se_modifica_al_resolver_el_vinculo() {
        val resolver = ResolverFalso()
        conServidor(vinculado = false, pacientesApi = resolver) {
            db.sembrarPropia("u1")

            envio.empujarYa("u1").join()

            // `pacientes/resolver` devuelve la ficha de la web, pero el paciente del dispositivo (su dueño es turnos) queda como estaba.
            assertEquals(
                listOf("99000100|Salud360|Prueba"),
                HcClinicaPruebas.textos(driver, "SELECT dni || '|' || nombre || '|' || apellido FROM paciente WHERE id = '$PACIENTE'"),
            )
        }
    }
}
