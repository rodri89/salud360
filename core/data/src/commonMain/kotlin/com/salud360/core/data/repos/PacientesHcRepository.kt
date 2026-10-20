package com.salud360.core.data.repos

import co.touchlab.kermit.Logger
import com.salud360.core.data.ahoraMillis
import com.salud360.core.data.hcgeneral.MotivoHc
import com.salud360.core.data.hcgeneral.historiasConCartera
import com.salud360.core.data.hcgeneral.idLocalDeFicha
import com.salud360.core.data.hcgeneral.motivoHc
import com.salud360.core.data.lista
import com.salud360.core.data.mappers.toModel
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.network.TobbException
import com.salud360.core.data.network.hc.HcPacienteFicha
import com.salud360.core.data.network.hc.PacientesHcApis
import com.salud360.core.data.uno
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.Id
import com.salud360.core.model.pacientes.MedicoPaciente
import com.salud360.core.model.pacientes.Paciente
import com.salud360.core.model.pacientes.PacienteExtra
import com.salud360.core.model.pacientes.Sexo
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.LocalDate

/** Cuántos pacientes llegaron de las carteras y por qué no llegaron las que fallaron (una por HC). */
data class ResultadoCarteras(val traidos: Int, val motivos: List<MotivoHc>)

/**
 * Pacientes de cada historia clínica con API (spec 002, parte 1): la cartera del médico y el vínculo de cada
 * paciente con su ficha. Atiende pediatría y la HC general por igual; lo que cambia es la [PacientesHcApis].
 *
 * El dueño de las fichas es la web de cada HC: lo que llega se guarda con `dirty = false` (también el vínculo, que
 * vive en `paciente_extra` con la especialidad y la clave [EXTRA_HC_PACIENTE_ID]).
 *
 * @param nombresHc código de HC → nombre visible, para nombrarla en los motivos (D3).
 */
class PacientesHcRepository(
    private val db: Salud360Db,
    private val apis: PacientesHcApis,
    private val nombresHc: Map<String, String>,
) {
    private val log = Logger.withTag("PacientesHc")
    private val pq get() = db.pacientesQueries

    /**
     * Trae la cartera del médico en [especialidad], de a páginas, y la deja vinculada en el dispositivo. Devuelve
     * cuántos llegaron; si falla lanza [TobbException] (lo de las páginas anteriores queda guardado).
     *
     * El paciente que ya está no se pisa (RF-13): acá llega la ficha de la HC, y la de turnos suele tener más cosas
     * (la obra social con su plan, el teléfono actualizado). Lo que sí se asegura siempre es el vínculo con el
     * médico, que es lo que hace que aparezca en su lista, y el id de su ficha, así abrir la historia clínica no
     * tiene que volver a resolverlo por documento.
     */
    suspend fun traerCartera(medicoId: Id, especialidad: String): Int {
        val api = apis.porEspecialidad[especialidad] ?: return 0
        var desde = 0
        var total = 0
        while (true) {
            val pagina = api.pagina(desde, PAGINA_PACIENTES)
            if (pagina.fichas.isEmpty()) break
            val ahora = ahoraMillis()
            // En una transacción por página: de a una fila, quinientos pacientes tardan casi un minuto, y esto
            // corre al abrir la pantalla.
            db.transaction {
                for (ficha in pagina.fichas) guardarPacienteDeCartera(ficha, especialidad, medicoId, ahora)
            }
            total += pagina.fichas.size
            if (!pagina.hayMas) break
            desde += pagina.fichas.size
        }
        log.i { "llegaron $total pacientes de la cartera de $medicoId en $especialidad" }
        return total
    }

    /**
     * Trae la cartera de cada historia clínica habilitada del médico que tiene API (RF-8), una por una. La que falla
     * queda como [MotivoHc] y no frena a las demás (RF-9); la lista muestra lo que ya había.
     */
    suspend fun sincronizarCarteras(medicoId: Id): ResultadoCarteras {
        val habilitadas = db.authQueries.medicoPorId(medicoId).uno { it.toModel() }?.especialidadesHc.orEmpty()
        var traidos = 0
        val motivos = mutableListOf<MotivoHc>()
        for (especialidad in historiasConCartera(habilitadas, apis.porEspecialidad.keys)) {
            try {
                traidos += traerCartera(medicoId, especialidad)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.w(e) { "no llegó la cartera de $medicoId en $especialidad" }
                val error = e as? TobbException
                    ?: TobbException(0, "respuesta", "No se pudo leer la cartera: ${e.message ?: "error"}")
                motivos += motivoHc(nombresHc[especialidad] ?: especialidad, error)
            }
        }
        return ResultadoCarteras(traidos, motivos)
    }

    /** Id de la ficha del paciente en esa historia clínica, si ya se conoce (RF-35). */
    suspend fun fichaConocida(pacienteId: Id, especialidad: String): Long? =
        pq.extrasDePaciente(pacienteId, especialidad).lista { it.toModel() }
            .firstOrNull { it.clave == EXTRA_HC_PACIENTE_ID }?.valor?.toLongOrNull()

    private suspend fun guardarPacienteDeCartera(ficha: HcPacienteFicha, especialidad: String, medicoId: Id, ahora: Long) {
        val id = idLocalDeFicha(especialidad, ficha)
        if (pq.pacientePorId(id).uno { it } == null) {
            pq.upsertPaciente(
                Paciente(
                    id = id,
                    dni = ficha.dni.trim(),
                    nombre = ficha.nombre.trim(),
                    apellido = ficha.apellido.trim(),
                    sexo = ficha.sexo?.trim()?.uppercase()?.let { s -> runCatching { Sexo.valueOf(s) }.getOrNull() },
                    fechaNacimiento = ficha.fechaNacimiento?.let { f -> runCatching { LocalDate.parse(f) }.getOrNull() },
                    telefono = ficha.telefono, mail = ficha.mail,
                    domicilio = ficha.domicilio, localidad = ficha.localidad,
                    obraSocial = ficha.obraSocial, numeroAfiliado = ficha.numeroAfiliado,
                    obraSocialPlan = ficha.obraSocialPlan,
                    nombrePadre = ficha.nombrePadre, nombreMadre = ficha.nombreMadre,
                    cantidadHermanos = ficha.cantidadHermanos.takeIf { h -> h > 0 },
                    nombreFamiliar = ficha.nombreFamiliar, telefonoFamiliar = ficha.telefonoFamiliar,
                ).toRow(ahora, dirty = false),
            )
        }
        if (pq.vinculo(medicoId, id).uno { it } == null) {
            pq.upsertMedicoPaciente(MedicoPaciente(medicoId, id).toRow(ahora, dirty = false))
        }
        // El vínculo guardado no se revalida; se reescribe solo si falta o es el mismo, así el que quedó `dirty`
        // de antes (pediatría lo guardaba así) se limpia.
        val conocida = fichaConocida(id, especialidad)
        if (conocida == null || conocida == ficha.id) {
            pq.upsertPacienteExtra(
                PacienteExtra(id, especialidad, EXTRA_HC_PACIENTE_ID, ficha.id.toString()).toRow(ahora, dirty = false),
            )
        }
    }

    companion object {
        /** Clave de `paciente_extra` donde se guarda el id del paciente en la historia clínica. */
        const val EXTRA_HC_PACIENTE_ID = "hc_paciente_id"

        /** Cuántos pacientes por pedido al traer la cartera (las APIs aceptan hasta 1000). */
        const val PAGINA_PACIENTES = 300
    }
}
