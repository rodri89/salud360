package com.salud360.core.data.repos

import com.salud360.core.data.hcgeneral.AvisoEnfermedades
import com.salud360.core.data.hcgeneral.EnCamino
import com.salud360.core.data.hcgeneral.Enfermedad
import com.salud360.core.data.hcgeneral.EstadoMarcado
import com.salud360.core.data.hcgeneral.EstadoSeccionEnfermedades
import com.salud360.core.data.hcgeneral.Marcable
import com.salud360.core.data.hcgeneral.MotivoEnfermedades
import com.salud360.core.data.hcgeneral.MotivoHc
import com.salud360.core.data.hcgeneral.TEXTO_DEMASIADOS_CAMBIOS
import com.salud360.core.data.hcgeneral.VistaSeccionEnfermedades
import com.salud360.core.data.hcgeneral.puedeAdministrar
import com.salud360.core.data.network.hc.HcPacienteCandidato
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Qué muestra la sección "Enfermedades" según el estado del controlador (spec 003, RF-9 a RF-14, RF-18, RF-19, RF-21,
 * RF-24, RF-28 a RF-31, RF-35, RF-37; P1-A, P2-B, P8-A). Todo a mano, sin coroutines: `SeccionEnfermedadesUi` es un
 * dato. Pacientes y enfermedades inventados.
 */
class PanelEnfermedadesTest {

    private val asma = Marcable(1, "Asma", false)
    private val gota = Marcable(2, "Gota", true)
    private val epilepsia = Enfermedad(30, "Epilepsia")
    private val estado = EstadoSeccionEnfermedades(marcables = listOf(gota, asma), otras = listOf(epilepsia))

    private val candidata = HcPacienteCandidato(id = 5, nombre = "Prueba", apellido = "Salud360", dni = "99000001")

    /** Un motivo por cada lugar de RF-14. */
    private fun general(tipo: MotivoHc.Tipo, texto: String) = MotivoEnfermedades.General(MotivoHc(tipo, texto))
    private val motivos: Map<Int, MotivoEnfermedades> = mapOf(
        1 to general(MotivoHc.Tipo.SESION_VENCIDA, "sesión vencida"),
        2 to general(MotivoHc.Tipo.HC_NO_HABILITADA, "no habilitada"),
        3 to general(MotivoHc.Tipo.MEDICO_NO_VINCULADO, "médico no vinculado"),
        4 to general(MotivoHc.Tipo.LICENCIA_VENCIDA, "licencia vencida"),
        5 to general(MotivoHc.Tipo.OTRO, "la web respondió algo raro"),
        6 to MotivoEnfermedades.ElegirFicha(listOf(candidata)),
        7 to MotivoEnfermedades.SinFicha("Corregí el documento en la ficha del paciente."),
        8 to MotivoEnfermedades.FichaNoDisponible,
        9 to MotivoEnfermedades.FueraDeCartera,
    )

    /** Con la sección cargada y editable por defecto; cada test cambia solo lo que prueba. */
    private fun ui(
        cargando: Boolean = false,
        marcado: EstadoMarcado = EstadoMarcado(confirmado = estado),
        motivo: MotivoEnfermedades? = null,
        textoSinCopia: String? = null,
        avisoSoloLectura: String? = null,
    ) = SeccionEnfermedadesUi(
        cargando = cargando,
        marcado = marcado,
        motivo = motivo,
        textoSinCopia = textoSinCopia,
        avisoSoloLectura = avisoSoloLectura,
        fichaId = 10,
    )

    /** Sin conexión con la copia (RF-28, RF-30): lo último confirmado, en solo lectura y con el aviso. */
    private fun sinConexionConCopia(
        cargando: Boolean = false,
        aviso: String = AVISO_SIN_CONEXION,
        marcado: EstadoMarcado = EstadoMarcado(confirmado = estado, soloLectura = true),
    ) = ui(cargando = cargando, marcado = marcado, avisoSoloLectura = aviso)

    // ---------- textos de la spec ----------

    @Test
    fun los_textos_son_los_de_la_spec() {
        assertEquals("Guardando…", TEXTO_GUARDANDO) // RF-21
        assertEquals("Guardado", TEXTO_GUARDADO) // RF-24
        assertEquals("Reintentar", TEXTO_REINTENTAR) // RF-30
        assertEquals("Administrar enfermedades", TEXTO_ADMINISTRAR) // RF-18, RF-37
        assertEquals("Sin conexión: las enfermedades se pueden ver pero no cambiar", AVISO_SIN_CONEXION) // RF-30
        assertEquals("Hace falta conexión para ver las enfermedades", TEXTO_HACE_FALTA_CONEXION) // RF-31
    }

