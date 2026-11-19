package com.salud360.core.data.hcgeneral

import com.salud360.core.model.especialidad.PerfilHc
import com.salud360.core.model.especialidad.PestaniaPerfil
import com.salud360.core.model.especialidad.SeccionCatalogoRemota
import com.salud360.core.model.especialidad.SeccionPerfil
import com.salud360.core.model.especialidad.TipoConsultaHc
import com.salud360.core.model.especialidad.TipoConsultaHc.CONSULTA
import com.salud360.core.model.especialidad.TipoConsultaHc.TELEMEDICINA
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reglas de un perfil antes de mandarlo a hclinica (RF-2, RF-4, RF-8, RF-9): los mismos casos y `campo` que
 * `PerfilesTest.php` de hclinica, más los nombres que solo cambian en tildes (que el servidor deja pasar y frena
 * el `unique` de la base).
 */
class PerfilValidacionTest {
    private val catalogo = listOf(
        SeccionCatalogoRemota("motivo_consulta", "Motivo de consulta", "Por qué viene"),
        SeccionCatalogoRemota("examen_fisico", "Examen físico"),
        SeccionCatalogoRemota("laboratorio_general", "Laboratorio general"),
        SeccionCatalogoRemota("datos_subjetivos", "Datos subjetivos"),
    )
    private val catalogoIds = catalogo.map { it.id }

    private fun seccion(id: String, nombre: String, vararg tipos: TipoConsultaHc = arrayOf(CONSULTA)) =
        SeccionPerfil(id, nombre, tipos.toSet())

    private val valido = PerfilHc(
        nombre = "Hepatología",
        pestanias = listOf(
            PestaniaPerfil(
                "Consulta",
                listOf(
                    seccion("motivo_consulta", "Motivo"),
                    seccion("examen_fisico", "Examen físico", CONSULTA, TELEMEDICINA),
                ),
            ),
            PestaniaPerfil(
                "Estudios",
                listOf(
                    seccion("laboratorio_general", "Laboratorio"),
                    seccion("datos_subjetivos", "Subjetivos", TELEMEDICINA),
                ),
            ),
        ),
    )

    private fun validar(perfil: PerfilHc, otros: List<String> = emptyList()) =
        validarPerfil(perfil, otros, catalogoIds)

    private fun PerfilHc.conPestania(i: Int, cambio: (PestaniaPerfil) -> PestaniaPerfil) =
        copy(pestanias = pestanias.mapIndexed { k, p -> if (k == i) cambio(p) else p })

    private fun PerfilHc.conSeccion(i: Int, j: Int, cambio: (SeccionPerfil) -> SeccionPerfil) =
        conPestania(i) { p -> p.copy(secciones = p.secciones.mapIndexed { k, s -> if (k == j) cambio(s) else s }) }

    /** Un solo error, en [campo], con un mensaje que dice qué corregir. */
    private fun assertUnError(errores: List<ErrorPerfil>, campo: String) {
        assertEquals(listOf(campo), errores.map { it.campo }, errores.toString())
        assertTrue(errores[0].mensaje.isNotBlank())
    }

    @Test
    fun perfil_valido_no_tiene_errores() {
        assertEquals(emptyList(), validar(valido, listOf("Pediatría", "Clínica")))
    }

    @Test
    fun nombre_de_perfil_vacio() {
        assertUnError(validar(valido.copy(nombre = "   ")), "nombre")
    }

    @Test
    fun nombre_de_pestania_vacio() {
        assertUnError(validar(valido.conPestania(1) { it.copy(nombre = "") }), "pestanias.1.nombre")
    }

    @Test
    fun nombre_de_seccion_vacio() {
        assertUnError(validar(valido.conSeccion(0, 1) { it.copy(nombre = " ") }), "pestanias.0.secciones.1.nombre")
    }

    @Test
    fun nombres_de_60_caracteres_son_validos_y_de_61_no() {
        // Con tildes y fuera del plano básico: el largo se cuenta en caracteres (como `mb_strlen`), no en bytes ni en
        // unidades UTF-16 ("𝄞" son dos).
        val de60 = valido.copy(nombre = "á".repeat(60))
            .conPestania(0) { it.copy(nombre = "  " + "ñ".repeat(60) + "  ") }
            .conSeccion(1, 0) { it.copy(nombre = "𝄞".repeat(60)) }
        assertEquals(emptyList(), validar(de60))

        val de61 = valido.copy(nombre = "á".repeat(61))
            .conPestania(1) { it.copy(nombre = "a".repeat(61)) }
            .conSeccion(1, 0) { it.copy(nombre = "é".repeat(61)) }
        assertEquals(
            listOf("nombre", "pestanias.1.nombre", "pestanias.1.secciones.0.nombre"),
            validar(de61).map { it.campo },
        )
    }

    @Test
    fun nombre_de_perfil_usado_por_otro_con_otra_capitalizacion_y_espacios() {
        val perfil = valido.copy(nombre = "  hepatología   adultos ")
        assertUnError(validar(perfil, listOf("Pediatría", "HEPATOLOGÍA ADULTOS")), "nombre")
    }

    @Test
    fun nombre_de_perfil_que_solo_cambia_en_tildes_es_repetido() {
        val errores = validar(valido.copy(nombre = "clinica  general"), listOf("Clínica general"))
        assertUnError(errores, "nombre")
        assertTrue("Clínica general" in errores[0].mensaje, errores[0].mensaje)
    }

    @Test
    fun pestania_repetida() {
        assertUnError(validar(valido.conPestania(1) { it.copy(nombre = " consulta ") }), "pestanias.1.nombre")
    }

    @Test
    fun pestania_que_solo_cambia_en_tildes_es_repetida() {
        val perfil = valido
            .conPestania(0) { it.copy(nombre = "Clínica general") }
            .conPestania(1) { it.copy(nombre = "clinica  general") }
        assertUnError(validar(perfil), "pestanias.1.nombre")
    }

    @Test
    fun seccion_fuera_del_catalogo() {
        assertUnError(
            validar(valido.conSeccion(1, 0) { it.copy(seccion = "inventada") }),
            "pestanias.1.secciones.0.seccion",
        )
    }

    @Test
    fun seccion_dos_veces_en_pestanias_distintas_se_marca_en_la_segunda() {
        val errores = validar(valido.conSeccion(1, 1) { it.copy(seccion = "motivo_consulta", nombre = "Motivo") })
        assertUnError(errores, "pestanias.1.secciones.1.seccion")
        // Dice cuál está repetida (RF-9).
        assertTrue("Motivo" in errores[0].mensaje, errores[0].mensaje)
    }

    @Test
    fun seccion_sin_tipos() {
        assertUnError(validar(valido.conSeccion(0, 0) { it.copy(tipos = emptySet()) }), "pestanias.0.secciones.0")
    }

    @Test
    fun perfil_sin_secciones() {
        val sinSecciones = valido.copy(pestanias = valido.pestanias.map { it.copy(secciones = emptyList()) })
        assertUnError(validar(sinSecciones), "pestanias")
        assertUnError(validar(valido.copy(pestanias = emptyList())), "pestanias")
    }

    @Test
    fun nueva_seccion_sale_con_consulta_y_el_nombre_por_defecto() {
        assertEquals(
            SeccionPerfil("motivo_consulta", "Motivo de consulta", setOf(CONSULTA)),
            nuevaSeccion("motivo_consulta", catalogo),
        )
    }
}
