package com.salud360.core.data.hcgeneral

import com.salud360.core.data.network.hc.PayloadsClinica
import com.salud360.core.model.Id
import com.salud360.core.model.especialidad.SeccionesComunes
import com.salud360.core.model.hc.Antecedente
import com.salud360.core.model.hc.Archivo
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.hc.EstadoEnvio
import com.salud360.core.model.hc.ExamenFisico
import com.salud360.core.model.hc.Laboratorio
import com.salud360.core.model.hc.RegistroClinico
import com.salud360.core.model.hc.SeccionValor
import kotlinx.datetime.LocalDate

// El plan de envío de una consulta propia de la HC general (spec 005, RF-9, RF-34, RF-41, RF-42, RF-44, RF-46, RF-55, RF-56, RF-60,
// RF-68, RF-69, RF-70). **Puro**: las filas, lo último que la web confirmó, los estados y el momento del armado entran como parámetros;
// no hay red, base ni reloj, y el mismo ingreso da siempre el mismo plan. **Solo ordena**: no ejecuta ningún pedido ni escribe nada (un
// envío por consulta a la vez, RF-54, es de quien envía, `HcClinicaEnvio`). Responde tres preguntas:
// - **Qué viaja** ([PlanDeEnvio.pasos]), en este orden: creación → contenido (textos, examen, formularios y antecedentes de T62-a, los cinco
//   estudios de T62-b, las cargas de laboratorio general y peso de T84-a y los documentos de T104) → cierre al final (RF-56). Solo lo tocado
//   (`dirty`) y solo si es un cambio real (T18): un vacío que el médico no tocó no se envía nunca; vaciar algo que la web tiene sí (RF-69).
// - **Qué no viaja y se limpia** ([PlanDeEnvio.sinViaje]): lo que nunca va a viajar (lo que la web no guarda, antecedentes desactivados, lo
//   que ya está igual en la web). Quien envía lo deja con `dirty = 0` **sin** figurar como enviado ni como pendiente (RF-60).
// - **Qué no viaja y se queda** ([PlanDeEnvio.enReserva]): lo que la web guarda pero esta versión todavía no envía (RF-4): sigue sucio para
//   cuando se habilite, no cuenta como pendiente y no frena el cierre.
// Del histórico del dispositivo y de la web no sale nada (RF-7, RF-1): el plan es vacío y no toca nada de ellas.

/** Las partes de una consulta que se envían por separado, en el orden en que se envían (y las claves de [EstadoEnvio] persistido). */
const val PARTE_CREAR = "crear"
const val PARTE_SECCIONES = "secciones"
const val PARTE_EXAMEN = "examen"
const val PARTE_FORMULARIOS = "formularios"
const val PARTE_ANTECEDENTES = "antecedentes"
const val PARTE_REGISTROS = "registros"
const val PARTE_CARGAS = "cargas"
const val PARTE_ADJUNTOS = "adjuntos"
const val PARTE_CIERRE = "cierre"
const val PARTE_BAJA = "baja"

private val PARTES_DE_CONTENIDO = listOf(
    PARTE_SECCIONES, PARTE_EXAMEN, PARTE_FORMULARIOS, PARTE_ANTECEDENTES, PARTE_REGISTROS, PARTE_CARGAS, PARTE_ADJUNTOS,
)
private val ORDEN_DE_PARTES = listOf(PARTE_CREAR) + PARTES_DE_CONTENIDO + listOf(PARTE_CIERRE, PARTE_BAJA)

/** Una fila con su marca de «tocado» y el momento (`updated_at`) en que se modificó por última vez. */
data class FilaSucia<T>(val fila: T, val dirty: Boolean, val actualizadaEn: Long)

/**
 * Lo último que la web confirmó de esta consulta, que es lo que decide si algo es un cambio real o un vacío que el médico no tocó
 * (RF-34, RF-69): el examen se guarda por fila y no sabe qué campo se tocó. Lo que no figura es lo que la web no tiene (la consulta
 * nace vacía y solo la app la escribe). **Quien envía tiene que registrarlo** al confirmar cada paso ([conLoConfirmado]); si no, un
 * texto vaciado después de enviarse no se notaría.
 */
data class LoEnviado(
    /** `<sección>/<campo>` → texto (las secciones de texto: `nota/texto`). */
    val valores: Map<String, String> = emptyMap(),
    /** Nombre del contrato (`peso`, `talla`, `tension_arterial`, `imc`, `nota`) → valor. */
    val examen: Map<String, String> = emptyMap(),
    /** `<sección>/<campo>` -> el valor del formulario tal como se mandó (`""` = vaciado). */
    val formularios: Map<String, String> = emptyMap(),
    /** Clave de la casilla (o `nota`) -> `<0|1>|<detalle>`. */
    val antecedentes: Map<String, String> = emptyMap(),
    /** Id de la fila en la web -> campo confirmado (`fecha`, `detalle`) de cada estudio (T62-b). */
    val registros: Map<String, Map<String, String>> = emptyMap(),
    /** Id de la carga en la web -> valores que la web tiene de esa carga (T84-a). */
    val cargas: Map<String, Map<String, String>> = emptyMap(),
) {
    /** Lo que queda confirmado después de que la web aceptó [paso]. Un valor en blanco queda como `""` (vaciado). */
    fun conLoConfirmado(paso: PasoDeEnvio, remotos: Map<Id, String> = emptyMap()): LoEnviado = when (paso) {
        is PasoDeEnvio.Secciones -> copy(valores = valores + paso.textos.mapKeys { "${it.key}/$CAMPO_TEXTO" }.mapValues { normalizado(it.value) })
        is PasoDeEnvio.Examen -> copy(
            examen = examen + paso.campos.mapValues { (campo, valor) -> if (campo in MEDIDAS) medidaNormalizada(valor) else normalizado(valor) },
        )
        is PasoDeEnvio.Formularios -> copy(
            formularios = formularios + paso.valores.associate { "${it.seccion}/${it.campo}" to formularioConfirmado(it) },
        )
        is PasoDeEnvio.Antecedentes -> copy(antecedentes = antecedentes + paso.items.associate { it.clave to casillaConfirmada(it) })
        is PasoDeEnvio.Registros -> copy(registros = registros + registrosConfirmados(paso.items, remotos))
        is PasoDeEnvio.Cargas -> copy(cargas = cargas + cargasConfirmadas(paso.cargas, remotos, cargas))
        else -> this
    }

    private fun normalizado(valor: String) = if (esVacio(valor)) "" else valor
    private fun medidaNormalizada(valor: String) = if (esVacioDeMedida(valor)) "" else valor
}

