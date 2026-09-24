package com.salud360.core.data.repos

import co.touchlab.kermit.Logger
import com.salud360.core.data.ahoraMillis
import com.salud360.core.data.lista
import com.salud360.core.data.mappers.toModel
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.network.TobbException
import com.salud360.core.data.network.hc.HcAbrirConsulta
import com.salud360.core.data.network.hc.HcCerrarConsulta
import com.salud360.core.data.network.hc.HcConsulta
import com.salud360.core.data.network.hc.HcExamenRequest
import com.salud360.core.data.network.hc.HcPaciente
import com.salud360.core.data.network.hc.HcPacienteRequest
import com.salud360.core.data.network.hc.HcRegistro
import com.salud360.core.data.network.hc.HcRegistroRemoto
import com.salud360.core.data.network.hc.HcRegistrosRequest
import com.salud360.core.data.network.hc.HcSeccionesRequest
import com.salud360.core.data.network.hc.HcApiClient
import com.salud360.core.data.uno
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.Id
import com.salud360.core.model.TobbIds
import com.salud360.core.model.especialidad.SeccionesComunes
import com.salud360.core.model.hc.Antecedente
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.hc.ExamenFisico
import com.salud360.core.model.hc.RegistroClinico
import com.salud360.core.model.hc.SeccionValor
import com.salud360.core.model.newId
import com.salud360.core.model.pacientes.PacienteExtra
import kotlinx.datetime.LocalDate
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Historia clínica de pediatría contra su propia API (`hc_pediatria`).
 *
 * La API escribe en las mismas tablas que usa la web de pediatría, así que lo que el médico carga acá
 * lo ve en `pediatria.hclinicadigital.com` y al revés.
 *
 * Identidad: se usa el token de turnosonlinebb, que ya tiene la app. Pediatría lo valida contra turnos
 * y resuelve al médico por `users.medico_id_tobb`; no hay un segundo inicio de sesión.
 */