    // ---------- 1. Cargando ----------

    @Test
    fun recien_abierta_y_sin_nada_que_mostrar_esta_cargando() {
        assertEquals(PanelEnfermedades.Cargando, panelDeSeccion(SeccionEnfermedadesUi(), editable = true))
        assertEquals(PanelEnfermedades.Cargando, panelDeSeccion(SeccionEnfermedadesUi(), editable = false))
    }

    @Test
    fun cargando_con_algo_que_mostrar_no_es_el_caso_cargando() {
        // "Reintentar" o "Elegir" vuelven a cargar con lo anterior a la vista: sigue lo que había, sin botones de reintento.
        val conVista = panelDeSeccion(ui(cargando = true), editable = true)
        assertIs<PanelEnfermedades.Contenido>(conVista)
        val conMotivo = panelDeSeccion(ui(cargando = true, motivo = motivos.getValue(9)), editable = true)
        assertIs<PanelEnfermedades.Motivo>(conMotivo)
        val sinCopia = panelDeSeccion(ui(cargando = true, textoSinCopia = TEXTO_HACE_FALTA_CONEXION), editable = true)
        assertIs<PanelEnfermedades.SinCopia>(sinCopia)
    }

    // ---------- 2. Elegir ficha (RF-9) ----------

    @Test
    fun elegir_ficha_lleva_las_candidatas_y_se_puede_elegir() {
        val p = panelDeSeccion(ui(marcado = EstadoMarcado(), motivo = motivos.getValue(6)), editable = true)
        assertIs<PanelEnfermedades.ElegirFicha>(p)
        assertEquals(listOf(candidata), p.candidatas)
        assertTrue(p.habilitado)
    }

    @Test
    fun elegir_ficha_no_se_puede_tocar_mientras_carga() {
        val p = panelDeSeccion(ui(cargando = true, marcado = EstadoMarcado(), motivo = motivos.getValue(6)), editable = true)
        assertIs<PanelEnfermedades.ElegirFicha>(p)
        assertFalse(p.habilitado)
    }

    @Test
    fun elegir_ficha_deja_administrar_en_consulta_abierta_con_conexion_y_no_en_las_demas() {
        fun administrar(editable: Boolean, cargando: Boolean = false) = (panelDeSeccion(
            ui(cargando = cargando, marcado = EstadoMarcado(), motivo = motivos.getValue(6)),
            editable,
        ) as PanelEnfermedades.ElegirFicha).administrar
        assertTrue(administrar(editable = true)) // RF-37: aunque se muestre un motivo de RF-9 a RF-12
        assertFalse(administrar(editable = false))
        assertFalse(administrar(editable = true, cargando = true))
    }

    // ---------- 3. Motivo (RF-10 a RF-13) ----------

    @Test
    fun cada_motivo_muestra_su_texto_y_no_las_casillas() {
        for ((lugar, motivo) in motivos) {
            if (lugar == 6) continue // tiene su propio caso
            val p = panelDeSeccion(ui(marcado = EstadoMarcado(), motivo = motivo), editable = true)
            assertIs<PanelEnfermedades.Motivo>(p, "motivo $lugar")
            assertEquals(motivo.texto, p.texto, "motivo $lugar")
        }
    }

    @Test
    fun los_motivos_no_muestran_las_enfermedades_ni_las_de_la_copia() {
        // Con el estado cargado debajo, el motivo gana: RF-11 no muestra enfermedades, tampoco las de la copia.
        for (lugar in listOf(1, 2, 3, 4, 7, 8, 9)) {
            val p = panelDeSeccion(ui(motivo = motivos.getValue(lugar)), editable = true)
            assertIs<PanelEnfermedades.Motivo>(p, "motivo $lugar")
        }
    }

    @Test
    fun administrar_por_motivo_si_con_7_a_9_y_no_con_1_a_4_RF_37() {
        val esperado = mapOf(1 to false, 2 to false, 3 to false, 4 to false, 5 to false, 7 to true, 8 to true, 9 to true)
        for ((lugar, administrar) in esperado) {
            val p = panelDeSeccion(ui(marcado = EstadoMarcado(), motivo = motivos.getValue(lugar)), editable = true)
            assertIs<PanelEnfermedades.Motivo>(p, "motivo $lugar")
            assertEquals(administrar, p.administrar, "administrar con el motivo $lugar")
        }
    }

