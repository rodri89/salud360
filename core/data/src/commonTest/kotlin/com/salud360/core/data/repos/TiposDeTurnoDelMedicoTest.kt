package com.salud360.core.data.repos

import com.salud360.core.model.turnos.CondicionTipoTurno
import com.salud360.core.model.turnos.OpcionTipoTurno
import com.salud360.core.model.turnos.TipoTurnoCatalogo
import com.salud360.core.model.turnos.TipoTurnoDelMedico
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Los tipos de turno de un médico en el panel del administrador: la condición, el estado de edición (tildar, condición,
 * opciones), el pedido exacto que sale a turnos, cuándo hay cambios y los textos. Todo puro: ni red, ni base. Los ids y
 * los nombres son inventados.
 */
class TiposDeTurnoDelMedicoTest {

    private val consulta = TipoTurnoCatalogo(1, "Consulta", activo = true)
    private val video = TipoTurnoCatalogo(2, "Videollamada", activo = true)
    private val eco = TipoTurnoCatalogo(3, "Ecografía", activo = true)
    private val viejo = TipoTurnoCatalogo(4, "Tipo viejo", activo = false)
    private val catalogo = listOf(consulta, video, eco, viejo)

    private fun delMedico(id: Int, condicion: CondicionTipoTurno = CondicionTipoTurno.SIEMPRE, vararg opciones: Int) =
        TipoTurnoDelMedico(id, "Tipo $id", condicion, opciones.map { OpcionTipoTurno(it, "Opción $it") })

    private fun vacio() = desdeServidor(catalogo, emptyList())

    // ---- Condición ----

    @Test
    fun lasTresCondicionesTienenSuCodigoYSuEtiqueta() {
        assertNull(CondicionTipoTurno.SIEMPRE.codigo)
        assertEquals("Siempre", CondicionTipoTurno.SIEMPRE.etiqueta)
        assertEquals(1, CondicionTipoTurno.SOLO_PRIMERA_CONSULTA.codigo)
        assertEquals("Solo primera consulta", CondicionTipoTurno.SOLO_PRIMERA_CONSULTA.etiqueta)
        assertEquals(0, CondicionTipoTurno.SOLO_SEGUIMIENTO.codigo)
        assertEquals("Solo consultas de seguimiento", CondicionTipoTurno.SOLO_SEGUIMIENTO.etiqueta)
        assertEquals(3, CondicionTipoTurno.entries.size)
    }

    @Test
    fun porCodigoTraduceLoQueInformaTurnos() {
        assertEquals(CondicionTipoTurno.SIEMPRE, CondicionTipoTurno.porCodigo(null))
        assertEquals(CondicionTipoTurno.SOLO_PRIMERA_CONSULTA, CondicionTipoTurno.porCodigo(1))
        assertEquals(CondicionTipoTurno.SOLO_SEGUIMIENTO, CondicionTipoTurno.porCodigo(0))
    }

    @Test
    fun unCodigoDesconocidoSeLeeComoSiempre() {
        assertEquals(CondicionTipoTurno.SIEMPRE, CondicionTipoTurno.porCodigo(7))
        assertEquals(CondicionTipoTurno.SIEMPRE, CondicionTipoTurno.porCodigo(-1))
    }

    // ---- Qué se ofrece ----

    @Test
    fun soloSeOfreceElCatalogoActivoEnSuOrden() {
        assertEquals(listOf(consulta, video, eco), tiposOfrecidos(catalogo))
    }

    @Test
    fun lasOpcionesPosiblesSonElCatalogoActivoSinElPropioTipo() {
        assertEquals(listOf(video, eco), opcionesPosibles(catalogo, tipoId = 1))
        assertEquals(listOf(consulta, eco), opcionesPosibles(catalogo, tipoId = 2))
        assertEquals(listOf(consulta, video, eco), opcionesPosibles(catalogo, tipoId = 99))
    }

    @Test
    fun laSeccionSoloSeVeConAgendaYConUnMedicoDeTurnos() {
        assertTrue(puedeVerTiposDeTurno(tieneAgenda = true, medicoId = "tobb-m12"))
        assertFalse(puedeVerTiposDeTurno(tieneAgenda = false, medicoId = "tobb-m12"))
        assertFalse(puedeVerTiposDeTurno(tieneAgenda = true, medicoId = "medico-local"))
        assertFalse(puedeVerTiposDeTurno(tieneAgenda = false, medicoId = "medico-local"))
    }

    // ---- desdeServidor ----

