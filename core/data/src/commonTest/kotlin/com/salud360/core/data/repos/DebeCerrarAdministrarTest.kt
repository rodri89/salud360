package com.salud360.core.data.repos

import com.salud360.core.data.hcgeneral.AvisoEnfermedades
import com.salud360.core.data.hcgeneral.Enfermedad
import com.salud360.core.data.hcgeneral.EstadoMarcado
import com.salud360.core.data.hcgeneral.EstadoSeccionEnfermedades
import com.salud360.core.data.hcgeneral.Marcable
import com.salud360.core.data.hcgeneral.MotivoEnfermedades
import com.salud360.core.data.hcgeneral.MotivoHc
import com.salud360.core.data.hcgeneral.puedeAdministrar
import com.salud360.core.data.network.hc.HcPacienteCandidato
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Cuándo la ventana "Administrar enfermedades" se cierra sola (spec 003, F3.5, ajuste 2026-10-23): si la sección deja de
 * poder administrar por la conexión o por un motivo de los lugares 1 a 5 de RF-14. No cierra cuando solo cambia
 * `editable` (RF-58) ni mientras la sección todavía carga. Todo a mano: `SeccionEnfermedadesUi` es un dato.
 */
class DebeCerrarAdministrarTest {

    private val estado = EstadoSeccionEnfermedades(
        marcables = listOf(Marcable(1, "Asma", false)),
        otras = listOf(Enfermedad(30, "Epilepsia")),
    )
    private val candidata = HcPacienteCandidato(id = 5, nombre = "Prueba", apellido = "Salud360", dni = "99000001")

    private fun general(tipo: MotivoHc.Tipo) = MotivoEnfermedades.General(MotivoHc(tipo, "motivo de prueba"))

    /** Un motivo por cada lugar de RF-14. */
    private val motivos: Map<Int, MotivoEnfermedades> = mapOf(
        1 to general(MotivoHc.Tipo.SESION_VENCIDA),
        2 to general(MotivoHc.Tipo.HC_NO_HABILITADA),
        3 to general(MotivoHc.Tipo.MEDICO_NO_VINCULADO),
        4 to general(MotivoHc.Tipo.LICENCIA_VENCIDA),
        5 to general(MotivoHc.Tipo.OTRO),
        6 to MotivoEnfermedades.ElegirFicha(listOf(candidata)),
        7 to MotivoEnfermedades.SinFicha("Corregí el documento en la ficha del paciente."),
        8 to MotivoEnfermedades.FichaNoDisponible,
        9 to MotivoEnfermedades.FueraDeCartera,
    )

    /** La sección cargada y con conexión; cada test cambia solo lo que prueba. */
    private fun ui(
        cargando: Boolean = false,
        marcado: EstadoMarcado = EstadoMarcado(confirmado = estado),
        motivo: MotivoEnfermedades? = null,
        textoSinCopia: String? = null,
    ) = SeccionEnfermedadesUi(cargando = cargando, marcado = marcado, motivo = motivo, textoSinCopia = textoSinCopia, fichaId = 10)

    @Test
    fun todo_normal_no_cierra() {
        assertFalse(debeCerrarAdministrar(ui()))
    }

    @Test
    fun sin_conexion_en_solo_lectura_cierra() {
        // RF-28: un toque sin respuesta pasó la sección a solo lectura con la ventana abierta.
        assertTrue(debeCerrarAdministrar(ui(marcado = EstadoMarcado(confirmado = estado, soloLectura = true))))
    }

    @Test
    fun sin_copia_por_falta_de_conexion_cierra() {
        assertTrue(debeCerrarAdministrar(ui(marcado = EstadoMarcado(), textoSinCopia = TEXTO_HACE_FALTA_CONEXION)))
    }

    @Test
    fun cada_motivo_de_los_lugares_1_a_5_cierra() {
        for (lugar in 1..5) {
            assertTrue(debeCerrarAdministrar(ui(motivo = motivos.getValue(lugar))), "lugar $lugar")
        }
    }

    @Test
    fun un_rechazo_de_un_toque_con_motivo_de_los_lugares_1_a_5_cierra() {
        // RF-27: el motivo llega por el aviso del marcado y también es el que se muestra.
        for (lugar in 1..5) {
            val marcado = EstadoMarcado(confirmado = estado, aviso = AvisoEnfermedades.Motivo(motivos.getValue(lugar)))
            assertTrue(debeCerrarAdministrar(ui(marcado = marcado)), "lugar $lugar")
        }
    }

    @Test
    fun cada_motivo_de_los_lugares_6_a_9_no_cierra() {
        for (lugar in 6..9) {
            assertFalse(debeCerrarAdministrar(ui(motivo = motivos.getValue(lugar))), "lugar $lugar")
        }
    }

    @Test
    fun el_motivo_mas_prioritario_manda_cuando_hay_varios() {
        // Un motivo 9 de la carga y un 2 de un toque: se muestra el 2 (RF-14), y ese cierra.
        val marcado = EstadoMarcado(confirmado = estado, aviso = AvisoEnfermedades.Motivo(motivos.getValue(2)))
        assertTrue(debeCerrarAdministrar(ui(marcado = marcado, motivo = motivos.getValue(9))))
    }

    @Test
    fun la_consulta_cerrada_con_la_ventana_abierta_no_cierra() {
        // RF-58: `editable` no entra en la regla; "Crear" sigue funcionando (sin marcar). La regla se apoya en que la
        // sección, con la misma conexión y motivo, sigue pudiendo administrar sin mirar `editable`.
        val u = ui()
        assertFalse(debeCerrarAdministrar(u))
        assertFalse(puedeAdministrar(editable = false, propia = true, conConexion = u.conConexion, motivo = u.motivoMostrado))
    }

    @Test
    fun cierra_justo_cuando_deja_de_poder_administrar_con_la_consulta_editable() {
        // La regla es la negación de `puedeAdministrar` con `editable = true` (la que usan los cambios de la lista).
        val casos = listOf(
            ui(),
            ui(marcado = EstadoMarcado(confirmado = estado, soloLectura = true)),
            ui(marcado = EstadoMarcado(), textoSinCopia = TEXTO_HACE_FALTA_CONEXION),
        ) + motivos.values.map { ui(motivo = it) }
        for (u in casos) {
            val puede = puedeAdministrar(editable = true, propia = true, conConexion = u.conConexion, motivo = u.motivoMostrado)
            assertEquals(!puede, debeCerrarAdministrar(u), u.toString())
        }
    }

    @Test
    fun mientras_la_seccion_carga_no_cierra() {
        // Todavía no se sabe si hay conexión: cargando no es "sin conexión". Recién abierta (sin nada) y recargando.
        assertFalse(debeCerrarAdministrar(SeccionEnfermedadesUi()))
        assertFalse(debeCerrarAdministrar(ui(cargando = true)))
        assertFalse(debeCerrarAdministrar(ui(cargando = true, marcado = EstadoMarcado(confirmado = estado, soloLectura = true))))
    }

    @Test
    fun cargando_con_un_motivo_de_los_lugares_1_a_5_igual_cierra() {
        // Un motivo ya conocido no depende de la conexión: mientras recarga, la sección ya dice por qué no se administra.
        assertTrue(debeCerrarAdministrar(ui(cargando = true, motivo = motivos.getValue(4))))
    }
}
