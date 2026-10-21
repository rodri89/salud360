package com.salud360.core.data.hcgeneral

import com.salud360.core.data.network.TobbException
import com.salud360.core.data.network.hc.HcPacienteCandidato

/** RF-10 (C1): la ficha elegida ya no es posible (`ficha_no_elegible`). */
const val TEXTO_FICHA_NO_ELEGIBLE =
    "La ficha elegida no corresponde a este paciente. Elegí otra o pedile al administrador que revise el documento."

/**
 * Por qué la sección "Enfermedades" no muestra la grilla, con su lugar en el orden de RF-14 ([prioridad], 1 primero)
 * y el [texto] para mostrar. La lista vacía (RF-18) no es un motivo.
 */
sealed interface MotivoEnfermedades {
    val prioridad: Int
    val texto: String

    /**
     * Los de la HC general, con su texto (RF-13): sesión 1, HC 2, médico 3, licencia 4; sin conexión y los errores no
     * previstos (`OTRO`) 5, como sin conexión con el texto de la web (PD3, RF-30, RF-31).
     */
    data class General(val motivo: MotivoHc) : MotivoEnfermedades {
        override val prioridad: Int
            get() = when (motivo.tipo) {
                MotivoHc.Tipo.SESION_VENCIDA -> 1
                MotivoHc.Tipo.HC_NO_HABILITADA -> 2
                MotivoHc.Tipo.MEDICO_NO_VINCULADO -> 3
                MotivoHc.Tipo.LICENCIA_VENCIDA -> 4
                MotivoHc.Tipo.SIN_CONEXION, MotivoHc.Tipo.OTRO -> 5
            }
        override val texto: String get() = motivo.texto
    }

    /** RF-9: varias fichas posibles; mientras no elija, sin casillas. */
    data class ElegirFicha(val candidatas: List<HcPacienteCandidato>) : MotivoEnfermedades {
        override val prioridad: Int get() = 6
        override val texto: String get() = "Elegí la ficha del paciente"
    }

    /** RF-10 (PD2): ninguna ficha posible, con el texto de la 002 que dice qué hacer. */
    data class SinFicha(override val texto: String) : MotivoEnfermedades {
        override val prioridad: Int get() = 7
    }

    /** RF-12: `404 paciente_no_encontrado`. */
    data object FichaNoDisponible : MotivoEnfermedades {
        override val prioridad: Int get() = 8
        override val texto: String get() = "La ficha de este paciente no está disponible en la web"
    }

    /** RF-11: `403 fuera_de_cartera`. */
    data object FueraDeCartera : MotivoEnfermedades {
        override val prioridad: Int get() = 9
        override val texto: String get() = "Este paciente no está en tu cartera"
    }
}

/** El motivo que deja el vínculo con la ficha de la web, o null si quedó vinculado (RF-9, RF-10). */
fun motivoDeVinculo(resultado: ResultadoVinculo, nombreHc: String): MotivoEnfermedades? = when (resultado) {
    is ResultadoVinculo.Vinculado -> null
    is ResultadoVinculo.ElegirFicha -> MotivoEnfermedades.ElegirFicha(resultado.candidatas)
    is ResultadoVinculo.DocumentoInvalido -> MotivoEnfermedades.SinFicha(resultado.mensaje)
    is ResultadoVinculo.FichaDeOtroPaciente -> MotivoEnfermedades.SinFicha(resultado.mensaje)
    ResultadoVinculo.FichaNoElegible -> MotivoEnfermedades.SinFicha(TEXTO_FICHA_NO_ELEGIBLE)
    is ResultadoVinculo.NoSePudo -> motivoDeError(resultado.error, nombreHc)
}

/**
 * El motivo de un error de la API de enfermedades: los dos propios de la sección por su código (RF-11, RF-12) y el
 * resto como en la HC general ([motivoHc], RF-13).
 */
fun motivoDeError(e: TobbException, nombreHc: String): MotivoEnfermedades = when (e.codigo) {
    "paciente_no_encontrado" -> MotivoEnfermedades.FichaNoDisponible
    "fuera_de_cartera" -> MotivoEnfermedades.FueraDeCartera
    else -> MotivoEnfermedades.General(motivoHc(nombreHc, e))
}

/** El único que se muestra cuando aplica más de uno (RF-14); a igual lugar, el primero de la lista. */
fun primerMotivo(motivos: List<MotivoEnfermedades>): MotivoEnfermedades? = motivos.minByOrNull { it.prioridad }

/**
 * Si con este motivo se muestra la copia del dispositivo en solo lectura: solo el lugar 5 (sin conexión, o un error no
 * previsto con el texto de la web, RF-30, RF-31). Con los demás no se muestran enfermedades (RF-9 a RF-13).
 */
fun muestraCopia(motivo: MotivoEnfermedades): Boolean = motivo.prioridad == 5
