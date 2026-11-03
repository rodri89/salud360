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
- (Cambio 2026-10-14) `especialidad/EspecialidadDefinition.kt`: `enum class ModoRegistro { LISTA, UNO_POR_CONSULTA }`
  y `RegistroDef.modo: ModoRegistro = ModoRegistro.LISTA` **al final** (las demás especialidades no cambian, RF-33).
  `UNO_POR_CONSULTA` = estudio con historial (RF-38 a RF-42): a lo sumo una entrada por consulta, fecha + campo
  `detalle`, mostrada junto con las de las otras consultas.
- (Cambio 2026-10-15) `especialidad/EspecialidadDefinition.kt`: `enum class ModoForm { FILAS, GRILLA_SI_NO }` y
  `SeccionDef.modoForm: ModoForm = ModoForm.FILAS` **al final** (todas las `SeccionDef` existentes quedan en `FILAS`,
  RF-73). `GRILLA_SI_NO` = los campos de la sección se dibujan como tarjetas SI / NO (RF-65 a RF-72); exige que todos
  sean `TipoCampo.SELECT` con opciones `["SI", "NO"]`, lo que fija `CatalogoHcGeneralTest` para serologías.
- (Cambio 2026-10-15 (3); RF-83 a RF-90) `especialidad/EspecialidadDefinition.kt`: `SeccionDef.conHistorial: Boolean =
  false` **al final** (después de `modoForm`; todas las `SeccionDef` existentes quedan en `false`, RF-90). Solo tiene
  efecto en `TipoSeccion.TEXTO`: el campo `texto` de esa consulta + "Consultas previas" como los estudios con
  historial. Opt-in por sección ("Decidido" C7-2).

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
- (Cambio 2026-10-15 (3); RF-83, RF-84) `HistoriaClinica.sq`, solo lectura (sin columnas ni migración):
  `valoresDeCampoDePaciente: SELECT sv.consulta_id, sv.valor FROM seccion_valor sv JOIN consulta c ON c.id =
  sv.consulta_id WHERE c.paciente_id = ? AND c.especialidad = ? AND sv.seccion = ? AND sv.campo = ? AND sv.deleted =
  0 AND c.deleted = 0 AND c.estado != 'ANULADA'`. No se reutiliza `historialCampo` (la que hoy usa
  `TextoLibreConPrevias` en todas las especialidades): no deja afuera las anuladas, y cambiarla cambiaría las demás
  historias clínicas (RF-90, "Decidido" 37). El orden y la fecha salen de `consultasDePaciente` (ya `estado !=
  'ANULADA'`, `fecha DESC, updated_at DESC`), que se cruza en la lógica pura.

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
- (Cambio 2026-10-15 (3)) `repos/HcRepository.kt`: `observarValoresDeCampo(pacienteId, especialidad, seccion, campo):
  Flow<Map<Id, String>>` sobre `valoresDeCampoDePaciente` (consulta → valor). `hcgeneral/TextoConHistorial.kt`
  (nuevo): `data class EntradaTexto(consultaId, fecha, texto)` e `historialTexto` (sección 5).
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
- (Cambio 2026-10-14) `HcGeneral.kt`: `deHepatologia` copia el `RegistroDef` de `eco_abdominal`, `veda`,
  `biopsia_hepatica`, `estudios_cardiologicos` y `tc_rmn` con `modo = UNO_POR_CONSULTA` (el de `hepatologia`, que se
  va en la tanda D, no cambia). Siguen siendo `TipoSeccion.REGISTROS` con el mismo `registroTipo` y el campo
  `detalle`: los datos locales no cambian y "tener datos" (RF-22) sigue igual.
- (Cambio 2026-10-15) `HcGeneral.kt`: `deHepatologia("serologias", ...)` copia la `SeccionDef` con
  `modoForm = GRILLA_SI_NO`; mismos 16 `CampoDef` (claves, etiquetas, orden de RF-65, `SELECT ["SI", "NO"]`), así lo
  guardado no cambia (RF-68) y "tener datos" (RF-22) sigue igual. La de `hepatologia` (`ClinicaMedica.kt`) no cambia
  (RF-73, "Decidido" C5-1).
- (Cambio 2026-10-15 (3); RF-90) `HcGeneral.kt`: `SeccionCatalogo(G.motivo)` pasa a
  `SeccionCatalogo(G.motivo.copy(conHistorial = true))` (mismo id `motivo_consulta`, mismo campo `texto`: lo guardado
  y "tener datos" de RF-22 no cambian). `SeccionesGenericas.motivo`, que usan `hepatologia`, `clinicaMedica`,
  pediatría y las demás, no cambia.
- ~~`ChildPughSeccion.kt`: sin cambios.~~ (Cambio 2026-10-15 (2); RF-74 a RF-82) Child-Pugh como la web. Ninguna
  `SeccionDef` cambia: el renderer CUSTOM `child_pugh` es uno solo y lo registran `hcGeneralContribution` y
  `hepatologiaContribution`, así que el dibujo nuevo llega a las dos (RF-82, "Decidido" 29).
  - `ChildPugh.kt` (nuevo, sin Compose): `data class ParametroChildPugh(clave, nombre, criterios: List<String>)` y
    `val parametrosChildPugh` con los cinco de hoy (las claves `ascitis`, `bilirrubina`, `albumina`, `tp`,
    `encefalopatia` y los textos de `PARAMETROS`, que ya tienen las erratas de la web corregidas), más las funciones
    puras de la sección 5. Reemplaza a `PARAMETROS` y a las cuentas que hoy están dentro del composable (`toIntOrNull`,
    que además sumaba un "4" o un "0" guardado).
  - `ChildPughSeccion.kt` (reescrito; la firma `ChildPughSeccion(s, ctx)` no cambia): `valores` = `ctx.valor(s.id,
    clave)` de cada parámetro; `puntos`, total (`totalChildPugh`), `completos` y clase (`claseChildPugh`) se calculan
    en cada recomposición, así el encabezado se actualiza con el toque (RF-77).
    - Solo lectura sin ningún parámetro con valor (`valores.all { it.isBlank() }`): `SeccionVacia()` de `features/hc`
      en vez del `SectionCard`, como `FormSeccion` (RF-81).
    - Dentro del `SectionCard` (título, ícono e `inicialmenteExpandida` como hoy), todo sobre `fondoTarjeta` de
      `coloresSiNo(LocalIsDarkTheme.current)` para que valgan los contrastes ya probados (RF-79).
    - Encabezado: `Text("Total: $total · Clase $clase")` con el estilo de hoy y `semantics { liveRegion =
      LiveRegionMode.Polite }` (el lector lo anuncia al cambiar), y la leyenda `"A: ≤ 6 · B: 7-9 · C: ≥ 10"`: en
      ancho, una `Row` con `SpaceBetween` (total a la izquierda, leyenda a la derecha); en angosto, una debajo de la
      otra.
    - `BoxWithConstraints`: con `maxWidth >= 600.dp` (mismo corte que RF-41 y RF-66), tabla (RF-74): fila de títulos
      "Parámetro" (`weight(1.4f)`) y "Puntos" (`weight(3f)`, centrado) y debajo "1", "2", "3" (`weight(1f)` cada uno);
      después una `Row(Modifier.height(IntrinsicSize.Min))` por parámetro, con el nombre (y, si el valor es
      desconocido, el valor como texto chico debajo, casos límite) y tres `OpcionElegible(criterio, descripcion,
      elegido = puntos == n, Modifier.weight(1f).fillMaxHeight())` → `ctx.setValor(s.id, clave,
      alternarPuntos(valor, "$n"))` (RF-76). Con menos de 600 dp (RF-75): por parámetro un `Column` con el nombre y
      las tres `OpcionElegible("$n punto(s) · criterio", …, Modifier.fillMaxWidth())` apiladas. El nombre no es
      clickable (RF-76).
    - Accesibilidad (RF-80): cada fila o bloque con `Modifier.selectableGroup().semantics { stateDescription =
      if (puntos == null) "sin cargar" else "$puntos puntos" }`; cada opción con `descripcion = "$nombre, $n
      punto(s), $criterio"` (el "elegido" lo pone `selected`), rol de opción, foco y 48 dp de `OpcionElegible`.
    - Solo lectura (RF-81): encabezado igual y, por parámetro, una `Row` "nombre" + "n puntos · criterio" (o el valor
      desconocido tal cual, o "—"), sin `OpcionElegible` ni `selectable`, en los dos anchos.
    - El valor pasa tal cual por `ctx.setValor`: `"1"`, `"2"`, `"3"` o `""`, mismo autoguardado de 600 ms, sin API
      (RF-78). No se guardan total ni clase: el total que pide la 002 (RF-27 de la 002) se calcula al enviar y puede
      salir de `totalChildPugh`.

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
- `ConsultaScreen.kt` (reemplazado por el cambio del 2026-10-14, "Decidido" 9): si `tipo.grupos` no está vacío, el
  mismo layout que pediatría (`tipo.pestanias` arriba en un `ScrollableTabRow` + `tipo.secciones` sueltas debajo,
  siempre visibles) y que `nueva_consulta.blade.php` de la web (nav "Datos" | "Antec. Familiares" y las secciones
  debajo): arriba "Datos" (ficha fija, abre ahí) + los grupos con algo visible **menos** el que se llama "Consulta";
  debajo, siempre, las secciones del grupo "Consulta" (el cuerpo) con `RenderSeccion`; al final, el bloque de solo
  lectura de RF-22 con un `SeccionContext` envuelto en `soloLectura = true` (todos los renderers ya respetan
  `ctx.soloLectura`), visible con cualquier pestaña (RF-37); el de RF-34 solo dentro de "Datos". Qué es pestaña y qué
  es cuerpo lo decide la función pura `layoutConsulta` (sección 5), no la pantalla. Avisos de RF-19, RF-26 y RF-27
  arriba. Sin grupos: código de hoy, intacto (RF-33).
