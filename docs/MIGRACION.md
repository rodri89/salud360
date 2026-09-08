# Migración de datos desde los proyectos Laravel

Este documento indica dónde queda cada tabla de los proyectos originales en el esquema unificado
(`core/database/src/commonMain/sqldelight/com/salud360/core/database/*.sq`). Los identificadores
pasan a ser UUID (texto); se recomienda conservar el id original en una columna auxiliar de un
script de migración para poder cruzar datos.

## Usuarios y roles (todos los proyectos)

| Origen | Destino |
|---|---|
| `users` + `usuario_tipo` / `tipo_usuario` (1 admin, 2 médico, 3 secretaria) | `usuario` (`rol` = ADMIN / MEDICO / SECRETARIA). Una sola cuenta por mail, aunque el médico esté en varios proyectos |
| `medicos` (turnos) + `users.medico_id_tobb` (HC) | `medico` (un registro por médico; `especialidades_hc` lista las HC habilitadas; `tiene_turnos` si estaba en turnosonlinebb) |
| `secretarias`, `secretaria_consultorios` (turnos), `medico_secretarias` (HC) | `secretaria` (`consultorio_ids`, `medico_ids`) |
| `medico_licencias` / `licencias` | `licencia` |
| `especialidads` (turnos) | `especialidad` (`codigo_hc` enlaza con la historia clínica) |
| `consultorios` | `consultorio` |
| `medico_infos` (paciente/consulta abiertos) | no se migra: era estado de navegación |
| `modulos` / `user_modulos` (HC: modo de vacunas, secciones visibles) | `medico_preferencia` |
| `modulos` / `modulo_medicos` (turnos, 1-12) | `medico_modulo` |
| `medico_configs` + `medico_primer_controls` | `config_agenda` |

## Pacientes

| Origen | Destino |
|---|---|
| `pacientes` de cada proyecto | `paciente` (unión de campos; se deduplica por DNI) |
| campos pediátricos (`nombre_padre`, `nombre_madre`, `cantidad_hermanos`), desarrollo infantil (`madre`, `padre`, `telefono_madre`, `telefono_padre`, `nacionalidad`), endocrinología (`nombre_familiar`, `telefono_familiar`), hematología (`*_opcional`) | columnas de `paciente` |
| `medico_pacientes` (ambos esquemas) | `medico_paciente` |
| `paciente_secretarias` | no se migra (se deduce de `secretaria.consultorio_ids`) |
| `familigramas` | `archivo` con `seccion = 'familigrama'` (por paciente) |
| `screenings` | `registro_clinico` tipo `screening` (por paciente) |

## Historia clínica

| Origen | Destino |
|---|---|
| `consultas` (`tipo_consulta` numérico) | `consulta` con `especialidad` y `tipo` textual (ver tabla de tipos abajo) |
| tablas "descripción simple" (`motivo_consultas`, `conductas`, `notas`, `plans`, `observaciones`, `datos_subjetivos`, `datos_objetivos`, `resumens`, `medicacion_*`, `evolucions`, `escolaridads`, `pantallas`, `habitos`, `somnias`, `catarses`, `menarcas`, ...) | `seccion_valor` con `seccion = <id de sección>` y `campo = 'texto'` |
| tablas de formulario (`alimentacions`, `motivo_consultas` cardio, `soplos`, `ecgs`, `conductas` cardio, `l_h_*`, `g_m_*`, `p_m_*`, escalas de desarrollo infantil, `datos_consultas` gineco, ...) | `seccion_valor` con `campo` = nombre de columna original |
| `examen_fisicos` (peso, talla, TA, IMC, PC, CA, IPD, percentilos, tiroides) | `examen_fisico` (tiroides → `seccion_valor` de `examen_fisico`) |
| `antecedentes_personales`, `antecedentes_familiares`, `antecedente_*` (flags + detalle) | `antecedente` (`categoria` personales/familiares/estudios_realizados, `clave` = columna) |
| antecedentes de texto acumulativo (hematología) | `antecedente` con `clave = 'texto'` |
| `antecedentes_perinatales`, `antecedente_ginecologicos`, `antecedente_obstetricos`, embarazo/parto de desarrollo infantil, `datos_familiares` | `paciente_extra` (`clave` = `<seccion>.<campo>`) |
| `examenes_complementarios`, `interconsultas`, `internaciones`, `ecografias`, `mamografias`, `estudios`, `estudio_*` (repetibles) | `registro_clinico` (tipo según `RegistroDef`; campos en JSON) |
| `laboratorio_generals`, `labs`, `valores_glucosas`, `laboratorio_hormonals`, `laboratorio_embarazo*`, `trombofilias`, `control_anticoagulacions`, `transtorno_hemorragiparos`, `planilla_control_m_m_s`, `pesos`, `laboratorio_general2s` | `laboratorio` (`tipo` = id del laboratorio en la definición; valores JSON con la clave normalizada del analito) |
| `consulta_fotos`, `*_fotos`, `fotos`, `consulta_pdfs`, `grabaciones_audio` | `archivo` (subir los archivos físicos al servidor con `POST /archivos/{id}`) |
| `aux_pendientes` / `pendientes` | `pendiente` |
| `diagnosticos`, `consulta_diagnostico`, `enfermedades`, `medico_enfermedades`, `paciente_enfermedades` | `diagnostico` + `consulta_diagnostico` |
| `interconsultores` | `interconsultor` |
| `vacunas_pacientes`, `vacuna_antigripals`, `vacunas_dos` | `vacuna_aplicada` (calendario, antigripal y "otras") |
| `desarrollo_madurativo_pacientes` | `seccion_valor` de `desarrollo` (`campo` = clave del hito) |
| `paps`, `pap_resultados`, `examen_fisicos` (dibujo hematología) | `seccion_valor` (`trazos` JSON) — los dibujos históricos se pueden adjuntar como imagen en `archivo` |