    @Test
    fun administrar_con_motivo_no_con_la_consulta_cerrada() {
        for (lugar in listOf(7, 8, 9)) {
            val p = panelDeSeccion(ui(marcado = EstadoMarcado(), motivo = motivos.getValue(lugar)), editable = false)
            assertIs<PanelEnfermedades.Motivo>(p, "motivo $lugar")
            assertFalse(p.administrar, "cerrada, motivo $lugar")
        }
    }

    @Test
    fun reintentar_por_motivo_P2_B_si_con_7_8_y_9_no_con_6_ni_con_1_a_4() {
        val esperado = mapOf(1 to false, 2 to false, 3 to false, 4 to false, 7 to true, 8 to true, 9 to true)
        for ((lugar, reintentar) in esperado) {
            val p = panelDeSeccion(ui(marcado = EstadoMarcado(), motivo = motivos.getValue(lugar)), editable = true)
            assertIs<PanelEnfermedades.Motivo>(p, "motivo $lugar")
            assertEquals(reintentar, p.reintentar, "reintentar con el motivo $lugar")
        }
        // El 6 no es un `Motivo`: es `ElegirFicha`, que no tiene "Reintentar" (ya tiene "Elegir").
        val seis = panelDeSeccion(ui(marcado = EstadoMarcado(), motivo = motivos.getValue(6)), editable = true)
        assertIs<PanelEnfermedades.ElegirFicha>(seis)
    }

    @Test
    fun el_motivo_5_si_llegara_al_caso_motivo_lleva_reintentar() {
        // El repositorio no lo manda como motivo (es SoloLectura o SinCopia), pero si llegara, se sale con "Reintentar".
        val p = panelDeSeccion(ui(marcado = EstadoMarcado(), motivo = motivos.getValue(5)), editable = true)
        assertIs<PanelEnfermedades.Motivo>(p)
        assertEquals(motivos.getValue(5).texto, p.texto)
        assertTrue(p.reintentar)
        assertFalse(p.administrar) // RF-30, RF-37: sin conexión o error de la web no se administra
    }

    @Test
    fun reintentar_se_apaga_mientras_carga() {
        for (lugar in listOf(5, 7, 8, 9)) {
            val p = panelDeSeccion(ui(cargando = true, marcado = EstadoMarcado(), motivo = motivos.getValue(lugar)), editable = true)
            assertIs<PanelEnfermedades.Motivo>(p, "motivo $lugar")
            assertFalse(p.reintentar, "cargando, motivo $lugar")
            assertFalse(p.administrar, "cargando, motivo $lugar") // no hay conexión confirmada mientras se reintenta
        }
    }

    @Test
    fun el_rechazo_de_un_toque_con_motivo_se_muestra_como_motivo_RF_27() {
        val aviso = AvisoEnfermedades.Motivo(motivos.getValue(4))
        val p = panelDeSeccion(ui(marcado = EstadoMarcado(confirmado = estado, aviso = aviso)), editable = true)
        assertIs<PanelEnfermedades.Motivo>(p)
        assertEquals(motivos.getValue(4).texto, p.texto)
        assertFalse(p.reintentar) // licencia: no se arregla reintentando (P2-B)
        assertFalse(p.administrar) // RF-37

        val fuera = AvisoEnfermedades.Motivo(motivos.getValue(9))
        val q = panelDeSeccion(ui(marcado = EstadoMarcado(confirmado = estado, aviso = fuera)), editable = true)
        assertIs<PanelEnfermedades.Motivo>(q)
        assertTrue(q.reintentar)
        assertTrue(q.administrar)
    }

    @Test
    fun con_mas_de_un_motivo_se_muestra_el_primero_de_RF_14() {
        // Un aviso con el motivo 2 y un motivo 6 muestran el 2 (y no es "Elegir ficha").
        val aviso = AvisoEnfermedades.Motivo(motivos.getValue(2))
        val p = panelDeSeccion(ui(marcado = EstadoMarcado(aviso = aviso), motivo = motivos.getValue(6)), editable = true)
        assertIs<PanelEnfermedades.Motivo>(p)
        assertEquals(motivos.getValue(2).texto, p.texto)

        // Al revés: el motivo cargado es el 1 y el aviso del toque el 9.
        val q = panelDeSeccion(
            ui(marcado = EstadoMarcado(aviso = AvisoEnfermedades.Motivo(motivos.getValue(9))), motivo = motivos.getValue(1)),
            editable = true,
        )
        assertIs<PanelEnfermedades.Motivo>(q)
        assertEquals(motivos.getValue(1).texto, q.texto)
    }

