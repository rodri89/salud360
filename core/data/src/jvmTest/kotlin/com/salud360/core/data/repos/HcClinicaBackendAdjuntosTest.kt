package com.salud360.core.data.repos

import com.salud360.core.data.files.ArchivoStore
import com.salud360.core.data.hcgeneral.SECCION_DOCUMENTOS
import com.salud360.core.data.lista
import com.salud360.core.data.mappers.toModel
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.network.TobbException
import com.salud360.core.data.network.hc.AdjuntoDeLaWebDto
import com.salud360.core.data.network.hc.ClinicaAdjuntosApi
import com.salud360.core.data.network.hc.RespuestaAdjuntoDto
import com.salud360.core.data.network.hc.RespuestaAdjuntosDto
import com.salud360.core.data.network.hc.RespuestaBajaDeAdjuntoDto
import com.salud360.core.data.network.hc.RespuestaLimitesDeAdjuntosDto
import com.salud360.core.data.repos.HcClinicaPruebas.Contexto
import com.salud360.core.data.repos.HcClinicaPruebas.FICHA
import com.salud360.core.data.repos.HcClinicaPruebas.PACIENTE
import com.salud360.core.data.repos.HcClinicaPruebas.conBackend
import com.salud360.core.data.repos.HcClinicaPruebas.textos
import com.salud360.core.data.sync.sembrarDeLaWeb
import com.salud360.core.data.sync.sembrarPropia
import com.salud360.core.model.especialidad.CodigosHc
import com.salud360.core.model.hc.Archivo
import com.salud360.core.model.hc.EstadoConsulta
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spec 005, T106 (RF-45, RF-33): la lectura de la HC general lista los **documentos** de la web al abrir una consulta, con la
 * regla de Q-E4-3 —una consulta **nueva y en curso** lista todos los documentos del paciente (`GET pacientes/{id}/adjuntos`) y
 * una **previa** solo los de esa consulta (`GET consultas/{id}/adjuntos`)—, los guarda como filas `archivo` sin archivo y los
 * baja con la sesión la primera vez que hay que mostrarlos. Lo que la app subió y lo que trae la lectura es **una sola fila**
 * (se empareja por `remoto_id`), un documento dado de baja en la web deja de listarse sin borrar el archivo local (Q15) y un
 * `404` del archivo no rompe la pantalla. API de adjuntos falsa y base SQLite de verdad; datos inventados.
 */
class HcClinicaBackendAdjuntosTest {

    private fun documento(
        id: Long,
        consultaWebId: Long,
        nombre: String = "informe-$id.pdf",
        tipo: String = "pdf",
        tamanio: Long = 1234,
    ) = AdjuntoDeLaWebDto(
        id = "$id",
        nombreOriginal = nombre,
        tamanio = tamanio,
        tipo = tipo,
        creadoEn = "2026-11-13 10:00:00",
        consultaId = "$consultaWebId",
    )

    /** Los documentos que se listan para el paciente (los de sus consultas, vigentes y de la sección `documentos`). */
    private suspend fun Contexto.documentos() =
        db.historiaClinicaQueries.archivosDePaciente(PACIENTE, SECCION_DOCUMENTOS).lista { it.toModel() }

    /** Todas las filas de `archivo` del paciente, también las que ya no se listan (para ver que no se borró nada). */
    private suspend fun Contexto.filas() = db.historiaClinicaQueries.archivosDePacienteTodos(PACIENTE).lista { it.toModel() }

    private suspend fun Contexto.fila(id: String) = filas().first { it.id == id }

    /** El `dirty` de la fila, tal como quedó en la base (el modelo no lo lleva). */
    private fun Contexto.dirty(id: String) = textos(driver, "SELECT dirty FROM archivo WHERE id = '$id'").single()

    private fun carpetaTemporal(): File =
        File.createTempFile("salud360-adjuntos-t106", "").also { it.delete(); it.mkdirs() }

    // ---------- Una consulta nueva lista por paciente; una previa, por consulta (Q-E4-3) ----------

