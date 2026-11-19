package com.salud360.core.data.hcgeneral

import com.salud360.core.model.especialidad.CatalogoHc
import com.salud360.core.model.especialidad.PerfilHc
import com.salud360.core.model.especialidad.SeccionCatalogoRemota

/**
 * Sección del catálogo de hclinica como la ve el administrador (RF-1). [soportada] = esta versión de la app la sabe
 * mostrar; si no, se lista como "requiere actualizar la app" y no se ofrece para agregar (RF-16).
 */
data class SeccionCatalogoAdmin(
    val id: String,
    val nombre: String,
    val descripcion: String,
    val soportada: Boolean,
)

/**
 * Catálogo para el administrador: el [remoto] (`config/catalogo`), en su orden y con su nombre y descripción,
 * marcando las que están entre las secciones de [catalogoApp]. Las legadas no cuentan: no se ofrecen en los perfiles.
 */
fun catalogoParaAdmin(remoto: List<SeccionCatalogoRemota>, catalogoApp: CatalogoHc): List<SeccionCatalogoAdmin> =
    remoto.map { SeccionCatalogoAdmin(it.id, it.nombre, it.descripcion, soportada = catalogoApp.seccion(it.id) != null) }

/** Las que se pueden agregar a [perfil]: soportadas y que no estén ya en alguna de sus pestañas (RF-9, RF-16). */
fun seccionesParaAgregar(catalogo: List<SeccionCatalogoAdmin>, perfil: PerfilHc): List<SeccionCatalogoAdmin> {
    val enPerfil = perfil.pestanias.flatMapTo(mutableSetOf()) { p -> p.secciones.map { it.seccion } }
    return catalogo.filter { it.soportada && it.id !in enPerfil }
}
