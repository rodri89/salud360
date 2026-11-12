package com.salud360.core.data.hcgeneral

import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.network.hc.CargaLaboratorioDto
import com.salud360.core.data.network.hc.ConsultaCabeceraDto
import com.salud360.core.data.network.hc.ExamenFisicoDto
import com.salud360.core.data.network.hc.LaboratoriosDto
import com.salud360.core.data.network.hc.PayloadsClinica
import com.salud360.core.data.network.hc.RespuestaConsultaDto
import com.salud360.core.data.network.hc.RespuestaHistoriaDto
import com.salud360.core.data.network.hc.RespuestaListaConsultasDto
import com.salud360.core.data.network.hc.SeccionesConsultaDto
import com.salud360.core.model.Id
import com.salud360.core.model.especialidad.CatalogoHc
import com.salud360.core.model.especialidad.CodigosHc
import com.salud360.core.model.especialidad.SeccionDef
import com.salud360.core.model.especialidad.SeccionesComunes
import com.salud360.core.model.especialidad.TipoConsultaHc
import com.salud360.core.model.especialidad.TipoSeccion
import com.salud360.core.model.hc.Antecedente
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.hc.ExamenFisico
import com.salud360.core.model.hc.Laboratorio
import com.salud360.core.model.hc.MedicoPreferencia
import com.salud360.core.model.hc.Pendiente
import com.salud360.core.model.hc.RegistroClinico
import com.salud360.core.model.hc.SeccionValor
import com.salud360.core.model.pacientes.PacienteExtra
import kotlinx.datetime.LocalDate

// Lectura de la HC general (spec 002, entrega L): ids locales y conversión de las respuestas de hclinica al modelo de
// la app. Todo puro: sin red, sin base, sin Compose. Quien guarda (`HcClinicaBackend`, E6.3 a E6.4b) arma las filas con
// `filaDeLaWeb` (siempre `dirty = 0`) y escribe solo lo que estas funciones devuelven.
//
// Reglas que valen para todo el archivo (Q15 = nunca borrar; RF-54, RF-55, RF-63):
// - «No hay dato» es `null` o una lista sin ese elemento, nunca un valor vacío: un valor vacío, en blanco o que no viene
//   no pisa lo ya cargado. Un `"0"` es un dato.
// - Los textos se guardan tal cual vinieron (ñ, emoji, saltos de línea y espacios incluidos). «En blanco» solo se usa
//   para decidir si hay dato, nunca para recortar.
// - No se re-mapean nombres: el servidor ya devuelve los ids de la app (Q12). Lo que el catálogo no conoce se guarda
//   igual y se informa con [desconocidos].
// - Nada de esto borra ni vacía algo ya traído. Lo que la web deja de mandar solo recibe una marca (`marcarAusentes`).

/**
 * Prefijo de los ids locales de todo lo que se trae de la web de la HC general (spec 002, "Identificadores locales"):
 * consulta `hcc-c<id>`, médico de otro `hcc-m<id>`, carga de laboratorio o de peso `hcc-l<tipo>-<id>`, fila de estudio,
 * examen o interconsulta `hcc-r<tipo>-<id>`, pendiente `hcc-p<id>` y antecedente familiar `hcc-a<ficha>-<clave>`. Todos
 * llevan el tipo (la web repite los números entre sus tablas: veda, biopsia y TC comparten el 1797 en el fixture) y salen
 * solo de lo que la web mandó, así que traer lo mismo dos veces da el mismo id. Disjunto de `hcp-c…` (pediatría), de
 * `hc-clinica-p…` (paciente de clínica), de `tobb-…` (turnos) y de los ids del dispositivo (UUID: la `h` no es hexadecimal).
 */
const val PREFIJO_ID_WEB = "hcc-"

/** Id local del médico de una consulta de la web que no cargó quien tiene la sesión. */
const val MEDICO_WEB_DESCONOCIDO = PREFIJO_ID_WEB + "m0"

const val TIPO_LABORATORIO_GENERAL = "laboratorio_general"
const val TIPO_LABORATORIO_REDUCIDO = "laboratorio_reducido"
const val TIPO_PESO = "peso"
const val TIPO_EXAMEN_COMPLEMENTARIO = "examen_complementario"
const val TIPO_INTERCONSULTA = "interconsulta"

/** Los cinco estudios por paciente de la web, con el id del tipo de registro de la app. */
val TIPOS_ESTUDIO_WEB = listOf("eco_abdominal", "veda", "biopsia_hepatica", "estudios_cardiologicos", "tc_rmn")

const val CATEGORIA_FAMILIARES = "familiares"

/** La nota libre de los antecedentes familiares: se guarda como la pantalla (marca puesta y el texto en el detalle). */
const val CLAVE_NOTA = "nota"

/** La nota de la pestaña de antecedentes familiares del perfil 4: aparte de [CLAVE_NOTA], que es otra cosa. */
const val CLAVE_NOTA_PERFIL4 = "nota_perfil4"

/** Clave de `medico_preferencia` con el nombre de un médico de la web (`medico_id = hcc-m<id>`). */
const val CLAVE_NOMBRE_MEDICO_WEB = "nombre"

/** Campo de `seccion_valor` donde va el texto de una sección de texto. */
const val CAMPO_TEXTO = "texto"

private const val CAMPO_DETALLE = "detalle"
private const val CAMPO_NUMERO = "numero"
private const val SECCION_ALIMENTACION = "alimentacion"
private const val SECCION_SEROLOGIAS = "serologias"
private const val SECCION_CHILD_PUGH = "child_pugh"
private val PUNTOS_CHILD_PUGH = setOf("1", "2", "3")

/**
 * La consulta vino de la web de la HC general (`remoto_id` no vacío o id `hcc-…`): la web es su dueña y la app no la
 * edita, no la cierra ni la retoma como propia. Solo tiene sentido frente a un backend de solo lectura: en pediatría una
 * consulta creada en el dispositivo también tiene `remoto_id` una vez enviada. La consulta SQL `consultaAbiertaLocal`
 * aplica el mismo criterio (con [PREFIJO_ID_WEB] como parámetro) y un test las compara.
 *
 * Una [Consulta.propia] (la creó esta app, spec 005) tiene `remoto_id` una vez enviada y **no** es de la web (RF-62): solo el
 * prefijo `hcc-` la hace de la web, que es como la lectura guarda lo que creó otro dispositivo (RF-64). `consultaAbiertaPropia`
 * aplica el mismo criterio. Sin la marca, todo igual que antes.
 */
