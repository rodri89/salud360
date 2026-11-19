package com.salud360.core.data.repos

import com.salud360.core.model.Id
import com.salud360.core.model.TobbIds
import com.salud360.core.model.turnos.CondicionTipoTurno
import com.salud360.core.model.turnos.TipoTurnoCatalogo
import com.salud360.core.model.turnos.TipoTurnoDelMedico

/**
 * Tipos de turno de un médico, desde el panel del administrador (solo administrador, solo médicos de turnos). Reglas
 * puras, sin Compose ni red: el dueño del dato es turnos, que recibe la lista EXACTA de tipos habilitados con su
 * condición y sus opciones (`PUT medicos/{id}/tipos-turno`). La pantalla solo muestra [ConfigTiposEditor] y delega acá.
 *
 * Tildar un tipo lo habilita y despliega sus opciones; la condición (primera consulta / seguimiento) es solo
 * configuración: la app todavía no filtra nada con ella.
 */

/** Largo máximo del nombre de un tipo nuevo (lo valida también turnos, con un `422`). */
const val LARGO_MAXIMO_NOMBRE_DE_TIPO = 60

/** Un tipo del pedido a turnos. [opciones] `null` = no se tocan las que turnos ya tiene; `[]` = se dejan vacías. */
data class ConfigTipoTurno(val id: Int, val condicion: CondicionTipoTurno, val opciones: List<Int>?)

/** Lo que turnos confirmó al guardar: los tipos que quedaron habilitados y los avisos (p. ej. un tipo quitado con horarios). */
data class GuardadoTipos(val tipos: List<TipoTurnoDelMedico>, val avisos: List<String>)

/**
 * Cómo está un tipo en pantalla. Un tipo destildado conserva [condicion] y [opciones] mientras se edita: al volver a
 * tildarlo reaparece lo que tenía. [opcionesTocadas]: el administrador cambió las opciones; solo entonces viajan en el
 * pedido, así no se pisan las que turnos tiene guardadas (y que quizá otro administrador cambió) por una copia vieja.
 */
data class TipoEnEdicion(
    val habilitado: Boolean = false,
    val condicion: CondicionTipoTurno = CondicionTipoTurno.SIEMPRE,
    val opciones: Set<Int> = emptySet(),
    val opcionesTocadas: Boolean = false,
)

/** Estado de edición de los tipos de un médico, por id de tipo. Un tipo que no está se ve como destildado. */
data class ConfigTiposEditor(val tipos: Map<Int, TipoEnEdicion> = emptyMap()) {
    fun estadoDe(tipoId: Int): TipoEnEdicion = tipos[tipoId] ?: TipoEnEdicion()

    /** Lo que de verdad se manda: solo los tipos habilitados, con su condición y sus opciones (sin la marca de «tocadas»). */
    internal fun efectivo(): Map<Int, Pair<CondicionTipoTurno, Set<Int>>> =
        tipos.filterValues { it.habilitado }.mapValues { (_, e) -> e.condicion to e.opciones }

    internal fun difiereDe(otro: ConfigTiposEditor): Boolean = efectivo() != otro.efectivo()
}

/** Cómo va la carga de los tipos de un médico: se pide a turnos cuando el administrador abre su sección. */
sealed interface EstadoTiposDelMedico {
    data object Cargando : EstadoTiposDelMedico

    data class Error(val motivo: String) : EstadoTiposDelMedico

    /** [original] es lo último que informó turnos; [avisos], los del último guardado (se muestran hasta la próxima edición). */
    data class Listo(
        val editor: ConfigTiposEditor,
        val original: ConfigTiposEditor,
        val avisos: List<String> = emptyList(),
    ) : EstadoTiposDelMedico {
        val hayCambios: Boolean get() = editor.difiereDe(original)
    }
}

