package com.salud360.core.data.repos

import com.salud360.core.data.hcgeneral.Enfermedad
import com.salud360.core.data.hcgeneral.EstadoAdministrar
import com.salud360.core.data.hcgeneral.TEXTO_ESCRIBI_PARA_BUSCAR
import com.salud360.core.data.hcgeneral.errorDeNombreEnfermedad
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Qué muestra la ventana "Administrar enfermedades" según el estado del controlador (spec 003, RF-37 a RF-47, RF-58;
 * P1-A, P3-A, P5-A). Todo a mano, sin coroutines: `AdministrarEnfermedadesUi` es un dato. Enfermedades inventadas.
 */
class PanelAdministrarTest {

    private val asma = Enfermedad(1, "Asma")
    private val gota = Enfermedad(2, "Gota")
    private val acidoUrico = Enfermedad(3, "ácido úrico")
    private val diabetes = Enfermedad(4, "Diabetes tipo 2")
    private val diabetesRepetida = Enfermedad(5, "diabetes tipo 2")
    private val epilepsia = Enfermedad(6, "Epilepsia")

    /** La lista del médico y "Del catálogo" (las activas que no están en su lista), como las manda la web. */
    private val estado = EstadoAdministrar(
        lista = listOf(gota, asma, acidoUrico),
        catalogo = listOf(epilepsia, diabetes, diabetesRepetida),
    )

    private fun ui(
        estado: EstadoAdministrar? = this.estado,
        buscado: String = "",
        cargando: Boolean = false,
        enCamino: Boolean = false,
        aviso: String? = null,
    ) = AdministrarEnfermedadesUi(estado = estado, buscado = buscado, cargando = cargando, enCamino = enCamino, aviso = aviso)

    private fun contenido(ui: AdministrarEnfermedadesUi) = assertIs<PanelAdministrar.Contenido>(panelDeAdministrar(ui))

    // ---------- cargando y falla al abrir ----------

    @Test
    fun cargando_sin_estado_es_cargando() {
        assertEquals(PanelAdministrar.Cargando, panelDeAdministrar(ui(estado = null, cargando = true)))
    }

    @Test
    fun sin_estado_ni_aviso_tampoco_hay_nada_que_mostrar_y_es_cargando() {
        // Entre abrir la ventana y que el controlador marque el pedido (y por defensa si no pudo pedir): nunca una ventana vacía.
        assertEquals(PanelAdministrar.Cargando, panelDeAdministrar(ui(estado = null)))
    }

    @Test
    fun sin_estado_y_con_aviso_es_solo_el_aviso() {
        // RF-46: falló al abrir (también con los motivos 1 a 5): solo la ventana lo muestra, con el texto que arma el controlador.
        val p = panelDeAdministrar(ui(estado = null, aviso = "Tu sesión venció. Volvé a ingresar."))
        assertEquals(PanelAdministrar.SoloAviso("Tu sesión venció. Volvé a ingresar."), p)
    }

    @Test
    fun cargando_gana_sobre_un_aviso_viejo_sin_estado() {
        assertEquals(PanelAdministrar.Cargando, panelDeAdministrar(ui(estado = null, cargando = true, aviso = "viejo")))
    }

    @Test
    fun con_estado_siempre_hay_contenido_aunque_siga_cargando() {
        // Un segundo pedido de administrar no oculta lo que ya se ve.
        assertIs<PanelAdministrar.Contenido>(panelDeAdministrar(ui(cargando = true)))
    }

    // ---------- el buscador (RF-39) ----------

    @Test
    fun buscador_vacio_muestra_la_lista_entera_en_orden_y_el_texto_del_catalogo() {
        val c = contenido(ui(buscado = ""))
        assertEquals(listOf(acidoUrico, asma, gota), c.lista) // por clave de nombre: «ácido úrico» antes de «Asma»
        assertEquals(emptyList(), c.catalogo)
        assertEquals(TEXTO_ESCRIBI_PARA_BUSCAR, c.textoCatalogo)
        assertEquals("Escribí para buscar en el catálogo", c.textoCatalogo) // RF-39
        assertNull(c.crear)
    }

    @Test
    fun buscador_con_solo_espacios_es_como_vacio() {
        val c = contenido(ui(buscado = "   \t "))
        assertEquals(listOf(acidoUrico, asma, gota), c.lista)
        assertEquals(TEXTO_ESCRIBI_PARA_BUSCAR, c.textoCatalogo)
        assertEquals(emptyList(), c.catalogo)
    }

