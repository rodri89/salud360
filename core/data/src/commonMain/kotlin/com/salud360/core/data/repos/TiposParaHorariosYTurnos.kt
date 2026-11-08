package com.salud360.core.data.repos

import com.salud360.core.data.network.TobbException
import com.salud360.core.model.turnos.OpcionTipoTurno
import com.salud360.core.model.turnos.SlotAgenda
import com.salud360.core.model.turnos.TipoTurno
import com.salud360.core.model.turnos.TipoTurnoDelMedico
import com.salud360.core.model.turnos.Turno

/**
 * Reglas puras de los tipos de turno que habilitó el administrador para un médico (sin red ni pantallas): qué tipos se
 * ofrecen al cargar un horario, qué opciones (subtipos) se ofrecen al asignar un turno sobre un horario, con qué tipo
 * se registra y cómo se rotula. La lista de tipos del médico ([TipoTurnoDelMedico]) la lee `AgendaTurnosOnline` de
 * turnos y la guarda en memoria; acá solo se decide qué hacer con ella, incluida la falta de lista (servidor viejo, sin
 * permiso o sin red), que degrada al comportamiento de antes.
 *
 * La condición de un tipo (primera consulta / seguimiento) es solo informativa: nada de acá la usa para filtrar.
 */

/** Texto de la opción que no elige ninguna: el turno queda con el tipo del horario. */
const val TEXTO_SIN_ESPECIFICAR = "Sin especificar"

/**
 * Un tipo que se ofrece en el selector de un horario. [habilitado] = `false` solo para el tipo actual de un horario que
 * el administrador ya no tiene habilitado: se sigue mostrando para no reescribirlo sin querer (turnos lo rechazaría).
 */
data class TipoOfrecido(val codigo: Int, val nombre: String, val habilitado: Boolean = true) {
    /** Lo que se lee en el selector. */
    val etiqueta: String get() = if (habilitado) nombre else "$nombre (ya no habilitado)"
}

/**
 * Los tipos que se ofrecen al cargar un horario de un médico, en el orden que informó turnos.
 *
 * - [tiposDelMedico] `null` = no se pudo leer (servidor viejo, sin permiso, sin red) y `[]` = sin tipos configurados:
 *   en ambos casos se ofrece solo «Consulta» (tipo 1), nunca el enum cerrado completo.
 * - [tipoActual] es el tipo ya elegido o el del horario que se está viendo: se incluye siempre. Si no está entre los
 *   habilitados queda marcado como no habilitado; si la lista no se pudo leer no se sabe, así que no se lo marca.
 * - [medicoRemoto] `false` (médico propio de Salud 360, sin tipos de turnos) conserva los tipos del enum de siempre.
 * - [nombresDeTipos]: los nombres que informó turnos, para rotular los códigos que la lista no trae.
 */
fun tiposOfrecidosParaHorario(
    tiposDelMedico: List<TipoTurnoDelMedico>?,
    tipoActual: Int? = null,
    nombresDeTipos: Map<Int, String> = emptyMap(),
    medicoRemoto: Boolean = true,
): List<TipoOfrecido> {
    val base = when {
        !medicoRemoto -> TipoTurno.entries.map { TipoOfrecido(it.codigo, it.etiqueta) }
        tiposDelMedico.isNullOrEmpty() -> listOf(TipoOfrecido(CODIGO_TIPO_CONSULTA, etiquetaDelTipo(CODIGO_TIPO_CONSULTA, nombresDeTipos[CODIGO_TIPO_CONSULTA])))
        else -> tiposDelMedico.map { TipoOfrecido(it.id, etiquetaDelTipo(it.id, it.nombre)) }.distinctBy { it.codigo }
    }
    if (tipoActual == null || base.any { it.codigo == tipoActual }) return base
    return base + TipoOfrecido(tipoActual, etiquetaDelTipo(tipoActual, nombresDeTipos[tipoActual]), habilitado = tiposDelMedico == null)
}

/** ¿Hay algo que elegir? Con un solo tipo ofrecido el selector no se muestra y se usa ese. */
fun hayQueElegirTipo(ofrecidos: List<TipoOfrecido>): Boolean = ofrecidos.size > 1

/** El tipo con que se carga el horario: el elegido, o —si todavía no se eligió nada— el primero que se ofrece. */
fun tipoElegidoParaHorario(ofrecidos: List<TipoOfrecido>, elegido: Int?): Int =
    elegido ?: ofrecidos.firstOrNull()?.codigo ?: CODIGO_TIPO_CONSULTA

/** Las opciones (subtipos) que se pueden elegir al asignar un turno sobre un horario de tipo [tipoDelSlot]; vacío si no tiene. */
fun opcionesParaSlot(tiposDelMedico: List<TipoTurnoDelMedico>?, tipoDelSlot: Int): List<OpcionTipoTurno> =
    tiposDelMedico?.firstOrNull { it.id == tipoDelSlot }?.opciones.orEmpty().filter { it.id != tipoDelSlot }.distinctBy { it.id }

