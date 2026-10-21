package com.salud360.core.data.hcgeneral

import com.salud360.core.data.network.TobbException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** RF-28: lo que se espera la respuesta de un toque (y la del estado que se pide después) antes de darla por perdida. */
val ESPERA_MARCADO: Duration = 10.seconds

/** RF-24: lo que se ve "Guardado" desde la última vez que cambió [EstadoMarcado.guardado]. */
val DURACION_GUARDADO: Duration = 3.seconds

/** RF-26. */
const val TEXTO_DEMASIADOS_CAMBIOS = "Demasiados cambios seguidos, esperá un momento"

/** RF-27: rechazo no previsto. */
const val TEXTO_NO_SE_PUDO_GUARDAR = "No se pudo guardar el cambio. Probá de nuevo."

/** RF-25. */
fun textoYaNoEnTuLista(nombre: String) = "«$nombre» ya no está en tu lista"

/** RF-25. */
fun textoDadaDeBaja(nombre: String) = "«$nombre» se dio de baja en el catálogo"

/** RF-28: ni el toque ni el estado pedido después contestaron. */
fun textoSinConfirmar(nombre: String) =
    "No se pudo confirmar el cambio en «$nombre»: puede haberse guardado. Volvé a abrir la sección con conexión."

/** El único aviso de la sección (RF-29): el último rechazo, hasta la siguiente confirmación. */
sealed interface AvisoEnfermedades {
    val texto: String

    /** Un texto de RF-25 a RF-28. */
    data class Rechazo(override val texto: String) : AvisoEnfermedades

    /** Un motivo de RF-11 a RF-13 (RF-27): la pantalla lo muestra como los demás motivos, sin enfermedades. */
    data class Motivo(val motivo: MotivoEnfermedades) : AvisoEnfermedades {
        override val texto: String get() = motivo.texto
    }
}

/**
 * Un toque sin respuesta todavía: lo elegido, la [secuencia] con que se envió y el [nombre] de la enfermedad (para el
 * aviso, aunque una respuesta más nueva ya no la traiga). [sinRespuesta]: se cumplió la espera y se pidió el estado
 * (RF-28); sigue mostrando lo elegido y bloqueada, pero el próximo estado aplicado manda sobre ella.
 */
data class EnCamino(val tiene: Boolean, val secuencia: Long, val nombre: String, val sinRespuesta: Boolean = false)

/**
 * Estado del marcado de la sección (RF-21 a RF-29, D9). [confirmado] es lo último que mandó la web (null hasta la
 * primera respuesta); [ultimaEnviada] numera cada pedido al enviarse (toques, estado, cambios de la lista) y una
 * respuesta con estado se aplica solo si su secuencia es mayor que [ultimaAplicada] (RF-23). [guardado] sube con cada
 * confirmación: la pantalla muestra "Guardado" [DURACION_GUARDADO] desde que cambia (RF-24). [soloLectura]: ni el toque
 * ni el estado pedido después contestaron (RF-28, RF-30); sigue así hasta que el controlador vuelva a abrir la sección.
 * Funciones puras: no hacen pedidos ni miden tiempo; cada transición devuelve el [PasoMarcado] con qué pedir.
 */
data class EstadoMarcado(
    val confirmado: EstadoSeccionEnfermedades? = null,
    val enCamino: Map<Long, EnCamino> = emptyMap(),
    val ultimaAplicada: Long = 0,
    val ultimaEnviada: Long = 0,
    val aviso: AvisoEnfermedades? = null,
    val guardado: Int = 0,
    val soloLectura: Boolean = false,
) {
    /** Lo que se muestra: [confirmado] con lo elegido de [enCamino] encima. */
    val vista: EstadoSeccionEnfermedades?
        get() = confirmado?.let { c ->
            c.copy(marcables = c.marcables.map { m -> enCamino[m.id]?.let { m.copy(tiene = it.tiene) } ?: m })
        }

    /** "Guardando…" (RF-21). */
    fun guardando(id: Long): Boolean = id in enCamino

    /** RF-22: solo las marcables sin pedido en camino, y nada en solo lectura (RF-30). */
    fun puedeTocar(id: Long): Boolean =
        !soloLectura && id !in enCamino && confirmado?.marcables?.any { it.id == id } == true
}

