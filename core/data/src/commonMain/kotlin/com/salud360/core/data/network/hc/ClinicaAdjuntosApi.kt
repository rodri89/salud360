package com.salud360.core.data.network.hc

import com.salud360.core.data.network.TobbException

/**
 * El único `tipo` de adjunto que esta versión escribe: la sección **Documentos** de la HC general (la tabla `consulta_pdfs`).
 * Las fotos de exámenes complementarios (`foto_examen`) quedaron **fuera de alcance** (T100 y T105): el servidor las rechaza
 * con `422 tipo_invalido` y la app no las manda.
 */
const val TIPO_ADJUNTO_DOCUMENTO = "documento"

/**
 * La API de adjuntos de la HC general en hclinica (spec 005, E4: RF-44 a RF-46), contra el contrato de
 * `md/API_SALUD360_CLINICA.md` de hclinica, «Escritura (E4)». Los ids de las rutas son los de la web: el de la consulta que
 * contestó `crear` y el del adjunto que vino en el alta o en la lista (como texto, igual que el de una carga). Cada operación
 * es **un pedido**: si falla lanza [TobbException] con su `status` y su `codigo` (`sin_token`, `token`, `sin_permiso`,
 * `fuera_de_cartera`, `consulta_no_propia`, `adjunto_no_propio`, `paciente_no_encontrado`, `consulta_no_encontrada`,
 * `adjunto_no_encontrado`, `consulta_cerrada`, `archivo_invalido`, `archivo_grande`, `tipo_invalido`,
 * `carpeta_no_escribible`, `escritura`, `consultas`; el `429` de Laravel llega sin código) y el cuerpo completo en
 * [TobbException.cuerpo] (el `campo` y el `motivo` de un `422 datos_invalidos`). **No reintenta**: eso es del envío diferido
 * (RF-6, RF-29), que repite con la misma clave sin duplicar (RF-15, D21). Es una interfaz para probar sin red.
 *
 * Esta capa no decide qué se sube ni cuándo: recibe lo ya decidido (el archivo, su nombre y su `Content-Type`, la clave de
 * repetibilidad y la consulta). Nada la llama todavía desde la cola de envío ni desde la pantalla (T104 y T107).
 */
interface ClinicaAdjuntosApi {
    /**
     * `POST consultas/{id}/adjuntos` multipart (RF-44): sube **un** archivo a la consulta [consultaWebId]. [clave] es
     * `a:<id local>` ([PayloadsClinica.claveDeAdjunto]), estable entre reintentos; repetirla devuelve la misma fila y un solo
     * archivo. [tipo] es [TIPO_ADJUNTO_DOCUMENTO] (el servidor valida el archivo por su **firma**, no por [mime] ni por
     * [nombre]: un contenido que no es PDF, JPG ni PNG es `422 archivo_invalido`). Un `413` del servidor web se lee como
     * `413 archivo_grande`: el cuerpo se cortó antes de llegar a Laravel.
     */
    suspend fun subir(
        consultaWebId: Long,
        clave: String,
        dispositivo: String,
        nombre: String,
        mime: String,
        bytes: ByteArray,
        tipo: String = TIPO_ADJUNTO_DOCUMENTO,
    ): RespuestaAdjuntoDto

    /**
     * `GET pacientes/{id}/adjuntos` (RF-45): lo que lista una consulta **nueva y en curso**: todos los documentos vigentes del
     * paciente, de todas sus consultas, cada uno con su `consulta_id`. Nunca uno sin consulta ni de otra HC (RF-46).
     */
    suspend fun listarDePaciente(pacienteWebId: Long): RespuestaAdjuntosDto

    /**
     * `GET consultas/{id}/adjuntos` (RF-45): lo que lista una consulta **previa** (cerrada, o abierta pero no la que se está
     * cargando): solo los documentos subidos en esa consulta.
     */
    suspend fun listarDeConsulta(consultaWebId: Long): RespuestaAdjuntosDto

    /**
     * `GET adjuntos/{id}/archivo` (RF-45): los bytes del archivo, **con la sesión**, nunca por un enlace público. El
     * `Content-Type` no viaja en esta llamada: el tipo (`pdf`, `jpeg` o `png`) lo dice la lista ([AdjuntoDeLaWebDto.tipo]) y
     * el servidor solo contesta un JSON (un error) en lugar del archivo cuando la fila está dada de baja o el archivo no está
     * en el disco, que [HcApiClient.descargar] rechaza.
     */
    suspend fun bajar(adjuntoWebId: String): ByteArray

