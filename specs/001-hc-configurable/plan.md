# Plan 001 — Perfiles configurables de la HC general

Estado: aprobado (2026-10-09) · Spec: `specs/001-hc-configurable/spec.md` (aprobada el 2026-10-09)

Se entrega junto con la 002 en la rama `hc-general` de este repo y `salud360-api` de `rodri89/hclinica`. Este plan
**no incluye push a `main`** (publica la web) ni ningún paso contra producción. Lo reutilizable de pediatría y del
Laravel ya está inventariado en `specs/002-hc-clinica/plan.md` ("Qué ya tenemos"); acá solo se cita.

## 1. Resumen de la solución
- En `hclinica` se copia la base de la API de pediatría (token de turnos, CORS, config, rol admin) y se le agregan
  cuatro tablas creadas en caliente: perfiles, sus pestañas, sus secciones y la asignación médico → perfil. El
  catálogo de secciones es código PHP (id, nombre por defecto, descripción).
- Una carga inicial idempotente arma "Clínica general" y "Hepatología" (RF-31) y asigna médicos según `users.perfil` (RF-32).
- Endpoints `/api/salud360/config/...`: el médico lee su configuración; el administrador lee el catálogo y crea,
  edita (reemplazo completo en una transacción), duplica, borra y asigna perfiles.
- En la app, "clinica" pasa a ser la única HC general: su `EspecialidadDefinition` se **arma en tiempo de ejecución**
  con la configuración recibida + el catálogo de la app (las secciones que ya existen hoy, con los mismos ids). La
  configuración se cachea en una tabla nueva (`6.sqm`), nunca `dirty`.
- `TipoConsultaDef` suma pestañas agrupadas (opcional); si no las tiene, la pantalla se comporta como hoy (RF-33).
- El panel de administración suma la solapa "Perfiles HC" (siguiendo el patrón de licencias: lee y escribe en la HC,
  nada local). Hepatología deja de ser especialidad: `7.sqm` pasa sus datos locales a "clinica" (RF-29, RF-30).

## 2. Modelo de datos en `hclinica` (RF-1 a RF-12, RF-15, RF-17, RF-31, RF-32)

### Catálogo: código, no tabla
`app/Services/Salud360/CatalogoSecciones.php`, constante `SECCIONES` en el orden de la web:
`id => ['nombre' => ..., 'descripcion' => ...]`. Los ids son **los mismos que ya usa la app** para no perder datos
locales (RF-29, RF-30):

| id | nombre por defecto |
|---|---|
| `antecedentes_familiares` | Antecedentes familiares |
| `motivo_consulta` | Motivo de consulta |
| `documentos` | Adjuntos (PDF) |
| `laboratorio_general` | Laboratorio general |
| `laboratorio_reducido` | Laboratorio reducido |
| `eco_abdominal` | Ecografía abdominal |
| `serologias` | Serologías de hepatitis |
| `tabla_peso` | Peso |
| `child_pugh` | Child-Pugh |
| `veda` | Videoendoscopía digestiva alta |
| `biopsia_hepatica` | Biopsia hepática |
| `estudios_cardiologicos` | Estudios cardiológicos |
| `tc_rmn` | TC / RMN / colangio-RMN |
| `datos_subjetivos` | Datos subjetivos |
| `datos_objetivos` | Datos objetivos |
| `alimentacion` | Alimentación |
| `diuresis_catarsis` | Diuresis y catarsis |
| `somnia` | Somnia |
| `habitos` | Hábitos |
| `examen_fisico` | Examen físico |
| `examenes_complementarios` | Exámenes complementarios |
| `interconsulta` | Interconsultas |
| `conductas` | Conductas |
| `nota` | Nota |

### Tablas nuevas (prefijo `salud360_`, InnoDB, `timestamps()`)
| Tabla | Columnas | Claves |
|---|---|---|
| `salud360_perfiles` | `id` bigIncrements, `nombre` varchar(60) | unique(`nombre`) |
| `salud360_perfil_pestanias` | `id`, `perfil_id` unsignedBigInteger, `nombre` varchar(60), `orden` unsignedInteger | index(`perfil_id`); unique(`perfil_id`,`nombre`) |
| `salud360_perfil_secciones` | `id`, `perfil_id`, `pestania_id`, `seccion` varchar(60) (id del catálogo), `nombre` varchar(60) (el que se muestra), `en_consulta` tinyint, `en_telemedicina` tinyint, `orden` unsignedInteger | index(`pestania_id`); unique(`perfil_id`,`seccion`) (RF-9 también en la base) |
| `salud360_perfil_medicos` | `id`, `medico_id_tobb` unsignedInteger, `perfil_id` unsignedBigInteger | unique(`medico_id_tobb`) (un perfil por médico); index(`perfil_id`) |
| `salud360_cargas` | `id`, `nombre` varchar(60), `corrida_en` timestamp | unique(`nombre`) — marcador de la carga inicial |

- El nombre del perfil se compara sin mayúsculas ni espacios de más en la validación (RF-4); el `unique` es la red de
  seguridad. Sin claves foráneas a propósito (igual que pediatría: el hosting y las tablas legacy no las usan).
- La asignación va por **número de médico de turnos**, no por `users.id`: así el administrador puede asignar el
  perfil en el alta (RF-18), antes de que alguien vincule `users.medico_id_tobb`. Al vincularlo, el perfil ya está.
