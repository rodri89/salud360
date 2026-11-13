package com.salud360.core.data.sync

import com.salud360.core.data.hcgeneral.EstadoGuardado
import com.salud360.core.data.hcgeneral.LoEnviado
import com.salud360.core.data.hcgeneral.SECCION_DOCUMENTOS
import com.salud360.core.data.hcgeneral.TIPO_LABORATORIO_GENERAL
import com.salud360.core.data.lista
import com.salud360.core.data.mappers.toModel
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.repos.EstadosDeEnvio
import com.salud360.core.data.repos.HcClinicaPruebas.PACIENTE
import com.salud360.core.data.repos.LoEnviadoGuardado
import com.salud360.core.data.uno
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.Id
import com.salud360.core.model.hc.Antecedente
import com.salud360.core.model.hc.Archivo
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.ExamenFisico
import com.salud360.core.model.hc.Laboratorio
import com.salud360.core.model.hc.RegistroClinico
import com.salud360.core.model.hc.SeccionValor
import kotlinx.datetime.LocalDate

/** Lo que los tests del envío miran de la base del dispositivo. */

internal suspend fun Salud360Db.consultaLocal(id: Id): Consulta? = historiaClinicaQueries.consultaPorId(id).uno { it.toModel() }

/** La fila de la consulta sigue con pendiente de envío (`dirty = 1`). */
internal suspend fun Salud360Db.consultaSucia(id: Id): Boolean = id in historiaClinicaQueries.consultasDirty().lista { it.id }

/** `<sección>/<campo>` de los textos de [consultaId] que siguen sin enviar. */
internal suspend fun Salud360Db.textosSucios(consultaId: Id): Set<String> =
    historiaClinicaQueries.seccionValoresDirty().lista { it }.filter { it.consulta_id == consultaId }.map { "${it.seccion}/${it.campo}" }.toSet()

internal suspend fun Salud360Db.examenSucio(consultaId: Id): Boolean =
    historiaClinicaQueries.examenesFisicosDirty().lista { it.consulta_id }.contains(consultaId)

internal suspend fun Salud360Db.antecedenteSucio(id: Id): Boolean = historiaClinicaQueries.antecedentesDirty().lista { it.id }.contains(id)

/** Todo lo `dirty` de las tablas que toca el envío de la HC general, con todas sus columnas. */
internal suspend fun Salud360Db.filasSucias(): List<List<Any>> = listOf(
    historiaClinicaQueries.consultasDirty().lista { it },
    historiaClinicaQueries.seccionValoresDirty().lista { it },
    historiaClinicaQueries.examenesFisicosDirty().lista { it },
    historiaClinicaQueries.antecedentesDirty().lista { it },
    historiaClinicaQueries.registrosDirty().lista { it },
    historiaClinicaQueries.archivosDirty().lista { it },
)

internal suspend fun Salud360Db.estadosDe(consultaId: Id, paciente: Id = PACIENTE): Map<String, EstadoGuardado> =
    EstadosDeEnvio(this).deLaConsulta(paciente, consultaId)

internal suspend fun Salud360Db.loEnviadoDe(consultaId: Id, paciente: Id = PACIENTE): LoEnviado = LoEnviadoGuardado(this).deLaConsulta(paciente, consultaId)

/** El mismo texto de la consulta, escrito de nuevo (como el autoguardado cuando el médico lo cambia): fila `dirty` con otro `updated_at`. */
internal suspend fun Salud360Db.escribirTexto(consultaId: Id, seccion: String, texto: String, actualizadaEn: Long) =
    historiaClinicaQueries.upsertSeccionValor(SeccionValor(consultaId, seccion, "texto", texto).toRow(actualizadaEn))

internal suspend fun Salud360Db.sembrarExamen(consultaId: Id, peso: String = "", talla: String = "", actualizadaEn: Long = 130) =
    historiaClinicaQueries.upsertExamenFisico(ExamenFisico(consultaId, peso = peso, talla = talla).toRow(actualizadaEn))

internal suspend fun Salud360Db.sembrarAntecedente(
    id: Id,
    consultaId: Id?,
    clave: String = "hta",
    activo: Boolean = true,
    categoria: String = "familiares",
    actualizadaEn: Long = 140,
    flag: Boolean = true,
    detalle: String = "padre",
) = historiaClinicaQueries.upsertAntecedente(Antecedente(id, PACIENTE, "clinica", categoria, clave, flag, detalle, consultaId, activo = activo).toRow(actualizadaEn))

/** Un valor de formulario (alimentación, serologías, Child-Pugh) sin enviar en [consultaId]: fila `dirty` de `seccion_valor`. */
internal suspend fun Salud360Db.sembrarFormulario(consultaId: Id, seccion: String, campo: String, valor: String, actualizadaEn: Long = 125) =
    historiaClinicaQueries.upsertSeccionValor(SeccionValor(consultaId, seccion, campo, valor).toRow(actualizadaEn))

/** `<sección>/<campo>` de los valores sin enviar que **no** son un texto (los formularios). */
internal suspend fun Salud360Db.formulariosSucios(consultaId: Id): Set<String> =
    historiaClinicaQueries.seccionValoresDirty().lista { it }.filter { it.consulta_id == consultaId && it.campo != "texto" }.map { "${it.seccion}/${it.campo}" }.toSet()

