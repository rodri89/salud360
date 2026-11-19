package com.salud360.core.data.hcgeneral

import com.salud360.core.data.network.TobbException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Marcar y desmarcar en la sección "Enfermedades" (spec 003, RF-21 a RF-29, D9): secuencias de envío, respuestas
 * desordenadas, casilla bloqueada en camino, rechazos, sin respuesta y "Guardado". Enfermedades inventadas.
 */
class MarcadoEnfermedadesTest {

    private val nombreHc = "Clínica"

    /** Asma (1), Diabetes (2) e Hipertensión (3); [marcadas] = las que el paciente tiene según la web. */
    private fun seccion(vararg marcadas: Long) = EstadoSeccionEnfermedades(
        marcables = listOf(
            Marcable(1, "Asma", 1L in marcadas),
            Marcable(2, "Diabetes", 2L in marcadas),
            Marcable(3, "Hipertensión", 3L in marcadas),
        ),
        otras = emptyList(),
    )

    private val inicial = EstadoMarcado(confirmado = seccion(3))

    private fun EstadoMarcado.tieneEnVista(id: Long) = vista!!.marcables.first { it.id == id }.tiene

    private fun EstadoMarcado.tieneConfirmado(id: Long) = confirmado!!.marcables.first { it.id == id }.tiene

    private fun error(status: Int, codigo: String?, mensaje: String = "Mensaje de la web") =
        TobbException(status, codigo, mensaje)

    // RF-21, RF-22

    @Test
    fun tocar_muestra_lo_elegido_guardando_y_lo_manda() {
        val paso = inicial.tocar(1, true)
        assertEquals(PedidoMarcado.Marcar(1, 1, true), paso.pedido)
        assertTrue(paso.estado.tieneEnVista(1))
        assertTrue(paso.estado.guardando(1))
        assertFalse(paso.estado.tieneConfirmado(1))
        assertFalse(paso.estado.guardando(2))
        // Desmarcar manda el estado elegido, no "invertir".
        assertEquals(PedidoMarcado.Marcar(2, 3, false), paso.estado.tocar(3, false).pedido)
    }

    @Test
    fun la_casilla_en_camino_no_se_toca_y_las_demas_si() {
        val enCamino = inicial.tocar(1, true).estado
        assertFalse(enCamino.puedeTocar(1))
        val otraVez = enCamino.tocar(1, false)
        assertNull(otraVez.pedido)
        assertSame(enCamino, otraVez.estado)

        assertTrue(enCamino.puedeTocar(2))
        val otra = enCamino.tocar(2, true)
        assertEquals(PedidoMarcado.Marcar(2, 2, true), otra.pedido)
        assertTrue(otra.estado.tieneEnVista(1))
        assertTrue(otra.estado.tieneEnVista(2))
    }

    @Test
    fun no_se_toca_una_que_no_es_marcable_ni_sin_estado() {
        assertNull(inicial.tocar(99, true).pedido)
        assertNull(EstadoMarcado().tocar(1, true).pedido)
    }

    @Test
    fun las_secuencias_crecen_entre_toques_y_pedidos_de_estado() {
        val (conEstado, pedido) = inicial.pedirEstado()
        assertEquals(PedidoMarcado.PedirEstado(1), pedido)
        assertEquals(PedidoMarcado.Marcar(2, 1, true), conEstado.tocar(1, true).pedido)
        val (conSecuencia, secuencia) = conEstado.siguienteSecuencia()
        assertEquals(2L, secuencia)
        assertEquals(2L, conSecuencia.ultimaEnviada)
    }

    // RF-23

