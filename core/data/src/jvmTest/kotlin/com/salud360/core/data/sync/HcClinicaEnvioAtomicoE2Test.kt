package com.salud360.core.data.sync

import com.salud360.core.data.hcgeneral.LoEnviado
import com.salud360.core.data.hcgeneral.SubentregaDeEscritura
import com.salud360.core.data.hcgeneral.seccionesDe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Spec 005, T63-a (RF-61): lo mismo que `HcClinicaEnvioAtomicoTest` para los formularios y los antecedentes, T63-b para los estudios y T85-a para las cargas de
 * laboratorio general y de peso. Un corte entre «la web confirmó» y «el dispositivo lo anotó» no deja nada a medias: lo que la web confirmó, el `remoto_id` de
 * cada fila, la limpieza de lo enviado y el estado se anotan en **una sola transacción**. El corte se simula con un `TRIGGER` que aborta al guardar
 * `hce_enviado_`. Como no quedó anotado, el reenvío repite el mismo pedido (los mismos `ref` o la misma `clave`) y la web, que escribe por campo, por fila y
 * por clave, queda igual (sin duplicar).
 */
class HcClinicaEnvioAtomicoE2Test {
    private val e2 = seccionesDe(SubentregaDeEscritura.E2)

    private fun Escena.cortarAlGuardarLoConfirmado() {
        driver.execute(
            null,
            "CREATE TRIGGER corte BEFORE INSERT ON paciente_extra WHEN NEW.clave LIKE 'hce_enviado_%' BEGIN SELECT RAISE(ABORT, 'corte simulado'); END",
            0,
        ).value
    }

    private fun Escena.repararElCorte() {
        driver.execute(null, "DROP TRIGGER corte", 0).value
    }

    private suspend fun Escena.creada() {
        db.sembrarPropia("u1", remotoId = "7001", dirty = false)
        servidor.consultas[7001] = ServidorFalso.ConsultaWeb(7001, "c:u1", 5001, "control")
    }

    @Test
    fun un_corte_al_anotar_los_formularios_los_deja_todos_pendientes_y_el_reenvio_no_duplica() = conServidor(habilitadas = e2) {
        creada()
        db.sembrarFormulario("u1", "serologias", "hiv", "NO")
        db.sembrarFormulario("u1", "child_pugh", "tp", "3")
        cortarAlGuardarLoConfirmado()

        envio.empujarYa("u1").join()

        assertEquals(mapOf("serologias.hiv" to "NO", "child_pugh.tp" to "3"), servidor.consultas.getValue(7001).formularios)
        assertEquals(setOf("serologias/hiv", "child_pugh/tp"), db.formulariosSucios("u1"), "ninguno quedó marcado como enviado: o todos o ninguno")
        assertEquals(LoEnviado(), db.loEnviadoDe("u1"), "ni anotado lo que la web confirmó")
        assertEquals(emptyMap(), db.estadosDe("u1"), "y un corte local no se toma por un error de la web")

        repararElCorte()
        envio.empujarYa("u1").join()

        assertEquals(List(2) { "formularios 7001 child_pugh.tp,serologias.hiv" }, servidor.pedidos)
        assertEquals(servidor.formulariosEnviados[0], servidor.formulariosEnviados[1], "el reenvío es idéntico")
        assertEquals(mapOf("serologias.hiv" to "NO", "child_pugh.tp" to "3"), servidor.consultas.getValue(7001).formularios, "sin duplicar nada")
        assertEquals(emptySet(), db.formulariosSucios("u1"))
        assertEquals(mapOf("serologias/hiv" to "NO", "child_pugh/tp" to "3"), db.loEnviadoDe("u1").formularios, "y ahora sí queda anotado")
    }

