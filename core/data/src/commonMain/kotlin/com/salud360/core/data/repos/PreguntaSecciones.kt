package com.salud360.core.data.repos

/**
 * ¿El médico ya configuró qué secciones ve en una historia clínica? Lo dice la existencia de la preferencia
 * ([clave]) y no su valor: «todas visibles» se guarda como valor vacío y también cuenta como elegido.
 */
fun hayConfigDeSecciones(preferencias: Map<String, String>, clave: String): Boolean = clave in preferencias

/**
 * Si al abrir una consulta hay que preguntar qué secciones ver (modal de la primera vez). Solo en consultas abiertas, solo
 * si el médico nunca configuró esa historia clínica y solo si hay secciones entre las cuales elegir.
 */
fun debePreguntarSecciones(configGuardada: Boolean, soloLectura: Boolean, haySeccionesParaElegir: Boolean): Boolean =
    !configGuardada && !soloLectura && haySeccionesParaElegir
