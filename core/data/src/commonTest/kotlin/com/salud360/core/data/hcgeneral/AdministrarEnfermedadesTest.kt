package com.salud360.core.data.hcgeneral

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "Administrar enfermedades": buscador, cuándo se ofrece "Crear", a quién se marca y los avisos de crear (spec 003,
 * RF-37, RF-39, RF-42 a RF-44, RF-58; D1, D10, D19; E3, E4). Enfermedades y pacientes inventados. Agregar, quitar y un
 * cambio por vez (RF-40, RF-41, RF-45, RF-46, RF-48) los aplica el controlador: se prueban en F2.8.
 */
class AdministrarEnfermedadesTest {

    private val asma = Enfermedad(1, "Asma")
    private val diabetesTipo2 = Enfermedad(2, "Diabetes tipo 2")
    private val hipertension = Enfermedad(3, "Hipertensión arterial")
    private val diabetes = Enfermedad(10, "Diabetes")
    private val diabetesDuplicada = Enfermedad(11, "diabetes")
    private val acidoUrico = Enfermedad(12, "Ácido úrico")
    private val hipotiroidismo = Enfermedad(13, "Hipotiroidismo")

    private val estado = EstadoAdministrar(
        lista = listOf(hipertension, asma, diabetesTipo2),
        catalogo = listOf(hipotiroidismo, diabetesDuplicada, acidoUrico, diabetes),
    )

    private fun resultado(
        nombre: String = "Gota",
        reutilizada: Boolean = false,
        reactivada: Boolean = false,
        yaEnLista: Boolean = false,
        marcada: Boolean = true,
        sinMarcarPor: String? = null,
    ) = ResultadoCrear(Enfermedad(20, nombre), reutilizada, reactivada, yaEnLista, marcada, sinMarcarPor)

    // RF-39: buscador

    @Test
    fun buscador_vacio_muestra_toda_la_lista_y_pide_escribir_para_el_catalogo() {
        for (buscado in listOf("", "   ", " \t\n ")) {
            val b = filtrar(estado, buscado)
            assertEquals(listOf(asma, diabetesTipo2, hipertension), b.lista, "«$buscado»")
            assertEquals(emptyList(), b.catalogo, "«$buscado»")
            assertEquals("Escribí para buscar en el catálogo", b.textoCatalogo, "«$buscado»")
        }
    }

    @Test
    fun parcial_filtra_la_lista_y_el_catalogo_con_duplicados_visibles() {
        val b = filtrar(estado, "diab")
        assertEquals(listOf(diabetesTipo2), b.lista)
        assertEquals(listOf(diabetes, diabetesDuplicada), b.catalogo)
        assertNull(b.textoCatalogo)
    }

    @Test
    fun compara_por_clave_sin_tildes_mayusculas_ni_espacios_de_mas() {
        assertEquals(listOf(acidoUrico), filtrar(estado, "  ÁCIDO  ").catalogo)
        assertEquals(listOf(acidoUrico), filtrar(estado, "acido u").catalogo)
        assertEquals(listOf(hipertension), filtrar(estado, "HIPERTENSION").lista)
        assertEquals(listOf(diabetesTipo2), filtrar(estado, " tipo \t 2").lista)
        val hipo = filtrar(estado, "hipo")
        assertEquals(emptyList(), hipo.lista)
        assertEquals(listOf(hipotiroidismo), hipo.catalogo)
    }

    @Test
    fun sin_resultados_no_pide_escribir() {
        val b = filtrar(estado, "zzz")
        assertEquals(emptyList(), b.lista)
        assertEquals(emptyList(), b.catalogo)
        assertNull(b.textoCatalogo)
    }

    // RF-42, RF-43: "Crear"

    @Test
    fun crear_se_ofrece_con_el_nombre_normalizado_si_no_hay_coincidencia_exacta() {
        assertEquals(OfertaCrear("Diab", null), ofrecerCrear(estado, "Diab"))
        assertEquals(OfertaCrear("Gota crónica", null), ofrecerCrear(estado, "  Gota \t crónica "))
        // Parcial de "Diabetes tipo 2": no es el mismo nombre. Mayúsculas y tildes quedan como se escribieron.
        assertEquals(OfertaCrear("diabetes tipo", null), ofrecerCrear(estado, "diabetes tipo"))
    }

    @Test
    fun crear_no_se_ofrece_si_hay_uno_con_el_mismo_nombre_en_la_lista_o_en_el_catalogo() {
        assertNull(ofrecerCrear(estado, " diabetes  TIPO 2 "))
        assertNull(ofrecerCrear(estado, "DIABETES"))
        assertNull(ofrecerCrear(estado, "diabétes"))
        assertNull(ofrecerCrear(estado, "acido urico"))
    }

    @Test
    fun crear_no_se_ofrece_vacio() {
        assertNull(ofrecerCrear(estado, ""))
        assertNull(ofrecerCrear(estado, " \t "))
    }

