package com.salud360.core.data.hcgeneral

/** RF-39: buscador vacío (o solo con espacios). */
const val TEXTO_ESCRIBI_PARA_BUSCAR = "Escribí para buscar en el catálogo"

/** E3: no se marcó y la web no dijo por qué (un `sin_marcar_por` que la app no conoce). */
private const val SIN_MOTIVO = "no se marcó al paciente."

/**
 * Lo que muestra "Administrar enfermedades" para lo escrito (RF-39): de la lista del médico y de "Del catálogo" las
 * que lo contienen, en orden alfabético. [textoCatalogo] es el texto de RF-39 en lugar de "Del catálogo" mientras no
 * haya nada escrito, o null.
 */
data class BusquedaAdministrar(val lista: List<Enfermedad>, val catalogo: List<Enfermedad>, val textoCatalogo: String?)

/** RF-42: "Crear «[nombre]»" con el nombre normalizado; con [error] (RF-43) no se envía y se muestra el texto. */
data class OfertaCrear(val nombre: String, val error: String?)

/** Por qué crear no marca al paciente de la consulta (RF-58), con el [texto] que va después de "porque". */
enum class MotivoSinMarcar(val texto: String) {
    NO_VINCULADO("el paciente no está vinculado a una ficha de la web"),
    FUERA_DE_CARTERA("el paciente no está en tu cartera"),
    NO_EDITABLE("la consulta no se puede editar"),
}

/** A quién se manda con "Crear" ([pacienteParaCrear]). */
sealed interface PacienteCrear {
    /** La ficha de la web del paciente de la consulta: la web la marca (RF-44, D19). */
    data class ConFicha(val fichaId: Long) : PacienteCrear

    /** Sin paciente: solo crea o reutiliza y suma a la lista (RF-58). */
    data class SinMarcar(val motivo: MotivoSinMarcar) : PacienteCrear
}

/**
 * La respuesta de crear que hace falta para el aviso (E4; F2.6 la arma desde el DTO). [enfermedad] trae el nombre del
 * catálogo, que con [reutilizada] puede no ser el escrito; [reactivada] va siempre con [reutilizada]. [sinMarcarPor]
 * queda crudo: la web puede mandar un valor que la app no conoce.
 */
data class ResultadoCrear(
    val enfermedad: Enfermedad,
    val reutilizada: Boolean,
    val reactivada: Boolean,
    val yaEnLista: Boolean,
    val marcada: Boolean,
    val sinMarcarPor: String?,
)

/**
 * Filtra en el dispositivo, sin pedidos (RF-39): la [claveDeNombre] de lo escrito contenida en la del nombre. Los
 * duplicados del catálogo se ven todos; sin nada escrito, la lista entera y "Del catálogo" vacío con su texto.
 */
fun filtrar(estado: EstadoAdministrar, buscado: String): BusquedaAdministrar {
    val clave = claveDeNombre(buscado)
    if (clave.isEmpty()) return BusquedaAdministrar(ordenAlfabetico(estado.lista), emptyList(), TEXTO_ESCRIBI_PARA_BUSCAR)
    fun contiene(e: Enfermedad) = clave in claveDeNombre(e.nombre)
    return BusquedaAdministrar(
        ordenAlfabetico(estado.lista.filter(::contiene)),
        ordenAlfabetico(estado.catalogo.filter(::contiene)),
        null,
    )
}

/**
 * RF-42: se ofrece "Crear" con el nombre normalizado si no está vacío y nadie de la lista ni del catálogo tiene el
 * mismo nombre ([mismoNombre]); si no, null. Un nombre inválido se ofrece con el error de RF-43 para mostrarlo.
 */
fun ofrecerCrear(estado: EstadoAdministrar, buscado: String): OfertaCrear? {
    val nombre = nombreNormalizado(buscado)
    if (nombre.isEmpty()) return null
    if ((estado.lista + estado.catalogo).any { mismoNombre(it.nombre, nombre) }) return null
    return OfertaCrear(nombre, errorDeNombreEnfermedad(nombre))
}

/**
 * RF-44, RF-58: la ficha va solo con la consulta editable y el paciente vinculado y en la cartera. Si la consulta ya no
 * se puede editar, ese es el motivo aunque también falte el vínculo. Un motivo general (lugar 1-5) no es de vínculo:
 * con la ficha guardada se manda y la web decide.
 */
fun pacienteParaCrear(fichaId: Long?, motivo: MotivoEnfermedades?, editable: Boolean): PacienteCrear = when {
    !editable -> PacienteCrear.SinMarcar(MotivoSinMarcar.NO_EDITABLE)
    motivo is MotivoEnfermedades.FueraDeCartera -> PacienteCrear.SinMarcar(MotivoSinMarcar.FUERA_DE_CARTERA)
    fichaId == null || motivo is MotivoEnfermedades.ElegirFicha || motivo is MotivoEnfermedades.SinFicha ||
        motivo is MotivoEnfermedades.FichaNoDisponible -> PacienteCrear.SinMarcar(MotivoSinMarcar.NO_VINCULADO)
    else -> PacienteCrear.ConFicha(fichaId)
}

/**
 * RF-37, D10: en una consulta abierta propia y con conexión, esté o no el paciente vinculado (también con los motivos
 * 6 a 9); no con los motivos generales (1 a 5: sesión, HC, médico, licencia, sin conexión o error de la web).
 */
fun puedeAdministrar(editable: Boolean, propia: Boolean, conConexion: Boolean, motivo: MotivoEnfermedades?): Boolean =
    editable && propia && conConexion && (motivo == null || motivo.prioridad > 5)

/**
 * El aviso después de crear, o null si no hace falta (nueva y marcada: la grilla ya la muestra marcada). Con los textos
 * de RF-44 y RF-58; el nombre es siempre el del catálogo. El [motivoLocal] (lo que dio [pacienteParaCrear]) gana sobre
 * lo que diga la web; un `sin_marcar_por` desconocido, o ninguno sin marcar, no dice motivo (E3).
 */
fun avisoCrear(resultado: ResultadoCrear, motivoLocal: MotivoSinMarcar?): String? {
    val nombre = resultado.enfermedad.nombre
    if (motivoLocal == null && resultado.marcada) {
        return when {
            resultado.yaEnLista -> "«$nombre» ya estaba en tu lista; se marcó al paciente"
            resultado.reutilizada -> "Ya existía en el catálogo como «$nombre»; se agregó a tu lista y se marcó al paciente"
            else -> null
        }
    }
    val motivo = motivoLocal ?: when (resultado.sinMarcarPor) {
        "paciente_no_encontrado" -> MotivoSinMarcar.NO_VINCULADO
        "fuera_de_cartera" -> MotivoSinMarcar.FUERA_DE_CARTERA
        else -> null
    }
    val noSeMarco = motivo?.let { "no se marcó al paciente porque ${it.texto}" } ?: SIN_MOTIVO
    return when {
        // Ya estaba: "Se agregó a tu lista" no correspondería.
        resultado.yaEnLista -> "«$nombre» ya estaba en tu lista. ${noSeMarco.replaceFirstChar { it.uppercase() }}"
        resultado.reutilizada -> "Ya existía en el catálogo como «$nombre». Se agregó a tu lista; $noSeMarco"
        else -> "Se agregó a tu lista; $noSeMarco"
    }
}