fun Consulta.esDeLaWeb(): Boolean = id.startsWith(PREFIJO_ID_WEB) || (remotoId.isNotBlank() && !propia)

/** La cargó el médico de la sesión. Las ajenas de la web llevan `hcc-m<id>`, que nunca es el id de un médico de turnos (RF-25). */
fun Consulta.esPropia(medicoSesionId: Id): Boolean = medicoId == medicoSesionId

// ---------- Ids (RF-52) ----------

fun idConsultaWeb(idWeb: Long): Id = "${PREFIJO_ID_WEB}c$idWeb"

fun idMedicoWeb(idWeb: Long): Id = "${PREFIJO_ID_WEB}m$idWeb"

/** [tipo]: `laboratorio_general`, `laboratorio_reducido` o `peso`. */
fun idCargaWeb(tipo: String, idWeb: Long): Id = "${PREFIJO_ID_WEB}l$tipo-$idWeb"

/** [tipo]: el del registro de la app (uno de [TIPOS_ESTUDIO_WEB], [TIPO_EXAMEN_COMPLEMENTARIO] o [TIPO_INTERCONSULTA]). */
fun idFilaWeb(tipo: String, idWeb: Long): Id = "${PREFIJO_ID_WEB}r$tipo-$idWeb"

fun idPendienteWeb(idWeb: Long): Id = "${PREFIJO_ID_WEB}p$idWeb"

/** [pacienteWebId] es el de la ficha de la web (`paciente_id` de la historia); [clave] es la de la casilla o una nota. */
fun idAntecedenteWeb(pacienteWebId: Long, clave: String): Id = "${PREFIJO_ID_WEB}a$pacienteWebId-$clave"

// ---------- Lo común ----------

private fun Long.valido() = this > 0

private fun String?.oElLocal(local: String): String = if (isNullOrBlank()) local else this

/** La web guarda `1000-01-01` o `0000-00-00` donde no hay fecha. */
private fun fechaDeLaWeb(texto: String?): LocalDate? {
    val dia = texto?.takeIf { it.length >= 10 }?.substring(0, 10) ?: return null
    if (dia == "1000-01-01" || dia == "0000-00-00") return null
    return runCatching { LocalDate.parse(dia) }.getOrNull()
}

/**
 * El id local de una consulta de la web, solo si esa consulta está en el dispositivo; si no, `null`. [vinculadas] son las consultas
 * **propias** ya enviadas (spec 005, RF-33): el id de la web  su id local; lo de la web que cuelga de una de ellas cuelga de la propia, no de
 * una `hcc-c<id>` que no existe.
 */
private fun consultaLocalDe(consultaWebId: Long?, consultasLocales: Set<Id>, vinculadas: Map<Long, Id> = emptyMap()): Id? {
    val id = consultaWebId?.takeIf { it.valido() } ?: return null
    return vinculadas[id] ?: idConsultaWeb(id).takeIf { it in consultasLocales }
}

// ---------- Consulta ----------

/**
 * La cabecera de una consulta de la web como [Consulta] del dispositivo, con `especialidad = clinica` y `remoto_id` = el
 * id de la web. `null` si no se la puede mostrar sin inventar algo: id, estado, tipo (`control` o `telemedicina`) o fecha
 * que no se reconocen. La fecha es `fecha` y, si no sirve, el día de `created_at`. El médico es el de la sesión si la
 * consulta es [ConsultaCabeceraDto.propia] y, si no, `hcc-m<id>` (RF-25). `updatedAt` queda en `null`: la fila la sella
 * quien escribe, y solo si hay algo que escribir ([fusionarConsulta]).
 */
fun consultaDeLaWeb(c: ConsultaCabeceraDto, pacienteLocalId: Id, medicoSesionId: Id): Consulta? {
    if (!c.id.valido()) return null
    val tipo = tipoDeLaWeb(c) ?: return null
    val estado = when (c.estado) {
        "ABIERTA" -> EstadoConsulta.ABIERTA
        "CERRADA" -> EstadoConsulta.CERRADA
        else -> return null
    }
    val fecha = fechaDeLaWeb(c.fecha) ?: fechaDeLaWeb(c.createdAt) ?: return null
    val medico = if (c.propia) medicoSesionId else c.medicoId?.takeIf { it.valido() }?.let(::idMedicoWeb) ?: MEDICO_WEB_DESCONOCIDO
    return Consulta(
        id = idConsultaWeb(c.id), pacienteId = pacienteLocalId, medicoId = medico, especialidad = CodigosHc.GENERAL,
        tipo = tipo, fecha = fecha, estado = estado, edadMostrar = c.edadMostrar, updatedAt = null, remotoId = c.id.toString(),
    )
}

private fun tipoDeLaWeb(c: ConsultaCabeceraDto): String? = when {
    c.tipo != null -> TipoConsultaHc.deCodigo(c.tipo)?.codigo
    c.tipoNumero == 1 -> TipoConsultaHc.CONSULTA.codigo
    c.tipoNumero == 4 -> TipoConsultaHc.TELEMEDICINA.codigo
    else -> null
}

/**
 * Qué escribir de la cabecera [web] sobre la [local] que ya estaba: la de la web, salvo que una edad vacía no pisa la que
 * la consulta ya tenía. `null` si no cambia nada (no se reescribe: el `updated_at` no se toca). El llamador no la usa
 * si la local tiene cambios sin enviar.
 */
fun fusionarConsulta(web: Consulta, local: Consulta?): Consulta? {
    if (local == null) return web
    // La web no sabe de la marca (`consultaDeLaWeb` nunca la pone): la de la fila local se conserva (RF-8, RF-33).
    val nueva = web.copy(edadMostrar = web.edadMostrar.ifBlank { local.edadMostrar }, updatedAt = local.updatedAt, propia = local.propia)
    return nueva.takeIf { it != local }
}

/**
 * Qué escribir de la cabecera [web] sobre la consulta **propia** ya enviada [propia] (spec 005, RF-33): la de la web, con las mismas
 * reglas de [fusionarConsulta] (una edad vacía no pisa; se conserva `propia`), pero conservando su id local, su médico y su `remoto_id`
 * —la web la trae como `hcc-c<id>`— y con dos protecciones: una **anulada** no se toca (es terminal: si la web la mostrara abierta
 * sería una baja que no llegó) y una **cerrada** sigue cerrada (la web no reabre, D13: una lista que llegó vieja no la vuelve a abierta).
 * `null` si no cambia nada. El llamador no la usa si la propia tiene cambios sin enviar ni si es de otro médico.
 */
