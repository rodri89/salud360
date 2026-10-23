package com.salud360.core.data.repos

import com.salud360.core.data.hcgeneral.AvisoEnfermedades
import com.salud360.core.data.hcgeneral.BusquedaAdministrar
import com.salud360.core.data.hcgeneral.EstadoAdministrar
import com.salud360.core.data.hcgeneral.EstadoMarcado
import com.salud360.core.data.hcgeneral.EstadoSeccionEnfermedades
import com.salud360.core.data.hcgeneral.MotivoEnfermedades
import com.salud360.core.data.hcgeneral.MotivoSinMarcar
import com.salud360.core.data.hcgeneral.OfertaCrear
import com.salud360.core.data.hcgeneral.PacienteCrear
import com.salud360.core.data.hcgeneral.PasoMarcado
import com.salud360.core.data.hcgeneral.PedidoMarcado
import com.salud360.core.data.hcgeneral.ResultadoVinculo
import com.salud360.core.data.hcgeneral.TEXTO_DEMASIADOS_CAMBIOS
import com.salud360.core.data.hcgeneral.TEXTO_NO_SE_PUDO_GUARDAR
import com.salud360.core.data.hcgeneral.avisoCrear
import com.salud360.core.data.hcgeneral.confirmado
import com.salud360.core.data.hcgeneral.errorDeNombreEnfermedad
import com.salud360.core.data.hcgeneral.estadoPedido
import com.salud360.core.data.hcgeneral.estadoPedidoSinRespuesta
import com.salud360.core.data.hcgeneral.filtrar
import com.salud360.core.data.hcgeneral.motivoDeError
import com.salud360.core.data.hcgeneral.motivoDeVinculo
import com.salud360.core.data.hcgeneral.nombreNormalizado
import com.salud360.core.data.hcgeneral.ofrecerCrear
import com.salud360.core.data.hcgeneral.pacienteParaCrear
import com.salud360.core.data.hcgeneral.pedirEstado
import com.salud360.core.data.hcgeneral.primerMotivo
import com.salud360.core.data.hcgeneral.puedeAdministrar
import com.salud360.core.data.hcgeneral.rechazado
import com.salud360.core.data.hcgeneral.sinRespuesta
import com.salud360.core.data.hcgeneral.textoDadaDeBaja
import com.salud360.core.data.hcgeneral.textoSinConfirmar
import com.salud360.core.data.hcgeneral.textoYaNoEnTuLista
import com.salud360.core.data.hcgeneral.tocar
import com.salud360.core.data.network.TobbException
import com.salud360.core.model.Id
import com.salud360.core.model.especialidad.CodigosHc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Lo que muestra la sección "Enfermedades" de una consulta. La pantalla decide con esto qué dibujar: [cargando], el
 * [motivoMostrado] (RF-9 a RF-14), [textoSinCopia] (RF-31), la [vista] con las casillas (RF-15 a RF-24, `vistaSeccion`
 * según la consulta sea editable o no) y, en solo lectura, el [avisoSoloLectura] con "Reintentar" (RF-30).
 *
 * @param marcado el estado de las casillas (lo elegido, "Guardando…", el aviso único, "Guardado").
 * @param motivo el motivo de la carga o del vínculo (lugares 6 a 9 de RF-14, o 1 a 4 de la web), sin enfermedades.
 * @param textoSinCopia sin conexión o con un error no previsto y sin copia de esa ficha (RF-31).
 * @param avisoSoloLectura el aviso de RF-30 mientras se ve la copia o tras RF-28; null si se puede editar.
 * @param fichaId la ficha de la web del paciente, cuando ya está vinculado.
 */
