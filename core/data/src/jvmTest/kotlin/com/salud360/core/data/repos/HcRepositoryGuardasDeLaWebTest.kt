package com.salud360.core.data.repos

import com.salud360.core.data.hcgeneral.TIPO_LABORATORIO_GENERAL
import com.salud360.core.data.hcgeneral.idAntecedenteWeb
import com.salud360.core.data.hcgeneral.idCargaWeb
import com.salud360.core.data.hcgeneral.idFilaWeb
import com.salud360.core.data.hcgeneral.idPendienteWeb
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.repos.HcClinicaPruebas.FICHA
import com.salud360.core.data.repos.HcClinicaPruebas.MEDICO
import com.salud360.core.data.repos.HcClinicaPruebas.PACIENTE
import com.salud360.core.data.repos.HcClinicaPruebas.sembrar
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.Id
import com.salud360.core.model.hc.Antecedente
import com.salud360.core.model.hc.Archivo
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.hc.ExamenFisico
import com.salud360.core.model.hc.Laboratorio
import com.salud360.core.model.hc.Pendiente
import com.salud360.core.model.hc.RegistroClinico
import com.salud360.core.model.hc.SeccionValor
import com.salud360.core.model.hc.VacunaAplicada
import com.salud360.core.model.newId
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Las guardas de `HcRepository` contra la escritura sobre lo que vino de la web (spec 002, E7.3, decisión del usuario sobre
 * la duda de E6.6, RF-45, RF-47, RF-62): cerrar, reabrir, anular, editar y borrar una consulta o una fila `hcc-…` se
 * rechaza con `false` SIN tocar la base —ninguna fila cambia, ninguna se crea y nada queda con pendiente de envío—, y las
 * mismas acciones sobre lo del dispositivo y sobre pediatría siguen como siempre. Base SQLite temporal, datos inventados.
 */
class HcRepositoryGuardasDeLaWebTest {
    private val fecha = LocalDate.parse("2026-10-30")

    private val webAbierta: Id = "hcc-c7003"
    private val webCerrada: Id = "hcc-c7004"
    private val cargaWeb = idCargaWeb(TIPO_LABORATORIO_GENERAL, 703)
    private val registroWeb = idFilaWeb("veda", 1797)
    private val antecedenteWeb = idAntecedenteWeb(FICHA, "hta")
    private val pendienteWeb = idPendienteWeb(1744)
    private val archivoDeLaWeb = "hcc-adjunto-1"
    private val local: Id = "3f2c9d1e-0a6b-4c11-9a77-5c1d2e3f4a5b"

    /** Lo que la lectura dejó de la web, sin pendiente de envío (como `filaDeLaWeb`). */
    private suspend fun Salud360Db.sembrarLaWeb() {
        val q = historiaClinicaQueries
        sembrar(webAbierta, estado = EstadoConsulta.ABIERTA)
        sembrar(webCerrada, estado = EstadoConsulta.CERRADA)
        q.upsertSeccionValor(SeccionValor(webAbierta, "habitos", "texto", "Texto de la web").toRow(5, dirty = false))
        q.upsertExamenFisico(ExamenFisico(webAbierta, peso = "70", talla = "1.70").toRow(5, dirty = false))
        q.upsertAntecedente(Antecedente(antecedenteWeb, PACIENTE, "clinica", "familiares", "hta", flag = true, detalle = "Padre").toRow(5, dirty = false))
        q.upsertRegistro(RegistroClinico(registroWeb, PACIENTE, webCerrada, "clinica", "veda", fecha, mapOf("detalle" to "Estudio de la web")).toRow(5, dirty = false))
        q.upsertLaboratorio(Laboratorio(cargaWeb, PACIENTE, webCerrada, "clinica", TIPO_LABORATORIO_GENERAL, fecha, mapOf("albumina" to "4")).toRow(5, dirty = false))
        q.upsertPendiente(Pendiente(pendienteWeb, PACIENTE, MEDICO, "Pedir ecografía").toRow(5, dirty = false))
        q.upsertArchivo(Archivo(archivoDeLaWeb, PACIENTE, webCerrada, null, "documentos", "informe.pdf", "application/pdf").toRow(5, dirty = false))
    }