- (Cambio 2026-10-14) `secciones/RegistrosSeccion.kt`: si `regDef.modo == UNO_POR_CONSULTA`, delega en
  `secciones/EstudioConHistorialSeccion.kt` (nuevo; mismo `SectionCard` con el título de la sección). Dentro:
  `BoxWithConstraints`: con `maxWidth >= 600.dp`, `Row` con el campo de esta consulta (fecha con `DateField` + texto
  con `TextAreaField`, ~10 líneas como el `rows="10"` de la web) a la izquierda y "Consultas previas" a la derecha
  (`Column` con `verticalScroll` y el mismo alto, cada entrada "fecha en negrita" + texto); con menos, las previas
  debajo del campo (RF-41). Botón "Ver todo" → `AlertDialog` ancho (`DialogProperties(usePlatformDefaultWidth =
  false)`, ~90 % del ancho y del alto) con la entrada de esta consulta marcada "Esta consulta" y después las previas,
  con desplazamiento (RF-40). Con `ctx.soloLectura` el campo es solo texto (RF-42). Qué entrada se edita y qué va a la
  derecha lo deciden `entradaDeConsulta`/`historialEstudio` (sección 5) sobre `registrosDelPaciente(tipo)` (todas las
  del paciente en la HC, ya ordenadas por `fecha DESC, updated_at DESC`). Sin "Agregar", sin pager, sin "Anterior":
  las previas no se editan desde acá.
- (Cambio 2026-10-14) `ConsultaViewModel`: `guardarEstudio(tipo, fecha, texto)` aplica `cambioEstudio` (sección 5):
  crear, actualizar o borrar (`hc.guardarRegistro`/`hc.eliminarRegistro`) **en el dispositivo**, con la misma espera
  y el mismo volcado al salir (`onCleared`) que el autoguardado de valores, para no escribir en la base con cada
  tecla ni perder lo último; nunca llama a la API (como hoy, `marcarSucia` solo encola el envío diferido, y "clinica"
  no tiene `HcBackend` hasta la 002).
- (Cambio 2026-10-14 (2)) `secciones/LaboratorioSeccion.kt` (el renderer de `TipoSeccion.LABORATORIO`, compartido
  por todas las especialidades):
  - Filas (RF-43 a RF-49): lee las ocultas de `ui.preferencias[clavePreferenciaFilasOcultas(def.codigo,
    labDef.tipo)]` con `ocultasDePreferencia` y recorre `filasVisibles(labDef.analitos, ocultas)` en vez de
    `labDef.analitos` (cabecera de grupo y franjas alternadas salen de la lista filtrada, así un grupo sin filas
    visibles no aparece). El mapa `valores` de la carga sigue arrancando con **todos** los valores de
    `enEstaConsulta` y `guardarLaboratorio` lo manda entero: las ocultas se guardan como estaban (RF-46). Si
    `puedeElegirFilas(def.codigo, labDef.analitos)`, `trailing` del `SectionCard` con `LinkButton("Elegir filas")`
    (o "Elegir filas (n ocultas)", `textoBotonFilas`), también con `ctx.soloLectura`.
  - Diálogo "Elegir filas" (`AlertDialog` en el mismo archivo): una `CheckboxField` de `core/ui` por analito con su
    grupo como subtítulo, dentro de un `Column` con `verticalScroll` y alto máximo (el general tiene 51 filas);
    cada cambio llama a `ctx.vm.guardarPreferencia(clave, alternarFila(...).joinToString(","))` y la tabla se
    redibuja sola porque `ConsultaUi.preferencias` ya es un flujo de la base. La casilla de la única visible va con
    `enabled = false` y el texto "Tiene que quedar al menos una fila visible" (RF-47). `LinkButton("Mostrar todas")`
    → `mostrarTodasLasFilas`; "Cerrar" no cambia nada más.
  - Preferencia (RF-44): misma tabla `medico_preferencia` y mismo `ConsultaViewModel.guardarPreferencia` →
    `HcRepository.guardarPreferencia` que `secciones_ocultas_clinica` (RF-28): mismo `dirty` que hoy (lo lee solo
    el `SyncEngine`, que la app no usa contra ningún servidor), nunca `HcApiSync` ni la API de hclinica. Clave
    `filas_ocultas_<codigo>_<laboratorioTipo>` (`filas_ocultas_clinica_laboratorio_general`,
    `..._laboratorio_reducido`), valor CSV de `AnalitoDef.clave` (sin comas: son `[a-z0-9_]`). No pasa por
    `ConfigSeccionesHcScreen`.
  - Celda editable (RF-50 a RF-54): la `CeldaEditable` privada (hoy `background(surface)` sobre el `SectionCard`, que
    también es `surface`: no se ve) se reemplaza por `CeldaEditable` de `core/ui`. Las celdas de solo lectura no
    cambian (texto sin borde ni fondo propio). Como el renderer es uno solo, el aspecto nuevo llega también a
    pediatría, endocrinología y las demás: es lo buscado (RF-54); el comportamiento no cambia.
- (Cambio 2026-10-14 (3)) `secciones/LaboratorioSeccion.kt`, varias cargas (RF-55 a RF-64): si
  `cargasMultiples(def.codigo)` (sección 5), una rama nueva; si no, el código de hoy intacto (`firstOrNull` de esta
  consulta, "Nueva carga"/"Editar", `take(8)`; RF-64, RF-33).
  - Estado: `abierta: Boolean` (columna editable), `fecha` (arranca en `fechaConsulta(ui)` cada vez que se abre) y
    `valores` (arranca vacío), con `remember` sin clave de carga (ya no hay "la" carga de la consulta); `error`
    para "Cargá al menos un valor".
  - Columnas: `columnasLaboratorio(historico, ctx.consultaId, editable = !ctx.soloLectura)`. Cabecera de cada una:
    fecha, "Esta consulta" (texto chico) si `deEstaConsulta`, y el tacho si `puedeBorrar` → el `ConfirmDialog` de hoy
    → `eliminarLaboratorio` (RF-60). Valores de las de esta consulta en `SemiBold`, como hoy la de la consulta; las
    demás normales; sin borde ni fondo propio (RF-50).
  - `ActionRow` (sin `ctx.soloLectura`): con `abierta`, `DateField` de la fecha; si `mostrarNuevaCarga(editable,
    abierta)`, `AcceptButton("Nueva carga")` que abre con fecha de la consulta y valores vacíos (RF-55, RF-56). Con
    `abierta`, la columna editable va primera con `CeldaEditable` en las filas de `filasVisibles` (RF-56) y abajo
    "Cancelar" (cierra y descarta, RF-58) y "Guardar laboratorio": `cargaNuevaValida(valores)`; si no, `error` y
    sigue abierta; si sí, `ctx.vm.guardarLaboratorio(tipo, null, fecha, valores)` y se cierra (RF-57).
  - Solo lectura (RF-63): `if (ctx.soloLectura && columnas.none { it.deEstaConsulta }) SeccionVacia()`, como hoy.
  - `ConsultaViewModel.guardarLaboratorio` no cambia: con `id = null` siempre crea (`newId()`), así una carga guardada
    nunca se pisa (RF-57, RF-59). En la HC general nadie le pasa un id.
- (Cambio 2026-10-14 (3)) `core/database` `HistoriaClinica.sq`: `laboratoriosDePaciente` pasa a `ORDER BY fecha DESC,
  updated_at DESC` (solo la consulta: sin columnas ni migración). Como en la HC general las cargas no se editan,
  `updated_at` es el momento en que se guardó (las borradas no se leen), y `columnasLaboratorio` conserva ese orden a
  igual fecha (RF-61). En pediatría solo fija el orden de dos cargas de la misma fecha, que hoy queda al azar.
- (Cambio 2026-10-15) `secciones/FormSeccion.kt` (serologías, RF-65 a RF-73): `FormSeccion` le pasa `s.modoForm` a
  `CamposForm`, que suma `modo: ModoForm = ModoForm.FILAS` **al final** (registros y diálogos lo siguen llamando sin
  él: nada cambia para ellos). Con `FILAS`, el código de hoy intacto. Con `GRILLA_SI_NO`:
  - `visibles` igual que hoy (`camposCompletos` en solo lectura: solo los cargados, RF-72; el `SeccionVacia` de
    `FormSeccion` sin ninguno sigue igual).
  - `BoxWithConstraints` → `columnasPara(maxWidth.value)` (2 o 4, RF-66) → `visibles.chunked(columnas)`: un `Row` por
    trozo con `SiNoVacio(c.etiqueta, valor, onCambio = { onCambio(c.clave, it) }, Modifier.weight(1f), soloLectura)`
    por campo y `Spacer(Modifier.weight(1f))` para completar la última fila (en solo lectura puede quedar
    incompleta). Grilla de ancho fijo con `Row` + `weight`, no `FlowRow` (`@ExperimentalLayoutApi` en esta versión de
    Compose y con anchos desparejos según la etiqueta). Al cambiar el ancho se recalcula sola y el estado vive en
    `valores` (casos límite).
  - El valor pasa tal cual: `SiNoVacio` devuelve `"SI"`, `"NO"` o `""` y `ctx.setValor` lo guarda como hoy guarda el
    `SelectField` (su "—" ya manda `""`): mismo autoguardado de 600 ms, sin API (RF-67, RF-68).