    @Test
    fun el_motivo_no_cambia_por_la_ventana_de_administrar_P1_A() {
        // El panel solo ve lo que trae `ui`: la misma `ui` da el mismo panel, haya o no una ventana que rechazó por 1 a 4.
        val u = ui(marcado = EstadoMarcado(confirmado = estado))
        assertEquals(panelDeSeccion(u, editable = true), panelDeSeccion(u.copy(), editable = true))
        assertIs<PanelEnfermedades.Contenido>(panelDeSeccion(u, editable = true))
    }

    // ---------- 4. Sin copia (RF-31) ----------

    @Test
    fun sin_copia_muestra_el_texto_con_reintentar_y_sin_administrar() {
        val p = panelDeSeccion(ui(marcado = EstadoMarcado(), textoSinCopia = TEXTO_HACE_FALTA_CONEXION), editable = true)
        assertIs<PanelEnfermedades.SinCopia>(p)
        assertEquals("Hace falta conexión para ver las enfermedades", p.texto)
        assertTrue(p.reintentar)
    }

    @Test
    fun sin_copia_con_un_error_de_la_web_muestra_el_texto_de_la_web() {
        val p = panelDeSeccion(ui(marcado = EstadoMarcado(), textoSinCopia = "Historia clínica de Clínica: error interno."), editable = true)
        assertIs<PanelEnfermedades.SinCopia>(p)
        assertEquals("Historia clínica de Clínica: error interno.", p.texto)
        assertTrue(p.reintentar)
    }

    @Test
    fun sin_copia_es_igual_con_la_consulta_cerrada_y_reintentar_se_apaga_mientras_carga() {
        val cerrada = panelDeSeccion(ui(marcado = EstadoMarcado(), textoSinCopia = TEXTO_HACE_FALTA_CONEXION), editable = false)
        assertEquals(PanelEnfermedades.SinCopia(TEXTO_HACE_FALTA_CONEXION, reintentar = true), cerrada)
        val cargando = panelDeSeccion(ui(cargando = true, marcado = EstadoMarcado(), textoSinCopia = TEXTO_HACE_FALTA_CONEXION), editable = true)
        assertEquals(PanelEnfermedades.SinCopia(TEXTO_HACE_FALTA_CONEXION, reintentar = false), cargando)
    }

    // ---------- 5. Contenido ----------

    @Test
    fun consulta_abierta_con_conexion_muestra_la_grilla_y_administrar() {
        val p = panelDeSeccion(ui(), editable = true)
        assertIs<PanelEnfermedades.Contenido>(p)
        val vista = assertIs<VistaSeccionEnfermedades.Grilla>(p.vista)
        assertEquals(listOf(asma, gota), vista.marcables) // RF-16: orden alfabético
        assertEquals(listOf(epilepsia), vista.otras)
        assertEquals("Otras enfermedades del paciente", vista.tituloOtras) // RF-19
        assertNull(vista.sinMarcables)
        assertNull(p.aviso)
        assertNull(p.avisoSoloLectura)
        assertFalse(p.reintentar)
        assertTrue(p.administrar)
        assertEquals(0, p.guardado)
    }

    @Test
    fun sin_marcables_muestra_el_texto_de_RF_18_con_otras_y_administrar() {
        val vacio = EstadoSeccionEnfermedades(marcables = emptyList(), otras = listOf(epilepsia))
        val p = panelDeSeccion(ui(marcado = EstadoMarcado(confirmado = vacio)), editable = true)
        assertIs<PanelEnfermedades.Contenido>(p)
        val vista = assertIs<VistaSeccionEnfermedades.Grilla>(p.vista)
        assertEquals(
            "No tenés enfermedades en tu listado. Podés agregarlas con «Administrar enfermedades».",
            vista.sinMarcables,
        )
        assertEquals(listOf(epilepsia), vista.otras)
        assertTrue(p.administrar)
    }

    @Test
    fun sin_otras_no_hay_bloque_de_otras() {
        val sinOtras = EstadoSeccionEnfermedades(marcables = listOf(asma), otras = emptyList())
        val p = panelDeSeccion(ui(marcado = EstadoMarcado(confirmado = sinOtras)), editable = true)
        assertIs<PanelEnfermedades.Contenido>(p)
        assertNull(assertIs<VistaSeccionEnfermedades.Grilla>(p.vista).tituloOtras)
    }