    @Test
    fun una_consulta_nueva_lista_todos_los_documentos_del_paciente() {
        val web = AdjuntosFalso(
            dePaciente = listOf(
                documento(2001, 7001),
                documento(2002, 7002, nombre = "foto.png", tipo = "png", tamanio = 20),
                // De una consulta que este dispositivo no tiene: no se lista (RF-46).
                documento(2009, 7999),
            ),
        )
        conBackend(adjuntos = web) {
            db.sembrarPropia("u-1") // propia, abierta y sin id de la web: la que se está cargando
            db.sembrarDeLaWeb("hcc-c7001")
            db.sembrarPropia("u-2", remotoId = "7002", estado = EstadoConsulta.CERRADA, dirty = false)

            backend.traerAdjuntos("u-1")

            assertEquals(listOf(FICHA), web.pedidosDePaciente, "una consulta nueva y en curso lista todos los documentos del paciente")
            assertEquals(emptyList(), web.pedidosDeConsulta, "y no pide la lista de una consulta")
            assertEquals(listOf("hcc-f2001", "hcc-f2002"), documentos().map { it.id }, "sin el de una consulta que no está en el dispositivo")

            val pdf = fila("hcc-f2001")
            assertEquals("hcc-c7001", pdf.consultaId, "cada documento cuelga de su consulta de origen")
            assertEquals(PACIENTE, pdf.pacienteId)
            assertEquals(SECCION_DOCUMENTOS, pdf.seccion)
            assertEquals("informe-2001.pdf", pdf.nombre)
            assertEquals("2001", pdf.remotoId, "el id de la web queda en la fila para bajarlo y para emparejarlo")
            assertEquals(1234L, pdf.tamanioBytes)
            assertNull(pdf.rutaLocal, "la fila se guarda sin archivo: los bytes se bajan la primera vez que se abre")
            assertEquals("application/pdf", pdf.mime)
            assertTrue(pdf.activo)
            assertEquals("0", dirty("hcc-f2001"), "lo traído de la web no queda pendiente de envío")

            val png = fila("hcc-f2002")
            assertEquals("u-2", png.consultaId, "lo de una propia ya enviada cuelga de la propia, no de una hcc-c")
            assertEquals("image/png", png.mime)
            assertEquals(20L, png.tamanioBytes)
        }
    }

    @Test
    fun una_consulta_previa_lista_solo_los_documentos_de_esa_consulta() {
        val web = AdjuntosFalso(
            dePaciente = listOf(documento(2002, 7002)),
            deConsulta = listOf(documento(2001, 7001)),
        )
        conBackend(adjuntos = web) {
            db.sembrarDeLaWeb("hcc-c7001") // una consulta de la web, cerrada: es una previa
            db.sembrarPropia("u-2", remotoId = "7002", dirty = false)

            backend.traerAdjuntos("hcc-c7001")

            assertEquals(listOf(7001L), web.pedidosDeConsulta, "una consulta previa lista solo los documentos de esa consulta")
            assertEquals(emptyList(), web.pedidosDePaciente, "y no los de todo el paciente")
            assertEquals(listOf("hcc-f2001"), documentos().map { it.id })
            assertEquals("hcc-c7001", documentos().single().consultaId)
        }
    }

    @Test
    fun una_consulta_previa_que_nunca_se_envio_no_pide_nada() {
        val web = AdjuntosFalso(dePaciente = listOf(documento(2001, 7001)))
        conBackend(adjuntos = web) {
            db.sembrarPropia("u-1", estado = EstadoConsulta.CERRADA) // cerrada acá y todavía sin id de la web

            backend.traerAdjuntos("u-1")

            assertEquals(emptyList(), web.pedidosDePaciente, "sin consulta en la web no hay lista que pedir")
            assertEquals(emptyList(), web.pedidosDeConsulta)
            assertEquals(emptyList(), filas(), "ni se inventa una fila")
        }
    }

    @Test
    fun sin_api_de_adjuntos_no_se_lista_ni_se_baja_nada() {
        conBackend { // el backend armado sin API de adjuntos (el constructor de cinco argumentos)
            db.sembrarPropia("u-1")
            db.sembrarDeLaWeb("hcc-c7001")
            backend.traerAdjuntos("u-1")

            assertEquals(emptyList(), filas())
            assertNull(
                backend.bajarArchivo(
                    "7001",
                    Archivo("hcc-f2001", PACIENTE, "hcc-c7001", null, SECCION_DOCUMENTOS, "x.pdf", "application/pdf", 1, 0, null, null, false, true, "2001"),
                ),
            )
        }
    }

    // ---------- Lo que la app subió y lo que trae la lectura son uno solo (RF-33) ----------

