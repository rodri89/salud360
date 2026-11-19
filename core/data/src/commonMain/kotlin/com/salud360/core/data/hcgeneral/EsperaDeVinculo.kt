package com.salud360.core.data.hcgeneral

import com.salud360.core.model.hc.CausaEnEspera
import com.salud360.core.model.hc.EstadoEnvio

// Qué pasa con la creación de una consulta cuando no se pudo vincular al paciente con su ficha de la web (spec 005, RF-6, RF-13, RF-19).
// Puro: del resultado de `PacientesHcRepository.vinculo` (la lógica de la 002 y la 003) al estado «En espera: <motivo>», con el motivo en
// palabras del usuario **nombrando la HC**, como el resto de los estados del envío. La consulta queda en el dispositivo sin enviarse, sin
// perder ni modificar nada; el médico lo resuelve desde ella (elegir ficha, corregir el documento) o el administrador (ficha de otro
// paciente), y la creación sale en el próximo intento. Ningún resultado crea una ficha: eso, cuando corresponde, lo decide la web al
// resolver (002 RF-10) y el envío no lo hace por su cuenta.

/**
 * El estado en que queda la creación según [resultado], o `null` si quedó vinculado (se envía). Un error al preguntar a la web
 * ([ResultadoVinculo.NoSePudo]) se clasifica como cualquier otro error del envío ([clasificarErrorDeEnvio]): sin conexión, sesión
 * vencida o HC no habilitada son «En espera»; un rechazo del dato, «No se pudo enviar».
 */
fun estadoDeVinculo(resultado: ResultadoVinculo, nombreHc: String): EstadoEnvio? = when (resultado) {
    is ResultadoVinculo.Vinculado -> null
    is ResultadoVinculo.ElegirFicha ->
        espera(nombreHc, CausaEnEspera.ELEGIR_FICHA, "hay más de una ficha con ese documento y ninguna coincide sola: elegí la ficha de este paciente.")
    ResultadoVinculo.FichaNoElegible ->
        espera(nombreHc, CausaEnEspera.ELEGIR_FICHA, "la ficha elegida ya no es posible: elegí otra ficha de este paciente.")
    is ResultadoVinculo.DocumentoInvalido ->
        espera(nombreHc, CausaEnEspera.DOCUMENTO_INVALIDO, resultado.mensaje.ifBlank { MENSAJE_DOCUMENTO }.enMinuscula())
    is ResultadoVinculo.FichaDeOtroPaciente ->
        espera(nombreHc, CausaEnEspera.FICHA_DE_OTRO_PACIENTE, resultado.mensaje.ifBlank { MENSAJE_FICHA_DE_OTRO_PACIENTE }.enMinuscula())
    is ResultadoVinculo.NoSePudo -> clasificarErrorDeEnvio(resultado.error, nombreHc).estado
}

private const val MENSAJE_DOCUMENTO = "el documento del paciente no sirve para la historia clínica: corregilo en la ficha del paciente"
private const val MENSAJE_FICHA_DE_OTRO_PACIENTE = "la ficha de la web con ese documento ya corresponde a otro paciente de turnosonlinebb: avisale al administrador"

private fun espera(nombreHc: String, causa: CausaEnEspera, texto: String) = EstadoEnvio.EnEspera(causa, "Historia clínica de $nombreHc: $texto")

private fun String.enMinuscula(): String = replaceFirstChar { it.lowercase() }