/** ¿Se ve la sección «Tipos de turno» de un médico? Con agenda y siendo de turnosonlinebb (los tipos viven allá). */
fun puedeVerTiposDeTurno(tieneAgenda: Boolean, medicoId: Id): Boolean =
    puedeVerConfigDeAgenda(tieneAgenda) && TobbIds.numero(medicoId) != null

/** Los tipos que se ofrecen: el catálogo activo, en su orden. Los inactivos no se ofrecen. */
fun tiposOfrecidos(catalogo: List<TipoTurnoCatalogo>): List<TipoTurnoCatalogo> = catalogo.filter { it.activo }

/** Las opciones que se ofrecen para [tipoId]: el resto del catálogo activo (un tipo no es opción de sí mismo). */
fun opcionesPosibles(catalogo: List<TipoTurnoCatalogo>, tipoId: Int): List<TipoTurnoCatalogo> =
    tiposOfrecidos(catalogo).filter { it.id != tipoId }

/**
 * Arma el editor con lo que informó turnos: tildados los tipos del médico (con condición y opciones), destildado el
 * resto del catálogo. Un tipo del médico que el catálogo no trae (o que está inactivo) igual queda tildado, para que el
 * pedido —que es la lista exacta— no lo suelte sin que el administrador lo haya destildado.
 */
fun desdeServidor(catalogo: List<TipoTurnoCatalogo>, delMedico: List<TipoTurnoDelMedico>): ConfigTiposEditor {
    val porId = delMedico.associateBy { it.id }
    val ids = (catalogo.map { it.id } + delMedico.map { it.id }).distinct()
    return ConfigTiposEditor(
        ids.associateWith { id ->
            porId[id]?.let { TipoEnEdicion(habilitado = true, condicion = it.condicion, opciones = it.opciones.map { o -> o.id }.toSet()) }
                ?: TipoEnEdicion()
        },
    )
}

/** Un [EstadoTiposDelMedico.Listo] recién traído de turnos: editor y original iguales. */
fun tiposListos(catalogo: List<TipoTurnoCatalogo>, delMedico: List<TipoTurnoDelMedico>, avisos: List<String> = emptyList()): EstadoTiposDelMedico.Listo {
    val editor = desdeServidor(catalogo, delMedico)
    return EstadoTiposDelMedico.Listo(editor, editor, avisos)
}

/** Tilda ([habilitado] = `true`) o destilda un tipo. Destildar conserva su condición y sus opciones en pantalla. */
fun alternarTipo(editor: ConfigTiposEditor, tipoId: Int, habilitado: Boolean): ConfigTiposEditor =
    editor.cambiar(tipoId) { it.copy(habilitado = habilitado) }

/** Cambia la condición de un tipo. Solo si el tipo está tildado: destildado no se ve ni se manda. */
fun cambiarCondicion(editor: ConfigTiposEditor, tipoId: Int, condicion: CondicionTipoTurno): ConfigTiposEditor =
    if (!editor.estadoDe(tipoId).habilitado) editor else editor.cambiar(tipoId) { it.copy(condicion = condicion) }

/**
 * Marca o desmarca [opcionId] como opción de [tipoId]. Solo si [tipoId] está tildado, si la opción es un tipo del
 * catálogo activo y no es el propio tipo; si no, devuelve el mismo editor. No exige que la opción esté habilitada para el médico.
 */
fun alternarOpcion(editor: ConfigTiposEditor, catalogo: List<TipoTurnoCatalogo>, tipoId: Int, opcionId: Int, marcada: Boolean): ConfigTiposEditor {
    if (!editor.estadoDe(tipoId).habilitado) return editor
    if (opcionId == tipoId || catalogo.none { it.id == opcionId && it.activo }) return editor
    return editor.cambiar(tipoId) { it.copy(opciones = if (marcada) it.opciones + opcionId else it.opciones - opcionId, opcionesTocadas = true) }
}

