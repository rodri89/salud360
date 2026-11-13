package com.salud360.core.data.sync

import com.salud360.core.data.hcgeneral.PARTE_ADJUNTOS
import com.salud360.core.data.hcgeneral.PARTE_CIERRE
import com.salud360.core.data.hcgeneral.SECCION_DOCUMENTOS
import com.salud360.core.data.hcgeneral.SubentregaDeEscritura
import com.salud360.core.data.hcgeneral.seccionesDe
import com.salud360.core.data.hcgeneral.textoDelEstado
import com.salud360.core.data.network.TobbException
import com.salud360.core.data.network.hc.RespuestaLimitesDeAdjuntosDto
import com.salud360.core.data.repos.HcClinicaPruebas.MEDICO
import com.salud360.core.data.repos.HcClinicaPruebas.PACIENTE
import com.salud360.core.model.Id
import com.salud360.core.model.hc.Archivo
import com.salud360.core.model.hc.CausaEnEspera
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.hc.EstadoEnvio
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Spec 005 RF-15, RF-32, RF-44, RF-46, RF-55, RF-56 y RF-61 (T104): la cola de envío manda los **documentos** de la sección `documentos`
 * de una consulta propia de la HC general (PDF, JPG, JPEG y PNG) con **un archivo por pedido** (P23), lee los bytes del `ArchivoStore`
 * del dispositivo, los sube con la clave `a:<id local>` (estable entre reintentos: una subida cortada que se repite devuelve la misma
 * fila y un solo archivo) y deja el `id` de la web en `archivo.remoto_id` en la **misma transacción** que limpia la fila (RF-61). Una
 * baja que la web tiene sale como `DELETE` con `a:<id local>-baja`. El aviso del tope (T103) se mira **antes** de subir: un documento
 * demasiado grande no se sube ni figura enviado (RF-44). Un archivo que falta en este dispositivo queda «No se pudo enviar: falta el
 * archivo en este dispositivo» **sin reintento solo**, y una causa ajena al dato (429/5xx) deja «En espera» con la espera de siempre,
 * sin ráfagas, y el cierre espera a que todo suba. Contra el [ServidorFalso] con memoria y con la subentrega E4 habilitada
 * **explícitamente** (el candado de la versión —`SECCIONES_HABILITADAS`— todavía no enciende `documentos`). Las fotos de exámenes
 * quedan fuera de alcance (T105) y no llegan.
 */
class HcClinicaEnvioAdjuntosTest {
    private val e4 = seccionesDe(SubentregaDeEscritura.E4)

    /** Una propia ya creada en la web (la 7001), sin nada sucio más que lo que siembre cada test. */
    private suspend fun Escena.creada(estado: EstadoConsulta = EstadoConsulta.ABIERTA, dirty: Boolean = false) {
        db.sembrarPropia("u1", remotoId = "7001", estado = estado, dirty = dirty)
        servidor.consultas[7001] = ServidorFalso.ConsultaWeb(7001, "c:u1", 5001, "control")
    }

    /** Un documento del paciente con su archivo de verdad en el `ArchivoStore` de la escena (o sin archivo, [conArchivo] = false). */
    private suspend fun Escena.documento(
        id: Id = "a1",
        nombre: String = "$id.pdf",
        mime: String = "application/pdf",
        bytes: ByteArray = "%PDF-1.4 documento inventado".toByteArray(),
        tamanioBytes: Long = bytes.size.toLong(),
        remotoId: String = "",
        activo: Boolean = true,
        conArchivo: Boolean = true,
        actualizadaEn: Long = 160,
    ): Archivo {
        val ruta = if (conArchivo) archivos.guardar(id, nombre, bytes) else null
        db.sembrarDocumento(
            id, nombre = nombre, mime = mime, rutaLocal = ruta, tamanioBytes = tamanioBytes, remotoId = remotoId, activo = activo,
            actualizadaEn = actualizadaEn,
        )
        return db.archivoLocal(id)!!
    }

    /**
     * Espera al envío que disparó un reintento después de mover [RelojFalso]. El envío corre en su propia corrutina y los bytes se leen con
     * `Dispatchers.IO` (`ArchivoStore`), así que `reloj.avanzar` no alcanza: se lo espera con el turno de la consulta (RF-54).
     */
    private suspend fun Escena.terminarElEnvio(consultaId: Id = "u1") = turnos.conTurno(consultaId) { }