- Las consultas **no** guardan el perfil (casos límite de la spec): borrar un perfil no toca datos clínicos.

### Cómo se crean
`PerfilesService::asegurarTablas()` con `Schema::hasTable`/`Schema::create`, como `TobbAuthService::asegurarTablas`
de pediatría: el hosting no corre migraciones y `artisan migrate` falla por migraciones viejas sin registrar. Se llama
al principio de cada endpoint de configuración.

### Carga inicial (RF-31, RF-32)
`app/Services/Salud360/CargaInicialPerfiles.php`, la llama `asegurarTablas()` después de crear las tablas:
1. Si `salud360_cargas` ya tiene `perfiles_iniciales`, no hace nada (no pisa lo que el administrador cambió después).
2. En una transacción: crea "Clínica general" y "Hepatología" **si no existe un perfil con ese nombre**, con las
   pestañas "Antec. Familiares" y "Consulta" y las secciones/tipos de la tabla de la spec (definidas como array en la
   clase, una sola fuente). Orden: ver "Para decidir" 1.
3. Asigna con `perfilPara(int $perfilWeb)` (función pura): 2 → Hepatología; 1, 5, 9 → Clínica general; el resto →
   ninguno. Solo `users.usuario_tipo = 2` con `medico_id_tobb <> 0`, y solo si ese número todavía no tiene asignación
   (`INSERT ... WHERE NOT EXISTS`).
4. Inserta el marcador. Todo idempotente: correrla dos veces deja lo mismo.

Se prueba primero contra la copia local (`C:\xampp\htdocs\HClinica-salud360`, base `hc_clinica` del XAMPP) y se
verifica con el E2E (sección 9). En producción corre sola en el primer pedido después de subir los archivos.

## 3. API en `hclinica` (bajo `/api/salud360`, middleware `salud360`)

**Rol y código.** El middleware valida el token de turnos con `GET auth/perfil` y lo cachea en
`salud360_token_cache` (copia de pediatría). Administrador = `rol: admin` en turnos, resuelto al usuario de
`hclinica` con `usuario_tipo = 1`; los controladores de administración exigen `usuario_tipo === 1` (`403 sin_permiso`).
`config('salud360.hc_codigos') = ['clinica', 'hepatologia']`: el médico entra si turnos le habilita **cualquiera** de
los dos (RF-29). Respuestas con el contrato `{ok, mensaje, codigo, ...}`.

| Método y ruta | Quién | Request → Response | Errores | RF |
|---|---|---|---|---|
| `GET auth/perfil` | todos | → `{usuario, rol, licencia}` (copia de pediatría) | 401 `sin_token`/`token`, 403 `hc_no_habilitada`/`medico_no_vinculado`/`licencia_vencida`, 503 `tobb_caido` | base |
| `GET config` | médico | → `{perfil: {id, nombre, pestanias: [{nombre, secciones: [{seccion, nombre, consulta, telemedicina}]}]} \| null}` en orden | 403 `sin_permiso` (no médico) | RF-19, RF-20, RF-23, RF-24 |
| `GET config/catalogo` | admin | → `{secciones: [{id, nombre, descripcion}]}` | 403 | RF-1, RF-2, RF-16 |
| `GET config/perfiles` | admin | → `{perfiles: [{id, nombre, medicos: n}]}` | 403 | RF-11 |
| `GET config/perfiles/{id}` | admin | → `{perfil}` completo | 403, 404 `no_encontrado` | RF-15 |
| `POST config/perfiles` | admin | `{nombre, pestanias}` → 201 `{perfil}` | 422 `validacion` con `errores: [{campo, mensaje}]` | RF-3 a RF-9, RF-12 |
| `PUT config/perfiles/{id}` | admin | perfil completo → `{perfil}`; reemplazo total en `DB::transaction` con `lockForUpdate` sobre la fila del perfil: borra pestañas/secciones y las vuelve a insertar | 404, 422 | RF-5 a RF-10, RF-15 |
| `DELETE config/perfiles/{id}` | admin | → `{ok}` | 404, 409 `perfil_con_medicos` `{medicos: n}` | RF-11 |
| `GET config/asignaciones` | admin | → `{asignaciones: [{medico_id_tobb, nombre, vinculado, activo, perfil_id, perfil_web}]}` (`activo` es null si no está vinculado): médicos vinculados de `users` + asignaciones de números todavía no vinculados | 403 | RF-17, RF-32 |
| `PUT config/asignaciones/{medicoIdTobb}` | admin | `{perfil_id: n \| null}` → `{asignacion}`; null borra la fila | 404 perfil inexistente, 422 número inválido | RF-17, RF-18 |

Validación del servidor (`PerfilesService::validar`, pura, la autoridad; la app repite la misma antes de mandar):
nombres de perfil, pestaña y sección no vacíos y ≤ 60 caracteres (`mb_strlen`, después de `trim`); al menos una
sección; nombre de perfil no usado por otro; pestañas sin nombres repetidos dentro del perfil; cada sección del
catálogo (RF-2), una sola vez (RF-9) y con al menos un tipo (RF-8). Cada error dice dónde (`pestanias.1.secciones.0`)
y qué corregir (RF-4). Duplicar (RF-12) lo hace la app con `POST` (reutiliza la validación).
Formas de `campo` (índices del array recibido; las repeticiones se marcan en la segunda aparición): `nombre` (vacío,
largo o usado por otro perfil), `pestanias` (perfil sin secciones), `pestanias.i.nombre` (vacío, largo o repetido),
`pestanias.i.secciones.j.nombre`, `pestanias.i.secciones.j.seccion` (fuera del catálogo o repetida),
`pestanias.i.secciones.j` (sección sin tipos); y `nombre`/`pestanias` cuando salta el `unique` de la base por nombres
que solo cambian en tildes (`erroresDeUnique` en `guardar`).

