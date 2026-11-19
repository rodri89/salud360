package com.salud360.core.data.hcgeneral

import com.salud360.core.model.especialidad.CodigosHc
import com.salud360.core.model.hc.Consulta

// A quién avisa el autoguardado de una consulta cuando termina de guardar un cambio en el dispositivo (spec 005, T36, RF-2, RF-27, RF-62).
// Puro: la pantalla de consulta no decide nada. En ningún caso es un pedido a la web: avisar solo programa un envío para dentro de 4 s.

/** Qué motor de envío se entera de que la consulta cambió. */
enum class AvisoDeCambios {
    /** Nadie: una consulta de la web solo se lee y nada sale sobre ella ni sobre sus filas (RF-2, D1). */
    NINGUNO,

    /** El envío diferido de la especialidad (`HcApiSync`, hoy pediatría), como siempre. */
    MOTOR_DE_LA_ESPECIALIDAD,

    /** La cola de la HC general (`HcClinicaEnvio.marcarSucia`). Ella decide qué entra: solo lo propio del médico de la sesión (RF-7, RF-50). */
    COLA_DE_LA_HC_GENERAL,
}

/**
 * A quién avisa el autoguardado de [consulta] ([deLaWeb]: la lee la web y no se escribe, `HcRepository.esConsultaDeLaWeb`). La HC general
 * avisa a su cola, también las del dispositivo anteriores a la spec: la cola ignora el histórico (nada sale solo, D2) pero la decisión es
 * suya, no de la pantalla. Las demás historias siguen con su motor.
 */
fun avisoDeCambios(consulta: Consulta, deLaWeb: Boolean): AvisoDeCambios = when {
    deLaWeb -> AvisoDeCambios.NINGUNO
    consulta.especialidad == CodigosHc.GENERAL -> AvisoDeCambios.COLA_DE_LA_HC_GENERAL
    else -> AvisoDeCambios.MOTOR_DE_LA_ESPECIALIDAD
}