    @Test
    fun lo_que_la_app_subio_y_lo_que_trae_la_lectura_son_una_sola_fila() {
        val web = AdjuntosFalso(dePaciente = listOf(documento(2002, 7002)))
        conBackend(adjuntos = web) {
            db.sembrarPropia("u-2", remotoId = "7002", dirty = false)
            db.sembrarPropia("u-1")
            // Lo que subió la app (T104): su id local, el archivo en el dispositivo y el id que confirmó la web.
            db.historiaClinicaQueries.upsertArchivo(
                Archivo(
                    "a-1", PACIENTE, "u-2", null, SECCION_DOCUMENTOS, "informe.pdf", "application/pdf", 1234, 0,
                    "/tmp/lo-que-sea.pdf", null, true, true, "2002",
                ).toRow(90, dirty = false),
            )

            backend.traerAdjuntos("u-1")

            assertEquals(listOf("a-1"), filas().map { it.id }, "se empareja por remoto_id: no nace una hcc-f2002 aparte")
            assertEquals("/tmp/lo-que-sea.pdf", fila("a-1").rutaLocal, "la fila del dispositivo conserva su archivo")
            assertEquals("2002", fila("a-1").remotoId)
        }
    }

    @Test
    fun lo_pendiente_de_enviar_no_se_toca() {
        val web = AdjuntosFalso(dePaciente = listOf(documento(2002, 7002)))
        conBackend(adjuntos = web) {
            db.sembrarPropia("u-2", remotoId = "7002", dirty = false)
            db.sembrarPropia("u-1")
            // Un documento del médico que todavía no viajó: no tiene id de la web y la lista no lo trae.
            db.historiaClinicaQueries.upsertArchivo(
                Archivo("b-1", PACIENTE, "u-1", null, SECCION_DOCUMENTOS, "nuevo.pdf", "application/pdf", 10, 0, "/tmp/nuevo.pdf").toRow(95),
            )

            backend.traerAdjuntos("u-1")

            assertEquals(listOf("b-1", "hcc-f2002"), filas().map { it.id }.sorted(), "lo que la web trae se suma sin borrar lo del médico")
            assertTrue(fila("b-1").activo, "lo pendiente de enviar sigue listado")
            assertEquals("1", dirty("b-1"), "y sigue pendiente de envío")
        }
    }

    // ---------- Una baja en la web: deja de listarse y nada se borra (Q15) ----------

    @Test
    fun un_documento_dado_de_baja_en_la_web_deja_de_listarse_sin_borrar_el_archivo_local() {
        val carpeta = carpetaTemporal()
        try {
            val store = ArchivoStore(carpeta.absolutePath)
            val ruta = runBlocking { store.guardar("hcc-f2001", "informe.pdf", byteArrayOf(37, 80, 68, 70)) }
            // La web ya no lo lista (se dio de baja allá); el dispositivo lo tenía traído y con el archivo cacheado.
            val web = AdjuntosFalso(deConsulta = emptyList())
            conBackend(adjuntos = web) {
                db.sembrarPropia("u-2", remotoId = "7002", estado = EstadoConsulta.CERRADA, dirty = false)
                db.historiaClinicaQueries.upsertArchivo(
                    Archivo(
                        "hcc-f2001", PACIENTE, "u-2", null, SECCION_DOCUMENTOS, "informe.pdf", "application/pdf", 4, 0,
                        ruta, null, false, true, "2001",
                    ).toRow(90, dirty = false),
                )
                assertEquals(listOf("hcc-f2001"), documentos().map { it.id }, "antes de la lectura se lista")

                backend.traerAdjuntos("u-2")

                assertEquals(emptyList(), documentos().map { it.id }, "deja de listarse")
                val guardada = fila("hcc-f2001")
                assertFalse(guardada.activo, "se da de baja lógica en el dispositivo, no se borra")
                assertEquals(ruta, guardada.rutaLocal, "el archivo local no se borra")
                assertEquals("2001", guardada.remotoId)
                assertEquals("0", dirty("hcc-f2001"), "y no queda algo para enviar: la web es la dueña")
                assertTrue(runBlocking { store.existe(ruta) }, "el archivo del dispositivo sigue ahí")
            }
        } finally {
            carpeta.deleteRecursively()
        }
    }

    // ---------- Los bytes: con la sesión, una sola vez y cacheados ----------