    /**
     * `DELETE adjuntos/{id}` con cuerpo JSON (RF-46): baja lógica (`activo = 0`, el archivo queda en el disco) de un documento
     * que subió la app para este médico en esa consulta; lo que subió la web es `403 adjunto_no_propio`. Repetir la baja es
     * `ok`. [clave] es `a:<id local>-baja` ([PayloadsClinica.claveDeBajaDeAdjunto]) y [consultaWebId], la consulta del adjunto.
     */
    suspend fun borrar(adjuntoWebId: String, consultaWebId: Long, clave: String, dispositivo: String): RespuestaBajaDeAdjuntoDto

    /**
     * `GET adjuntos/limites` (D18): el límite efectivo para avisar **antes** de subir (RF-44). Solo lectura: no sube nada ni
     * escribe en la web.
     */
    suspend fun limites(): RespuestaLimitesDeAdjuntosDto
}

/** [ClinicaAdjuntosApi] contra hclinica, sobre el [HcApiClient] completo (el de solo lectura no puede subir ni dar de baja). */
class ClinicaAdjuntosApiRemota(private val cliente: HcApiClient) : ClinicaAdjuntosApi {
    override suspend fun subir(
        consultaWebId: Long,
        clave: String,
        dispositivo: String,
        nombre: String,
        mime: String,
        bytes: ByteArray,
        tipo: String,
    ): RespuestaAdjuntoDto {
        val respuesta = try {
            cliente.subir(
                "$RUTA_CONSULTAS/$consultaWebId/adjuntos",
                campos = mapOf("clave" to clave, "dispositivo" to dispositivo, "tipo" to tipo),
                nombre = nombre,
                mime = mime,
                bytes = bytes,
            )
        } catch (e: TobbException) {
            // El servidor web corta el cuerpo antes de que llegue a Laravel y contesta un `413` en HTML, sin JSON ni `codigo`.
            // Para la app es un archivo grande: es lo mismo que diría el servidor si lo hubiera podido mirar (RF-44).
            if (e.status == 413 && e.codigo == null) throw TobbException(413, CODIGO_ARCHIVO_GRANDE, MENSAJE_413, cuerpo = e.cuerpo)
            throw e
        }
        return cliente.leerTodo(RespuestaAdjuntoDto.serializer(), respuesta)
    }

    override suspend fun listarDePaciente(pacienteWebId: Long): RespuestaAdjuntosDto =
        cliente.leerTodo(RespuestaAdjuntosDto.serializer(), cliente.get("$RUTA_PACIENTES/$pacienteWebId/adjuntos"))

    override suspend fun listarDeConsulta(consultaWebId: Long): RespuestaAdjuntosDto =
        cliente.leerTodo(RespuestaAdjuntosDto.serializer(), cliente.get("$RUTA_CONSULTAS/$consultaWebId/adjuntos"))

    override suspend fun bajar(adjuntoWebId: String): ByteArray = cliente.descargar("$RUTA_ADJUNTOS/$adjuntoWebId/archivo")

    override suspend fun borrar(
        adjuntoWebId: String,
        consultaWebId: Long,
        clave: String,
        dispositivo: String,
    ): RespuestaBajaDeAdjuntoDto = cliente.leerTodo(
        RespuestaBajaDeAdjuntoDto.serializer(),
        cliente.delete("$RUTA_ADJUNTOS/$adjuntoWebId", PayloadsClinica.bajaDeAdjunto(clave, dispositivo, consultaWebId).toString()),
    )

    override suspend fun limites(): RespuestaLimitesDeAdjuntosDto =
        cliente.leerTodo(RespuestaLimitesDeAdjuntosDto.serializer(), cliente.get("$RUTA_ADJUNTOS/limites"))

    private companion object {
        const val RUTA_CONSULTAS = "consultas"
        const val RUTA_PACIENTES = "pacientes"
        const val RUTA_ADJUNTOS = "adjuntos"
        const val CODIGO_ARCHIVO_GRANDE = "archivo_grande"
        const val MENSAJE_413 = "El archivo supera el tamaño que acepta el servidor web (413)."
    }
}