    // ---------- Un archivo por pedido, en su lugar ----------

    @Test
    fun los_documentos_van_entre_las_cargas_y_el_cierre() = conServidor(habilitadas = e4) {
        db.sembrarPropia("u1", estado = EstadoConsulta.CERRADA)
        db.sembrarTexto("u1")
        db.sembrarCarga("l1", "u1")
        documento("a1", actualizadaEn = 170)
        documento("a2", nombre = "a2.png", mime = "image/png", actualizadaEn = 180)

        envio.empujarYa("u1").join()

        assertEquals(
            listOf("crear c:u1", "secciones 7001 nota", "laboratorio 7001 l:l1", "adjunto 7001 a:a1", "adjunto 7001 a:a2", "cerrar 7001 c:u1-cierre"),
            servidor.pedidos,
            "un archivo por pedido, después de las cargas y antes del cierre",
        )
        assertEquals(2, servidor.documentos.size)
        assertEquals(emptyList(), db.filasSucias().flatten())
        assertEquals(emptyMap(), db.estadosDe("u1"))
    }

    @Test
    fun un_documento_nuevo_se_sube_con_la_clave_de_su_id_y_los_bytes_del_dispositivo() = conServidor(habilitadas = e4) {
        creada()
        val bytes = "%PDF-1.7 documento inventado".toByteArray()
        documento("a1", bytes = bytes, actualizadaEn = 160)

        envio.empujarYa("u1").join()

        assertEquals(listOf("adjunto 7001 a:a1"), servidor.pedidos, "la clave de repetibilidad sale del id local")
        val subido = servidor.documentosSubidos.single()
        assertTrue(subido.bytes.contentEquals(bytes), "los bytes se leen del ArchivoStore, no se inventan")
        assertEquals("a1.pdf", subido.nombre)
        assertEquals("application/pdf", subido.mime)
        assertEquals(1, servidor.pedidosDeLimites.get(), "el tope se pregunta una sola vez por paso")
        val enLaWeb = servidor.documentos.values.single()
        assertEquals(enLaWeb.id.toString(), db.archivoLocal("a1")!!.remotoId, "el id que contestó la web queda en la fila")
        assertFalse(db.archivoSucio("a1"), "y la fila se limpia en la misma transacción")
        assertEquals(160, db.archivoActualizadoEn("a1"), "sin mover el updated_at (RF-55)")
        assertEquals(emptyMap(), db.estadosDe("u1"))
        assertEquals(emptyMap<String, Map<String, String>>(), db.loEnviadoDe("u1").cargas, "el id de un adjunto vive en su fila")
    }