data class SeccionEnfermedadesUi(
    val cargando: Boolean = true,
    val marcado: EstadoMarcado = EstadoMarcado(),
    val motivo: MotivoEnfermedades? = null,
    val textoSinCopia: String? = null,
    val avisoSoloLectura: String? = null,
    val fichaId: Long? = null,
) {
    /** RF-14: el único motivo que se muestra; también un rechazo de un toque con motivo de RF-11 a RF-13 (RF-27). */
    val motivoMostrado: MotivoEnfermedades?
        get() = primerMotivo(listOfNotNull(motivo, (marcado.aviso as? AvisoEnfermedades.Motivo)?.motivo))

    /** Lo que se puede mostrar (con las casillas elegidas encima), o null si hay un motivo o falta la copia. */
    val vista: EstadoSeccionEnfermedades?
        get() = if (motivoMostrado != null || textoSinCopia != null) null else marcado.vista

    /** Con conexión: ya cargó, no está en solo lectura y no se quedó sin copia (RF-37). */
    val conConexion: Boolean get() = !cargando && !marcado.soloLectura && textoSinCopia == null
}

/**
 * Lo que muestra la ventana "Administrar enfermedades": la lista y el catálogo de la web ([estado], null hasta que
 * llegan), lo [buscado] (se conserva ante un rechazo, RF-46), un cambio de la lista [enCamino] (RF-45) y el [aviso].
 */
data class AdministrarEnfermedadesUi(
    val estado: EstadoAdministrar? = null,
    val buscado: String = "",
    val cargando: Boolean = false,
    val enCamino: Boolean = false,
    val aviso: String? = null,
) {
    /** RF-39: lo que se ve para lo escrito. */
    val busqueda: BusquedaAdministrar? get() = estado?.let { filtrar(it, buscado) }

    /** RF-42, RF-43: "Crear «…»" para lo escrito. */
    val oferta: OfertaCrear? get() = estado?.let { ofrecerCrear(it, buscado) }
}

/**
 * Orquesta los pedidos de la sección "Enfermedades" de una consulta (spec 003): resuelve el vínculo del paciente con
 * `PacientesHcRepository` (RF-8, RF-9), abre la sección (un solo pedido, RF-15), marca y desmarca (RF-21 a RF-29) y
 * administra la lista del médico (RF-38 a RF-48). Las reglas son las de `MarcadoEnfermedades` y
 * `AdministrarEnfermedades` (puras); acá solo se hacen los pedidos y se aplican sus transiciones. Coroutines sin
 * Compose: la pantalla observa [estado] y [administrar] y llama a las funciones, que no bloquean.
 *
 * Nunca lo llama el autoguardado, `HcApiSync` ni `SyncEngine`: cada toque sale en el momento (RF-21) y nada de esto
 * queda `dirty` ni en `seccion_valor` (RF-32, RF-36). La espera de 10 s (RF-28) es la del [repo]
 * (`EnfermedadesRepository.esperar`, sobre el `await`: una respuesta tardía igual llega y se aplica si RF-23 lo permite).
 *
 * @param scope el del ViewModel; los pedidos viven mientras viva.
 * @param editable si la consulta se puede editar (abierta y propia, RF-35), se lee en cada uso.
 */