Documentación del contrato y pruebas con curl: `md/API_SALUD360_CLINICA.md` en `hclinica`.

## 4. App: módulos y archivos

### `core/model`
- `especialidad/EspecialidadDefinition.kt`: `data class PestaniaDef(id, titulo, secciones: List<String>)` y
  `TipoConsultaDef.grupos: List<PestaniaDef> = emptyList()` **al final** (los constructores posicionales de las otras
  especialidades no cambian). Con `grupos` vacío todo sigue igual (RF-33).
- `especialidad/PerfilHc.kt` (nuevo, `@Serializable` para el caché): `TipoConsultaHc` (`CONSULTA` ↔ `"control"`,
  `TELEMEDICINA` ↔ `"telemedicina"`), `SeccionPerfil(seccion, nombre, tipos)`, `PestaniaPerfil(nombre, secciones)`,
  `PerfilHc(id, nombre, pestanias)`, `SeccionCatalogoRemota`, `AsignacionPerfil`, `CatalogoHc` (base + secciones de la
  app con lo que cada una necesita: `SeccionDef`, categorías de antecedentes, laboratorios, registros) y
  `CodigosHc.GENERAL = "clinica"`.

### `core/database` (RF-25, RF-26, RF-29, RF-30)
- `migrations/6.sqm` + `HcConfig.sq` (nuevo): tabla `hc_config(medico_id, hc_codigo, config_json, recibida_en,
  PRIMARY KEY(medico_id, hc_codigo))`. Sin `dirty`/`deleted`: es copia de un dato remoto. `config_json` guarda el
  perfil o `null` (sin perfil, RF-19), así se distingue "sin perfil" de "nunca recibida" (RF-26).
- `migrations/7.sqm` (fase de migración): `UPDATE ... SET especialidad = 'clinica' WHERE especialidad = 'hepatologia'`
  en `consulta`, `antecedente`, `registro_clinico`, `laboratorio`; `especialidad.codigo_hc`; `medico.especialidades_hc`
  (`REPLACE`, la duplicación la limpia la normalización); y `medico_preferencia`: `secciones_ocultas_hepatologia` se une
  a `secciones_ocultas_clinica` (concatenación CSV; repetidos no molestan porque se lee como conjunto) y se borra la vieja.
  Nada se borra de datos clínicos. `HistoriaClinica.sq` solo suma consultas de lectura (ver abajo); sin columnas nuevas.
- `HistoriaClinica.sq`: consultas nuevas para RF-22: tipos de registro, de laboratorio y secciones de archivo con datos
  en una consulta (`SELECT DISTINCT tipo ... WHERE consulta_id = ?`).

### `core/data`
- `network/hc/HcConfigDtos.kt`: DTOs de la sección 3 y su paso a/desde `PerfilHc`.
- `repos/ConfigHcRepository.kt` (médico): `refrescar(medicoId)` → `GET config` y guarda en `hc_config`;
  `guardada(medicoId)`; `estado(medicoId, conRed)` usa `estadoConfig` (lógica pura). Recibe una interfaz
  `ConfigHcApi` (implementada sobre `HcApiClients.porEspecialidad["clinica"]`) para poder probarlo con un falso, sin
  sumar `ktor-client-mock`.
- `repos/PerfilesHcRepository.kt` (admin): catálogo, perfiles, guardar/crear/borrar, asignaciones. **Nada local**:
  sin conexión no se edita (RF-14), así que no hay copia que pueda quedar `dirty`. Los errores traen el `mensaje` y
  los `errores` de la API (RF-13).
- `hcgeneral/*.kt`: la lógica pura de la sección 5.
- `repos/TurnosImportador.kt`, `repos/AgendaTurnosOnline.kt` y `mappers/Mappers.kt` (medico `toModel`): aplican
  `normalizarHistoriasClinicas` ("hepatologia" → "clinica", sin repetidos); `codigoHcPorNombre("hepato…")` → "clinica" (RF-29).
- `DataModule.kt`: `single` de los dos repositorios. **No** se registra un `HcBackend` para "clinica" en esta spec: si
  se registrara, `HcApiSync` intentaría mandar consultas a endpoints que llegan en la 002.
- `build.gradle.kts`: propiedades `salud360.test.hc.clinica`, `.clinica.token`, `.clinica.token.admin` (separadas de
  las de pediatría para no cruzar tokens).
- `gradle.properties` (raíz): `salud360.entorno.release.hc.clinica=https://clinica.hclinicadigital.com`; la de dev,
  ver "Para decidir" 4.

### `especialidades/clinica`
- `HcGeneral.kt` (reemplaza a `ClinicaMedica.kt`): `catalogoHcGeneral: CatalogoHc` con las 24 secciones (reutiliza las
  `SeccionDef` actuales de clínica, hepatología y `SeccionesGenericas`; `diuresis_catarsis` y `somnia` como texto, igual
  que pediatría y que la web), la base `hcGeneralBase` (código "clinica", nombre "Clínica", examen físico con peso,
  talla, IMC, TA y nota, y las secciones **legadas** que la app tenía en clínica y hepatología —diagnósticos, audio,
  fotos, antecedentes personales— solo para mostrar datos viejos según RF-22/RF-30) y
  `hcGeneralContribution` con el renderer de Child-Pugh.
