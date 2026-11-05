package com.salud360.core.data.repos

/**
 * Reglas de la agenda por médico (spec 005), en un solo lugar y sin Compose, red ni reloj: quién tiene agenda, quién se
 * ve para pedir turno, qué muestra cada pantalla y qué pedido puede salir hacia turnos. Las pantallas y los
 * repositorios preguntan acá en vez de armar condiciones sueltas.
 *
 * «Tiene agenda» es una decisión del administrador que informa turnos (`tiene_agenda`); `tiene_horarios` solo se usa
 * de respaldo cuando el servidor todavía no lo informa. El consultorio no entra en esa decisión: lo mira cada acceso
 * que necesita abrir la agenda de un consultorio ([puedeVerAgenda], [puedeVerHorarios]).
 */

/** Consultorio 0 de turnos = «sin consultorio»; la app lo guarda como `tobb-c0`. */
private const val CONSULTORIO_CERO = "tobb-c0"

/**
 * ¿El médico tiene agenda? Manda `tiene_agenda` si turnos lo informa; si no (servidor sin actualizar) rige el criterio
 * de siempre: tiene horarios cargados (`tiene_horarios == 1`).
 */
fun tieneAgendaDe(tieneAgenda: Int?, tieneHorarios: Int): Boolean =
    if (tieneAgenda != null) tieneAgenda == 1 else tieneHorarios == 1

/**
 * ¿El médico se ofrece al paciente? Manda `visible_en_turnos` si turnos lo informa; si no, el valor que el dispositivo
 * ya tenía ([previo]) y, sin previo, `true` (lo de siempre). Nunca vuelve a `true` un valor guardado: eso pisaba el
 * interruptor del panel.
 */
fun visibleDe(visible: Int?, previo: Boolean?): Boolean =
    if (visible != null) visible == 1 else previo ?: true

/** ¿Hay un consultorio real? No lo es ni el nulo, ni el vacío, ni el consultorio 0 de turnos. */
fun tieneConsultorio(consultorioId: String?): Boolean =
    !consultorioId.isNullOrBlank() && consultorioId != CONSULTORIO_CERO

/** Agenda y turnos: necesitan agenda y un consultorio donde abrirla. */
fun puedeVerAgenda(tieneAgenda: Boolean, consultorioId: String?): Boolean = tieneAgenda && tieneConsultorio(consultorioId)

/** «Horarios de atención»: agenda y consultorio, tenga o no horarios cargados todavía. */
fun puedeVerHorarios(tieneAgenda: Boolean, consultorioId: String?): Boolean = tieneAgenda && tieneConsultorio(consultorioId)

/** Obras sociales, reservas, cupo, módulos y mensajes: solo con agenda. */
fun puedeVerConfigDeAgenda(tieneAgenda: Boolean): Boolean = tieneAgenda

/** Recetas: solo con agenda. */
fun puedeVerRecetas(tieneAgenda: Boolean): Boolean = tieneAgenda

/** El ítem «Config» del médico: con agenda o con alguna historia clínica (la configuración de la HC no depende de los turnos). */
fun puedeVerMenuConfig(tieneAgenda: Boolean, especialidadesHc: List<String>): Boolean =
    tieneAgenda || especialidadesHc.isNotEmpty()

/**
 * ¿Está habilitado el interruptor «Visible para pacientes»? Con agenda, siempre; sin agenda solo para apagarlo (así el
 * administrador puede limpiar un médico que turnos informa como «visible sin agenda»), nunca para prenderlo.
 */
fun puedeCambiarVisible(tieneAgenda: Boolean, visible: Boolean): Boolean = tieneAgenda || visible

/**
 * Alta de usuario (RF-14): ¿se manda `mostrar_en_turnos = true`? Solo para un médico con agenda que lo pidió; sin
 * agenda no tiene sentido ofrecerse para pedir turno, y los demás roles nunca.
 */
fun mostrarEnTurnosEnElAlta(esMedico: Boolean, tieneAgenda: Boolean, mostrarEnTurnos: Boolean): Boolean =
    esMedico && tieneAgenda && mostrarEnTurnos

/** Alta de usuario (RF-14): la casilla «Mostrar para pedir turno» solo se puede marcar si el médico tiene agenda. */
fun puedeMarcarMostrarEnElAlta(tieneAgenda: Boolean): Boolean = tieneAgenda

/** Lo que turnos tiene guardado de un médico: sus dos datos independientes. */
data class EstadoAgenda(val tieneAgenda: Boolean, val visible: Boolean)

/** Lo que turnos confirmó al guardar la agenda, con la cantidad de turnos futuros que cuenta el servidor. */
data class AgendaGuardada(val tieneAgenda: Boolean, val visible: Boolean, val turnosFuturos: Int)

/** Resultado de [cambioDeAgenda]: el pedido que sale a turnos o el motivo por el que ni siquiera se envía. */
sealed interface CambioDeAgenda {
    /** Solo viaja lo que no es `null`. */
    data class Enviar(val tieneAgenda: Boolean?, val visible: Boolean?) : CambioDeAgenda

    data class Rechazado(val motivo: String) : CambioDeAgenda
}

/**
 * Arma el pedido del administrador antes de salir a la red. [tieneAgenda] y [visible] son lo que se quiere cambiar
 * (`null` = no se toca).
 *  - Apagar la agenda manda solo `tiene_agenda = false`: turnos deja al médico no visible (RF-5). Pedir además
 *    «visible» se rechaza; pedir «no visible» es redundante y tampoco se manda.
 *  - Prender «visible» sin agenda (ni existente ni pedida en el mismo cambio) se rechaza (RF-6).
 *  - Apagar «visible» se envía siempre, también en un médico sin agenda.
 *  - Un cambio sin ningún campo se rechaza.
 */
fun cambioDeAgenda(actual: EstadoAgenda, tieneAgenda: Boolean?, visible: Boolean?): CambioDeAgenda {
    if (tieneAgenda == null && visible == null) return CambioDeAgenda.Rechazado("No hay ningún cambio para guardar.")
    if (tieneAgenda == false) {
        if (visible == true) return CambioDeAgenda.Rechazado("Un médico sin agenda no puede estar visible para los pacientes.")
        return CambioDeAgenda.Enviar(tieneAgenda = false, visible = null)
    }
    if (visible == true && tieneAgenda != true && !actual.tieneAgenda) {
        return CambioDeAgenda.Rechazado("Primero hay que activar la agenda del médico: sin agenda no puede estar visible para los pacientes.")
    }
    return CambioDeAgenda.Enviar(tieneAgenda = tieneAgenda, visible = visible)
}

/**
 * Aviso para el administrador después de guardar (RF-18): si la agenda pasó de prendida a apagada y el médico tiene
 * turnos futuros, dice cuántos y que siguen vigentes. En cualquier otro caso, `null`.
 */
fun avisoDeAgendaGuardada(antes: EstadoAgenda, ahora: AgendaGuardada): String? {
    if (!antes.tieneAgenda || ahora.tieneAgenda || ahora.turnosFuturos <= 0) return null
    val n = ahora.turnosFuturos
    val turnos = if (n == 1) "1 turno futuro" else "$n turnos futuros"
    return "Agenda apagada. El médico tiene $turnos; siguen vigentes (no se cancelan)."
}