    @Test
    fun lo_escrito_filtra_la_lista_y_el_catalogo_por_clave_ignorando_tildes_y_mayusculas() {
        val c = contenido(ui(buscado = "ACIDO"))
        assertEquals(listOf(acidoUrico), c.lista)
        assertEquals(emptyList(), c.catalogo)
        assertNull(c.textoCatalogo)
    }

    @Test
    fun los_duplicados_del_catalogo_se_ven_todos() {
        val c = contenido(ui(buscado = "diabetes"))
        assertEquals(listOf(diabetes, diabetesRepetida), c.catalogo.sortedBy { it.id }) // las dos, aunque se llamen igual por clave
        assertEquals(2, c.catalogo.size)
        assertEquals(emptyList(), c.lista)
        assertNull(c.textoCatalogo)
    }

    @Test
    fun lo_escrito_que_no_coincide_con_nada_no_dice_nada() {
        val c = contenido(ui(buscado = "xyz"))
        assertEquals(emptyList(), c.lista)
        assertEquals(emptyList(), c.catalogo)
        assertNull(c.textoCatalogo) // ni "Escribí para buscar…": hay algo escrito
    }

    @Test
    fun una_lista_vacia_no_dice_nada() {
        val c = contenido(ui(estado = EstadoAdministrar(emptyList(), listOf(epilepsia))))
        assertEquals(emptyList(), c.lista)
        assertEquals(TEXTO_ESCRIBI_PARA_BUSCAR, c.textoCatalogo)
    }

    @Test
    fun una_lista_cambiada_desde_otro_lado_se_muestra_entera() {
        // RF-48: el panel no recuerda nada; muestra lo que trae el estado.
        val nueva = EstadoAdministrar(lista = listOf(epilepsia, gota), catalogo = listOf(asma))
        assertEquals(listOf(epilepsia, gota), contenido(ui(estado = nueva)).lista)
    }

    // ---------- "Crear" (RF-42, RF-43, P5-A) ----------

    @Test
    fun ofrece_crear_con_el_nombre_normalizado() {
        val c = contenido(ui(buscado = "  Hipotiroidismo   primario "))
        assertEquals(CrearVisible("Hipotiroidismo primario", error = null), c.crear)
    }

    @Test
    fun no_ofrece_crear_si_ya_hay_una_con_ese_nombre_en_la_lista_o_en_el_catalogo() {
        assertNull(contenido(ui(buscado = "gota")).crear) // de la lista
        assertNull(contenido(ui(buscado = " DIABETES   tipo 2 ")).crear) // de "Del catálogo"
        assertNull(contenido(ui(buscado = "ACIDO URICO")).crear) // sin tildes
    }

    @Test
    fun no_ofrece_crear_con_el_buscador_vacio() {
        assertNull(contenido(ui(buscado = "")).crear)
    }

    @Test
    fun solo_espacios_muestra_el_error_de_vacio_y_no_el_boton() {
        // P5-A, RF-43: aparece apenas se escribe algo (aunque sea un espacio); ofrecerCrear no lo ofrece, lo arma el panel.
        val c = contenido(ui(buscado = "   "))
        assertEquals(CrearVisible(nombre = "", error = "El nombre está vacío"), c.crear)
        assertEquals(TEXTO_ESCRIBI_PARA_BUSCAR, c.textoCatalogo) // el de RF-39 sigue en "Del catálogo"
    }

    @Test
    fun un_nombre_de_256_caracteres_muestra_el_error_de_largo_y_uno_de_255_se_ofrece() {
        val largo = contenido(ui(buscado = "a".repeat(256)))
        assertEquals("El nombre tiene más de 255 caracteres: acortalo", largo.crear?.error)
        assertEquals("a".repeat(256), largo.crear?.nombre)

        val justo = contenido(ui(buscado = "a".repeat(255)))
        assertEquals(CrearVisible("a".repeat(255), error = null), justo.crear)
    }

    @Test
    fun un_nombre_con_caracteres_invisibles_muestra_el_error() {
        val conCeroAncho = contenido(ui(buscado = "Asma\u200B bronquial"))
        assertEquals("El nombre tiene caracteres que no se ven: escribilo de nuevo", conCeroAncho.crear?.error)
        val conNbsp = contenido(ui(buscado = "Hipotiroidismo\u00A0primario"))
        assertEquals("El nombre tiene caracteres que no se ven: escribilo de nuevo", conNbsp.crear?.error)
    }