    @Test
    fun nombre_invalido_se_ofrece_con_el_texto_de_rf_43_y_no_se_envia() {
        assertEquals(
            OfertaCrear("\u00A0Gota", "El nombre tiene caracteres que no se ven: escribilo de nuevo"),
            ofrecerCrear(estado, "\u00A0Gota"),
        )
        assertEquals(
            "El nombre tiene caracteres que no se ven: escribilo de nuevo",
            ofrecerCrear(estado, "Go\u200Bta")?.error,
        )
        assertEquals("El nombre tiene más de 255 caracteres: acortalo", ofrecerCrear(estado, "a".repeat(256))?.error)
        assertEquals(OfertaCrear("a".repeat(255), null), ofrecerCrear(estado, "a".repeat(255)))
    }

    // RF-44, RF-58: a quién se marca

    @Test
    fun manda_la_ficha_solo_con_la_consulta_editable_y_vinculada() {
        assertEquals(PacienteCrear.ConFicha(7), pacienteParaCrear(7, null, editable = true))
        // Un error de lugar 5 no es de vínculo: la ficha se manda y la web decide.
        assertEquals(PacienteCrear.ConFicha(7), pacienteParaCrear(7, general(MotivoHc.Tipo.OTRO), editable = true))
    }

    @Test
    fun sin_vinculo_no_manda_la_ficha() {
        val noVinculado = PacienteCrear.SinMarcar(MotivoSinMarcar.NO_VINCULADO)
        assertEquals(noVinculado, pacienteParaCrear(null, null, editable = true))
        assertEquals(noVinculado, pacienteParaCrear(null, MotivoEnfermedades.ElegirFicha(emptyList()), editable = true))
        assertEquals(noVinculado, pacienteParaCrear(null, MotivoEnfermedades.SinFicha("Sin ficha"), editable = true))
        assertEquals(noVinculado, pacienteParaCrear(7, MotivoEnfermedades.FichaNoDisponible, editable = true))
    }

    @Test
    fun fuera_de_cartera_no_manda_la_ficha() {
        assertEquals(
            PacienteCrear.SinMarcar(MotivoSinMarcar.FUERA_DE_CARTERA),
            pacienteParaCrear(7, MotivoEnfermedades.FueraDeCartera, editable = true),
        )
    }

    @Test
    fun consulta_no_editable_no_manda_la_ficha() {
        val noEditable = PacienteCrear.SinMarcar(MotivoSinMarcar.NO_EDITABLE)
        assertEquals(noEditable, pacienteParaCrear(7, null, editable = false))
        assertEquals(noEditable, pacienteParaCrear(null, MotivoEnfermedades.FueraDeCartera, editable = false))
    }

    // RF-37, D10: el botón

    @Test
    fun administrar_en_consulta_abierta_propia_con_conexion_aunque_no_este_vinculado() {
        assertTrue(puedeAdministrar(editable = true, propia = true, conConexion = true, motivo = null))
        val deVinculo = listOf(
            MotivoEnfermedades.ElegirFicha(emptyList()),
            MotivoEnfermedades.SinFicha("Sin ficha"),
            MotivoEnfermedades.FichaNoDisponible,
            MotivoEnfermedades.FueraDeCartera,
        )
        for (m in deVinculo) assertTrue(puedeAdministrar(true, true, true, m), m.texto)
    }

    @Test
    fun administrar_no_con_motivos_generales_sin_conexion_ni_cerrada_ni_ajena() {
        for (tipo in MotivoHc.Tipo.entries) {
            assertFalse(puedeAdministrar(true, true, true, general(tipo)), tipo.name)
        }
        assertFalse(puedeAdministrar(editable = true, propia = true, conConexion = false, motivo = null))
        assertFalse(puedeAdministrar(editable = false, propia = true, conConexion = true, motivo = null))
        assertFalse(puedeAdministrar(editable = true, propia = false, conConexion = true, motivo = null))
    }

    // RF-44, RF-58, E3: avisos de crear

    @Test
    fun nueva_y_marcada_no_tiene_aviso() {
        assertNull(avisoCrear(resultado(), null))
    }

    @Test
    fun reutilizada_avisa_con_el_nombre_del_catalogo() {
        assertEquals(
            "Ya existía en el catálogo como «Diabetes»; se agregó a tu lista y se marcó al paciente",
            avisoCrear(resultado(nombre = "Diabetes", reutilizada = true), null),
        )
    }

    @Test
    fun reactivada_avisa_como_reutilizada() {
        assertEquals(
            "Ya existía en el catálogo como «Gota»; se agregó a tu lista y se marcó al paciente",
            avisoCrear(resultado(reutilizada = true, reactivada = true), null),
        )
    }