private fun ConfigTiposEditor.cambiar(tipoId: Int, cambio: (TipoEnEdicion) -> TipoEnEdicion): ConfigTiposEditor =
    copy(tipos = tipos + (tipoId to cambio(estadoDe(tipoId))))

/**
 * La lista exacta que queda habilitada, por id: solo los tipos tildados, con su condición. Las `opciones` viajan solo
 * en los tipos cuyas opciones el administrador tocó (ordenadas; si las vació, `[]`); en el resto van `null` y turnos
 * conserva las que tiene. Sin tipos tildados es la lista vacía, que también es un pedido válido.
 */
fun aPedido(editor: ConfigTiposEditor): List<ConfigTipoTurno> =
    editor.tipos.entries
        .filter { it.value.habilitado }
        .sortedBy { it.key }
        .map { (id, e) -> ConfigTipoTurno(id, e.condicion, if (e.opcionesTocadas) e.opciones.sorted() else null) }

/**
 * ¿El administrador cambió algo respecto de lo que informó turnos? Compara los tipos tildados con su condición y sus
 * opciones: tildar y destildar, o marcar y desmarcar, no deja cambios; lo que tenga un tipo destildado no cuenta.
 */
fun hayCambios(original: ConfigTiposEditor, editor: ConfigTiposEditor): Boolean = editor.difiereDe(original)

/** El catálogo con el tipo recién creado al final, sin duplicarlo: sirve si el alta salió bien y el refresco del catálogo falló. */
fun conTipoCreado(catalogo: List<TipoTurnoCatalogo>, creado: TipoTurnoCatalogo): List<TipoTurnoCatalogo> =
    catalogo.filterNot { it.id == creado.id } + creado

/** El nombre para crear un tipo: recortado y de 1 a [LARGO_MAXIMO_NOMBRE_DE_TIPO] caracteres; si no, `null`. */
fun nombreNuevoDeTipo(texto: String): String? = texto.trim().takeIf { it.isNotEmpty() && it.length <= LARGO_MAXIMO_NOMBRE_DE_TIPO }

/** El error que se muestra mientras se escribe el nombre; un nombre vacío no muestra error (solo deshabilita «Crear»). */
fun errorDelNombreDeTipo(texto: String): String? =
    if (texto.trim().length > LARGO_MAXIMO_NOMBRE_DE_TIPO) "El nombre puede tener hasta $LARGO_MAXIMO_NOMBRE_DE_TIPO caracteres." else null

private fun motivoDe(motivo: String?): String = motivo?.takeIf { it.isNotBlank() } ?: "sin conexión"

/** Aviso al guardar: turnos confirmó. Si informó avisos (p. ej. un tipo quitado que sigue con horarios) se muestran tal cual. */
fun avisoDeTiposGuardados(avisosDeTurnos: List<String>): String {
    val avisos = avisosDeTurnos.map { it.trim() }.filter { it.isNotEmpty() }
    return if (avisos.isEmpty()) "Tipos de turno guardados" else "Tipos de turno guardados. ${avisos.joinToString(" ")}"
}

/** Aviso cuando no se guardó: el motivo es el real (el de turnos o el de la red). */
fun avisoDeTiposNoGuardados(motivo: String?): String = "No se pudieron guardar los tipos de turno: ${motivoDe(motivo)}"

/** Aviso cuando turnos creó el tipo. */
fun avisoDeTipoCreado(): String = "Tipo de turno creado"

/** Aviso cuando no se creó el tipo: el motivo es el real (p. ej. «nombre repetido» con el mensaje de turnos). */
fun avisoDeTipoNoCreado(motivo: String?): String = "No se pudo crear el tipo de turno: ${motivoDe(motivo)}"

/** Texto cuando no se pudieron traer los tipos del médico (queda en la sección, con «Reintentar»). */
fun avisoDeTiposNoCargados(motivo: String?): String = "No se pudieron cargar los tipos de turno: ${motivoDe(motivo)}"
