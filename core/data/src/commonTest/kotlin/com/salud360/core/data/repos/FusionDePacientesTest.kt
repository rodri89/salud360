package com.salud360.core.data.repos

import com.salud360.core.model.pacientes.MedicoPaciente
import com.salud360.core.model.pacientes.Paciente
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Qué hacer con la ficha local cuando turnos contesta el alta de un paciente (`POST pacientes`), y cómo se fusionan
 * la ficha y los vínculos con médicos. Todo puro: ni red, ni base. Los datos son inventados.
 */
class FusionDePacientesTest {

    // ---- decidirTrasAlta ----

    @Test
    fun si_turnos_lo_creo_con_este_alta_se_conserva_la_ficha_local_y_se_anota_el_id() {
        val d = decidirTrasAlta(idLocal = "uuid-1", idRemoto = "tobb-p7", creado = true, remotaYaEnElDispositivo = false)

        assertEquals(DecisionDeAlta(AccionTrasAlta.ANOTAR_ID_REMOTO, yaExistia = false), d)
    }

    @Test
    fun si_el_dni_ya_existia_en_turnos_la_ficha_local_se_reemplaza_por_la_de_turnos() {
        val d = decidirTrasAlta(idLocal = "uuid-1", idRemoto = "tobb-p7", creado = false, remotaYaEnElDispositivo = false)

        assertEquals(DecisionDeAlta(AccionTrasAlta.REEMPLAZAR_LOCAL, yaExistia = true), d)
    }

    @Test
    fun si_la_ficha_de_turnos_ya_estaba_en_el_dispositivo_se_fusiona_aunque_turnos_diga_creado() {
        val d = decidirTrasAlta(idLocal = "uuid-1", idRemoto = "tobb-p7", creado = true, remotaYaEnElDispositivo = true)

        assertEquals(DecisionDeAlta(AccionTrasAlta.REEMPLAZAR_LOCAL, yaExistia = false), d, "turnos dice que lo creó: no se avisa que ya existía")
    }

    @Test
    fun si_la_ficha_local_ya_tiene_el_id_de_turnos_solo_se_actualiza() {
        val existia = decidirTrasAlta(idLocal = "tobb-p7", idRemoto = "tobb-p7", creado = false, remotaYaEnElDispositivo = true)
        val nuevo = decidirTrasAlta(idLocal = "tobb-p7", idRemoto = "tobb-p7", creado = true, remotaYaEnElDispositivo = true)

        assertEquals(DecisionDeAlta(AccionTrasAlta.ACTUALIZAR_LOCAL, yaExistia = true), existia)
        assertEquals(DecisionDeAlta(AccionTrasAlta.ACTUALIZAR_LOCAL, yaExistia = false), nuevo)
    }

    // ---- fusionarFicha ----

    @Test
    fun sin_ficha_previa_queda_la_de_turnos_tal_cual() {
        val nuevo = Paciente("tobb-p7", "30111222", "ANA", "PRUEBA", telefono = "000-1111")

        assertEquals(nuevo, fusionarFicha(null, nuevo))
    }

    @Test
    fun los_datos_de_turnos_mandan_sobre_los_locales() {
        val local = Paciente("uuid-1", "30111222", "ANA", "PRUEBA", telefono = "111", mail = "viejo@ejemplo.test", obraSocial = "OS LOCAL")
        val turnos = Paciente("tobb-p7", "30111222", "ANA MARIA", "PRUEBA", telefono = "222", mail = "nuevo@ejemplo.test", obraSocial = "OS TURNOS")

        val r = fusionarFicha(local, turnos)

        assertEquals("ANA MARIA", r.nombre)
        assertEquals("222", r.telefono)
        assertEquals("nuevo@ejemplo.test", r.mail)
        assertEquals("OS TURNOS", r.obraSocial)
    }