    private fun HcClinicaPruebas.Contexto.repo() = HcRepository(db, mapOf(backend.especialidad to backend))

    /** Todas las tablas de la historia clínica, fila por fila, más las vacunas. */
    private fun HcClinicaPruebas.Contexto.volcado(): Map<String, List<String>> =
        HcClinicaPruebas.volcarTodo(driver) + ("vacuna_aplicada" to HcClinicaPruebas.volcar(driver, "vacuna_aplicada"))

    private fun HcClinicaPruebas.Contexto.sucias(): Int =
        HcClinicaPruebas.filasSucias(driver) + HcClinicaPruebas.textos(driver, "SELECT COUNT(*) FROM vacuna_aplicada WHERE dirty <> 0").single().toInt()

    /** La operación se rechaza (`false`) y la base queda idéntica y sin filas sucias. */
    private suspend fun HcClinicaPruebas.Contexto.rechazada(nombre: String, operacion: suspend () -> Boolean) {
        val antes = volcado()
        assertEquals(0, sucias(), "$nombre: partimos de una base limpia")
        assertFalse(operacion(), "$nombre: se tiene que rechazar")
        assertEquals(antes, volcado(), "$nombre: la base no puede cambiar")
        assertEquals(0, sucias(), "$nombre: no puede dejar nada pendiente de envío")
    }

    // ---- La consulta de la web: cerrar, reabrir, anular, cambiar la fecha ----

    @Test
    fun cerrar_reabrir_y_anular_una_consulta_de_la_web_se_rechazan() = HcClinicaPruebas.conBackend {
        db.sembrarLaWeb()
        val r = repo()
        rechazada("cerrar la abierta") { r.cerrarConsulta(webAbierta) }
        rechazada("cerrar con fecha y edad") { r.cerrarConsulta(webAbierta, fecha, "33 años") }
        rechazada("reabrir la cerrada") { r.reabrirConsulta(webCerrada) }
        rechazada("anular la abierta") { r.anularConsulta(webAbierta) }
        rechazada("anular la cerrada") { r.anularConsulta(webCerrada) }
    }

    @Test
    fun guardar_la_cabecera_de_una_consulta_de_la_web_se_rechaza() = HcClinicaPruebas.conBackend {
        db.sembrarLaWeb()
        val r = repo()
        val c = r.consulta(webAbierta)!!
        rechazada("cambiar la fecha") { r.guardarConsulta(c.copy(fecha = LocalDate.parse("2026-01-01"))) }
        rechazada("cambiar el estado") { r.guardarConsulta(c.copy(estado = EstadoConsulta.CERRADA)) }
    }

    @Test
    fun una_consulta_con_remoto_id_de_un_backend_de_solo_lectura_tambien_se_rechaza() = HcClinicaPruebas.conBackend {
        // Aunque su id no sea hcc-…: el backend dice que lo que tiene remoto_id es de la web (E5.3).
        db.sembrar(local, remotoId = "88", estado = EstadoConsulta.ABIERTA)
        val r = repo()
        rechazada("cerrar") { r.cerrarConsulta(local) }
        rechazada("anular") { r.anularConsulta(local) }
        rechazada("guardar la cabecera") { r.guardarConsulta(r.consulta(local)!!.copy(edadMostrar = "otra")) }
    }

    @Test
    fun sin_ningun_backend_el_prefijo_de_la_web_igual_se_rechaza() = HcClinicaPruebas.conBackend {
        db.sembrarLaWeb()
        val r = HcRepository(db)
        rechazada("cerrar") { r.cerrarConsulta(webAbierta) }
        rechazada("anular") { r.anularConsulta(webCerrada) }
        rechazada("guardar una sección") { r.guardarSeccion(webAbierta, "habitos", mapOf("texto" to "otro")) }
    }

