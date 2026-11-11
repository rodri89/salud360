package com.salud360.core.data.sync

import com.salud360.core.data.hcgeneral.EstadoGuardado
import com.salud360.core.data.hcgeneral.LoEnviado
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
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.ExamenFisico
import com.salud360.core.model.hc.SeccionValor

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