fun fusionarConsultaPropia(web: Consulta, propia: Consulta): Consulta? {
    if (propia.estado == EstadoConsulta.ANULADA) return null
    val estado = if (propia.estado == EstadoConsulta.CERRADA) EstadoConsulta.CERRADA else web.estado
    return fusionarConsulta(
        web.copy(
            id = propia.id, pacienteId = propia.pacienteId, medicoId = propia.medicoId, especialidad = propia.especialidad,
            estado = estado, remotoId = propia.remotoId,
        ),
        propia,
    )
}
/** El nombre del médico de una consulta ajena, para `medico_preferencia`; `null` para las propias o sin nombre. */
fun nombreMedicoDeLaWeb(c: ConsultaCabeceraDto): MedicoPreferencia? {
    if (c.propia) return null
    val medico = c.medicoId?.takeIf { it.valido() } ?: return null
    val nombre = c.medicoNombre?.takeIf { it.isNotBlank() } ?: return null
    return MedicoPreferencia(idMedicoWeb(medico), CLAVE_NOMBRE_MEDICO_WEB, nombre)
}

/** De la más reciente a la más antigua: por `(created_at, id)` y, si el servidor no manda `created_at`, por `(fecha, id)`. */
fun ordenarMasRecientesPrimero(cabeceras: List<ConsultaCabeceraDto>): List<ConsultaCabeceraDto> =
    cabeceras.sortedWith(
        compareByDescending<ConsultaCabeceraDto> { c -> c.createdAt?.takeIf { it.isNotBlank() } ?: c.fecha }.thenByDescending { it.id },
    )

// ---------- Secciones de una consulta ----------

/**
 * Lo que la web guarda por consulta como `seccion_valor` de [consultaLocalId]: los textos en el campo `texto`, la
 * alimentación como `"<0|1>|<detalle>"`, las serologías `SI`/`NO` y los puntos de Child-Pugh `"1"` a `"3"`. Solo entra lo
 * que tiene dato: un texto en blanco, `"0|"`, una serología que no es `SI`/`NO` o un punto fuera de rango no se escriben.
 * Las secciones que la web no tiene (diagnósticos, audio, fotos, documentos, antecedentes personales, enfermedades) no
 * aparecen nunca.
 */
fun valoresDeLaWeb(consultaLocalId: Id, s: SeccionesConsultaDto): List<SeccionValor> = buildList {
    s.textos().forEach { (seccion, texto) ->
        if (texto.isNotBlank()) add(SeccionValor(consultaLocalId, seccion, CAMPO_TEXTO, texto))
    }
    s.alimentacion.forEach { (campo, valor) ->
        if (tieneDato(valor)) add(SeccionValor(consultaLocalId, SECCION_ALIMENTACION, campo, valor))
    }
    s.serologias.forEach { (campo, valor) ->
        if (valor == "SI" || valor == "NO") add(SeccionValor(consultaLocalId, SECCION_SEROLOGIAS, campo, valor))
    }
    s.childPugh.forEach { (campo, valor) ->
        if (valor in PUNTOS_CHILD_PUGH) add(SeccionValor(consultaLocalId, SECCION_CHILD_PUGH, campo, valor))
    }
}

/**
 * El examen físico de la consulta con lo que trae la web sobre el [local] (o uno vacío): solo reemplaza los campos que
 * vienen con dato, el resto se conserva (la frecuencia cardíaca y la circunferencia abdominal de antes también: la web no
 * las tiene). `null` si no hay nada que escribir. Un `"0"` o `"0.0"` que llega es un dato: lo «sin cargar» (`"0.00"`) ya
 * lo filtró el servidor.
 */
fun fusionarExamenFisico(consultaLocalId: Id, e: ExamenFisicoDto, local: ExamenFisico?): ExamenFisico? {
    val base = local ?: ExamenFisico(consultaLocalId)
    val nuevo = base.copy(
        peso = e.peso.oElLocal(base.peso),
        talla = e.talla.oElLocal(base.talla),
        tensionArterial = e.tensionArterial.oElLocal(base.tensionArterial),
        imc = e.imc.oElLocal(base.imc),
        nota = e.nota.oElLocal(base.nota),
    )
    return nuevo.takeIf { it != base }
}

/**
 * Qué escribir de lo que trae la web en el contenido de una consulta **propia** ya enviada (spec 005, RF-33, RF-34, T27 decisión 5, T65-a).
 * Un texto de los que esta app envía ([PayloadsClinica.SECCIONES_DE_TEXTO]) se actualiza solo si el dispositivo **no** lo tiene pendiente
 * y si lo que hay acá es lo mismo que la web tenía la última vez ([loEnviado]) o está vacío: un texto distinto de lo último que la web tuvo
 * y sin pendiente es algo que el médico **descartó** y se conserva solo en el dispositivo. Los formularios (E2) siguen la misma regla:
 * se actualizan si no están pendientes, si acá no había nada y si lo que hay es lo mismo que la web tenía ([LoEnviado.formularios], con el
 * valor canónico); un valor distinto y sin pendiente también se conserva. Un valor vacío no viene en [web] (nunca pisa).
 */
fun valoresDeLaWebParaLaPropia(
    web: List<SeccionValor>,
    locales: Map<Pair<String, String>, SeccionValor>,
    pendientes: Set<Pair<String, String>>,
    loEnviado: LoEnviado,
): List<SeccionValor> = web.filter { w ->
    val clave = w.seccion to w.campo
    val local = locales[clave]
    when {
        clave in pendientes -> false
        local?.valor == w.valor -> false
        w.seccion in PayloadsClinica.SECCIONES_DE_TEXTO -> local == null || local.valor.isBlank() || local.valor == loEnviado.valores["${w.seccion}/${w.campo}"]
        else -> local == null || esLoQueLaWebTeniaDelFormulario(w, local.valor, loEnviado)
    }
}

/**
 * [local] es lo que la web tenía de ese campo de formulario (`LoEnviado.formularios`, valor canónico) o acá no había nada: en los dos casos
 * lo que trae la web se escribe. Un valor distinto y sin pendiente es algo que el médico descartó y se conserva (RF-64).
 */