    // ---- Lo que cuelga de la consulta de la web ----

    @Test
    fun las_secciones_y_el_examen_fisico_de_una_consulta_de_la_web_no_se_escriben() = HcClinicaPruebas.conBackend {
        db.sembrarLaWeb()
        val r = repo()
        rechazada("guardarSeccion de un campo que existe") { r.guardarSeccion(webAbierta, "habitos", mapOf("texto" to "Otro texto")) }
        rechazada("guardarSeccion de un campo nuevo") { r.guardarSeccion(webAbierta, "diuresis", mapOf("texto" to "Nuevo")) }
        rechazada("guardarValor") { r.guardarValor(webAbierta, "habitos", "texto", "Otro") }
        rechazada("guardarValor vacío") { r.guardarValor(webAbierta, "habitos", "texto", "") }
        rechazada("guardarExamenFisico") { r.guardarExamenFisico(ExamenFisico(webAbierta, peso = "99")) }
    }

    @Test
    fun los_adjuntos_los_diagnosticos_y_las_vacunas_de_una_consulta_de_la_web_no_se_escriben() = HcClinicaPruebas.conBackend {
        db.sembrarLaWeb()
        val r = repo()
        rechazada("guardarArchivo nuevo") {
            r.guardarArchivo(Archivo(newId(), PACIENTE, webAbierta, null, "documentos", "nuevo.pdf", "application/pdf"))
        }
        rechazada("guardarArchivo existente") { r.guardarArchivo(r.archivo(archivoDeLaWeb)!!.copy(nombre = "otro.pdf")) }
        rechazada("eliminarArchivo") { r.eliminarArchivo(archivoDeLaWeb) }
        rechazada("asignarDiagnostico") { r.asignarDiagnostico(webAbierta, "d1", true) }
        rechazada("guardarVacuna") { r.guardarVacuna(VacunaAplicada(newId(), PACIENTE, "antigripal", consultaId = webAbierta)) }
    }

    // ---- Lo que vino de la web, escrito desde cualquier consulta ----

    @Test
    fun el_laboratorio_de_la_web_no_se_edita_ni_se_borra_ni_se_crea_en_una_consulta_de_la_web() = HcClinicaPruebas.conBackend {
        db.sembrarLaWeb()
        val r = repo()
        rechazada("eliminarLaboratorio") { r.eliminarLaboratorio(cargaWeb) }
        rechazada("guardarLaboratorio de la carga de la web") {
            r.guardarLaboratorio(Laboratorio(cargaWeb, PACIENTE, webCerrada, "clinica", TIPO_LABORATORIO_GENERAL, fecha, mapOf("albumina" to "9")))
        }
        rechazada("guardarLaboratorio nuevo en una consulta de la web") {
            r.guardarLaboratorio(Laboratorio(newId(), PACIENTE, webCerrada, "clinica", TIPO_LABORATORIO_GENERAL, fecha, mapOf("albumina" to "3")))
        }
    }

    @Test
    fun el_laboratorio_de_la_web_tampoco_se_toca_desde_una_consulta_local() = HcClinicaPruebas.conBackend {
        db.sembrarLaWeb()
        db.sembrar(local, remotoId = "", estado = EstadoConsulta.ABIERTA)
        val r = repo()
        rechazada("eliminarLaboratorio de la carga de la web, con una consulta local abierta") { r.eliminarLaboratorio(cargaWeb) }
        rechazada("editar la carga de la web con otra consulta de origen") {
            r.guardarLaboratorio(r.observarLaboratorios(PACIENTE, "clinica", TIPO_LABORATORIO_GENERAL).first().single().copy(consultaId = local))
        }
    }