    @Test
    fun respuestas_al_reves_la_vieja_se_descarta_y_deja_su_casilla_con_lo_elegido() {
        // Asma (1) y después Diabetes (2); la web procesa primero Diabetes y su respuesta todavía no tiene Asma.
        val dos = inicial.tocar(1, true).estado.tocar(2, true).estado
        val trasDiabetes = dos.confirmado(2, 2, seccion(2, 3)).estado
        assertEquals(2L, trasDiabetes.ultimaAplicada)
        assertTrue(trasDiabetes.tieneEnVista(1)) // Asma sigue en camino: conserva lo elegido
        assertTrue(trasDiabetes.guardando(1))
        assertTrue(trasDiabetes.tieneEnVista(2))

        // Llega la de Asma (secuencia 1, vieja): no pisa el estado más nuevo, pero su casilla queda marcada.
        val trasAsma = trasDiabetes.confirmado(1, 1, seccion(1, 3)).estado
        assertEquals(2L, trasAsma.ultimaAplicada)
        assertFalse(trasAsma.guardando(1))
        assertTrue(trasAsma.tieneEnVista(1))
        assertTrue(trasAsma.tieneConfirmado(1))
        assertTrue(trasAsma.tieneEnVista(2)) // la vieja decía Diabetes sin marcar: descartado
        assertTrue(trasAsma.tieneEnVista(3))
    }

    @Test
    fun al_aplicar_una_respuesta_las_que_siguen_en_camino_conservan_lo_elegido() {
        val dos = inicial.tocar(1, true).estado.tocar(3, false).estado
        val trasAsma = dos.confirmado(1, 1, seccion(1, 3)).estado // todavía con Hipertensión marcada
        assertTrue(trasAsma.tieneConfirmado(3))
        assertFalse(trasAsma.tieneEnVista(3))
        assertTrue(trasAsma.guardando(3))
        assertFalse(trasAsma.puedeTocar(3))
        assertTrue(trasAsma.puedeTocar(1))
    }

    @Test
    fun un_estado_pedido_viejo_se_descarta() {
        val (pidio, pedido) = inicial.pedirEstado()
        val trasToque = pidio.tocar(1, true).estado.confirmado(2, 1, seccion(1, 3)).estado
        val viejo = trasToque.estadoPedido(pedido!!.secuencia, seccion())
        assertSame(trasToque, viejo.estado)
        assertTrue(viejo.estado.tieneEnVista(1))
    }

    // RF-24, RF-29

    @Test
    fun guardado_sube_con_cada_confirmacion() {
        val dos = inicial.tocar(1, true).estado.tocar(2, true).estado
        assertEquals(0, dos.guardado)
        val una = dos.confirmado(2, 2, seccion(2, 3)).estado
        assertEquals(1, una.guardado)
        // También la confirmación descartada por vieja: la web confirmó el cambio.
        assertEquals(2, una.confirmado(1, 1, seccion(1, 3)).estado.guardado)
    }

    @Test
    fun un_solo_aviso_el_ultimo_que_se_limpia_con_la_siguiente_confirmacion() {
        val tres = inicial.tocar(1, true).estado.tocar(2, true).estado.tocar(3, false).estado
        val con429 = tres.rechazado(1, 1, error(429, null, "Too Many Attempts."), nombreHc).estado
        assertEquals(AvisoEnfermedades.Rechazo("Demasiados cambios seguidos, esperá un momento"), con429.aviso)
        val con500 = con429.rechazado(2, 2, error(500, null, "Error interno"), nombreHc).estado
        assertEquals(AvisoEnfermedades.Rechazo("No se pudo guardar el cambio. Probá de nuevo."), con500.aviso)
        val confirmada = con500.confirmado(3, 3, seccion()).estado
        assertNull(confirmada.aviso)
        assertEquals(1, confirmada.guardado)
    }

    // RF-25