private fun esLoQueLaWebTeniaDelFormulario(w: SeccionValor, local: String, loEnviado: LoEnviado): Boolean {
    if (local.isBlank()) return true
    val enviado = loEnviado.formularios["${w.seccion}/${w.campo}"] ?: return false
    return local == enviado || PayloadsClinica.valorDeFormulario(w.seccion, w.campo, local) == enviado
}

/**
 * El examen físico de la consulta **propia** con lo que trae la web (misma idea que [valoresDeLaWebParaLaPropia]): cada campo que la web
 * trae con dato reemplaza al de acá solo si acá está vacío o es lo último que la web tenía ([loExamen], por nombre del contrato); un
 * campo distinto y sin pendiente se conserva (descartado). `null` si no hay nada que escribir. El llamador no la usa si la fila está sucia.
 */
fun fusionarExamenDeLaPropia(consultaLocalId: Id, e: ExamenFisicoDto, local: ExamenFisico?, loExamen: Map<String, String>): ExamenFisico? {
    val base = local ?: ExamenFisico(consultaLocalId)
    fun campo(nombre: String, web: String?, actual: String, medida: Boolean): String {
        if (web.isNullOrBlank()) return actual
        val vacio = if (medida) esVacioDeMedida(actual) else esVacio(actual)
        return if (vacio || actual == loExamen[nombre]) web else actual
    }
    val nuevo = base.copy(
        peso = campo("peso", e.peso, base.peso, medida = true),
        talla = campo("talla", e.talla, base.talla, medida = true),
        tensionArterial = campo("tension_arterial", e.tensionArterial, base.tensionArterial, medida = true),
        imc = campo("imc", e.imc, base.imc, medida = true),
        nota = campo("nota", e.nota, base.nota, medida = false),
    )
    return nuevo.takeIf { it != base }
}

/**
 * Lo que la web tiene de la consulta después de esta lectura ([previo] + lo que trajo con dato): así lo que el médico cambie después se
 * compara contra eso, también un vaciado a propósito (RF-69). Un valor vacío de la web **no** cambia lo anotado (puede ser un campo que
 * nunca se cargó o que se vació allá; el envío siguiente decide por lo que escribió el médico). Incluye los **formularios** (T65-a,
 * alimentación, serologías y Child-Pugh) con el valor canónico que manda la API, así el plan de envío compara contra el mismo texto.
 */
fun loEnviadoTrasLaLectura(previo: LoEnviado, s: SeccionesConsultaDto, e: ExamenFisicoDto): LoEnviado {
    val textos = s.textos().filterValues { it.isNotBlank() }.mapKeys { "${it.key}/$CAMPO_TEXTO" }
    val examen = listOf(
        "peso" to e.peso, "talla" to e.talla, "tension_arterial" to e.tensionArterial, "imc" to e.imc, "nota" to e.nota,
    ).filter { !it.second.isNullOrBlank() }.associate { it.first to it.second!! }
    return previo.copy(
        valores = previo.valores + textos,
        examen = previo.examen + examen,
        formularios = previo.formularios + formulariosDeLaWeb(s),
    )
}

/**
 * Los formularios que la web tiene con dato, como los guarda `LoEnviado` (`<sección>/<campo>` -> valor canónico, T62-a): las mismas
 * claves y los mismos filtros que [valoresDeLaWeb] (alimentación con dato, serología `SI`/`NO`, Child-Pugh `1` a `3`). Lo que la web no
 * manda no cambia lo anotado.
 */
private fun formulariosDeLaWeb(s: SeccionesConsultaDto): Map<String, String> = buildMap {
    s.alimentacion.forEach { (campo, valor) ->
        if (tieneDato(valor)) put("$SECCION_ALIMENTACION/$campo", PayloadsClinica.valorDeFormulario(SECCION_ALIMENTACION, campo, valor) ?: valor)
    }
    s.serologias.forEach { (campo, valor) -> if (valor == "SI" || valor == "NO") put("$SECCION_SEROLOGIAS/$campo", valor) }
    s.childPugh.forEach { (campo, valor) -> if (valor in PUNTOS_CHILD_PUGH) put("$SECCION_CHILD_PUGH/$campo", valor) }
}
private fun camposDelExamen(peso: String?, talla: String?, tension: String?, imc: String?, nota: String?): List<String> = listOf(
    "peso" to peso, "talla" to talla, "tension_arterial" to tension, "imc" to imc, "nota" to nota,
).filter { !it.second.isNullOrBlank() }.map { it.first }

private fun camposDelExamen(e: ExamenFisicoDto) = camposDelExamen(e.peso, e.talla, e.tensionArterial, e.imc, e.nota)

/**
 * Las claves de ausencia de los campos del examen físico [e] del dispositivo que la web manda (peso, talla, tensión, IMC y nota)
 * y tienen dato: lo que ya se trajo y [marcarAusentes] podría marcar. La frecuencia cardíaca y los demás campos son de la app.
 */
fun clavesDelExamenTraido(consultaLocalId: Id, e: ExamenFisico): Set<String> =
    camposDelExamen(e.peso, e.talla, e.tensionArterial, e.imc, e.nota).map { claveDeCampo(consultaLocalId, SeccionesComunes.EXAMEN_FISICO, it) }.toSet()

// ---------- Antecedentes familiares ----------

private class AntecedenteWeb(val clave: String, val flag: Boolean, val detalle: String)

/**
 * Las casillas marcadas (o con detalle) y las dos notas. Una casilla en 0 sin detalle no se manda y no se inventa.
 */
private fun antecedentesWeb(h: RespuestaHistoriaDto): List<AntecedenteWeb> = buildList {
    h.antecedentesFamiliares?.let { a ->
        a.casillas.forEach { (clave, casilla) ->
            val detalle = casilla.detalle?.takeIf { it.isNotBlank() }.orEmpty()
            if (casilla.flag != 0 || detalle.isNotEmpty()) add(AntecedenteWeb(clave, casilla.flag != 0, detalle))
        }
        a.nota?.takeIf { it.isNotBlank() }?.let { add(AntecedenteWeb(CLAVE_NOTA, true, it)) }
    }
    h.antecedentesFamiliaresPerfil4?.nota?.takeIf { it.isNotBlank() }?.let { add(AntecedenteWeb(CLAVE_NOTA_PERFIL4, true, it)) }
}

