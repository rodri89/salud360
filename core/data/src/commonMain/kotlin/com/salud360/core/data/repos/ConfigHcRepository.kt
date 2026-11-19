package com.salud360.core.data.repos

import com.salud360.core.data.ahoraMillis
import com.salud360.core.data.hcgeneral.ConfigGuardada
import com.salud360.core.data.hcgeneral.ConfigRemota
import com.salud360.core.data.hcgeneral.EstadoConfigHc
import com.salud360.core.data.hcgeneral.estadoConfig
import com.salud360.core.data.mappers.json
import com.salud360.core.data.network.TobbException
import com.salud360.core.data.network.hc.HcApiClient
import com.salud360.core.data.network.hc.HcPerfilDto
import com.salud360.core.data.network.hc.toModel
import com.salud360.core.data.uno
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.Id
import com.salud360.core.model.especialidad.CodigosHc
import com.salud360.core.model.especialidad.PerfilHc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.nullable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * `GET config` de hclinica: el perfil del médico que pide, o null si no tiene uno asignado. Si falla lanza
 * [TobbException] con su código (`red` = sin conexión). Es una interfaz para poder probar el repositorio sin red.
 */
fun interface ConfigHcApi {
    suspend fun config(): PerfilHc?
}

/** [ConfigHcApi] contra hclinica: `{perfil}` o `perfil: null`. */
class ConfigHcApiRemota(private val cliente: HcApiClient) : ConfigHcApi {
    override suspend fun config(): PerfilHc? =
        cliente.leer(HcPerfilDto.serializer().nullable, cliente.get("config"), "perfil")?.toModel()
}

/**
 * Configuración de la HC general del médico (RF-23, RF-25, RF-26): se trae de hclinica al abrir pantallas y se guarda
 * en `hc_config` como copia sin vencimiento. El dueño es hclinica: la fila nunca queda `dirty` ni pasa por
 * `HcApiSync`/`SyncEngine`, y nunca se pide desde el autoguardado.
 *
 * @param api null cuando el entorno no tiene URL de hclinica (dev sin `hc.clinica`): se trata como sin conexión.
 */
class ConfigHcRepository(private val db: Salud360Db, private val api: ConfigHcApi?) {
    private val q get() = db.hcConfigQueries
    private val serializer = PerfilHc.serializer().nullable

    /** Pide la configuración y la guarda. Si falla lanza [TobbException] y deja lo guardado como estaba. */
    suspend fun refrescar(medicoId: Id): PerfilHc? {
        val api = api ?: throw TobbException(0, "red", "Este entorno no tiene configurada la historia clínica general")
        val perfil = try {
            api.config()
        } catch (e: CancellationException) {
            throw e
        } catch (e: TobbException) {
            throw e
        } catch (e: Throwable) {
            throw TobbException(0, "respuesta", "No se pudo leer la configuración de la historia clínica: ${e.message ?: "error"}")
        }
        q.guardarConfig(medicoId, CodigosHc.GENERAL, json.encodeToString(serializer, perfil), ahoraMillis())
        return perfil
    }

    /** Lo guardado en el dispositivo, o null si nunca se recibió (o la copia no se puede leer). */
    suspend fun guardada(medicoId: Id): ConfigGuardada? {
        val texto = q.configGuardada(medicoId, CodigosHc.GENERAL).uno { it.config_json } ?: return null
        return runCatching { ConfigGuardada(json.decodeFromString(serializer, texto)) }.getOrNull()
    }

    /**
     * Con qué configuración se arma la HC: con [conRed] la pide (y la guarda); si no hay red o falla, vale la guardada.
     * Sin config usable lleva el código y el mensaje del fallo para el aviso.
     */
    suspend fun estado(medicoId: Id, conRed: Boolean): EstadoConfigHc {
        val remota = if (!conRed) {
            ConfigRemota.Fallida("red", "Sin conexión con la historia clínica general")
        } else {
            try {
                ConfigRemota.Recibida(refrescar(medicoId))
            } catch (e: TobbException) {
                ConfigRemota.Fallida(e.codigo, e.message ?: "No se pudo traer la configuración de la historia clínica")
            }
        }
        return estadoConfig(guardada(medicoId), remota)
    }

    /**
     * [estado] con red, pero esperando a hclinica a lo sumo [espera] ("Decidido" C9): si no contesta a tiempo se deja
     * de esperar y vale lo guardado, como sin conexión (RF-25, RF-26).
     */
    suspend fun estadoConEspera(medicoId: Id, espera: Duration = ESPERA): EstadoConfigHc =
        withTimeoutOrNull(espera) { estado(medicoId, conRed = true) } ?: estado(medicoId, conRed = false)

    companion object {
        /** Lo que espera la historia clínica a que llegue la configuración antes de usar la guardada. */
        val ESPERA = 5.seconds
    }
}