    @Test
    fun ya_no_esta_en_tu_lista_avisa_con_el_nombre_y_pide_el_estado() {
        val enCamino = inicial.tocar(1, true).estado
        val paso = enCamino.rechazado(1, 1, error(422, "no_en_tu_lista"), nombreHc)
        assertEquals(AvisoEnfermedades.Rechazo("«Asma» ya no está en tu lista"), paso.estado.aviso)
        assertEquals(PedidoMarcado.PedirEstado(2), paso.pedido)
        assertFalse(paso.estado.guardando(1))
        assertTrue(paso.estado.puedeTocar(1))

        // El estado nuevo ya no la trae: se muestra ese y el aviso sigue (no es una confirmación).
        val sinAsma = EstadoSeccionEnfermedades(listOf(Marcable(2, "Diabetes", false), Marcable(3, "Hipertensión", true)), emptyList())
        val trasEstado = paso.estado.estadoPedido(2, sinAsma).estado
        assertEquals(sinAsma, trasEstado.vista)
        assertEquals(paso.estado.aviso, trasEstado.aviso)
        assertEquals(0, trasEstado.guardado)
    }

    @Test
    fun dada_de_baja_avisa_con_el_nombre_aunque_ya_no_este_en_el_estado() {
        val enCamino = inicial.tocar(2, true).estado
        // Una respuesta más nueva ya la sacó de las marcables antes del rechazo.
        val sinDiabetes = EstadoSeccionEnfermedades(listOf(Marcable(1, "Asma", false)), listOf(Enfermedad(2, "Diabetes")))
        val (pidio, pedido) = enCamino.pedirEstado()
        val aplicado = pidio.estadoPedido(pedido!!.secuencia, sinDiabetes).estado
        val paso = aplicado.rechazado(1, 2, error(422, "enfermedad_dada_de_baja"), nombreHc)
        assertEquals(AvisoEnfermedades.Rechazo("«Diabetes» se dio de baja en el catálogo"), paso.estado.aviso)
        assertEquals(PedidoMarcado.PedirEstado(3), paso.pedido)
    }

    // RF-26

    @Test
    fun demasiados_pedidos_revierte_y_avisa_sin_pedir_nada() {
        val enCamino = inicial.tocar(3, false).estado
        val paso = enCamino.rechazado(1, 3, error(429, null, "Too Many Attempts."), nombreHc)
        assertNull(paso.pedido)
        assertTrue(paso.estado.tieneEnVista(3))
        assertFalse(paso.estado.guardando(3))
        assertEquals("Demasiados cambios seguidos, esperá un momento", paso.estado.aviso!!.texto)
    }

    // RF-27

    @Test
    fun los_motivos_de_rf_11_a_rf_13_revierten_y_se_aplican() {
        val enCamino = inicial.tocar(1, true).estado
        val casos = listOf(
            error(403, "licencia_vencida") to motivoDeError(error(403, "licencia_vencida"), nombreHc),
            error(401, "token") to motivoDeError(error(401, "token"), nombreHc),
            error(403, "hc_no_habilitada") to motivoDeError(error(403, "hc_no_habilitada"), nombreHc),
            error(403, "medico_no_vinculado") to motivoDeError(error(403, "medico_no_vinculado"), nombreHc),
            error(403, "fuera_de_cartera") to MotivoEnfermedades.FueraDeCartera,
            error(404, "paciente_no_encontrado") to MotivoEnfermedades.FichaNoDisponible,
        )
        for ((e, motivo) in casos) {
            val paso = enCamino.rechazado(1, 1, e, nombreHc)
            assertNull(paso.pedido, e.codigo)
            assertFalse(paso.estado.tieneEnVista(1), e.codigo)
            assertFalse(paso.estado.guardando(1), e.codigo)
            assertEquals(AvisoEnfermedades.Motivo(motivo), paso.estado.aviso, e.codigo)
        }
        // Mismo texto que el resto de la HC general (RF-13).
        assertEquals(
            motivoHc(nombreHc, error(403, "licencia_vencida")).texto,
            enCamino.rechazado(1, 1, error(403, "licencia_vencida"), nombreHc).estado.aviso!!.texto,
        )
    }