    @Test
    fun un_dato_que_turnos_trae_vacio_no_pisa_el_local() {
        val local = Paciente(
            "uuid-1", "30111222", "ANA", "PRUEBA", telefono = "111", mail = "ana@ejemplo.test", domicilio = "Calle 1", localidad = "Ciudad",
            fechaNacimiento = LocalDate(2020, 5, 17), obraSocial = "OS", numeroAfiliado = "9", obraSocialPlan = "PLAN", nota = "nota local",
        )
        val turnos = Paciente("tobb-p7", "30111222", "ANA", "PRUEBA")

        val r = fusionarFicha(local, turnos)

        assertEquals("111", r.telefono)
        assertEquals("ana@ejemplo.test", r.mail)
        assertEquals("Calle 1", r.domicilio)
        assertEquals("Ciudad", r.localidad)
        assertEquals(LocalDate(2020, 5, 17), r.fechaNacimiento)
        assertEquals("OS", r.obraSocial)
        assertEquals("9", r.numeroAfiliado)
        assertEquals("PLAN", r.obraSocialPlan)
        assertEquals("nota local", r.nota)
    }

    @Test
    fun la_ficha_fusionada_toma_el_id_de_turnos_y_conserva_los_datos_que_turnos_no_conoce() {
        val local = Paciente("uuid-1", "30111222", "ANA", "PRUEBA", nombreMadre = "MADRE DE PRUEBA", nacionalidad = "ARGENTINA")
        val turnos = Paciente("tobb-p7", "30111222", "ANA", "PRUEBA", telefono = "222")

        val r = fusionarFicha(local, turnos)

        assertEquals("tobb-p7", r.id)
        assertEquals("MADRE DE PRUEBA", r.nombreMadre)
        assertEquals("ARGENTINA", r.nacionalidad)
        assertEquals("222", r.telefono)
    }

    // ---- fusionarVinculos ----

    @Test
    fun los_vinculos_de_la_ficha_local_pasan_al_id_de_turnos() {
        val r = fusionarVinculos(
            locales = listOf(MedicoPaciente("tobb-m1", "uuid-1"), MedicoPaciente("tobb-m2", "uuid-1", bloqueado = true)),
            remotos = emptyList(),
            idRemoto = "tobb-p7",
        )

        assertEquals(
            listOf(MedicoPaciente("tobb-m1", "tobb-p7"), MedicoPaciente("tobb-m2", "tobb-p7", bloqueado = true)),
            r.sortedBy { it.medicoId },
        )
    }

    @Test
    fun si_el_medico_ya_tenia_el_vinculo_con_turnos_queda_uno_solo_y_activo() {
        val r = fusionarVinculos(
            locales = listOf(MedicoPaciente("tobb-m1", "uuid-1", activo = true)),
            remotos = listOf(MedicoPaciente("tobb-m1", "tobb-p7", activo = false, bloqueado = true)),
            idRemoto = "tobb-p7",
        )

        assertEquals(listOf(MedicoPaciente("tobb-m1", "tobb-p7", activo = true, bloqueado = true)), r, "el médico acaba de darlo de alta: vuelve a estar activo; el bloqueo es el que ya tenía turnos")
    }

    @Test
    fun los_vinculos_de_otros_medicos_que_ya_estaban_con_turnos_se_conservan() {
        val r = fusionarVinculos(
            locales = listOf(MedicoPaciente("tobb-m1", "uuid-1")),
            remotos = listOf(MedicoPaciente("tobb-m2", "tobb-p7")),
            idRemoto = "tobb-p7",
        )

        assertEquals(setOf("tobb-m1", "tobb-m2"), r.map { it.medicoId }.toSet())
        assertTrue(r.all { it.pacienteId == "tobb-p7" })
    }

    @Test
    fun un_vinculo_inactivo_local_no_reactiva_el_remoto() {
        val r = fusionarVinculos(
            locales = listOf(MedicoPaciente("tobb-m1", "uuid-1", activo = false)),
            remotos = listOf(MedicoPaciente("tobb-m1", "tobb-p7", activo = true)),
            idRemoto = "tobb-p7",
        )

        assertEquals(listOf(MedicoPaciente("tobb-m1", "tobb-p7", activo = true)), r)
    }

    // ---- avisos ----

    @Test
    fun el_aviso_de_paciente_existente_es_claro() {
        assertEquals("Ese paciente ya existía en el sistema: se asoció a tu lista.", avisoDePacienteYaExistia())
    }
}