    @Test
    fun bytesDe_baja_el_documento_una_sola_vez_y_lo_cachea() {
        val carpeta = carpetaTemporal()
        try {
            val store = ArchivoStore(carpeta.absolutePath)
            val pdf = byteArrayOf(37, 80, 68, 70, 45, 49, 46, 52)
            val web = AdjuntosFalso(dePaciente = listOf(documento(2001, 7001))).apply { bytes["2001"] = pdf }
            conBackend(adjuntos = web) {
                db.sembrarDeLaWeb("hcc-c7001")
                db.sembrarPropia("u-1")
                backend.traerAdjuntos("u-1")
                val repo = HcRepository(db, mapOf(CodigosHc.GENERAL to backend))
                val consulta = requireNotNull(repo.consulta("u-1"))
                val adjunto = documentos().single()
                assertNull(adjunto.rutaLocal, "la lectura no trae el archivo, solo la lista")

                assertEquals(pdf.toList(), repo.bytesDeArchivo(consulta, adjunto, store)?.toList(), "lo baja con la sesión del médico")
                assertEquals(listOf("2001"), web.bajados)
                val guardado = documentos().single()
                val ruta = requireNotNull(guardado.rutaLocal) { "queda cacheado en el dispositivo" }
                assertTrue(runBlocking { store.existe(ruta) })
                assertEquals(pdf.toList(), runBlocking { store.leer(ruta) }?.toList())

                // La pantalla puede seguir con el adjunto de antes (sin ruta): la segunda vez sale de la copia local.
                assertEquals(pdf.toList(), repo.bytesDeArchivo(consulta, adjunto, store)?.toList())
                assertEquals(listOf("2001"), web.bajados, "no se vuelve a pedir")
            }
        } finally {
            carpeta.deleteRecursively()
        }
    }

    @Test
    fun un_404_del_archivo_no_rompe_la_pantalla() {
        val carpeta = carpetaTemporal()
        try {
            val store = ArchivoStore(carpeta.absolutePath)
            val web = AdjuntosFalso(dePaciente = listOf(documento(2001, 7001))).apply {
                fallaAlBajar = TobbException(404, "adjunto_no_encontrado", "No existe el adjunto.")
            }
            conBackend(adjuntos = web) {
                db.sembrarDeLaWeb("hcc-c7001")
                db.sembrarPropia("u-1")
                backend.traerAdjuntos("u-1")
                val repo = HcRepository(db, mapOf(CodigosHc.GENERAL to backend))
                val consulta = requireNotNull(repo.consulta("u-1"))
                val adjunto = documentos().single()

                assertNull(repo.bytesDeArchivo(consulta, adjunto, store), "el 404 del archivo no rompe la pantalla: se muestra vacío")

                assertNull(documentos().single().rutaLocal, "ni se inventa un archivo")
                assertEquals(listOf("2001"), web.bajados, "se preguntó una sola vez")
            }
        } finally {
            carpeta.deleteRecursively()
        }
    }

    /**
     * La web de adjuntos de mentira (T106): lo que contesta cada lista, los bytes de cada documento y qué se pidió. La subida,
     * la baja y los límites son de T104 y acá no se usan.
     */
    private class AdjuntosFalso(
        var dePaciente: List<AdjuntoDeLaWebDto> = emptyList(),
        var deConsulta: List<AdjuntoDeLaWebDto> = emptyList(),
    ) : ClinicaAdjuntosApi {
        val pedidosDePaciente = mutableListOf<Long>()
        val pedidosDeConsulta = mutableListOf<Long>()
        val bajados = mutableListOf<String>()
        val bytes = mutableMapOf<String, ByteArray>()
        var fallaAlBajar: Throwable? = null

        override suspend fun listarDePaciente(pacienteWebId: Long): RespuestaAdjuntosDto {
            pedidosDePaciente += pacienteWebId
            return RespuestaAdjuntosDto(dePaciente)
        }

        override suspend fun listarDeConsulta(consultaWebId: Long): RespuestaAdjuntosDto {
            pedidosDeConsulta += consultaWebId
            return RespuestaAdjuntosDto(deConsulta)
        }

        override suspend fun bajar(adjuntoWebId: String): ByteArray {
            bajados += adjuntoWebId
            fallaAlBajar?.let { throw it }
            return bytes[adjuntoWebId] ?: ByteArray(0)
        }

        override suspend fun subir(
            consultaWebId: Long,
            clave: String,
            dispositivo: String,
            nombre: String,
            mime: String,
            bytes: ByteArray,
            tipo: String,
        ): RespuestaAdjuntoDto = error("T106 no sube documentos")

        override suspend fun borrar(
            adjuntoWebId: String,
            consultaWebId: Long,
            clave: String,
            dispositivo: String,
        ): RespuestaBajaDeAdjuntoDto = error("T106 no da de baja documentos")

        override suspend fun limites(): RespuestaLimitesDeAdjuntosDto = error("T106 no pregunta los límites")
    }
}