/** Los antecedentes familiares del paciente, con el id determinista de [idAntecedenteWeb] y sin consulta (son del paciente). */
fun antecedentesDeLaWeb(pacienteLocalId: Id, h: RespuestaHistoriaDto): List<Antecedente> =
    antecedentesWeb(h).map {
        Antecedente(
            id = idAntecedenteWeb(h.pacienteId, it.clave), pacienteId = pacienteLocalId, especialidad = CodigosHc.GENERAL,
            categoria = CATEGORIA_FAMILIARES, clave = it.clave, flag = it.flag, detalle = it.detalle, consultaId = null, activo = true,
        )
    }

/**
 * Qué escribir del antecedente [web] sabiendo los [existentes] del paciente: `null` si el dispositivo ya tiene una fila de
 * esa clave que no es la de la web, o que está sucia ([sucias]): lo del dispositivo manda y no se duplica (RF-54). Si la
 * fila existente es la de la web, un detalle vacío no borra el que ya tenía; `null` si no cambia nada.
 */
fun fusionarAntecedente(web: Antecedente, existentes: List<Antecedente>, sucias: Set<Id>): Antecedente? {
    val mismos = existentes.filter {
        it.pacienteId == web.pacienteId && it.especialidad == web.especialidad && it.categoria == web.categoria && it.clave == web.clave
    }
    if (mismos.any { it.id != web.id || it.id in sucias }) return null
    val local = mismos.firstOrNull() ?: return web
    return web.copy(detalle = web.detalle.ifBlank { local.detalle }).takeIf { it != local }
}

// ---------- Laboratorio y peso ----------

private fun cargasPorTipo(l: LaboratoriosDto) = listOf(
    TIPO_LABORATORIO_GENERAL to l.laboratorioGeneral,
    TIPO_LABORATORIO_REDUCIDO to l.laboratorioReducido,
    TIPO_PESO to l.peso,
)

/** Una carga se convierte si tiene id, fecha y al menos un valor (D1: las que la web guarda sin fecha no llegan). */
private fun cargaValida(c: CargaLaboratorioDto) =
    c.id.valido() && fechaDeLaWeb(c.fecha) != null && c.valores.any { it.value.isNotBlank() }

/**
 * Las cargas por paciente y fecha, con `remoto_id` = el id de la carga en la web (spec 005, T86-a): es con lo que la lectura empareja
 * una carga que esta app ya envió (su id local) con la misma que trae `historia`, y así no nace una `hcc-l<tipo>-<id>` duplicada. La
 * consulta de origen es la local `hcc-c<id>` si está en [consultasLocales] y, si no, `null`: la carga se guarda igual, sin consulta. Los
 * valores vacíos se descartan.
 */
fun laboratoriosDeLaWeb(pacienteLocalId: Id, l: LaboratoriosDto, consultasLocales: Set<Id>, vinculadas: Map<Long, Id> = emptyMap()): List<Laboratorio> =
    cargasPorTipo(l).flatMap { (tipo, cargas) ->
        cargas.filter(::cargaValida).mapNotNull { c ->
            val fecha = fechaDeLaWeb(c.fecha) ?: return@mapNotNull null
            Laboratorio(
                id = idCargaWeb(tipo, c.id), pacienteId = pacienteLocalId, consultaId = consultaLocalDe(c.consultaId, consultasLocales, vinculadas),
                especialidad = CodigosHc.GENERAL, tipo = tipo, fecha = fecha, valores = c.valores.filterValues { it.isNotBlank() },
                remotoId = c.id.toString(),
            )
        }
    }

/**
 * Qué escribir de la carga [web] sobre la [local] de la misma carga: los valores que vienen pisan, los que no vienen se conservan, y la
 * consulta y el `remoto_id` que ya tenía no se pierden. El llamador la usa para una carga de la web (`hcc-l…`) y también para una carga
 * **propia** ya enviada, a la que le pasa [web] con su id local, su consulta y su `activo` (spec 005, RF-33, T86-a): por eso una carga
 * dada de baja en el dispositivo no se reactiva aunque la web todavía la devuelva. `null` si no cambia nada.
 */
fun fusionarLaboratorio(web: Laboratorio, local: Laboratorio?): Laboratorio? {
    if (local == null) return web
    val nueva = web.copy(
        consultaId = web.consultaId ?: local.consultaId,
        valores = local.valores + web.valores,
        remotoId = web.remotoId.ifBlank { local.remotoId },
    )
    return nueva.takeIf { it != local }
}

/**
 * La carga que trae la web como carga **propia** ya enviada [propia] (spec 005, RF-33, T86-a): se le devuelve su id local, su consulta, su
 * `activo` y su `remoto_id` —la web la trae como `hcc-l<tipo>-<id>`, pero es la misma fila del dispositivo— y así [fusionarLaboratorio] la
 * refresca sin duplicarla ni moverla de consulta.
 */
fun cargaDeLaWebParaLaPropia(web: Laboratorio, propia: Laboratorio): Laboratorio =
    web.copy(id = propia.id, consultaId = propia.consultaId ?: web.consultaId, activo = propia.activo, remotoId = propia.remotoId.ifBlank { web.remotoId })

// ---------- Estudios, exámenes complementarios e interconsultas ----------

private class RegistroWeb(val tipo: String, val id: Long, val consultaId: Long?, val fecha: String?, val campos: Map<String, String>)

private fun registrosWeb(h: RespuestaHistoriaDto): List<RegistroWeb> = buildList {
    val e = h.estudios
    listOf(
        TIPOS_ESTUDIO_WEB[0] to e.ecoAbdominal, TIPOS_ESTUDIO_WEB[1] to e.veda, TIPOS_ESTUDIO_WEB[2] to e.biopsiaHepatica,
        TIPOS_ESTUDIO_WEB[3] to e.estudiosCardiologicos, TIPOS_ESTUDIO_WEB[4] to e.tcRmn,
    ).forEach { (tipo, estudios) ->
        estudios.filter { it.id.valido() && it.detalle.isNotBlank() }
            .forEach { add(RegistroWeb(tipo, it.id, it.consultaId, it.fecha, mapOf(CAMPO_DETALLE to it.detalle))) }
    }
    h.examenesComplementarios.forEach { x ->
        val campos = camposDeTexto("solicito" to x.solicito, "respuesta" to x.respuesta)
        if (x.id.valido() && campos.isNotEmpty()) {
            add(RegistroWeb(TIPO_EXAMEN_COMPLEMENTARIO, x.id, x.consultaId, x.fecha, campos + numero(x.numero)))
        }
    }
    h.interconsultas.forEach { x ->
        val campos = camposDeTexto("especialista" to x.especialista, "solicito" to x.solicito, "respuesta" to x.respuesta)
        if (x.id.valido() && campos.isNotEmpty()) {
            add(RegistroWeb(TIPO_INTERCONSULTA, x.id, x.consultaId, x.fecha, campos + numero(x.numero)))
        }
    }
}