- `ChildPughSeccion.kt`: sin cambios.

### `features/hc`
- `EspecialidadRegistry.kt`: `EspecialidadContribution` suma `catalogo: CatalogoHc? = null` (al final). El registro
  sigue estático para nombres y `existe()`.
- `DefinicionesHc.kt` (nuevo, `single` en `HcModule.kt`): `suspend fun resolver(codigo, medicoId): DefinicionResuelta`.
  Para especialidades sin catálogo devuelve la definición estática de siempre (RF-33). Para "clinica": intenta
  `ConfigHcRepository.refrescar` con espera corta; si falla, usa el caché; arma la definición con `armarDefinicion`.
  `DefinicionResuelta` = definición + `sinPerfil` + `primeraVezSinConexion` + `desconocidas`.
- `AvisosHcGeneral` (en el mismo archivo, `single`): recuerda en memoria si ya se avisó en esta sesión que hay
  secciones que esta versión no sabe mostrar (RF-27).
- `ConsultaViewModel.kt`: resuelve la definición **una vez** al abrir y la congela para esa pantalla (RF-23: un cambio
  que llega con la consulta abierta se aplica la próxima vez, sin perder lo cargado). `seccionesVisibles` delega en la
  función pura; suma `seccionesSoloLectura` (RF-22) y el estado de avisos.
- `ConsultaScreen.kt`: si `tipo.grupos` no está vacío, pestañas = "Datos" (ficha fija) + grupos con al menos una
  sección visible; cada pestaña renderiza sus secciones con `RenderSeccion`. Debajo, el bloque de solo lectura con un
  `SeccionContext` envuelto en `soloLectura = true` (todos los renderers ya respetan `ctx.soloLectura`). Avisos de
  RF-19, RF-26 y RF-27 arriba. Sin grupos: código de hoy, intacto.
- `HistoriaClinicaScreen.kt` / `HistoriaClinicaViewModel`: definición resuelta en un `StateFlow`; con RF-19 o RF-26
  muestra el mensaje, no ofrece consulta nueva y deja abrir las anteriores en lectura; ofrece solo los tipos con
  secciones. La copia de diagnósticos pasa a mirar las secciones de los tipos y no `definicion.secciones` (si no, las
  legadas la dispararían).
- `ConfigSeccionesHcScreen.kt`: para "clinica" lista las secciones del perfil del médico (todas las pestañas, con el
  nombre del perfil), misma preferencia `secciones_ocultas_clinica` (RF-28). Nunca se poda la lista guardada: lo que
  ocultó vuelve oculto si el administrador quita y repone la sección.

### `features/admin` (RF-1 a RF-18)
- `PerfilesHcViewModel.kt` (nuevo, en `AdminModule.kt`): estado de la lista, del perfil en edición (borrador que se
  conserva si el guardado falla, RF-13), del catálogo y de las asignaciones; `sinConexion` cuando la carga o el
  guardado fallan con código `red` (RF-14: la pantalla lo dice y deshabilita la edición, con "Reintentar").
- `PerfilesHcScreens.kt` (nuevo): solapa "Perfiles HC" en `AdminScreens.kt` (`SOLAPAS`).
  - Lista: nombre, médicos asignados, Editar / Duplicar / Borrar (Borrar deshabilitado con médicos, RF-11).
  - Editor: nombre del perfil; pestañas (agregar, renombrar, subir/bajar, quitar); dentro de cada una, secciones
    (agregar del catálogo —las que no están en el perfil y la app sabe mostrar, marcadas "Consulta" (RF-8)—,
    renombrar, casillas Consulta/Telemedicina, subir/bajar, mover de pestaña, quitar). Errores de validación junto al
    campo. Catálogo con nombre y descripción; las secciones que la app no conoce, "requiere actualizar la app",
    deshabilitadas; si un perfil ya las tiene, se conservan al guardar (RF-16).
  - Asignaciones: médicos con "clinica" habilitada (de `AdminRepository.observarMedicos`, cruzados por número de turnos
    como licencias) con un selector de perfil que incluye "Sin perfil" (RF-17).
- `AdminScreens.kt` / `AdminViewModel.kt` (RF-18): en el alta, si se marca "Clínica" aparece el selector de perfil;
  después del alta en turnos se asigna con el `medico_id` que devuelve `TobbUsuarioCreado` (si la asignación falla, el
  usuario queda creado y se avisa que falta asignarle el perfil). En la ficha del médico, selector de perfil si tiene
  "Clínica". La lista de historias clínicas ya muestra una sola "Clínica" porque sale de las contribuciones.

### `composeApp`
- `AppDi.kt`: `clinicaContribution` → `hcGeneralContribution` (fase médico); se quita `hepatologiaContribution` (fase
  de migración); `single { catalogoHcGeneral }` para que admin y HC lo inyecten.