private val MEDIDAS = setOf("peso", "talla", "tension_arterial", "imc")

/** Lo que queda anotado de un formulario que la web aceptó: el valor que se mandó, y `""` si quedó vacío (un vaciado confirmado es un dato). */
private fun formularioConfirmado(v: SeccionValor): String {
    val canonico = PayloadsClinica.valorDeFormulario(v.seccion, v.campo, v.valor) ?: v.valor
    return if (PayloadsClinica.esFormularioVacio(v.seccion, canonico)) "" else canonico
}

/**
 * Lo que queda anotado de una casilla de antecedentes que la web aceptó: `<0|1>|<detalle>`; una casilla sin marcar y sin detalle, o una nota en
 * blanco, queda `0|` (vaciada). La `nota` se anota con el flag en 1 si tiene texto: lo que decide es el texto.
 */
private fun casillaConfirmada(a: Antecedente): String = when {
    a.clave == CLAVE_NOTA -> if (esVacio(a.detalle)) "0|" else "1|${a.detalle}"
    !a.flag && esVacio(a.detalle) -> "0|"
    else -> "${if (a.flag) 1 else 0}|${a.detalle}"
}

/**
 * La casilla [a] como la tiene la web según lo último confirmado (el mismo flag y detalle de [LoEnviado.antecedentes] sobre la fila [a]), o `null` si todavía
 * no se confirmó nada de esa clave. Es lo que el cuerpo de la API (`PayloadsClinica.antecedentes`) necesita como «lo ya enviado»: el plan y el cuerpo deciden
 * el cambio real con la misma información.
 */
fun LoEnviado.antecedenteEnviado(a: Antecedente): Antecedente? =
    antecedentes[a.clave]?.let { a.copy(flag = flagDeLaCasilla(it), detalle = detalleDeLaCasilla(it)) }

/**
 * La fila de la web que corresponde a [r] según lo último confirmado (la fecha y el detalle de [LoEnviado.registros] sobre la fila), o `null` si la web
 * todavía no tiene nada de ese estudio. Es lo que el cuerpo de la API (`PayloadsClinica.registros`) necesita como «lo ya enviado», para que el plan y el
 * cuerpo decidan el cambio real con la misma información (como [antecedenteEnviado]).
 */
fun LoEnviado.registroEnviado(r: RegistroClinico): RegistroClinico? {
    val confirmado = registros[r.remotoId].takeIf { r.remotoId.isNotBlank() } ?: return null
    val fecha = confirmado[CAMPO_FECHA_DEL_REGISTRO]?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: r.fecha
    return r.copy(fecha = fecha, campos = r.campos + (CAMPO_DETALLE_DEL_REGISTRO to confirmado[CAMPO_DETALLE_DEL_REGISTRO].orEmpty()))
}

/**
 * Lo que la web tiene de la carga [c] según lo último confirmado ([LoEnviado.cargas]), o `null` si esa carga nunca se confirmó (un alta). Es lo que
 * el cuerpo de la API (`PayloadsClinica.carga`) necesita como «lo ya enviado», para que el plan y el cuerpo decidan el cambio real con la misma
 * información (como [antecedenteEnviado] y [registroEnviado]).
 */
fun LoEnviado.cargaEnviada(c: Laboratorio): Laboratorio? =
    cargas[c.remotoId].takeIf { c.remotoId.isNotBlank() }?.let { c.copy(valores = it) }

/**
 * Lo que queda anotado de cada estudio que la web aceptó: el id de la fila en la web (el de la respuesta, [remotos], o el que ya tenía la fila) y los campos
 * que se mandaron (`fecha`, `detalle`; `""` es un detalle vaciado). Un estudio que todavía no tiene id no se puede anotar: se anota en el envío siguiente,
 * cuando la web lo devuelve (T63-b).
 */
private fun registrosConfirmados(items: List<RegistroClinico>, remotos: Map<Id, String>): Map<String, Map<String, String>> = items.mapNotNull { r ->
    val remoto = remotos[r.id] ?: r.remotoId.takeIf { it.isNotBlank() } ?: return@mapNotNull null
    remoto to camposConfirmadosDelEstudio(r)
}.toMap()

internal const val CAMPO_FECHA_DEL_REGISTRO = "fecha"
internal const val CAMPO_DETALLE_DEL_REGISTRO = "detalle"

/**
 * Lo que queda anotado de cada carga que la web aceptó: el id de la fila de la web (el de la respuesta, [remotos], o el que ya tenía la carga) y los
 * valores que la web tiene de ella. No son solo los que viajaron en este pedido: se suman a los que ya estaban confirmados, porque la actualización
 * manda lo tocado y la web conserva el resto. Una baja no anota nada: la web deja la fila con sus valores (su cierre la reactiva). Una carga que todavía
 * no tiene id no se puede anotar: se anota en el envío siguiente, cuando la web lo devuelve (T85).
 */
private fun cargasConfirmadas(
    items: List<CargaQueViaja>,
    remotos: Map<Id, String>,
    confirmadas: Map<String, Map<String, String>>,
): Map<String, Map<String, String>> = items.filterIsInstance<CargaQueViaja.Guardar>().mapNotNull { viaja ->
    val carga = viaja.carga
    val remoto = remotos[carga.id] ?: carga.remotoId.takeIf { it.isNotBlank() } ?: return@mapNotNull null
    val anterior = confirmadas[remoto].orEmpty()
    remoto to (anterior + valoresAEnviar(carga.valores, anterior))
}.toMap()

private fun flagDeLaCasilla(confirmada: String): Boolean = confirmada.substringBefore("|") == "1"

private fun detalleDeLaCasilla(confirmada: String): String = confirmada.substringAfter("|", "")

