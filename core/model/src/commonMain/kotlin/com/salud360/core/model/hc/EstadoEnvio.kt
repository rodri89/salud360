package com.salud360.core.model.hc

/**
 * En qué está el envío a la web de una parte de una consulta propia de la HC general (spec 005, RF-6, RF-30). Es un dato
 * **local**: lo decide este dispositivo según lo que le contestó la web, y nunca se envía a ningún lado. Los textos para
 * mostrar los arma `core/data` (`textoDelEstado`); el `motivo` ya viene en palabras del usuario y nombrando la HC.
 */
sealed interface EstadoEnvio {
    /** Hay algo para enviar y todavía no salió (o está en camino): «Falta enviar». Es la ausencia de un estado guardado. */
    data object Pendiente : EstadoEnvio

    /**
     * No se pudo enviar por una causa **ajena al dato**: «En espera: <motivo>» (RF-6). No es un rechazo: se vuelve a intentar
     * sola cuando la app comprueba de nuevo la causa, y a mano.
     */
    data class EnEspera(val causa: CausaEnEspera, val motivo: String) : EstadoEnvio

    /**
     * La web rechazó el cambio por una **causa del dato** que no se arregla reintentando: «No se pudo enviar: <motivo>»
     * (RF-30). No se reintenta solo; el médico lo reintenta a mano o lo descarta.
     */
    data class NoSePudoEnviar(val causa: CausaDeRechazo, val motivo: String) : EstadoEnvio

    /** El médico decidió no enviarlo: queda solo en el dispositivo, «Descartado: no se envió a la web» (RF-30, D19(d)). */
    data object Descartado : EstadoEnvio
}

/** Por qué un envío está «En espera» (RF-6). */
enum class CausaEnEspera {
    /** `401 sin_token|token`: la sesión venció (también a mitad de un envío). */
    SESION_VENCIDA,

    /** `403 hc_no_habilitada`. */
    HC_NO_HABILITADA,

    /** `403 medico_no_vinculado`. */
    MEDICO_NO_VINCULADO,

    /** `403 licencia_vencida`. */
    LICENCIA_VENCIDA,

    /** `403 sin_permiso`: quien envía no es médico de esa HC (RF-52). */
    SIN_PERMISO,

    /** `403 fuera_de_cartera`. */
    FUERA_DE_CARTERA,

    /** `404 paciente_no_encontrado`: la ficha no existe o está dada de baja; una ficha se puede reactivar (RF-19). */
    PACIENTE_DE_BAJA,

    /** Sin red. */
    SIN_CONEXION,

    /** `429`: demasiados pedidos por minuto. */
    DEMASIADOS_PEDIDOS,

    /** La web no responde bien: `5xx`, `503 tobb_caido` o una respuesta que no se puede leer. */
    SERVIDOR,
}

/** Por qué la web rechazó un cambio (RF-30). */
enum class CausaDeRechazo {
    /** `422 datos_invalidos`: datos que la web no acepta. */
    DATOS_INVALIDOS,

    /** `422 datos_invalidos` con motivo `clave_en_uso`: el identificador del envío ya se usó para otra operación (un error de la app, no del médico). */
    CLAVE_EN_USO,

    /** `404 consulta_no_encontrada`: la consulta ya no existe o está dada de baja en la web (RF-19, RF-33). */
    CONSULTA_DE_BAJA,

    /** `409 consulta_cerrada`: la consulta ya está cerrada o dada de baja. */
    CONSULTA_CERRADA,

    /** `403 consulta_no_propia`: la consulta no es de este médico o no la creó la app. */
    CONSULTA_NO_PROPIA,

    /** Cualquier otro rechazo. */
    OTRO,
}
