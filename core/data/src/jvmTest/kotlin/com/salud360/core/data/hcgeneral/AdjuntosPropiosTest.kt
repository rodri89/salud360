package com.salud360.core.data.hcgeneral

import com.salud360.core.data.lista
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.repos.HcClinicaPruebas
import com.salud360.core.data.repos.HcClinicaPruebas.PACIENTE
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.Id
import com.salud360.core.model.hc.Archivo
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Spec 005, T103 (RF-46): `archivosDeConsultaPropia` es la lista que va a necesitar el envío de E4 —los adjuntos **tocados de
 * esa** consulta, altas y bajas— y no la que usa el envío de la especialidad (`archivosPendientesDeConsulta`,
 * `HistoriaClinica.sq`), que además toma los sucios **sin consulta** (`consulta_id` NULL) del mismo paciente. En la HC general
 * un documento sin consulta no viaja: la web lo listaría como de otra. Solo se agregó una consulta al `.sq`; el esquema de
 * `archivo` es el mismo. Base SQLite temporal, datos inventados.
 */
class AdjuntosPropiosTest {

    private suspend fun Salud360Db.sembrarArchivo(
        id: Id,
        consultaId: Id?,
        seccion: String = "documentos",
        activo: Boolean = true,
        dirty: Boolean = true,
        deleted: Boolean = false,
        actualizadoEn: Long = 100,
    ) = historiaClinicaQueries.upsertArchivo(
        Archivo(id, PACIENTE, consultaId, null, seccion, "$id.pdf", "application/pdf", activo = activo)
            .toRow(actualizadoEn, deleted = deleted, dirty = dirty),
    )

    private suspend fun Salud360Db.deLaConsulta(consultaId: Id): List<Id> =
        historiaClinicaQueries.archivosDeConsultaPropia(consultaId).lista { it.id }

    /** La de siempre (`HcApiSync`, pediatría): incluye los sucios sin consulta del paciente. */
    private suspend fun Salud360Db.pendientesDeLaEspecialidad(consultaId: Id): List<Id> =
        historiaClinicaQueries.archivosPendientesDeConsulta(consultaId, PACIENTE).lista { it.id }

    @Test
    fun toma_solo_los_adjuntos_de_esa_consulta_y_deja_afuera_los_sin_consulta_del_paciente() = HcClinicaPruebas.conBase { db, _ ->
        db.sembrarArchivo("alta", "u1", actualizadoEn = 100)
        db.sembrarArchivo("baja", "u1", activo = false, actualizadoEn = 110)
        db.sembrarArchivo("por-paciente", consultaId = null, seccion = "familigrama", actualizadoEn = 120)
        db.sembrarArchivo("otra-propia", "u2", actualizadoEn = 130)
        db.sembrarArchivo("limpio", "u1", dirty = false, actualizadoEn = 140)
        db.sembrarArchivo("borrado", "u1", deleted = true, actualizadoEn = 150)

        assertEquals(listOf("alta", "baja"), db.deLaConsulta("u1"), "las altas y las bajas de esa consulta, y solo las tocadas")
        assertEquals(listOf("otra-propia"), db.deLaConsulta("u2"), "lo de otra consulta no se cuela")
        assertEquals(emptyList(), db.deLaConsulta("u9"), "una consulta sin adjuntos da vacío")

        // Lo que toma el envío de la especialidad sí incluye el adjunto por paciente del mismo paciente: esa es la diferencia.
        assertEquals(listOf("alta", "baja", "por-paciente"), db.pendientesDeLaEspecialidad("u1"))
    }

    @Test
    fun sin_adjuntos_de_la_consulta_los_de_paciente_no_aparecen_aunque_esten_sucios() =
        HcClinicaPruebas.conBase { db, _ ->
            db.sembrarArchivo("por-paciente", consultaId = null, seccion = "documentos", actualizadoEn = 100)

            assertEquals(emptyList(), db.deLaConsulta("u1"), "sin consulta no es de ninguna consulta")
            assertEquals(listOf("por-paciente"), db.pendientesDeLaEspecialidad("u1"), "la de la especialidad lo lleva (límite conocido, sin cambios)")
        }

    @Test
    fun el_esquema_de_archivo_no_cambio_y_remoto_id_sigue_al_final() = HcClinicaPruebas.conBase { _, driver ->
        assertEquals(
            listOf(
                "id", "paciente_id", "consulta_id", "registro_id", "seccion", "nombre", "mime", "tamanio_bytes", "duracion_ms",
                "ruta_local", "url_remota", "subido", "activo", "updated_at", "deleted", "dirty", "remoto_id",
            ),
            HcClinicaPruebas.textos(driver, "SELECT name FROM pragma_table_info('archivo') ORDER BY cid"),
            "agregar la consulta al .sq no toca el esquema",
        )
        assertEquals(11L, Salud360Db.Schema.version, "la versión del esquema no cambia (la 11 de T80)")
    }
}
