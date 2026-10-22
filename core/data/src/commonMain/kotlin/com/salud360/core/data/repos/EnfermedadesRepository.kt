package com.salud360.core.data.repos

import co.touchlab.kermit.Logger
import com.salud360.core.data.ahoraMillis
import com.salud360.core.data.hcgeneral.EstadoAdministrar
import com.salud360.core.data.hcgeneral.EstadoSeccionEnfermedades
import com.salud360.core.data.hcgeneral.MotivoEnfermedades
import com.salud360.core.data.hcgeneral.MotivoHc
import com.salud360.core.data.hcgeneral.motivoDeError
import com.salud360.core.data.hcgeneral.muestraCopia
import com.salud360.core.data.mappers.json
import com.salud360.core.data.network.TobbException
import com.salud360.core.data.network.hc.AdministrarEnfermedadesDto
import com.salud360.core.data.network.hc.EnfermedadDto
import com.salud360.core.data.network.hc.EnfermedadesApi
import com.salud360.core.data.network.hc.MarcableDto
import com.salud360.core.data.network.hc.RespuestaCrear
import com.salud360.core.data.network.hc.RespuestaLista
import com.salud360.core.data.network.hc.SeccionEnfermedadesDto
import com.salud360.core.data.network.hc.toModel
import com.salud360.core.data.uno
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.Id
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** RF-30: aviso de la copia en solo lectura cuando no hay conexión. */
const val AVISO_SIN_CONEXION = "Sin conexión: las enfermedades se pueden ver pero no cambiar"

/** RF-31: no hay conexión y el dispositivo no tiene la sección de ese paciente. */
const val TEXTO_HACE_FALTA_CONEXION = "Hace falta conexión para ver las enfermedades"

/** Cómo terminó un pedido a la web de enfermedades. */
sealed interface ResultadoPedido<out T> {
    /** Respuesta correcta; ya está guardada en la copia. */
    data class Ok<T>(val valor: T) : ResultadoPedido<T>

    /** La web (o la red) contestó con un error; [TobbException.codigo] `red` = sin conexión. La copia no se tocó. */
    data class Error(val error: TobbException) : ResultadoPedido<Nothing>

    /** No contestó en la `espera` (RF-28): el pedido sigue en camino y, si llega, igual actualiza la copia. */
    data object SinRespuesta : ResultadoPedido<Nothing>
}

/** Lo que se muestra al abrir la sección de un paciente (RF-9 a RF-15, RF-30, RF-31). */
sealed interface CargaSeccion {
    /** Recién recibida de la web: la sección se puede editar. */
    data class Actual(val estado: EstadoSeccionEnfermedades) : CargaSeccion

    /** Sin conexión o error no previsto, con copia (RF-30): solo lectura, con el [aviso] y "Reintentar". */
    data class SoloLectura(val estado: EstadoSeccionEnfermedades, val aviso: String) : CargaSeccion

    /** Sin conexión o error no previsto y sin copia de esa ficha (RF-31): solo el [texto], sin grilla. */
    data class SinCopia(val texto: String) : CargaSeccion

    /** Un motivo que no muestra enfermedades (RF-11 a RF-13), tampoco las de la copia. */
    data class Motivo(val motivo: MotivoEnfermedades) : CargaSeccion
}

/**
 * Copia en el dispositivo de lo que dice la web de enfermedades (RF-32 a RF-34) y los pedidos a su API. El dueño es
 * hclinica: la tabla `hc_enfermedades` no tiene `dirty`, nada de esto pasa por `seccion_valor`, `paciente_extra`,
 * `HcApiSync` ni `SyncEngine`, y nunca se lo llama desde el autoguardado.
 *
 * Cada pedido sale en su [Deferred] (`pedir…`) y [esperar] lo espera a lo sumo [espera] **sobre el `await`, no sobre el
 * pedido**: así, si se cumple el tiempo, la respuesta tardía igual llega, actualiza la copia y quien la pidió puede
 * seguir esperándola (RF-23 decide si se aplica). En la web un `withTimeoutOrNull` sobre el pedido abortaría el `fetch`.
 * La copia se reemplaza por parte solo con una respuesta correcta (también una lista vacía, RF-33); un error nunca la
 * toca, salvo `403 fuera_de_cartera`, que borra la sección de esa ficha (RF-11).
 *
 * @param api null cuando el entorno no tiene URL de hclinica: se trata como sin conexión, como [ConfigHcRepository].
 * @param nombreHc nombre visible de la HC general, para los motivos.
 * @param scope donde corren los pedidos; tiene que sobrevivir al tiempo de espera.
 */
