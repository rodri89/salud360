package com.salud360.core.data.repos

import com.salud360.core.model.auth.Licencia
import com.salud360.core.model.auth.Medico
import com.salud360.core.model.auth.Rol
import com.salud360.core.model.auth.Usuario
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Qué médicos y licencias muestra el panel del administrador: se oculta a quien está desactivado, ya sea el médico
 * (`Medico.activo`, de turnos) o su usuario (`Usuario.activo`). Todo puro: ni red, ni base.
 */
class ListadosDelPanelTest {

    private fun usuario(id: String, activo: Boolean = true) =
        Usuario(id = id, email = "$id@ejemplo.test", nombre = "N$id", apellido = "A$id", rol = Rol.MEDICO, activo = activo)

    private fun medico(id: String, usuarioId: String, activo: Boolean = true) =
        Medico(id = id, usuarioId = usuarioId, nombre = "N$id", apellido = "A$id", activo = activo)

    private fun licencia(medicoId: String) = Licencia(medicoId = medicoId, fechaExpiracion = "2030-01-01", fechaAviso = "2029-12-01")

    // ---- medicosVisiblesEnElPanel ----

    private data class Caso(val nombre: String, val medicoActivo: Boolean, val usuarioActivo: Boolean?, val visible: Boolean)

    private val casos = listOf(
        Caso("médico activo con usuario activo", medicoActivo = true, usuarioActivo = true, visible = true),
        Caso("médico inactivo con usuario activo", medicoActivo = false, usuarioActivo = true, visible = false),
        Caso("médico activo con usuario inactivo", medicoActivo = true, usuarioActivo = false, visible = false),
        Caso("médico inactivo con usuario inactivo", medicoActivo = false, usuarioActivo = false, visible = false),
        Caso("médico activo sin dato del usuario", medicoActivo = true, usuarioActivo = null, visible = true),
        Caso("médico inactivo sin dato del usuario", medicoActivo = false, usuarioActivo = null, visible = false),
    )

    @Test
    fun `tabla de médicos visibles según el estado del médico y de su usuario`() {
        casos.forEach { c ->
            val usuarios = c.usuarioActivo?.let { listOf(usuario("tobb-u1", it)) } ?: emptyList()
            val resultado = medicosVisiblesEnElPanel(listOf(medico("tobb-m1", "tobb-u1", c.medicoActivo)), usuarios)
            assertEquals(c.visible, resultado.isNotEmpty(), c.nombre)
        }
    }

    @Test
    fun `médico sin usuarioId decide solo su propio activo`() {
        val usuarios = listOf(usuario("tobb-u1", activo = false))
        assertEquals(1, medicosVisiblesEnElPanel(listOf(medico("tobb-m1", "", activo = true)), usuarios).size)
        assertTrue(medicosVisiblesEnElPanel(listOf(medico("tobb-m1", "", activo = false)), usuarios).isEmpty())
    }

    @Test
    fun `usuarioId que no está en la lista de usuarios decide solo el médico`() {
        val usuarios = listOf(usuario("tobb-u9", activo = false))
        assertEquals(1, medicosVisiblesEnElPanel(listOf(medico("tobb-m1", "tobb-u1", activo = true)), usuarios).size)
        assertTrue(medicosVisiblesEnElPanel(listOf(medico("tobb-m1", "tobb-u1", activo = false)), usuarios).isEmpty())
    }

    @Test
    fun `lista vacía de médicos da lista vacía`() {
        assertTrue(medicosVisiblesEnElPanel(emptyList(), listOf(usuario("tobb-u1"))).isEmpty())
        assertTrue(medicosVisiblesEnElPanel(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `conserva el orden y saca solo a los desactivados`() {
        val usuarios = listOf(usuario("tobb-u1"), usuario("tobb-u2", activo = false), usuario("tobb-u3"), usuario("tobb-u4"))
        val medicos = listOf(
            medico("tobb-m4", "tobb-u4"),
            medico("tobb-m2", "tobb-u2"),
            medico("tobb-m1", "tobb-u1"),
            medico("tobb-m5", "tobb-u5", activo = false),
            medico("tobb-m3", "tobb-u3"),
        )
        assertEquals(listOf("tobb-m4", "tobb-m1", "tobb-m3"), medicosVisiblesEnElPanel(medicos, usuarios).map { it.id })
    }

    // ---- licenciasVisiblesEnElPanel ----

    @Test
    fun `licencias solo de los médicos visibles y en su orden`() {
        val visibles = listOf(medico("tobb-m1", "tobb-u1"), medico("tobb-m3", "tobb-u3"))
        val licencias = listOf(licencia("tobb-m3"), licencia("tobb-m2"), licencia("tobb-m1"))
        assertEquals(listOf("tobb-m3", "tobb-m1"), licenciasVisiblesEnElPanel(licencias, visibles).map { it.medicoId })
    }

    @Test
    fun `sin médicos visibles no hay licencias`() {
        assertTrue(licenciasVisiblesEnElPanel(listOf(licencia("tobb-m1")), emptyList()).isEmpty())
    }

    @Test
    fun `sin licencias da lista vacía`() {
        assertTrue(licenciasVisiblesEnElPanel(emptyList(), listOf(medico("tobb-m1", "tobb-u1"))).isEmpty())
    }
}
