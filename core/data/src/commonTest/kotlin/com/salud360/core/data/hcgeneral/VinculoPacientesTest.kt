package com.salud360.core.data.hcgeneral

import com.salud360.core.data.network.TobbException
import com.salud360.core.data.network.hc.HcPacienteCandidato
import com.salud360.core.data.network.hc.HcPacienteFicha
import com.salud360.core.model.especialidad.CodigosHc
import com.salud360.core.model.pacientes.Paciente
import com.salud360.core.model.pacientes.Sexo
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Lógica pura del vínculo de pacientes (spec 002, RF-8, RF-11 a RF-13, RF-36, RF-37). La tabla de documentos es la
 * misma que `PacienteVinculoReglasTest` de hclinica (los casos que no son texto allá no existen acá). Pacientes
 * inventados.
 */
class VinculoPacientesTest {

    // RF-12: documento

    @Test
    fun documento_con_puntos_y_espacios_vale() {
        assertEquals("30111222", documentoParaHc("30.111.222"))
        assertEquals("30111222", documentoParaHc(" 30 111 222 "))
        assertEquals("30111222", documentoParaHc("30111222"))
        assertEquals("1000", documentoParaHc("1000"))
        assertEquals("12345678", documentoParaHc("12.345.678"))
        assertEquals("30111222", documentoParaHc("30 111 222"))
    }

    @Test
    fun documentos_invalidos() {
        val invalidos = listOf(
            "-12345678", "12345678A", "0", "1", "999", "99999", "2147483648", "99999999999", "", "   ",
            ".", "12-345-678", "+12345678", "1e5",
        )
        for (dni in invalidos) assertNull(documentoParaHc(dni), "debería ser inválido: '$dni'")
    }

    @Test
    fun el_maximo_de_la_web_vale() {
        assertEquals("2147483647", documentoParaHc("2147483647"))
        assertEquals("2147483647", documentoParaHc("2.147.483.647"))
        assertEquals("2147483647", documentoParaHc("002147483647"))
    }

    @Test
    fun mensaje_con_el_documento_como_lo_escribio_el_medico() {
        assertEquals(
            "El documento «12.345.67A» no sirve para la historia clínica: corregilo en la ficha del paciente",
            mensajeDocumentoInvalido("12.345.67A"),
        )
    }

    // RF-13: pedido

    private val paciente = Paciente(
        id = "tobb-p7001", dni = " 30.111.222 ", nombre = "PRUEBA", apellido = "SALUD360",
        sexo = Sexo.F, fechaNacimiento = LocalDate(2015, 3, 4),
        telefono = "  ", mail = "", domicilio = "CALLE INVENTADA 1", localidad = "",
        obraSocial = "", numeroAfiliado = " ", obraSocialPlan = "",
        nombrePadre = "", nombreMadre = "MADRE INVENTADA", cantidadHermanos = null,
        nombreFamiliar = "FAMILIAR INVENTADO", telefonoFamiliar = "",
    )

    @Test
    fun pedido_con_vacios_va_con_null() {
        val p = pedidoResolver(paciente, 7001)
        assertEquals("30.111.222", p.dni)
        assertEquals("PRUEBA", p.nombre)
        assertEquals("SALUD360", p.apellido)
        assertEquals(7001, p.pacienteIdTobb)
        assertEquals("2015-03-04", p.fechaNacimiento)
        assertEquals("F", p.sexo)
        assertEquals("CALLE INVENTADA 1", p.domicilio)
        assertEquals("MADRE INVENTADA", p.nombreMadre)
        assertEquals("FAMILIAR INVENTADO", p.nombreFamiliar)
        for (vacio in listOf(
            p.telefono, p.mail, p.localidad, p.obraSocial, p.numeroAfiliado, p.obraSocialPlan, p.nombrePadre,
            p.telefonoFamiliar,
        )) assertNull(vacio)
        assertNull(p.cantidadHermanos)
    }

    @Test
    fun pedido_sin_id_de_turnos_ni_fecha_ni_sexo() {
        val p = pedidoResolver(paciente.copy(sexo = null, fechaNacimiento = null), null)
        assertNull(p.pacienteIdTobb)
        assertNull(p.fechaNacimiento)
        assertNull(p.sexo)
    }

    // RF-11, RF-12, RF-36: errores de resolver

