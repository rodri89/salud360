package com.salud360.core.data.network.hc

import com.salud360.core.data.network.TobbException
import kotlinx.datetime.LocalDate

/**
 * La escritura de consultas de la HC general (spec 005, E1: crear, textos, examen físico, cerrar y baja). Los ids son los de la web
 * (el de la ficha que informa `pacientes/resolver`, el de la consulta que contestó `crear`). Cada operación es **un pedido**: si
 * falla lanza [TobbException] con su `status` y su `codigo` (`sin_token`, `token`, `sin_permiso`, `fuera_de_cartera`,
 * `consulta_no_propia`, `hc_no_habilitada`, `medico_no_vinculado`, `licencia_vencida`, `paciente_no_encontrado`,
 * `consulta_no_encontrada`, `consulta_cerrada`, `datos_invalidos`, `escritura`, `tobb_caido`; el `429` de Laravel llega sin
 * código) y el cuerpo completo en [TobbException.cuerpo] (el `campo` y el `motivo` de un `422`). **No reintenta**: eso es del
 * envío diferido (RF-6, RF-29), que repite con la misma clave sin duplicar (RF-15). Es una interfaz para probar sin red.
 *
 * Esta capa no decide qué se manda ni cuándo: recibe lo ya decidido. Nada la llama todavía desde el autoguardado.
 */
interface ClinicaEscrituraApi {
    /** `POST consultas` (RF-12 a RF-15): crea la consulta abierta, o devuelve la ya creada con esa [clave] (`creada = false`). */
    suspend fun crear(
        clave: String,
        dispositivo: String,
        pacienteWebId: Long,
        tipo: String,
        fecha: LocalDate,
        edadMostrar: String? = null,
    ): RespuestaCrearConsultaDto

    /** `PUT consultas/{id}/secciones` (RF-20): [textos] es sección → texto, solo lo tocado; `""` vacía. Sin nada que mandar no sale ningún pedido. */
    suspend fun secciones(consultaWebId: Long, textos: Map<String, String>): RespuestaEscrituraDto

    /** `PUT consultas/{id}/examen` (RF-21): [campos] es nombre del contrato → valor, solo lo tocado. Sin nada que mandar no sale ningún pedido. */
    suspend fun examen(consultaWebId: Long, campos: Map<String, String>): RespuestaEscrituraDto

    /** `POST consultas/{id}/cerrar` (RF-22): con la fecha y la edad que cargó el médico y la clave de este cierre. */
    suspend fun cerrar(
        consultaWebId: Long,
        clave: String,
        dispositivo: String,
        fecha: LocalDate,
        edadMostrar: String? = null,
    ): RespuestaCerrarDto

    /** `DELETE consultas/{id}` con cuerpo JSON (RF-26): baja lógica de una consulta propia y abierta. */
    suspend fun baja(consultaWebId: Long, clave: String, dispositivo: String): RespuestaBajaDto
}

/** [ClinicaEscrituraApi] contra hclinica, sobre el [HcApiClient] completo (el de solo lectura no puede escribir). */
class ClinicaEscrituraApiRemota(private val cliente: HcApiClient) : ClinicaEscrituraApi {
    override suspend fun crear(
        clave: String,
        dispositivo: String,
        pacienteWebId: Long,
        tipo: String,
        fecha: LocalDate,
        edadMostrar: String?,
    ): RespuestaCrearConsultaDto =
        cliente.leerTodo(
            RespuestaCrearConsultaDto.serializer(),
            cliente.post(RUTA, PayloadsClinica.crear(clave, dispositivo, pacienteWebId, tipo, fecha, edadMostrar).toString()),
        )

    override suspend fun secciones(consultaWebId: Long, textos: Map<String, String>): RespuestaEscrituraDto {
        val cuerpo = PayloadsClinica.secciones(textos) ?: return NADA_QUE_ESCRIBIR
        return cliente.leerTodo(RespuestaEscrituraDto.serializer(), cliente.put("$RUTA/$consultaWebId/secciones", cuerpo.toString()))
    }

    override suspend fun examen(consultaWebId: Long, campos: Map<String, String>): RespuestaEscrituraDto {
        val cuerpo = PayloadsClinica.examen(campos) ?: return NADA_QUE_ESCRIBIR
        return cliente.leerTodo(RespuestaEscrituraDto.serializer(), cliente.put("$RUTA/$consultaWebId/examen", cuerpo.toString()))
    }

    override suspend fun cerrar(consultaWebId: Long, clave: String, dispositivo: String, fecha: LocalDate, edadMostrar: String?): RespuestaCerrarDto =
        cliente.leerTodo(
            RespuestaCerrarDto.serializer(),
            cliente.post("$RUTA/$consultaWebId/cerrar", PayloadsClinica.cerrar(clave, dispositivo, fecha, edadMostrar).toString()),
        )

    override suspend fun baja(consultaWebId: Long, clave: String, dispositivo: String): RespuestaBajaDto =
        cliente.leerTodo(
            RespuestaBajaDto.serializer(),
            cliente.delete("$RUTA/$consultaWebId", PayloadsClinica.baja(clave, dispositivo).toString()),
        )

    private companion object {
        const val RUTA = "consultas"

        /** Lo que contesta una escritura sin nada que escribir: no hubo pedido, no se guardó ni se ignoró nada. */
        val NADA_QUE_ESCRIBIR = RespuestaEscrituraDto(guardadas = emptyList(), desconocidas = emptyList())
    }
}
