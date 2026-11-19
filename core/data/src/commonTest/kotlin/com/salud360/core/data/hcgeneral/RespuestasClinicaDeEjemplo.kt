package com.salud360.core.data.hcgeneral

import com.salud360.core.data.network.hc.ConsultasClinicaApiRemota
import com.salud360.core.data.network.hc.EjemplosConsultasClinica
import com.salud360.core.data.network.hc.HcApiClient
import com.salud360.core.data.network.hc.HcApiSoloLectura
import com.salud360.core.data.network.hc.RespuestaConsultaDto
import com.salud360.core.data.network.hc.RespuestaHistoriaDto
import com.salud360.core.data.network.hc.RespuestaListaConsultasDto

/**
 * Las respuestas inventadas de [EjemplosConsultasClinica] ya leídas con los DTOs de la E6.1 (la misma lectura que
 * hace la app), para los tests de la conversión (`LecturaClinica`).
 */
internal object RespuestasClinicaDeEjemplo {
    private val cliente = HcApiClient("http://localhost", "clinica")
    private val api = ConsultasClinicaApiRemota(HcApiSoloLectura(cliente))

    fun lista(texto: String = EjemplosConsultasClinica.LISTA): RespuestaListaConsultasDto =
        api.listaDe(cliente.interpretar(200, texto))

    fun consulta(texto: String = EjemplosConsultasClinica.CONSULTA_COMPLETA): RespuestaConsultaDto =
        api.consultaDe(cliente.interpretar(200, texto))

    fun historia(texto: String = EjemplosConsultasClinica.HISTORIA): RespuestaHistoriaDto =
        api.historiaDe(cliente.interpretar(200, texto))

    /** La consulta A con estas `secciones` y este `examen_fisico` (JSON de adentro de las llaves o `{...}` entero). */
    fun consultaConSecciones(secciones: String, examen: String = "{}"): RespuestaConsultaDto = consulta(
        """{"ok": true, "consulta": ${EjemplosConsultasClinica.CABECERA_A}, "secciones": $secciones,
            "examen_fisico": $examen, "no_leidas": []}""",
    )
}