/** Todo lo que el plan necesita saber de una consulta. */
data class EntradaDelPlan(
    val consulta: Consulta,
    /** La fila de la consulta sigue sucia: falta crearla, cerrarla o darla de baja (se limpia cuando se confirma lo último). */
    val consultaSucia: Boolean,
    val valores: List<FilaSucia<SeccionValor>> = emptyList(),
    val examen: FilaSucia<ExamenFisico>? = null,
    /** Los antecedentes del paciente; solo cuentan los asociados a esta consulta (RF-68). */
    val antecedentes: List<FilaSucia<Antecedente>> = emptyList(),
    /** Las filas de registro del paciente; solo cuentan las asociadas a esta consulta (RF-68). */
    val registros: List<FilaSucia<RegistroClinico>> = emptyList(),
    /** Las cargas de laboratorio y de peso del paciente; solo cuentan las asociadas a esta consulta (RF-68). */
    val cargas: List<FilaSucia<Laboratorio>> = emptyList(),
    /** Los adjuntos del paciente; solo cuentan los asociados a esta consulta (RF-68). */
    val archivos: List<FilaSucia<Archivo>> = emptyList(),
    val loEnviado: LoEnviado = LoEnviado(),
    /** El estado guardado de cada parte (T20); lo que no figura es «Falta enviar». */
    val estados: Map<String, EstadoEnvio> = emptyMap(),
    /** Las secciones que envía esta versión ([SECCIONES_HABILITADAS], el candado por sección: RF-4). */
    val habilitadas: Set<String> = SECCIONES_HABILITADAS,
    /** El médico tocó «Reintentar»: las partes rechazadas vuelven a incluirse (RF-30). */
    val reintentoManual: Boolean = false,
    /** El momento en que se arma el envío; el plan solo lo repite (RF-55). */
    val armadoEn: Long,
)

enum class TablaDeLaFila { CONSULTA, SECCION_VALOR, EXAMEN_FISICO, ANTECEDENTE, REGISTRO, LABORATORIO, ARCHIVO }

/**
 * Una fila que viaja en un paso, con su `updated_at` al armar el plan: al confirmarse el paso, solo se limpia la que sigue igual (RF-55).
 * [clave] es `<sección>/<campo>` en los valores, el id de la consulta en el examen y la consulta, y el id de la fila en lo demás.
 */
data class FilaCubierta(val tabla: TablaDeLaFila, val clave: String, val actualizadaEn: Long)

/**
 * Una carga de laboratorio general o de peso que viaja en [PasoDeEnvio.Cargas] (E3, T84-a). Cada una es un pedido propio (P23): un alta o la
 * actualización de una carga de la web, con la fecha que resolvió el plan ([fechaAEnviar]), o la baja de la carga que la web ya tiene.
 */
sealed interface CargaQueViaja {
    val carga: Laboratorio

    /** Alta (sin `remotoId`) o actualización de esa carga de la web ([fecha] ya resuelta: nunca un marcador de «sin fecha»). */
    data class Guardar(override val carga: Laboratorio, val fecha: LocalDate) : CargaQueViaja

    /** La baja de una carga que la web tiene (`DELETE`): sin fecha ni valores. */
    data class Baja(override val carga: Laboratorio) : CargaQueViaja
}

sealed interface PasoDeEnvio {
    val parte: String
    val cubre: List<FilaCubierta>

    /** `POST consultas`: la fecha y la edad son las que cargó el médico. */
    data class Crear(val clave: String, val tipo: String, val fecha: LocalDate, val edadMostrar: String, override val cubre: List<FilaCubierta>) : PasoDeEnvio {
        override val parte get() = PARTE_CREAR
    }

    /** `PUT consultas/{id}/secciones`: sección → texto, solo lo cambiado (`""` vacía). */
    data class Secciones(val textos: Map<String, String>, override val cubre: List<FilaCubierta>) : PasoDeEnvio {
        override val parte get() = PARTE_SECCIONES
    }

    /** `PUT consultas/{id}/examen`: nombre del contrato → valor, solo los campos cambiados. */
    data class Examen(val campos: Map<String, String>, override val cubre: List<FilaCubierta>) : PasoDeEnvio {
        override val parte get() = PARTE_EXAMEN
    }

    /** Los formularios (alimentación, serologías, Child-Pugh) que cambian de verdad: un valor por campo (canónico; `""` = vaciado a propósito) (E2, T62-a). */
    data class Formularios(val valores: List<SeccionValor>, override val cubre: List<FilaCubierta>) : PasoDeEnvio {
        override val parte get() = PARTE_FORMULARIOS
    }

    /** Las casillas de antecedentes familiares (y la nota) de esta consulta que cambian de verdad, activas y asociadas a ella (E2, T62-a, RF-9, RF-68). */
    data class Antecedentes(val items: List<Antecedente>, override val cubre: List<FilaCubierta>) : PasoDeEnvio {
        override val parte get() = PARTE_ANTECEDENTES
    }

    /**
     * Los cinco estudios de esta consulta que cambian de verdad (E2, T62-b, RF-39, RF-40): un estudio viaja con su fecha y su `campos.detalle` (el servidor
     * exige la fecha) y vaciarlo es `detalle ""`; no hay baja de estudio (Q-E2-2). Los exámenes complementarios y las interconsultas quedan fuera de alcance
     * (T52-b) y no se arman.
     */
    data class Registros(val items: List<RegistroClinico>, override val cubre: List<FilaCubierta>) : PasoDeEnvio {
        override val parte get() = PARTE_REGISTROS
    }

    /** Las cargas de laboratorio general y peso de esta consulta (E3, T84-a): cada una es un pedido, con su fecha o como baja de la que la web tiene. */
    data class Cargas(val cargas: List<CargaQueViaja>, override val cubre: List<FilaCubierta>) : PasoDeEnvio {
        override val parte get() = PARTE_CARGAS
    }

    /**
     * Los **documentos** de la sección `documentos` de esta consulta (E4, T104, RF-44, RF-46): cada uno es un pedido. Un adjunto **sin
     * `remotoId`** es un alta (`POST consultas/{id}/adjuntos`, un archivo por pedido) y uno **dado de baja con `remotoId`** es la baja de
     * la fila que la web ya tiene (`DELETE adjuntos/{id}`). No hay actualización: reemplazar un archivo es un alta nueva. Las fotos de
     * exámenes quedan fuera de alcance (T105) y no se arman.
     */
    data class Adjuntos(val adjuntos: List<Archivo>, override val cubre: List<FilaCubierta>) : PasoDeEnvio {
        override val parte get() = PARTE_ADJUNTOS
    }

    /** `POST consultas/{id}/cerrar`: con la clave de este cierre y la fecha y la edad de la consulta. Siempre el último. */
    data class Cerrar(val clave: String, val fecha: LocalDate, val edadMostrar: String, override val cubre: List<FilaCubierta>) : PasoDeEnvio {
        override val parte get() = PARTE_CIERRE
    }

