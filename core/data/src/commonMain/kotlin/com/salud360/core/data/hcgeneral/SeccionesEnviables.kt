package com.salud360.core.data.hcgeneral

import com.salud360.core.model.especialidad.CampoExamenFisico
import com.salud360.core.model.especialidad.SeccionesComunes
import com.salud360.core.model.hc.Antecedente

// Qué secciones de la HC general se ofrecen para cargar y se envían a la web (spec 005, RF-3, RF-4, D9). Puro: ids de sección,
// casillas y campos del examen; la pantalla muestra lo que esto decide y no decide nada (constitución, punto 4). Solo vale para
// la HC general: las demás historias clínicas no pasan por acá. Esto **no escribe ni borra nada**: lo que ya está cargado en el
// dispositivo se conserva y se lee (constitución, punto 3); solo cambia qué se ofrece y qué puede viajar.
//
// Dos razones distintas para no enviar algo, con textos distintos (spec, tabla de textos):
// - [EnvioDeSeccion.NUNCA]: la web no lo guarda (D9). «No se envía a la web».
// - [EnvioDeSeccion.TODAVIA_NO]: la web lo guarda pero esta versión de la app aún no lo envía (RF-4). «Esta sección todavía no
//   se envía a la web». Cada subentrega (E2 a E4) tiene sus secciones en [SUBENTREGA_QUE_HABILITA]; cuáles envía esta versión lo dice [SECCIONES_HABILITADAS].

/** Las subentregas de la escritura que habilitan secciones, en orden (la E0 no envía nada y la E5 es la licencia). */
enum class SubentregaDeEscritura { E1, E2, E3, E4 }

/**
 * La subentrega que **siembra** [SECCIONES_HABILITADAS]: ya no es el candado real (lo es el conjunto). Sigue en [SubentregaDeEscritura.E1] porque esta versión
 * habilita de E1 y solo una parte de E2 (decisión del usuario, T67 reducida): cambiarla encendería todo lo de una subentrega de golpe.
 */
val SUBENTREGA_HABILITADA = SubentregaDeEscritura.E1

enum class EnvioDeSeccion {
    /** Se ofrece para cargar en una consulta propia y viaja a la web. */
    SE_ENVIA,

    /** La web la guarda pero esta versión aún no la envía: no se ofrece (RF-4). Lo ya cargado se conserva. */
    TODAVIA_NO,

    /** La web no la guarda (D9): no se ofrece ni se envía nunca (RF-3). Lo ya cargado se conserva, solo para leer. */
    NUNCA,

    /** No viaja con la consulta: tiene su propia API (spec 003, `enfermedades`) y se ofrece siempre. */
    APARTE,
}

/** RF-3: lo que la web no guarda y el dispositivo tiene cargado. */
const val AVISO_NO_SE_ENVIA = "No se envía a la web"

/** RF-4: una sección que la web guarda y esta versión aún no envía. */
const val AVISO_TODAVIA_NO_SE_ENVIA = "Esta sección todavía no se envía a la web"

/** Secciones del catálogo viejo que la web no guarda (D9): diagnósticos, audio y antecedentes personales (sección legada). */
val SECCIONES_QUE_LA_WEB_NO_GUARDA = setOf(SeccionesComunes.DIAGNOSTICO, SeccionesComunes.AUDIO, SeccionesComunes.ANTECEDENTES_PERSONALES)

/** Casillas de los antecedentes familiares que la web no guarda (D9): el tabaquismo. */
val CASILLAS_QUE_LA_WEB_NO_GUARDA = setOf("tabaquismo")

/** Campos del examen físico que la web no guarda (D9): frecuencia cardíaca y circunferencia abdominal. */
val CAMPOS_DEL_EXAMEN_QUE_LA_WEB_NO_GUARDA = setOf(CampoExamenFisico.FRECUENCIA_CARDIACA, CampoExamenFisico.CIRCUNFERENCIA_ABDOMINAL)

/** La categoría de los antecedentes del catálogo viejo («Antecedentes personales») que la web no guarda. */
private const val CATEGORIA_PERSONALES = "personales"