- (Cambio 2026-10-15 (3); RF-83 a RF-90) Motivo de consulta con historial:
  - `secciones/CampoConHistorial.kt` (nuevo, extraído de `EstudioConHistorialSeccion.kt` sin cambiar su dibujo):
    `data class EntradaHistorial(fecha: LocalDate?, texto: String)` y `@Composable CampoConHistorial(titulo,
    estaConsulta: EntradaHistorial, previas: List<EntradaHistorial>, campo: @Composable (Modifier) -> Unit)`: el
    `BoxWithConstraints` (≥ 600 dp: `Row` con `campo` a la izquierda y "Consultas previas" del mismo alto a la derecha;
    menos: debajo, RF-41/RF-88), el recuadro `ConsultasPrevias` con desplazamiento propio y "Ver todo", y el
    `VerTodoDialog` ("Esta consulta" + previas, RF-40/RF-87). `EstudioConHistorialSeccion` lo usa pasando su campo
    (fecha + texto) y sus registros mapeados a `EntradaHistorial(fecha, textoEstudio)`: se tiene que ver igual que hoy.
  - `secciones/FormSeccion.kt`, `TextoSeccion`: si `s.conHistorial`, delega en `TextoConHistorialSeccion(s, ctx, ui,
    texto)` (nuevo, en `secciones/TextoConHistorialSeccion.kt`); si no, el código de hoy intacto, con su
    `TextoLibreConPrevias` (RF-90). `RenderSeccionSola` le pasa `ui` (ya lo tiene). Dentro:
    - `texto` = `valores["texto"]` que llega de `RenderSeccion` (sale de `vm.valores.collectAsState()` en
      `ConsultaScreen`): nunca `ctx.valor` (StateFlow `.value` sin observar; el bug de T44).
    - `previas` = `remember(s.id, ctx.consultaId) { ctx.vm.historialTexto(s.id, "texto") }.collectAsState(emptyList())`
      (recordado, como `registrosDelPaciente` en los estudios: si no, cada tecla crea un flujo nuevo), mapeadas a
      `EntradaHistorial(fecha, texto)`.
    - Campo: `TextAreaField(s.titulo, texto, { ctx.setValor(s.id, "texto", it) }, minLines = 10, readOnly =
      ctx.soloLectura)`, sin `DateField` (RF-85); en solo lectura y vacío, "Sin datos en esta consulta". Mismo
      `ctx.setValor` que hoy: autoguardado de 600 ms en el dispositivo, nunca la API (RF-86).
    - `estaConsulta` = `EntradaHistorial(fechaConsulta(ui), texto)` para "Ver todo" (RF-87).
    - `ctx.soloLectura && texto.isBlank() && previas.isEmpty()` → `SeccionVacia()` (RF-89). Si `s.conArchivos`,
      `ArchivosInline` debajo, como hoy (motivo no lo tiene).
  - `ConsultaViewModel.historialTexto(seccion, campo): Flow<List<EntradaTexto>>` = `combine(hc.observarConsultas(
    pacienteId, especialidad), hc.observarValoresDeCampo(...)) { c, v -> historialTexto(c, v, consultaId) }`; sin
    consulta, `flowOf(emptyList())`. Solo lectura de la base: no escribe ni marca nada `dirty`.
- `HistoriaClinicaScreen.kt` / `HistoriaClinicaViewModel`: definición resuelta en un `StateFlow`; con RF-19 o RF-26
  muestra el mensaje, no ofrece consulta nueva y deja abrir las anteriores en lectura; ofrece solo los tipos con
  secciones. La copia de diagnósticos pasa a mirar las secciones de los tipos y no `definicion.secciones` (si no, las
  legadas la dispararían).
- `ConfigSeccionesHcScreen.kt`: para "clinica" lista las secciones del perfil del médico (todas las pestañas, con el
  nombre del perfil), misma preferencia `secciones_ocultas_clinica` (RF-28). Nunca se poda la lista guardada: lo que
  ocultó vuelve oculto si el administrador quita y repone la sección.

### `core/ui` (cambio 2026-10-14 (2); RF-50 a RF-54)
- `theme/ColoresCelda.kt` (nuevo): `data class ColoresCelda(fondo, borde, bordeFoco, texto, ayuda)` y
  `coloresCelda(oscuro: Boolean)`, más `contraste(a: Color, b: Color): Float` (fórmula WCAG con
  `Color.luminance()`). Valores, tomados de la paleta existente:

  | | Claro | Oscuro |
  |---|---|---|
  | fondo | `primaryContainer` claro `#D5EBF1` | `Salud360Colors.DarkBackground` `#0F1B1E` (más hundido que la tarjeta `#16262B`) |
  | borde (1 dp) | `onSurfaceVariant` claro `#4F5B5E` (≈ 5,7:1 contra el fondo, ≈ 7:1 contra la fila blanca) | `onSurfaceVariant` oscuro `#B8C6CA` (≈ 10:1 / ≈ 9:1) |
  | bordeFoco (2 dp) y cursor | `TealStart` `#2C7B90` (≈ 3,9:1 / ≈ 4,8:1) | `primary` oscuro `#6EC1D6` (≈ 8,6:1) |
  | texto | `OnBackground` `#333333` | `DarkOnBackground` `#E6EDEF` |
  | ayuda ("Valor") | `#4F5B5E` | `#B8C6CA` |

  `outline` (`#B9C4C7`, ≈ 1,8:1 contra blanco) no sirve para el borde: no llega a 3:1.
- `components/FormFields.kt`: `CeldaEditable(valor, onCambio, modifier, ayuda = "Valor")`: `BasicTextField` de una
  línea con `decorationBox` (fondo `fondo`, `border(1.dp | 2.dp, borde | bordeFoco, shapes.extraSmall)` según
  `interactionSource.collectIsFocusedAsState()`, texto de ayuda si está vacío), `cursorBrush = SolidColor(bordeFoco)`
  (el de por defecto es negro y no se ve en oscuro), colores con `coloresCelda(LocalIsDarkTheme.current)`. Mismo
  ancho y relleno que la celda de hoy (120 dp) para no mover la tabla.

### `core/ui` (cambio 2026-10-15; RF-65 a RF-72)
`core/ui` no depende de `core/model`: el componente recibe textos, no `CampoDef`.
- `theme/ColoresSiNo.kt` (nuevo): `data class ColoresSiNo(fondoTarjeta, relleno, textoRelleno, borde, texto,
  bordeFoco)` y `coloresSiNo(oscuro: Boolean)`, con la `contraste` de `ColoresCelda.kt`. Valores de la paleta:

  | | Claro | Oscuro |
  |---|---|---|
  | fondoTarjeta | `surface` claro (blanco) | `Salud360Colors.DarkSurface` `#16262B` |
  | relleno (elegido) | `TealStart` `#2C7B90` (≈ 4,8:1 contra blanco) | `primary` oscuro `#6EC1D6` (≈ 8:1) |
  | textoRelleno y tilde | blanco (≈ 4,8:1 contra el relleno) | `DarkBackground` `#0F1B1E` |
  | borde (no elegido, 1 dp) | `#4F5B5E` (≈ 7:1) | `#B8C6CA` (≈ 9:1) |
  | texto (botón no elegido y nombre del marcador) | `OnBackground` `#333333` | `DarkOnBackground` `#E6EDEF` |
  | bordeFoco (2 dp) | `#333333` | `#E6EDEF` |

  El foco va con el color del texto y no con el relleno: sobre un botón elegido, un borde del mismo color que el
  relleno no se vería. `outline` no sirve para el borde (≈ 1,8:1, como en `ColoresCelda`).
- `components/SiNoVacio.kt` (nuevo):
  - Puras (sin Compose, para testear): `alternarSiNo(actual: String, tocado: String): String` y
    `columnasPara(anchoDp: Float): Int` (sección 5).
  - `@Composable SiNoVacio(etiqueta, valor: String, onCambio: (String) -> Unit, modifier, soloLectura = false)`:
    tarjeta (`fondoTarjeta`, borde `outline` decorativo, `shapes.small`) con la etiqueta arriba (`maxLines = 2`, sin
    elipsis, RF-66) y debajo dos botones del mismo ancho "SI" y "NO", de al menos 48 × 48 dp (RF-71). Elegido:
    fondo `relleno`, texto `textoRelleno` y una tilde (`Icons.Filled.Check`) antes del texto; no elegido: borde 1 dp
    `borde` y texto `texto` (RF-69, RF-70). Cada botón con `selectable(selected, role = Role.RadioButton,
    onClick = { onCambio(alternarSiNo(valor, opcion)) })` (Enter/espacio con el teclado) y borde de foco 2 dp
    `bordeFoco` con `collectIsFocusedAsState`, por fuera del botón con un margen de ~2 dp ("Decidido" C5-4; RF-71); la tarjeta con `semantics(mergeDescendants = false) {
    stateDescription = valor.ifBlank { "sin cargar" } }` y cada botón con `contentDescription = "$etiqueta, $opcion"`
    (el "elegido" lo pone `selected`). Valor que no es "SI", "NO" ni "" (casos límite): se muestra como texto debajo
    de la etiqueta y ningún botón elegido.
  - Con `soloLectura`: etiqueta y el valor como texto en una píldora con los colores del elegido, sin botones ni
    `selectable` (RF-72).
  - Reutilizable para cualquier SI / NO con "sin cargar"; `SiNoField` (sin vuelta a vacío) no se toca.

