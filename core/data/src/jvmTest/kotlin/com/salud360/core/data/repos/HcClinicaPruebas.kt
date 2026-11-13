package com.salud360.core.data.repos

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.salud360.core.data.hcgeneral.RespuestasClinicaDeEjemplo
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.network.TobbException
import com.salud360.core.data.network.hc.ClinicaAdjuntosApi
import com.salud360.core.data.network.hc.ConsultaCabeceraDto
import com.salud360.core.data.network.hc.ConsultasClinicaApi
import com.salud360.core.data.network.hc.EjemplosConsultasClinica
import com.salud360.core.data.network.hc.OmitidasConsultasDto
import com.salud360.core.data.network.hc.PacientesHcApi
import com.salud360.core.data.network.hc.PacientesHcApis
import com.salud360.core.data.network.hc.HcPacienteRequest
import com.salud360.core.data.network.hc.HcResolverRespuesta
import com.salud360.core.data.network.hc.PaginaCartera
import com.salud360.core.data.network.hc.RespuestaConsultaDto
import com.salud360.core.data.network.hc.RespuestaHistoriaDto
import com.salud360.core.data.network.hc.RespuestaListaConsultasDto
import com.salud360.core.database.DriverFactory
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.Id
import com.salud360.core.model.especialidad.CodigosHc
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.pacientes.Paciente
import com.salud360.core.model.pacientes.PacienteExtra
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import java.io.File

/**
 * Lo que comparten los tests de `HcClinicaBackend` (spec 002, E6.3): una base SQLite de verdad en un archivo temporal con
 * un paciente de prueba, volcados de tablas para comparar «antes y después» fila por fila y una API de pacientes que falla
 * si alguien la usa (la lectura nunca pide `pacientes/resolver`, RF-49). Datos inventados (documento 99000100).
 */
internal object HcClinicaPruebas {
    const val PACIENTE = "p-99000100"
    const val MEDICO = "m-99000009"

    /** Id de la ficha de la web del paciente de prueba. */
    const val FICHA = 5001L

    /** Las tablas de la historia clínica que la lectura no puede borrar ni vaciar (más `paciente_extra`). */
    val TABLAS = listOf(
        "consulta", "seccion_valor", "examen_fisico", "antecedente", "registro_clinico", "laboratorio", "pendiente",
        "archivo", "consulta_diagnostico", "medico_preferencia", "paciente_extra",
    )

    /** Todo lo que puede contestar hclinica y la app tiene que dejar pasar tal cual, sin reintento (RF-56, RF-58). */
    val ERRORES = listOf(
        TobbException(401, "no_autorizado", "Sesión vencida"),
        TobbException(403, "fuera_de_cartera", "Ese paciente no está en tu cartera."),
        TobbException(403, "hc_no_habilitada", "No habilitada"),
        TobbException(404, "consulta_no_encontrada", "Consulta no encontrada."),
        TobbException(404, "paciente_no_encontrado", "Paciente no encontrado."),
        TobbException(422, "datos_invalidos", "Falta el paciente o la consulta, o no es válida."),
        TobbException(429, null, "Too Many Attempts."),
        TobbException(500, "consultas", "No se pudo leer la historia clínica."),
        TobbException(0, "red", "Sin conexión con la historia clínica de clinica"),
        TobbException(0, "respuesta", "Respuesta inesperada de clinica: JSON cortado"),
    )

    /** La API de pacientes de la HC general: si se la llama, el test falla (ni `resolver` ni la cartera). */
    object PacientesProhibida : PacientesHcApi {
        override suspend fun pagina(desde: Int, limite: Int): PaginaCartera = error("la lectura no pide la cartera")

        override suspend fun resolver(pedido: HcPacienteRequest, elegida: Long?): HcResolverRespuesta =
            error("la lectura no puede llamar a pacientes/resolver (RF-49)")
    }

    fun pacientes(db: Salud360Db) = PacientesHcRepository(db, PacientesHcApis(mapOf(CodigosHc.GENERAL to PacientesProhibida)), emptyMap())