    /** `DELETE consultas/{id}`: la baja de una consulta creada y anulada. Va sola. */
    data class Baja(val clave: String, override val cubre: List<FilaCubierta>) : PasoDeEnvio {
        override val parte get() = PARTE_BAJA
    }
}

/** Por qué una fila sucia no viaja y se limpia sin figurar como enviada (RF-60). */
enum class MotivoSinViaje {
    /** Es lo que la web ya tiene, o un vacío que el médico no tocó: no hay nada que enviar. */
    SIN_CAMBIO_REAL,

    /** Un antecedente desactivado (los que dejó la migración de hepatología, RF-9). */
    DESACTIVADO,

    /** Lo que la web no guarda (RF-3, D9). */
    LA_WEB_NO_LA_GUARDA,

    /** La consulta se anuló antes de crearse en la web: queda solo en el dispositivo (RF-26). */
    CONSULTA_ANULADA,

    /** La consulta ya creada se anuló: lo pendiente se descarta y solo sale la baja (RF-26). */
    DESCARTADO_POR_ANULAR,
}

data class FilaSinViaje(val tabla: TablaDeLaFila, val clave: String, val motivo: MotivoSinViaje, val actualizadaEn: Long)

/** Por qué una fila sucia espera sin viajar ni limpiarse. */
enum class MotivoEnReserva {
    /** La web la guarda y esta versión todavía no la envía (RF-4): sale cuando se habilite. */
    TODAVIA_NO_SE_ENVIA,

    /** La app no sabe enviar ese dato (campo desconocido de una sección). */
    NO_HAY_ENVIO_PARA_ESTE_DATO,

    /** Dato por paciente de una consulta anulada: no se envía ni se tira. */
    CONSULTA_ANULADA,
}

data class FilaEnReserva(val tabla: TablaDeLaFila, val clave: String, val motivo: MotivoEnReserva, val actualizadaEn: Long)

/** Por qué una parte no sale (o el cierre no sale) aunque haya algo para enviar. */
enum class MotivoRetencion {
    /** La parte tiene el estado «No se pudo enviar»: no se reintenta sola; el médico reintenta o descarta (RF-30). */
    RECHAZADA,

    /** El cierre espera a que el médico resuelva una parte rechazada (RF-56). */
    FRENADO_POR_UNA_PARTE_RECHAZADA,
}

data class Retencion(val parte: String, val motivo: MotivoRetencion)

/** Por qué el plan está vacío sin mirar nada de la consulta. */
enum class MotivoPlanVacio { DEL_HISTORICO, DE_LA_WEB }

/**
 * Lo que se decide de una consulta. [pasos] se ejecutan en orden y el envío se corta en el primero que falla ([siguiente]), así el
 * cierre solo sale cuando todo lo anterior llegó (RF-56).
 */
data class PlanDeEnvio(
    val consultaId: Id,
    val pasos: List<PasoDeEnvio>,
    val sinViaje: List<FilaSinViaje>,
    val enReserva: List<FilaEnReserva>,
    val retenciones: List<Retencion>,
    val motivoVacio: MotivoPlanVacio?,
    val armadoEn: Long,
) {
    /** Hay algo para enviar ahora: lo que espera ([enReserva]) o no viaja ([sinViaje]) no cuenta. */
    val hayAlgoPorEnviar: Boolean get() = pasos.isNotEmpty()

    /**
     * El próximo paso: el primero que todavía no está en [confirmadas]. `null` si no queda ninguno o si ese está en [frenadas] (falló o
     * está en espera): no se salta a los que siguen, y en particular el cierre nunca sale con algo anterior sin confirmar.
     */
    fun siguiente(confirmadas: Set<String>, frenadas: Set<String> = emptySet()): PasoDeEnvio? {
        val proximo = pasos.firstOrNull { it.parte !in confirmadas } ?: return null
        return proximo.takeIf { it.parte !in frenadas }
    }
}

/** El plan de [entrada]. */
fun planDeEnvio(entrada: EntradaDelPlan): PlanDeEnvio {
    val c = entrada.consulta
    val vacio = when (c.origenDeConsulta()) {
        OrigenDeConsulta.HISTORICO -> MotivoPlanVacio.DEL_HISTORICO
        OrigenDeConsulta.WEB -> MotivoPlanVacio.DE_LA_WEB
        OrigenDeConsulta.PROPIA -> null
    }
    if (vacio != null) return PlanDeEnvio(c.id, emptyList(), emptyList(), emptyList(), emptyList(), vacio, entrada.armadoEn)
    return Armado(entrada).armar()
}

private class Armado(private val e: EntradaDelPlan) {
    private val c = e.consulta
    private val yaCreada = c.remotoId.isNotBlank()
    private val anulada = c.estado == EstadoConsulta.ANULADA
    private val consultaEn = c.updatedAt?.toEpochMilliseconds() ?: 0L

    private val sinViaje = mutableListOf<FilaSinViaje>()
    private val enReserva = mutableListOf<FilaEnReserva>()
    private val retenciones = mutableListOf<Retencion>()

    fun armar(): PlanDeEnvio {
        val pasos = if (anulada) pasosDeLaAnulada() else pasosNormales()
        return PlanDeEnvio(c.id, pasos, sinViaje.toList(), enReserva.toList(), retenciones.toList(), null, e.armadoEn)
    }

    // ---------- Una consulta anulada ----------

    private fun pasosDeLaAnulada(): List<PasoDeEnvio> {
        val motivo = if (yaCreada) MotivoSinViaje.DESCARTADO_POR_ANULAR else MotivoSinViaje.CONSULTA_ANULADA
        e.valores.filter { it.dirty }.sortedBy { it.fila.seccion + "/" + it.fila.campo }.forEach {
            sinViaje += FilaSinViaje(TablaDeLaFila.SECCION_VALOR, clave(it.fila), motivo, it.actualizadaEn)
        }
        e.examen?.takeIf { it.dirty }?.let { sinViaje += FilaSinViaje(TablaDeLaFila.EXAMEN_FISICO, c.id, motivo, it.actualizadaEn) }
        antecedentes(anulada = true)
        registros(anulada = true)
        cargas(anulada = true)
        adjuntos(anulada = true)
        if (!yaCreada || !e.consultaSucia) return emptyList()
        if (rechazada(PARTE_BAJA)) {
            retenciones += Retencion(PARTE_BAJA, MotivoRetencion.RECHAZADA)
            return emptyList()
        }
        return listOf(PasoDeEnvio.Baja(PayloadsClinica.claveDeBaja(c.id), listOf(FilaCubierta(TablaDeLaFila.CONSULTA, c.id, consultaEn))))
    }

