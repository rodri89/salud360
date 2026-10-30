package com.salud360.core.data.network.hc

/**
 * Respuestas de ejemplo de las tres rutas de lectura de hclinica (`md/API_SALUD360_CLINICA.md`, sección «Consultas (solo
 * lectura)»), con la forma que contesta el servidor de verdad (E4.2 a E4.4) y datos inventados «Prueba Salud360». Los ids
 * son de mentira. Sin el `{"ok": true` de adelante: cada uno es el contenido del objeto, para poder agregarle o sacarle
 * claves en los tests.
 */
internal object EjemplosConsultasClinica {
    const val CABECERA_A = """{"id": 7003, "paciente_id": 5001, "medico_id": 9, "medico_nombre": "Médico Prueba Salud360",
        "tipo": "control", "tipo_numero": 1, "fecha": "2026-03-10", "created_at": "2026-03-10 10:00:00", "estado": "CERRADA",
        "edad_mostrar": "45 años", "propia": true, "updated_at": "2026-03-10 10:30:00"}"""

    const val CABECERA_B = """{"id": 7004, "paciente_id": 5001, "medico_id": 14, "medico_nombre": "Otro Médico Prueba Salud360",
        "tipo": "telemedicina", "tipo_numero": 4, "fecha": "2026-04-14", "created_at": "2026-04-14 15:30:00", "estado": "CERRADA",
        "edad_mostrar": "", "propia": false, "updated_at": "2026-04-14 16:00:00"}"""

    const val CABECERA_C = """{"id": 7005, "paciente_id": 5001, "medico_id": 9, "medico_nombre": "Médico Prueba Salud360",
        "tipo": "control", "tipo_numero": 1, "fecha": "2026-09-01", "created_at": "2026-09-01 09:00:00", "estado": "ABIERTA",
        "edad_mostrar": "", "propia": true, "updated_at": "2026-09-01 09:20:00"}"""

    /** `GET consultas?paciente_id=…&limite=3`: la primera página de las cuatro consultas listables. */
    const val LISTA = """{"ok": true,
        "consultas": [$CABECERA_C, $CABECERA_B, $CABECERA_A],
        "total": 4, "desde": 0, "hay_mas": 1,
        "omitidas": {"foto": 1, "otro": 1, "sin_fecha": 0}}"""

    /** `GET consultas/{id}` de una consulta cerrada con todas las secciones. */
    const val CONSULTA_COMPLETA = """{"ok": true, "consulta": $CABECERA_A,
        "secciones": {
            "motivo_consulta": {"texto": "Prueba Salud360 motivo (a)"},
            "datos_subjetivos": {"texto": "Prueba Salud360 datos subjetivos (a)"},
            "datos_objetivos": {"texto": "Prueba Salud360 datos objetivos (a)"},
            "conductas": {"texto": "Prueba Salud360 conductas (a)"},
            "nota": {"texto": "Prueba Salud360 nota (a)"},
            "habitos": {"texto": "Prueba Salud360 hábitos (a): tabaco, alcohol, ejercicio"},
            "diuresis_catarsis": {"texto": "Prueba Salud360 diuresis y catarsis (a)"},
            "somnia": {"texto": "Prueba Salud360 somnia (a)"},
            "alimentacion": {"hiposodica": "1|Prueba Salud360 hiposódica (a)", "diabetes": "0|Prueba Salud360 sin diabetes",
                             "celiaco": "1|Prueba Salud360 celíaco detalle (a)"},
            "serologias": {"igm_hav": "SI", "ags_hbv": "NO", "hiv": "NO"},
            "child_pugh": {"ascitis": "2", "bilirrubina": "1", "albumina": "3", "tp": "2", "encefalopatia": "1"}},
        "examen_fisico": {"peso": "70.5", "talla": "170", "tension_arterial": "120/80", "imc": "24.4",
                          "nota": "Prueba Salud360 examen físico (a)"},
        "no_leidas": []}"""

    /** Una consulta abierta con poco cargado; el examen físico trae un valor «sin cargar» que el servidor sí manda. */
    const val CONSULTA_ABIERTA = """{"ok": true, "consulta": $CABECERA_C,
        "secciones": {"motivo_consulta": {"texto": "Prueba Salud360 motivo (c)"}, "conductas": {"texto": "Prueba Salud360 conductas (c)"},
                      "nota": {"texto": "Prueba Salud360 nota (c) en curso"}},
        "examen_fisico": {"talla": "0.0", "imc": "0"}, "no_leidas": []}"""

