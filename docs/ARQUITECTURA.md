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
todas las especialidades; y la tabla de "Desarrollo madurativo" existe solo en pediatría.

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

- **Médico**: ve su agenda (si `tieneTurnos`, es decir, si tiene consultorio en turnosonlinebb) y sus pacientes;
  abre la historia clínica de cada especialidad habilitada (`medico.especialidades_hc`). Si tiene dos, elige cuál
  al abrir la ficha.
- **Secretaria**: elige consultorio y médico; opera agenda, pacientes, recetas y obras sociales, y abre las
  historias clínicas habilitadas al médico elegido.
- **Administrador**: usuarios, médicos, secretarias, consultorios, especialidades, feriados, licencias.

La sesión se resuelve con `resolverPerfil` (misma función en app y servidor) a partir del mail.

## Identidad y permisos: turnosonlinebb como identidad única

Todos los usuarios de Salud 360 entran con su cuenta de **turnosonlinebb** (`POST /api/salud360/auth/login`),
tengan o no agenda. Un médico puede tener agenda, historias clínicas o ambas:

- **Agenda**: la tiene si turnosonlinebb le asignó consultorio (`consultorio_id` > 0 en el perfil).
- **Historias clínicas**: el administrador de turnosonlinebb las habilita por médico en Administración → Médico →
  "Historias clínicas Salud 360" (tabla `salud360_medico_hc`). El perfil de login las devuelve en
  `medico.historias_clinicas` (`["pediatria", ...]`) y `TurnosImportador` las copia a `medico.especialidades_hc`.
  Mientras la web no informe el campo, `TurnosImportador.FALLBACK_HC_POR_ESPECIALIDAD` deduce la HC del nombre de la
  especialidad de turnos (Pediatría → `pediatria`); hay que ponerlo en `false` cuando la web esté desplegada.

Antes, cada historia clínica tenía sus propios usuarios y guardaba el id del médico de turnos en
`users.medico_id_tobb`. Ese campo se conserva, pero invertido: ahora es la forma en que cada sistema de HC
reconoce al usuario que llega con el token de turnosonlinebb.

## Fase siguiente: una API por historia clínica

Cada historia clínica sigue viviendo en su propio Laravel (hc_pediatria, hclinica, ...). El plan es que cada uno
exponga `/api/salud360/...` copiando el patrón de turnosonlinebb (`Api/Salud360/*`, `Salud360Cors`,
`TokenService`), con una diferencia: no emite tokens propios. El middleware acepta el token de turnosonlinebb
(`Authorization: Bearer` o `X-Salud360-Token`), lo valida con `GET https://turnosonlinebb.com/api/salud360/auth/perfil`
(con caché por token) y resuelve el usuario local por `users.medico_id_tobb = perfil.medico.id`.

En la app, `EspecialidadDefinition.apiBaseUrl` indica la URL de esa API. Con ella, cada especialidad tendrá un
repositorio "API primero, caché local" como `AgendaTurnosOnline` (la base SQLite sigue siendo la caché y el modo
sin conexión). Queda por decidir en esa fase si la API expone las tablas legacy de cada proyecto o tablas genéricas
nuevas (`consulta`, `seccion_valor`, `examen_fisico`, ...) cargadas con un script de migración.

## Percentilos y curvas de crecimiento

`core/crecimiento` calcula percentilos y puntajes z con el método LMS sobre los patrones OMS 2006 (0-5 años) y la
referencia OMS 2007 (5-19 años): peso, talla, perímetro cefálico e IMC por edad. Las tablas se generan con
`tools/oms/generar_tablas.py`. En el examen físico de las especialidades con `conPercentilos` los percentilos se
completan solos (si el paciente tiene fecha de nacimiento y sexo) y se pueden corregir a mano; la sección
`curvas_crecimiento` (renderer del motor, disponible para cualquier especialidad) grafica los exámenes físicos
históricos sobre las curvas P3-P97.

## Offline-first y sincronización

- Cada escritura local marca la fila con `dirty = 1`.
- `SyncEngine.sincronizar()`: envía las filas sucias (`POST /sync/push`), sube adjuntos pendientes y
  trae los cambios de cada tabla desde la última versión conocida (`GET /sync/pull`).
- Conflictos: última escritura gana por `updated_at`; para **turnos** el servidor rechaza un turno si
  el horario ya fue ocupado por otro dispositivo, y el cliente lo ve al sincronizar.
- Web: la base corre en memoria (sql.js en un Web Worker) con una copia en IndexedDB del navegador que se
  actualiza tras cada escritura (`composeApp/src/wasmJsMain/resources/sqljs-persistente.worker.js`), así
  recargar la pestaña no pierde datos. Si cambia el esquema entre versiones, la copia se descarta y se recrea
  (es una caché). Las rutas se reflejan en la URL (`#pacientes/…`), por lo que recargar vuelve a la misma pantalla.
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