    // ---------- Una consulta abierta o cerrada ----------

    private fun pasosNormales(): List<PasoDeEnvio> {
        val pasos = mutableListOf<PasoDeEnvio>()
        val (secciones, formularios) = valores()
        val examen = examen()
        val antecedentes = antecedentes(anulada = false)
        val registros = registros(anulada = false)
        val cargas = cargas(anulada = false)
        val adjuntos = adjuntos(anulada = false)
        val contenido = buildList {
            secciones?.let { add(it) }
            examen?.let { add(it) }
            formularios?.let { add(it) }
            antecedentes?.let { add(it) }
            registros?.let { add(it) }
            cargas?.let { add(it) }
            adjuntos?.let { add(it) }
        }.sortedBy { ORDEN_DE_PARTES.indexOf(it.parte) }

        val quiereCrear = !yaCreada
        val quiereCerrar = c.estado == EstadoConsulta.CERRADA && (e.consultaSucia || !yaCreada)

        val crearBloqueada = quiereCrear && rechazada(PARTE_CREAR)
        val partesRechazadas = PARTES_DE_CONTENIDO.filter { rechazada(it) }
        val cierreRechazado = rechazada(PARTE_CIERRE)

        if (crearBloqueada) retenciones += Retencion(PARTE_CREAR, MotivoRetencion.RECHAZADA)
        partesRechazadas.forEach { retenciones += Retencion(it, MotivoRetencion.RECHAZADA) }
        if (quiereCerrar) {
            when {
                cierreRechazado -> retenciones += Retencion(PARTE_CIERRE, MotivoRetencion.RECHAZADA)
                crearBloqueada || partesRechazadas.isNotEmpty() -> retenciones += Retencion(PARTE_CIERRE, MotivoRetencion.FRENADO_POR_UNA_PARTE_RECHAZADA)
            }
        }
        if (crearBloqueada) return emptyList()

        if (quiereCrear) {
            pasos += PasoDeEnvio.Crear(
                PayloadsClinica.claveDeCrear(c.id), c.tipo, c.fecha, c.edadMostrar, listOf(FilaCubierta(TablaDeLaFila.CONSULTA, c.id, consultaEn)),
            )
        }
        pasos += contenido.filter { it.parte !in partesRechazadas }
        if (quiereCerrar && !cierreRechazado && partesRechazadas.isEmpty()) {
            pasos += PasoDeEnvio.Cerrar(
                PayloadsClinica.claveDeCierre(c.id), c.fecha, c.edadMostrar, listOf(FilaCubierta(TablaDeLaFila.CONSULTA, c.id, consultaEn)),
            )
        }
        return pasos
    }

    /** La parte tiene «No se pudo enviar» y el médico no pidió reintentarla a mano. */
    private fun rechazada(parte: String): Boolean = !e.reintentoManual && e.estados[parte] is EstadoEnvio.NoSePudoEnviar

    private fun clave(v: SeccionValor) = "${v.seccion}/${v.campo}"

    // ---------- Valores de sección ----------

    private var seccionesCalculadas: Pair<PasoDeEnvio.Secciones?, PasoDeEnvio.Formularios?>? = null

    private fun valores(): Pair<PasoDeEnvio.Secciones?, PasoDeEnvio.Formularios?> {
        seccionesCalculadas?.let { return it }
        val textos = linkedMapOf<String, String>()
        val cubreTextos = mutableListOf<FilaCubierta>()
        val formularios = mutableListOf<FilaSucia<SeccionValor>>()
        for (f in e.valores.filter { it.dirty }.sortedBy { clave(it.fila) }) {
            val v = f.fila
            val ref = clave(v)
            val envio = envioDeSeccion(v.seccion, e.habilitadas)
            when {
                envio == EnvioDeSeccion.NUNCA -> sinViaje += FilaSinViaje(TablaDeLaFila.SECCION_VALOR, ref, MotivoSinViaje.LA_WEB_NO_LA_GUARDA, f.actualizadaEn)
                envio == EnvioDeSeccion.TODAVIA_NO ->
                    enReserva += FilaEnReserva(TablaDeLaFila.SECCION_VALOR, ref, MotivoEnReserva.TODAVIA_NO_SE_ENVIA, f.actualizadaEn)
                envio == EnvioDeSeccion.APARTE ->
                    enReserva += FilaEnReserva(TablaDeLaFila.SECCION_VALOR, ref, MotivoEnReserva.NO_HAY_ENVIO_PARA_ESTE_DATO, f.actualizadaEn)
                v.seccion in PayloadsClinica.SECCIONES_DE_TEXTO ->
                    if (v.campo != CAMPO_TEXTO) {
                        enReserva += FilaEnReserva(TablaDeLaFila.SECCION_VALOR, ref, MotivoEnReserva.NO_HAY_ENVIO_PARA_ESTE_DATO, f.actualizadaEn)
                    } else if (cambioReal(e.loEnviado.valores[ref], v.valor) == CambioReal.NINGUNO) {
                        sinViaje += FilaSinViaje(TablaDeLaFila.SECCION_VALOR, ref, MotivoSinViaje.SIN_CAMBIO_REAL, f.actualizadaEn)
                    } else {
                        textos[v.seccion] = v.valor
                        cubreTextos += FilaCubierta(TablaDeLaFila.SECCION_VALOR, ref, f.actualizadaEn)
                    }
                v.seccion in PayloadsClinica.SECCIONES_DE_FORMULARIOS -> formularios += f
                else -> enReserva += FilaEnReserva(TablaDeLaFila.SECCION_VALOR, ref, MotivoEnReserva.NO_HAY_ENVIO_PARA_ESTE_DATO, f.actualizadaEn)
            }
        }
        val resultado = Pair(
            if (textos.isEmpty()) null else PasoDeEnvio.Secciones(textos, cubreTextos),
            pasoDeFormularios(formularios),
        )
        seccionesCalculadas = resultado
        return resultado
    }