    @Test
    fun los_registros_de_la_web_no_se_editan_ni_se_quitan_ni_se_crean_en_una_consulta_de_la_web() = HcClinicaPruebas.conBackend {
        db.sembrarLaWeb()
        val r = repo()
        rechazada("eliminarRegistro") { r.eliminarRegistro(registroWeb) }
        rechazada("guardarRegistro de la fila de la web") { r.guardarRegistro(r.registro(registroWeb)!!.copy(campos = mapOf("detalle" to "Cambiado"))) }
        rechazada("guardarRegistro nuevo en una consulta de la web") {
            r.guardarRegistro(RegistroClinico(newId(), PACIENTE, webAbierta, "clinica", "interconsulta", fecha, mapOf("solicito" to "x")))
        }
    }

    @Test
    fun el_antecedente_de_la_web_no_se_edita_desde_ninguna_consulta() = HcClinicaPruebas.conBackend {
        db.sembrarLaWeb()
        db.sembrar(local, remotoId = "", estado = EstadoConsulta.ABIERTA)
        val r = repo()
        rechazada("destildar la casilla de la web desde una consulta local") {
            r.guardarAntecedente(PACIENTE, "clinica", "familiares", "hta", false, "", local)
        }
        rechazada("cambiarle el detalle") { r.guardarAntecedente(PACIENTE, "clinica", "familiares", "hta", true, "Madre", null) }
        rechazada("un antecedente nuevo atribuido a una consulta de la web") {
            r.guardarAntecedente(PACIENTE, "clinica", "familiares", "dm", true, "x", webAbierta)
        }
    }

    @Test
    fun el_pendiente_de_la_web_no_se_resuelve_ni_se_reemplaza_y_no_se_crea_uno_en_una_consulta_de_la_web() = HcClinicaPruebas.conBackend {
        db.sembrarLaWeb()
        val r = repo()
        rechazada("resolver el pendiente de la web") { r.guardarPendiente(r.observarPendientes(PACIENTE, MEDICO).first().single().copy(resuelto = true)) }
        rechazada("pendiente nuevo en una consulta de la web") { r.guardarPendiente(Pendiente(newId(), PACIENTE, MEDICO, "Traer estudios", webAbierta)) }
    }

    // ---- Lo del dispositivo no cambia ----

    @Test
    fun las_acciones_sobre_lo_del_dispositivo_siguen_como_siempre() = HcClinicaPruebas.conBackend {
        db.sembrar(local, remotoId = "", estado = EstadoConsulta.ABIERTA, dirty = false)
        val r = repo()

        assertTrue(r.guardarSeccion(local, "habitos", mapOf("texto" to "Mío")))
        assertTrue(r.guardarValor(local, "diuresis", "texto", "Mía"))
        assertTrue(r.guardarExamenFisico(ExamenFisico(local, peso = "80")))
        assertTrue(r.guardarAntecedente(PACIENTE, "clinica", "familiares", "dm", true, "Madre", local))
        val registro = RegistroClinico(newId(), PACIENTE, local, "clinica", "interconsulta", fecha, mapOf("solicito" to "x"))
        assertTrue(r.guardarRegistro(registro))
        val lab = Laboratorio(newId(), PACIENTE, local, "clinica", TIPO_LABORATORIO_GENERAL, fecha, mapOf("albumina" to "4"))
        assertTrue(r.guardarLaboratorio(lab))
        val pendiente = Pendiente(newId(), PACIENTE, MEDICO, "Traer estudios", local)
        assertTrue(r.guardarPendiente(pendiente))
        assertTrue(r.guardarArchivo(Archivo(newId(), PACIENTE, local, null, "documentos", "mio.pdf", "application/pdf")))
        assertTrue(r.guardarVacuna(VacunaAplicada(newId(), PACIENTE, "antigripal", consultaId = local)))
        assertTrue(r.asignarDiagnostico(local, "d1", true))
        assertTrue(r.guardarConsulta(r.consulta(local)!!.copy(edadMostrar = "33 años")))

        assertTrue(r.eliminarLaboratorio(lab.id))
        assertTrue(r.eliminarRegistro(registro.id))
        assertEquals("0", HcClinicaPruebas.textos(driver, "SELECT activo FROM laboratorio WHERE id = '${lab.id}'").single(), "la carga local queda dada de baja")
        assertEquals("0", HcClinicaPruebas.textos(driver, "SELECT activo FROM registro_clinico WHERE id = '${registro.id}'").single())

        assertTrue(r.cerrarConsulta(local))
        assertEquals(EstadoConsulta.CERRADA, r.consulta(local)!!.estado)
        assertTrue(r.reabrirConsulta(local))
        assertEquals(EstadoConsulta.ABIERTA, r.consulta(local)!!.estado)
        assertTrue(r.anularConsulta(local))
        assertEquals(EstadoConsulta.ANULADA, r.consulta(local)!!.estado)

        assertNotEquals(0, sucias(), "lo del dispositivo sí queda pendiente de envío, como siempre")
    }