/** Qué subentrega habilita el envío de cada sección (spec, «Subentregas»). Lo que no figura acá no se envía en ninguna. */
private val SUBENTREGA_QUE_HABILITA: Map<String, SubentregaDeEscritura> = buildMap {
    // E1: los textos (RF-20) y el examen físico (RF-21).
    listOf(
        SeccionesComunes.MOTIVO_CONSULTA, SeccionesComunes.DATOS_SUBJETIVOS, SeccionesComunes.DATOS_OBJETIVOS, SeccionesComunes.CONDUCTAS,
        SeccionesComunes.NOTA, "habitos", "diuresis_catarsis", "somnia", SeccionesComunes.EXAMEN_FISICO,
    ).forEach { put(it, SubentregaDeEscritura.E1) }
    // E2: formularios, antecedentes familiares, los cinco estudios, exámenes complementarios e interconsultas.
    listOf(
        "alimentacion", "serologias", "child_pugh", SeccionesComunes.ANTECEDENTES_FAMILIARES, "eco_abdominal", "veda", "biopsia_hepatica",
        "estudios_cardiologicos", "tc_rmn", SeccionesComunes.EXAMENES_COMPLEMENTARIOS, SeccionesComunes.INTERCONSULTA,
    ).forEach { put(it, SubentregaDeEscritura.E2) }
    // E3: laboratorio general y reducido, peso y pendientes.
    listOf("laboratorio_general", "laboratorio_reducido", "tabla_peso", SeccionesComunes.PENDIENTES).forEach { put(it, SubentregaDeEscritura.E3) }
    // E4: adjuntos (PDF).
    put("documentos", SubentregaDeEscritura.E4)
}

/** Secciones con su propia API: se ofrecen siempre y no dependen del envío de la consulta. */
private val SECCIONES_APARTE = setOf("enfermedades")

/**
 * Todas las secciones que habilita [subentrega] **y las de las anteriores** (el mismo espíritu acumulativo de siempre: E2 ofrece lo de E1 y lo suyo).
 * Solo las que la 005 envía: no incluye lo que la web no guarda ([SECCIONES_QUE_LA_WEB_NO_GUARDA]) ni lo que tiene su propia API ([SECCIONES_APARTE]).
 */
fun seccionesDe(subentrega: SubentregaDeEscritura): Set<String> = SUBENTREGA_QUE_HABILITA.filterValues { it <= subentrega }.keys.toSet()

/**
 * **El candado del envío (RF-4): el conjunto de secciones que esta versión de la app envía a la web.** **Es lo único que se cambia para habilitar una
 * sección**: la pantalla, el plan de envío y el contador lo siguen solos.
 *
 * Hoy son las nueve de [SUBENTREGA_HABILITADA] (E1: los ocho textos y el examen físico) **más una parte de E2, por decisión del usuario del 2026-11-11
 * (T67 reducida, autorizada antes del cierre de E1)**: serologías, Child-Pugh, antecedentes familiares y los cinco estudios; **más la E3 reducida, por
 * decisión del usuario del 2026-11-12 (T88 reducida, también antes del cierre de E1)**: el laboratorio general (`laboratorio_general`) y el peso
 * (`tabla_peso`). Quedan en «todavía no» la alimentación, los exámenes complementarios y las interconsultas (de E2), el laboratorio reducido y los
 * pendientes (de E3, fuera de alcance: la API de E3 solo escribe `laboratorio_generals` y `pesos`) y los adjuntos (E4); lo que la web no guarda
 * ([SECCIONES_QUE_LA_WEB_NO_GUARDA]) y lo que tiene su propia API ([SECCIONES_APARTE]) siguen igual. Con todo habilitado se escribe `seccionesDe(E4)`.
 *
 * Va declarado **después** de [SUBENTREGA_QUE_HABILITA]: los `val` de nivel superior se inicializan en el orden del archivo.
 */
val SECCIONES_HABILITADAS: Set<String> = seccionesDe(SUBENTREGA_HABILITADA) + setOf(
    "serologias", "child_pugh", SeccionesComunes.ANTECEDENTES_FAMILIARES,
    "eco_abdominal", "veda", "biopsia_hepatica", "estudios_cardiologicos", "tc_rmn",
    // T88 reducida: la E3 que la API de la web ya escribe (laboratorio general y peso); el reducido y los pendientes siguen en «todavía no».
    "laboratorio_general", "tabla_peso",
)

/**
 * Qué pasa con [seccion] en una versión que envía las secciones de [habilitadas]. Lo que la web no guarda ([EnvioDeSeccion.NUNCA]) y lo que tiene su
 * propia API ([EnvioDeSeccion.APARTE]) mandan sobre el conjunto. Una sección que no se conoce (o que ninguna subentrega de la 005 habilita, como la
 * historia digitalizada) cuenta como [EnvioDeSeccion.TODAVIA_NO]: ante la duda no se ofrece, aunque alguien la ponga en [habilitadas].
 */
fun envioDeSeccion(seccion: String, habilitadas: Set<String> = SECCIONES_HABILITADAS): EnvioDeSeccion = when {
    seccion in SECCIONES_QUE_LA_WEB_NO_GUARDA -> EnvioDeSeccion.NUNCA
    seccion in SECCIONES_APARTE -> EnvioDeSeccion.APARTE
    else -> if (seccion in habilitadas && seccion in SUBENTREGA_QUE_HABILITA) EnvioDeSeccion.SE_ENVIA else EnvioDeSeccion.TODAVIA_NO
}

