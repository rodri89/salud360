package com.salud360.core.data.network.hc

import com.salud360.core.data.hcgeneral.TIPO_LABORATORIO_GENERAL
import com.salud360.core.data.hcgeneral.TIPO_PESO
import com.salud360.core.data.network.TobbException
import com.salud360.core.model.Id
import com.salud360.core.model.hc.Antecedente
import com.salud360.core.model.hc.Laboratorio
import com.salud360.core.model.hc.RegistroClinico
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonObject

/**
 * La escritura de consultas de la HC general (spec 005, E1: crear, textos, examen físico, cerrar y baja; E2: formularios, antecedentes familiares y registros; E3: cargas de laboratorio general y peso). Los ids son los de la web
 * (el de la ficha que informa `pacientes/resolver`, el de la consulta que contestó `crear`). Cada operación es **un pedido**: si
 * falla lanza [TobbException] con su `status` y su `codigo` (`sin_token`, `token`, `sin_permiso`, `fuera_de_cartera`,
 * `consulta_no_propia`, `hc_no_habilitada`, `medico_no_vinculado`, `licencia_vencida`, `paciente_no_encontrado`,
 * `consulta_no_encontrada`, `consulta_cerrada`, `datos_invalidos`, `escritura`, `tobb_caido`; el `429` de Laravel llega sin
 * código) y el cuerpo completo en [TobbException.cuerpo] (el `campo` y el `motivo` de un `422`). **No reintenta**: eso es del
 * envío diferido (RF-6, RF-29), que repite con la misma clave sin duplicar (RF-15). Es una interfaz para probar sin red.
 *
 * Esta capa no decide qué se manda ni cuándo: recibe lo ya decidido (las de E2 y las cargas de E3 reciben además lo último que la web confirmó y
 * arman solo lo que cambió, [PayloadsClinica]). Nada la llama todavía desde el autoguardado ni desde la cola de envío.
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

    /**
     * `PUT consultas/{id}/formularios` (E2, RF-35, RF-37, RF-38): [valores] es sección -> (clave -> valor), solo lo tocado y con el valor tal como
     * lo guarda la app (alimentación `"<0|1>|<detalle>"`, serología `SI`/`NO`, Child-Pugh `"1"`..`"3"`; en blanco = vaciado a propósito, sale `""`).
     * Sin nada que mandar no sale ningún pedido. Es un *upsert*: no lleva clave de repetibilidad.
     */
    suspend fun formularios(consultaWebId: Long, valores: Map<String, Map<String, String>>): RespuestaEscrituraDto

    /**
     * `PUT pacientes/{id}/antecedentes-familiares` (E2, RF-36): las casillas y la nota de [antecedentes] que cambian de verdad contra [enviados]
     * (clave -> lo último que la web confirmó), asociadas a la consulta [consultaWebId]. Nunca el tabaquismo, lo personal ni los desactivados.
     * Sin nada que mandar no sale ningún pedido.
     */
    suspend fun antecedentes(
        pacienteWebId: Long,
        consultaWebId: Long,
        antecedentes: List<Antecedente>,
        enviados: Map<String, Antecedente> = emptyMap(),
    ): RespuestaAntecedentesDto

    /**
     * `PUT consultas/{id}/registros` (E2, RF-39, RF-40, RF-70): los estudios, exámenes e interconsultas de [registros] que se escriben o se dan de
     * baja, contra [enviados] (id local -> lo último que la web confirmó). **Todo o nada** en el servidor. Sin nada que mandar no sale ningún pedido.
     */
    suspend fun registros(
        consultaWebId: Long,
        registros: List<RegistroClinico>,
        enviados: Map<Id, RegistroClinico> = emptyMap(),
    ): RespuestaRegistrosDto

    /**
     * `PUT pacientes/{id}/laboratorios` (E3, RF-41, RF-42, RF-69): una carga de laboratorio general de la consulta [consultaWebId], con
     * la [fecha] clínica (nunca vacía ni el marcador de «sin fecha») y, contra [confirmada] (lo último que la web confirmó de esa carga),
     * solo los valores que cambian —al alta ([idWeb] nulo), todos los que tienen contenido—; un valor que se vacía sale `""` (RF-69). La
     * clave es `l:<id local>` de la carga ([PayloadsClinica.claveDeCarga]), estable entre reintentos. Sin nada que mandar no sale ningún
     * pedido. [idWeb] presente = actualización de esa fila de la web (va como `id`).
     */
    suspend fun laboratorio(
        pacienteWebId: Long,
        consultaWebId: Long,
        dispositivo: String,
        fecha: LocalDate,
        carga: Laboratorio,
        idWeb: Long? = null,
        confirmada: Laboratorio? = null,
    ): RespuestaCargaDto

    /** `PUT pacientes/{id}/pesos` (E3): igual que [laboratorio], con `valores: {peso}` y sin `tipo`. Sin nada que mandar no sale ningún pedido. */
    suspend fun peso(
        pacienteWebId: Long,
        consultaWebId: Long,
        dispositivo: String,
        fecha: LocalDate,
        carga: Laboratorio,
        idWeb: Long? = null,
        confirmada: Laboratorio? = null,
    ): RespuestaCargaDto

    /**
     * `DELETE pacientes/{id}/laboratorios/{cargaId}` y `/pesos/{cargaId}` con cuerpo JSON (E3, RF-41, RF-42): baja lógica de una carga
     * propia de [consultaWebId] (`activo = 0`, nunca se borra); repetir la baja es `ok`. La clave es `l:<id local>-baja` y [tipo] elige la
     * ruta (`laboratorio_general` o `peso`).
     */
    suspend fun bajaDeCarga(
        pacienteWebId: Long,
        cargaWebId: Long,
        consultaWebId: Long,
        clave: String,
        dispositivo: String,
        tipo: String = TIPO_LABORATORIO_GENERAL,
    ): RespuestaBajaDeCargaDto
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

    override suspend fun formularios(consultaWebId: Long, valores: Map<String, Map<String, String>>): RespuestaEscrituraDto {
        val cuerpo = PayloadsClinica.formularios(valores) ?: return NADA_QUE_ESCRIBIR
        return cliente.leerTodo(RespuestaEscrituraDto.serializer(), cliente.put("$RUTA/$consultaWebId/formularios", cuerpo.toString()))
    }

    override suspend fun antecedentes(
        pacienteWebId: Long,
        consultaWebId: Long,
        antecedentes: List<Antecedente>,
        enviados: Map<String, Antecedente>,
    ): RespuestaAntecedentesDto {
        val cuerpo = PayloadsClinica.antecedentes(consultaWebId, antecedentes, enviados) ?: return ANTECEDENTES_SIN_NADA
        return cliente.leerTodo(RespuestaAntecedentesDto.serializer(), cliente.put("pacientes/$pacienteWebId/antecedentes-familiares", cuerpo.toString()))
    }

    override suspend fun registros(consultaWebId: Long, registros: List<RegistroClinico>, enviados: Map<Id, RegistroClinico>): RespuestaRegistrosDto {
        val cuerpo = PayloadsClinica.registros(registros, enviados) ?: return REGISTROS_SIN_NADA
        return cliente.leerTodo(RespuestaRegistrosDto.serializer(), cliente.put("$RUTA/$consultaWebId/registros", cuerpo.toString()))
    }

    // ---------- E3 (spec 005, T81): cargas de laboratorio general y peso ----------

    override suspend fun laboratorio(
        pacienteWebId: Long,
        consultaWebId: Long,
        dispositivo: String,
        fecha: LocalDate,
        carga: Laboratorio,
        idWeb: Long?,
        confirmada: Laboratorio?,
    ): RespuestaCargaDto {
        val cuerpo = PayloadsClinica.carga(
            PayloadsClinica.claveDeCarga(carga.id), dispositivo, consultaWebId, fecha, carga, idWeb, confirmada, TIPO_LABORATORIO_GENERAL,
        ) ?: return CARGA_SIN_NADA
        return cliente.leerTodo(RespuestaCargaDto.serializer(), cliente.put("$RUTA_PACIENTES/$pacienteWebId/laboratorios", cuerpo.toString()))
    }

    override suspend fun peso(
        pacienteWebId: Long,
        consultaWebId: Long,
        dispositivo: String,
        fecha: LocalDate,
        carga: Laboratorio,
        idWeb: Long?,
        confirmada: Laboratorio?,
    ): RespuestaCargaDto {
        val cuerpo = PayloadsClinica.carga(
            PayloadsClinica.claveDeCarga(carga.id), dispositivo, consultaWebId, fecha, carga, idWeb, confirmada, TIPO_PESO,
        ) ?: return CARGA_SIN_NADA
        return cliente.leerTodo(RespuestaCargaDto.serializer(), cliente.put("$RUTA_PACIENTES/$pacienteWebId/pesos", cuerpo.toString()))
    }

    override suspend fun bajaDeCarga(
        pacienteWebId: Long,
        cargaWebId: Long,
        consultaWebId: Long,
        clave: String,
        dispositivo: String,
        tipo: String,
    ): RespuestaBajaDeCargaDto = cliente.leerTodo(
        RespuestaBajaDeCargaDto.serializer(),
        cliente.delete(
            "$RUTA_PACIENTES/$pacienteWebId/${rutaDeCarga(tipo)}/$cargaWebId",
            PayloadsClinica.bajaDeCarga(clave, dispositivo, consultaWebId).toString(),
        ),
    )

    /** La ruta de la tabla de la carga: solo se escriben el laboratorio general y el peso (el reducido quedó fuera de alcance). */
    private fun rutaDeCarga(tipo: String): String = when (tipo) {
        TIPO_LABORATORIO_GENERAL -> RUTA_LABORATORIOS
        TIPO_PESO -> RUTA_PESOS
        else -> throw IllegalArgumentException("La API de E3 solo escribe laboratorio general y peso, no '$tipo'")
    }

    private companion object {
        const val RUTA = "consultas"
        const val RUTA_PACIENTES = "pacientes"
        const val RUTA_LABORATORIOS = "laboratorios"
        const val RUTA_PESOS = "pesos"

        /** Lo que contesta una escritura sin nada que escribir: no hubo pedido, no se guardó ni se ignoró nada. */
        val NADA_QUE_ESCRIBIR = RespuestaEscrituraDto(guardadas = emptyList(), desconocidas = emptyList())
        val ANTECEDENTES_SIN_NADA = RespuestaAntecedentesDto(id = null, guardadas = emptyList(), desconocidas = emptyList())
        val REGISTROS_SIN_NADA = RespuestaRegistrosDto(ids = JsonObject(emptyMap()), guardadas = emptyList(), desconocidas = emptyList())
        val CARGA_SIN_NADA = RespuestaCargaDto(id = "", guardadas = emptyList(), desconocidas = emptyList())
    }
}