## 5. Lógica pura (`core/data/src/commonMain/.../hcgeneral`, sin Compose ni red)
| Función | Qué hace | RF |
|---|---|---|
| `validarPerfil(perfil, otrosNombres, catalogoIds): List<ErrorPerfil>` | Reglas de la sección 3, con el lugar y el texto a corregir | RF-2, RF-4, RF-8, RF-9 |
| `nuevaSeccion(seccionId, catalogo): SeccionPerfil` | Nombre por defecto y tipo Consulta | RF-8 |
| `nombreCopia(nombre, existentes): String` / `duplicarPerfil(perfil, existentes)` | "Copia de X", "Copia de X (2)"…; recorta para no pasar 60 | RF-12 |
| `puedeBorrar(perfil, asignaciones)` | Sin médicos asignados | RF-11 |
| `catalogoParaAdmin(remoto, catalogoApp)` | Marca `soportada` | RF-1, RF-16 |
| `armarDefinicion(catalogo, perfil?): DefinicionArmada` | Definición "clinica": todas las secciones conocidas (títulos del perfil), tipos con `grupos` filtrados por tipo, pestañas vacías fuera, ids desconocidos aparte | RF-7, RF-19, RF-20, RF-21, RF-24, RF-27 |
| `seccionesVisibles(tipo, ocultas)` | Grupos sin las ocultas por el médico, nunca fuera del perfil | RF-21, RF-28 |
| `campoTieneDato(campo, valor)` / `seccionesConDatos(datos, def)` | "Tener datos" en esa consulta según el tipo de sección (texto/form, examen, registros, laboratorios, archivos, diagnósticos de esa consulta; los antecedentes y los registros por paciente no cuentan); `0`, `0\|` y vacío no cuentan | RF-22 |
| `seccionesSoloLectura(catalogo, enPerfilParaTipo, conDatos, ocultas, enPerfil)` | Con datos en la consulta y fuera del perfil para ese tipo, con el **nombre por defecto**, al final, en orden de catálogo y después legadas; `enPerfil` sin valor por defecto | RF-22, RF-30 |
| `seccionesDelPacienteSoloLectura(catalogo, antecedentes, enPerfil, ocultas)` | Secciones por paciente con datos que no están en el perfil en ningún tipo, con el nombre por defecto, para "Datos" | RF-34, RF-30 |
| `estadoConfig(guardada, remota): EstadoConfigHc` | `ConPerfil` / `SinPerfil` / `PrimeraVezSinConexion`; la remota buena manda, si falla vale la guardada sin vencimiento | RF-23, RF-25, RF-26 |
| `normalizarHistoriasClinicas(codigos)` | "hepatologia" → "clinica", sin repetidos | RF-29 |

No dependen de "hoy": ninguna regla de esta spec mira fechas.

## 6. Cambios fuera de este repo
1. **`hclinica`** (repo `rodri89/hclinica`, clon `C:\Users\flor\rebonline\hclinica`, rama `salud360-api`; nunca en
   `C:\Users\flor\hclinica`): todo lo de las secciones 2 y 3. Archivos: `config/salud360.php`,
   `app/Http/Middleware/Salud360Api.php` y `Salud360Cors.php` (+ registro en `app/Http/Kernel.php`),
   `app/Services/Salud360/TobbAuthService.php` (copia con `hc_codigos` y **el arreglo del modo "turnos caído"**: resolver
   por `user_id` de la caché, que incluye al admin, y pasar por `conLicencia`), `CatalogoSecciones.php`,
   `PerfilesService.php`, `CargaInicialPerfiles.php`, `app/Http/Controllers/Api/Salud360/Salud360Controller.php`,
   `AuthController.php`, `ConfigController.php`, `PerfilController.php`, `routes/api.php`,
   `tests/Unit/PerfilesTest.php` (mapeo RF-32 y validación, sin base), `md/API_SALUD360_CLINICA.md`.
   `LicenciaController`, `ColumnasLegacy` y el conector con reintento entran con la 002; al copiar `LicenciaController`
   se le agrega el `use ...ColumnasLegacy` que le falta en pediatría.
2. **turnosonlinebb** (repo `rodri89/turnosonlinebb`, clon `C:\Users\flor\rebonline\turnosonlinebb`; aprobado el
   2026-10-09): endpoint nuevo `PUT /api/salud360/medicos/{id}/historias-clinicas` `{historias_clinicas: [..]}`, solo
   admin, que reemplaza las filas de `salud360_medico_hc` de ese médico (mismo criterio que `admin_hc_medico` de la
   web). En la app: `AgendaTurnosOnline` + `AdminViewModel` lo usan al editar un médico (RF-18). El catálogo de
   turnos (`hepatologia`, "Clínica médica") no se toca: la app normaliza.
3. **Orden de despliegue** (al final, con la 002, y solo con pedido explícito): subir el endpoint de turnos →
   subir `hclinica` → `config:clear` →
   verificar con curl sin credenciales (`401 sin_token`, y `codigo: token` con un token inventado, no `tobb_caido`) →
   primer pedido del administrador dispara la carga inicial y se revisa → unir `hc-general` a `main` (eso publica la web).
   Si la app sale antes que la API, la HC general muestra "la primera vez hace falta conexión" y no rompe nada más.

## 7. Sin conexión y caché
- Médico: la configuración se trae al abrir la historia clínica, la consulta y Configuración; se guarda en `hc_config`
  y vale sin vencimiento (RF-25). Sin caché y sin red: aviso de RF-26; consultas locales visibles en lectura, nada se
  borra. Con caché "sin perfil": aviso de RF-19.
- Las consultas de la HC general, en esta spec, siguen guardándose solo en el dispositivo como hoy (la API clínica
  es la 002). La configuración nunca pasa por `HcApiSync` ni por `SyncEngine`, y nunca se llama desde el autoguardado.