### `core/ui` (cambio 2026-10-15 (2); RF-79, RF-80)
- `components/OpcionElegible.kt` (nuevo): el `BotonSiNo` privado de `SiNoVacio.kt` pasa a ser público y genérico,
  `@Composable OpcionElegible(texto, descripcion, elegido, onClick, modifier, colores = coloresSiNo(LocalIsDarkTheme
  .current))`, con el mismo dibujo: `selectable(role = Role.RadioButton)`, foco 2 dp `bordeFoco` por fuera con ~2 dp
  de margen (C5-4), al menos 48 × 48 dp, elegido = `relleno` + tilde + `textoRelleno`, no elegido = borde 1 dp
  `borde` + `texto`, `contentDescription = descripcion`. Lo único nuevo: el texto puede ocupar varias líneas,
  centrado (`textAlign = TextAlign.Center`, sin `maxLines`), para criterios como "Severa (III) o refractaria".
- `SiNoVacio.kt`: `BotonSiNo` se reemplaza por `OpcionElegible(opcion, "$etiqueta, $opcion", …)`; serologías se ve y
  se anuncia igual (se comprueba a mano en T44). `ColoresSiNo` no cambia: Child-Pugh usa los mismos colores y
  `fondoTarjeta`, así que `ColoresSiNoTest` ya cubre RF-79.

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
| `layoutConsulta(tipo, ocultas, cumple): LayoutConsulta` (cambio 2026-10-14; reemplaza a `pestaniasConsulta`) | `pestanias` = "Datos" + grupos visibles cuyo título **no** es "Consulta" (`claveDeNombre`); `cuerpo` = las secciones visibles del primer grupo que se llama "Consulta", o vacío. Sin grupos: pestañas vacías y cuerpo vacío (pantalla de siempre) | RF-20, RF-21, RF-28, RF-35, RF-36 |
| `entradaDeConsulta(registros, consultaId): RegistroClinico?` | La entrada de esa consulta: la de fecha más reciente si hay varias (datos viejos), `null` si no hay | RF-38, RF-39, casos límite |
| `historialEstudio(registros, editada): List<RegistroClinico>` | Todas las del paciente menos la `editada`, sin texto vacío (`detalle` en blanco), por `fecha DESC` y después `updated_at DESC` | RF-38, RF-40 |
| `cambioEstudio(actual, fecha, texto): CambioEstudio` | `Crear` (sin entrada y texto con algo), `Actualizar` (con entrada y cambió fecha o texto), `Borrar` (con entrada y texto en blanco), `Nada` | RF-39 |
| `clavePreferenciaFilasOcultas(codigo, laboratorioTipo)` / `ocultasDePreferencia(valor?)` (cambio 2026-10-14 (2), `hcgeneral/FilasLaboratorio.kt`) | `"filas_ocultas_<codigo>_<tipo>"`; CSV → conjunto sin vacíos (null o "" → vacío) | RF-44 |
| `puedeElegirFilas(codigo, analitos)` | `codigo == CodigosHc.GENERAL` y más de un analito | RF-49 |
| `filasVisibles(analitos, ocultas): List<AnalitoDef>` | Los analitos sin los ocultos, en su orden; si no queda ninguno, todos; claves ocultas que el laboratorio no tiene se ignoran | RF-45, RF-47 |
| `alternarFila(analitos, ocultas, clave, visible): Set<String>` | Agrega o quita `clave`; si ocultarla deja sin visibles, devuelve `ocultas` igual; nunca toca claves de afuera del laboratorio | RF-43, RF-47 |
| `esUltimaVisible(analitos, ocultas, clave)` | La casilla que hay que deshabilitar | RF-47 |
| `mostrarTodasLasFilas(analitos, ocultas)` | `ocultas` menos las claves del laboratorio (las de afuera se conservan) | RF-48, casos límite |
| `textoBotonFilas(analitos, ocultas)` | "Elegir filas" o "Elegir filas (n ocultas)", contando solo las de ese laboratorio | RF-48 |
| `cargasMultiples(codigo)` (cambio 2026-10-14 (3), `hcgeneral/CargasLaboratorio.kt`) | `codigo == CodigosHc.GENERAL` | RF-64 |
| `columnasLaboratorio(historico, consultaId, editable, maxOtras = 8): List<ColumnaLaboratorio>` | `ColumnaLaboratorio(carga, deEstaConsulta, puedeBorrar)`. Sin las inactivas; orden por `fecha` descendente con orden **estable** (a igual fecha queda el de entrada, que la consulta trae por `updated_at DESC`); todas las de `consultaId` y las primeras `maxOtras` de otras consultas, intercaladas por fecha; `puedeBorrar = editable` para todas (de esta consulta y de otras) | RF-60, RF-61, RF-62, RF-63 |
| `mostrarNuevaCarga(editable, abierta)` | `editable && !abierta`: siempre, aunque la consulta ya tenga cargas; nunca dos columnas editables | RF-55, RF-56 |
| `cargaNuevaValida(valores)` | `true` si algún valor no está en blanco (el `ConsultaViewModel` ya descarta los vacíos al guardar) | RF-57 |
| `alternarSiNo(actual, tocado): String` (cambio 2026-10-15, en **`core/ui`** `components/SiNoVacio.kt`: la usa el componente y `core/ui` no depende de `core/data`) | `tocado == actual` → `""` (sin cargar); si no, `tocado`. Nunca devuelve `"NO"` sin que se haya tocado "NO". `actual` desconocido (no "SI"/"NO"/"") → `tocado` | RF-67, RF-68, casos límite |
| `columnasPara(anchoDp): Int` (ídem, `core/ui`) | `< 600` → 2; `>= 600` → 4 | RF-66 |
| `puntosDe(valor): Int?` (cambio 2026-10-15 (2), en **`especialidades/clinica`** `ChildPugh.kt`: es de Child-Pugh y ahí ya hay `commonTest`; hoy no existe, la cuenta está dentro del composable) | `"1"`, `"2"`, `"3"` → 1, 2, 3; cualquier otra cosa (`""`, `"0"`, `"4"`, texto) → `null` | RF-77, RF-78, casos límite |
| `alternarPuntos(actual, tocado): String` (ídem) | La regla de `alternarSiNo` de `core/ui` (la llama: `tocado == actual` → `""`, si no `tocado`), con nombre propio para Child-Pugh. `actual` desconocido → `tocado` | RF-76, casos límite |
| `totalChildPugh(valores: Map<String, String>): Int` (ídem) | Suma de `puntosDe` de las claves de `parametrosChildPugh`; sin ninguna, 0; los desconocidos no suman | RF-77 |
| `completosChildPugh(valores): Int` (ídem) | Cuántos de los cinco tienen `puntosDe != null` | RF-77 |
| `claseChildPugh(total, completos): String` (ídem) | `completos < 5` → `"—"`; si no, `total <= 6` → `"A"`, `<= 9` → `"B"`, si no `"C"` | RF-77 |
| `historialTexto(consultas: List<Consulta>, valores: Map<Id, String>, consultaActual: Id): List<EntradaTexto>` (cambio 2026-10-15 (3), `hcgeneral/TextoConHistorial.kt`) | Una `EntradaTexto(consultaId, fecha de la consulta, texto)` por consulta de `consultas` que no es `consultaActual`, no está `ANULADA` (defensa: la consulta SQL ya las saca) y tiene en `valores` un texto que no está en blanco; ordenadas por `fecha` descendente con orden **estable** (a igual fecha queda el de entrada, que `consultasDePaciente` trae por `updated_at DESC`). Valores de consultas que no están en `consultas` se ignoran. Sin límite | RF-83, RF-84, RF-85 |

Acciones por columna en la HC general: columna editable → escribir, cambiar la fecha, "Guardar laboratorio",
"Cancelar"; columna guardada (de esta consulta o de otra) → solo leer y, con la consulta editable, tacho; nunca
"Editar" (RF-59).

No dependen de "hoy": ninguna regla de esta spec mira fechas (la fecha por defecto de RF-39 es la de la consulta, que
la pantalla le pasa a `cambioEstudio`).

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
- (Cambio 2026-10-14) `core/data` commonTest: `DefinicionHcGeneralTest` suma `layoutConsulta` (RF-35, RF-36) y
  `EstudioConHistorialTest` (nuevo: `entradaDeConsulta`, `historialEstudio`, `cambioEstudio`; RF-38, RF-39);
  `CatalogoHcGeneralTest` verifica que los cinco estudios tienen `UNO_POR_CONSULTA` y el resto de los registros
  `LISTA`. Los RF-37, RF-40, RF-41 y RF-42 se prueban a mano con `devWeb`.
- (Cambio 2026-10-14 (2)) `core/data` commonTest `FilasLaboratorioTest` (analitos inventados, con grupos): RF-44,
  RF-45, RF-47, RF-48, RF-49. `core/ui` jvmTest `ColoresCeldaTest`: en los dos modos, `contraste` de borde y
  bordeFoco contra el fondo de la celda ≥ 3, de borde contra `surface` y contra `surfaceVariant` al 50 % sobre
  `surface` (las dos franjas de la fila) ≥ 3, de texto y ayuda contra el fondo ≥ 4,5, y fondo distinto de `surface`
  (RF-50 a RF-52); más un caso conocido de `contraste` (negro/blanco = 21). RF-43, RF-46, RF-50, RF-52 (foco y
  cursor), RF-53 y RF-54 a mano con `devWeb`, en modo claro y oscuro.
- (Cambio 2026-10-14 (3)) `core/data` commonTest `CargasLaboratorioTest` (cargas inventadas): RF-55, RF-57, RF-60
  a RF-62, RF-64. `core/data` jvmTest `LaboratoriosDePacienteTest` (SQLite en archivo temporal, como
  `DatosConsultaTest`): dos cargas con la misma fecha salen la última guardada primero, y una inactiva no sale
  (RF-61). RF-56, RF-58, RF-59, RF-63 y la pantalla de pediatría (RF-64) a mano con `devWeb`.