    @Test
    fun sinTiposHabilitadosTodoQuedaDestildadoYSinCambios() {
        val editor = vacio()
        catalogo.forEach { assertFalse(editor.estadoDe(it.id).habilitado, "tipo ${it.id}") }
        assertEquals(emptyList(), aPedido(editor))
        assertFalse(hayCambios(editor, editor))
    }

    @Test
    fun losTiposDelMedicoQuedanTildadosConSuCondicionYSusOpciones() {
        val editor = desdeServidor(
            catalogo,
            listOf(delMedico(1, CondicionTipoTurno.SOLO_PRIMERA_CONSULTA, 2, 3), delMedico(3, CondicionTipoTurno.SOLO_SEGUIMIENTO)),
        )
        val uno = editor.estadoDe(1)
        assertTrue(uno.habilitado)
        assertEquals(CondicionTipoTurno.SOLO_PRIMERA_CONSULTA, uno.condicion)
        assertEquals(setOf(2, 3), uno.opciones)
        assertFalse(uno.opcionesTocadas)
        val tres = editor.estadoDe(3)
        assertTrue(tres.habilitado)
        assertEquals(CondicionTipoTurno.SOLO_SEGUIMIENTO, tres.condicion)
        assertEquals(emptySet(), tres.opciones)
        assertFalse(editor.estadoDe(2).habilitado)
    }

    @Test
    fun unTipoDelMedicoQueNoEstaEnElCatalogoSeConservaEnElPedido() {
        // Si el catálogo no lo trae, igual está habilitado en turnos: el pedido es la lista exacta y no puede soltarlo.
        val editor = desdeServidor(catalogo, listOf(delMedico(1), delMedico(77, CondicionTipoTurno.SOLO_SEGUIMIENTO)))
        assertEquals(listOf(1, 77), aPedido(editor).map { it.id })
        assertEquals(CondicionTipoTurno.SOLO_SEGUIMIENTO, aPedido(editor).last().condicion)
    }

    @Test
    fun unTipoInactivoQueElMedicoTieneHabilitadoSeConserva() {
        val editor = desdeServidor(catalogo, listOf(delMedico(4)))
        assertTrue(editor.estadoDe(4).habilitado)
        assertEquals(listOf(4), aPedido(editor).map { it.id })
    }

    // ---- alternarTipo ----

    @Test
    fun tildarUnTipoLoHabilitaConLosValoresDeSiempre() {
        val editor = alternarTipo(vacio(), 2, habilitado = true)
        val e = editor.estadoDe(2)
        assertTrue(e.habilitado)
        assertEquals(CondicionTipoTurno.SIEMPRE, e.condicion)
        assertEquals(emptySet(), e.opciones)
        assertFalse(editor.estadoDe(1).habilitado, "no toca a los demás")
    }

    @Test
    fun destildarUnTipoLoDeshabilitaPeroConservaSuConfiguracionEnPantalla() {
        val inicial = desdeServidor(catalogo, listOf(delMedico(1, CondicionTipoTurno.SOLO_SEGUIMIENTO, 2)))
        val destildado = alternarTipo(inicial, 1, habilitado = false)
        assertFalse(destildado.estadoDe(1).habilitado)
        assertEquals(emptyList(), aPedido(destildado), "un tipo destildado no sale en el pedido")

        val retildado = alternarTipo(destildado, 1, habilitado = true)
        assertTrue(retildado.estadoDe(1).habilitado)
        assertEquals(CondicionTipoTurno.SOLO_SEGUIMIENTO, retildado.estadoDe(1).condicion, "al volver a tildar reaparece lo que estaba")
        assertEquals(setOf(2), retildado.estadoDe(1).opciones)
    }

    @Test
    fun tildarUnTipoQueElEditorNoConocePasaAEstarHabilitado() {
        // Un tipo creado después de cargar al médico: el editor todavía no lo tiene.
        val editor = alternarTipo(vacio(), 50, habilitado = true)
        assertTrue(editor.estadoDe(50).habilitado)
    }

    @Test
    fun tildarUnTipoYaTildadoNoCambiaNada() {
        val inicial = desdeServidor(catalogo, listOf(delMedico(1, CondicionTipoTurno.SOLO_PRIMERA_CONSULTA, 2)))
        assertEquals(inicial, alternarTipo(inicial, 1, habilitado = true))
    }

    // ---- cambiarCondicion ----

    @Test
    fun laCondicionCambiaEnUnTipoHabilitado() {
        CondicionTipoTurno.entries.forEach { condicion ->
            val editor = cambiarCondicion(alternarTipo(vacio(), 1, true), 1, condicion)
            assertEquals(condicion, editor.estadoDe(1).condicion)
        }
    }