    private val candidatas = listOf(
        HcPacienteCandidato(501, "PRUEBA UNO", "SALUD360", "30111222", "2015-03-04", 4),
        HcPacienteCandidato(502, "PRUEBA DOS", "SALUD360", "30111222", null, 0),
    )

    @Test
    fun ambiguo_da_las_candidatas_para_elegir() {
        val e = TobbException(409, "paciente_ambiguo", "Elegí cuál corresponde.", candidatos = candidatas)
        assertEquals(ResultadoVinculo.ElegirFicha(candidatas), resultadoDe(e))
    }

    @Test
    fun ya_vinculado_da_el_texto_de_rf36() {
        val e = TobbException(409, "paciente_ya_vinculado", "Mensaje de la web")
        assertEquals(
            ResultadoVinculo.FichaDeOtroPaciente(
                "La ficha de la web con ese documento ya corresponde a otro paciente de turnosonlinebb: avisale al administrador",
            ),
            resultadoDe(e),
        )
    }

    @Test
    fun dni_invalido_da_documento_invalido_con_el_mensaje() {
        val mensaje = mensajeDocumentoInvalido("999")
        assertEquals(ResultadoVinculo.DocumentoInvalido(mensaje), resultadoDe(TobbException(422, "dni_invalido", mensaje)))
    }

    @Test
    fun ficha_no_elegible() {
        assertEquals(ResultadoVinculo.FichaNoElegible, resultadoDe(TobbException(422, "ficha_no_elegible", "No corresponde.")))
    }

    @Test
    fun otro_codigo_no_se_pudo_con_la_misma_excepcion() {
        for (e in listOf(
            TobbException(503, "ocupado", "Ocupado"),
            TobbException(0, "red", "Sin conexión"),
            TobbException(403, "licencia_vencida", "Licencia vencida"),
            TobbException(500, null, "Error"),
        )) {
            val r = resultadoDe(e)
            assertEquals(ResultadoVinculo.NoSePudo::class, r::class)
            assertSame(e, (r as ResultadoVinculo.NoSePudo).error)
        }
    }

    // RF-8: qué carteras se traen

    @Test
    fun hepatologia_cuenta_como_la_hc_general() {
        val conApi = setOf("pediatria", CodigosHc.GENERAL)
        assertEquals(setOf("pediatria", CodigosHc.GENERAL), historiasConCartera(listOf("pediatria", "hepatologia"), conApi))
        assertEquals(setOf(CodigosHc.GENERAL), historiasConCartera(listOf("hepatologia", CodigosHc.GENERAL), conApi))
    }

    @Test
    fun sin_api_queda_afuera() {
        assertEquals(setOf("pediatria"), historiasConCartera(listOf("pediatria", "hepatologia", "hematologia"), setOf("pediatria")))
        assertEquals(emptySet(), historiasConCartera(listOf("hematologia"), setOf("pediatria", CodigosHc.GENERAL)))
    }

    // RF-37: id local de la ficha

    @Test
    fun id_local_con_y_sin_vinculo_con_turnos() {
        assertEquals("tobb-p7001", idLocalDeFicha(CodigosHc.GENERAL, HcPacienteFicha(id = 501, pacienteIdTobb = 7001)))
        assertEquals("tobb-p7001", idLocalDeFicha("pediatria", HcPacienteFicha(id = 77, pacienteIdTobb = 7001)))
        assertEquals("hc-clinica-p501", idLocalDeFicha(CodigosHc.GENERAL, HcPacienteFicha(id = 501)))
        assertEquals("hc-pediatria-p501", idLocalDeFicha("pediatria", HcPacienteFicha(id = 501)))
    }

    @Test
    fun mismo_documento_sin_vinculo_no_se_empareja() {
        val a = HcPacienteFicha(id = 501, dni = "30111222", nombre = "PRUEBA", apellido = "SALUD360")
        val b = HcPacienteFicha(id = 77, dni = "30111222", nombre = "PRUEBA", apellido = "SALUD360")
        assertNotEquals(idLocalDeFicha(CodigosHc.GENERAL, a), idLocalDeFicha("pediatria", b))
        assertNotEquals(idLocalDeFicha(CodigosHc.GENERAL, a), idLocalDeFicha(CodigosHc.GENERAL, a.copy(id = 502)))
    }
}