- Administrador: todo en línea. Sin red no se edita (RF-14) y lo que estaba editando queda en pantalla (RF-13).

## 8. Decisiones (con la alternativa descartada)
- **Catálogo como código en los dos lados**, con los mismos ids que la app ya usa. Descartado: tabla de catálogo en la
  base (agregar una sección exige programarla igual, y la tabla podría prometer algo que la app no sabe dibujar).
- **Caché en una tabla SQLDelight (`6.sqm`)**. Descartado: `Settings` (en web es `localStorage`, aparte de la base;
  no se prueba con la base de prueba de `jvmTest` y no viaja con la migración de hepatología, que igual exige una).
- **La definición de "clinica" se arma en tiempo de ejecución** por médico (`DefinicionesHc` + `armarDefinicion`), y
  las demás especialidades siguen estáticas. Descartado: generar las definiciones fijas de clínica/hepatología desde
  la config (exige publicar la app para cada perfil, contra RF-24) o hacer dinámico todo el registro (toca pediatría,
  contra RF-33).
- **Pestañas agrupadas como campo opcional de `TipoConsultaDef`**. Descartado: reinterpretar `pestanias` (hoy cada
  pestaña es una sección suelta; cambiarlo rompe pediatría).
- **Tablas en caliente**, como pediatría. Descartado: migraciones de Laravel (fallan en ese hosting) y SQL a mano
  (sin forma de repetirlo igual en local y en producción).
- **Guardar un perfil = reemplazarlo entero en una transacción**. Descartado: endpoints por pestaña o por sección
  (dos administradores podrían dejar una mezcla, contra RF-15).
- **Asignación por número de médico de turnos**. Descartado: por `users.id` (no se podría asignar en el alta, RF-18).
- **El perfil del administrador no se cachea**. Descartado: copia local con `dirty = false` como licencias (no hace
  falta: sin conexión no se edita).
- **Hepatología se migra con `7.sqm`**. Descartado: migrar en Kotlin al arrancar (no es atómico y hay que recordar si ya corrió).
- **Repositorio con interfaz de API falsa en los tests**. Descartado: `ktor-client-mock` (dependencia nueva).

## 9. Estrategia de tests
- `core/data` commonTest: `PerfilValidacionTest` (RF-2, RF-4, RF-8, RF-9), `PerfilCopiaTest` (RF-11, RF-12),
  `DefinicionHcGeneralTest` (RF-7, RF-19, RF-20, RF-21, RF-24, RF-27, casos límite de pestaña vacía),
  `SeccionesSoloLecturaTest` (RF-22, RF-28, RF-30, RF-34), `EstadoConfigTest` (RF-23, RF-25, RF-26),
  `HistoriasClinicasTest` (RF-29), `CatalogoAdminTest` (RF-1, RF-16), `PerfilesDtoTest` (ida y vuelta del JSON).
- `core/data` jvmTest (base SQLite en archivo temporal, como los E2E): `ConfigHcRepositoryTest` con API falsa
  (guardar, leer sin red, nunca `dirty`; RF-25, RF-26) y `MigracionHepatologiaTest` (`Schema.migrate` de 7 a 8 sobre
  filas sembradas: consultas, antecedentes, registros, laboratorios, preferencias y el diagnóstico de una consulta
  siguen ahí con "clinica"; RF-29, RF-30).
- `especialidades/clinica` commonTest (nuevo; el plugin de convención ya trae `kotlin-test`):
  `CatalogoHcGeneralTest`: cada id del catálogo de la spec tiene `SeccionDef` y sus dependencias, y los dos perfiles
  iniciales, armados con el catálogo real, dan las pestañas y secciones de la tabla (RF-31, RF-33: pediatría no cambia).
