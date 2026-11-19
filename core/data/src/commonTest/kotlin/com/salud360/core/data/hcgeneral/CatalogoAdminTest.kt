package com.salud360.core.data.hcgeneral

import com.salud360.core.model.especialidad.CatalogoHc
import com.salud360.core.model.especialidad.EspecialidadDefinition
import com.salud360.core.model.especialidad.PerfilHc
import com.salud360.core.model.especialidad.PestaniaPerfil
import com.salud360.core.model.especialidad.SeccionCatalogo
import com.salud360.core.model.especialidad.SeccionCatalogoRemota
import com.salud360.core.model.especialidad.SeccionDef
import com.salud360.core.model.especialidad.SeccionPerfil
import com.salud360.core.model.especialidad.TipoConsultaHc.CONSULTA
import com.salud360.core.model.especialidad.TipoSeccion
import kotlin.test.Test
import kotlin.test.assertEquals

/** Catálogo que ve el administrador (RF-1) y las secciones que esta versión no sabe mostrar (RF-16). */
class CatalogoAdminTest {
    // La app conoce tres secciones, en un orden distinto al del servidor, y una legada que no se ofrece.
    private val catalogoApp = CatalogoHc(
        base = EspecialidadDefinition("clinica", "Clínica", "hclinica", emptyList(), emptyList()),
        secciones = listOf(
            SeccionCatalogo(SeccionDef("examen_fisico", "Examen físico", TipoSeccion.EXAMEN_FISICO)),
            SeccionCatalogo(SeccionDef("motivo_consulta", "Motivo de consulta", TipoSeccion.TEXTO)),
            SeccionCatalogo(SeccionDef("datos_subjetivos", "Datos subjetivos", TipoSeccion.TEXTO)),
        ),
        legadas = listOf(SeccionCatalogo(SeccionDef("diagnosticos", "Diagnósticos", TipoSeccion.DIAGNOSTICOS))),
    )

    private val remoto = listOf(
        SeccionCatalogoRemota("motivo_consulta", "Motivo de consulta", "Por qué viene"),
        SeccionCatalogoRemota("seccion_nueva", "Sección nueva", "La agregó hclinica después de esta versión"),
        SeccionCatalogoRemota("examen_fisico", "Examen físico", "Peso, talla, TA"),
        SeccionCatalogoRemota("diagnosticos", "Diagnósticos"),
        SeccionCatalogoRemota("datos_subjetivos", "Datos subjetivos"),
    )

    @Test
    fun orden_nombre_y_descripcion_del_remoto() {
        assertEquals(
            listOf(
                SeccionCatalogoAdmin("motivo_consulta", "Motivo de consulta", "Por qué viene", soportada = true),
                SeccionCatalogoAdmin("seccion_nueva", "Sección nueva", "La agregó hclinica después de esta versión", soportada = false),
                SeccionCatalogoAdmin("examen_fisico", "Examen físico", "Peso, talla, TA", soportada = true),
                SeccionCatalogoAdmin("diagnosticos", "Diagnósticos", "", soportada = false),
                SeccionCatalogoAdmin("datos_subjetivos", "Datos subjetivos", "", soportada = true),
            ),
            catalogoParaAdmin(remoto, catalogoApp),
        )
    }

    @Test
    fun la_seccion_que_la_app_no_tiene_no_es_soportada_aunque_sea_legada() {
        val soportadas = catalogoParaAdmin(remoto, catalogoApp).associate { it.id to it.soportada }
        assertEquals(false, soportadas["seccion_nueva"])
        assertEquals(false, soportadas["diagnosticos"])
    }

    @Test
    fun para_agregar_solo_soportadas_y_que_no_esten_en_el_perfil() {
        val perfil = PerfilHc(
            nombre = "Perfil de prueba",
            pestanias = listOf(PestaniaPerfil("Consulta", listOf(SeccionPerfil("examen_fisico", "Examen", setOf(CONSULTA))))),
        )
        assertEquals(
            listOf("motivo_consulta", "datos_subjetivos"),
            seccionesParaAgregar(catalogoParaAdmin(remoto, catalogoApp), perfil).map { it.id },
        )
    }
}
