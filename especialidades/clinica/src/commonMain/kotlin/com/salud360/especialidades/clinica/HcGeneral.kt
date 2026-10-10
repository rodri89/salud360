package com.salud360.especialidades.clinica

import com.salud360.core.model.especialidad.CampoExamenFisico
import com.salud360.core.model.especialidad.CatalogoHc
import com.salud360.core.model.especialidad.CodigosHc
import com.salud360.core.model.especialidad.EspecialidadDefinition
import com.salud360.core.model.especialidad.SeccionCatalogo
import com.salud360.core.model.especialidad.SeccionDef
import com.salud360.features.hc.SeccionesGenericas as G

// ---- HC general (spec 001) ----
// Convive con `clinicaMedica` y `hepatologia` hasta que la HC general se registre (tanda C): reutiliza sus
// secciones, laboratorios y registros sin cambiarlos.

/** Base de la HC general: lo que no depende del perfil. Los tipos y las secciones los arma `armarDefinicion`. */
val hcGeneralBase = EspecialidadDefinition(
    codigo = CodigosHc.GENERAL, nombre = "Clínica", nombreLegacy = "hclinica",
    tiposConsulta = emptyList(), secciones = emptyList(),
    examenFisicoCampos = setOf(CampoExamenFisico.PESO, CampoExamenFisico.TALLA, CampoExamenFisico.IMC, CampoExamenFisico.TENSION_ARTERIAL, CampoExamenFisico.NOTA),
)

/** Sección con el título por defecto del catálogo de hclinica ("Decidido" B1). */
private fun SeccionDef.comoCatalogo(titulo: String) = if (this.titulo == titulo) this else copy(titulo = titulo)

private fun deHepatologia(id: String, titulo: String): SeccionCatalogo {
    val def = hepatologia.seccion(id)!!.comoCatalogo(titulo)
    return SeccionCatalogo(
        def,
        laboratorios = listOfNotNull(def.laboratorioTipo?.let { hepatologia.laboratorio(it) }),
        registros = listOfNotNull(def.registroTipo?.let { hepatologia.registro(it) }),
    )
}

/**
 * Las 24 secciones de `CatalogoSecciones.php` de hclinica, con sus ids, en su orden y con sus nombres por defecto.
 * Las legadas (diagnósticos, audio, fotos, antecedentes personales) no van en perfiles: solo muestran datos viejos de
 * clínica y hepatología (RF-22, RF-30).
 */
val catalogoHcGeneral = CatalogoHc(
    base = hcGeneralBase,
    secciones = listOf(
        SeccionCatalogo(
            G.seccionFamiliares.comoCatalogo("Antecedentes familiares"),
            antecedentes = clinicaMedica.antecedentes.filter { it.categoria == "familiares" },
        ),
        SeccionCatalogo(G.motivo),
        SeccionCatalogo(G.documentos.comoCatalogo("Adjuntos (PDF)")),
        SeccionCatalogo(hepatologia.seccion("laboratorio_general")!!, laboratorios = listOf(labGeneralClinica)),
        deHepatologia("laboratorio_reducido", "Laboratorio reducido"),
        deHepatologia("eco_abdominal", "Ecografía abdominal"),
        deHepatologia("serologias", "Serologías de hepatitis"),
        deHepatologia("tabla_peso", "Peso"),
        deHepatologia("child_pugh", "Child-Pugh"),
        deHepatologia("veda", "Videoendoscopía digestiva alta"),
        deHepatologia("biopsia_hepatica", "Biopsia hepática"),
        deHepatologia("estudios_cardiologicos", "Estudios cardiológicos"),
        deHepatologia("tc_rmn", "TC / RMN / colangio-RMN"),
        SeccionCatalogo(G.subjetivo),
        SeccionCatalogo(G.objetivo),
        SeccionCatalogo(alimentacionClinica),
        // Como texto, igual que pediatría y que la web.
        SeccionCatalogo(G.texto("diuresis_catarsis", "Diuresis y catarsis", "diuresis")),
        SeccionCatalogo(G.texto("somnia", "Somnia", "sueno")),
        SeccionCatalogo(G.habitos),
        SeccionCatalogo(G.examenFisico),
        SeccionCatalogo(G.examenesComplementarios, registros = listOf(G.registroExamenComplementario)),
        SeccionCatalogo(G.interconsulta, registros = listOf(G.registroInterconsulta)),
        SeccionCatalogo(G.conductas),
        SeccionCatalogo(G.nota),
    ),
    legadas = listOf(
        SeccionCatalogo(G.diagnosticos),
        SeccionCatalogo(G.audio),
        SeccionCatalogo(G.fotos),
        // La de hepatología (casillas y nota libre) también muestra la nota libre que cargaba clínica.
        SeccionCatalogo(G.seccionPersonales, antecedentes = listOf(G.personalesEstandar)),
    ),
)