    /**
     * Los formularios que viajan: un valor por campo, solo si es un **cambio real** contra lo último que la web confirmó (RF-34, RF-69). Un valor que la
     * web no entiende no viaja ni se limpia (queda en reserva); un vacío sobre nada, o un valor igual al de la web, se limpia sin figurar enviado (RF-60).
     * El valor del paso es el canónico (el que arma `PayloadsClinica.formularios`) y `""` si se vació a propósito.
     */
    private fun pasoDeFormularios(filas: List<FilaSucia<SeccionValor>>): PasoDeEnvio.Formularios? {
        val viajan = mutableListOf<SeccionValor>()
        val cubre = mutableListOf<FilaCubierta>()
        for (f in filas) {
            val v = f.fila
            val ref = clave(v)
            val aMandar = PayloadsClinica.valorDeFormulario(v.seccion, v.campo, v.valor)
            if (aMandar == null) {
                enReserva += FilaEnReserva(TablaDeLaFila.SECCION_VALOR, ref, MotivoEnReserva.NO_HAY_ENVIO_PARA_ESTE_DATO, f.actualizadaEn)
                continue
            }
            when (cambioReal(e.loEnviado.formularios[ref], aMandar) { PayloadsClinica.esFormularioVacio(v.seccion, it) }) {
                CambioReal.NINGUNO -> sinViaje += FilaSinViaje(TablaDeLaFila.SECCION_VALOR, ref, MotivoSinViaje.SIN_CAMBIO_REAL, f.actualizadaEn)
                CambioReal.ESCRIBIR -> {
                    viajan += v.copy(valor = aMandar)
                    cubre += FilaCubierta(TablaDeLaFila.SECCION_VALOR, ref, f.actualizadaEn)
                }
                CambioReal.VACIAR -> {
                    viajan += v.copy(valor = "")
                    cubre += FilaCubierta(TablaDeLaFila.SECCION_VALOR, ref, f.actualizadaEn)
                }
            }
        }
        return if (viajan.isEmpty()) null else PasoDeEnvio.Formularios(viajan, cubre)
    }

    // ---------- Examen físico ----------

    private var examenCalculado = false
    private var examenPaso: PasoDeEnvio.Examen? = null

    private fun examen(): PasoDeEnvio.Examen? {
        if (examenCalculado) return examenPaso
        examenCalculado = true
        val f = e.examen?.takeIf { it.dirty } ?: return null
        if (!seccionSeOfrece(SeccionesComunes.EXAMEN_FISICO, e.habilitadas)) {
            enReserva += FilaEnReserva(TablaDeLaFila.EXAMEN_FISICO, c.id, MotivoEnReserva.TODAVIA_NO_SE_ENVIA, f.actualizadaEn)
            return null
        }
        val x = f.fila
        val actuales = linkedMapOf("imc" to x.imc, "nota" to x.nota, "peso" to x.peso, "talla" to x.talla, "tension_arterial" to x.tensionArterial)
        val campos = linkedMapOf<String, String>()
        for ((campo, valor) in actuales.toList().sortedBy { it.first }) {
            val previo = e.loEnviado.examen[campo]
            val cambio = if (campo in MEDIDAS) cambioRealDeMedida(previo, valor) else cambioReal(previo, valor)
            if (cambio != CambioReal.NINGUNO) campos[campo] = valor
        }
        if (campos.isEmpty()) {
            sinViaje += FilaSinViaje(TablaDeLaFila.EXAMEN_FISICO, c.id, MotivoSinViaje.SIN_CAMBIO_REAL, f.actualizadaEn)
            return null
        }
        examenPaso = PasoDeEnvio.Examen(campos, listOf(FilaCubierta(TablaDeLaFila.EXAMEN_FISICO, c.id, f.actualizadaEn)))
        return examenPaso
    }

    // ---------- Datos por paciente asociados a esta consulta (RF-68) ----------

    private fun antecedentes(anulada: Boolean): PasoDeEnvio.Antecedentes? {
        val viajan = mutableListOf<FilaSucia<Antecedente>>()
        for (f in e.antecedentes.filter { it.dirty && it.fila.consultaId == c.id }.sortedBy { it.fila.id }) {
            val a = f.fila
            when {
                !a.activo -> sinViaje += FilaSinViaje(TablaDeLaFila.ANTECEDENTE, a.id, MotivoSinViaje.DESACTIVADO, f.actualizadaEn)
                !casillaSeEnvia(a.categoria, a.clave) ->
                    sinViaje += FilaSinViaje(TablaDeLaFila.ANTECEDENTE, a.id, MotivoSinViaje.LA_WEB_NO_LA_GUARDA, f.actualizadaEn)
                anulada -> enReserva += FilaEnReserva(TablaDeLaFila.ANTECEDENTE, a.id, MotivoEnReserva.CONSULTA_ANULADA, f.actualizadaEn)
                !seccionSeOfrece(SeccionesComunes.ANTECEDENTES_FAMILIARES, e.habilitadas) ->
                    enReserva += FilaEnReserva(TablaDeLaFila.ANTECEDENTE, a.id, MotivoEnReserva.TODAVIA_NO_SE_ENVIA, f.actualizadaEn)
                !PayloadsClinica.esCasillaDeFamiliares(a) ->
                    enReserva += FilaEnReserva(TablaDeLaFila.ANTECEDENTE, a.id, MotivoEnReserva.NO_HAY_ENVIO_PARA_ESTE_DATO, f.actualizadaEn)
                cambioDeLaCasilla(a) == CambioReal.NINGUNO ->
                    sinViaje += FilaSinViaje(TablaDeLaFila.ANTECEDENTE, a.id, MotivoSinViaje.SIN_CAMBIO_REAL, f.actualizadaEn)
                else -> viajan += f
            }
        }
        if (viajan.isEmpty()) return null
        return PasoDeEnvio.Antecedentes(viajan.map { it.fila }, viajan.map { FilaCubierta(TablaDeLaFila.ANTECEDENTE, it.fila.id, it.actualizadaEn) })
    }

    /** Qué es guardar [a] donde la web tenía lo último confirmado (RF-34, RF-69): la nota se compara por su texto; las casillas por flag y detalle. */
    private fun cambioDeLaCasilla(a: Antecedente): CambioReal {
        val anterior = e.loEnviado.antecedenteEnviado(a)
        if (a.clave == CLAVE_NOTA) return cambioReal(anterior?.detalle, a.detalle)
        return cambioRealDeAntecedente(anterior, a.flag, a.detalle)
    }