    @Test
    fun un_espacio_no_comun_al_borde_no_se_recorta_y_es_invisible() {
        assertEquals(
            "El nombre tiene caracteres que no se ven: escribilo de nuevo",
            contenido(ui(buscado = "\u00A0")).crear?.error,
        )
    }

    @Test
    fun el_texto_del_error_se_arma_igual_que_el_del_controlador() {
        // El mismo de RF-43 que usa `crear` en el controlador: la ventana lo muestra sin duplicar la regla.
        val c = contenido(ui(buscado = "x".repeat(300)))
        assertEquals(errorDeNombreEnfermedad("x".repeat(300)), c.crear?.error)
    }

    // ---------- crear y conservar lo escrito (P3-A, RF-46) ----------

    @Test
    fun tras_crear_el_buscador_se_conserva_la_nueva_esta_en_la_lista_y_ya_no_se_ofrece_crear() {
        val hipo = Enfermedad(9, "Hipotiroidismo primario")
        val despues = EstadoAdministrar(lista = estado.lista + hipo, catalogo = estado.catalogo)
        val c = contenido(ui(estado = despues, buscado = "Hipotiroidismo   primario", aviso = "Ya existía en el catálogo como «Hipotiroidismo primario»; se agregó a tu lista y se marcó al paciente"))
        assertEquals(listOf(hipo), c.lista)
        assertNull(c.crear)
        assertEquals("Ya existía en el catálogo como «Hipotiroidismo primario»; se agregó a tu lista y se marcó al paciente", c.aviso)
    }

    @Test
    fun un_rechazo_conserva_lo_escrito_y_muestra_el_aviso_con_la_oferta_de_crear() {
        val c = contenido(ui(buscado = "Hipotiroidismo", aviso = "Demasiados cambios seguidos, esperá un momento"))
        assertEquals("Demasiados cambios seguidos, esperá un momento", c.aviso)
        assertEquals(CrearVisible("Hipotiroidismo", error = null), c.crear)
    }

    @Test
    fun sin_aviso_el_panel_no_trae_ninguno() {
        assertNull(contenido(ui()).aviso)
    }

    // ---------- un cambio por vez (RF-45) ----------

    @Test
    fun con_un_cambio_en_camino_todo_queda_deshabilitado_pero_se_sigue_viendo() {
        val c = contenido(ui(buscado = "diabetes tipo 2", enCamino = true))
        assertFalse(c.accionesHabilitadas)
        assertEquals(2, c.catalogo.size)
        assertNull(c.crear) // hay con ese nombre

        val conCrear = contenido(ui(buscado = "Hipotiroidismo", enCamino = true))
        assertFalse(conCrear.accionesHabilitadas)
        assertEquals(CrearVisible("Hipotiroidismo", error = null), conCrear.crear) // el botón se ve, deshabilitado
    }

    @Test
    fun sin_un_cambio_en_camino_las_acciones_estan_habilitadas() {
        assertTrue(contenido(ui(enCamino = false)).accionesHabilitadas)
    }

    @Test
    fun el_error_de_nombre_no_depende_de_que_haya_un_cambio_en_camino() {
        val c = contenido(ui(buscado = "a".repeat(256), enCamino = true))
        assertEquals("El nombre tiene más de 255 caracteres: acortalo", c.crear?.error)
    }

    // ---------- lo que la ventana no ofrece (RF-47) ----------

    @Test
    fun el_panel_no_tiene_ningun_caso_de_renombrar_dar_de_baja_ni_eliminar() {
        // Exhaustivo a propósito: si alguien suma un caso nuevo al panel, este `when` deja de compilar y obliga a revisar RF-47.
        val casos = listOf(
            PanelAdministrar.Cargando,
            PanelAdministrar.SoloAviso("x"),
            contenido(ui()),
        )
        val nombres = casos.map { c ->
            when (c) {
                PanelAdministrar.Cargando -> "cargando"
                is PanelAdministrar.SoloAviso -> "aviso"
                is PanelAdministrar.Contenido -> "contenido"
            }
        }
        assertEquals(listOf("cargando", "aviso", "contenido"), nombres)
    }
}