- (Cambio 2026-10-15) `core/ui` jvmTest `SiNoVacioTest`: `alternarSiNo` en sus seis combinaciones de `""`/"SI"/"NO" ×
  "SI"/"NO" (tocar el elegido → `""`; ninguna da "NO" sin tocar "NO") y con un valor desconocido; `columnasPara` en
  320, 599,9 → 2 y 600, 1200 → 4. `ColoresSiNoTest`: en los dos modos, relleno y borde contra `fondoTarjeta` ≥ 3,
  bordeFoco contra `fondoTarjeta` ≥ 3 y bordeFoco ≠ relleno (el foco va por fuera del botón, "Decidido" C5-4),
  textoRelleno contra relleno ≥ 4,5, texto contra `fondoTarjeta` ≥ 4,5, y relleno ≠ fondoTarjeta (RF-70, RF-71). `especialidades/clinica` `CatalogoHcGeneralTest`:
  serologías del catálogo con `GRILLA_SI_NO`, sus 16 campos en el orden de RF-65 con las claves de hoy y todos
  `SELECT ["SI", "NO"]`; ninguna otra sección del catálogo, ni `hepatologia`/`clinicaMedica`, con `GRILLA_SI_NO`
  (RF-68, RF-73). RF-65, RF-67 (pantalla), RF-69, RF-71 (teclado y lector), RF-72 y el resto de RF-73 a mano con
  `devWeb`, en claro y oscuro, ancho y angosto.
- (Cambio 2026-10-15 (2)) `especialidades/clinica` commonTest `ChildPughTest`: `parametrosChildPugh` con las cinco
  claves de hoy en el orden de RF-74 y tres criterios cada uno con los textos de RF-74 (sin "(1)"/"(2)"/"(3)");
  `puntosDe` de "1", "2", "3" y de `""`, "0", "4", "x" → `null`; `alternarPuntos` (`""`+"2" → "2", "2"+"2" → `""`,
  "2"+"3" → "3", "x"+"1" → "1"); `totalChildPugh` sin valores → 0, cinco "3" → 15, parcial y con un "4" que no suma;
  `claseChildPugh` en los bordes 6 → A, 7 y 9 → B, 10 y 15 → C, y con 4 completos → "—" (RF-74, RF-76 a RF-78,
  casos límite). `core/ui` `ColoresSiNoTest`: el color del total contra `fondoTarjeta` ≥ 4,5 en claro y oscuro
  ("Decidido" 33). `CatalogoHcGeneralTest` sigue en verde (las `SeccionDef` de `child_pugh` y los dos renderers no
  cambian). RF-74, RF-75, RF-79, RF-80, RF-81 y RF-82 a mano con `devWeb`, en claro y oscuro, ancho y angosto, y
  serologías igual que antes (por `OpcionElegible`).
- (Cambio 2026-10-15 (3)) `core/data` commonTest `TextoConHistorialTest` (consultas y valores inventados):
  `historialTexto` sin la actual; una anulada con texto → fuera; texto `""`, `"   "` y `"\n"` → fuera; una consulta sin
  valor → fuera; un valor de una consulta que no está en la lista → ignorado; orden por fecha descendente con una
  posterior a la actual incluida; dos con la misma fecha → el orden de entrada; la fecha es la de la consulta (RF-83 a
  RF-85). `core/data` jvmTest `ValoresDeCampoDePacienteTest` (SQLite en archivo temporal, como `DatosConsultaTest`):
  `observarValoresDeCampo` trae el `texto` de `motivo_consulta` de dos consultas del paciente en "clinica" y no el de
  otro paciente, otra especialidad, otra sección, otro campo, una consulta borrada ni una anulada (RF-84).
  `especialidades/clinica` `CatalogoHcGeneralTest`: `motivo_consulta` del catálogo con `conHistorial = true`, ninguna
  otra sección del catálogo, ni `hepatologia`/`clinicaMedica`, con `conHistorial` (RF-90). RF-83 (pantalla), RF-86,
  RF-87, RF-88, RF-89 y el resto de RF-90 a mano con `devWeb`, ancho y angosto, y los estudios con historial igual que
  antes (por `CampoConHistorial`).
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
API de configuración); **B** = 3 y 3b (8); **C** = 4 y 5 (9); **C2** = cambios de la prueba manual del
2026-10-14 (4: cuerpo de la consulta y estudios con historial, RF-35 a RF-42); **C3** = laboratorio, cambio del
2026-10-14 (2) (3: filas visibles y celda editable, RF-43 a RF-54); **C4** = varias cargas de laboratorio, cambio
del 2026-10-14 (3) (2: lógica y pantalla, RF-55 a RF-64); **C5** = serologías en grilla, cambio del 2026-10-15
(2: lógica, colores y catálogo; pantalla; RF-65 a RF-73); **C6** = Child-Pugh como la web, cambio del 2026-10-15 (2)
(2: lógica pura; pantalla; RF-74 a RF-82); **C7** = motivo de consulta con historial, cambio del 2026-10-15 (3) (2:
lógica pura, consulta de lectura y marca en el catálogo; pantalla; RF-83 a RF-90); **D** = 6 y 7 (4). Cada tanda se
aprueba antes de generar su `tasks.md`.

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
| RF-33 | `grupos` opcional, registro estático para el resto, sin backend "clinica"; excepción RF-54; `cargasMultiples` (RF-64) | Tests existentes en verde, `CatalogoHcGeneralTest`, manual pediatría |
| RF-34 | `seccionesDelPacienteSoloLectura`, bloque de lectura en "Datos" (T27/T30) | `SeccionesSoloLecturaTest`, manual |
| RF-35 | `layoutConsulta` (cuerpo = grupo "Consulta"), `ConsultaScreen` pestañas + cuerpo debajo (T32/T33) | `DefinicionHcGeneralTest`, manual |
| RF-36 | `layoutConsulta` (sin "Consulta": todo pestañas; solo "Consulta": "Datos" + cuerpo) (T32/T33) | `DefinicionHcGeneralTest`, manual |
| RF-37 | `ConsultaScreen`: bloque de RF-22 después del cuerpo, fuera de la pestaña elegida (T33) | Manual |
| RF-38 | `ModoRegistro.UNO_POR_CONSULTA` en los cinco estudios, `entradaDeConsulta`, `historialEstudio`, `EstudioConHistorialSeccion` (T34/T35) | `EstudioConHistorialTest`, `CatalogoHcGeneralTest`, manual |
| RF-39 | `cambioEstudio`, `ConsultaViewModel.guardarEstudio` (local, con espera) (T34/T35) | `EstudioConHistorialTest`, manual |
| RF-40 | "Ver todo" en `EstudioConHistorialSeccion` (T35) | Manual |
| RF-41 | `BoxWithConstraints` < 600 dp (T35) | Manual (ventana angosta / teléfono) |
| RF-42 | `ctx.soloLectura` en `EstudioConHistorialSeccion` (T35) | Manual |
| RF-43 | Diálogo "Elegir filas" en `LaboratorioSeccion`, `alternarFila` (T37) | `FilasLaboratorioTest`, manual |
| RF-44 | `clavePreferenciaFilasOcultas`, `guardarPreferencia` como RF-28 (T36/T37) | `FilasLaboratorioTest`, manual (otro médico, otro laboratorio) |
| RF-45 | `filasVisibles` en el renderer, también con `ctx.soloLectura` (T36/T37) | `FilasLaboratorioTest`, manual |
| RF-46 | `valores` de la carga con todas las claves; `guardarLaboratorio` sin cambios (T37) | Manual (ocultar, editar, guardar, mostrar) |
| RF-47 | `filasVisibles` (ninguna → todas), `alternarFila`, `esUltimaVisible` (T36/T37) | `FilasLaboratorioTest`, manual |
| RF-48 | `mostrarTodasLasFilas`, `textoBotonFilas` (T36/T37) | `FilasLaboratorioTest`, manual |
| RF-49 | `puedeElegirFilas` (T36/T37) | `FilasLaboratorioTest`, manual (Peso y pediatría sin botón) |
| RF-50 | `CeldaEditable` de `core/ui` en `LaboratorioSeccion` (T38) | `ColoresCeldaTest` (fondo ≠ `surface`), manual claro y oscuro |
| RF-51 | `coloresCelda` (T38) | `ColoresCeldaTest` |
| RF-52 | Borde 2 dp `bordeFoco` y `cursorBrush` en `CeldaEditable` (T38) | `ColoresCeldaTest`, manual con tabulador |
| RF-53 | Texto de ayuda en el `decorationBox` (T38) | Manual |
| RF-54 | Un solo renderer de laboratorio para todas las especialidades (T38) | Manual (pediatría) |
| RF-55 | `mostrarNuevaCarga`, botón en la rama de `cargasMultiples` de `LaboratorioSeccion` (T39/T40) | `CargasLaboratorioTest`, manual |
| RF-56 | Columna editable primera con `fechaConsulta(ui)`, `CeldaEditable` en `filasVisibles` (T40) | Manual |
| RF-57 | `cargaNuevaValida`; `guardarLaboratorio(tipo, null, ...)` siempre crea (T39/T40) | `CargasLaboratorioTest`, manual |
| RF-58 | "Cancelar" cierra y descarta (T40) | Manual |
| RF-59 | Sin "Editar" en la rama de `cargasMultiples`; nadie pasa un id a `guardarLaboratorio` (T40) | Manual |
| RF-60 | `ColumnaLaboratorio.puedeBorrar`, `ConfirmDialog` + `eliminarLaboratorio` (T39/T40) | `CargasLaboratorioTest`, manual |
| RF-61 | `columnasLaboratorio` (orden estable) + `ORDER BY fecha DESC, updated_at DESC`; "Esta consulta" en la cabecera (T39/T40) | `CargasLaboratorioTest`, `LaboratoriosDePacienteTest`, manual |
| RF-62 | `columnasLaboratorio(maxOtras = 8)` (T39) | `CargasLaboratorioTest` |
| RF-63 | `editable = !ctx.soloLectura`; `SeccionVacia` sin cargas de esta consulta (T39/T40) | `CargasLaboratorioTest`, manual (consulta cerrada) |
| RF-64 | `cargasMultiples`; rama de hoy intacta para las demás (T39/T40) | `CargasLaboratorioTest`, manual (pediatría) |
| RF-65 | `ModoForm.GRILLA_SI_NO` en serologías de `catalogoHcGeneral`; `CamposForm` en grilla con `SiNoVacio` (T41/T42) | `CatalogoHcGeneralTest` (orden), manual |
| RF-66 | `columnasPara`, `BoxWithConstraints` + `chunked` + `weight` (T41/T42) | `SiNoVacioTest`, manual (ancho y angosto) |
| RF-67 | `alternarSiNo` en `SiNoVacio` (T41/T42) | `SiNoVacioTest`, manual |
| RF-68 | Mismos `CampoDef`; `""`/"SI"/"NO" por `ctx.setValor` como el `SelectField` (T41/T42) | `SiNoVacioTest`, `CatalogoHcGeneralTest`, manual (consulta con serologías cargadas antes) |
| RF-69 | Relleno + tilde / borde en `SiNoVacio` (T42) | Manual claro y oscuro |
| RF-70 | `coloresSiNo` (T41) | `ColoresSiNoTest` |
| RF-71 | `selectable(role = RadioButton)`, foco 2 dp, 48 dp, `stateDescription`/`contentDescription` (T41/T42) | `ColoresSiNoTest` (foco), manual (tabulador, lector de pantalla) |
| RF-72 | `camposCompletos` + `SiNoVacio(soloLectura)` en la grilla (T42) | Manual (consulta cerrada, solo lectura de RF-22) |
| RF-73 | `modoForm = FILAS` por defecto; solo la copia de `HcGeneral.kt` en grilla (T41) | `CatalogoHcGeneralTest`, manual (hepatología estática, pediatría) |
| RF-74 | `parametrosChildPugh`; tabla con `OpcionElegible` en `ChildPughSeccion` (T43/T44) | `ChildPughTest` (orden y textos), manual (ancho) |
| RF-75 | `BoxWithConstraints` < 600 dp, bloques con opciones apiladas (T44) | Manual (angosto, cambiar de ancho) |
| RF-76 | `alternarPuntos` → `ctx.setValor` (T43/T44) | `ChildPughTest`, manual |
| RF-77 | `totalChildPugh`, `completosChildPugh`, `claseChildPugh`; encabezado (T43/T44) | `ChildPughTest`, manual |
| RF-78 | Mismas claves; `"1"`/`"2"`/`"3"`/`""` por `ctx.setValor`; `puntosDe` (T43/T44) | `ChildPughTest`, manual (consulta con Child-Pugh cargado antes) |
| RF-79 | `OpcionElegible` con `coloresSiNo` sobre `fondoTarjeta` (T44) | `ColoresSiNoTest` (ya existe), manual claro y oscuro |
| RF-80 | `OpcionElegible` (`selectable`, foco, 48 dp), `descripcion`, `selectableGroup` + `stateDescription` (T44) | Manual (tabulador, lector de pantalla) |
| RF-81 | Rama de solo lectura de `ChildPughSeccion`; `SeccionVacia` sin valores (T44) | Manual (consulta cerrada, solo lectura de RF-22) |
| RF-82 | Un solo renderer `child_pugh` en `hcGeneralContribution` y `hepatologiaContribution` (T44) | `CatalogoHcGeneralTest` (renderers), manual (pediatría y demás secciones igual) |
| RF-83 | `historialTexto`, `ConsultaViewModel.historialTexto`, `TextoConHistorialSeccion` + `CampoConHistorial` (T45/T46) | `TextoConHistorialTest`, manual |
| RF-84 | `valoresDeCampoDePaciente` (sin anuladas ni borradas) + `consultasDePaciente`; `historialTexto` sin la actual (T45) | `TextoConHistorialTest`, `ValoresDeCampoDePacienteTest`, manual (consulta vieja ve las posteriores) |
| RF-85 | Fecha de `Consulta` en `EntradaTexto`; sin `DateField` en el campo (T45/T46) | `TextoConHistorialTest`, manual (cambiar la fecha de una consulta) |
| RF-86 | `ctx.setValor(s.id, "texto", …)` como hoy (autoguardado, sin API) (T46) | Manual (salir enseguida y volver) |
| RF-87 | `VerTodoDialog` de `CampoConHistorial` con `fechaConsulta(ui)` (T46) | Manual |
| RF-88 | `BoxWithConstraints` < 600 dp de `CampoConHistorial` (T46) | Manual (angosto) |
| RF-89 | `ctx.soloLectura` en `TextoConHistorialSeccion`; `SeccionVacia` sin texto ni previas (T46) | Manual (consulta cerrada, solo lectura de RF-22) |
| RF-90 | `SeccionDef.conHistorial = false` por defecto; solo `motivo_consulta` de `catalogoHcGeneral` en `true`; `TextoSeccion` de hoy intacto sin la marca (T45/T46) | `CatalogoHcGeneralTest`, manual (conductas, hepatología aparte, pediatría igual) |

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
7. ~~**Pantalla del médico**: pestañas reales ("Datos" | pestañas del perfil), abriendo en "Datos"; el bloque de solo
   lectura (RF-22) va debajo de la pestaña elegida.~~ **Reemplazado** por el cambio del 2026-10-14 ("Decidido" 9).
