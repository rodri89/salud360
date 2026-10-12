package com.salud360.core.data.repos

import com.salud360.core.data.network.TobbException
import com.salud360.core.data.network.hc.HcApiClient
import com.salud360.core.data.network.hc.HcAsignacionDto
import com.salud360.core.data.network.hc.HcAsignarRequest
import com.salud360.core.data.network.hc.HcPerfilDto
import com.salud360.core.data.network.hc.HcResumenPerfilDto
import com.salud360.core.data.network.hc.HcSeccionCatalogoDto
import com.salud360.core.data.network.hc.toDto
import com.salud360.core.data.network.hc.toModel
import com.salud360.core.model.especialidad.AsignacionPerfil
import com.salud360.core.model.especialidad.PerfilHc
import com.salud360.core.model.especialidad.ResumenPerfil
import com.salud360.core.model.especialidad.SeccionCatalogoRemota
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject

/**
 * Perfiles de la HC general para el administrador (RF-1 a RF-17): todo contra hclinica, **nada local** (sin conexión
 * el administrador no edita perfiles). Cada fallo lanza [TobbException] con el `mensaje`, el `codigo` y, en un
 * `422 validacion`, los `errores` campo por campo de la API (RF-13); `codigo` `red` = sin conexión (RF-14).
 *
 * @param cliente null cuando el entorno no tiene URL de hclinica (dev sin `hc.clinica`): se trata como sin conexión.
 */
class PerfilesHcRepository(private val cliente: HcApiClient?) {

    suspend fun catalogo(): List<SeccionCatalogoRemota> = llamar { c ->
        c.leer(ListSerializer(HcSeccionCatalogoDto.serializer()), c.get("config/catalogo"), "secciones").map { it.toModel() }
    }

    /** Por nombre, con cuántos médicos tiene cada uno. */
    suspend fun perfiles(): List<ResumenPerfil> = llamar { c ->
        c.leer(ListSerializer(HcResumenPerfilDto.serializer()), c.get("config/perfiles"), "perfiles").map { it.toModel() }
    }

    suspend fun perfil(id: Long): PerfilHc = llamar { c -> c.perfil(c.get("config/perfiles/$id")) }

    /** Alta (también la copia de un perfil): devuelve el perfil guardado, con su `id`. */
    suspend fun crear(perfil: PerfilHc): PerfilHc = llamar { c ->
        c.perfil(c.post("config/perfiles", c.cuerpo(HcPerfilDto.serializer(), perfil.toDto())))
    }

    /** Reemplaza el perfil entero (el `id` no cambia, las asignaciones siguen). */
    suspend fun guardar(perfil: PerfilHc): PerfilHc {
        val id = perfil.id ?: return crear(perfil)
        return llamar { c -> c.perfil(c.put("config/perfiles/$id", c.cuerpo(HcPerfilDto.serializer(), perfil.toDto()))) }
    }

    /** Con médicos asignados la API contesta `409 perfil_con_medicos` y no borra nada. */
    suspend fun borrar(id: Long) {
        llamar { c -> c.delete("config/perfiles/$id") }
    }

    suspend fun asignaciones(): List<AsignacionPerfil> = llamar { c ->
        c.leer(ListSerializer(HcAsignacionDto.serializer()), c.get("config/asignaciones"), "asignaciones").map { it.toModel() }
    }

    /** Asigna o cambia el perfil del médico (por número de turnos); [perfilId] null se lo quita. */
    suspend fun asignar(medicoIdTobb: Long, perfilId: Long?): AsignacionPerfil = llamar { c ->
        val r = c.put("config/asignaciones/$medicoIdTobb", c.cuerpo(HcAsignarRequest.serializer(), HcAsignarRequest(perfilId)))
        c.leer(HcAsignacionDto.serializer(), r, "asignacion").toModel()
    }

    private fun HcApiClient.perfil(r: JsonObject): PerfilHc =
        leer(HcPerfilDto.serializer(), r, "perfil").toModel()

    private suspend fun <T> llamar(bloque: suspend (HcApiClient) -> T): T {
        val c = cliente ?: throw TobbException(0, "red", "Este entorno no tiene configurada la historia clínica general")
        return try {
            bloque(c)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TobbException) {
            throw e
        } catch (e: Throwable) {
            throw TobbException(0, "respuesta", "No se pudo leer la respuesta de la historia clínica: ${e.message ?: "error"}")
        }
    }
}