    @Test
    fun ya_en_la_lista_y_marcada() {
        assertEquals(
            "«Asma» ya estaba en tu lista; se marcó al paciente",
            avisoCrear(resultado(nombre = "Asma", reutilizada = true, yaEnLista = true), null),
        )
    }

    @Test
    fun sin_marcar_por_lo_que_dice_la_web() {
        assertEquals(
            "Se agregó a tu lista; no se marcó al paciente porque el paciente no está vinculado a una ficha de la web",
            avisoCrear(resultado(marcada = false, sinMarcarPor = "paciente_no_encontrado"), null),
        )
        assertEquals(
            "Se agregó a tu lista; no se marcó al paciente porque el paciente no está en tu cartera",
            avisoCrear(resultado(marcada = false, sinMarcarPor = "fuera_de_cartera"), null),
        )
    }

    @Test
    fun sin_marcar_por_desconocido_no_dice_motivo() {
        for (codigo in listOf("no_en_tu_lista", "enfermedad_dada_de_baja", "uno_nuevo", null)) {
            assertEquals(
                "Se agregó a tu lista; no se marcó al paciente.",
                avisoCrear(resultado(marcada = false, sinMarcarPor = codigo), null),
                codigo,
            )
        }
    }

    @Test
    fun sin_marcar_por_el_motivo_local() {
        val textos = mapOf(
            MotivoSinMarcar.NO_VINCULADO to "el paciente no está vinculado a una ficha de la web",
            MotivoSinMarcar.FUERA_DE_CARTERA to "el paciente no está en tu cartera",
            MotivoSinMarcar.NO_EDITABLE to "la consulta no se puede editar",
        )
        for ((motivo, texto) in textos) {
            assertEquals(
                "Se agregó a tu lista; no se marcó al paciente porque $texto",
                avisoCrear(resultado(marcada = false), motivo),
            )
        }
    }

    @Test
    fun el_motivo_local_gana_sobre_la_web() {
        assertEquals(
            "Se agregó a tu lista; no se marcó al paciente porque la consulta no se puede editar",
            avisoCrear(resultado(marcada = false, sinMarcarPor = "fuera_de_cartera"), MotivoSinMarcar.NO_EDITABLE),
        )
        assertEquals(
            "Se agregó a tu lista; no se marcó al paciente porque el paciente no está vinculado a una ficha de la web",
            avisoCrear(resultado(marcada = true), MotivoSinMarcar.NO_VINCULADO),
        )
    }

    @Test
    fun sin_marcar_y_reutilizada_empieza_por_ya_existia() {
        assertEquals(
            "Ya existía en el catálogo como «Diabetes». Se agregó a tu lista; no se marcó al paciente porque el " +
                "paciente no está en tu cartera",
            avisoCrear(resultado(nombre = "Diabetes", reutilizada = true, marcada = false), MotivoSinMarcar.FUERA_DE_CARTERA),
        )
        assertEquals(
            "Ya existía en el catálogo como «Diabetes». Se agregó a tu lista; no se marcó al paciente.",
            avisoCrear(resultado(nombre = "Diabetes", reutilizada = true, marcada = false, sinMarcarPor = "uno_nuevo"), null),
        )
    }

    @Test
    fun sin_marcar_y_ya_en_la_lista_empieza_por_ya_estaba() {
        assertEquals(
            "«Asma» ya estaba en tu lista. No se marcó al paciente porque la consulta no se puede editar",
            avisoCrear(resultado(nombre = "Asma", reutilizada = true, yaEnLista = true, marcada = false), MotivoSinMarcar.NO_EDITABLE),
        )
        assertEquals(
            "«Asma» ya estaba en tu lista. No se marcó al paciente.",
            avisoCrear(resultado(nombre = "Asma", reutilizada = true, yaEnLista = true, marcada = false, sinMarcarPor = "uno_nuevo"), null),
        )
    }

    // RF-41, D1: quitar no desmarca

    @Test
    fun la_quitada_sigue_siendo_del_paciente_y_vuelve_a_del_catalogo() {
        // Respuesta de quitar «Asma» con el paciente que la tenía: la web la pasa a "Otras" y al catálogo.
        val administrar = EstadoAdministrar(listOf(diabetesTipo2, hipertension), listOf(asma, diabetes))
        val seccion = EstadoSeccionEnfermedades(
            marcables = listOf(Marcable(2, "Diabetes tipo 2", false), Marcable(3, "Hipertensión arterial", false)),
            otras = listOf(asma),
        )
        assertEquals(listOf(asma), enfermedadesDelPaciente(seccion))
        val b = filtrar(administrar, "asma")
        assertEquals(emptyList(), b.lista)
        assertEquals(listOf(asma), b.catalogo)
        assertNull(ofrecerCrear(administrar, "asma"))
    }

    private fun general(tipo: MotivoHc.Tipo) = MotivoEnfermedades.General(MotivoHc(tipo, "Texto de $tipo"))
}
