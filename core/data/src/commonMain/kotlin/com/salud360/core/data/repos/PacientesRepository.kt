package com.salud360.core.data.repos

import com.salud360.core.data.ahoraMillis
import com.salud360.core.data.flujoLista
import com.salud360.core.data.flujoUno
import com.salud360.core.data.lista
import com.salud360.core.data.mappers.toModel
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.uno
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.Id
import com.salud360.core.model.pacientes.MedicoPaciente
import com.salud360.core.model.pacientes.Paciente
import com.salud360.core.model.pacientes.PacienteExtra
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Cartera de pacientes. Un paciente es único por DNI en todo el sistema y se vincula a uno o
 * más médicos (reemplaza a las tablas `medico_pacientes` de cada proyecto).
 */
class PacientesRepository(private val db: Salud360Db) {
    private val q get() = db.pacientesQueries

    fun observar(id: Id): Flow<Paciente?> = q.pacientePorId(id).flujoUno { it.toModel() }
    /** Fichas de varios pacientes (por ejemplo, los de los turnos del día) para mostrar afiliado, plan, etc. */
    fun observarPorIds(ids: List<Id>): Flow<List<Paciente>> =
        if (ids.isEmpty()) kotlinx.coroutines.flow.flowOf(emptyList()) else q.pacientesPorIds(ids).flujoLista { it.toModel() }
    suspend fun porId(id: Id): Paciente? = q.pacientePorId(id).uno { it.toModel() }
    suspend fun porDni(dni: String): Paciente? = q.pacientePorDni(dni.trim()).uno { it.toModel() }

    fun observarDeMedico(medicoId: Id): Flow<List<Paciente>> = q.pacientesDeMedico(medicoId).flujoLista { it.toModel() }
    fun observarTodos(): Flow<List<Paciente>> = q.pacientes().flujoLista { it.toModel() }

    /**
     * Página del listado (los primeros `limite`, ordenados por apellido) con filtro por DNI (prefijo) o apellido/nombre
     * (contiene) resuelto en SQL. `medicoId` null = todos (administrador). Reactivo: se vuelve a emitir al cambiar la base.
     */
    fun observarPagina(medicoId: Id?, texto: String, limite: Long): Flow<List<Paciente>> {
        val t = texto.trim()
        return if (medicoId != null) q.pacientesDeMedicoPagina(medicoId, t, limite).flujoLista { it.toModel() }
        else q.pacientesPagina(t, limite).flujoLista { it.toModel() }
    }

    /** Total de pacientes que cumplen el mismo filtro que [observarPagina]. */
    fun observarTotal(medicoId: Id?, texto: String): Flow<Long> {
        val t = texto.trim()
        val flujo = if (medicoId != null) q.contarPacientesDeMedico(medicoId, t).flujoUno { it } else q.contarPacientes(t).flujoUno { it }
        return flujo.map { it ?: 0L }
    }
    fun observarPendientesActivacion(): Flow<List<Paciente>> = q.pacientesPendientesActivacion().flujoLista { it.toModel() }

    /** Búsqueda por DNI (prefijo) o por apellido/nombre (contiene), como `pacienteConsultar` de las apps. */
    suspend fun buscar(texto: String, medicoId: Id? = null, limite: Long = 50): List<Paciente> {
        val t = texto.trim()
        if (t.isEmpty()) return if (medicoId != null) q.pacientesDeMedico(medicoId).lista { it.toModel() }.take(limite.toInt()) else emptyList()
        return if (medicoId != null) q.buscarPacientesDeMedico(medicoId, t, limite).lista { it.toModel() }
        else q.buscarPacientes(t, limite).lista { it.toModel() }
    }

    /**
     * Alta o actualización. Si ya existe otro paciente con el mismo DNI se reutiliza (comportamiento de
     * `altaPacienteMedicoSecretaria`): se lo vincula al médico indicado y se devuelve **su** ficha, sin pisarla con
     * lo que se escribió. Quien llama distingue ese caso porque el id devuelto es distinto del que se pasó.
     */
    suspend fun guardar(paciente: Paciente, vincularA: Id? = null): Paciente {
        val existente = if (paciente.dni.isNotBlank()) porDni(paciente.dni) else null
        if (existente != null && existente.id != paciente.id) {
            if (vincularA != null) vincular(vincularA, existente.id)
            return existente
        }
        val normalizado = paciente.copy(
            nombre = paciente.nombre.trim().uppercase(),
            apellido = paciente.apellido.trim().uppercase(),
            dni = paciente.dni.trim(),
            nota = paciente.nota.trim(),
        )
        db.transaction {
            q.upsertPaciente(normalizado.toRow(ahoraMillis()))
            if (vincularA != null) {
                q.upsertMedicoPaciente(MedicoPaciente(vincularA, normalizado.id).toRow(ahoraMillis()))
            }
        }
        return normalizado
    }