    /** Base temporal con el paciente de prueba; [vinculado] le guarda la ficha de la web (sin `dirty`). */
    fun conBase(vinculado: Boolean = true, bloque: suspend (Salud360Db, SqlDriver) -> Unit) {
        val archivo = File.createTempFile("salud360-hcclinica", ".db").also { it.delete() }
        try {
            runBlocking {
                val driver = DriverFactory(archivo.absolutePath).createDriver()
                try {
                    val db = Salud360Db(driver)
                    db.pacientesQueries.upsertPaciente(Paciente(PACIENTE, "99000100", "Salud360", "Prueba").toRow(0, dirty = false))
                    if (vinculado) {
                        db.pacientesQueries.upsertPacienteExtra(
                            PacienteExtra(PACIENTE, CodigosHc.GENERAL, PacientesHcRepository.EXTRA_HC_PACIENTE_ID, FICHA.toString())
                                .toRow(0, dirty = false),
                        )
                    }
                    bloque(db, driver)
                } finally {
                    driver.close()
                }
            }
        } finally {
            archivo.delete()
        }
    }

    fun textos(driver: SqlDriver, sql: String): List<String> =
        driver.executeQuery(null, sql, { c -> QueryResult.Value(buildList { while (c.next().value) add(c.getString(0).orEmpty()) }) }, 0).value

    /** Todas las filas de [tabla], con todas sus columnas (`quote`), ordenadas: sirve para comparar antes y después. */
    fun volcar(driver: SqlDriver, tabla: String): List<String> {
        val columnas = textos(driver, "SELECT name FROM pragma_table_info('$tabla') ORDER BY cid")
        check(columnas.isNotEmpty()) { "la tabla $tabla no existe" }
        return textos(
            driver,
            "SELECT " + columnas.joinToString(" || '|' || ") { "quote($it)" } + " FROM $tabla ORDER BY " + columnas.joinToString(","),
        )
    }

    fun volcarTodo(driver: SqlDriver): Map<String, List<String>> = TABLAS.associateWith { volcar(driver, it) }

    /**
     * Como [volcarTodo] pero sin las filas `hcc_ausente_…` de `paciente_extra` (las marcas «Ya no figura en la web», E6.4b,
     * vivas o dadas de baja): sirve para comprobar que, además de marcar, no cambió ninguna otra fila.
     */
    fun volcarTodoSinMarcas(driver: SqlDriver): Map<String, List<String>> =
        volcarTodo(driver).mapValues { (_, filas) -> filas.filterNot { "'hcc_ausente_" in it } }

    /** Las marcas vigentes de [paciente] como `clave de ausencia=fecha`, ordenadas (las dadas de baja no cuentan). */
    fun marcasVigentes(driver: SqlDriver, paciente: Id = PACIENTE): List<String> = textos(
        driver,
        "SELECT substr(clave, 13) || '=' || valor FROM paciente_extra WHERE paciente_id = '$paciente' AND especialidad = 'clinica' " +
            "AND clave LIKE 'hcc!_ausente!_%' ESCAPE '!' AND deleted = 0 ORDER BY clave",
    )

    /** Cuántas filas de las tablas de la lectura quedaron con pendiente de envío (lo traído de la web nunca debe). */
    fun filasSucias(driver: SqlDriver): Int = TABLAS.sumOf { textos(driver, "SELECT COUNT(*) FROM $it WHERE dirty <> 0").single().toInt() }

    /** Una consulta ya traída (o del dispositivo, según [id] y [remotoId]) de [paciente], con la fila que escribiría la lectura. */
    suspend fun Salud360Db.sembrar(
        id: Id,
        remotoId: String = id.removePrefix("hcc-c"),
        paciente: Id = PACIENTE,
        medico: Id = MEDICO,
        estado: EstadoConsulta = EstadoConsulta.CERRADA,
        edad: String = "",
        dirty: Boolean = false,
        actualizada: Long = 111,
    ) = historiaClinicaQueries.upsertConsulta(
        Consulta(id, paciente, medico, "clinica", "consulta", LocalDate.parse("2026-10-30"), estado, edad, remotoId = remotoId)
            .toRow(actualizada, dirty = dirty),
    )

    /** La historia de la ficha de prueba sin nada cargado: todas las claves que el servidor siempre manda. */
    fun historiaVacia(ficha: Long = FICHA): RespuestaHistoriaDto =
        RespuestasClinicaDeEjemplo.historia(EjemplosConsultasClinica.HISTORIA_VACIA.replace("5002", "$ficha"))

    class Reloj(var millis: Long = 1_700_000_000_000L) {
        fun avanzar(ms: Long) { millis += ms }
    }