/**
 * Código con el que se registra un turno sobre [slot]: el de la [opcionElegida] si es una opción válida del tipo del
 * horario; si no (sin opción, de otro tipo, sin lista de tipos o sin horario), el del horario como siempre.
 */
fun tipoConQueSeRegistra(slot: SlotAgenda?, opcionElegida: Int?, tiposDelMedico: List<TipoTurnoDelMedico>?): Int {
    val delSlot = codigoParaRegistrar(slot)
    if (slot == null || opcionElegida == null) return delSlot
    return if (opcionesParaSlot(tiposDelMedico, delSlot).any { it.id == opcionElegida }) opcionElegida else delSlot
}

/** El turno con el tipo [codigo] (conserva el código real aunque el enum cerrado no lo conozca). */
fun Turno.conTipo(codigo: Int): Turno = copy(tipoTurno = TipoTurno.porCodigo(codigo), tipoCodigo = TipoTurno.codigoSiNoEsDelEnum(codigo))

/**
 * Cómo se rotula el tipo de un turno. Si el código es de una opción de un único tipo habilitado, «Tipo: Opción»; si el
 * padre no se conoce (sin lista, o la opción es de dos tipos), solo el nombre de la opción; un tipo habilitado por sí
 * mismo se rotula solo. El nombre sale de lo que informó turnos, luego de la lista y por último del enum o «Tipo <código>».
 */
fun etiquetaDelTipoDelTurno(codigo: Int, nombresDeTipos: Map<Int, String>, tiposDelMedico: List<TipoTurnoDelMedico>?): String {
    val tipos = tiposDelMedico.orEmpty()
    if (tipos.any { it.id == codigo }) return etiquetaDelTipo(codigo, nombresDeTipos[codigo] ?: tipos.first { it.id == codigo }.nombre)
    val padres = tipos.filter { t -> t.opciones.any { it.id == codigo } }
    val nombreDeLaOpcion = nombresDeTipos[codigo] ?: padres.firstOrNull()?.opciones?.firstOrNull { it.id == codigo }?.nombre
    val nombre = etiquetaDelTipo(codigo, nombreDeLaOpcion)
    val padre = padres.singleOrNull() ?: return nombre
    return "${etiquetaDelTipo(padre.id, padre.nombre)}: $nombre"
}

/** Aviso al registrar: «Turno registrado», y —si se eligió una opción y no el tipo del horario— cuál. */
fun avisoDeTurnoRegistrado(codigoRegistrado: Int, slot: SlotAgenda?, nombresDeTipos: Map<Int, String>, tiposDelMedico: List<TipoTurnoDelMedico>?): String =
    if (slot == null || codigoRegistrado == slot.codigoDeTipo) "Turno registrado"
    else "Turno registrado · ${etiquetaDelTipoDelTurno(codigoRegistrado, nombresDeTipos, tiposDelMedico)}"

/** Ayuda debajo del selector de opciones: con qué queda el turno según lo elegido ([etiquetaDelHorario] es el tipo del horario). */
fun ayudaDeOpcion(etiquetaDelHorario: String, elegida: OpcionTipoTurno?): String =
    "Se registra como $etiquetaDelHorario" + (elegida?.let { ": ${it.nombre}" } ?: "")

/** Cómo salió la lectura de los tipos habilitados de un médico (`GET medicos/{n}/tipos-turno`). */
sealed interface LecturaDeTiposDelMedico {
    /** Turnos los informó (puede ser la lista vacía: el médico no tiene tipos configurados). */
    data class Leidos(val tipos: List<TipoTurnoDelMedico>) : LecturaDeTiposDelMedico

    /** Turnos no los da a este usuario o no tiene la ruta (servidor viejo, 403, 404/405) o el médico no es de turnos: se degrada sin avisar. */
    data object NoDisponible : LecturaDeTiposDelMedico

    /** No hubo conexión; si ya había una lista en memoria, sigue valiendo. */
    data class SinConexion(val motivo: String?) : LecturaDeTiposDelMedico
}

/** Qué significa que la lectura de los tipos del médico haya fallado con [e]: sin conexión, o no disponible (todo lo demás). */
fun lecturaDeTiposFallida(e: Throwable): LecturaDeTiposDelMedico =
    if (e is TobbException && (e.codigo == "red" || e.status == 0)) LecturaDeTiposDelMedico.SinConexion(e.message) else LecturaDeTiposDelMedico.NoDisponible

/**
 * Texto discreto para Horarios cuando no se pudieron leer los tipos: solo si fue por falta de conexión y no había
 * nada en memoria. Un servidor viejo o sin permiso degrada sin avisar (sería un cartel permanente hasta actualizarlo).
 */
fun avisoDeTiposNoLeidos(lectura: LecturaDeTiposDelMedico, hayEnMemoria: Boolean): String? =
    if (lectura is LecturaDeTiposDelMedico.SinConexion && !hayEnMemoria) {
        "No se pudieron leer los tipos de turno habilitados del médico (sin conexión): por ahora se ofrece solo «Consulta»."
    } else null