    @Test
    fun un_error_no_previsto_revierte_con_su_texto() {
        val enCamino = inicial.tocar(3, false).estado
        val errores = listOf(
            error(500, null, "Error interno"),
            error(500, "enfermedades", "No se pudo"),
            error(503, "ocupado", "Ocupado"),
            error(0, "respuesta", "No se pudo leer"),
            error(503, "tobb_caido", "turnosonlinebb no responde"), // la web contestó: no se guardó
            error(422, "datos_invalidos", "Faltan datos"),
        )
        for (e in errores) {
            val paso = enCamino.rechazado(1, 3, e, nombreHc)
            assertNull(paso.pedido, e.codigo)
            assertTrue(paso.estado.tieneEnVista(3), e.codigo)
            assertFalse(paso.estado.guardando(3), e.codigo)
            assertEquals(AvisoEnfermedades.Rechazo("No se pudo guardar el cambio. Probá de nuevo."), paso.estado.aviso, e.codigo)
        }
    }

    @Test
    fun la_casilla_vuelve_al_ultimo_confirmado_no_al_de_antes_de_tocar() {
        // Mientras Asma estaba en camino, otra respuesta trajo a Hipertensión desmarcada desde otro lado.
        val enCamino = inicial.tocar(1, true).estado.tocar(2, true).estado
        val trasDiabetes = enCamino.confirmado(2, 2, seccion(2)).estado
        val paso = trasDiabetes.rechazado(1, 1, error(500, null), nombreHc)
        assertFalse(paso.estado.tieneEnVista(1))
        assertFalse(paso.estado.tieneEnVista(3))
        assertTrue(paso.estado.tieneEnVista(2))
    }

    @Test
    fun un_rechazo_de_otra_secuencia_o_casilla_se_ignora() {
        val enCamino = inicial.tocar(1, true).estado
        assertSame(enCamino, enCamino.rechazado(7, 1, error(500, null), nombreHc).estado)
        assertSame(enCamino, enCamino.rechazado(1, 2, error(500, null), nombreHc).estado)
    }

    // RF-28, RF-30

    @Test
    fun sin_respuesta_no_revierte_y_pide_el_estado() {
        val enCamino = inicial.tocar(1, true).estado
        val paso = enCamino.sinRespuesta(1, 1)
        assertEquals(PedidoMarcado.PedirEstado(2), paso.pedido)
        assertTrue(paso.estado.tieneEnVista(1))
        assertFalse(paso.estado.puedeTocar(1))
        assertNull(paso.estado.aviso)

        // El estado nuevo manda para esa casilla (no conserva lo elegido) y la libera.
        val trasEstado = paso.estado.estadoPedido(2, seccion(3)).estado
        assertFalse(trasEstado.tieneEnVista(1))
        assertFalse(trasEstado.guardando(1))
        assertTrue(trasEstado.puedeTocar(1))
        assertFalse(trasEstado.soloLectura)
    }

    @Test
    fun un_error_de_red_es_sin_respuesta() {
        val enCamino = inicial.tocar(1, true).estado
        for (e in listOf(error(0, "red", "Sin conexión"), error(0, null, "Sin conexión"))) {
            val paso = enCamino.rechazado(1, 1, e, nombreHc)
            assertEquals(enCamino.sinRespuesta(1, 1), paso, e.codigo)
        }
    }

    @Test
    fun sin_respuesta_mientras_otra_sigue_en_camino_esa_conserva_lo_elegido() {
        val dos = inicial.tocar(1, true).estado.tocar(2, true).estado
        val pidio = dos.sinRespuesta(1, 1).estado
        val trasEstado = pidio.estadoPedido(3, seccion(3)).estado
        assertFalse(trasEstado.tieneEnVista(1))
        assertTrue(trasEstado.tieneEnVista(2))
        assertTrue(trasEstado.guardando(2))
    }

