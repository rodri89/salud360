package com.salud360.core.data.hcgeneral

import com.salud360.core.data.network.TobbException
import com.salud360.core.data.network.hc.HcPacienteCandidato
import com.salud360.core.data.network.hc.HcPacienteFicha
import com.salud360.core.data.network.hc.HcPacienteRequest
import com.salud360.core.model.Id
import com.salud360.core.model.TobbIds
import com.salud360.core.model.pacientes.Paciente

// Vínculo de un paciente de la app con su ficha en la web de cada historia clínica (spec 002, parte 1), sin red:
// lo usa `PacientesHcRepository`.

/** El máximo que guarda la web (`pacientes.dni` es `int`), como texto: se compara sin convertir, para no desbordar. */
private const val DNI_MAXIMO = "2147483647"
private const val DNI_MINIMO = 1000

/** Documento del paciente ficticio con el que turnosonlinebb bloquea horarios. */
private const val DNI_BLOQUEO = 99999

/**
 * El documento como lo va a guardar la web, o null si no sirve (RF-12). Misma regla que
 * `PacienteVinculoReglas::normalizarDni` de hclinica, para avisar antes de pedir: puntos y espacios se quitan, otro
 * signo o letra no vale; entre 1000 y 2147483647 y distinto de 99999.
 */
fun documentoParaHc(dni: String): String? {
    val texto = dni.filterNot { it == '.' || it.isWhitespace() }
    if (texto.isEmpty() || texto.any { it !in '0'..'9' }) return null
    val sinCeros = texto.trimStart('0')
    if (sinCeros.length > DNI_MAXIMO.length || (sinCeros.length == DNI_MAXIMO.length && sinCeros > DNI_MAXIMO)) return null
    val numero = sinCeros.ifEmpty { "0" }.toInt()
    if (numero < DNI_MINIMO || numero == DNI_BLOQUEO) return null
    return numero.toString()
}

/** RF-12, con el documento tal como está en la ficha del paciente. */
fun mensajeDocumentoInvalido(dni: String): String =
    "El documento «$dni» no sirve para la historia clínica: corregilo en la ficha del paciente"

private const val MENSAJE_FICHA_DE_OTRO =
    "La ficha de la web con ese documento ya corresponde a otro paciente de turnosonlinebb: avisale al administrador"

/**
 * Cuerpo de `pacientes/resolver` con la ficha del paciente de la app. Lo vacío va como null, que la web toma como "no
 * vino": nunca se manda un vacío como dato (RF-13). Va todo lo que tiene la app; cada API usa lo suyo.
 */
fun pedidoResolver(paciente: Paciente, idTobb: Long?): HcPacienteRequest = with(paciente) {
    HcPacienteRequest(
        dni = dni.trim(),
        nombre = nombre.trim(),
        apellido = apellido.trim(),
        pacienteIdTobb = idTobb,
        fechaNacimiento = fechaNacimiento?.toString(),
        sexo = sexo?.name,
        telefono = telefono.oNull(),
        mail = mail.oNull(),
        domicilio = domicilio.oNull(),
        localidad = localidad.oNull(),
        obraSocial = obraSocial.oNull(),
        numeroAfiliado = numeroAfiliado.oNull(),
        obraSocialPlan = obraSocialPlan.oNull(),
        nombrePadre = nombrePadre.oNull(),
        nombreMadre = nombreMadre.oNull(),
        cantidadHermanos = cantidadHermanos,
        nombreFamiliar = nombreFamiliar.oNull(),
        telefonoFamiliar = telefonoFamiliar.oNull(),
    )
}

private fun String.oNull(): String? = trim().ifEmpty { null }

/** Cómo terminó el intento de vincular un paciente con su ficha de una historia clínica. */
sealed interface ResultadoVinculo {
    data class Vinculado(val fichaId: Long) : ResultadoVinculo

    /** Varias fichas posibles con ese documento y ninguna desempata: elige el médico (RF-11). */
    data class ElegirFicha(val candidatas: List<HcPacienteCandidato>) : ResultadoVinculo

    /** RF-12: no se pide nada a la web. */
    data class DocumentoInvalido(val mensaje: String) : ResultadoVinculo

    /** RF-36: todas las fichas con ese documento son de otro paciente de turnosonlinebb. */
    data class FichaDeOtroPaciente(val mensaje: String) : ResultadoVinculo

    /** La ficha elegida ya no es posible (otro documento, de baja o vinculada con otro paciente). */
    data object FichaNoElegible : ResultadoVinculo

    /** Cualquier otro error; [error] es el original, para que quien lo muestre arme el motivo. */
    data class NoSePudo(val error: TobbException) : ResultadoVinculo
}

/** Lo que significa para el vínculo un error de `pacientes/resolver`. */
fun resultadoDe(e: TobbException): ResultadoVinculo = when (e.codigo) {
    "paciente_ambiguo" -> ResultadoVinculo.ElegirFicha(e.candidatos)
    "paciente_ya_vinculado" -> ResultadoVinculo.FichaDeOtroPaciente(MENSAJE_FICHA_DE_OTRO)
    "dni_invalido" -> ResultadoVinculo.DocumentoInvalido(e.message.orEmpty())
    "ficha_no_elegible" -> ResultadoVinculo.FichaNoElegible
    else -> ResultadoVinculo.NoSePudo(e)
}

/**
 * Historias clínicas de las que se trae la cartera del médico (RF-8): las habilitadas que tienen API, normalizadas
 * ([normalizarHistoriasClinicas]: turnos puede seguir diciendo "hepatologia", que es la HC general, spec 001 RF-29), sin
 * repetir y en el orden de [especialidadesHc].
 */
fun historiasConCartera(especialidadesHc: List<String>, conApi: Set<String>): Set<String> =
    normalizarHistoriasClinicas(especialidadesHc).filter { it in conApi }.toSet()

/**
 * Con qué id vive en el dispositivo un paciente que llega en la cartera (RF-37). Si la web ya sabe cuál es en turnos,
 * ese, así queda una sola ficha aunque esté en dos carteras; si no, uno propio de esa historia clínica, siempre el
 * mismo. Nunca se empareja por documento.
 */
fun idLocalDeFicha(especialidad: String, ficha: HcPacienteFicha): Id =
    if (ficha.pacienteIdTobb > 0) TobbIds.paciente(ficha.pacienteIdTobb) else "hc-$especialidad-p${ficha.id}"