    /** Una consulta con un PDF adjunto: nada de examen y el aviso de lo que la API no devuelve. */
    const val CONSULTA_CON_PDF = """{"ok": true, "consulta": $CABECERA_A,
        "secciones": {"motivo_consulta": {"texto": "Prueba Salud360 motivo (f)"}},
        "examen_fisico": {}, "no_leidas": ["documentos"]}"""

    /** `GET pacientes/{id}/historia`, recortado como en el md. */
    const val HISTORIA = """{"ok": true, "paciente_id": 5001,
        "antecedentes_familiares": {"hta": {"flag": 1, "detalle": "Prueba Salud360 hta detalle"}, "dbt_ir": {"flag": 1},
                                    "otro": {"flag": 1, "detalle": "Prueba Salud360 otro af"},
                                    "nota": "Prueba Salud360 nota antecedentes"},
        "antecedentes_familiares_perfil4": {"nota": "Prueba Salud360 nota del perfil 4"},
        "laboratorios": {
            "laboratorio_general": [{"id": 703, "consulta_id": 7003, "fecha": "2026-03-10",
                                     "valores": {"hto": "41", "hb": "13.9", "alb_mina": "4.1", "f_sforo": "3.5",
                                                 "otro": "Prueba Salud360 otro lab 2"}}],
            "laboratorio_reducido": [{"id": 19, "consulta_id": 7003, "fecha": "2026-03-10",
                                      "valores": {"hemograma": "normal", "glucemia": "97", "otros": "Prueba Salud360 otro reducido"}}],
            "peso": [{"id": 25, "consulta_id": 7003, "fecha": "2026-03-10", "valores": {"peso": "70.5"}}]},
        "estudios": {
            "eco_abdominal": [{"id": 1801, "consulta_id": 7003, "fecha": "2026-02-01", "detalle": "Prueba Salud360 ecografía abdominal (a)"},
                              {"id": 1802, "consulta_id": 7006, "fecha": null, "detalle": "Prueba Salud360 ecografía abdominal (f) sin fecha"}],
            "veda": [{"id": 1797, "consulta_id": 7003, "fecha": "2026-02-02", "detalle": "Prueba Salud360 videoendoscopia (a)"}],
            "biopsia_hepatica": [{"id": 1797, "consulta_id": 7003, "fecha": "2026-02-03", "detalle": "Prueba Salud360 biopsia hepática (a)"}],
            "estudios_cardiologicos": [],
            "tc_rmn": [{"id": 1797, "consulta_id": 7003, "fecha": "2026-02-04", "detalle": "Prueba Salud360 TC/RMN (a)"}]},
        "motivos": [{"id": 1859, "consulta_id": 7005, "fecha": "2026-09-01", "texto": "Prueba Salud360 motivo (c)"},
                    {"id": 1857, "consulta_id": 7003, "fecha": "2026-03-10", "texto": "Prueba Salud360 motivo (a)"}],
        "examenes_complementarios": [{"id": 22, "consulta_id": 7003, "fecha": "2026-03-10", "solicito": "Prueba Salud360 examen solicitado 1",
                                      "respuesta": "Prueba Salud360 examen respondido 1", "numero": 1},
                                     {"id": 23, "consulta_id": 7003, "fecha": "2026-03-10", "solicito": "Prueba Salud360 examen solicitado 2",
                                      "numero": 2}],
        "interconsultas": [{"id": 14, "consulta_id": 7003, "fecha": "2026-03-10", "especialista": "Prueba Salud360 especialista 1",
                            "solicito": "Prueba Salud360 interconsulta solicitada 1", "respuesta": "Prueba Salud360 interconsulta respondida 1",
                            "numero": 1}],
        "pendientes": {"id": 1744, "texto": "Prueba Salud360 pendiente"},
        "omitidas": {"laboratorio_sin_fecha": 1}}"""

    /** Un paciente sin nada cargado: todo vacío o `null`, pero con todas las claves que el servidor siempre manda. */
    const val HISTORIA_VACIA = """{"ok": true, "paciente_id": 5002,
        "antecedentes_familiares": null,
        "antecedentes_familiares_perfil4": null,
        "laboratorios": {"laboratorio_general": [], "laboratorio_reducido": [], "peso": []},
        "estudios": {"eco_abdominal": [], "veda": [], "biopsia_hepatica": [], "estudios_cardiologicos": [], "tc_rmn": []},
        "motivos": [], "examenes_complementarios": [], "interconsultas": [],
        "pendientes": null, "omitidas": {"laboratorio_sin_fecha": 0}}"""
}