/** Un estudio sin enviar de [consultaId] (T62-b/T63-b): la fila de `registro_clinico` con su fecha y su `detalle`. */
internal suspend fun Salud360Db.sembrarRegistro(
    id: Id,
    consultaId: Id? = "u1",
    tipo: String = "eco_abdominal",
    detalle: String = "hígado graso",
    fecha: LocalDate? = FECHA_DE_PRUEBA,
    remotoId: String = "",
    activo: Boolean = true,
    actualizadaEn: Long = 140,
) = historiaClinicaQueries.upsertRegistro(
    RegistroClinico(id, PACIENTE, consultaId, "clinica", tipo, fecha, mapOf("detalle" to detalle), activo, remotoId).toRow(actualizadaEn),
)

/** El mismo estudio, con otro `detalle` (o `null` para no tocarlo) y otro `updated_at`: como el autoguardado cuando el médico lo cambia. */
internal suspend fun Salud360Db.escribirRegistro(id: Id, detalle: String? = null, fecha: LocalDate? = null, actualizadaEn: Long = 200) {
    val fila = historiaClinicaQueries.registroPorId(id).uno { it.toModel() } ?: return
    historiaClinicaQueries.upsertRegistro(
        fila.copy(fecha = fecha ?: fila.fecha, campos = if (detalle != null) fila.campos + ("detalle" to detalle) else fila.campos).toRow(actualizadaEn),
    )
}

/** La fila del dispositivo, con su `remoto_id` y su `activo`. */
internal suspend fun Salud360Db.registroLocal(id: Id): RegistroClinico? = historiaClinicaQueries.registroPorId(id).uno { it.toModel() }

internal suspend fun Salud360Db.registroSucio(id: Id): Boolean = historiaClinicaQueries.registrosDirty().lista { it.id }.contains(id)

internal suspend fun Salud360Db.registroActualizadoEn(id: Id): Long? = historiaClinicaQueries.registroPorId(id).uno { it.updated_at }

/** Los ids de los estudios sin enviar de [consultaId]. */
internal suspend fun Salud360Db.registrosSucios(consultaId: Id): Set<String> =
    historiaClinicaQueries.registrosDirty().lista { it }.filter { it.consulta_id == consultaId }.map { it.id }.toSet()

/** Una carga sin enviar de [consultaId] (T84-a/T85-a: laboratorio general o peso), con su fecha y sus valores. */
internal suspend fun Salud360Db.sembrarCarga(
    id: Id,
    consultaId: Id? = "u1",
    tipo: String = TIPO_LABORATORIO_GENERAL,
    fecha: LocalDate = FECHA_DE_PRUEBA,
    valores: Map<String, String> = mapOf("hb" to "13"),
    remotoId: String = "",
    activo: Boolean = true,
    actualizadaEn: Long = 150,
) = historiaClinicaQueries.upsertLaboratorio(Laboratorio(id, PACIENTE, consultaId, "clinica", tipo, fecha, valores, activo, remotoId).toRow(actualizadaEn))

/** La misma carga con otros valores y otro `updated_at`: como el autoguardado cuando el médico la edita. */
internal suspend fun Salud360Db.escribirCarga(id: Id, valores: Map<String, String>, actualizadaEn: Long = 200) {
    val fila = historiaClinicaQueries.laboratorioPorId(id).uno { it.toModel() } ?: return
    historiaClinicaQueries.upsertLaboratorio(fila.copy(valores = valores).toRow(actualizadaEn))
}

/** La fila del dispositivo de una carga, con su `remoto_id`, su `activo` y sus valores. */
internal suspend fun Salud360Db.cargaLocal(id: Id): Laboratorio? = historiaClinicaQueries.laboratorioPorId(id).uno { it.toModel() }

internal suspend fun Salud360Db.cargaSucia(id: Id): Boolean = historiaClinicaQueries.laboratoriosDirty().lista { it.id }.contains(id)

internal suspend fun Salud360Db.cargaActualizadaEn(id: Id): Long? = historiaClinicaQueries.laboratorioPorId(id).uno { it.updated_at }

/** Los ids de las cargas sin enviar de [consultaId]. */
internal suspend fun Salud360Db.cargasSucias(consultaId: Id): Set<String> =
    historiaClinicaQueries.laboratoriosDirty().lista { it }.filter { it.consulta_id == consultaId }.map { it.id }.toSet()

/** Un documento sin enviar de [consultaId] (T104): la fila de `archivo` con su `ruta_local` y su `remoto_id`. */
internal suspend fun Salud360Db.sembrarDocumento(
    id: Id,
    consultaId: Id? = "u1",
    nombre: String = "$id.pdf",
    mime: String = "application/pdf",
    rutaLocal: String? = null,
    tamanioBytes: Long = 0,
    remotoId: String = "",
    activo: Boolean = true,
    actualizadaEn: Long = 160,
) = historiaClinicaQueries.upsertArchivo(
    Archivo(
        id = id, pacienteId = PACIENTE, consultaId = consultaId, seccion = SECCION_DOCUMENTOS, nombre = nombre, mime = mime,
        tamanioBytes = tamanioBytes, rutaLocal = rutaLocal, activo = activo, remotoId = remotoId,
    ).toRow(actualizadaEn),
)

/** La fila del dispositivo de un documento, con su `remoto_id`, su `activo` y su `ruta_local`. */
internal suspend fun Salud360Db.archivoLocal(id: Id): Archivo? = historiaClinicaQueries.archivoPorId(id).uno { it.toModel() }

internal suspend fun Salud360Db.archivoSucio(id: Id): Boolean = historiaClinicaQueries.archivosDirty().lista { it.id }.contains(id)

internal suspend fun Salud360Db.archivoActualizadoEn(id: Id): Long? = historiaClinicaQueries.archivoPorId(id).uno { it.updated_at }