class HcPediatriaBackend(
    private val db: Salud360Db,
    private val api: HcApiClient,
    override val especialidad: String = "pediatria",
) : HcBackend {
    private val log = Logger.withTag("HcPediatria")
    private val hq get() = db.historiaClinicaQueries
    private val pq get() = db.pacientesQueries

    // ------------------------------------------------------------------
    // Consultas
    // ------------------------------------------------------------------

    override suspend fun asegurarConsulta(consulta: Consulta): String? {
        if (consulta.remotoId.isNotBlank()) return consulta.remotoId
        val pacienteRemoto = resolverPaciente(consulta.pacienteId) ?: return null
        val cuerpo = HcAbrirConsulta(
            pacienteId = pacienteRemoto,
            tipo = consulta.tipo,
            fecha = fechaTexto(consulta.fecha),
            edadMostrar = consulta.edadMostrar,
        )
        val r = api.post("consultas/abrir", api.cuerpo(HcAbrirConsulta.serializer(), cuerpo))
        val remota = api.leer(HcConsulta.serializer(), r, "consulta")
        val remotoId = remota.id.toString()
        hq.guardarRemotoId(remotoId, consulta.id)
        log.i { "consulta ${consulta.id} creada en pediatría como $remotoId" }
        return remotoId
    }

    override suspend fun enviarSecciones(remotoId: String, secciones: Map<String, Map<String, String>>) {
        if (secciones.isEmpty()) return
        val r = api.put("consultas/$remotoId/secciones", api.cuerpo(HcSeccionesRequest.serializer(), HcSeccionesRequest(secciones)))
        // Pediatría todavía no traduce las secciones estructuradas (fase 2) y avisa cuáles ignoró.
        // Se deja constancia: la consulta queda igual en el dispositivo, pero esa parte no está allá.
        val desconocidas = api.leerOpcional(SERIALIZER_IDS, r, "desconocidas").orEmpty()
        if (desconocidas.isNotEmpty()) {
            log.w { "pediatría no guardó ${desconocidas.joinToString()} de la consulta $remotoId: todavía no traduce esas secciones" }
        }
    }

    override suspend fun enviarExamen(remotoId: String, campos: Map<String, String>) {
        if (campos.isEmpty()) return
        api.put("consultas/$remotoId/examen", api.cuerpo(HcExamenRequest.serializer(), HcExamenRequest(campos)))
    }

    override suspend fun enviarRegistros(remotoId: String, registros: List<RegistroClinico>): Map<Id, String> {
        if (registros.isEmpty()) return emptyMap()
        val cuerpo = HcRegistrosRequest(
            registros.map {
                HcRegistro(
                    ref = it.id,
                    tipo = it.tipo,
                    id = it.remotoId.ifBlank { null },
                    fecha = it.fecha?.toString(),
                    campos = it.campos,
                    borrado = !it.activo,
                )
            },
        )
        val r = api.put("consultas/$remotoId/registros", api.cuerpo(HcRegistrosRequest.serializer(), cuerpo))
        val desconocidos = api.leerOpcional(SERIALIZER_IDS, r, "desconocidos").orEmpty()
        if (desconocidos.isNotEmpty()) {
            log.w { "pediatría no guardó registros de tipo ${desconocidos.joinToString()}: todavía no los traduce" }
        }
        return api.leerOpcional(SERIALIZER_CAMPOS, r, "ids").orEmpty()
    }

    override suspend fun enviarEstado(consulta: Consulta, remotoId: String) {
        when (consulta.estado) {
            EstadoConsulta.CERRADA -> {
                val cuerpo = HcCerrarConsulta(fechaTexto(consulta.fecha), consulta.edadMostrar)
                api.post("consultas/$remotoId/cerrar", api.cuerpo(HcCerrarConsulta.serializer(), cuerpo))
            }
            EstadoConsulta.ABIERTA -> api.post("consultas/$remotoId/reabrir")
            EstadoConsulta.ANULADA -> api.pedir(io.ktor.http.HttpMethod.Delete, "consultas/$remotoId")
        }
    }

    override suspend fun traerConsultas(pacienteId: Id, medicoId: Id): List<Consulta> {
        val pacienteRemoto = resolverPaciente(pacienteId) ?: return emptyList()
        val r = api.get("consultas", mapOf("paciente_id" to pacienteRemoto))
        val remotas = api.leerOpcional(ListSerializer(HcConsulta.serializer()), r, "consultas") ?: emptyList()
        val ahora = ahoraMillis()
        val out = mutableListOf<Consulta>()
        for (remota in remotas) {
            val local = hq.consultaPorRemotoId(especialidad, remota.id.toString()).uno { it.toModel() }
            // Una consulta con cambios sin enviar manda ella: no se pisa lo que el médico escribió.
            if (local != null && esSucia(local.id)) { out += local; continue }
            val consulta = Consulta(
                id = local?.id ?: "hcp-c${remota.id}",
                pacienteId = pacienteId,
                medicoId = medicoId,
                especialidad = especialidad,
                tipo = remota.tipo,
                fecha = remota.fecha?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: local?.fecha ?: hoy(),
                estado = runCatching { EstadoConsulta.valueOf(remota.estado) }.getOrDefault(EstadoConsulta.CERRADA),
                edadMostrar = remota.edadMostrar,
                remotoId = remota.id.toString(),
            )
            hq.upsertConsulta(consulta.toRow(ahora, dirty = false))
            out += consulta
        }
        return out
    }

    override suspend fun traerConsulta(consultaLocalId: Id, remotoId: String) {
        val r = api.get("consultas/$remotoId")
        val ahora = ahoraMillis()

        // Claves ya pendientes de envío: lo que el médico escribió gana siempre sobre lo remoto.
        val pendientes = hq.seccionValoresDirty().lista { "${it.consulta_id}|${it.seccion}|${it.campo}" }.toSet()
        val secciones = api.leerOpcional(SERIALIZER_SECCIONES, r, "secciones") ?: emptyMap()
        for ((seccion, campos) in secciones) {
            val categoria = CATEGORIAS[seccion]
            if (categoria != null) {
                guardarAntecedentes(consultaLocalId, categoria, campos, ahora)
                continue
            }
            for ((campo, valor) in campos) {
                if ("$consultaLocalId|$seccion|$campo" in pendientes) continue
                hq.upsertSeccionValor(SeccionValor(consultaLocalId, seccion, campo, valor).toRow(ahora, dirty = false))
            }
        }

        val examen = api.leerOpcional(SERIALIZER_CAMPOS, r, "examen_fisico")
        if (!examen.isNullOrEmpty() && !examenPendiente(consultaLocalId)) {
            val local = hq.examenFisicoDeConsulta(consultaLocalId).uno { it.toModel() }
            hq.upsertExamenFisico(desdeCampos(consultaLocalId, examen, local).toRow(ahora, dirty = false))
        }

        val registros = api.leerOpcional(ListSerializer(HcRegistroRemoto.serializer()), r, "registros").orEmpty()
        if (registros.isNotEmpty()) guardarRegistros(consultaLocalId, registros, ahora)
    }

    /**
     * Filas de las listas que vienen de pediatría. Se emparejan por el id remoto: la que ya está en el
     * dispositivo se actualiza y la que no, se crea. Lo que todavía no se pudo enviar no se toca.
     */
    private suspend fun guardarRegistros(consultaLocalId: Id, remotos: List<HcRegistroRemoto>, ahora: Long) {
        val consulta = hq.consultaPorId(consultaLocalId).uno { it.toModel() } ?: return
        val pendientes = hq.registrosDirty().lista { it.id }.toSet()
        val locales = hq.registrosDePacienteTodos(consulta.pacienteId, especialidad).lista { it.toModel() }
        for (remoto in remotos) {
            val local = locales.firstOrNull { it.remotoId == remoto.id && it.tipo == remoto.tipo }
            if (local != null && local.id in pendientes) continue
            val registro = (local ?: RegistroClinico(newId(), consulta.pacienteId, consultaLocalId, especialidad, remoto.tipo))
                .copy(
                    consultaId = local?.consultaId ?: consultaLocalId,
                    fecha = remoto.fecha?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: local?.fecha,
                    campos = remoto.campos,
                    remotoId = remoto.id,
                )
            hq.upsertRegistro(registro.toRow(ahora, dirty = false))
        }
    }

    /**
     * Antecedentes que vienen de pediatría. Llegan como si fueran una sección —un campo por clave, con
     * el valor `"<tilde>|<detalle>"`— pero en el dispositivo son filas de `antecedente`, del paciente
     * y no de la consulta. Lo que el médico todavía no pudo enviar gana: no se pisa.
     */
    private suspend fun guardarAntecedentes(consultaLocalId: Id, categoria: String, campos: Map<String, String>, ahora: Long) {
        val consulta = hq.consultaPorId(consultaLocalId).uno { it.toModel() } ?: return
        val pendientes = hq.antecedentesDirty().lista { "${it.paciente_id}|${it.categoria}|${it.clave}" }.toSet()
        val actuales = hq.antecedentesDeCategoria(consulta.pacienteId, especialidad, categoria).lista { it.toModel() }
        for ((clave, valor) in campos) {
            if ("${consulta.pacienteId}|$categoria|$clave" in pendientes) continue
            val partes = valor.split("|", limit = 2)
            val a = (actuales.firstOrNull { it.clave == clave }
                ?: Antecedente(newId(), consulta.pacienteId, especialidad, categoria, clave))
                .copy(flag = partes.getOrNull(0) == "1", detalle = partes.getOrNull(1).orEmpty(), consultaId = consultaLocalId)
            hq.upsertAntecedente(a.toRow(ahora, dirty = false))
        }
    }

    // ------------------------------------------------------------------
    // Pacientes
    // ------------------------------------------------------------------

    /**
     * Id del paciente en pediatría. Si todavía no se conoce, se lo resuelve mandando la ficha completa:
     * pediatría lo busca por su vínculo con turnos o por documento, y lo crea si hace falta.
     * El vínculo queda guardado para no repetir la llamada.
     */
    suspend fun resolverPaciente(pacienteId: Id): Long? {
        idConocido(pacienteId)?.let { return it }
        val p = pq.pacientePorId(pacienteId).uno { it.toModel() } ?: return null
        val cuerpo = HcPacienteRequest(
            dni = p.dni.trim(),
            nombre = p.nombre,
            apellido = p.apellido,
            pacienteIdTobb = TobbIds.numero(pacienteId) ?: idTobbDeExtras(pacienteId),
            fechaNacimiento = p.fechaNacimiento?.toString(),
            sexo = p.sexo?.name,
            telefono = p.telefono.ifBlank { null },
            mail = p.mail.ifBlank { null },
            domicilio = p.domicilio.ifBlank { null },
            localidad = p.localidad.ifBlank { null },
            obraSocial = p.obraSocial.ifBlank { null },
            numeroAfiliado = p.numeroAfiliado.ifBlank { null },
            obraSocialPlan = p.obraSocialPlan.ifBlank { null },
            nombrePadre = p.nombrePadre.ifBlank { null },
            nombreMadre = p.nombreMadre.ifBlank { null },
            cantidadHermanos = p.cantidadHermanos,
        )
        val r = api.post("pacientes/resolver", api.cuerpo(HcPacienteRequest.serializer(), cuerpo))
        val remoto = api.leer(HcPaciente.serializer(), r, "paciente")
        pq.upsertPacienteExtra(
            PacienteExtra(pacienteId, especialidad, EXTRA_HC_PACIENTE_ID, remoto.id.toString()).toRow(ahoraMillis(), dirty = true),
        )
        return remoto.id
    }

    private suspend fun idConocido(pacienteId: Id): Long? =
        pq.extrasDePaciente(pacienteId, especialidad).lista { it.toModel() }
            .firstOrNull { it.clave == EXTRA_HC_PACIENTE_ID }?.valor?.toLongOrNull()

    private suspend fun idTobbDeExtras(pacienteId: Id): Long? =
        pq.extrasDePaciente(pacienteId, "turnos").lista { it.toModel() }
            .firstOrNull { it.clave == "tobb_id" }?.valor?.toLongOrNull()

    // ------------------------------------------------------------------
    // Apoyo
    // ------------------------------------------------------------------

    private suspend fun esSucia(consultaId: Id): Boolean =
        hq.consultasDirty().lista { it.id }.contains(consultaId)

    private suspend fun examenPendiente(consultaId: Id): Boolean =
        hq.examenesFisicosDirty().lista { it.consulta_id }.contains(consultaId)

    /** Arma el examen físico local a partir de los campos que informa pediatría, conservando el resto. */
    private fun desdeCampos(consultaId: Id, campos: Map<String, String>, actual: ExamenFisico?): ExamenFisico {
        val base = actual ?: ExamenFisico(consultaId)
        return base.copy(
            consultaId = consultaId,
            peso = campos["peso"] ?: base.peso,
            pesoPercentil = campos["peso_percentil"] ?: base.pesoPercentil,
            talla = campos["talla"] ?: base.talla,
            tallaPercentil = campos["talla_percentil"] ?: base.tallaPercentil,
            imc = campos["imc"] ?: base.imc,
            imcPercentil = campos["imc_percentil"] ?: base.imcPercentil,
            perimetroCefalico = campos["perimetro_cefalico"] ?: base.perimetroCefalico,
            perimetroCefalicoPercentil = campos["perimetro_cefalico_percentil"] ?: base.perimetroCefalicoPercentil,
            tensionArterial = campos["tension_arterial"] ?: base.tensionArterial,
            ipd = campos["ipd"] ?: base.ipd,
            nota = campos["nota"] ?: base.nota,
        )
    }

    companion object {
        private val SERIALIZER_CAMPOS = MapSerializer(String.serializer(), String.serializer())
        private val SERIALIZER_SECCIONES = MapSerializer(String.serializer(), SERIALIZER_CAMPOS)
        private val SERIALIZER_IDS = ListSerializer(String.serializer())

        /** Clave de `paciente_extra` donde se guarda el id del paciente en la historia clínica. */
        const val EXTRA_HC_PACIENTE_ID = "hc_paciente_id"

        /** Secciones que en realidad son antecedentes del paciente: id de sección => categoría. */
        val CATEGORIAS = mapOf(
            SeccionesComunes.ANTECEDENTES_PERSONALES to "personales",
            SeccionesComunes.ANTECEDENTES_FAMILIARES to "familiares",
        )

        /** Campos del examen físico que pediatría conoce. Los demás no viajan: no los muestra. */
        val CAMPOS_EXAMEN = listOf(
            "peso", "peso_percentil", "talla", "talla_percentil", "imc", "imc_percentil",
            "perimetro_cefalico", "perimetro_cefalico_percentil", "tension_arterial", "ipd", "nota",
        )

        /** Examen físico local en el formato que espera la API. */
        fun aCampos(e: ExamenFisico): Map<String, String> = mapOf(
            "peso" to e.peso,
            "peso_percentil" to e.pesoPercentil,
            "talla" to e.talla,
            "talla_percentil" to e.tallaPercentil,
            "imc" to e.imc,
            "imc_percentil" to e.imcPercentil,
            "perimetro_cefalico" to e.perimetroCefalico,
            "perimetro_cefalico_percentil" to e.perimetroCefalicoPercentil,
            "tension_arterial" to e.tensionArterial,
            "ipd" to e.ipd,
            "nota" to e.nota,
        )
    }
}