    class Contexto(val backend: HcClinicaBackend, val db: Salud360Db, val driver: SqlDriver, val api: ApiFalsa, val reloj: Reloj)

    /**
     * El backend con una [ApiFalsa], un reloj falso y una base temporal con el paciente de prueba. [adjuntos] es la API de adjuntos
     * (spec 005, T106): sin ella el backend queda como antes (no lista ni baja documentos).
     */
    fun conBackend(
        api: ApiFalsa = ApiFalsa(),
        vinculado: Boolean = true,
        adjuntos: ClinicaAdjuntosApi? = null,
        bloque: suspend Contexto.() -> Unit,
    ) = conBase(vinculado) { db, driver ->
        val reloj = Reloj()
        val backend = HcClinicaBackend(db, api, pacientes(db), { reloj.millis }, HcClinicaBackend.MAX_PAGINAS, adjuntos)
        Contexto(backend, db, driver, api, reloj).bloque()
    }

    /** Cómo se pidió una página de la lista. */
    class Pedido(val ficha: Long, val limite: Int, val desde: Int)

    /**
     * La web: la lista de [cabeceras], de la más nueva a la más vieja y en páginas de lo que pida la app; las consultas
     * de [consultas] y el [historial]. Anota cada pedido (`pedidos` son los de la lista) y en qué orden salieron (`orden`).
     */
    class ApiFalsa(
        var cabeceras: List<ConsultaCabeceraDto> = emptyList(),
        var omitidas: OmitidasConsultasDto = OmitidasConsultasDto(),
    ) : ConsultasClinicaApi {
        val pedidos = mutableListOf<Pedido>()
        val pedidosConsulta = mutableListOf<Long>()
        val pedidosHistoria = mutableListOf<Long>()

        /** `lista`, `consulta` o `historia`, en el orden en que salieron los pedidos. */
        val orden = mutableListOf<String>()

        /** Para armar una respuesta a medida de cada pedido de la lista; por defecto, la lista cortada como el servidor. */
        var respuesta: ((Pedido) -> RespuestaListaConsultasDto)? = null

        /** Número de pedido de la lista (desde 1) → error que lanza en vez de contestar. */
        val fallas = mutableMapOf<Int, Throwable>()

        /** Si no es null, cada pedido de la lista espera a que se abra. */
        var compuerta: CompletableDeferred<Unit>? = null

        /** Lo que contesta `GET consultas/{id}`, por id de la web; el que falta es un error del test. */
        val consultas = mutableMapOf<Long, RespuestaConsultaDto>()

        /** Id de la web → error que lanza `GET consultas/{id}` en vez de contestar. */
        val fallasConsulta = mutableMapOf<Long, Throwable>()

        /** Lo que contesta `GET pacientes/{id}/historia`; si es null, un paciente sin nada cargado de la ficha que se pidió. */
        var historial: RespuestaHistoriaDto? = null

        /** Si no es null, el historial falla con esto en vez de contestar. */
        var fallaHistorial: Throwable? = null

        override suspend fun listar(pacienteWebId: Long, limite: Int, desde: Int): RespuestaListaConsultasDto {
            val pedido = Pedido(pacienteWebId, limite, desde)
            pedidos += pedido
            orden += "lista"
            compuerta?.await()
            fallas[pedidos.size]?.let { throw it }
            respuesta?.let { return it(pedido) }
            val pagina = cabeceras.drop(desde).take(limite)
            return RespuestaListaConsultasDto(pagina, cabeceras.size, desde, if (desde + pagina.size < cabeceras.size) 1 else 0, omitidas)
        }

        override suspend fun consulta(id: Long): RespuestaConsultaDto {
            pedidosConsulta += id
            orden += "consulta"
            fallasConsulta[id]?.let { throw it }
            return consultas[id] ?: error("el test no preparó la consulta $id")
        }

        override suspend fun historia(pacienteWebId: Long): RespuestaHistoriaDto {
            pedidosHistoria += pacienteWebId
            orden += "historia"
            fallaHistorial?.let { throw it }
            return historial ?: historiaVacia(pacienteWebId)
        }

        /** Todos los pedidos que salieron, de cualquier ruta. */
        val totalDePedidos: Int get() = pedidos.size + pedidosConsulta.size + pedidosHistoria.size
    }
}