- `hclinica`: `vendor/bin/phpunit tests/Unit/PerfilesTest.php` (mapeo RF-32 y validación, sin base), `php -l` y curl.
- E2E `core/data` jvmTest `HcPerfilesE2ETest`, **solo contra la copia local**, se saltea sin propiedades:
  ```
  .\gradlew :core:data:jvmTest --rerun --tests '*HcPerfilesE2ETest*' `
    -Psalud360.test.hc.clinica=http://localhost/HClinica-salud360/public/index.php `
    -Psalud360.test.hc.clinica.token.admin=<token admin> -Psalud360.test.hc.clinica.token=<token médico de prueba>
  ```
  Recorre: catálogo; "Clínica general" y "Hepatología" iguales a la tabla (RF-31); cada asignación coincide con
  `perfilPara(perfil_web)` (RF-32); crear un perfil con dos pestañas y tres secciones, duplicarlo, intentar los
  inválidos (RF-3 a RF-12), asignarlo al médico de prueba y leer `GET config` como ese médico (RF-17, RF-20, RF-24);
  borrar con médico asignado → 409 (RF-11); deja todo como estaba.
- Manual con `devWeb` (lista de pasos para el usuario si el entorno no da): el administrador arma y asigna el perfil
  de prueba y el médico lo ve con esos nombres y en ese orden sin recompilar (criterio de finalización); sin red en el
  panel (RF-13, RF-14); médico sin perfil (RF-19); modo avión la primera vez (RF-26) y después (RF-25); sección
  desconocida (perfil con un id que la app no tiene) (RF-16, RF-27); ocultar y reponer (RF-28); una consulta de
  pediatría se ve igual que antes (RF-33); un dispositivo con datos viejos de hepatología (RF-29, RF-30).

## 10. Riesgos y cosas a vigilar
- **Nunca datos reales**: `hc_clinica.sql` y `bd_test/` no se citan ni se copian a tests o docs; los E2E crean su
  propio perfil y lo borran; los nombres de médicos no se escriben en el repo (van por número).
- **Columnas nuevas siempre al final** (`upsertX` es posicional): esta spec no agrega columnas a tablas existentes;
  `hc_config` es tabla nueva. `verifyMigrations` está apagado: el test de migración es el único control.
- **Nada de API desde el autoguardado** (600 ms): la config se pide al abrir pantallas, nunca al escribir.
- **Dueño remoto sin `dirty`**: `hc_config` no tiene la columna y los perfiles del admin no se guardan local.
- **`config:clear`** al agregar `config/salud360.php` en `hclinica`; si no, aparece `503 tobb_caido` falso.
- Un paciente con consultas abiertas en clínica **y** hepatología queda con dos abiertas de la HC general; y si tenía
  antecedentes familiares en las dos, quedan dos filas por clave: el test de migración tiene que fijar cuál se muestra.
- `ConsultaUi` se recalcula con cada cambio de la consulta: la definición congelada tiene que vivir fuera del
  `flatMapLatest` para cumplir RF-23.
- Las secciones legadas en la definición no deben disparar comportamiento (copia de diagnósticos, Configuración).
- Médicos no vinculados en `hclinica` reciben `medico_no_vinculado` al pedir la config: el aviso tiene que nombrarlo.
- Un administrador con una versión vieja de la app no debe borrar, al guardar, secciones que no conoce (RF-16).

## 11. Orden de trabajo (fases y tandas de `tasks.md`, máx. ~10 tareas cada una)
| Fase | Qué | RF que cierra | Tareas |
|---|---|---|---|
| 0. Entorno local | Clon y rama de `hclinica`, copia servida, base restaurada, tokens sembrados; turnos local en XAMPP (`htdocs\TurnosOnlineBB-salud360` con su base) respondiendo `auth/login`; `devWeb` con `-P` apuntando a los dos; curl de humo | — | 3 |
| 1. Base de la API | Middleware, servicio de token (con el arreglo), CORS, config, `auth/perfil`, rutas | RNF "solo el administrador", RF-29 (lado servidor) | 3 |
| 1b. Turnos | Endpoint `PUT medicos/{id}/historias-clinicas` en turnosonlinebb + prueba con curl | RF-18 (servidor) | 1 |
| 2. Modelo y API de configuración | Tablas, catálogo, validación, endpoints, carga inicial, phpunit, md | RF-1 a RF-12, RF-15, RF-17, RF-31, RF-32 (servidor) | 5 |
| 3. Lógica en la app | Tipos de `core/model`, funciones puras con tests, catálogo de la app + su test | RF-2, RF-4, RF-7, RF-8, RF-9, RF-11, RF-12, RF-16, RF-20 a RF-22, RF-27, RF-28 (lógica), RF-31 | 5 |
| 3b. Datos en la app | `6.sqm`, repositorios, DTOs, Koin, URL release, tests de caché | RF-23, RF-25, RF-26 | 3 |
| 4. Administración | Solapa Perfiles HC: lista, editor, asignaciones, alta/edición de médico | RF-1 a RF-18 (interfaz) | 4 |
| 5. Médico | `DefinicionesHc`, pestañas agrupadas, solo lectura, avisos, Configuración, `hcGeneralContribution` | RF-19 a RF-28, RF-33, RF-34 | 5 |
| 6. Migración hepatología | `7.sqm` + test, normalización de códigos, quitar `hepatologiaContribution` | RF-29, RF-30 | 2 |
| 7. E2E y cierre | `HcPerfilesE2ETest`, pasos manuales `devWeb`, docs (`AGENTS.md`, `MEMORY.md`, `docs/`) | criterios de finalización | 2 |

Tandas propuestas: **A1** = fases 0, 1 y 1b (7 tareas: entorno, base de la API, turnos); **A2** = fase 2 (5, modelo y
API de configuración); **B** = 3 y 3b (8); **C** = 4 y 5 (9); **D** = 6 y 7 (4). Cada tanda se aprueba antes de
generar su `tasks.md`.

**Prueba manual en esta máquina** (sin tocar `gradle.properties`; Gradle toma primero lo que viene por `-P`):
`.\gradlew devWeb "-Psalud360.entorno.dev.turnos=http://localhost/TurnosOnlineBB-salud360/public/index.php" "-Psalud360.entorno.dev.hc.clinica=http://localhost/HClinica-salud360/public/index.php"`
(confirmado en T3; las comillas hacen falta en PowerShell por los puntos del `-P`). Pediatría sigue apuntando al
MAMP de `gradle.properties`.

## Tabla RF → dónde → cómo se prueba
| RF | Dónde se implementa | Cómo se prueba |
|---|---|---|
| RF-1 | `CatalogoSecciones.php`, `GET config/catalogo`; `catalogoParaAdmin`; editor | `CatalogoAdminTest`, E2E, manual |
| RF-2 | Validación servidor (sección fuera del catálogo); editor solo ofrece catálogo | `PerfilValidacionTest`, phpunit, E2E |
| RF-3 | `POST`/`PUT config/perfiles` | E2E |
| RF-4 | `validarPerfil` + `PerfilesService::validar` | `PerfilValidacionTest`, phpunit, E2E |
| RF-5 | Editor (pestañas) + `PUT` reemplazo | E2E, manual |
| RF-6 | Editor (secciones, mover de pestaña) + `PUT` | E2E, manual |
| RF-7 | `salud360_perfil_secciones.nombre` / pestaña; `armarDefinicion` | `DefinicionHcGeneralTest`, E2E |
| RF-8 | `nuevaSeccion`, `validarPerfil`, servidor | `PerfilValidacionTest`, phpunit |
| RF-9 | `validarPerfil`, unique en la base | `PerfilValidacionTest`, E2E |
| RF-10 | Asignación por `perfil_id`; `PUT` conserva el id | E2E |
| RF-11 | `puedeBorrar`, `DELETE` → 409 | `PerfilCopiaTest`, E2E |
| RF-12 | `nombreCopia`, `duplicarPerfil` + `POST` | `PerfilCopiaTest`, E2E |
| RF-13 | `PerfilesHcViewModel` (borrador + mensaje de la API) | Manual `devWeb` |
| RF-14 | `PerfilesHcViewModel.sinConexion` | Manual (red cortada) |
| RF-15 | `PUT` en transacción con `lockForUpdate` | E2E (dos `PUT` seguidos, se lee el último completo) |
| RF-16 | `catalogoParaAdmin`, editor conserva lo desconocido | `CatalogoAdminTest`, manual |
| RF-17 | `PUT config/asignaciones`, solapa | E2E, manual |
| RF-18 | Alta y ficha del médico en `AdminScreens.kt` | Manual |
| RF-19 | `GET config` con `perfil: null`; `estadoConfig`; avisos en HC | `EstadoConfigTest`, `DefinicionHcGeneralTest`, manual |
| RF-20 | `armarDefinicion`, `ConsultaScreen` agrupada | `DefinicionHcGeneralTest`, E2E, manual |
| RF-21 | `armarDefinicion`, `seccionesVisibles` | `DefinicionHcGeneralTest` |
| RF-22 | `seccionesConDatos` (solo datos de esa consulta), `seccionesSoloLectura`, bloque de lectura al final de la consulta (T27/T30) | `SeccionesSoloLecturaTest`, `DatosConsultaTest`, manual |
| RF-23 | `DefinicionesHc` + definición congelada en `ConsultaViewModel` | `EstadoConfigTest`, manual |
| RF-24 | Definición armada desde la config | E2E + manual (perfil nuevo sin recompilar) |
| RF-25 | `hc_config`, `ConfigHcRepository`, `estadoConfig` | `ConfigHcRepositoryTest`, `EstadoConfigTest`, manual |
| RF-26 | `estadoConfig` (`PrimeraVezSinConexion`), aviso | `EstadoConfigTest`, `ConfigHcRepositoryTest`, manual |
| RF-27 | `armarDefinicion.desconocidas`, `AvisosHcGeneral` | `DefinicionHcGeneralTest`, manual |
| RF-28 | `seccionesVisibles`, `ConfigSeccionesHcScreen`, preferencia sin podar | `SeccionesSoloLecturaTest`, manual |
| RF-29 | `7.sqm`, `normalizarHistoriasClinicas`, `hc_codigos` del servidor, contribución quitada | `MigracionHepatologiaTest`, `HistoriasClinicasTest`, manual |
| RF-30 | `7.sqm` sin borrados; secciones legadas; `seccionesSoloLectura` | `MigracionHepatologiaTest`, `SeccionesSoloLecturaTest` |
| RF-31 | `CargaInicialPerfiles`; catálogo de la app | `CatalogoHcGeneralTest`, E2E |
| RF-32 | `CargaInicialPerfiles::perfilPara` | phpunit, E2E (`perfil_web`) |
| RF-33 | `grupos` opcional, registro estático para el resto, sin backend "clinica" | Tests existentes en verde, `CatalogoHcGeneralTest`, manual pediatría |
| RF-34 | `seccionesDelPacienteSoloLectura`, bloque de lectura en "Datos" (T27/T30) | `SeccionesSoloLecturaTest`, manual |

## Decidido (2026-10-09, antes "Para decidir")
1. **Orden de telemedicina**: como en la web (subjetivos y objetivos antes de exámenes complementarios). Ajustada la
   tabla de la spec (RF-31).
2. **Habilitación de un médico existente (RF-18)**: endpoint nuevo en turnosonlinebb (sección 6, fase 1b).
3. **Catálogo de turnos**: no se toca; la app normaliza `hepatologia` → `clinica`.
4. **Prueba manual en esta máquina**: turnos local en XAMPP y `devWeb` con `-P` (fase 0 y sección 11).
   `salud360.entorno.dev.hc.clinica` para la Mac queda a confirmar cuando se arme allá.
5. **Consultas viejas "HC digitalizada"**: la definición incluye el tipo `foto` solo para leer; no se ofrece para
   consultas nuevas; sus fotos salen por RF-22.
6. **Frecuencia cardíaca y circunferencia abdominal ya cargadas**: se muestran solo para leer dentro del examen
   físico cuando tienen valor.
7. **Pantalla del médico**: pestañas reales ("Datos" | pestañas del perfil), abriendo en "Datos"; el bloque de solo
   lectura (RF-22) va debajo de la pestaña elegida.
8. **Médico sin perfil (RF-19)**: no puede crear consultas nuevas; solo lee las anteriores.