    /**
     * Reemplaza la ficha local `idLocal` por la que turnos tiene para ese DNI ([remoto], id `tobb-p<N>`), sin dejar dos
     * filas con el mismo DNI. En una sola transacción:
     * - la ficha `remoto.id` queda con los datos de turnos, sin pisar con vacíos lo que ya había (ver [fusionarFicha]),
     *   y sin `dirty`: su dueño es turnos;
     * - todo lo que apuntaba a `idLocal` pasa a `remoto.id`: vínculos con médicos (ver [fusionarVinculos]), `paciente_extra`
     *   (ante una misma clave gana el dato que ya tenía el id de turnos), turnos, recetas, consultas, antecedentes,
     *   registros clínicos, laboratorios, archivos, pendientes y vacunas;
     * - la ficha local sobrante se marca borrada.
     *
     * Es idempotente y sirve aunque la ficha de turnos ya estuviera en el dispositivo (la sincronizó otra pantalla antes)
     * o aunque la local ya no exista. Devuelve la ficha que quedó.
     */
    suspend fun reemplazarPorRemoto(idLocal: Id, remoto: Paciente): Paciente {
        val ahora = ahoraMillis()
        var ficha = remoto
        db.transaction {
            val mismo = idLocal == remoto.id
            val local = if (mismo) null else q.pacientePorId(idLocal).uno { it.toModel() }
            val previa = q.pacientePorId(remoto.id).uno { it.toModel() } ?: local
            ficha = fusionarFicha(previa, remoto)
            q.upsertPaciente(ficha.toRow(ahora, dirty = false))
            if (mismo) return@transaction

            val deLaLocal = q.vinculosDePaciente(idLocal).lista { it.toModel() }
            if (deLaLocal.isNotEmpty()) {
                val deTurnos = q.vinculosDePaciente(remoto.id).lista { it.toModel() }
                fusionarVinculos(deLaLocal, deTurnos, remoto.id).forEach { q.upsertMedicoPaciente(it.toRow(ahora, dirty = false)) }
            }
            q.borrarVinculosDePaciente(idLocal)
            q.repuntarExtrasDePaciente(remoto.id, idLocal)
            q.borrarExtrasDePaciente(idLocal)
            q.repuntarTurnosDePaciente(remoto.id, idLocal)
            q.repuntarRecetasDePaciente(remoto.id, idLocal)
            q.repuntarConsultasDePaciente(remoto.id, idLocal)
            q.repuntarAntecedentesDePaciente(remoto.id, idLocal)
            q.repuntarRegistrosDePaciente(remoto.id, idLocal)
            q.repuntarLaboratoriosDePaciente(remoto.id, idLocal)
            q.repuntarArchivosDePaciente(remoto.id, idLocal)
            q.repuntarPendientesDePaciente(remoto.id, idLocal)
            q.repuntarVacunasDePaciente(remoto.id, idLocal)
            local?.let { q.upsertPaciente(it.toRow(ahora, deleted = true)) }
        }
        return ficha
    }

    suspend fun vincular(medicoId: Id, pacienteId: Id) {
        val actual = q.vinculo(medicoId, pacienteId).uno { it.toModel() }
        if (actual == null || !actual.activo) q.upsertMedicoPaciente(MedicoPaciente(medicoId, pacienteId).toRow(ahoraMillis()))
    }

    suspend fun bloquear(medicoId: Id, pacienteId: Id, bloqueado: Boolean) {
        val actual = q.vinculo(medicoId, pacienteId).uno { it.toModel() } ?: MedicoPaciente(medicoId, pacienteId)
        q.upsertMedicoPaciente(actual.copy(bloqueado = bloqueado).toRow(ahoraMillis()))
    }

    suspend fun desvincular(medicoId: Id, pacienteId: Id) {
        val actual = q.vinculo(medicoId, pacienteId).uno { it.toModel() } ?: return
        q.upsertMedicoPaciente(actual.copy(activo = false).toRow(ahoraMillis()))
    }

    suspend fun vinculosDe(pacienteId: Id): List<MedicoPaciente> = q.vinculosDePaciente(pacienteId).lista { it.toModel() }

    suspend fun activar(pacienteId: Id) {
        val p = porId(pacienteId) ?: return
        q.upsertPaciente(p.copy(activo = true).toRow(ahoraMillis()))
    }

    suspend fun eliminar(pacienteId: Id) {
        val p = porId(pacienteId) ?: return
        q.upsertPaciente(p.toRow(ahoraMillis(), deleted = true))
    }

    // ---- datos extra por especialidad ----

    fun observarExtras(pacienteId: Id, especialidad: String): Flow<Map<String, String>> =
        q.extrasDePaciente(pacienteId, especialidad).flujoLista { it.toModel() }.map { lista -> lista.associate { it.clave to it.valor } }

    suspend fun guardarExtra(pacienteId: Id, especialidad: String, clave: String, valor: String) {
        q.upsertPacienteExtra(PacienteExtra(pacienteId, especialidad, clave, valor).toRow(ahoraMillis()))
    }
}