8. **Médico sin perfil (RF-19)**: no puede crear consultas nuevas; solo lee las anteriores.

## Decidido (2026-10-14, cambio de la prueba manual de la tanda C)
9. **Pantalla del médico** (decisión del usuario; spec RF-35 a RF-37): layout de pediatría y de la web. Arriba
   "Datos" (abre ahí) y las pestañas del perfil menos la que se llama "Consulta" (`claveDeNombre`); debajo, siempre
   visible, el cuerpo = las secciones de "Consulta". Sin pestaña "Consulta": todo pestañas, como hasta ahora. Solo
   "Consulta": "Datos" + cuerpo. El bloque de RF-22 va al final, después del cuerpo, con cualquier pestaña; el de
   RF-34, solo en "Datos". Descartado: marcar el cuerpo con un dato nuevo en el perfil (exige cambiar las tablas y la
   API de hclinica y el editor; el nombre ya es lo que usan la web y los dos perfiles iniciales).
10. **Estudios con historial** (decisión del usuario; spec RF-38 a RF-42): modo `UNO_POR_CONSULTA` del renderer de
    registros, no un `renderKey` CUSTOM: la sección sigue siendo `REGISTROS` (mismo `registroTipo`, mismo campo
    `detalle`, misma tabla `registro_clinico`), así "tener datos" de RF-22, el bloque de solo lectura y los datos ya
    cargados siguen igual, y no hace falta migración. Una fila por consulta con `fecha` + `detalle` es lo mismo que
    guarda la web (`biopsia_hepaticas`, `videoendoscopia_digestiva_altas`, `ecografia_abdominals`,
    `estudios_cardiologicos`, `tc_rmn_colangiormns`: `consulta_id`, `paciente_id`, `descripcion`, `fecha`, `activo`;
    `guardar*` de `MedicoController` actualiza la fila de esa consulta o la crea), así que la 002 la mapea 1 a 1.
    Lo que muestra la web (`cargar*` con `tipoConsulta = 1`): todas las filas activas del paciente por `id DESC`; la
    primera va al campo de la izquierda (con su fecha) y las demás, `fecha + "\n" + descripcion` separadas por una
    línea en blanco, sin las de descripción vacía, al textarea deshabilitado "Consultas previas"; al ver una consulta
    cerrada (`tipoConsulta = 2`) solo la descripción de esa consulta. La app ordena por fecha (no por `id`, que no
    tiene) y deja el campo vacío si la consulta no tiene entrada ("Para decidir" C2-1). Descartado: `renderKey`
    CUSTOM (RF-22 mira `valores` para CUSTOM y no vería los registros) y un `TipoSeccion` nuevo (obliga a tocar todos
    los `when` de pediatría y las demás).

## Decidido (2026-10-14, cambio (2): laboratorio)
11. **Elegir filas desde la sección** (decisión del usuario; RF-43): botón en el `SectionCard` del laboratorio y
    diálogo con casillas que se aplican al momento. Descartado: hacerlo en Configuración (lejos de la tabla; el
    médico no ve el efecto mientras elige).
12. **Fila con valores se oculta igual** (decisión del usuario; RF-45, RF-46): como las secciones ocultas de RF-28;
    los datos no se tocan y "Mostrar todas" los vuelve. Descartado: no dejar ocultar filas con valores (en el
    laboratorio general casi todas tienen alguno en alguna fecha).
13. **Preferencia por médico, por especialidad y por laboratorio, en el dispositivo** (RF-44): clave
    `filas_ocultas_<codigo>_<laboratorioTipo>` en `medico_preferencia`, con el mismo guardado (y el mismo `dirty`)
    que `secciones_ocultas_<codigo>`. Por laboratorio y no por analito global, porque Laboratorio general y reducido
    comparten nombres pero son tablas distintas. Descartado: guardarla en la HC general (exige tabla y endpoint
    nuevos en hclinica, y RF-28 ya es local).
14. **Al menos una fila visible** (RF-47): casilla de la última deshabilitada con el mensaje, y `filasVisibles`
    devuelve todas si lo guardado no deja ninguna (defensa ante datos viejos). Descartado: permitir ocultarlas todas
    y mostrar un aviso en la tabla vacía (la sección quedaría sin forma de cargar).