/** Lo que hay que pedirle a la web después de una transición. */
sealed interface PedidoMarcado {
    val secuencia: Long

    /** Mandar el estado elegido, no "invertir" (RF-21). */
    data class Marcar(override val secuencia: Long, val id: Long, val tiene: Boolean) : PedidoMarcado

    /** Pedir el estado de la sección (RF-25, RF-28; también al abrir). */
    data class PedirEstado(override val secuencia: Long) : PedidoMarcado
}

data class PasoMarcado(val estado: EstadoMarcado, val pedido: PedidoMarcado? = null)

/** Reserva la secuencia de un pedido que no sale de acá (cambios de la lista que traen `seccion`, F2.8). */
fun EstadoMarcado.siguienteSecuencia(): Pair<EstadoMarcado, Long> =
    (ultimaEnviada + 1).let { copy(ultimaEnviada = it) to it }

fun EstadoMarcado.pedirEstado(): PasoMarcado =
    siguienteSecuencia().let { (estado, s) -> PasoMarcado(estado, PedidoMarcado.PedirEstado(s)) }

/** RF-21, RF-22: muestra lo elegido y lo manda; si no se puede tocar, nada. */
fun EstadoMarcado.tocar(id: Long, tiene: Boolean): PasoMarcado {
    if (!puedeTocar(id)) return PasoMarcado(this)
    val nombre = confirmado!!.marcables.first { it.id == id }.nombre
    val (estado, s) = siguienteSecuencia()
    return PasoMarcado(estado.copy(enCamino = enCamino + (id to EnCamino(tiene, s, nombre))), PedidoMarcado.Marcar(s, id, tiene))
}

/**
 * La web confirmó el toque [secuencia] sobre [id] con el [estado] de la sección (RF-23, RF-24, RF-29). Si es más nueva
 * que lo aplicado, se aplica; si no, se descarta pero la casilla queda como la guardó la web (nadie más la cambió desde
 * este dispositivo: RF-22). En los dos casos sube [EstadoMarcado.guardado] y se limpia el aviso.
 */
fun EstadoMarcado.confirmado(secuencia: Long, id: Long, estado: EstadoSeccionEnfermedades): PasoMarcado {
    val base = copy(enCamino = sinPendiente(secuencia, id), guardado = guardado + 1, aviso = null)
    if (secuencia > ultimaAplicada) return PasoMarcado(base.aplicar(secuencia, estado))
    val tiene = estado.marcables.firstOrNull { it.id == id }?.tiene ?: return PasoMarcado(base)
    return PasoMarcado(
        base.copy(confirmado = confirmado?.let { c -> c.copy(marcables = c.marcables.map { if (it.id == id) it.copy(tiene = tiene) else it }) }),
    )
}

/**
 * La web rechazó el toque [secuencia] sobre [id]. Sin conexión (`red`) es [sinRespuesta] (RF-28); el resto saca la
 * casilla de "en camino", así vuelve al último estado confirmado, con su aviso: 429 (RF-26), ya no en la lista o dada
 * de baja más pedir el estado (RF-25), un motivo de RF-11 a RF-13 o el texto de no previsto (RF-27). `tobb_caido` es
 * no previsto: la web contestó, así que no se guardó.
 */