    @Test
    fun un_corte_al_anotar_los_antecedentes_los_deja_pendientes_y_sin_anotar() = conServidor(habilitadas = e2) {
        creada()
        db.sembrarAntecedente("a1", "u1", clave = "hta", detalle = "padre")
        db.sembrarAntecedente("a2", "u1", clave = "nota", detalle = "Texto")
        cortarAlGuardarLoConfirmado()

        envio.empujarYa("u1").join()

        assertTrue(db.antecedenteSucio("a1") && db.antecedenteSucio("a2"))
        assertEquals(emptyMap(), db.loEnviadoDe("u1").antecedentes)
        assertEquals(emptyMap(), db.estadosDe("u1"))

        repararElCorte()
        envio.empujarYa("u1").join()

        assertEquals(List(2) { "antecedentes 5001 7001 hta,nota" }, servidor.pedidos)
        assertEquals(servidor.antecedentesRecibidos[0].cuerpo, servidor.antecedentesRecibidos[1].cuerpo, "el reenvío es idéntico")
        assertEquals(mapOf("hta" to "1|padre", "nota" to "Texto"), servidor.consultas.getValue(7001).antecedentes, "sin duplicar nada")
        assertFalse(db.antecedenteSucio("a1") || db.antecedenteSucio("a2"))
        assertEquals(mapOf("hta" to "1|padre", "nota" to "1|Texto"), db.loEnviadoDe("u1").antecedentes)
    }

    @Test
    fun un_corte_al_anotar_los_estudios_los_deja_pendientes_y_el_reenvio_no_duplica() = conServidor(habilitadas = e2) {
        creada()
        db.sembrarRegistro("r-eco", "u1", "eco_abdominal", detalle = "hígado graso")
        cortarAlGuardarLoConfirmado()

        envio.empujarYa("u1").join()

        assertEquals("hígado graso", servidor.registros.values.single().campos["detalle"], "la web ya lo tiene")
        assertEquals("", db.registroLocal("r-eco")!!.remotoId, "el id de la web no quedó a medias")
        assertTrue(db.registroSucio("r-eco"))
        assertEquals(emptyMap(), db.loEnviadoDe("u1").registros, "ni anotado lo que la web confirmó")
        assertEquals(emptyMap(), db.estadosDe("u1"), "y un corte local no se toma por un error de la web")

        repararElCorte()
        envio.empujarYa("u1").join()

        assertEquals(List(2) { "registros 7001 r:r-eco" }, servidor.pedidos, "el reenvío repite los mismos ref")
        assertEquals(servidor.cuerposDeRegistros[0], servidor.cuerposDeRegistros[1], "byte a byte: el mismo cuerpo")
        assertEquals(1, servidor.registros.size, "sin duplicar el estudio")
        assertFalse(db.registroSucio("r-eco"))
        assertEquals(
            mapOf("fecha" to "2026-11-06", "detalle" to "hígado graso"),
            db.loEnviadoDe("u1").registros.getValue(servidor.registros.values.single().id.toString()),
            "y ahora sí queda anotado",
        )
    }

    @Test
    fun un_corte_al_anotar_las_cargas_las_deja_pendientes_sin_id_y_el_reenvio_no_duplica() = conServidor(habilitadas = seccionesDe(SubentregaDeEscritura.E3)) {
        creada()
        db.sembrarCarga("l1", "u1", valores = mapOf("hb" to "13"))
        cortarAlGuardarLoConfirmado()

        envio.empujarYa("u1").join()

        val enLaWeb = servidor.cargas.values.single()
        assertEquals("13", enLaWeb.valores["hb"], "la web ya lo tiene")
        assertEquals("", db.cargaLocal("l1")!!.remotoId, "el id de la web no quedó a medias")
        assertTrue(db.cargaSucia("l1"))
        assertEquals(emptyMap(), db.loEnviadoDe("u1").cargas, "ni anotado lo que la web confirmó")
        assertEquals(emptyMap(), db.estadosDe("u1"), "y un corte local no se toma por un error de la web")

        repararElCorte()
        envio.empujarYa("u1").join()

        assertEquals(List(2) { "laboratorio 7001 l:l1" }, servidor.pedidos, "el reenvío repite la misma clave")
        assertEquals(servidor.cuerposDeCargas[0], servidor.cuerposDeCargas[1], "byte a byte: el mismo cuerpo")
        assertEquals(1, servidor.cargas.size, "sin duplicar la carga")
        assertFalse(db.cargaSucia("l1"))
        assertEquals(enLaWeb.id.toString(), db.cargaLocal("l1")!!.remotoId, "ahora sí queda el id de siempre")
        assertEquals(
            mapOf("hb" to "13"),
            db.loEnviadoDe("u1").cargas.getValue(enLaWeb.id.toString()),
            "y ahora sí queda anotado",
        )
    }
}