15. **Solo HC general** (RF-49, RF-33): `puedeElegirFilas` mira `CodigosHc.GENERAL`. La lógica y la clave ya son por
    código de especialidad, así que extenderlo es cambiar esa condición (ver "Decidido" C3-1).
16. **Celda editable en `core/ui`, para todas las especialidades** (RF-50 a RF-54): un componente `CeldaEditable` con
    colores de la paleta existente y su test de contraste; el cambio de aspecto llega a pediatría y las demás porque
    el renderer es uno solo, y es lo deseado (el problema es el mismo). Descartado: `NumberField`/`OutlinedTextField`
    (etiqueta y alto de un campo de formulario: no entran en una celda de 120 dp) y estilo solo para "clinica" (dos
    aspectos para la misma tabla).

## Decidido (2026-10-14, tanda C3, antes "Para decidir")
C3-1. **Elegir filas en las demás historias clínicas** (pediatría, endocrinología, hematología… las que tengan
   laboratorio con más de una fila): queda solo en la HC general en esta spec (RF-49, para cumplir RF-33).
   Extenderlo a las demás especialidades es un cambio aparte (quitar la condición de `puedeElegirFilas` y probar
   pediatría), sin tocar datos ni API.

## Decidido (2026-10-14, cambio (3): varias cargas de laboratorio)
17. **Cada "Nueva carga" es una fila nueva** (decisión del usuario; RF-55 a RF-57): misma tabla `laboratorio`, que ya
    admite varias filas por consulta (`consulta_id` no es único): sin migración ni columnas. `guardarLaboratorio`
    con `id = null` siempre crea. Es lo que hace la web (cada guardado crea una carga con fecha propia), así que la 002
    (RF-20, RF-21) mapea cada carga a una fila de la web, 1 a 1; la app siempre manda fecha (por defecto la de la
    consulta), así que el "sin fecha → 2000-01-01" de la web no se da desde la app. Descartado: una carga por consulta
    con "Editar" (lo de hoy; no deja registrar dos laboratorios en la misma consulta).
18. **Las guardadas no se editan, se borran y se recargan** (decisión del usuario; RF-59, RF-60), como la web,
    también las de esta consulta. El tacho está en **todas** las columnas mientras la consulta se pueda editar (hoy
    ya está en las anteriores; la de esta consulta no lo tenía porque se editaba) y en ninguna con la consulta
    cerrada o en solo lectura, como hoy. Borrar es el borrado lógico de siempre (`activo = false`). Descartado: tacho
    solo en las de esta consulta (cambiaría lo que hoy se puede hacer con las anteriores).
19. **Orden y límite** (RF-61, RF-62): todo por fecha descendente, sin separar las de esta consulta (se marcan "Esta
    consulta"); a igual fecha, la última guardada primero, por `updated_at` en la consulta SQL y orden estable en
    `columnasLaboratorio` (el modelo `Laboratorio` no expone `updated_at` y no hace falta sumarlo). Se mantiene el
    límite de 8 de otras consultas que hay hoy, y las de esta consulta se muestran todas para que nunca quede una sin
    tacho. Descartado: las de esta consulta primero y después las anteriores (rompe la lectura por fechas de la
    tabla cuando la carga de hoy tiene una fecha vieja); quitar el límite (con muchas cargas la tabla se vuelve muy
    ancha; un "Ver más" queda para otra spec si hace falta).
20. **Carga vacía no se guarda** (RF-57): una columna sin valores no aporta nada y, como no se edita, solo se podría
    borrar. Descartado: guardarla como hoy (crea columnas vacías con cada clic en "Guardar").
21. **Sin autoguardado de la columna editable**: como hoy, el laboratorio se guarda con "Guardar laboratorio"; salir
    sin guardar descarta lo escrito (casos límite). Descartado: autoguardar (crearía cargas a medio escribir que no se
    pueden editar).

## Decidido (2026-10-14, tanda C4, antes "Para decidir")
C4-1. **Varias cargas solo en la HC general** (decisión del usuario; RF-64, RF-33): `cargasMultiples` mira
   `CodigosHc.GENERAL`, como `puedeElegirFilas`. Extenderlo a las demás historias clínicas es un cambio aparte que
   requiere relevar la API de pediatría. Motivo: pediatría tiene `HcPediatriaBackend` + `HcApiSync`, que hoy manda
   una carga por consulta que se edita; si su API acepta varias cargas por consulta y cómo las muestra su web no
   está relevado (y RF-33 dice que pediatría no cambia). El renderer es uno y la diferencia es una condición por
   código, igual que "Elegir filas"; extenderlo después es quitarla y probar contra la API de pediatría.
   Descartado: aplicarlo a todas (un solo comportamiento, pero cambia pediatría y su sincronización).

## Decidido (2026-10-15, cambio: serologías en grilla)
22. **Grilla de tarjetas SI / NO con vuelta a vacío** (decisión del usuario; RF-65 a RF-67): un toque por marcador y
    tocar el elegido lo deja sin cargar. Descartado: los 16 desplegables en una fila (lo de hoy: etiquetas cortadas e
    inutilizable en el teléfono), desplegables en varias filas (siguen siendo dos toques por marcador) y `SiNoField`
    (no deja volver a "sin cargar").
23. **2 o 4 columnas, sin paso intermedio** (RF-66): 16 se reparte justo en 2 y en 4 (filas completas); con 3 la última
    fila quedaría con una tarjeta sola. El corte en 600 dp del ancho de la sección es el mismo criterio que RF-41.
    Descartado: `FlowRow` con ancho según la etiqueta (filas desparejas y API experimental).
24. **Mismos valores que hoy** (RF-68): `"SI"`, `"NO"` y `""` en las mismas claves; sin migración, sin columnas y
    compatible con la 002 (RF-26 de la 002). Descartado: guardar como `SI_NO` o con otro código para "sin cargar".
25. **Componente en `core/ui` y modo opt-in en la `SeccionDef`** (RF-73): `SiNoVacio` sirve para cualquier SI / NO
    con vacío; `ModoForm.GRILLA_SI_NO` solo lo activa serologías de `catalogoHcGeneral`. Por sección y no por
    `CampoDef.grupo`: el modo cambia cómo se dibuja la sección entera y `grupo` ya significa "misma fila" para todas
    las especialidades. Descartado: cambiar el dibujo de todos los `SELECT ["SI", "NO"]` agrupados (tocaría
    `hepatologia`, `clinicaMedica` y cualquier otra especialidad con ese patrón, contra RF-33) y un `TipoSeccion` o
    `renderKey` CUSTOM nuevo (obliga a tocar los `when` de todas las especialidades, o RF-22 dejaría de ver los
    valores, como en "Decidido" 10).
26. **Solo lectura con solo los cargados, en la grilla** (RF-72): como hoy (`camposCompletos`), pero en tarjetas en
    vez de en una fila. Descartado: los 16 con "—" (en una consulta vieja con dos marcadores cargados, 14 tarjetas
    vacías tapan lo importante).

## Decidido (2026-10-15, tanda C5, antes "Para decidir")
C5-1. **Grilla también en la hepatología estática** (`hepatologia` de `ClinicaMedica.kt`, que desaparece en la tanda
   D): **no**, no cambia. Sus consultas pasan a "clinica" con `7.sqm` y desde ahí se ven con la grilla; aplicarla
   antes es una línea (`modoForm = GRILLA_SI_NO` en su `SeccionDef`) pero cambia una historia clínica que hoy RF-73
   deja como está, para unas semanas.
C5-2. **Color propio para "SI"** (reactivo, lo que más importa clínicamente): **no**, mismo relleno para SI y NO (la
   diferencia la dan el texto y la posición; dos colores exigen otro par con contraste en claro y oscuro y pueden
   leerse como "bien/mal").
C5-3. **Solo lectura**: solo los marcadores cargados ("Decidido" 26), no "los 16 con —".
C5-4. **Foco por fuera del botón** (coordinador, al empezar T41): el borde de foco de 2 dp se dibuja por fuera del
   botón con un margen de ~2 dp, así lo que tiene al lado es siempre `fondoTarjeta`. Motivo: en oscuro ningún color
   llega a 3:1 a la vez contra la tarjeta `#16262B` y contra el relleno `#6EC1D6` (haría falta luminancia ≥ 0,152 y
   ≤ 0,121); en claro `#333333` da 2,61 contra el relleno. El test pide bordeFoco contra `fondoTarjeta` ≥ 3 y
   bordeFoco ≠ relleno (RF-71 solo exige lo primero). Descartado: doble anillo (otro color y más dibujo) y oscurecer
   el relleno (no cierra con el texto a 4,5).

## Decidido (2026-10-15, tanda C6, antes "Para decidir"; cambio (2): Child-Pugh como la web)
27. **Tabla con celdas que se tocan, con el aspecto de los SI / NO** (decisión del usuario; RF-74, RF-76, RF-79):
    Parámetro | 1 | 2 | 3 bajo "Puntos", como `cps.blade.php`; la elegida con el relleno teal y la tilde de serologías
    (`ColoresSiNo`, ya con contrastes probados), no el amarillo de la web; tocar la elegida la deja sin cargar, como
    RF-67. Descartado: los radios de hoy (no vuelven a "sin cargar" y la fila se desborda), el amarillo de la web
    (otro par de colores para probar en claro y oscuro) y que tocar el nombre limpie el parámetro, como la web (una
    acción escondida, sin indicio visual, y un segundo camino para lo mismo).
28. **Total y clase en el encabezado, con los textos de la app** (decisión del usuario; RF-77): "Total: N · Clase X",
    clase "—" hasta tener los cinco, y "A: ≤ 6 · B: 7-9 · C: ≥ 10" (la web dice "C: >10", que deja afuera el 10). Se
    calculan, no se guardan (RF-78): la web guarda `total_puntos` y la 002 lo calcula al enviar (con `totalChildPugh`).
    Descartado: solo el total, como la web (la clase es lo que se usa clínicamente).
29. **Para las dos Child-Pugh, la de la HC general y la de la hepatología aparte** (RF-82): es el mismo renderer, no
    hay una `SeccionDef` que distinga, y la hepatología aparte desaparece en la tanda D. A diferencia de serologías
    (C5-1), dejarla como está exigiría dos renderers para unas semanas. Descartado: limitarlo a la HC general.
30. **Angosto: un bloque por parámetro con las opciones apiladas** (RF-75): cada opción a todo el ancho con "n puntos ·
    criterio". Descartado: las tres celdas en fila (en 320 dp quedan de ~90 dp y "Severa (III) o refractaria" se parte
    en tres o cuatro líneas) y `FlowRow` (experimental en esta versión y con filas desparejas).