    @Test
    fun la_grilla_muestra_lo_elegido_de_las_casillas_en_camino() {
        val enCamino = mapOf(asma.id to EnCamino(tiene = true, secuencia = 3, nombre = asma.nombre))
        val u = ui(marcado = EstadoMarcado(confirmado = estado, enCamino = enCamino))
        val p = panelDeSeccion(u, editable = true)
        assertIs<PanelEnfermedades.Contenido>(p)
        val vista = assertIs<VistaSeccionEnfermedades.Grilla>(p.vista)
        assertTrue(vista.marcables.first { it.id == asma.id }.tiene) // RF-21: lo elegido
        assertTrue(u.marcado.guardando(asma.id)) // la pantalla pone TEXTO_GUARDANDO con esto
        assertFalse(u.marcado.puedeTocar(asma.id)) // RF-22
        assertTrue(u.marcado.puedeTocar(gota.id))
    }

    @Test
    fun guardado_es_el_contador_del_marcado() {
        val p = panelDeSeccion(ui(marcado = EstadoMarcado(confirmado = estado, guardado = 4)), editable = true)
        assertIs<PanelEnfermedades.Contenido>(p)
        assertEquals(4, p.guardado) // RF-24
    }

    @Test
    fun el_ultimo_rechazo_es_el_aviso_y_la_grilla_sigue() {
        val aviso = AvisoEnfermedades.Rechazo(TEXTO_DEMASIADOS_CAMBIOS)
        val p = panelDeSeccion(ui(marcado = EstadoMarcado(confirmado = estado, aviso = aviso)), editable = true)
        assertIs<PanelEnfermedades.Contenido>(p)
        assertEquals(TEXTO_DEMASIADOS_CAMBIOS, p.aviso) // RF-29
        assertIs<VistaSeccionEnfermedades.Grilla>(p.vista)
        assertTrue(p.administrar)
        assertFalse(p.reintentar)
    }

    @Test
    fun el_rechazo_tambien_se_ve_en_la_consulta_cerrada() {
        val aviso = AvisoEnfermedades.Rechazo(TEXTO_DEMASIADOS_CAMBIOS)
        val p = panelDeSeccion(ui(marcado = EstadoMarcado(confirmado = estado, aviso = aviso)), editable = false)
        assertIs<PanelEnfermedades.Contenido>(p)
        assertEquals(TEXTO_DEMASIADOS_CAMBIOS, p.aviso)
    }

    // ---------- consulta cerrada o ajena (RF-35) ----------

    @Test
    fun consulta_cerrada_muestra_el_estado_actual_sin_administrar() {
        val p = panelDeSeccion(ui(), editable = false)
        assertIs<PanelEnfermedades.Contenido>(p)
        val vista = assertIs<VistaSeccionEnfermedades.EstadoActual>(p.vista)
        assertEquals(listOf("Epilepsia", "Gota"), vista.enfermedades.map { it.nombre }) // marcadas + otras, ordenadas
        assertEquals("Estado actual del paciente", vista.leyenda)
        assertFalse(p.administrar)
        assertFalse(p.reintentar)
    }

    @Test
    fun consulta_cerrada_sin_ninguna_dice_sin_enfermedades_registradas() {
        val ninguna = EstadoSeccionEnfermedades(marcables = listOf(asma), otras = emptyList())
        val p = panelDeSeccion(ui(marcado = EstadoMarcado(confirmado = ninguna)), editable = false)
        assertIs<PanelEnfermedades.Contenido>(p)
        assertEquals("Sin enfermedades registradas", assertIs<VistaSeccionEnfermedades.EstadoActual>(p.vista).vacio)
    }

    @Test
    fun cerrada_con_copia_y_aviso_muestra_el_estado_actual_con_reintentar_y_sin_administrar() {
        val p = panelDeSeccion(sinConexionConCopia(), editable = false)
        assertIs<PanelEnfermedades.Contenido>(p)
        assertIs<VistaSeccionEnfermedades.EstadoActual>(p.vista)
        assertEquals(AVISO_SIN_CONEXION, p.avisoSoloLectura)
        assertTrue(p.reintentar)
        assertFalse(p.administrar)
    }

    // ---------- sin conexión con la copia (RF-28, RF-30, P8-A) ----------