    @Test
    fun una_consulta_de_pediatria_con_remoto_id_se_cierra_y_se_anula_como_siempre() = HcClinicaPruebas.conBase { db, driver ->
        val q = db.historiaClinicaQueries
        val pediatria = Consulta("hcp-c88", PACIENTE, MEDICO, "pediatria", "control", fecha, EstadoConsulta.ABIERTA, remotoId = "88")
        q.upsertConsulta(pediatria.toRow(5, dirty = false))
        val r = HcRepository(db, mapOf("pediatria" to PediatriaFalsa))

        assertTrue(r.guardarSeccion("hcp-c88", "motivo_consulta", mapOf("texto" to "Control")))
        assertTrue(r.cerrarConsulta("hcp-c88"))
        assertEquals(EstadoConsulta.CERRADA, r.consulta("hcp-c88")!!.estado)
        assertTrue(r.reabrirConsulta("hcp-c88"))
        assertTrue(r.anularConsulta("hcp-c88"))
        assertEquals(EstadoConsulta.ANULADA, r.consulta("hcp-c88")!!.estado)
        assertEquals("1", HcClinicaPruebas.textos(driver, "SELECT COUNT(*) FROM consulta WHERE id = 'hcp-c88' AND dirty <> 0").single())
    }

    @Test
    fun cerrar_una_consulta_que_no_existe_no_escribe_nada() = HcClinicaPruebas.conBackend {
        val r = repo()
        rechazada("cerrar una inexistente") { r.cerrarConsulta("no-existe") }
        rechazada("reabrir una inexistente") { r.reabrirConsulta("no-existe") }
        rechazada("anular una inexistente") { r.anularConsulta("no-existe") }
        rechazada("eliminar un laboratorio inexistente") { r.eliminarLaboratorio("no-existe") }
    }

    /** Pediatría de mentira: un backend que escribe (`soloLectura = false`); acá no manda nada. */
    private object PediatriaFalsa : HcBackend {
        override val especialidad = "pediatria"
        override suspend fun traerConsultas(pacienteId: Id, medicoId: Id): List<Consulta> = emptyList()
        override suspend fun traerConsulta(consultaLocalId: Id, remotoId: String) = Unit
        override suspend fun asegurarConsulta(consulta: Consulta): String? = null
        override suspend fun enviarSecciones(remotoId: String, secciones: Map<String, Map<String, String>>) = Unit
        override suspend fun enviarExamen(remotoId: String, campos: Map<String, String>) = Unit
        override suspend fun enviarRegistros(remotoId: String, registros: List<RegistroClinico>): Map<Id, String> = emptyMap()
        override suspend fun enviarEstado(consulta: Consulta, remotoId: String) = Unit
        override suspend fun subirArchivo(remotoId: String, archivo: Archivo, bytes: ByteArray): String? = null
        override suspend fun borrarArchivo(remotoId: String, archivo: Archivo) = Unit
        override suspend fun bajarArchivo(remotoId: String, archivo: Archivo): ByteArray? = null
    }
}