    @Test
    fun laCondicionNoCambiaEnUnTipoDestildado() {
        val inicial = vacio()
        assertEquals(inicial, cambiarCondicion(inicial, 1, CondicionTipoTurno.SOLO_SEGUIMIENTO))
    }

    // ---- alternarOpcion ----

    @Test
    fun seMarcaYSeDesmarcaUnaOpcionDeUnTipoHabilitado() {
        val habilitado = alternarTipo(vacio(), 1, true)
        val conOpcion = alternarOpcion(habilitado, catalogo, 1, 2, marcada = true)
        assertEquals(setOf(2), conOpcion.estadoDe(1).opciones)
        assertTrue(conOpcion.estadoDe(1).opcionesTocadas)

        val conDos = alternarOpcion(conOpcion, catalogo, 1, 3, marcada = true)
        assertEquals(setOf(2, 3), conDos.estadoDe(1).opciones)

        val sinUna = alternarOpcion(conDos, catalogo, 1, 2, marcada = false)
        assertEquals(setOf(3), sinUna.estadoDe(1).opciones)
    }

    @Test
    fun noSeMarcaUnaOpcionEnUnTipoDestildado() {
        val inicial = vacio()
        assertEquals(inicial, alternarOpcion(inicial, catalogo, 1, 2, marcada = true))
    }

    @Test
    fun unTipoNoPuedeSerOpcionDeSiMismo() {
        val habilitado = alternarTipo(vacio(), 1, true)
        assertEquals(habilitado, alternarOpcion(habilitado, catalogo, 1, 1, marcada = true))
    }

    @Test
    fun soloSePuedenMarcarOpcionesDelCatalogoActivo() {
        val habilitado = alternarTipo(vacio(), 1, true)
        assertEquals(habilitado, alternarOpcion(habilitado, catalogo, 1, 4, marcada = true), "el tipo 4 está inactivo")
        assertEquals(habilitado, alternarOpcion(habilitado, catalogo, 1, 99, marcada = true), "el tipo 99 no existe")
    }

    @Test
    fun unaOpcionPuedeSerUnTipoQueElMedicoNoTieneHabilitado() {
        val habilitado = alternarTipo(vacio(), 1, true)
        val editor = alternarOpcion(habilitado, catalogo, 1, 3, marcada = true)
        assertEquals(setOf(3), editor.estadoDe(1).opciones)
        assertFalse(editor.estadoDe(3).habilitado)
    }

    @Test
    fun lasOpcionesDeUnTipoDestildadoSeConservanYReaparecenAlVolverATildar() {
        val habilitado = alternarOpcion(alternarTipo(vacio(), 1, true), catalogo, 1, 2, marcada = true)
        val vuelta = alternarTipo(alternarTipo(habilitado, 1, false), 1, true)
        assertEquals(setOf(2), vuelta.estadoDe(1).opciones)
        assertTrue(vuelta.estadoDe(1).opcionesTocadas)
    }

    @Test
    fun marcarUnaOpcionYaMarcadaNoCambiaElConjunto() {
        val editor = alternarOpcion(alternarOpcion(alternarTipo(vacio(), 1, true), catalogo, 1, 2, true), catalogo, 1, 2, true)
        assertEquals(setOf(2), editor.estadoDe(1).opciones)
    }

    // ---- aPedido ----

    @Test
    fun elPedidoTrae_soloLosTiposHabilitados_enOrdenPorId() {
        var editor = vacio()
        editor = alternarTipo(editor, 3, true)
        editor = alternarTipo(editor, 1, true)
        assertEquals(listOf(1, 3), aPedido(editor).map { it.id })
    }

    @Test
    fun sinNingunTipoHabilitadoElPedidoEsLaListaVacia() {
        val editor = alternarTipo(desdeServidor(catalogo, listOf(delMedico(1))), 1, habilitado = false)
        assertEquals(emptyList(), aPedido(editor))
    }

    @Test
    fun elPedidoLlevaLaCondicionDeCadaTipo() {
        var editor = vacio()
        editor = cambiarCondicion(alternarTipo(editor, 1, true), 1, CondicionTipoTurno.SIEMPRE)
        editor = cambiarCondicion(alternarTipo(editor, 2, true), 2, CondicionTipoTurno.SOLO_PRIMERA_CONSULTA)
        editor = cambiarCondicion(alternarTipo(editor, 3, true), 3, CondicionTipoTurno.SOLO_SEGUIMIENTO)
        assertEquals(
            listOf(CondicionTipoTurno.SIEMPRE, CondicionTipoTurno.SOLO_PRIMERA_CONSULTA, CondicionTipoTurno.SOLO_SEGUIMIENTO),
            aPedido(editor).map { it.condicion },
        )
    }