    /**
     * Los **cinco estudios** que viajan (T62-b, RF-39, RF-40, RF-70): solo filas sucias de esta consulta y solo si son un **cambio real** contra lo que la
     * web confirmó (fecha o detalle; `LoEnviado.registros`). Un estudio nuevo viaja con su fecha y su detalle (alta); uno que la web ya tiene, solo si
     * cambió (edición). Un estudio en blanco que la web tenía viaja con `detalle ""` (Q-E2-2: vaciar **no** es dar de baja la fila). No viajan, y no se
     * limpian, los estudios sin fecha (el servidor la exige: quedan en reserva, sin perder el dato) ni un estudio desactivado (no hay baja de estudio:
     * fuera de alcance), y los exámenes complementarios y las interconsultas quedan fuera de alcance (T52-b) hasta que se implementen. Lo que es igual a lo
     * confirmado se limpia sin figurar enviado (RF-60).
     */
    private fun registros(anulada: Boolean): PasoDeEnvio.Registros? {
        val viajan = mutableListOf<FilaSucia<RegistroClinico>>()
        for (f in e.registros.filter { it.dirty && it.fila.consultaId == c.id }.sortedBy { it.fila.id }) {
            val r = f.fila
            val seccion = seccionDelRegistro(r.tipo)
            when {
                envioDeSeccion(seccion, e.habilitadas) == EnvioDeSeccion.NUNCA ->
                    sinViaje += FilaSinViaje(TablaDeLaFila.REGISTRO, r.id, MotivoSinViaje.LA_WEB_NO_LA_GUARDA, f.actualizadaEn)
                anulada -> enReserva += FilaEnReserva(TablaDeLaFila.REGISTRO, r.id, MotivoEnReserva.CONSULTA_ANULADA, f.actualizadaEn)
                !seccionSeOfrece(seccion, e.habilitadas) ->
                    enReserva += FilaEnReserva(TablaDeLaFila.REGISTRO, r.id, MotivoEnReserva.TODAVIA_NO_SE_ENVIA, f.actualizadaEn)
                r.tipo !in TIPOS_ESTUDIO_WEB ->
                    enReserva += FilaEnReserva(TablaDeLaFila.REGISTRO, r.id, MotivoEnReserva.NO_HAY_ENVIO_PARA_ESTE_DATO, f.actualizadaEn)
                !r.activo -> enReserva += FilaEnReserva(TablaDeLaFila.REGISTRO, r.id, MotivoEnReserva.NO_HAY_ENVIO_PARA_ESTE_DATO, f.actualizadaEn)
                r.fecha == null -> enReserva += FilaEnReserva(TablaDeLaFila.REGISTRO, r.id, MotivoEnReserva.NO_HAY_ENVIO_PARA_ESTE_DATO, f.actualizadaEn)
                !cambioDelEstudio(r) -> sinViaje += FilaSinViaje(TablaDeLaFila.REGISTRO, r.id, MotivoSinViaje.SIN_CAMBIO_REAL, f.actualizadaEn)
                else -> viajan += f
            }
        }
        if (viajan.isEmpty()) return null
        return PasoDeEnvio.Registros(viajan.map { it.fila }, viajan.map { FilaCubierta(TablaDeLaFila.REGISTRO, it.fila.id, it.actualizadaEn) })
    }

    /**
     * Qué es guardar el estudio [r] donde la web tenía lo confirmado: cambia si cambió la fecha o el detalle. Un estudio del que no se confirmó nada es un
     * alta, y como el servidor no crea un estudio sin detalle (lo mismo que decide `PayloadsClinica.registros`), un estudio vacío sobre nada no es un cambio.
     */
    private fun cambioDelEstudio(r: RegistroClinico): Boolean {
        val confirmado = e.loEnviado.registros[r.remotoId].takeIf { r.remotoId.isNotBlank() }
        val detalle = r.campos[CAMPO_DETALLE_DEL_REGISTRO].orEmpty()
        val texto = cambioReal(confirmado?.get(CAMPO_DETALLE_DEL_REGISTRO), detalle)
        val fecha = confirmado != null && confirmado[CAMPO_FECHA_DEL_REGISTRO] != r.fecha?.toString()
        return texto != CambioReal.NINGUNO || fecha
    }

    // ---------- Cargas de laboratorio general y de peso (E3, T84-a) ----------

    /**
     * Las **cargas de laboratorio general y de peso** que viajan (T84-a, RF-41, RF-42, RF-68, RF-69): solo filas sucias de esta consulta, solo los dos
     * tipos que tiene API (el reducido quedó fuera de alcance, T73, y espera en reserva) y solo si la sección está habilitada (el candado, RF-4). Una
     * carga nueva (sin `remotoId`) viaja con su fecha ([fechaAEnviar]) y todos los valores con contenido; una que la web ya tiene, con la fecha y **solo
     * lo tocado** contra `LoEnviado.cargas`. Una dada de baja viaja como baja si la web la tenía, y una que nunca se envió y se retiró del dispositivo no
     * viaja ni queda pendiente (el repositorio la deja limpia, T83-a). Sin fecha útil (ni la de la carga ni la de la consulta) queda en reserva, sin
     * perder el dato, y lo que es igual a lo confirmado se limpia sin figurar enviado (RF-60). La carga de la web es suya: la app no la manda.
     */
    private fun cargas(anulada: Boolean): PasoDeEnvio.Cargas? {
        val viajan = mutableListOf<CargaQueViaja>()
        val cubre = mutableListOf<FilaCubierta>()
        for (f in e.cargas.filter { it.dirty && it.fila.consultaId == c.id }.sortedBy { it.fila.id }) {
            val carga = f.fila
            val seccion = SECCION_DE_LA_CARGA[carga.tipo]
            when {
                carga.esDeLaWeb() ->
                    enReserva += FilaEnReserva(TablaDeLaFila.LABORATORIO, carga.id, MotivoEnReserva.NO_HAY_ENVIO_PARA_ESTE_DATO, f.actualizadaEn)
                anulada -> enReserva += FilaEnReserva(TablaDeLaFila.LABORATORIO, carga.id, MotivoEnReserva.CONSULTA_ANULADA, f.actualizadaEn)
                seccion == null || !seccionSeOfrece(seccion, e.habilitadas) -> enReserva += FilaEnReserva(
                    TablaDeLaFila.LABORATORIO, carga.id,
                    if (seccion == null) MotivoEnReserva.NO_HAY_ENVIO_PARA_ESTE_DATO else MotivoEnReserva.TODAVIA_NO_SE_ENVIA, f.actualizadaEn,
                )
                !carga.activo && carga.remotoId.isBlank() ->
                    sinViaje += FilaSinViaje(TablaDeLaFila.LABORATORIO, carga.id, MotivoSinViaje.SIN_CAMBIO_REAL, f.actualizadaEn)
                !carga.activo -> {
                    viajan += CargaQueViaja.Baja(carga)
                    cubre += FilaCubierta(TablaDeLaFila.LABORATORIO, carga.id, f.actualizadaEn)
                }
                else -> when (val fecha = fechaAEnviar(carga, c)) {
                    is FechaAEnviar.Valida ->
                        if (valoresAEnviar(carga.valores, e.loEnviado.cargas[carga.remotoId].takeIf { carga.remotoId.isNotBlank() }).isEmpty()) {
                            sinViaje += FilaSinViaje(TablaDeLaFila.LABORATORIO, carga.id, MotivoSinViaje.SIN_CAMBIO_REAL, f.actualizadaEn)
                        } else {
                            viajan += CargaQueViaja.Guardar(carga, fecha.fecha)
                            cubre += FilaCubierta(TablaDeLaFila.LABORATORIO, carga.id, f.actualizadaEn)
                        }
                    FechaAEnviar.SinFecha ->
                        enReserva += FilaEnReserva(TablaDeLaFila.LABORATORIO, carga.id, MotivoEnReserva.NO_HAY_ENVIO_PARA_ESTE_DATO, f.actualizadaEn)
                }
            }
        }
        if (viajan.isEmpty()) return null
        return PasoDeEnvio.Cargas(viajan, cubre)
    }

