package com.salud360.core.data.hcgeneral

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Qué cosa es cada marca «Ya no figura en la web» (RF-63) para que la lista, la consulta y sus secciones sepan a qué ítem
 * ponerle la etiqueta. Las claves son las que fija `marcarAusentes` (E6.2); acá nada se borra ni se oculta: solo se consulta.
 */
class AusenciasEnLaWebTest {
    private val consulta = idConsultaWeb(7003)
    private val carga = idCargaWeb(TIPO_LABORATORIO_GENERAL, 703)
    private val peso = idCargaWeb(TIPO_PESO, 25)
    private val estudio = idFilaWeb("veda", 1797)
    private val interconsulta = idFilaWeb(TIPO_INTERCONSULTA, 22)
    private val pendiente = idPendienteWeb(1744)
    private val antecedente = idAntecedenteWeb(5001, "hta")
    private val nota = idAntecedenteWeb(5001, CLAVE_NOTA)

    private val marcas = mapOf(
        consulta to "2026-10-20",
        claveDeCampo(consulta, "habitos", "texto") to "2026-10-21",
        claveDeCampo(consulta, "examen_fisico", "peso") to "2026-10-21",
        claveDeCampo(consulta, "examen_fisico", "talla") to "2026-10-21",
        claveDeCampo(idConsultaWeb(7004), "somnia", "texto") to "2026-10-22",
        carga to "2026-10-23",
        peso to "2026-10-23",
        estudio to "2026-10-24",
        interconsulta to "2026-10-24",
        pendiente to "2026-10-25",
        antecedente to "2026-10-26",
        nota to "2026-10-26",
    )
    private val ausencias = AusenciasEnLaWeb(marcas)

    @Test
    fun cada_clave_se_reconoce_como_lo_que_es() {
        assertEquals(ItemAusente.Consulta(consulta), itemAusente(consulta))
        assertEquals(ItemAusente.Contenido(consulta, "habitos", "texto"), itemAusente("hcc-c7003:habitos:texto"))
        assertEquals(ItemAusente.Contenido(consulta, "examen_fisico", "peso"), itemAusente(claveDeCampo(consulta, "examen_fisico", "peso")))
        assertEquals(ItemAusente.Carga(carga, TIPO_LABORATORIO_GENERAL), itemAusente(carga))
        assertEquals(ItemAusente.Carga(peso, TIPO_PESO), itemAusente(peso))
        assertEquals(ItemAusente.Fila(estudio, "veda"), itemAusente(estudio))
        assertEquals(ItemAusente.Fila(interconsulta, TIPO_INTERCONSULTA), itemAusente(interconsulta))
        assertEquals(ItemAusente.PendienteDeLaWeb(pendiente), itemAusente(pendiente))
        assertEquals(ItemAusente.Antecedente(antecedente, "hta"), itemAusente(antecedente))
        assertEquals(ItemAusente.Antecedente(nota, CLAVE_NOTA), itemAusente(nota))
        assertEquals(
            ItemAusente.Antecedente(idAntecedenteWeb(5001, "dependiente_terceros"), "dependiente_terceros"),
            itemAusente("hcc-a5001-dependiente_terceros"),
        )
    }

    @Test
    fun una_clave_que_no_es_de_una_marca_conocida_no_es_nada() {
        listOf("", "hcc-", "hcc-c", "hcc-cabc", "hcc-c7003:habitos", "hcc-c7003::texto", "hcc-m14", "hcp-c12", "hcc-p", "hcc-l-1", "hcc-a5001", "xyz")
            .forEach { assertNull(itemAusente(it), "«$it» no es una marca") }
    }

    @Test
    fun la_consulta_marcada_y_desde_cuando() {
        assertTrue(ausencias.consulta(consulta))
        assertEquals("2026-10-20", ausencias.desdeCuando(consulta))
        assertFalse(ausencias.consulta(idConsultaWeb(7999)), "una consulta sin marca no la tiene")
        assertNull(ausencias.desdeCuando(idConsultaWeb(7999)))
        // Las marcas de su contenido no son la marca de la consulta.
        assertFalse(ausencias.consulta(idConsultaWeb(7004)))
    }