    @Test
    fun el_estado_tampoco_responde_ultimo_confirmado_aviso_y_solo_lectura() {
        val dos = inicial.tocar(1, true).estado.tocar(2, true).estado
        val trasDiabetes = dos.confirmado(2, 2, seccion(2, 3)).estado
        val pidio = trasDiabetes.sinRespuesta(1, 1)
        val paso = pidio.estado.estadoPedidoSinRespuesta(pidio.pedido!!.secuencia)
        assertNull(paso.pedido)
        assertTrue(paso.estado.soloLectura)
        assertFalse(paso.estado.tieneEnVista(1))
        assertFalse(paso.estado.guardando(1))
        assertTrue(paso.estado.tieneEnVista(2))
        assertEquals(
            AvisoEnfermedades.Rechazo(
                "No se pudo confirmar el cambio en «Asma»: puede haberse guardado. Volvé a abrir la sección con conexión.",
            ),
            paso.estado.aviso,
        )
        // Solo lectura: no se toca nada.
        assertFalse(paso.estado.puedeTocar(3))
        assertNull(paso.estado.tocar(3, false).pedido)
    }

    @Test
    fun una_respuesta_tardia_mas_nueva_se_aplica_pero_sigue_en_solo_lectura() {
        val dos = inicial.tocar(1, true).estado.tocar(2, true).estado
        val pidio = dos.sinRespuesta(1, 1)
        val soloLectura = pidio.estado.estadoPedidoSinRespuesta(pidio.pedido!!.secuencia).estado
        assertTrue(soloLectura.guardando(2))

        // Diabetes (secuencia 2) contesta tarde, con Asma ya guardada: se aplica.
        val tarde = soloLectura.confirmado(2, 2, seccion(1, 2, 3)).estado
        assertEquals(2L, tarde.ultimaAplicada)
        assertTrue(tarde.tieneEnVista(1))
        assertTrue(tarde.tieneEnVista(2))
        assertTrue(tarde.soloLectura)
        assertFalse(tarde.puedeTocar(1))
    }

    @Test
    fun la_confirmacion_tardia_del_toque_perdido_lo_deja_como_lo_guardo_la_web() {
        val pidio = inicial.tocar(1, true).estado.sinRespuesta(1, 1)
        // El estado pedido (2) contestó antes de que la web procesara el toque: Asma sin marcar.
        val trasEstado = pidio.estado.estadoPedido(2, seccion(3)).estado
        val tarde = trasEstado.confirmado(1, 1, seccion(1, 3)).estado
        assertTrue(tarde.tieneEnVista(1))
        assertEquals(2L, tarde.ultimaAplicada)
    }

    @Test
    fun en_solo_lectura_otro_sin_respuesta_avisa_sin_pedir_el_estado() {
        val dos = inicial.tocar(1, true).estado.tocar(2, true).estado
        val pidio = dos.sinRespuesta(1, 1)
        val soloLectura = pidio.estado.estadoPedidoSinRespuesta(pidio.pedido!!.secuencia).estado
        val paso = soloLectura.sinRespuesta(2, 2)
        assertNull(paso.pedido)
        assertFalse(paso.estado.tieneEnVista(2))
        assertFalse(paso.estado.guardando(2))
        assertEquals(
            "No se pudo confirmar el cambio en «Diabetes»: puede haberse guardado. Volvé a abrir la sección con conexión.",
            paso.estado.aviso!!.texto,
        )
        // Y un rechazo de la lista tampoco pide el estado.
        val otro = inicial.tocar(3, false).estado.tocar(1, true).estado
        val pidioOtro = otro.sinRespuesta(1, 3)
        val soloLecturaOtro = pidioOtro.estado.estadoPedidoSinRespuesta(pidioOtro.pedido!!.secuencia).estado
        assertNull(soloLecturaOtro.rechazado(2, 1, error(422, "no_en_tu_lista"), nombreHc).pedido)
    }

    @Test
    fun el_estado_sin_respuesta_ya_superado_no_cambia_nada() {
        val pidio = inicial.tocar(1, true).estado.sinRespuesta(1, 1)
        val (otraVez, pedido) = pidio.estado.pedirEstado()
        val aplicado = otraVez.estadoPedido(pedido!!.secuencia, seccion(1, 3)).estado
        assertSame(aplicado, aplicado.estadoPedidoSinRespuesta(pidio.pedido!!.secuencia).estado)
        assertFalse(aplicado.soloLectura)
    }
}