    @Test
    fun lasOpcionesNoTocadasNoViajanYElServidorLasConserva() {
        val editor = desdeServidor(catalogo, listOf(delMedico(1, CondicionTipoTurno.SIEMPRE, 2, 3)))
        assertNull(aPedido(editor).single().opciones)
        val recienTildado = alternarTipo(vacio(), 2, true)
        assertNull(aPedido(recienTildado).single().opciones)
    }

    @Test
    fun lasOpcionesTocadasViajanOrdenadas() {
        var editor = alternarTipo(vacio(), 1, true)
        editor = alternarOpcion(editor, catalogo, 1, 3, true)
        editor = alternarOpcion(editor, catalogo, 1, 2, true)
        assertEquals(listOf(2, 3), aPedido(editor).single().opciones)
    }

    @Test
    fun unTipoHabilitadoCuyasOpcionesSeVaciaronMandaLaListaVacia() {
        var editor = desdeServidor(catalogo, listOf(delMedico(1, CondicionTipoTurno.SIEMPRE, 2)))
        editor = alternarOpcion(editor, catalogo, 1, 2, marcada = false)
        assertEquals(emptyList(), aPedido(editor).single().opciones, "vaciar las opciones es un pedido: `[]` tiene que viajar")
    }

    @Test
    fun lasOpcionesInactivasQueTraiaElServidorNoSePierdenAlTocarLasOtras() {
        // El servidor tiene la opción 4 (hoy inactiva en el catálogo): la pantalla no la ofrece, pero tocar otra no la borra.
        var editor = desdeServidor(catalogo, listOf(delMedico(1, CondicionTipoTurno.SIEMPRE, 2, 4)))
        editor = alternarOpcion(editor, catalogo, 1, 3, marcada = true)
        assertEquals(listOf(2, 3, 4), aPedido(editor).single().opciones)
    }

    @Test
    fun unTipoDestildadoNoSaleAunqueSusOpcionesSeHayanTocado() {
        var editor = alternarOpcion(alternarTipo(vacio(), 1, true), catalogo, 1, 2, true)
        editor = alternarTipo(editor, 1, false)
        assertEquals(emptyList(), aPedido(editor))
    }

    // ---- hayCambios ----

    @Test
    fun sinTocarNadaNoHayCambios() {
        val original = desdeServidor(catalogo, listOf(delMedico(1, CondicionTipoTurno.SOLO_SEGUIMIENTO, 2)))
        assertFalse(hayCambios(original, original))
    }

    @Test
    fun tildarUnTipoEsUnCambioYDestildarloDeNuevoNo() {
        val original = vacio()
        val tildado = alternarTipo(original, 2, true)
        assertTrue(hayCambios(original, tildado))
        assertFalse(hayCambios(original, alternarTipo(tildado, 2, false)))
    }

    @Test
    fun destildarUnTipoHabilitadoEsUnCambio() {
        val original = desdeServidor(catalogo, listOf(delMedico(1)))
        assertTrue(hayCambios(original, alternarTipo(original, 1, false)))
    }

    @Test
    fun cambiarLaCondicionEsUnCambioYVolverAlaOriginalNo() {
        val original = desdeServidor(catalogo, listOf(delMedico(1)))
        val cambiada = cambiarCondicion(original, 1, CondicionTipoTurno.SOLO_PRIMERA_CONSULTA)
        assertTrue(hayCambios(original, cambiada))
        assertFalse(hayCambios(original, cambiarCondicion(cambiada, 1, CondicionTipoTurno.SIEMPRE)))
    }

    @Test
    fun cambiarLasOpcionesEsUnCambioYMarcarYDesmarcarNo() {
        val original = desdeServidor(catalogo, listOf(delMedico(1, CondicionTipoTurno.SIEMPRE, 2)))
        val conOtra = alternarOpcion(original, catalogo, 1, 3, marcada = true)
        assertTrue(hayCambios(original, conOtra))
        val devuelta = alternarOpcion(conOtra, catalogo, 1, 3, marcada = false)
        assertTrue(devuelta.estadoDe(1).opcionesTocadas, "se tocó, pero el resultado es el mismo")
        assertFalse(hayCambios(original, devuelta))
    }

    @Test
    fun lasOpcionesDeUnTipoDestildadoNoCuentanComoCambio() {
        val original = vacio()
        var editor = alternarTipo(original, 1, true)
        editor = alternarOpcion(editor, catalogo, 1, 2, true)
        editor = cambiarCondicion(editor, 1, CondicionTipoTurno.SOLO_SEGUIMIENTO)
        editor = alternarTipo(editor, 1, false)
        assertFalse(hayCambios(original, editor), "un tipo destildado no se manda: lo que tenga adentro no es un cambio")
    }