private fun camposDeTexto(vararg pares: Pair<String, String?>): Map<String, String> =
    pares.mapNotNull { (clave, valor) -> valor?.takeIf { it.isNotBlank() }?.let { clave to it } }.toMap()

/** El `numero` de orden de la web (exámenes e interconsultas se muestran por él): se guarda como un campo más. */
private fun numero(n: Int?): Map<String, String> = n?.let { mapOf(CAMPO_NUMERO to it.toString()) }.orEmpty()

/**
 * Los estudios de la web (`detalle`), los exámenes complementarios (`solicito`, `respuesta`) y las interconsultas
 * (`especialista`, `solicito`, `respuesta`), cada uno con el id de su tipo, `remoto_id` = el id de la web y, si la web lo
 * mandó, el `numero` de orden como campo. Una fila sin texto no se convierte; un campo sin texto no se inventa.
 */
fun registrosDeLaWeb(pacienteLocalId: Id, h: RespuestaHistoriaDto, consultasLocales: Set<Id>, vinculadas: Map<Long, Id> = emptyMap()): List<RegistroClinico> =
    registrosWeb(h).map {
        RegistroClinico(
            id = idFilaWeb(it.tipo, it.id), pacienteId = pacienteLocalId, consultaId = consultaLocalDe(it.consultaId, consultasLocales, vinculadas),
            especialidad = CodigosHc.GENERAL, tipo = it.tipo, fecha = fechaDeLaWeb(it.fecha), campos = it.campos,
            activo = true, remotoId = it.id.toString(),
        )
    }

/**
 * Qué escribir del registro [web] sobre el [local] del mismo id: los campos que vienen pisan, los que no vienen se
 * conservan, y la consulta y la fecha que ya tenía no vuelven a `null`. `null` si no cambia nada.
 */
fun fusionarRegistro(web: RegistroClinico, local: RegistroClinico?): RegistroClinico? {
    if (local == null) return web
    val nuevo = web.copy(
        consultaId = web.consultaId ?: local.consultaId, fecha = web.fecha ?: local.fecha, campos = local.campos + web.campos,
    )
    return nuevo.takeIf { it != local }
}

/**
 * Los campos de un estudio tal como los guarda `LoEnviado.registros` (spec 005, T62-b y T65-a): la `fecha` y el `detalle` (`""` si está vacío).
 * Es lo que el plan de envío compara para decidir si el estudio cambió y lo que el servidor escribe (la fecha y el detalle juntos).
 */
fun camposConfirmadosDelEstudio(r: RegistroClinico): Map<String, String> = buildMap {
    r.fecha?.let { put(CAMPO_FECHA_DEL_REGISTRO, it.toString()) }
    put(CAMPO_DETALLE_DEL_REGISTRO, if (esVacio(r.campos[CAMPO_DETALLE_DEL_REGISTRO])) "" else r.campos.getValue(CAMPO_DETALLE_DEL_REGISTRO))
}

// ---------- Motivos y pendientes ----------

/** El motivo de cada consulta del paciente (si hay más de uno por consulta, el de mayor id), por id de consulta de la web. */
private fun motivosWeb(h: RespuestaHistoriaDto): Map<Long, String> = h.motivos
    .filter { it.id.valido() && it.texto.isNotBlank() }
    .mapNotNull { m -> m.consultaId?.takeIf { it.valido() }?.let { it to m } }
    .groupBy({ it.first }, { it.second })
    .mapValues { (_, motivos) -> motivos.maxBy { it.id }.texto }

/**
 * El motivo de cada consulta como el texto `motivo_consulta` de esa consulta, para las «consultas previas» (RF-41), solo
 * para las consultas que están en [consultasLocales]: sin la consulta no hay dónde mostrarlo y no se crea una.
 */
fun motivosDeLaWeb(h: RespuestaHistoriaDto, consultasLocales: Set<Id>): List<SeccionValor> =
    motivosWeb(h).mapNotNull { (consultaWebId, texto) ->
        consultaLocalDe(consultaWebId, consultasLocales)?.let { SeccionValor(it, SeccionesComunes.MOTIVO_CONSULTA, CAMPO_TEXTO, texto) }
    }

private fun pendienteWeb(h: RespuestaHistoriaDto) = h.pendientes?.takeIf { it.id.valido() && it.texto.isNotBlank() }

/** El pendiente del paciente (la web los guarda por paciente): del médico de la sesión, sin consulta y sin resolver. */
fun pendienteDeLaWeb(pacienteLocalId: Id, medicoSesionId: Id, h: RespuestaHistoriaDto): Pendiente? =
    pendienteWeb(h)?.let { Pendiente(idPendienteWeb(it.id), pacienteLocalId, medicoSesionId, it.texto, null, false) }

// ---------- Filas para guardar (siempre sin pendiente de envío, RF-53) ----------
// `toRow` tiene `dirty = true` por defecto; lo traído de la web nunca debe quedar sucio (el motor de envío lo empujaría).
// `ahora` solo se pasa para lo que de verdad se escribe.

fun Consulta.filaDeLaWeb(ahora: Long) = toRow(ahora, dirty = false)

fun SeccionValor.filaDeLaWeb(ahora: Long) = toRow(ahora, dirty = false)

fun ExamenFisico.filaDeLaWeb(ahora: Long) = toRow(ahora, dirty = false)

fun Antecedente.filaDeLaWeb(ahora: Long) = toRow(ahora, dirty = false)

fun Laboratorio.filaDeLaWeb(ahora: Long) = toRow(ahora, dirty = false)

fun RegistroClinico.filaDeLaWeb(ahora: Long) = toRow(ahora, dirty = false)

fun Pendiente.filaDeLaWeb(ahora: Long) = toRow(ahora, dirty = false)

fun MedicoPreferencia.filaDeLaWeb(ahora: Long) = toRow(ahora, dirty = false)

fun PacienteExtra.filaDeLaWeb(ahora: Long) = toRow(ahora, dirty = false)

// ---------- Lo que el catálogo no conoce (RF-42) ----------

/** Algo que la web mandó y el catálogo de la app no conoce: se guarda igual y se informa; [tipo] dice qué es. */
data class Desconocido(val tipo: String, val clave: String)

