package com.salud360.especialidades.clinica

import com.salud360.core.data.hcgeneral.armarDefinicion
import com.salud360.core.model.especialidad.CampoExamenFisico
import com.salud360.core.model.especialidad.CatalogoHc
import com.salud360.core.model.especialidad.CodigosHc
import com.salud360.core.model.especialidad.EspecialidadDefinition
import com.salud360.core.model.especialidad.ModoForm
import com.salud360.core.model.especialidad.ModoRegistro
import com.salud360.core.model.especialidad.SeccionCatalogo
import com.salud360.core.model.especialidad.SeccionDef
import com.salud360.features.hc.EspecialidadContribution
import com.salud360.features.hc.SeccionesGenericas as G

// ---- HC general (spec 001) ----
// Reemplaza a `clinicaMedica` en la app; reutiliza sus secciones, laboratorios y registros, y los de `hepatologia`
// (que sigue registrada aparte hasta la tanda D), sin cambiarlos.

/** Base de la HC general: lo que no depende del perfil. Los tipos y las secciones los arma `armarDefinicion`. */
val hcGeneralBase = EspecialidadDefinition(
    codigo = CodigosHc.GENERAL, nombre = "Clínica", nombreLegacy = "hclinica",
    tiposConsulta = emptyList(), secciones = emptyList(),
    examenFisicoCampos = setOf(CampoExamenFisico.PESO, CampoExamenFisico.TALLA, CampoExamenFisico.IMC, CampoExamenFisico.TENSION_ARTERIAL, CampoExamenFisico.NOTA),
    // La clínica de antes los cargaba: lo ya cargado se sigue leyendo ("Decidido" 6 del plan).
    examenFisicoSoloLectura = setOf(CampoExamenFisico.FRECUENCIA_CARDIACA, CampoExamenFisico.CIRCUNFERENCIA_ABDOMINAL),
)

/** Sección con el título por defecto del catálogo de hclinica ("Decidido" B1). */
private fun SeccionDef.comoCatalogo(titulo: String) = if (this.titulo == titulo) this else copy(titulo = titulo)

/** Los estudios que en la HC general guardan una entrada por consulta, con historial (RF-38). */
private val estudiosConHistorial = setOf("eco_abdominal", "veda", "biopsia_hepatica", "estudios_cardiologicos", "tc_rmn")

/** Las secciones que en la HC general se dibujan como tarjetas SI / NO (RF-65). */
private val formulariosEnGrilla = setOf("serologias")

private fun deHepatologia(id: String, titulo: String): SeccionCatalogo {
    // Copia: la de `hepatologia` queda en FILAS hasta que se vaya en la tanda D (RF-73); los campos no cambian (RF-68).
    val def = hepatologia.seccion(id)!!.comoCatalogo(titulo)
        .let { if (it.id in formulariosEnGrilla) it.copy(modoForm = ModoForm.GRILLA_SI_NO) else it }
    return SeccionCatalogo(
        def,
        laboratorios = listOfNotNull(def.laboratorioTipo?.let { hepatologia.laboratorio(it) }),
        // Copia: el de `hepatologia` queda en LISTA hasta que se vaya en la tanda D (RF-33).
        registros = listOfNotNull(def.registroTipo?.let { hepatologia.registro(it) })
            .map { if (it.tipo in estudiosConHistorial) it.copy(modo = ModoRegistro.UNO_POR_CONSULTA) else it },
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

/**
 * La HC general en el registro: la definición sin perfil solo da el nombre y `existe()`; la de cada médico la arma
 * `DefinicionesHc` con el [catalogoHcGeneral] y su perfil.
 */
val hcGeneralContribution = EspecialidadContribution(
    armarDefinicion(catalogoHcGeneral, null).definicion,
    mapOf("child_pugh" to { s, ctx -> ChildPughSeccion(s, ctx) }),
    catalogo = catalogoHcGeneral,
)