    // ---- Estado de la carga ----

    @Test
    fun elEstadoListoSabeSiHayCambios() {
        val original = desdeServidor(catalogo, listOf(delMedico(1)))
        val listo = EstadoTiposDelMedico.Listo(original, original)
        assertFalse(listo.hayCambios)
        assertTrue(listo.copy(editor = alternarTipo(original, 2, true)).hayCambios)
    }

    @Test
    fun tiposListosArmaEditorYOriginalIgualesConLosAvisos() {
        val listo = tiposListos(catalogo, listOf(delMedico(1, CondicionTipoTurno.SOLO_PRIMERA_CONSULTA, 2)), listOf("Aviso uno."))
        assertEquals(listo.editor, listo.original)
        assertEquals(listOf("Aviso uno."), listo.avisos)
        assertFalse(listo.hayCambios)
        assertEquals(emptyList(), tiposListos(catalogo, emptyList()).avisos)
    }

    // ---- Catálogo ----

    @Test
    fun elTipoCreadoSeSumaAlCatalogoSinDuplicarse() {
        val creado = TipoTurnoCatalogo(9, "Nuevo", activo = true)
        assertEquals(catalogo + creado, conTipoCreado(catalogo, creado))
        assertEquals(catalogo + creado, conTipoCreado(catalogo + creado, creado))
    }

    @Test
    fun elNombreNuevoSeRecortaYDebeTenerEntre1y60Caracteres() {
        assertEquals("Control", nombreNuevoDeTipo("  Control  "))
        assertNull(nombreNuevoDeTipo(""))
        assertNull(nombreNuevoDeTipo("    "))
        assertEquals("a".repeat(60), nombreNuevoDeTipo("a".repeat(60)))
        assertNull(nombreNuevoDeTipo("a".repeat(61)))
        assertEquals("a".repeat(60), nombreNuevoDeTipo("  " + "a".repeat(60) + "  "), "los espacios de los costados no cuentan")
    }

    @Test
    fun soloUnNombreMuyLargoMuestraError() {
        assertNull(errorDelNombreDeTipo(""))
        assertNull(errorDelNombreDeTipo("Control"))
        assertNull(errorDelNombreDeTipo("a".repeat(60)))
        assertEquals("El nombre puede tener hasta 60 caracteres.", errorDelNombreDeTipo("a".repeat(61)))
    }

    // ---- Avisos ----

    @Test
    fun guardarSinAvisosDelServidorDiceSoloQueSeGuardo() {
        assertEquals("Tipos de turno guardados", avisoDeTiposGuardados(emptyList()))
        assertEquals("Tipos de turno guardados", avisoDeTiposGuardados(listOf("", "   ")))
    }

    @Test
    fun guardarConAvisosDelServidorLosMuestraTalCual() {
        val aviso = "El tipo Consulta sigue teniendo 17 horarios activos."
        assertEquals("Tipos de turno guardados. $aviso", avisoDeTiposGuardados(listOf(aviso)))
        assertEquals("Tipos de turno guardados. $aviso Otro aviso.", avisoDeTiposGuardados(listOf(aviso, "Otro aviso.")))
    }

    @Test
    fun siNoSeGuardoElAvisoDiceElMotivoReal() {
        assertEquals(
            "No se pudieron guardar los tipos de turno: Solo el administrador puede cambiarlos",
            avisoDeTiposNoGuardados("Solo el administrador puede cambiarlos"),
        )
        assertEquals("No se pudieron guardar los tipos de turno: sin conexión", avisoDeTiposNoGuardados(null))
        assertEquals("No se pudieron guardar los tipos de turno: sin conexión", avisoDeTiposNoGuardados("  "))
    }

    @Test
    fun losAvisosDeCrearUnTipo() {
        assertEquals("Tipo de turno creado", avisoDeTipoCreado())
        assertEquals("No se pudo crear el tipo de turno: Ya existe un tipo con ese nombre", avisoDeTipoNoCreado("Ya existe un tipo con ese nombre"))
        assertEquals("No se pudo crear el tipo de turno: sin conexión", avisoDeTipoNoCreado(null))
    }

    @Test
    fun elAvisoDeQueNoSePudieronCargar() {
        assertEquals("No se pudieron cargar los tipos de turno: sin conexión", avisoDeTiposNoCargados(null))
        assertEquals("No se pudieron cargar los tipos de turno: motivo", avisoDeTiposNoCargados("motivo"))
    }
}