/**
 * Lo ya convertido que [catalogo] no conoce (secciones, campos, analitos, tipos de registro, campos de registro y
 * antecedentes), sin repetidos. No quita nada: es para el registro (log) y los avisos. [camposCustom] son los campos de las
 * secciones propias del módulo de la especialidad (`child_pugh`), que el catálogo no declara; una sección propia que no
 * figura ahí acepta cualquier campo.
 */
fun desconocidos(
    catalogo: CatalogoHc,
    valores: List<SeccionValor> = emptyList(),
    laboratorios: List<Laboratorio> = emptyList(),
    registros: List<RegistroClinico> = emptyList(),
    antecedentes: List<Antecedente> = emptyList(),
    camposCustom: Map<String, Set<String>> = emptyMap(),
): List<Desconocido> {
    val todas = catalogo.secciones + catalogo.legadas
    val secciones = todas.associateBy { it.id }
    val laboratoriosDelCatalogo = todas.flatMap { it.laboratorios }.associateBy { it.tipo }
    val registrosDelCatalogo = todas.flatMap { it.registros }.associateBy { it.tipo }
    val familiares = todas.flatMap { it.antecedentes }.filter { it.categoria == CATEGORIA_FAMILIARES }
        .flatMap { c -> c.items.map { it.clave } }.toSet() + CLAVE_NOTA + CLAVE_NOTA_PERFIL4
    return buildList {
        for (v in valores) {
            val seccion = secciones[v.seccion]
            when {
                seccion == null -> add(Desconocido("seccion", v.seccion))
                !campoConocido(seccion.def, v.campo, camposCustom) -> add(Desconocido("campo", "${v.seccion}/${v.campo}"))
            }
        }
        for (l in laboratorios) {
            val def = laboratoriosDelCatalogo[l.tipo]
            if (def == null) {
                add(Desconocido("laboratorio", l.tipo))
                continue
            }
            val analitos = def.analitos.map { it.clave }.toSet()
            l.valores.keys.filter { it !in analitos }.forEach { add(Desconocido("analito", "${l.tipo}/$it")) }
        }
        for (r in registros) {
            val def = registrosDelCatalogo[r.tipo]
            if (def == null) {
                add(Desconocido("registro", r.tipo))
                continue
            }
            val campos = def.campos.map { it.clave }.toSet() + CAMPO_NUMERO
            r.campos.keys.filter { it !in campos }.forEach { add(Desconocido("campo_de_registro", "${r.tipo}/$it")) }
        }
        for (a in antecedentes) {
            if (a.categoria != CATEGORIA_FAMILIARES || a.clave !in familiares) add(Desconocido("antecedente", a.clave))
        }
    }.distinct()
}

private fun campoConocido(def: SeccionDef, campo: String, camposCustom: Map<String, Set<String>>): Boolean = when (def.tipo) {
    TipoSeccion.TEXTO -> campo == CAMPO_TEXTO
    TipoSeccion.FORM -> def.campos.any { it.clave == campo }
    TipoSeccion.CUSTOM -> camposCustom[def.id]?.let { campo in it } ?: true
    else -> false
}

// ---------- Lo que ya no figura en la web (Q15 = nunca borrar, RF-63) ----------
// Una cosa traída se identifica con una «clave de ausencia»: su id local (`hcc-c7003`, `hcc-llaboratorio_general-703`,
// `hcc-rveda-1797`, `hcc-p1744`, `hcc-a5001-hta`) o, para el contenido de una consulta (una sección, un campo, un campo del
// examen físico), `<id de la consulta>:<sección>:<campo>`. La marca se guarda en `paciente_extra` del paciente, con la
// clave `hcc_ausente_<clave de ausencia>` y la fecha en que se notó como valor. El antecedente se identifica por su
// clave aunque la fila local tenga otro id.

/** Prefijo de la clave de `paciente_extra` de una marca «Ya no figura en la web». */
const val PREFIJO_MARCA_AUSENTE = "hcc_ausente_"

/** Contenido de una consulta: `hcc-c7003:motivo_consulta:texto`, `hcc-c7003:examen_fisico:peso`. */
fun claveDeCampo(consultaLocalId: Id, seccion: String, campo: String): String = "$consultaLocalId:$seccion:$campo"

/** La fila de `paciente_extra` que marca [clave] (de ausencia) como «Ya no figura en la web» desde [hoy]. */
fun marcaAusente(pacienteLocalId: Id, clave: String, hoy: LocalDate) =
    PacienteExtra(pacienteLocalId, CodigosHc.GENERAL, PREFIJO_MARCA_AUSENTE + clave, hoy.toString())

/** La clave de ausencia de una clave de `paciente_extra`, o `null` si esa clave no es una marca. */
fun claveDeLaMarca(clavePacienteExtra: String): String? =
    clavePacienteExtra.takeIf { it.startsWith(PREFIJO_MARCA_AUSENTE) && it.length > PREFIJO_MARCA_AUSENTE.length }
        ?.removePrefix(PREFIJO_MARCA_AUSENTE)

/** Las claves de ausencia del contenido que trae una consulta: lo que tiene dato en sus secciones y en su examen físico. */
fun clavesDeLaConsulta(r: RespuestaConsultaDto): Set<String> {
    val id = idConsultaWeb(r.consulta.id)
    return buildSet {
        valoresDeLaWeb(id, r.secciones).forEach { add(claveDeCampo(it.consultaId, it.seccion, it.campo)) }
        camposDelExamen(r.examenFisico).forEach { add(claveDeCampo(id, SeccionesComunes.EXAMEN_FISICO, it)) }
    }
}

/** Las claves de ausencia de todo lo que trae la historia del paciente (lo que [marcarAusentes] cubre con ella). */
fun clavesDeLaHistoria(h: RespuestaHistoriaDto): Set<String> = buildSet {
    antecedentesWeb(h).forEach { add(idAntecedenteWeb(h.pacienteId, it.clave)) }
    cargasPorTipo(h.laboratorios).forEach { (tipo, cargas) -> cargas.filter(::cargaValida).forEach { add(idCargaWeb(tipo, it.id)) } }
    registrosWeb(h).forEach { add(idFilaWeb(it.tipo, it.id)) }
    pendienteWeb(h)?.let { add(idPendienteWeb(it.id)) }
    motivosWeb(h).keys.forEach { add(claveDeCampo(idConsultaWeb(it), SeccionesComunes.MOTIVO_CONSULTA, CAMPO_TEXTO)) }
}