    @Test
    fun el_contenido_marcado_de_una_consulta_por_seccion_y_por_campo() {
        assertTrue(ausencias.seccion(consulta, "habitos"))
        assertTrue(ausencias.contenido(consulta, "habitos", "texto"))
        assertTrue(ausencias.seccion(consulta, "examen_fisico"))
        assertEquals(setOf("peso", "talla"), ausencias.campos(consulta, "examen_fisico"))
        assertFalse(ausencias.contenido(consulta, "examen_fisico", "imc"))
        assertFalse(ausencias.seccion(consulta, "somnia"), "la sección marcada es de otra consulta")
        assertEquals(setOf("habitos", "examen_fisico"), ausencias.secciones(consulta))
        assertEquals(setOf("somnia"), ausencias.secciones(idConsultaWeb(7004)))
        assertEquals(emptySet(), ausencias.secciones(idConsultaWeb(7999)))
    }

    @Test
    fun cargas_estudios_pendientes_y_antecedentes_marcados() {
        assertTrue(ausencias.carga(carga))
        assertTrue(ausencias.carga(peso))
        assertFalse(ausencias.carga(idCargaWeb(TIPO_LABORATORIO_GENERAL, 704)))
        assertTrue(ausencias.fila(estudio))
        assertTrue(ausencias.fila(interconsulta))
        assertFalse(ausencias.fila(idFilaWeb("biopsia_hepatica", 1797)), "el mismo número en otro tipo es otra fila")
        assertTrue(ausencias.pendiente(pendiente))
        assertFalse(ausencias.pendiente(idPendienteWeb(1)))
        assertTrue(ausencias.antecedente(antecedente))
        assertTrue(ausencias.antecedente(nota))
        assertFalse(ausencias.antecedente(idAntecedenteWeb(5001, "dbt_ir")))
    }

    @Test
    fun una_marca_de_un_tipo_no_se_confunde_con_otro_ni_con_un_id_del_dispositivo() {
        assertFalse(ausencias.carga(estudio))
        assertFalse(ausencias.fila(carga))
        assertFalse(ausencias.pendiente(consulta))
        assertFalse(ausencias.consulta("3f2c9d1e-0a6b-4c11-9a77-5c1d2e3f4a5b"), "una consulta del dispositivo nunca lleva la marca")
        assertFalse(ausencias.antecedente("3f2c9d1e-0a6b-4c11-9a77-5c1d2e3f4a5b"))
    }

    @Test
    fun todos_los_items_marcados_quedan_clasificados() {
        val items = ausencias.items()
        assertEquals(marcas.size, items.size, "ninguna marca de las de arriba se pierde")
        assertEquals(1, items.count { it is ItemAusente.Consulta })
        assertEquals(1, items.count { it is ItemAusente.PendienteDeLaWeb })
        assertEquals(4, items.count { it is ItemAusente.Contenido })
        assertEquals(2, items.count { it is ItemAusente.Carga })
        assertEquals(2, items.count { it is ItemAusente.Fila })
        assertEquals(2, items.count { it is ItemAusente.Antecedente })
    }

    @Test
    fun una_marca_con_clave_rara_no_rompe_y_queda_afuera_de_los_items() {
        val a = AusenciasEnLaWeb(mapOf(consulta to "2026-10-20", "cosa-rara" to "2026-10-20"))
        assertEquals(listOf<ItemAusente>(ItemAusente.Consulta(consulta)), a.items())
        assertEquals("2026-10-20", a.desdeCuando("cosa-rara"), "la fecha de cualquier clave se puede consultar")
    }

    @Test
    fun sin_marcas_nada_figura_como_ausente() {
        val sin = AusenciasEnLaWeb.NINGUNA
        assertFalse(sin.consulta(consulta))
        assertFalse(sin.seccion(consulta, "habitos"))
        assertFalse(sin.carga(carga))
        assertFalse(sin.pendiente(pendiente))
        assertEquals(emptyList(), sin.items())
    }
}