### Tipos de consulta

| Proyecto | `tipo_consulta` | `consulta.tipo` |
|---|---|---|
| pediatría | 1 control, 2 enfermedad, 3 foto, 4 telemedicina, 5 prenatal, 6 lactancia | `control`, `enfermedad`, `foto`, `telemedicina`, `prenatal`, `lactancia` |
| clínica / hepatología | 1 consulta, 2 enfermedad, 3 foto, 4 telemedicina | `control`, `enfermedad`, `foto`, `telemedicina` |
| gineco | 1 ginecológico, 2 obstétrico, 3 telemedicina, 4 foto | `ginecologico`, `obstetrico`, `telemedicina`, `foto` |
| endocrinología | 1 consulta, 3 foto, 4 telemedicina | `control`, `foto`, `telemedicina` |
| hematología | 1 control, 2 digitalizada, 3 telemedicina (+ flags LH/LNH/GM/SMD) | `control`, `foto`, `telemedicina`, `lh`, `lnh`, `gm`, `smd` |
| cardiología | 1 consulta, 2-6 estudios | `control`, `ecodoppler`, `ecocardiograma`, `cine`, `electro`, `peg` |
| desarrollo infantil | 1 consulta, 2 foto, 3 escalas | `control`, `foto`, `escalas` |

## Turnos (turnosonlinebb)

| Origen | Destino |
|---|---|
| `horario_medicos` | `horario` (`dia` 1-7, `horario` HH:MM, vigencia) |
| `horario_medico_d_h_s` | `horario_rango` |
| `fechas_agregadas` + `horarios_medicos_agregados` | `fecha_agregada` (horarios en una sola columna) |
| `turno_registrados` | `turno` (`estado` ACTIVO/CANCELADO/BLOQUEADO; `asistencia`; `fechaTurno` texto → `fecha` ISO) |
| `feriados` | `feriado` |
| `obra_socials`, `obra_social_medicos` | `obra_social`, `obra_social_medico` |
| `medico_mensajes_especiales` | `mensaje_especial` |
| `recetas`, `receta_estados`, `paciente_recetas` | `receta` (`estado` 1-7; archivos en `archivos`) |
| videollamadas, MercadoPago, Google Calendar, OneSignal | fuera del alcance de la app interna; siguen en la web pública de turnosonlinebb. `turno.pagado` e `importe_reserva` se conservan |

## Procedimiento sugerido

1. Exportar cada base MySQL a CSV/JSON.
2. Escribir un script (Kotlin/JVM con `core:database` y `core:data`, o Python) que cree los
   registros con los mapeos de arriba usando los mismos modelos y `INSERT OR REPLACE`.
3. Insertar directamente en la base del servidor (`salud360-server.db`) con `dirty = 0` y agregar
   una fila en `cambios` por registro para que los dispositivos los descarguen, o bien correr el
   script como cliente contra `POST /sync/push`.
4. Copiar los archivos de `public/img/...` al directorio de adjuntos del servidor con el id nuevo.
