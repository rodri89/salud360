package com.salud360.core.data.repos

import co.touchlab.kermit.Logger
import com.salud360.core.data.ahoraMillis
import com.salud360.core.data.hcgeneral.MotivoHc
import com.salud360.core.data.hcgeneral.ResultadoVinculo
import com.salud360.core.data.hcgeneral.documentoParaHc
import com.salud360.core.data.hcgeneral.historiasConCartera
import com.salud360.core.data.hcgeneral.idLocalDeFicha
import com.salud360.core.data.hcgeneral.mensajeDocumentoInvalido
import com.salud360.core.data.hcgeneral.motivoHc
import com.salud360.core.data.hcgeneral.pedidoResolver
import com.salud360.core.data.hcgeneral.resultadoDe
import com.salud360.core.data.lista
import com.salud360.core.data.mappers.toModel
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.network.TobbException
import com.salud360.core.data.network.hc.HcPacienteFicha
import com.salud360.core.data.network.hc.PacientesHcApis
import com.salud360.core.data.uno
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.Id
import com.salud360.core.model.TobbIds
import com.salud360.core.model.pacientes.MedicoPaciente
import com.salud360.core.model.pacientes.Paciente
import com.salud360.core.model.pacientes.PacienteExtra
import com.salud360.core.model.pacientes.Sexo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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
 * @param esperar la pausa antes de reintentar un `503 ocupado`; los tests la pasan sin dormir.
 */
class PacientesHcRepository(
    private val db: Salud360Db,
    private val apis: PacientesHcApis,
    private val nombresHc: Map<String, String>,
    private val esperar: suspend (Long) -> Unit = { delay(it) },
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

    /**
     * La ficha del paciente en [especialidad] (RF-10). Si ya se conoce la devuelve sin red (RF-35); si no, la pide a
     * la web, que la busca por paciente de turnos o por documento y la crea si no hay ninguna. Con un documento que
     * no sirve no se pide nada (RF-12). Lo llama quien la necesita: abrir una sección que lee de la web o enviar la
     * primera consulta.
     */
    suspend fun vinculo(
        pacienteId: Id,
        especialidad: String,
        alResolver: (suspend (HcPacienteFicha) -> Unit)? = null,
    ): ResultadoVinculo {
        fichaConocida(pacienteId, especialidad)?.let { return ResultadoVinculo.Vinculado(it) }
        return resolver(pacienteId, especialidad, elegida = null, alResolver = alResolver)
    }

    /**
     * Le pregunta a la historia clínica por el paciente solo para leer el **sexo** que informa (`null` si no lo
     * informa, si el documento no sirve o si falla). No guarda ni pisa el vínculo: el que ya está guardado manda
     * (RF-35), aunque la respuesta traiga otro id.
     */
    suspend fun sexoDelServidor(pacienteId: Id, especialidad: String): String? {
        val api = apis.porEspecialidad[especialidad] ?: return null
        val paciente = pq.pacientePorId(pacienteId).uno { it.toModel() } ?: return null
        if (documentoParaHc(paciente.dni) == null) return null
        val pedido = pedidoResolver(paciente, TobbIds.numero(pacienteId) ?: idTobbDeExtras(pacienteId))
        return try {
            api.resolver(pedido, null).paciente.sexo
        } catch (e: TobbException) {
            log.i { "no se pudo leer el sexo de $pacienteId en $especialidad: ${e.codigo}" }
            null
        }
    }

    /** Vincula con la ficha que eligió el médico entre las candidatas de [ResultadoVinculo.ElegirFicha] (RF-11). */
    suspend fun elegirFicha(pacienteId: Id, especialidad: String, fichaId: Long): ResultadoVinculo =
        resolver(pacienteId, especialidad, elegida = fichaId)

    /**
     * `pacientes/resolver` y, si queda vinculado, el vínculo guardado sin `dirty` (el dueño es la web). Lo que no
     * vincula no guarda nada. Solo se traducen los errores de la API ([TobbException]); otro error sigue de largo,
     * como antes en pediatría.
     */
    private suspend fun resolver(
        pacienteId: Id,
        especialidad: String,
        elegida: Long?,
        alResolver: (suspend (HcPacienteFicha) -> Unit)? = null,
    ): ResultadoVinculo {
        val api = apis.porEspecialidad[especialidad]
            ?: return ResultadoVinculo.NoSePudo(TobbException(0, "sin_api", "La historia clínica de $especialidad no tiene API"))
        val paciente = pq.pacientePorId(pacienteId).uno { it.toModel() }
            ?: return ResultadoVinculo.NoSePudo(TobbException(0, PACIENTE_NO_ESTA, "El paciente no está en el dispositivo"))
        if (documentoParaHc(paciente.dni) == null) {
            return ResultadoVinculo.DocumentoInvalido(mensajeDocumentoInvalido(paciente.dni))
        }
        val pedido = pedidoResolver(paciente, TobbIds.numero(pacienteId) ?: idTobbDeExtras(pacienteId))
        val respuesta = try {
            try {
                api.resolver(pedido, elegida)
            } catch (e: TobbException) {
                // Otro pedido con el mismo documento está creando la ficha (D4): una vez más, cuando ya terminó.
                if (e.codigo != "ocupado") throw e
                log.i { "la web está ocupada con el documento de $pacienteId en $especialidad: se reintenta" }
                esperar(ESPERA_OCUPADO)
                api.resolver(pedido, elegida)
            }
        } catch (e: TobbException) {
            log.w(e) { "no quedó vinculado $pacienteId en $especialidad" }
            return resultadoDe(e)
        }
        val fichaId = respuesta.paciente.id
        // Lo que informa la respuesta (hoy el sexo de pediatría), antes de guardar el vínculo: si esto falla, el
        // vínculo ya resuelto no se pierde.
        alResolver?.invoke(respuesta.paciente)
        pq.upsertPacienteExtra(
            PacienteExtra(pacienteId, especialidad, EXTRA_HC_PACIENTE_ID, fichaId.toString()).toRow(ahoraMillis(), dirty = false),
        )
        log.i { "$pacienteId vinculado con la ficha $fichaId de $especialidad (${respuesta.vinculadoPor})" }
        return ResultadoVinculo.Vinculado(fichaId)
    }

    private suspend fun idTobbDeExtras(pacienteId: Id): Long? =
        pq.extrasDePaciente(pacienteId, "turnos").lista { it.toModel() }
            .firstOrNull { it.clave == "tobb_id" }?.valor?.toLongOrNull()

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

        /** Código del [ResultadoVinculo.NoSePudo] cuando el paciente no está en el dispositivo (no se pidió nada). */
        const val PACIENTE_NO_ESTA = "paciente_no_esta"

        /** Cuánto se espera antes de reintentar un `503 ocupado` (D4). */
        const val ESPERA_OCUPADO = 2_000L
    }
}