/** Qué respuesta de la web se está mirando para decidir qué marcar. */
sealed interface LecturaWeb {
    /** Todas las páginas de `GET consultas` que se recorrieron en un mismo «Actualizar», en orden. */
    data class Lista(val pacienteWebId: Long, val paginas: List<RespuestaListaConsultasDto>) : LecturaWeb

    /** `GET consultas/{id}` respondió bien. */
    data class UnaConsulta(val respuesta: RespuestaConsultaDto) : LecturaWeb

    /** `GET consultas/{id}` respondió `404 consulta_no_encontrada` para una consulta que se había traído. */
    data class ConsultaNoEncontrada(val consultaWebId: Long) : LecturaWeb

    /** `GET pacientes/{id}/historia` respondió bien. */
    data class Historia(val pacienteWebId: Long, val respuesta: RespuestaHistoriaDto) : LecturaWeb

    /** Cualquier otra cosa: error, `429`, sin conexión, JSON cortado o inválido, `403`. No marca ni desmarca nada. */
    data class ConError(val motivo: String) : LecturaWeb
}

/**
 * Qué cambia en las marcas «Ya no figura en la web»: [marcar] y [desmarcar] son claves de ausencia. No hay forma de
 * expresar un borrado ni un vaciado: lo único que la lectura puede hacer con algo ya traído es marcarlo o desmarcarlo.
 */
data class MarcasAusentes(val marcar: Set<String>, val desmarcar: Set<String>)

private val CLAVE_DE_CONSULTA = Regex("${PREFIJO_ID_WEB}c\\d+")
private val CLAVE_DE_PENDIENTE = Regex("${PREFIJO_ID_WEB}p\\d+")

/**
 * Las marcas a poner y a quitar tras una [lectura] de la web. [traido] son las claves de ausencia de lo que el
 * dispositivo ya trajo **de ese paciente** y [marcadas] las que hoy llevan la marca.
 *
 * - Solo se marca lo que la respuesta cubre y solo si es completa y correcta: una consulta de una página no pedida no se
 *   marca; la lista solo cubre si se recorrieron todas las páginas en un mismo «Actualizar» (empiezan en 0, encadenan,
 *   `total` constante, la suma da el total y solo la última dice que no hay más); la historia, si es la del paciente.
 *   Una respuesta con error o dudosa no marca nada.
 * - Lo que sigue viniendo, o vuelve a venir, no se marca y pierde la marca que tuviera; una marca ya puesta no se repite.
 * - Con `404 consulta_no_encontrada` se marca la consulta (su contenido queda como estaba).
 * - La nota del perfil 4 nunca se marca: su ausencia no se distingue de un servidor sin esa tabla.
 */
fun marcarAusentes(traido: Set<String>, marcadas: Set<String>, lectura: LecturaWeb): MarcasAusentes {
    val c = cobertura(lectura) ?: return MarcasAusentes(emptySet(), emptySet())
    return MarcasAusentes(
        marcar = traido.filterTo(linkedSetOf<String>()) { c.cubre(it) && it !in c.presentes && it !in marcadas },
        desmarcar = marcadas.filterTo(linkedSetOf<String>()) { it in c.presentes },
    )
}

/** Qué claves cubre una respuesta ([cubre]) y cuáles trajo ([presentes]). */
private class Cobertura(val cubre: (String) -> Boolean, val presentes: Set<String>)

private fun cobertura(lectura: LecturaWeb): Cobertura? = when (lectura) {
    is LecturaWeb.ConError -> null
    is LecturaWeb.Lista -> {
        val todas = lectura.paginas.flatMap { it.consultas }
        if (todas.any { it.pacienteId != lectura.pacienteWebId }) {
            null
        } else {
            val completa = listaCompleta(lectura.paginas)
            Cobertura(
                cubre = { completa && CLAVE_DE_CONSULTA.matches(it) },
                presentes = todas.filter { it.id.valido() }.map { idConsultaWeb(it.id) }.toSet(),
            )
        }
    }
    is LecturaWeb.UnaConsulta -> {
        val id = lectura.respuesta.consulta.id
        if (!id.valido()) {
            null
        } else {
            val prefijo = "${idConsultaWeb(id)}:"
            Cobertura(cubre = { it.startsWith(prefijo) }, presentes = clavesDeLaConsulta(lectura.respuesta) + idConsultaWeb(id))
        }
    }
    is LecturaWeb.ConsultaNoEncontrada -> {
        if (!lectura.consultaWebId.valido()) {
            null
        } else {
            val id = idConsultaWeb(lectura.consultaWebId)
            Cobertura(cubre = { it == id }, presentes = emptySet())
        }
    }
    is LecturaWeb.Historia ->
        if (lectura.respuesta.pacienteId != lectura.pacienteWebId) {
            null
        } else {
            Cobertura(cubre = { enAlcanceDeLaHistoria(it, lectura.pacienteWebId) }, presentes = clavesDeLaHistoria(lectura.respuesta))
        }
}

/**
 * Las páginas de la lista cubren toda la lista: desde 0, cada una empieza donde terminó la anterior, el `total` no
 * cambia, todas menos la última dicen que hay más y la última que no, no hay consultas repetidas y la suma da el total.
 */
private fun listaCompleta(paginas: List<RespuestaListaConsultasDto>): Boolean {
    if (paginas.isEmpty()) return false
    val total = paginas.first().total
    var esperado = 0
    paginas.forEachIndexed { i, p ->
        if (p.total != total || p.desde != esperado || p.quedanMas != (i < paginas.lastIndex)) return false
        esperado += p.consultas.size
    }
    val ids = paginas.flatMap { p -> p.consultas.map { it.id } }
    return ids.size == total && ids.toSet().size == ids.size
}

private fun enAlcanceDeLaHistoria(clave: String, pacienteWebId: Long): Boolean =
    (clave.startsWith("${PREFIJO_ID_WEB}a$pacienteWebId-") && !clave.endsWith("-$CLAVE_NOTA_PERFIL4")) ||
        clave.startsWith("${PREFIJO_ID_WEB}l") ||
        clave.startsWith("${PREFIJO_ID_WEB}r") ||
        CLAVE_DE_PENDIENTE.matches(clave) ||
        (clave.startsWith("${PREFIJO_ID_WEB}c") && clave.endsWith(":${SeccionesComunes.MOTIVO_CONSULTA}:$CAMPO_TEXTO"))
