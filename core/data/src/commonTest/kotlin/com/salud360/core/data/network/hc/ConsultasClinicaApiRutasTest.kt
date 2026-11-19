package com.salud360.core.data.network.hc

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Las rutas de lectura de hclinica contra el md («Consultas (solo lectura)»): `GET consultas?paciente_id=&limite=&desde=`,
 * `GET consultas/{id}` y `GET pacientes/{id}/historia`. `paciente_id`, `limite` y `desde` van en la query, nunca en un
 * cuerpo.
 */
class ConsultasClinicaApiRutasTest {
    private val api = ConsultasClinicaApiRemota(HcApiSoloLectura(HcApiClient("http://localhost", "clinica")))

    @Test
    fun rutas_de_las_tres_lecturas() {
        assertEquals("consultas", ConsultasClinicaApiRemota.RUTA_LISTA)
        assertEquals("consultas/7003", api.rutaConsulta(7003))
        assertEquals("pacientes/5001/historia", api.rutaHistoria(5001))
    }

    @Test
    fun la_lista_manda_paciente_limite_y_desde_en_la_query() {
        // `Any?` explícito: sin él Kotlin unifica los números sueltos con el `5001L` y los vuelve `Long`.
        assertEquals(
            mapOf<String, Any?>("paciente_id" to 5001L, "limite" to 3, "desde" to 0),
            api.paramsLista(pacienteWebId = 5001, limite = 3, desde = 0),
        )
        assertEquals(
            mapOf<String, Any?>("paciente_id" to 5001L, "limite" to 50, "desde" to 100),
            api.paramsLista(5001, ConsultasClinicaApi.LIMITE_POR_DEFECTO, 100),
        )
    }

    @Test
    fun el_limite_por_defecto_es_el_del_servidor() {
        assertEquals(50, ConsultasClinicaApi.LIMITE_POR_DEFECTO)
    }

    @Test
    fun los_ids_grandes_viajan_enteros() {
        // El servidor acepta hasta 9 cifras; un id largo no se acorta ni se pasa por decimales.
        assertEquals("consultas/999999999", api.rutaConsulta(999_999_999))
        assertEquals("pacientes/999999999/historia", api.rutaHistoria(999_999_999))
    }
}
