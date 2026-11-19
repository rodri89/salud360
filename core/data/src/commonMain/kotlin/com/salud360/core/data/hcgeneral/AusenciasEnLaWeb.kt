package com.salud360.core.data.hcgeneral

import com.salud360.core.model.Id

// Qué cosa es cada marca «Ya no figura en la web» (RF-63, Q15) para que la lista, la consulta y sus secciones sepan a qué
// ítem ponerle la etiqueta. Las marcas son las que escribe `marcarAusentes` (E6.2) y lee `HcClinicaBackend.marcasAusentes`
// (clave de ausencia → fecha). Acá solo se consulta: nada se oculta, se tacha ni se borra.

/** Lo que una clave de ausencia señala. */
sealed interface ItemAusente {
    /** Una consulta de la web (`hcc-c7003`). */
    data class Consulta(val id: Id) : ItemAusente

    /** Un campo de una sección de una consulta, o del examen físico (`hcc-c7003:habitos:texto`). */
    data class Contenido(val consultaId: Id, val seccion: String, val campo: String) : ItemAusente

    /** Una carga de laboratorio o de peso (`hcc-llaboratorio_general-703`); [tipo] es el del registro de la app. */
    data class Carga(val id: Id, val tipo: String) : ItemAusente

    /** Un estudio, examen complementario o interconsulta (`hcc-rveda-1797`); [tipo] es el del registro de la app. */
    data class Fila(val id: Id, val tipo: String) : ItemAusente

    /** El pendiente de la web (`hcc-p1744`). */
    data class PendienteDeLaWeb(val id: Id) : ItemAusente

    /** Una casilla o nota de antecedentes familiares (`hcc-a5001-hta`). */
    data class Antecedente(val id: Id, val clave: String) : ItemAusente
}

private val CLAVE_CONSULTA = Regex("${PREFIJO_ID_WEB}c\\d+")
private val CLAVE_CONTENIDO = Regex("(${PREFIJO_ID_WEB}c\\d+):([^:]+):([^:]+)")
private val CLAVE_CARGA = Regex("${PREFIJO_ID_WEB}l([a-z_]+)-\\d+")
private val CLAVE_FILA = Regex("${PREFIJO_ID_WEB}r([a-z_]+)-\\d+")
private val CLAVE_PENDIENTE = Regex("${PREFIJO_ID_WEB}p\\d+")
private val CLAVE_ANTECEDENTE = Regex("${PREFIJO_ID_WEB}a\\d+-(.+)")

/** Qué señala [clave] (de ausencia); `null` si no es una de las formas que fija `marcarAusentes`. */
fun itemAusente(clave: String): ItemAusente? {
    CLAVE_CONSULTA.matchEntire(clave)?.let { return ItemAusente.Consulta(clave) }
    CLAVE_CONTENIDO.matchEntire(clave)?.let { return ItemAusente.Contenido(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
    CLAVE_CARGA.matchEntire(clave)?.let { return ItemAusente.Carga(clave, it.groupValues[1]) }
    CLAVE_FILA.matchEntire(clave)?.let { return ItemAusente.Fila(clave, it.groupValues[1]) }
    CLAVE_PENDIENTE.matchEntire(clave)?.let { return ItemAusente.PendienteDeLaWeb(clave) }
    CLAVE_ANTECEDENTE.matchEntire(clave)?.let { return ItemAusente.Antecedente(clave, it.groupValues[1]) }
    return null
}

/**
 * Las marcas «Ya no figura en la web» de un paciente: clave de ausencia → fecha (`AAAA-MM-DD`) en que se notó. Las
 * preguntas reciben el **id local** de lo que la pantalla muestra (el de la consulta, de la carga, de la fila, del
 * pendiente o del antecedente) y contestan si lleva la marca; la pantalla le pone la etiqueta [YA_NO_FIGURA_EN_LA_WEB]
 * y sigue mostrando todo como estaba.
 */
data class AusenciasEnLaWeb(val marcas: Map<String, String> = emptyMap()) {
    private val clasificadas: List<ItemAusente> by lazy { marcas.keys.sorted().mapNotNull { itemAusente(it) } }

    /** La fecha en que se notó la ausencia de [clave], o `null` si no tiene marca. */
    fun desdeCuando(clave: String): String? = marcas[clave]

    /** La consulta [consultaId] ya no figura en la web. */
    fun consulta(consultaId: Id): Boolean = ItemAusente.Consulta(consultaId) in clasificadas

    /**
     * La consulta **propia** ya enviada cuyo id en la web es [remotoId] ya no figura en la web (spec 005, RF-33). La marca lleva la clave de
     * la consulta de la web (`hcc-c<remoto_id>`) aunque la fila local tenga otro id.
     */
    fun consultaPropia(remotoId: String): Boolean = remotoId.toLongOrNull()?.takeIf { it > 0 }?.let { consulta(idConsultaWeb(it)) } == true
    /** El campo [campo] de la sección [seccion] de la consulta (también del examen físico) ya no figura en la web. */
    fun contenido(consultaId: Id, seccion: String, campo: String): Boolean = claveDeCampo(consultaId, seccion, campo) in marcas

    /** Algún campo de la sección [seccion] de la consulta ya no figura en la web. */
    fun seccion(consultaId: Id, seccion: String): Boolean = campos(consultaId, seccion).isNotEmpty()

    /** Los campos de la sección [seccion] de la consulta que ya no figuran en la web. */
    fun campos(consultaId: Id, seccion: String): Set<String> =
        contenidos(consultaId).filter { it.seccion == seccion }.mapTo(linkedSetOf()) { it.campo }

    /** Las secciones de la consulta con algún campo que ya no figura en la web. */
    fun secciones(consultaId: Id): Set<String> = contenidos(consultaId).mapTo(linkedSetOf()) { it.seccion }

    /** La carga de laboratorio o de peso [cargaId] ya no figura en la web. */
    fun carga(cargaId: Id): Boolean = clasificadas.any { it is ItemAusente.Carga && it.id == cargaId }

    /** El estudio, examen complementario o interconsulta [filaId] ya no figura en la web. */
    fun fila(filaId: Id): Boolean = clasificadas.any { it is ItemAusente.Fila && it.id == filaId }

    /** El pendiente [pendienteId] ya no figura en la web. */
    fun pendiente(pendienteId: Id): Boolean = clasificadas.any { it is ItemAusente.PendienteDeLaWeb && it.id == pendienteId }

    /** El antecedente familiar [antecedenteId] ya no figura en la web. */
    fun antecedente(antecedenteId: Id): Boolean = clasificadas.any { it is ItemAusente.Antecedente && it.id == antecedenteId }

    /**
     * El antecedente familiar de la casilla o nota [clave] ya no figura en la web. Se busca por la clave porque la fila local
     * puede tener otro id que el `hcc-a<ficha>-<clave>` de la marca (la marca es del paciente de esta pantalla).
     */
    fun antecedenteDeClave(clave: String): Boolean = clasificadas.any { it is ItemAusente.Antecedente && it.clave == clave }

    /** Todas las marcas reconocidas, en el orden de sus claves; las que no se reconocen quedan afuera (siguen en [marcas]). */
    fun items(): List<ItemAusente> = clasificadas

    private fun contenidos(consultaId: Id) = clasificadas.filterIsInstance<ItemAusente.Contenido>().filter { it.consultaId == consultaId }

    companion object {
        val NINGUNA = AusenciasEnLaWeb()
    }
}