class EnfermedadesRepository(
    private val db: Salud360Db,
    private val api: EnfermedadesApi?,
    private val nombreHc: String,
    private val espera: Duration = ESPERA,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val log = Logger.withTag("EnfermedadesRepository")
    private val q get() = db.hcEnfermedadesQueries

    // ---------- pedidos ----------

    /** `GET` de la sección de [fichaId] (la ficha de la web). */
    fun pedirSeccion(medicoId: Id, fichaId: Long): Deferred<ResultadoPedido<EstadoSeccionEnfermedades>> =
        pedir({ it.seccion(fichaId) }, alFallar = { borrarSiFueraDeCartera(medicoId, fichaId, it) }) { guardarSeccion(medicoId, fichaId, it) }

    /** `PUT` del estado elegido de una casilla. */
    fun pedirMarcar(medicoId: Id, fichaId: Long, enfermedadId: Long, tiene: Boolean): Deferred<ResultadoPedido<EstadoSeccionEnfermedades>> =
        pedir({ it.marcar(fichaId, enfermedadId, tiene) }, alFallar = { borrarSiFueraDeCartera(medicoId, fichaId, it) }) {
            guardarSeccion(medicoId, fichaId, it)
        }

    /** `GET` de la lista del médico y el catálogo. */
    fun pedirAdministrar(medicoId: Id): Deferred<ResultadoPedido<EstadoAdministrar>> =
        pedir({ it.administrar() }) { guardarAdministrar(medicoId, it) }

    /** `PUT` de agregar o quitar de la lista; con [fichaId] la respuesta puede traer también la sección. */
    fun pedirEnLista(medicoId: Id, enfermedadId: Long, enLista: Boolean, fichaId: Long?): Deferred<ResultadoPedido<RespuestaLista>> =
        pedir({ it.enLista(enfermedadId, enLista, fichaId) }) {
            guardarAdministrar(medicoId, it.administrar)
            if (fichaId != null) it.seccion?.let { s -> guardarSeccion(medicoId, fichaId, s) }
        }

    /** `POST` de crear; con [fichaId], si se marcó al paciente la respuesta trae también la sección. */
    fun pedirCrear(medicoId: Id, nombre: String, fichaId: Long?): Deferred<ResultadoPedido<RespuestaCrear>> =
        pedir({ it.crear(nombre, fichaId) }) {
            guardarAdministrar(medicoId, it.administrar)
            if (fichaId != null) it.seccion?.let { s -> guardarSeccion(medicoId, fichaId, s) }
        }

    /** Espera el [pedido] a lo sumo [espera]; si no contesta, [ResultadoPedido.SinRespuesta] y el pedido sigue. */
    suspend fun <T> esperar(pedido: Deferred<ResultadoPedido<T>>): ResultadoPedido<T> =
        withTimeoutOrNull(espera) { pedido.await() } ?: ResultadoPedido.SinRespuesta

    /**
     * Abre la sección de una ficha: pide el estado y, si no se puede, resuelve con la copia o con el motivo que
     * corresponde (RF-11 a RF-13, RF-30, RF-31).
     */
    suspend fun abrirSeccion(medicoId: Id, fichaId: Long): CargaSeccion =
        cargaDe(esperar(pedirSeccion(medicoId, fichaId))) { copiaSeccion(medicoId, fichaId) }

    private suspend fun cargaDe(resultado: ResultadoPedido<EstadoSeccionEnfermedades>, copia: suspend () -> EstadoSeccionEnfermedades?): CargaSeccion {
        val error = when (resultado) {
            is ResultadoPedido.Ok -> return CargaSeccion.Actual(resultado.valor)
            is ResultadoPedido.Error -> resultado.error
            ResultadoPedido.SinRespuesta -> null
        }
        val motivo = error?.let { motivoDeError(it, nombreHc) }
            ?: MotivoEnfermedades.General(MotivoHc(MotivoHc.Tipo.SIN_CONEXION, AVISO_SIN_CONEXION))
        if (!muestraCopia(motivo)) return CargaSeccion.Motivo(motivo)
        val sinConexion = motivo is MotivoEnfermedades.General && motivo.motivo.tipo == MotivoHc.Tipo.SIN_CONEXION
        val estado = copia()
        return when {
            estado != null -> CargaSeccion.SoloLectura(estado, if (sinConexion) AVISO_SIN_CONEXION else motivo.texto)
            else -> CargaSeccion.SinCopia(if (sinConexion) TEXTO_HACE_FALTA_CONEXION else motivo.texto)
        }
    }

    // ---------- copia ----------

    /** Lo último que dijo la web de la sección de [fichaId], o null si nunca se recibió (o no se puede leer). */
    suspend fun copiaSeccion(medicoId: Id, fichaId: Long): EstadoSeccionEnfermedades? =
        leer(medicoId, PARTE_SECCION, fichaId) { json.decodeFromString(SeccionEnfermedadesDto.serializer(), it).toModel() }

    /** La lista del médico y el catálogo tal como los dejó la web, o null si nunca se recibieron. */
    suspend fun copiaAdministrar(medicoId: Id): EstadoAdministrar? =
        leer(medicoId, PARTE_ADMINISTRAR, SIN_FICHA) { json.decodeFromString(AdministrarEnfermedadesDto.serializer(), it).toModel() }

    private suspend fun <T> leer(medicoId: Id, parte: String, fichaId: Long, convertir: (String) -> T): T? {
        val texto = q.copia(medicoId, parte, fichaId).uno { it.datos_json } ?: return null
        return runCatching { convertir(texto) }.getOrNull()
    }

    private suspend fun guardarSeccion(medicoId: Id, fichaId: Long, estado: EstadoSeccionEnfermedades) {
        val dto = SeccionEnfermedadesDto(
            estado.marcables.map { MarcableDto(it.id, it.nombre, it.tiene) },
            estado.otras.map { EnfermedadDto(it.id, it.nombre) },
        )
        q.guardar(medicoId, PARTE_SECCION, fichaId, json.encodeToString(SeccionEnfermedadesDto.serializer(), dto), ahoraMillis())
    }

    private suspend fun guardarAdministrar(medicoId: Id, estado: EstadoAdministrar) {
        val dto = AdministrarEnfermedadesDto(
            estado.lista.map { EnfermedadDto(it.id, it.nombre) },
            estado.catalogo.map { EnfermedadDto(it.id, it.nombre) },
        )
        q.guardar(medicoId, PARTE_ADMINISTRAR, SIN_FICHA, json.encodeToString(AdministrarEnfermedadesDto.serializer(), dto), ahoraMillis())
    }

    private suspend fun borrarSiFueraDeCartera(medicoId: Id, fichaId: Long, e: TobbException) {
        if (e.codigo == "fuera_de_cartera") q.borrarFicha(medicoId, fichaId)
    }

    /**
     * Lanza [pedido] en [scope]. Una respuesta correcta se guarda con [guardar] antes de completar; un error pasa por
     * [alFallar] (solo `fuera_de_cartera` toca la copia). Si la copia no se puede escribir se registra y el resultado
     * igual es el de la web (la copia es solo para mostrar). Los errores no previstos se vuelven [TobbException]
     * `respuesta`, como en [ConfigHcRepository].
     */
    private fun <T> pedir(
        pedido: suspend (EnfermedadesApi) -> T,
        alFallar: suspend (TobbException) -> Unit = {},
        guardar: suspend (T) -> Unit,
    ): Deferred<ResultadoPedido<T>> = scope.async {
        val api = api
            ?: return@async ResultadoPedido.Error(TobbException(0, "red", "Este entorno no tiene configurada la historia clínica general"))
        val valor = try {
            pedido(api)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TobbException) {
            tocarCopia("actualizar") { alFallar(e) }
            return@async ResultadoPedido.Error(e)
        } catch (e: Throwable) {
            return@async ResultadoPedido.Error(
                TobbException(0, "respuesta", "No se pudo leer la respuesta de enfermedades: ${e.message ?: "error"}"),
            )
        }
        tocarCopia("guardar") { guardar(valor) }
        ResultadoPedido.Ok(valor)
    }

    private suspend fun tocarCopia(que: String, bloque: suspend () -> Unit) {
        try {
            bloque()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.w(e) { "No se pudo $que la copia de enfermedades" }
        }
    }

    companion object {
        /** RF-28: lo que se espera a la web antes de darla por sin conexión. */
        val ESPERA = 10.seconds

        private const val PARTE_ADMINISTRAR = "administrar"
        private const val PARTE_SECCION = "seccion"

        /** `ficha_id` de la parte `administrar`, que no es de ninguna ficha. */
        private const val SIN_FICHA = 0L
    }
}