fun EstadoMarcado.rechazado(secuencia: Long, id: Long, error: TobbException, nombreHc: String): PasoMarcado {
    val pendiente = enCamino[id]?.takeIf { it.secuencia == secuencia } ?: return PasoMarcado(this)
    if (error.codigo == "red" || (error.status == 0 && error.codigo == null)) return sinRespuesta(secuencia, id)
    val revertido = copy(enCamino = enCamino - id)
    fun avisar(texto: String) = revertido.copy(aviso = AvisoEnfermedades.Rechazo(texto))
    return when {
        error.status == 429 -> PasoMarcado(avisar(TEXTO_DEMASIADOS_CAMBIOS))
        error.codigo == "no_en_tu_lista" -> avisar(textoYaNoEnTuLista(pendiente.nombre)).pedirEstadoSiEditable()
        error.codigo == "enfermedad_dada_de_baja" -> avisar(textoDadaDeBaja(pendiente.nombre)).pedirEstadoSiEditable()
        else -> {
            val motivo = motivoDeError(error, nombreHc)
            PasoMarcado(
                if (motivo.prioridad == 5) avisar(TEXTO_NO_SE_PUDO_GUARDAR)
                else revertido.copy(aviso = AvisoEnfermedades.Motivo(motivo)),
            )
        }
    }
}

/**
 * El toque [secuencia] sobre [id] no contestó en [ESPERA_MARCADO] (RF-28): no se revierte a ciegas, se pide el estado.
 * En solo lectura ya no se pide nada: vuelve al último confirmado con el aviso de RF-28.
 */
fun EstadoMarcado.sinRespuesta(secuencia: Long, id: Long): PasoMarcado {
    val pendiente = enCamino[id]?.takeIf { it.secuencia == secuencia && !it.sinRespuesta } ?: return PasoMarcado(this)
    if (soloLectura) {
        return PasoMarcado(copy(enCamino = enCamino - id, aviso = AvisoEnfermedades.Rechazo(textoSinConfirmar(pendiente.nombre))))
    }
    return copy(enCamino = enCamino + (id to pendiente.copy(sinRespuesta = true))).pedirEstado()
}

/** Llegó el estado pedido con [secuencia]: se aplica si es más nuevo que lo aplicado (RF-23); no es una confirmación. */
fun EstadoMarcado.estadoPedido(secuencia: Long, estado: EstadoSeccionEnfermedades): PasoMarcado =
    PasoMarcado(if (secuencia > ultimaAplicada) aplicar(secuencia, estado) else this)

/**
 * El estado pedido con [secuencia] tampoco contestó (RF-28): los toques sin respuesta anteriores vuelven al último
 * confirmado, con el aviso del más reciente, y la sección pasa a solo lectura (RF-30). Si ya se aplicó algo más nuevo,
 * no hace falta.
 */
fun EstadoMarcado.estadoPedidoSinRespuesta(secuencia: Long): PasoMarcado {
    if (secuencia <= ultimaAplicada) return PasoMarcado(this)
    val perdidos = enCamino.filterValues { it.sinRespuesta && it.secuencia < secuencia }
    val ultimo = perdidos.values.maxByOrNull { it.secuencia }
    return PasoMarcado(
        copy(
            enCamino = enCamino - perdidos.keys,
            soloLectura = true,
            aviso = ultimo?.let { AvisoEnfermedades.Rechazo(textoSinConfirmar(it.nombre)) } ?: aviso,
        ),
    )
}

private fun EstadoMarcado.sinPendiente(secuencia: Long, id: Long): Map<Long, EnCamino> =
    if (enCamino[id]?.secuencia == secuencia) enCamino - id else enCamino

/** Aplica un estado más nuevo: las casillas en camino conservan lo elegido; las sin respuesta enviadas antes, no. */
private fun EstadoMarcado.aplicar(secuencia: Long, estado: EstadoSeccionEnfermedades): EstadoMarcado =
    copy(
        confirmado = estado,
        ultimaAplicada = secuencia,
        enCamino = enCamino.filterValues { !(it.sinRespuesta && it.secuencia < secuencia) },
    )

private fun EstadoMarcado.pedirEstadoSiEditable(): PasoMarcado = if (soloLectura) PasoMarcado(this) else pedirEstado()