/** RF-3 y RF-4: la sección se ofrece para cargar en una consulta nueva de la HC general. */
fun seccionSeOfrece(seccion: String, habilitadas: Set<String> = SECCIONES_HABILITADAS): Boolean =
    envioDeSeccion(seccion, habilitadas).let { it == EnvioDeSeccion.SE_ENVIA || it == EnvioDeSeccion.APARTE }

/** El aviso de [seccion] cuando no se ofrece, o `null` si se ofrece. Lo ya cargado se conserva y se lee con este aviso. */
fun avisoDeSeccion(seccion: String, habilitadas: Set<String> = SECCIONES_HABILITADAS): String? =
    when (envioDeSeccion(seccion, habilitadas)) {
        EnvioDeSeccion.NUNCA -> AVISO_NO_SE_ENVIA
        EnvioDeSeccion.TODAVIA_NO -> AVISO_TODAVIA_NO_SE_ENVIA
        EnvioDeSeccion.SE_ENVIA, EnvioDeSeccion.APARTE -> null
    }

/** De [ids] (por ejemplo, las secciones de un perfil), las que se ofrecen en esta versión, en el mismo orden. */
fun seccionesQueSeOfrecen(ids: List<String>, habilitadas: Set<String> = SECCIONES_HABILITADAS): List<String> =
    ids.filter { seccionSeOfrece(it, habilitadas) }
/**
 * La casilla [clave] de los antecedentes de [categoria] puede viajar a la web: todo `personales` (la sección legada de antecedentes
 * personales) y el tabaquismo, no. El resto de los antecedentes familiares y la nota que los acompaña, sí (RF-3, RF-36). Que la
 * sección se envíe o no en esta versión lo dice [envioDeSeccion] (E2); esto es solo lo que la web puede guardar.
 */
fun casillaSeEnvia(categoria: String, clave: String): Boolean =
    categoria != CATEGORIA_PERSONALES && !(categoria == "familiares" && clave in CASILLAS_QUE_LA_WEB_NO_GUARDA)

/** El campo del examen físico puede viajar: peso, talla, IMC, tensión arterial y nota (RF-21). */
fun campoDelExamenSeEnvia(campo: CampoExamenFisico): Boolean = campo in CAMPOS_QUE_VIAJAN_DEL_EXAMEN

private val CAMPOS_QUE_VIAJAN_DEL_EXAMEN = setOf(
    CampoExamenFisico.PESO, CampoExamenFisico.TALLA, CampoExamenFisico.IMC, CampoExamenFisico.TENSION_ARTERIAL, CampoExamenFisico.NOTA,
)

/** «No se envía a la web» si [antecedente] es de los que la web no guarda y ya estaba cargado; `null` si puede viajar. */
fun avisoDeAntecedente(antecedente: Antecedente): String? =
    AVISO_NO_SE_ENVIA.takeUnless { casillaSeEnvia(antecedente.categoria, antecedente.clave) }

/** «No se envía a la web» si [campo] del examen es de los que la web no guarda; `null` si puede viajar. */
fun avisoDeCampoDelExamen(campo: CampoExamenFisico): String? = AVISO_NO_SE_ENVIA.takeUnless { campoDelExamenSeEnvia(campo) }

// Lo que dice la pantalla de una consulta (T37). [seEnvia] = la consulta se envía a la web (`Consulta.seEnviaALaWeb`): solo ahí valen estos avisos;
// el histórico, lo de la web, pediatría y las demás historias se ven como siempre.

/** El aviso que lleva la sección [seccion] en una consulta que se envía: «No se envía a la web» (RF-3) o «Esta sección todavía no se envía a la web» (RF-4); `null` si se ofrece. */
fun avisoDeEnvioDeLaSeccion(seccion: String, seEnvia: Boolean): String? = if (seEnvia) avisoDeSeccion(seccion) else null

/** La casilla que la web no guarda (el tabaquismo, [sinEnvio]) se ve, solo para leer, con «No se envía a la web». */
fun avisoDeEnvioDeLaCasilla(sinEnvio: Boolean, seEnvia: Boolean): String? = AVISO_NO_SE_ENVIA.takeIf { seEnvia && sinEnvio }

/** El campo del examen físico que la web no guarda (frecuencia cardíaca, circunferencia abdominal) se ve, solo para leer, con «No se envía a la web». */
fun avisoDeEnvioDelCampoDelExamen(campo: CampoExamenFisico, seEnvia: Boolean): String? = if (seEnvia) avisoDeCampoDelExamen(campo) else null

/** De [antecedentes], los que la web guarda y por lo tanto pueden viajar. No borra ni cambia los demás: siguen en el dispositivo. */
fun antecedentesQueSePuedenEnviar(antecedentes: List<Antecedente>): List<Antecedente> =
    antecedentes.filter { casillaSeEnvia(it.categoria, it.clave) }
