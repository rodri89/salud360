# Arquitectura de Salud 360

## Idea central

Los siete proyectos Laravel repetían el mismo esqueleto (usuarios, médicos, secretarias, pacientes,
consultas, `medico_infos`, licencias, fotos, pendientes) y variaban solo en las secciones clínicas.
Salud 360 separa esas dos cosas:

1. **Núcleo compartido** (`core/*`): un solo esquema para pacientes, consultas, agenda y sincronización.
2. **Especialidades declarativas** (`especialidades/*`): cada historia clínica es un objeto
   `EspecialidadDefinition` que describe tipos de consulta, pestañas, secciones y campos.
   El módulo `features/hc` renderiza cualquier definición sin código específico.

Así, la sección "Motivo de consulta" o "Exámenes complementarios" existe una sola vez y la usan
todas las especialidades; y "Calendario de vacunas" existe solo en pediatría.

## Cómo se guarda una consulta

En vez de una tabla por sección (más de 200 tablas sumando los proyectos), hay pocas tablas genéricas:

| Tabla | Qué guarda |
|---|---|
| `consulta` | cabecera: paciente, médico, especialidad, tipo, fecha, estado |
| `seccion_valor` | cualquier campo de cualquier sección: `(consulta, seccion, campo) -> valor` |
| `examen_fisico` | peso, talla, IMC, TA, PC, CA, FC, temperatura, saturación, IPD, nota (tipado porque se grafica) |
| `antecedente` | antecedentes por paciente y categoría: `flag + detalle` |
| `paciente_extra` | datos del paciente propios de una especialidad (antecedentes perinatales, ginecológicos...) |
| `registro_clinico` | filas repetibles con campos JSON: interconsultas, estudios, internaciones, hermanos... |
| `laboratorio` | una carga por fecha con valores JSON (tablas longitudinales de todas las especialidades) |
| `archivo` | fotos, PDF y audios (ruta local + URL remota) |
| `pendiente`, `diagnostico`, `consulta_diagnostico`, `interconsultor`, `vacuna_aplicada`, `medico_preferencia` | funciones transversales |

Cada tabla tiene `updated_at`, `deleted` y `dirty` para la sincronización.

## Tipos de sección

`TipoSeccion` define cómo se renderiza y dónde se guarda cada sección:

- `FORM`, `TEXTO`: campos en `seccion_valor` (por consulta)
- `FORM_PACIENTE`: campos en `paciente_extra` (por paciente)
- `EXAMEN_FISICO`: tabla `examen_fisico`
- `ANTECEDENTES`: tabla `antecedente` (casillas con detalle o texto acumulativo)
- `LABORATORIO`: tabla `laboratorio` con los analitos de un `LaboratorioDef`
- `REGISTROS`: tabla `registro_clinico` con los campos de un `RegistroDef`
- `ARCHIVOS`, `AUDIO`: tabla `archivo`
- `DIAGNOSTICOS`: catálogo del médico
- `CUSTOM`: composable propio registrado por el módulo de especialidad (`renderKey`)

## Agregar una especialidad nueva

1. Crear `especialidades/<nombre>/` copiando el `build.gradle.kts` de otra.
2. Definir un `EspecialidadDefinition` (ver `especialidades/endocrinologia` como ejemplo simple
   y `pediatria` como ejemplo con secciones a medida).
3. Exportar un `EspecialidadContribution` y agregarlo a la lista `especialidades` en
   `composeApp/src/commonMain/kotlin/com/salud360/app/AppDi.kt`.
4. Habilitarla al médico desde Administración → Médicos.

No hace falta tocar la base de datos ni el servidor.

## Roles y acceso

- **Médico**: ve su agenda (si `tieneTurnos`) y sus pacientes; abre la historia clínica de cada
  especialidad habilitada (`medico.especialidades_hc`). Si tiene dos, elige cuál al abrir la ficha.
- **Secretaria**: elige consultorio y médico; opera agenda, pacientes, recetas y obras sociales.
- **Administrador**: usuarios, médicos, secretarias, consultorios, especialidades, feriados, licencias.

La sesión se resuelve con `resolverPerfil` (misma función en app y servidor) a partir del mail.

## Offline-first y sincronización

- Cada escritura local marca la fila con `dirty = 1`.
- `SyncEngine.sincronizar()`: envía las filas sucias (`POST /sync/push`), sube adjuntos pendientes y
  trae los cambios de cada tabla desde la última versión conocida (`GET /sync/pull`).
- Conflictos: última escritura gana por `updated_at`; para **turnos** el servidor rechaza un turno si
  el horario ya fue ocupado por otro dispositivo, y el cliente lo ve al sincronizar.
- Web: la base vive en memoria (sql.js) mientras la pestaña está abierta y se recarga del servidor.
  Android e iOS mantienen la base en el dispositivo.

## Agenda de turnos

`TurnosRepository.slotsDelDia` reimplementa `createJson` de turnosonlinebb: plantilla semanal
vigente (o fecha agregada) cruzada con turnos activos/bloqueados, feriados, primer control doble,
"mostrar de a dos", ventana de días y cupos de primer control. Los módulos 1-12 se conservan.

## Diseño

`core/ui` reproduce la identidad de las apps originales: degradado teal `#2C7B90 → #004D45`
(barras, botones, cabeceras de tabla), títulos índigo `#303F9F`, fondo `#F8F7FA`, botones
"píldora". Mejoras: modo oscuro, navegación adaptativa (barra lateral en pantallas anchas, barra
inferior en teléfonos), autoguardado con indicador, calendario nativo en vez de datepicker y
colores por especialidad.