class EnfermedadesControlador(
    private val repo: EnfermedadesRepository,
    private val pacientesHc: PacientesHcRepository,
    private val scope: CoroutineScope,
    private val medicoId: Id,
    private val pacienteId: Id,
    private val editable: () -> Boolean = { true },
) {
    private val nombreHc = repo.nombreHc
    private val _estado = MutableStateFlow(SeccionEnfermedadesUi())
    private val _administrar = MutableStateFlow(AdministrarEnfermedadesUi())

    val estado: StateFlow<SeccionEnfermedadesUi> = _estado.asStateFlow()
    val administrar: StateFlow<AdministrarEnfermedadesUi> = _administrar.asStateFlow()

    private var abierto = false

    /** Sube con cada [cerrarAdministrar]: un pedido de la ventana recuerda la de cuando salió. */
    private val ventana = MutableStateFlow(0)

    // ---------- abrir (RF-8, RF-9, RF-15, RF-30, RF-31) ----------

    /** Resuelve el vínculo si hace falta y pide la sección: un solo pedido. Abrir otra vez no repite nada. */
    fun abrir() {
        if (abierto) return
        abierto = true
        scope.launch { cargar() }
    }

    /** RF-30: vuelve a pedir la sección (y el vínculo, si faltaba). */
    fun reintentar() {
        var tomado = false
        _estado.update { tomado = !it.cargando; if (tomado) it.copy(cargando = true) else it }
        if (tomado) scope.launch { cargar() }
    }

    /** RF-9: el médico eligió una de las candidatas: se guarda el vínculo y se carga la sección. */
    fun elegirFicha(fichaId: Long) {
        // Dos toques seguidos: el segundo no repite la resolución ni la carga mientras el primero sigue en vuelo.
        var tomado = false
        _estado.update { tomado = !it.cargando; if (tomado) it.copy(cargando = true) else it }
        if (!tomado) return
        scope.launch {
            val ficha = vincular { pacientesHc.elegirFicha(pacienteId, CodigosHc.GENERAL, fichaId) } ?: return@launch
            cargarSeccion(ficha)
        }
    }

    private suspend fun cargar() {
        val ficha = _estado.value.fichaId ?: vincular { pacientesHc.vinculo(pacienteId, CodigosHc.GENERAL) } ?: return
        cargarSeccion(ficha)
    }

    /** Ficha del paciente, o null si no quedó vinculado (y ya se mostró por qué). */
    private suspend fun vincular(resolver: suspend () -> ResultadoVinculo): Long? {
        val resultado = try {
            resolver()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            ResultadoVinculo.NoSePudo(e as? TobbException ?: errorNoLeido(e))
        }
        if (resultado is ResultadoVinculo.Vinculado) {
            _estado.update { it.copy(fichaId = resultado.fichaId) }
            return resultado.fichaId
        }
        val motivo = motivoDeVinculo(resultado, nombreHc) ?: return null
        aplicarCarga(repo.cargaSinFicha(motivo), secuencia = 0)
        return null
    }

    private suspend fun cargarSeccion(ficha: Long) {
        val secuencia = reservarSecuencia()
        aplicarCarga(repo.abrirSeccion(medicoId, ficha), secuencia)
    }

    private fun aplicarCarga(carga: CargaSeccion, secuencia: Long) = _estado.update { u ->
        val base = u.marcado.copy(confirmado = null, enCamino = emptyMap(), aviso = null, soloLectura = false)
        val limpio = u.copy(cargando = false, marcado = base, motivo = null, textoSinCopia = null, avisoSoloLectura = null)
        when (carga) {
            is CargaSeccion.Actual -> limpio.copy(marcado = base.estadoPedido(secuencia, carga.estado).estado)
            is CargaSeccion.SoloLectura ->
                limpio.copy(marcado = base.copy(confirmado = carga.estado, soloLectura = true), avisoSoloLectura = carga.aviso)
            is CargaSeccion.SinCopia -> limpio.copy(textoSinCopia = carga.texto)
            is CargaSeccion.Motivo -> limpio.copy(motivo = carga.motivo)
        }
    }

    // ---------- marcar (RF-21 a RF-29) ----------

    /** RF-21: manda el estado elegido en este momento, sin esperar al autoguardado. No hace nada si la casilla no se puede tocar (RF-22). */
    fun tocar(enfermedadId: Long, tiene: Boolean) {
        val ficha = _estado.value.fichaId ?: return
        val pedido = marcar { it.tocar(enfermedadId, tiene) } as? PedidoMarcado.Marcar ?: return
        scope.launch { enviarToque(ficha, pedido) }
    }

    private suspend fun enviarToque(ficha: Long, p: PedidoMarcado.Marcar) {
        val pedido = repo.pedirMarcar(medicoId, ficha, p.id, p.tiene)
        val resultado = repo.esperar(pedido)
        if (resultado !is ResultadoPedido.SinRespuesta) return resolverToque(ficha, p, resultado)
        // RF-28: no se revierte a ciegas, se pide el estado; y si el toque contesta después se lo aplica igual (RF-23).
        seguir(marcar { it.sinRespuesta(p.secuencia, p.id) }, ficha)
        resolverToque(ficha, p, pedido.await())
    }

    private fun resolverToque(ficha: Long, p: PedidoMarcado.Marcar, resultado: ResultadoPedido<EstadoSeccionEnfermedades>) {
        when (resultado) {
            is ResultadoPedido.Ok -> marcar { it.confirmado(p.secuencia, p.id, resultado.valor) }
            is ResultadoPedido.Error -> seguir(marcar { it.rechazado(p.secuencia, p.id, resultado.error, nombreHc) }, ficha)
            ResultadoPedido.SinRespuesta -> Unit
        }
    }

    /** Si la transición pidió el estado de la sección, lo pide. */
    private fun seguir(pedido: PedidoMarcado?, ficha: Long) {
        if (pedido is PedidoMarcado.PedirEstado) scope.launch { pedirEstado(ficha, pedido.secuencia) }
    }

    private suspend fun pedirEstado(ficha: Long, secuencia: Long) {
        val pedido = repo.pedirSeccion(medicoId, ficha)
        when (val r = repo.esperar(pedido)) {
            is ResultadoPedido.Ok -> marcar { it.estadoPedido(secuencia, r.valor) }
            is ResultadoPedido.Error -> estadoRechazado(secuencia, r.error)
            ResultadoPedido.SinRespuesta -> {
                marcar { it.estadoPedidoSinRespuesta(secuencia) }
                // La respuesta tardía se aplica si es más nueva que lo aplicado, pero sigue en solo lectura (RF-28).
                (pedido.await() as? ResultadoPedido.Ok)?.let { ok -> marcar { it.estadoPedido(secuencia, ok.valor) } }
            }
        }
    }

    /**
     * La web rechazó el pedido del estado: sin conexión o un error no previsto es como no tener respuesta (RF-28); con un
     * motivo (fuera de la cartera, licencia, sesión…) la sección muestra ese motivo en lugar de las casillas.
     */
    private fun estadoRechazado(secuencia: Long, error: TobbException) {
        val motivo = motivoDeError(error, nombreHc)
        if (motivo.prioridad == 5) {
            marcar { it.estadoPedidoSinRespuesta(secuencia) }
        } else {
            _estado.update { it.copy(motivo = motivo, marcado = it.marcado.copy(enCamino = emptyMap())) }
        }
    }

    /** Aplica una transición de [MarcadoEnfermedades] y devuelve qué pedir después. En solo lectura deja el aviso de RF-30. */
    private fun marcar(transicion: (EstadoMarcado) -> PasoMarcado): PedidoMarcado? {
        var pedido: PedidoMarcado? = null
        _estado.update { u ->
            val paso = transicion(u.marcado)
            pedido = paso.pedido
            u.copy(
                marcado = paso.estado,
                avisoSoloLectura = u.avisoSoloLectura ?: AVISO_SIN_CONEXION.takeIf { paso.estado.soloLectura },
            )
        }
        return pedido
    }

    /** Una secuencia para un pedido que no sale del marcado (la carga de la sección, un cambio de la lista con ficha). */
    private fun reservarSecuencia(): Long = marcar { it.pedirEstado() }!!.secuencia

    // ---------- administrar (RF-37 a RF-48, RF-58) ----------

    /** RF-37: con una consulta abierta propia y con conexión, esté o no vinculado el paciente (también con los motivos 6 a 9). */
    fun puedeAdministrar(): Boolean {
        val e = _estado.value
        return puedeAdministrar(editable(), propia = true, conConexion = e.conConexion, motivo = e.motivoMostrado)
    }

    /**
     * Los cambios de la lista (agregar, quitar, crear) no miran [editable]: RF-58 pide que "Crear" siga funcionando (sin
     * marcar) con la consulta que se cerró con la ventana abierta. Sí miran la conexión y los motivos 1 a 5 (RF-30, RF-37).
     */
    private fun puedeCambiarLista(): Boolean {
        val e = _estado.value
        return puedeAdministrar(editable = true, propia = true, conConexion = e.conConexion, motivo = e.motivoMostrado)
    }

    /**
     * RF-38: pide la lista del médico y el catálogo (un solo pedido). No hace nada si no se puede administrar (RF-37) ni
     * si ya hay un pedido de estos en vuelo.
     */
    fun abrirAdministrar() {
        if (!puedeAdministrar()) return
        val deEstaVentana = ventana.value
        var tomado = false
        _administrar.update { tomado = !it.cargando; if (tomado) it.copy(cargando = true, aviso = null) else it }
        if (!tomado) return
        scope.launch {
            val r = repo.esperar(repo.pedirAdministrar(medicoId))
            // Si se cerró la ventana mientras tanto, la respuesta no vuelve a escribirla.
            _administrar.update {
                if (deEstaVentana != ventana.value) return@update it
                when (r) {
                    is ResultadoPedido.Ok -> it.copy(estado = r.valor, cargando = false)
                    is ResultadoPedido.Error -> it.copy(cargando = false, aviso = textoDe(r.error))
                    ResultadoPedido.SinRespuesta -> it.copy(cargando = false, aviso = textoDe(errorSinRespuesta()))
                }
            }
        }
    }

    /**
     * Se cerró la ventana: la próxima vez que se abra no se ve lo de esta (lo escrito, el aviso ni la lista vieja) y lo
     * que todavía esté en camino no la reescribe. Un cambio de la lista en camino sigue contando hasta que conteste (RF-45).
     */
    fun cerrarAdministrar() {
        ventana.update { it + 1 }
        _administrar.update { AdministrarEnfermedadesUi(enCamino = it.enCamino) }
    }

    /** RF-39: lo escrito en el buscador (el filtro es de la pantalla, sin pedidos). */
    fun buscar(texto: String) = _administrar.update { it.copy(buscado = texto) }

    /** RF-40: suma la enfermedad a la lista del médico. */
    fun agregar(enfermedadId: Long) {
        if (!puedeCambiarLista()) return
        cambiarLista(
            nombre = nombreDe(enfermedadId),
            pedir = { ficha -> repo.pedirEnLista(medicoId, enfermedadId, true, ficha) },
            leer = { r, _ -> CambioListo(r.administrar, r.seccion, enfermedadId, aviso = null) },
        )
    }

    /** RF-41: la saca de la lista del médico, sin desmarcarla de ningún paciente. */
    fun quitar(enfermedadId: Long) {
        if (!puedeCambiarLista()) return
        cambiarLista(
            nombre = nombreDe(enfermedadId),
            pedir = { ficha -> repo.pedirEnLista(medicoId, enfermedadId, false, ficha) },
            leer = { r, _ -> CambioListo(r.administrar, r.seccion, enfermedadId, aviso = null) },
        )
    }

    /**
     * RF-44: crea (o reutiliza) la enfermedad con el nombre normalizado y la suma a la lista; con el paciente de la
     * consulta, la marca. Un nombre que no sirve (RF-43) no se envía y se muestra su texto.
     */
    fun crear(nombre: String) {
        if (!puedeCambiarLista() || _administrar.value.enCamino) return
        val normalizado = nombreNormalizado(nombre)
        errorDeNombreEnfermedad(normalizado)?.let { error ->
            _administrar.update { it.copy(aviso = error) }
            return
        }
        cambiarLista(
            nombre = normalizado,
            pedir = { ficha -> repo.pedirCrear(medicoId, normalizado, ficha) },
            leer = { r, motivoLocal -> CambioListo(r.administrar, r.seccion, r.resultado.enfermedad.id, avisoCrear(r.resultado, motivoLocal)) },
        )
    }

    /** Lo que quedó de un cambio de la lista que la web confirmó. */
    private class CambioListo(
        val administrar: EstadoAdministrar,
        val seccion: EstadoSeccionEnfermedades?,
        val enfermedadId: Long,
        val aviso: String?,
    )

    /**
     * Un cambio de la lista por vez (RF-45). Con el paciente de la consulta vinculado y en la cartera (y la consulta
     * editable) el pedido va con su ficha y la respuesta trae la sección, que pasa por las mismas secuencias que los
     * toques (RF-23, RF-40, RF-44, RF-48); si no, sin ficha (RF-58).
     */
    private fun <T> cambiarLista(
        nombre: String,
        pedir: (fichaId: Long?) -> Deferred<ResultadoPedido<T>>,
        leer: (T, MotivoSinMarcar?) -> CambioListo,
    ) {
        val deEstaVentana = ventana.value
        var tomado = false
        _administrar.update { tomado = !it.enCamino; if (tomado) it.copy(enCamino = true, aviso = null) else it }
        if (!tomado) return
        scope.launch {
            val e = _estado.value
            val paciente = pacienteParaCrear(e.fichaId, e.motivo, editable())
            val ficha = (paciente as? PacienteCrear.ConFicha)?.fichaId
            val motivoLocal = (paciente as? PacienteCrear.SinMarcar)?.motivo
            val secuencia = ficha?.let { reservarSecuencia() }
            when (val r = repo.esperar(pedir(ficha))) {
                is ResultadoPedido.Ok -> {
                    val cambio = leer(r.valor, motivoLocal)
                    // Si se cerró la ventana mientras tanto, la respuesta no la reescribe (la sección sí se actualiza).
                    _administrar.update {
                        if (deEstaVentana != ventana.value) it.copy(enCamino = false)
                        else it.copy(estado = cambio.administrar, enCamino = false, aviso = cambio.aviso)
                    }
                    if (cambio.seccion != null && secuencia != null) marcar { it.confirmado(secuencia, cambio.enfermedadId, cambio.seccion) }
                }
                is ResultadoPedido.Error -> cambioRechazado(nombre, r.error, deEstaVentana)
                ResultadoPedido.SinRespuesta -> cambioRechazado(nombre, errorSinRespuesta(), deEstaVentana)
            }
        }
    }

    /**
     * RF-46: no se muestra como hecho; el motivo (los de RF-25 a RF-28) y lo escrito se conserva. Sin conexión vuelve a
     * pedir administrar y muestra ese, con el aviso de que no se pudo confirmar. Con la ventana ya cerrada solo libera
     * el "en camino".
     */
    private suspend fun cambioRechazado(nombre: String, error: TobbException, deEstaVentana: Int) {
        if (deEstaVentana != ventana.value) {
            _administrar.update { it.copy(enCamino = false) }
            return
        }
        val sinConexion = esSinConexion(error)
        val aviso = when {
            sinConexion -> textoSinConfirmar(nombre)
            error.status == 429 -> TEXTO_DEMASIADOS_CAMBIOS
            error.codigo == "no_en_tu_lista" -> textoYaNoEnTuLista(nombre)
            error.codigo == "enfermedad_dada_de_baja" -> textoDadaDeBaja(nombre)
            error.codigo == "nombre_invalido" -> error.message ?: TEXTO_NO_SE_PUDO_GUARDAR
            else -> motivoDeError(error, nombreHc).let { if (it.prioridad == 5) TEXTO_NO_SE_PUDO_GUARDAR else it.texto }
        }
        // Con el cambio sin confirmar sigue en camino hasta saber cómo quedó la lista: nada de volver a tocarla antes.
        _administrar.update { it.copy(aviso = aviso, enCamino = sinConexion) }
        if (!sinConexion) return
        val nueva = (repo.esperar(repo.pedirAdministrar(medicoId)) as? ResultadoPedido.Ok)?.valor
        _administrar.update { if (deEstaVentana != ventana.value) it.copy(enCamino = false) else it.copy(estado = nueva ?: it.estado, enCamino = false) }
    }

    private fun nombreDe(enfermedadId: Long): String =
        _administrar.value.estado?.let { (it.lista + it.catalogo).firstOrNull { e -> e.id == enfermedadId }?.nombre }.orEmpty()

    // ---------- errores ----------

    private fun esSinConexion(e: TobbException) = e.codigo == "red" || (e.status == 0 && e.codigo == null)

    private fun errorSinRespuesta() = TobbException(0, "red", "Sin respuesta de la historia clínica general")

    private fun errorNoLeido(e: Throwable) =
        TobbException(0, "respuesta", "No se pudo leer la respuesta de enfermedades: ${e.message ?: "error"}")

    private fun textoDe(error: TobbException): String = motivoDeError(error, nombreHc).texto
}