    @Test
    fun el_jpeg_y_el_png_viajan_con_su_nombre_y_su_content_type() = conServidor(habilitadas = e4) {
        creada()
        documento("jpg", nombre = "foto.jpg", mime = "image/jpeg", bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()), actualizadaEn = 170)
        documento("png", nombre = "foto.png", mime = "image/png", bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47), actualizadaEn = 180)

        envio.empujarYa("u1").join()

        assertEquals(listOf("adjunto 7001 a:jpg", "adjunto 7001 a:png"), servidor.pedidos)
        assertEquals(
            listOf("foto.jpg" to "image/jpeg", "foto.png" to "image/png"),
            servidor.documentosSubidos.map { it.nombre to it.mime },
        )
        assertEquals(2, servidor.documentos.size)
        assertEquals(
            servidor.documentos.values.map { it.id.toString() }.sorted(),
            listOf(db.archivoLocal("jpg")!!.remotoId, db.archivoLocal("png")!!.remotoId).sorted(),
        )
        assertFalse(db.archivoSucio("jpg") || db.archivoSucio("png"))
    }

    @Test
    fun la_baja_de_un_documento_que_la_web_tiene_sale_como_delete_y_se_limpia() = conServidor(habilitadas = e4) {
        creada()
        documento("a1")
        envio.empujarYa("u1").join()
        val id = db.archivoLocal("a1")!!.remotoId

        // El repositorio lo da de baja (T103): `activo = 0, dirty = 1` con su `remoto_id`.
        db.sembrarDocumento("a1", rutaLocal = db.archivoLocal("a1")!!.rutaLocal, remotoId = id, activo = false, actualizadaEn = 600)
        envio.empujarYa("u1").join()

        assertEquals(listOf("adjunto 7001 a:a1", "bajaDeAdjunto 7001 a:a1-baja"), servidor.pedidos)
        assertFalse(servidor.documentos.getValue(id.toLong()).activo, "la web lo da de baja (lógica, sin borrar el archivo)")
        assertFalse(db.archivoSucio("a1"))
        assertEquals(id, db.archivoLocal("a1")!!.remotoId, "la baja no toca el id de la web")
        assertEquals(emptyMap(), db.estadosDe("u1"))
    }

    @Test
    fun lo_que_el_repositorio_guarda_o_da_de_baja_queda_sucio_y_la_cola_lo_ve() = conServidor(habilitadas = e4) {
        creada()
        // El camino de la pantalla (`ConsultaViewModel.agregarArchivo`/`eliminarArchivo`): el autoguardado ya avisa a la cola.
        val ruta = archivos.guardar("a1", "informe.pdf", "%PDF-1.4".toByteArray())
        assertTrue(hc.guardarArchivo(Archivo("a1", PACIENTE, "u1", null, SECCION_DOCUMENTOS, "informe.pdf", "application/pdf", 8, rutaLocal = ruta)))

        envio.empujarYa("u1").join()

        assertEquals(listOf("adjunto 7001 a:a1"), servidor.pedidos, "la cola ve el documento sin que la pantalla tenga que hacer nada más")
        assertTrue(hc.eliminarArchivo("a1"))

        envio.empujarYa("u1").join()

        assertEquals(listOf("adjunto 7001 a:a1", "bajaDeAdjunto 7001 a:a1-baja"), servidor.pedidos)
        assertFalse(db.archivoSucio("a1"))
    }

    // ---------- Lo que no se puede subir ----------

    @Test
    fun un_documento_que_falta_en_este_dispositivo_queda_no_se_pudo_enviar_sin_reintento_solo() = conServidor(habilitadas = e4) {
        creada(estado = EstadoConsulta.CERRADA, dirty = true)
        documento("a1", conArchivo = false)

        envio.empujarYa("u1").join()

        assertEquals(emptyList(), servidor.pedidos, "no se pide nada: los bytes no están")
        val estado = assertIs<EstadoEnvio.NoSePudoEnviar>(db.estadosDe("u1").getValue(PARTE_ADJUNTOS).estado)
        assertEquals("No se pudo enviar: falta el archivo en este dispositivo", textoDelEstado(estado))
        assertTrue(db.archivoSucio("a1"), "el dato sigue en el dispositivo")
        assertEquals("", db.archivoLocal("a1")!!.remotoId)
        assertEquals(0, reloj.esperando, "un rechazo del dato no arma un reintento solo")
        assertFalse("cerrar" in servidor.operaciones, "y el cierre espera")

        reloj.avanzar(3_600_000)
        assertEquals(emptyList(), servidor.pedidos, "ni solo ni en una hora")
        assertEquals(0, reloj.esperando)
    }

    @Test
    fun un_documento_mas_grande_que_el_tope_no_se_sube_ni_figura_enviado() = conServidor(habilitadas = e4) {
        creada(estado = EstadoConsulta.CERRADA, dirty = true)
        servidor.limitesDeAdjuntos = RespuestaLimitesDeAdjuntosDto(maximoBytes = 15_728_640, maximoWebBytes = 15_728_640)
        // El peso que decide es el de la fila (o el de los bytes, el mayor): el tope se mira antes de subir.
        documento("a1", tamanioBytes = 15_728_641)

        envio.empujarYa("u1").join()

        assertEquals(emptyList(), servidor.pedidos, "no se sube")
        assertEquals(emptyList(), servidor.documentosSubidos)
        assertEquals(1, servidor.pedidosDeLimites.get())
        assertEquals("", db.archivoLocal("a1")!!.remotoId, "ni figura enviado")
        assertTrue(db.archivoSucio("a1"))
        val estado = assertIs<EstadoEnvio.NoSePudoEnviar>(db.estadosDe("u1").getValue(PARTE_ADJUNTOS).estado)
        assertEquals("No se pudo enviar: El archivo pesa 15,1 MB y el máximo es 15 MB", textoDelEstado(estado), "el aviso de T103")
        assertEquals(0, reloj.esperando)
        assertFalse("cerrar" in servidor.operaciones)
    }

    @Test
    fun un_429_deja_en_espera_sin_rafaga_y_el_reintento_no_duplica_el_archivo() = conServidor(habilitadas = e4) {
        creada(estado = EstadoConsulta.CERRADA, dirty = true)
        documento("a1")
        servidor.falla = { if (it == "adjunto") TobbException(429, null, "Too Many Attempts.") else null }

        envio.empujarYa("u1").join()

        val estado = assertIs<EstadoEnvio.EnEspera>(db.estadosDe("u1").getValue(PARTE_ADJUNTOS).estado)
        assertEquals(CausaEnEspera.DEMASIADOS_PEDIDOS, estado.causa)
        assertIs<EstadoEnvio.EnEspera>(db.estadosDe("u1").getValue(PARTE_CIERRE).estado, "el cierre espera a que suba")
        assertTrue(db.archivoSucio("a1"))
        assertFalse("cerrar" in servidor.operaciones)

        val pedidos = servidor.pedidos.size
        reloj.avanzar(14_000)
        assertEquals(pedidos, servidor.pedidos.size, "sin ráfagas: se espera el reintento de los 15 s")

        servidor.falla = null
        reloj.avanzar(1_000)
        terminarElEnvio()

        assertEquals(listOf("adjunto 7001 a:a1", "adjunto 7001 a:a1", "cerrar 7001 c:u1-cierre"), servidor.pedidos)
        assertEquals(1, servidor.documentos.size, "el reintento no duplica el archivo")
        assertFalse(db.archivoSucio("a1"))
        assertEquals(emptyMap(), db.estadosDe("u1"))
    }

    @Test
    fun un_5xx_deja_en_espera_y_sale_solo_al_volver_el_servidor() = conServidor(habilitadas = e4) {
        creada()
        documento("a1")
        servidor.falla = { if (it == "adjunto") TobbException(500, "escritura", "No se pudo escribir.") else null }

        envio.empujarYa("u1").join()

        assertEquals(CausaEnEspera.SERVIDOR, assertIs<EstadoEnvio.EnEspera>(db.estadosDe("u1").getValue(PARTE_ADJUNTOS).estado).causa)
        assertTrue(db.archivoSucio("a1"))

        servidor.falla = null
        reloj.avanzar(15_000)
        terminarElEnvio()

        assertEquals(listOf("adjunto 7001 a:a1", "adjunto 7001 a:a1"), servidor.pedidos)
        assertEquals(1, servidor.documentos.size)
        assertFalse(db.archivoSucio("a1"))
        assertEquals(emptyMap(), db.estadosDe("u1"))
    }

    // ---------- La sesión y el candado ----------

    @Test
    fun con_la_sesion_de_otro_medico_no_sube_nada_ni_modifica_nada() = conServidor(habilitadas = e4) {
        creada()
        documento("a1")
        val antes = db.filasSucias()

        medicoDeLaSesion = "otro-medico"
        envio.empujarYa("u1").join()
        envio.marcarSucia("u1")
        reloj.avanzar(3_600_000)

        assertEquals(emptyList(), servidor.pedidos)
        assertEquals(antes, db.filasSucias())
        assertEquals(emptyMap(), db.estadosDe("u1"))

        medicoDeLaSesion = MEDICO
        envio.empujarYa("u1").join()

        assertEquals(listOf("adjunto 7001 a:a1"), servidor.pedidos)
    }

    @Test
    fun con_un_conjunto_que_no_incluye_e4_los_documentos_no_salen_ni_frenan_el_cierre() =
        conServidor(habilitadas = seccionesDe(SubentregaDeEscritura.E3)) {
            creada(estado = EstadoConsulta.CERRADA, dirty = true)
            documento("a1")

            envio.empujarYa("u1").join()

            assertEquals(listOf("cerrar 7001 c:u1-cierre"), servidor.pedidos, "sin `documentos` en el conjunto, esperan y el cierre sale")
            assertTrue(db.archivoSucio("a1"), "siguen sucias, para cuando se habiliten (RF-4)")
            assertEquals(0, envio.contarPendientes().total)
        }
}