    // ---------- Documentos (E4, T104) ----------

    /**
     * Los **documentos** de la sección `documentos` que viajan (T104, RF-44, RF-46): solo filas sucias de esta consulta (RF-68) y solo si el candado
     * de la versión enciende la sección (RF-4). Un **alta** es un archivo activo sin `remotoId`; una **baja**, uno que la web ya tiene (`remotoId`) y
     * se dio de baja (`activo = false`, lo mismo que deja el repositorio en T103). Un documento que **nunca se subió y se borró** no viaja ni queda
     * pendiente (en la web no hay nada que dar de baja) y uno que la web ya tiene y sigue activo tampoco: no hay actualización de un archivo (reemplazarlo
     * es un alta nueva). Son las mismas reglas que [adjuntoViaja] (T103): **nunca** uno sin consulta, de otra consulta u otra HC, la **foto de un examen**
     * (T105, queda en reserva sin perder el dato) ni uno que no sea PDF, JPG, JPEG o PNG (la web lo rechazaría; se limpia sin figurar enviado, RF-60). El
     * tipo lo decide la extensión declarada ([tipoDeAdjunto]). Lo que es de una consulta anulada queda en reserva (Q-E3-2, como las cargas).
     */
    private fun adjuntos(anulada: Boolean): PasoDeEnvio.Adjuntos? {
        val viajan = mutableListOf<Archivo>()
        val cubre = mutableListOf<FilaCubierta>()
        for (f in e.archivos.filter { it.dirty && it.fila.consultaId == c.id }.sortedBy { it.fila.id }) {
            val a = f.fila
            when {
                // La foto de un examen complementario (T105): todavía no hay envío, y el dato no se pierde.
                a.registroId != null -> enReserva += FilaEnReserva(TablaDeLaFila.ARCHIVO, a.id, MotivoEnReserva.NO_HAY_ENVIO_PARA_ESTE_DATO, f.actualizadaEn)
                a.seccion != SECCION_DOCUMENTOS ->
                    sinViaje += FilaSinViaje(TablaDeLaFila.ARCHIVO, a.id, MotivoSinViaje.LA_WEB_NO_LA_GUARDA, f.actualizadaEn)
                tipoDeAdjunto(a.nombre, a.mime) == null ->
                    sinViaje += FilaSinViaje(TablaDeLaFila.ARCHIVO, a.id, MotivoSinViaje.LA_WEB_NO_LA_GUARDA, f.actualizadaEn)
                anulada -> enReserva += FilaEnReserva(TablaDeLaFila.ARCHIVO, a.id, MotivoEnReserva.CONSULTA_ANULADA, f.actualizadaEn)
                !seccionSeOfrece(SECCION_DOCUMENTOS, e.habilitadas) ->
                    enReserva += FilaEnReserva(TablaDeLaFila.ARCHIVO, a.id, MotivoEnReserva.TODAVIA_NO_SE_ENVIA, f.actualizadaEn)
                !a.activo && a.remotoId.isBlank() ->
                    sinViaje += FilaSinViaje(TablaDeLaFila.ARCHIVO, a.id, MotivoSinViaje.SIN_CAMBIO_REAL, f.actualizadaEn)
                a.activo && a.remotoId.isNotBlank() ->
                    sinViaje += FilaSinViaje(TablaDeLaFila.ARCHIVO, a.id, MotivoSinViaje.SIN_CAMBIO_REAL, f.actualizadaEn)
                else -> {
                    viajan += a
                    cubre += FilaCubierta(TablaDeLaFila.ARCHIVO, a.id, f.actualizadaEn)
                }
            }
        }
        if (viajan.isEmpty()) return null
        return PasoDeEnvio.Adjuntos(viajan, cubre)
    }
}

/** La sección del catálogo a la que pertenece un tipo de registro (casi siempre el mismo id). */
private fun seccionDelRegistro(tipo: String): String = when (tipo) {
    "examen_complementario" -> SeccionesComunes.EXAMENES_COMPLEMENTARIOS
    else -> tipo
}

/**
 * La sección del catálogo (el candado por sección, RF-4) a la que pertenece un tipo de carga. Solo el laboratorio general y el peso tienen envío
 * (T73, T84-a): el reducido no figura y por eso espera en reserva.
 */
private val SECCION_DE_LA_CARGA = mapOf(TIPO_LABORATORIO_GENERAL to "laboratorio_general", TIPO_PESO to "tabla_peso")

/**
 * La sección del catálogo de un tipo de carga de E3 (`laboratorio_general`, `tabla_peso`), o `null` si ese tipo no se envía (el reducido, T73). La usan el
 * plan y el contador (`core:data`, T85-a), para que los dos decidan con el mismo mapa: el `tipo` de la base (`peso`) no es el id de la sección (`tabla_peso`).
 */
internal fun seccionDeLaCarga(tipo: String): String? = SECCION_DE_LA_CARGA[tipo]