    @Test
    fun editable_sin_conexion_con_copia_sigue_en_la_grilla_con_casillas_no_tocables_P8_A() {
        val u = sinConexionConCopia()
        val p = panelDeSeccion(u, editable = true)
        assertIs<PanelEnfermedades.Contenido>(p)
        val vista = assertIs<VistaSeccionEnfermedades.Grilla>(p.vista) // no pasa a "Estado actual del paciente"
        assertEquals(listOf(asma, gota), vista.marcables)
        assertEquals(listOf(epilepsia), vista.otras)
        assertTrue(vista.marcables.isNotEmpty() && vista.marcables.none { u.marcado.puedeTocar(it.id) }) // no tocables
        assertEquals(AVISO_SIN_CONEXION, p.avisoSoloLectura)
        assertTrue(p.reintentar)
        assertFalse(p.administrar) // RF-30: ni abrir "Administrar enfermedades"
    }

    @Test
    fun solo_lectura_con_el_texto_de_la_web_usa_ese_texto_en_lugar_del_aviso() {
        val texto = "Historia clínica de Clínica: error interno."
        val p = panelDeSeccion(sinConexionConCopia(aviso = texto), editable = true)
        assertIs<PanelEnfermedades.Contenido>(p)
        assertEquals(texto, p.avisoSoloLectura) // RF-30
        assertTrue(p.reintentar)
    }

    @Test
    fun solo_lectura_tras_un_toque_sin_respuesta_conserva_el_aviso_de_RF_28() {
        val sinConfirmar = AvisoEnfermedades.Rechazo("No se pudo confirmar el cambio en «Asma»: puede haberse guardado.")
        val marcado = EstadoMarcado(confirmado = estado, soloLectura = true, aviso = sinConfirmar)
        val p = panelDeSeccion(sinConexionConCopia(marcado = marcado), editable = true)
        assertIs<PanelEnfermedades.Contenido>(p)
        assertEquals(sinConfirmar.texto, p.aviso) // el último rechazo
        assertEquals(AVISO_SIN_CONEXION, p.avisoSoloLectura)
        assertIs<VistaSeccionEnfermedades.Grilla>(p.vista)
        assertTrue(p.reintentar)
        assertFalse(p.administrar)
    }

    @Test
    fun reintentar_se_apaga_mientras_carga_en_solo_lectura() {
        val p = panelDeSeccion(sinConexionConCopia(cargando = true), editable = true)
        assertIs<PanelEnfermedades.Contenido>(p)
        assertFalse(p.reintentar)
        assertEquals(AVISO_SIN_CONEXION, p.avisoSoloLectura) // el aviso sigue hasta que llegue la respuesta
        assertIs<VistaSeccionEnfermedades.Grilla>(p.vista)
    }

    // ---------- administrar (RF-37) ----------

    @Test
    fun administrar_solo_con_consulta_abierta_propia_y_con_conexion() {
        // editable × (con conexión | sin conexión | cargando) × (con y sin copia)
        assertTrue((panelDeSeccion(ui(), editable = true) as PanelEnfermedades.Contenido).administrar)
        assertFalse((panelDeSeccion(ui(), editable = false) as PanelEnfermedades.Contenido).administrar)
        assertFalse((panelDeSeccion(sinConexionConCopia(), editable = true) as PanelEnfermedades.Contenido).administrar)
        assertFalse((panelDeSeccion(sinConexionConCopia(), editable = false) as PanelEnfermedades.Contenido).administrar)
        assertFalse((panelDeSeccion(ui(cargando = true), editable = true) as PanelEnfermedades.Contenido).administrar)
    }

    @Test
    fun el_panel_coincide_con_puedeAdministrar_del_controlador() {
        // El controlador decide con `puedeAdministrar(editable, true, ui.conConexion, ui.motivoMostrado)`: el panel, igual.
        val casos = listOf(
            ui(),
            ui(cargando = true),
            sinConexionConCopia(),
            ui(marcado = EstadoMarcado(), textoSinCopia = TEXTO_HACE_FALTA_CONEXION),
        ) + motivos.values.map { ui(marcado = EstadoMarcado(), motivo = it) }
        for (u in casos) for (editable in listOf(true, false)) {
            val esperado = puedeAdministrar(editable, true, u.conConexion, u.motivoMostrado)
            val administrar = when (val p = panelDeSeccion(u, editable)) {
                is PanelEnfermedades.Contenido -> p.administrar
                is PanelEnfermedades.Motivo -> p.administrar
                is PanelEnfermedades.ElegirFicha -> p.administrar
                PanelEnfermedades.Cargando, is PanelEnfermedades.SinCopia -> false
            }
            assertEquals(esperado, administrar, "motivo ${u.motivoMostrado?.prioridad}, editable $editable, cargando ${u.cargando}")
        }
    }
}
