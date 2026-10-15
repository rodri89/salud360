package com.salud360.especialidades.clinica

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Child-Pugh como la web: parámetros, toque, total y clase (RF-74, RF-76 a RF-78). */
class ChildPughTest {

    // RF-74: las claves de siempre (RF-78), en orden y con los criterios sin "(1)"/"(2)"/"(3)".
    @Test
    fun cinco_parametros_con_las_claves_y_los_textos_de_la_spec() {
        assertEquals(
            listOf(
                ParametroChildPugh("ascitis", "Ascitis", listOf("Ausente (I)", "Moderada (II)", "Severa (III) o refractaria")),
                ParametroChildPugh("bilirrubina", "Bilirrubina total (mg/dl)", listOf("≤ 2", "2 - 3", "> 3")),
                ParametroChildPugh("albumina", "Albúmina (g/dl)", listOf("> 3,5", "2,8 - 3,5", "< 2,8")),
                ParametroChildPugh("tp", "TP (seg sobre control) / RIN", listOf("1-3 / < 1,8", "4-6 / 1,8-2,3", "> 6 / > 2,3")),
                ParametroChildPugh("encefalopatia", "Encefalopatía", listOf("No", "Grado I/II", "Grado III/IV o refractaria")),
            ),
            parametrosChildPugh,
        )
    }

    // RF-78 y casos límite: solo "1", "2" y "3" son puntos; lo demás no suma.
    @Test
    fun puntos_solo_de_1_2_y_3() {
        assertEquals(1, puntosDe("1"))
        assertEquals(2, puntosDe("2"))
        assertEquals(3, puntosDe("3"))
        listOf("", "0", "4", "x", " 2").forEach { assertNull(puntosDe(it), "\"$it\"") }
    }

    // RF-76: tocar pone el puntaje; tocar el elegido deja sin cargar; un valor desconocido se reemplaza.
    @Test
    fun alternar_puntos() {
        assertEquals("2", alternarPuntos("", "2"))
        assertEquals("", alternarPuntos("2", "2"))
        assertEquals("3", alternarPuntos("2", "3"))
        assertEquals("1", alternarPuntos("x", "1"))
        assertEquals("", alternarPuntos(alternarPuntos("", "2"), "2"))
    }

    // RF-77: suma de los cargados; 0 sin ninguno; los desconocidos y las claves ajenas no suman.
    @Test
    fun total_y_completos() {
        assertEquals(0, totalChildPugh(emptyMap()))
        assertEquals(0, completosChildPugh(emptyMap()))
        val todosTres = parametrosChildPugh.associate { it.clave to "3" }
        assertEquals(15, totalChildPugh(todosTres))
        assertEquals(5, completosChildPugh(todosTres))
        val parcial = mapOf("ascitis" to "2", "bilirrubina" to "1", "albumina" to "4", "tp" to "", "otra" to "3")
        assertEquals(3, totalChildPugh(parcial))
        assertEquals(2, completosChildPugh(parcial))
    }

    // RF-77: A ≤ 6, B 7-9, C ≥ 10; "—" mientras falte alguno.
    @Test
    fun clase_en_los_bordes() {
        assertEquals("A", claseChildPugh(5, 5))
        assertEquals("A", claseChildPugh(6, 5))
        assertEquals("B", claseChildPugh(7, 5))
        assertEquals("B", claseChildPugh(9, 5))
        assertEquals("C", claseChildPugh(10, 5))
        assertEquals("C", claseChildPugh(15, 5))
        assertEquals("—", claseChildPugh(12, 4))
        assertEquals("—", claseChildPugh(0, 0))
    }

    @Test
    fun cuatro_cargados_suman_pero_sin_clase() {
        val cuatro = mapOf("ascitis" to "1", "bilirrubina" to "1", "albumina" to "1", "tp" to "1", "encefalopatia" to "4")
        assertEquals(4, totalChildPugh(cuatro))
        assertEquals("—", claseChildPugh(totalChildPugh(cuatro), completosChildPugh(cuatro)))
    }
}