31. **Solo lectura como texto** (RF-81): los cinco parámetros, cada uno con "n puntos · criterio" o "—", y el total y
    la clase. Descartado: la tabla con la celda elegida resaltada (los bordes de las demás parecen botones) y mostrar
    solo los cargados como serologías (son cinco, y saber cuál falta explica la clase "—").
32. **`OpcionElegible` en `core/ui` en vez de otro botón en `especialidades/clinica`**: el `BotonSiNo` de `SiNoVacio`
    pasa a público con el texto en varias líneas, así Child-Pugh y serologías comparten dibujo, foco y colores.
    Descartado: copiar el botón en `ChildPughSeccion.kt` (dos dibujos que se separan con el tiempo).
33. **Color del total con contraste probado** (decisión del usuario, al pasar a "Decidido" la tanda C6): el color del
    texto "Total: N · Clase X" entra al test de contraste de `core/ui` (`ColoresSiNoTest`), en claro y en oscuro, con
    al menos 4,5:1 contra `fondoTarjeta`. Si `Salud360Colors.Indigo` (el de hoy) no llega en oscuro, se usa `texto`
    de `ColoresSiNo`. Va en T44.

## Decidido (2026-10-15, cambio (3): motivo de consulta con historial)
34. **Mismo dibujo que los estudios con historial** (decisión del usuario; RF-83, RF-87, RF-88): el layout, el recuadro
    "Consultas previas" y "Ver todo" se extraen a `CampoConHistorial` y los usan los estudios y el motivo, así no se
    separan con el tiempo. Descartado: copiar `EstudioConHistorialSeccion` (dos dibujos) y hacer del motivo un
    registro `UNO_POR_CONSULTA` (cambiaría dónde se guarda, pediría migrar los motivos ya cargados en `seccion_valor` y
    rompería "tener datos" y el texto de las demás especialidades).
35. **Se guarda igual que hoy** (RF-86): `seccion_valor` (consulta, `motivo_consulta`, `texto`), `ctx.setValor` y el
    autoguardado de 600 ms; nada nuevo se escribe. Es una fila por consulta, como `motivo_consultas` de la web
    (`consulta_id`, `descripcion`, `fecha`; `guardarMotivoConsulta` actualiza la de esa consulta o la crea), así que la
    002 la mapea 1 a 1 y manda como `fecha` la de la consulta ("Decidido" C7-1).
36. **Historial = consultas no anuladas del paciente en la HC general** (RF-84): todas, de cualquier tipo y también las
    posteriores (como el "Decidido" 3 de la tanda C2 para los estudios), sin la que se ve; ordenadas por la fecha de la consulta.
    La web (`cargarMotivoConsulta`, `tipoConsulta = 1`) muestra las filas activas del paciente con `consulta_id <>`
    la actual, por `motivo_consultas.fecha DESC, id DESC`, como `fecha + "\n" + descripcion`, sin las vacías, y no
    mira si la consulta está anulada; la app sí las saca, como `ultimosValoresDeSeccion`. Sin límite de cantidad: el
    recuadro tiene desplazamiento (el `take(8)` de `TextoLibreConPrevias` no aplica acá).
37. **Consulta de lectura nueva y lógica pura** (`valoresDeCampoDePaciente` + `historialTexto`). Descartado: reutilizar
    `historialCampo` (no saca las anuladas; agregarle el filtro cambia el "Consultas previas" de texto de pediatría y
    las demás, contra RF-90) y filtrar en la pantalla (sin test).

## Decidido (2026-10-15, tanda C7, antes "Para decidir")
C7-1. **Sin fecha propia del motivo: usa la de su consulta** (decisión del usuario; RF-85). La web tiene una:
   `motivo_consultas.fecha`, con un selector en
   `motivo_consulta.blade.php` (solo al crear la consulta, por defecto hoy) que se guarda aparte de la fecha de la
   consulta, y sus previas se ordenan y muestran por esa fecha. En la app no: en la práctica es la fecha de la consulta, un segundo selector para el mismo dato
   confunde y no hay dónde guardarla sin un campo nuevo (`seccion_valor` `fecha`). La 002 manda la fecha de la consulta
   como `motivo_consultas.fecha` y, al leer de la web, se queda con la de la consulta. Descartado: un `DateField` como
   los estudios, guardado en el campo `fecha` de la misma sección (sin esquema nuevo), que la 002 mapea 1 a 1.
C7-2. **Opt-in por sección con `SeccionDef.conHistorial`, activada solo en motivo** (decisión del usuario; RF-90). Para
   las demás secciones de texto (conductas, nota, datos subjetivos/objetivos, exámenes…), en esta tanda nada:
   sumarla después a otra sección de la HC general es una línea en `HcGeneral.kt` y su caso en
   `CatalogoHcGeneralTest`, sin tocar la pantalla. Cuáles se activan lo decide el usuario en otro cambio, después de
   mirar cómo las muestra la web. Descartado: condicionar por id (`motivo_consulta` y
   `CodigosHc.GENERAL`) dentro de `TextoSeccion` (no se reutiliza sin tocar código de pantalla) y activarlo en todas
   las de texto de la HC general (no lo pidió el usuario y cambia la carga de conductas y nota).

## Decidido en la tanda D (2026-10-26) — aprobado por el usuario el 2026-10-26
Detalle, tareas T47 a T53 y decisiones D1 a D7 (todas las recomendadas) en `tasks.md` ("Tanda D"). Lo que cambia respecto del plan de arriba,
que se conserva como se aprobó (con `7.sqm` y la hepatología "hasta la D"); donde difiere, manda esta sección:
1. **Numeración de la migración:** `7.sqm` (sección 4 `core/database`, sección 9 `MigracionHepatologiaTest` "de 7 a 8", sección 8
   "Hepatología se migra con `7.sqm`") ya la usó la spec 003 (`hc_enfermedades`, esquema v8). La migración de hepatología es
   **`8.sqm` (v8 → v9)**, solo datos: no cambia ningún `.sq` ni agrega columnas.
2. **`medico.especialidades_hc` y `especialidad.codigo_hc` salen de la migración** (la sección 4 los incluía con `REPLACE`): son datos de
   turnos (dueño remoto) y se normalizan al leer (`MedicoRow.toModel`, `EspecialidadRow.toModel`) y al guardar (`TurnosImportador`,
   `AgendaTurnosOnline`). Menos SQL en la migración, que en el navegador, si falla, recrea la base vacía.
3. **`paciente_extra` faltaba en el inventario:** tiene `PRIMARY KEY (paciente_id, especialidad, clave)`, así que mover `hepatologia` →
   `clinica` puede chocar y hacer fallar toda la migración. Pasa solo la fila que no choca (`NOT EXISTS`).
4. **Choques (riesgo de la sección 10, "el test de migración tiene que fijar cuál se muestra"):** antecedente repetido → queda activa la
   modificada más reciente y la otra `activo = 0`; consultas abiertas en las dos HC → las dos abiertas; secciones ocultas → unión y la
   clave vieja con `deleted = 1`. Preguntas D2 a D4.
5. **`normalizarHistoriasClinicas` y `codigoHcPorNombre`** (sección 5 y sección 4 `core/data`): archivo `hcgeneral/HistoriasClinicas.kt`; la
   lectura se normaliza en `MedicoRow.toModel`, el único punto por el que la base alimenta la sesión y el panel. `codigoHcPorNombre`
   pasa a función suelta para probarla sin base.
6. **`hepatologiaContribution` se borra pero `val hepatologia` queda** (`internal`): `HcGeneral.kt` copia de ahí secciones, laboratorios y
   registros. El test `hepatologia_sigue_igual` se reemplaza por uno que fija que todo lo que la hepatología guardaba se lee desde la HC
   general (RF-29, RF-30); lo cita `specs/003-hc-enfermedades/plan.md` (RF-57, tabla de pruebas).
7. **El servidor no se toca** (sección 6, punto 2 y "Decidido" 3 de 2026-10-09: "la app normaliza"): turnos sigue ofreciendo y devolviendo
   `hepatologia` y hclinica acepta las dos (`hc_codigos`). Cambiarlo es la pregunta D1 (opciones B y C, "preguntar antes").
8. **Fase 7 / `HcPerfilesE2ETest`:** nunca se escribió. Queda fuera de la tanda D (pregunta D7); la tanda D de la sección 11 ("D = 6 y 7") se reduce
   a la fase 6 más el cierre de documentación.
9. **Riesgo nuevo, de la sección 10:** el driver web (`DriverFactory.wasmJs.kt`) descarta todas las tablas si una migración falla o si la base
   es de una versión más nueva: ni la migración ni una app vieja sobre una base migrada tienen vuelta atrás en la web (pregunta D5).
