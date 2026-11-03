# Plan 002, parte 2, entrega L — Consultas de la web en la app, solo lectura

Estado: **aprobado por el usuario el 2026-10-28** (con las dudas Q1 a Q18 resueltas: Q15 = nunca borrar, el resto las
recomendaciones del planificador). La aprobación incluye que la tanda S del servidor (E3 y E4) puede empezar; la
subida a producción (E9) requiere aprobación aparte. Cubre RF-38 a RF-63 de `spec.md` (y RF-1 a RF-4 y RF-25 en lo que
toca a leer). Parte 1 (pacientes y cartera): `plan-parte1.md`. Depende de la spec 001 (perfiles,
secciones y esquema local v9) y de la parte 1 de esta spec; la sección Enfermedades (spec 003) no cambia.
El borrador de abajo ("Borrador previo") **queda reemplazado** para la entrega L; para la entrega E (escritura) hay que
rehacerlo cuando se decida (ver "Entrega E, reservada"). No se borra: sigue siendo la referencia de lo que falta.

## Resumen

La app va a poder **leer** las consultas que los médicos cargan en la web de la HC general, sin escribir nada en el
servidor. Para eso:

1. **Servidor (`hclinica`)**: tres rutas `GET` nuevas (lista de consultas de un paciente, una consulta con sus
   secciones, y el historial por paciente). Un mapa explícito tabla/columna de la web hacia los nombres de la app. Cero
   escrituras.
2. **App**: un `HcClinicaBackend` **de solo lectura** (nuevo), registrado para `clinica` en `HcBackends`. Se registra
   *después* de la pantalla en solo lectura (E7.3; decisión D6 de la spec, 2026-10-29) y de tres candados que impiden cualquier envío: (a) una propiedad `soloLectura` en `HcBackend` que
   `HcApiSync` respeta; (b) el backend ni siquiera tiene en la mano un cliente que sepa escribir (`HcApiSoloLectura`);
   (c) nunca llama a `pacientes/resolver`. Lo traído se guarda con identificadores propios (`hcc-…`) y sin pendiente
   de envío; lo cargado antes en el dispositivo (todo `dirty = 1`) no se toca ni sale. **Nada traído se borra**: lo que
   la web deja de mandar queda en el dispositivo con la marca "Ya no figura en la web" (RF-63, Q15).
3. **Pantalla**: la historia del paciente separa "abiertas en la web", "consultas anteriores" (con origen) y
   "solo en este dispositivo"; la consulta de la web abre siempre en solo lectura, con las secciones de su perfil
   más lo que quede fuera del perfil (spec 001, RF-22); avisa lo que la web tiene y la app no muestra.

Sin migración de esquema (ver "Migraciones").

## Qué se verificó contra el código (2026-10-28) y qué no

Leído y comprobado (no se ejecutó nada contra bases ni servidores):
- `HcBackend`, `HcApiSync` (carpeta `sync/`, no `repos/`), `HcPediatriaBackend`, `HcRepository`, `HcDtos`,
  `HcApiClient`, `DataModule`, `ConsultaViewModel`, `HistoriaClinicaViewModel`, `PacienteDetalleViewModel`,
  `HistoriaClinica.sq`, `migrations/1.sqm` a `8.sqm`, `catalogoHcGeneral`, `ClinicaMedica.kt`, `LaboratorioSeccion`,
  `CargasLaboratorio`, `AdminRepository`, `MotivoHc`.
- hclinica (solo lectura de archivos): `routes/api.php`, `Salud360Controller`, `PacienteController`,
  `CatalogoSecciones`, `md/API_SALUD360_CLINICA.md`, `MedicoController` (cómo la web lee: `cargar*`,
  `pacienteConsultasListado`, `establecerActivo`, `getLaboratorio*`) y las migraciones de las tablas hijas.
- hc_pediatria (referencia): `ConsultaController` (`index`, `show`, `propia`), `Salud360Controller`
  (`formatearConsulta`, `consultaVisible`), `SeccionesService` (`leer`, `leerTextos`, `leerFormularios`).

Confirmado del análisis de brecha: todos los hallazgos del pedido se verificaron, con estas precisiones (ver la tabla
siguiente): `HcApiSync` está en `sync/`; la lista de la web no filtra `consultas.activo` **ni** `consultas.medico_id`
(el médico ve las de todos los médicos de los pacientes de su cartera); `tabaquismo` sigue en el catálogo; el id del
laboratorio reducido "Otros" también está desalineado (`otros` frente a la columna `otro`).

**No verificado** (hay que comprobarlo en E3.1, E8 y E9): ver "No verificado" al final.

## Contradicciones: spec y plan viejos frente al código real

| # | Lo que dicen | Lo que hay | Qué se hace |
|---|---|---|---|
| 1 | AGENTS y el pedido ubican `HcApiSync` en `repos/` | Está en `core/data/.../sync/HcApiSync.kt` | Se cita la ruta real |
| 2 | Tareas de la parte 1 (E2): "No se registra un `HcBackend` para clinica … hasta nueva orden" y comentario de `DataModule` ("la general no tiene `HcBackend`") | La L necesita registrar uno | Se registra **solo** uno de solo lectura, después de los candados (E5); se actualiza el comentario de `DataModule`. Es la "nueva orden" que pide esa regla |
| 3 | Plan viejo B.1: "backend genérico por especialidad; registrar `clinica` y `hepatologia`" | Hepatología ya no es una HC (spec 001 RF-29, `8.sqm`); `HcBackends` solo conoce pediatría | Un backend de clínica independiente. No se refactoriza `HcPediatriaBackend` (RF-60) |
| 4 | Plan viejo B.2: ids locales con la especialidad, cambiando los `hcp-c<id>` | Pediatría ya guarda `hcp-c<id>` y funciona | No se cambia pediatría; clínica usa su prefijo `hcc-` |
| 5 | Plan viejo B.5: migración `6.sqm` con `laboratorio.remoto_id` | `6.sqm`, `7.sqm` y `8.sqm` ya existen (esquema v9); la próxima sería `9.sqm` (v10) | La L no la necesita; queda para la E (**REQUIERE aprobación explícita**) |
| 6 | Plan viejo A.4: separar motor y mapa en el Laravel | Hace falta para escribir, no para leer | La L usa un mapa de solo lectura (`MapaClinica`); el motor de la E se decide después |
| 7 | Plan viejo A.3: `abrir`, `cerrar`, `reabrir`, `DELETE` | Son escritura | Entrega E |
| 8 | Spec 002: "tabaquismo sale de clínica" (2026-10-09) | `clinicaMedica.antecedentes` (`ClinicaMedica.kt:58`) lo sigue ofreciendo y `catalogoHcGeneral` lo copia | Leer no se afecta; decisión Q13 |
| 9 | Spec 002 RF-25 ("las de otro médico son de solo lectura") | `HcPediatriaBackend.traerConsultas` estampa `medicoId` del usuario en todas y no lee `propia` | Clínica sí lo hace bien; pediatría queda fuera de la L (Q17) |
| 10 | Plan viejo: "`GET consultas`, `GET {id}` con todo junto" | El historial de motivo y estudios necesita datos de *todas* las consultas del paciente | Tres rutas: lista, consulta e historial del paciente (decisión D1 más abajo) |
| 11 | Pedido: "`HcApiSync.kt:115-116` no filtra antecedentes `activo = 0`" | Cierto, y también `empujarTodo` junta toda consulta sucia sin mirar la especialidad | El candado de `HcApiSync` corta por backend; el filtro de `activo = 0` pertenece a la E |
| 12 | Plan viejo B.8: la cartera "sí informe el error" | Ya hecho en la parte 1 (E2.3, E2.4) | Nada |

## Servidor: `hclinica` (repo aparte, rama `salud360-api`; este repo no lo implementa)

Dependencia de este plan. Las tareas que tocan ese repo se aprueban por tanda: **la tanda S (E3 y E4) quedó aprobada como
un todo el 2026-10-28** ("arranquemos con la tanda S"), así que sus tareas no necesitan preguntar de nuevo. Orden de
despliegue: servidor (primero local; producción solo con aprobación aparte, E9) → app.

### Rutas (las tres `GET`, dentro del grupo `salud360`, con el middleware que ya existe)

| Ruta | Para qué | Parecido en la web |
|---|---|---|
| `GET consultas?paciente_id=&limite=&desde=` | Cabeceras de las consultas del paciente | `pacienteConsultasListado` (sin su filtro por médico) |
| `GET consultas/{id}` | Una consulta con lo que se guarda **por consulta** | los `cargar*` de `MedicoController` |
| `GET pacientes/{id}/historia` | Lo que la web guarda **por paciente y fecha** | `getLaboratorio*Tabla`, `cargarMotivoConsulta` (previas), `cargarAntecedentesFamiliares`, `cargarPendientes` |

Permisos (los mismos que `PacienteController@show`): `soloMedico` (administrador o secretaria → `403 sin_permiso`);
el paciente tiene que estar en `medico_pacientes` activo del médico (`atiende`) → si no, `403 fuera_de_cartera`;
paciente inexistente o dado de baja → `404 paciente_no_encontrado`; consulta inexistente o con `activo = 0` →
`404 consulta_no_encontrada`. El `limite` se acota a 1..200 (por defecto 50); `desde` ≥ 0; `paciente_id` faltante o
no numérico → `422 datos`. Los ids se validan como texto (PHP 32 bits). Una consulta de **otro médico** se devuelve
igual (la web la muestra) con `propia: false`.

### La lectura no escribe (garantías, RF-50)
- Los archivos nuevos no contienen `insert`, `update`, `delete`, `save(`, `create(`, `Schema::`, `DB::statement`,
  `asegurarColumna` ni `inicializarCampos`; se comprueba con un `grep` (tarea E4.3).
- No lee ni escribe `medico_infos`. No crea ni usa `paciente_id_tobb`. No toca `medico_pacientes`.
- Lo único que se escribe por pedido es lo que ya escribe el middleware `salud360` (caché de tokens, vínculos
  pendientes y, si la licencia está vencida, su `activo = 0`): sesión, no datos clínicos. Está en el alcance de RF-50
  como excepción nombrada.
- Prueba: suma de control (`CHECKSUM TABLE`) de las tablas leídas, `medico_infos`, `pacientes` y `medico_pacientes` antes
  y después de una pasada completa de lecturas: iguales (E4.3, E8.2).

### Qué devuelve cada ruta (formato JSON)

Respuestas `{ok, ...}` como el resto de la API. Fechas `AAAA-MM-DD`; `null` cuando la web guarda una fecha vacía
(`1000-01-01`, `0000-00-00`). Texto tal cual (UTF-8). Una sección o campo sin contenido **no se manda**.

**`GET consultas`**
```
{ ok, consultas: [ { id, paciente_id, medico_id, medico_nombre, tipo, tipo_numero, fecha, estado,
                     edad_mostrar, edad_meses, propia, updated_at } ],
  total, desde, hay_mas,            // 0 / 1 como número, igual que la cartera
  omitidas: { foto: n, otro: n, sin_fecha: n } }
```
- `tipo`: `control` (tipo 1), `telemedicina` (tipo 4). `estado`: `ABIERTA` (`activo = 2`), `CERRADA` (`activo = 1`).
- Se listan solo consultas con `activo` 1 o 2 y `es_foto = 0` y tipo 1 o 4; `total` cuenta solo esas. Lo demás
  (historia digitalizada `es_foto = 1`, tipos distintos de 1 y 4, `created_at` vacío) no se manda y suma en `omitidas`
  (RF-43). `activo = 0` no se lista ni se cuenta (Q11).
- Orden `created_at desc, id desc`. `fecha` = `created_at` (es la fecha clínica, igual que en la web).
- `medico_nombre` = `users.name` del médico que la cargó; `propia` = `consultas.medico_id` es el médico del pedido.

**`GET consultas/{id}`** (por consulta)
```
{ ok, consulta: { …igual que arriba… },
  secciones: {
    motivo_consulta: { texto }, datos_subjetivos: { texto }, datos_objetivos: { texto }, conductas: { texto },
    nota: { texto }, habitos: { texto }, diuresis_catarsis: { texto }, somnia: { texto },
    alimentacion: { hiposodica: "1|detalle", diabetes: "…", celiaco: "…", otra: "…" },
    serologias: { igm_hav: "SI" | "NO", … },
    child_pugh: { ascitis: "1".."3", bilirrubina, albumina, tp, encefalopatia } },
  examen_fisico: { peso, talla, tension_arterial, imc, nota },
  no_leidas: [ "documentos", "audio", "fotos", "diagnosticos", "antecedentes_personales" ] }
```
- Textos: `descripcion` de la fila `activo = 1` de esa consulta (la más nueva si hay varias).
- Alimentación: `"<0|1>|<detalle>"` como guarda la app; un campo en `0` y sin detalle no se manda.
- Serologías: columna `0` = sin cargar (no se manda, **nunca "NO"**), `1` = `SI`, `2` = `NO` (al revés que pediatría).
- Child-Pugh: puntos `1` a `3`; `0` o vacío no se manda. El total no se manda (la app lo calcula; el E2E lo compara).
- `no_leidas`: ids de las secciones que la L no muestra y que **tienen datos activos** en esa consulta (Q2).

**`GET pacientes/{id}/historia`** (por paciente)
```
{ ok, paciente_id,
  antecedentes_familiares: { hta: { flag, detalle }, …, nota } | null,     // última fila activa del paciente
  laboratorios: { laboratorio_general: [ { id, consulta_id, fecha, valores: { <id de la app>: valor } } ],
                  laboratorio_reducido: [ … ], peso: [ { id, consulta_id, fecha, valores: { peso: valor } } ] },
  estudios: { eco_abdominal: [ { id, consulta_id, fecha, detalle } ], veda: [ … ], biopsia_hepatica: [ … ],
              estudios_cardiologicos: [ … ], tc_rmn: [ … ] },
  motivos: [ { id, consulta_id, fecha, texto } ],
  examenes_complementarios: [ { id, consulta_id, fecha, solicito, respuesta, numero } ],
  interconsultas: [ { id, consulta_id, fecha, especialista, solicito, respuesta, numero } ],
  pendientes: { id, texto } | null,
  omitidas: { laboratorio_sin_fecha: n } }
```
- Todo `activo = 1`, del paciente, de consultas con `activo` 1 o 2. Las filas con `activo = 2` (pendientes de confirmar
  de una consulta abierta) **no se mandan** (como la tabla de la web) y la app lo avisa en la consulta abierta (RF-44).
- `valores` usa los **ids de la app** (mapa explícito, ver abajo): el servidor traduce, la app no cambia sus ids (Q12).
- Sin tope de filas en la L; E4.1 mide el tamaño real en la copia local. Si pasara de unos cientos de KB se agrega
  paginado o `desde` (decisión de ese momento, no ahora).

### Mapa explícito web → app (`MapaClinica`, sin base)

Se arma (E3.2) y se **verifica contra la base local** (`SHOW COLUMNS`, en E3.1) porque las migraciones del repo no incluyen las
columnas que se agregaron después (`laboratorio_generals` tiene en la base más columnas que las de su migración).

| Dato | Tabla web (alcance) | Columnas web → clave de la app |
|---|---|---|
| Textos | `motivo_consultas`, `datos_subjetivos`, `datos_objetivos`, `conductas`, `notas`, `habitos`, `catarses`, `somnias` (consulta; `activo = 1`) | `descripcion` → `texto`; el motivo guarda además `fecha` |
| Examen físico | `examen_fisicos` (consulta) | `peso`, `talla`, `ta` → `tension_arterial`, `imc`, `nota` (FC y circunferencia abdominal: la web no los tiene) |
| Alimentación | `alimentacions` (consulta) | `hiposodica`, `diabetes`, **`seliaco` → `celiaco`**, `otra` y sus `_detalle` |
| Serologías | `serologias_hepatitis` (consulta) | las 16: `igm_hav`, `ags_hbv`, `ig_anti_hcv`, `fan`, `anti_lkm`, `igg_hav`, `ig_anti_core`, `ig_anti_s_hbv`, `ig_anti_hdv`, `asma`, `fibrotest`, `hiv`, `ag_e`, `anti_ag_e`, `ig_anti_hev`, `ama` (0/1/2 → vacío/SI/NO) |
| Child-Pugh | `c_p_s` (consulta) | `ascitis`, `bilirrubina`, `albumina`, `tp`, `encefalopatia` |
| Antecedentes familiares | `antecedentes_familiares` (paciente, fila `activo = 1` de mayor `id`) | `hta`, `dbt_nir`, `dbt_ir`, `acv`, `dsl`, `fa`, `mp`, `analfabeto`, `postrado`, `af_ca_mama`, `ca_mama`, `institucionalizado`, `psicofarmacos`, **`dependientes_terceros` → `dependiente_terceros`**, `otro` (+ `_detalle`) y `nota`; **`tabaquismo`: no existe en la web** |
| Estudios | `ecografia_abdominals`, `videoendoscopia_digestiva_altas`, `biopsia_hepaticas`, `tc_rmn_colangiormns`, `estudios_cardiologicos` (paciente, con `consulta_id`) | `descripcion` → `detalle` (varchar 191 en cardiológicos), `fecha` |
| Laboratorio general | `laboratorio_generals` (paciente y `fecha`) | las 51 columnas → ids de la app; **desalineados**: `albumina`↔`alb_mina`, `fosforo`↔`f_sforo`, `concentracion`↔`concentraci_n`, `acido_urico`↔`_cido_rico`, `b12_homocisteina_folico`↔`b12_homociste_na_f_lico` (se confirman en E3.2 aplicando la regla de la app `lowercase().replace(Regex("[^a-z0-9]+"), "_")`) |
| Laboratorio reducido | `laboratorio_general2s` | 15 columnas; **`otro` (web) ↔ `otros` (app)** |
| Peso | `pesos` (`fecha`, `valor`) | `valor` → `peso` |
| Exámenes complementarios | `examenes_complementarios` (paciente, acumulativo, orden `numero`) | `fechaSolicitud` → `fecha`, `solicito`, `respuesta`, `numero`, `consulta_id`, `consulta_respuesta` |
| Interconsultas | `interconsultas` | igual + `especialista` |
| Pendientes | `aux_pendientes` (paciente, última `activo = 1`) | `pendientes` → `texto` |

Lo que el mapa deja afuera a propósito (se avisa por `no_leidas`, RF-43): `documentos` (`consulta_pdfs`), audio, fotos,
diagnósticos y antecedentes personales. Los nombres de las tablas de audio y fotos se confirman en E3.1.

### Cómo queda cada sección del catálogo (`catalogoHcGeneral`, 25 secciones)

| Sección | En la L | De dónde sale |
|---|---|---|
| `antecedentes_familiares` | Sí (por paciente) | `historia.antecedentes_familiares` |
| `enfermedades` | Sin cambios (spec 003, API propia) | — |
| `motivo_consulta` | Sí, con consultas previas | `secciones` + `historia.motivos` |
| `documentos` | **No**: aviso si hay PDFs | `no_leidas` |
| `laboratorio_general`, `laboratorio_reducido`, `tabla_peso` | Sí (por paciente y fecha) | `historia.laboratorios` |
| `eco_abdominal`, `veda`, `biopsia_hepatica`, `estudios_cardiologicos`, `tc_rmn` | Sí, con consultas previas | `historia.estudios` |
| `serologias`, `child_pugh` | Sí | `secciones` |
| `datos_subjetivos`, `datos_objetivos`, `conductas`, `nota`, `habitos`, `diuresis_catarsis`, `somnia` | Sí | `secciones` |
| `alimentacion` | Sí | `secciones` |
| `examen_fisico` | Sí | `examen_fisico` |
| `examenes_complementarios`, `interconsulta` | Sí (acumulativas del paciente) | `historia` |
| Legadas: `diagnosticos`, `audio`, `fotos`, `antecedentes_personales` | **No**: aviso si hay datos | `no_leidas` |
| Pendientes (no es sección del catálogo) | Sí | `historia.pendientes` |

### Errores
`401 sin_token` / `401 token` / `403 hc_no_habilitada` / `403 medico_no_vinculado` / `403 licencia_vencida` /
`503 tobb_caido` (el middleware que ya existe); `403 sin_permiso`; `403 fuera_de_cartera`; `404 paciente_no_encontrado`;
`404 consulta_no_encontrada`; `422 datos`; `429` (el límite `throttle:60,1` por dirección de red; Laravel contesta
`Too Many Attempts`, la app lo reconoce por el estado); `500` con el mensaje del servidor (en producción
`APP_DEBUG` puede estar encendido: hay que verlo en E9).

### Archivos (nuevos salvo indicación)
| Archivo | Qué |
|---|---|
| `app/Services/Salud360/MapaClinica.php` | Constantes del mapa (sin base) |
| `app/Services/Salud360/ConsultasReglas.php` | Reglas puras: tipo y estado, fecha útil, `limite`, serologías 0/1/2, alimentación `"flag|detalle"`, armado de laboratorio por mapa, clasificar omitidas |
| `app/Services/Salud360/ConsultasService.php` | Solo `SELECT`: `listar`, `leer`, `historia`, `noLeidas` |
| `app/Http/Controllers/Api/Salud360/ConsultaController.php` | `index`, `show`, `historia` |
| `app/Http/Controllers/Api/Salud360/Salud360Controller.php` | `consultaVisible` (404 / 403 `fuera_de_cartera`) y `formatearConsulta` |
| `routes/api.php` | Las tres rutas `GET` |
| `tests/Unit/MapaClinicaTest.php`, `tests/Unit/ConsultasReglasTest.php` | phpunit sin base ni Laravel |
| `md/API_SALUD360_CLINICA.md` | Sección "Consultas (lectura)", tabla "Código" y "Lo que viene" actualizadas |

### Rendimiento y límites
- Una consulta se lee con una veintena de `SELECT` por clave (`consulta_id`, `paciente_id`) en una sola conexión; la web
  abre unas 97 llamadas por consulta. Las tablas hijas tienen `consulta_id` y `paciente_id` con clave foránea (índice
  implícito); E4.1 revisa con `EXPLAIN` los `SELECT` por paciente de la historia.
- La app: 2 pedidos al abrir la historia (lista + historial), 1 por consulta abierta. Con `throttle:60,1` por IP
  alcanza para muchos médicos tras la misma red salvo ráfagas; la app no reintenta sola ante `429` (RF-57).
- Producción: modo estricto de MySQL probable, a diferencia de la copia local (MariaDB 10.1 sin modo estricto). Una
  lectura no inserta, así que el modo estricto no la afecta, salvo `GROUP BY` o comparaciones de tipo: los `SELECT` se
  escriben sin depender de ellos.

## App (este repo, rama `hc-general`)

### Los tres candados contra el envío (RF-47, RF-48, RF-49)

Por qué: hoy todo lo escrito en la HC general quedó `dirty = 1` (también lo que `8.sqm` movió de hepatología);
`ConsultaViewModel` llama a `HcApiSync.empujarTodo()` al abrir cualquier consulta de una especialidad con API, y ese
junta **todas** las consultas sucias (`HcApiSync.kt:83-94`); `drenar` manda además los antecedentes sin mirar `activo`
(`:115-116`). Registrar un backend de `clinica` sin candados mandaría a producción el historial local.

1. **`HcBackend.soloLectura`** (propiedad con valor por defecto `false`). `HcApiSync` solo envía por backends con
   `soloLectura = false`: `drenar` sale antes de leer el trabajo pendiente, `consultasDeAntecedentesPendientes`
   no cuenta lo de esos backends y `marcarSucia` / `empujarYa` / `empujarTodo` no hacen nada con ellos. Las filas
   sucias **no se limpian** ni se marcan. El contador "pendientes de enviar" no cambia (Q16, opción A).
2. **`HcApiSoloLectura`** (nuevo): envoltorio de `HcApiClient` que expone solo `get`, `leer`, `leerOpcional` y
   `leerTodo`. `HcClinicaBackend` lo recibe en el constructor: no puede escribir porque no tiene cómo. Los métodos de
   escritura de `HcBackend` en `HcClinicaBackend` lanzan `UnsupportedOperationException` (falla ruidosa y cerrada, no
   un no-op).
3. **Sin `resolver`**: `HcClinicaBackend` usa `PacientesHcRepository.fichaConocida` (solo lee `paciente_extra`) y
   **nunca** `vinculo`. Sin ficha conocida devuelve vacío y un aviso (RF-49).
4. Efectos laterales que el registro del backend dispararía y se cierran en E5:
   `AdminRepository.guardarLicencia` hoy devuelve `true` para un médico solo de clínica (no hay destino) y con un
   backend que no guarda licencias devolvería `false`; se excluyen los backends de solo lectura de los destinos
   (RF-61). `HcRepository.abrirConsulta` reutiliza "la consulta abierta" del médico: con una abierta de la web
   (`remoto_id` no vacío) abriría esa; para un backend de solo lectura se usa una consulta nueva que solo mira las
   locales (`consultaAbiertaLocal`, una consulta SQL nueva, sin migración).

### Archivos de la app

| Archivo | Cambio | RF |
|---|---|---|
| `core/data/.../repos/HcBackend.kt` | `val soloLectura: Boolean get() = false` | RF-47 |
| `core/data/.../sync/HcApiSync.kt` | Candado (1) | RF-47, RF-48 |
| `core/data/.../repos/HcRepository.kt` | `esSoloLectura(especialidad)`; `abrirConsulta` ignora consultas de la web; `traerConsultasRemotas` sin cambio | RF-46 |
| `core/data/.../repos/AdminRepository.kt` | Destinos de licencia sin backends de solo lectura | RF-61 |
| `core/database/.../HistoriaClinica.sq` | Consulta `consultaAbiertaLocal` (sin cambio de tablas) | RF-46 |
| `core/data/.../network/hc/HcApiSoloLectura.kt` (nuevo) | Candado (2) | RF-47 |
| `core/data/.../network/hc/HcClinicaDtos.kt` (nuevo) | DTOs de las tres respuestas (`HcDtos.kt` no se toca) | RF-38 a RF-41 |
| `core/data/.../hcgeneral/LecturaClinica.kt` (nuevo, puro) | Ids, conversión DTO → modelo, `esDeLaWeb`, `esPropia`, qué se aplica y qué se conserva, `marcarAusentes` (qué lleva la marca, nunca qué se borra) | RF-42, RF-52 a RF-55, RF-63 |
| `core/data/.../hcgeneral/AvisosLectura.kt` (nuevo, puro) | Texto y estado de los avisos (`omitidas`, `no_leidas`, sin vínculo, abierta en la web, "Ya no figura en la web") | RF-43, RF-44, RF-49, RF-63 |
| `core/data/.../repos/HcClinicaBackend.kt` (nuevo) | `traerConsultas`, `traerConsulta`, debounce, aplicar historial y marcas de ausentes; escritura prohibida; **ningún `DELETE` ni vaciado** | RF-38 a RF-41, RF-47 a RF-49, RF-53 a RF-58, RF-63 |
| `core/data/.../DataModule.kt` | `CodigosHc.GENERAL -> HcClinicaBackend(...)` en `HcBackends`; comentarios | RF-38 |
| `core/data/.../hcgeneral/MotivoHc.kt` | Caso `429` ("demasiados pedidos") | RF-56 |
| `core/data/.../hcgeneral/CargasLaboratorio.kt` | `puedeBorrar` falso para cargas de la web | RF-62 |
| `features/hc/.../ConsultaViewModel.kt` | `ConsultaUi.soloLecturaWeb`; pendientes de la web no se resuelven; actualizar | RF-45, RF-62 |
| `features/hc/.../ConsultaScreen.kt` | Banner, avisos, sin "Reabrir", marca "Ya no figura en la web" en la consulta y en lo que corresponda | RF-43, RF-45, RF-63 |
| `features/hc/.../HistoriaClinicaViewModel.kt` y `HistoriaClinicaScreen.kt` | Grupos de la lista, motivo del fallo, "Ver más", "Actualizar", marca en la lista | RF-38, RF-44, RF-46, RF-56, RF-63 |
| `core/data/build.gradle.kts` | Pasar `salud360.test.hc.clinica.ficha` a los tests | — |

No cambian: `HcPediatriaBackend`, `HcDtos.kt`, `PacientesHcRepository`, `PacienteDetalleViewModel` (ya pide
`traerConsultasRemotas` de cada especialidad con API, y con el backend nuevo lo hará también para clínica: es una
lectura de 2 pedidos).

### Identificadores locales (RF-52)
- Consulta: `hcc-c<id web>`. Médico de otro: `hcc-m<users.id>` (nunca coincide con el médico de la sesión: así `propia`
  se deriva sin columna nueva). Carga de laboratorio: `hcc-l<tipo>-<id>`. Fila de estudio o lista: `hcc-r<tipo>-<id>`.
  Pendiente: `hcc-p<id>`.
- Pediatría usa `hcp-c<id>`; el paciente de clínica `hc-clinica-p<id>` (`idLocalDeFicha`). Son prefijos disjuntos (se
  prueba). Como la clave primaria es global, un id sin prefijo propio pisaría (`INSERT OR REPLACE`) a otra HC.
- Laboratorio y pendiente no tienen `remoto_id`: no hace falta, porque su id es determinista y el dueño es la web
  (la app no escribe). Traer dos veces reemplaza la misma fila, con las reglas de Q15 de más abajo: un valor que no
  llega, o llega vacío, no pisa el que la fila ya tenía (la escritura es campo por campo, no un `INSERT OR REPLACE`
  ciego de una fila armada solo con lo que vino).

### Mapeo al modelo local (todo con `dirty = false`)

| Web (respuesta) | Tabla local | Detalle |
|---|---|---|
| `consultas[]` | `consulta` | `id = hcc-c<id>`, `remoto_id = <id web>`, `medico_id` = el del usuario si `propia`, si no `hcc-m<id>`; `estado`; `fecha`; `edad_mostrar` |
| `secciones.<sección>.<campo>` | `seccion_valor` | texto en campo `texto`; formularios por su clave; sin sección = no se escribe |
| `examen_fisico` | `examen_fisico` | solo los campos que vienen; el resto se conserva |
| `historia.laboratorios` | `laboratorio` | `consulta_id` = la consulta local de origen o `null` si no está |
| `historia.estudios`, `examenes_complementarios`, `interconsultas` | `registro_clinico` | tipo del catálogo; campos `detalle` / `solicito`, `respuesta`, `especialista`; `consulta_id` de origen |
| `historia.antecedentes_familiares` | `antecedente` | `categoria = familiares`, fila existente por clave (o id nuevo); `nota` como clave `nota` |
| `historia.pendientes` | `pendiente` | `medico_id` = el del usuario (la web los guarda por paciente) |
| nombre del médico de otro | `medico_preferencia` | `medico_id = hcc-m<id>`, clave `nombre` (Q4, opción A) |
| `omitidas` y `no_leidas` | `paciente_extra` | claves `hcc_omitidas` y `hcc_no_leidas_<id web>` (clave-valor existente, sin migración) |
| lo que la web dejó de mandar (RF-63) | `paciente_extra` | una clave `hcc_ausente_<id local de lo traído>` por cosa marcada (consulta, carga de laboratorio, fila de estudio o lista, pendiente, antecedente, sección o campo de una consulta); valor = fecha en que se notó. **Solo se escribe la marca; la fila de datos no se toca.** El formato exacto de las claves lo fija `marcarAusentes` (E6.2) |

Qué se aplica y qué se conserva (RF-53 a RF-55):
- Cada escritura mira antes si la **misma clave** está sucia (`seccionValoresDirty`, `examenesFisicosDirty`,
  `antecedentesDirty`, `registrosDirty`, `laboratoriosDirty`, `pendientesDirty`) y, si lo está, no la toca. Las consultas
  `hcc-…` no se pueden editar (solo lectura), así que en la práctica solo chocan los antecedentes por paciente
  (misma `categoria`/`clave`) y las cargas de laboratorio.
- Una consulta se aplica **en una transacción**: o entra completa o no entra (respuesta cortada, JSON inválido, `500`).
- Sin vínculo (`fichaConocida` nulo): no se pide nada (RF-49).
- **Q15 = nunca borrar (RF-63).** La lectura **no tiene ninguna operación que elimine o vacíe** filas ya traídas: no hay
  `DELETE` de datos clínicos, ni `UPDATE` que ponga un valor vacío donde había uno, ni "espejo". Reglas de escritura:
  (1) un valor que llega se escribe solo si no es vacío y la clave no está sucia; (2) un valor que **no** llega, o llega
  vacío, no cambia la fila que ya estaba; (3) lo que una respuesta **completa y correcta** deja de mandar recibe una
  marca `hcc_ausente_…` en `paciente_extra` (la marca es lo único que se escribe), y si después vuelve a venir se quita la
  marca y se aplica el valor de la web; (4) una respuesta con error, cortada, dudosa, o de una página que no cubre la
  consulta (lista con `hay_mas`, más allá de la última consulta pedida) **no marca nada**; (5) un `404
  consulta_no_encontrada` al abrir una consulta ya traída la marca y deja su contenido. La decisión de qué lleva marca es la
  función pura `marcarAusentes(...)` (devuelve marcas, nunca borrados), con prueba; su aplicación es la tarea E6.4b.

### Cuándo se trae (RF-57, RF-58)
- Al abrir la historia del paciente (`HistoriaClinicaViewModel`) y la ficha (`PacienteDetalleViewModel`):
  `traerConsultas` = 1 pedido de lista + 1 de historial. Nunca desde el autoguardado ni mientras se escribe.
- Al abrir una consulta de la web (`ConsultaViewModel`): `traerConsulta` = 1 pedido. `empujarTodo()` que dispara esa
  pantalla queda sin efecto sobre clínica por el candado (1).
- "Actualizar" (botón nuevo) fuerza ambos. Debounce en el backend: no repite la lista del mismo paciente antes de 60 s
  (reloj inyectado, probable con un reloj falso). `429` → un único aviso, sin reintento propio.

### Pantalla (RF-43 a RF-46)
- **Historia del paciente**: tarjeta "Abiertas en la web (solo lectura)" con "Ver"; tarjeta "Consulta en curso" solo para
  la local abierta, con Continuar y Anular; "Consultas anteriores" con etiqueta "Web" o "Solo en este dispositivo" y,
  si no es propia, "Cargada por <nombre>" (o "otro médico"); línea "N consultas de la web de un tipo que esta versión no
  muestra" (RF-43); "Ver más" mientras `hay_mas`; "Actualizar". Un fallo de la lectura usa la tarjeta que ya existe
  ("No se pudo traer la historia clínica"), con el texto de `motivoHc` (RF-56) y el aviso de paciente sin vínculo
  (RF-49).
- **Consulta de la web**: abre siempre con `soloLectura = true` (nuevo origen: `remoto_id` no vacío y backend de solo
  lectura), sin "Reabrir" ni "Guardar y cerrar"; banner "Consulta de la web — solo lectura"; si está abierta, "Abierta
  en la web: lo pendiente de confirmar no se ve hasta que se cierre"; si trae `no_leidas`, "La web tiene <adjuntos…>
  que esta versión no muestra"; las secciones sin datos siguen mostrando el aviso que ya tienen en lectura. Lo que
  quede fuera del perfil sale al final como ya lo resuelve `soloLecturaConsulta` (spec 001, RF-22).
- **Lo que ya no figura en la web (RF-63)**: una consulta con la marca sigue en su grupo de la lista, con la etiqueta
  "Ya no figura en la web" al lado de "Web"; dentro de la consulta, un aviso arriba ("Esta consulta ya no figura en la
  web: lo que ves es lo último que se trajo") y, en la sección, carga de laboratorio, estudio o pendiente que la web
  dejó de mandar, la misma etiqueta. Nada se oculta ni se tacha: se ve igual que antes.
- **Laboratorio** (comportamiento que ya tiene la pantalla en lectura): una consulta sin carga propia muestra la
  sección vacía; el historial completo del paciente se ve donde la consulta tiene su carga (RF-41 lo acota así).
- Lógica en funciones puras de `core/data/hcgeneral` (constitución, punto 4), porque `features/*` no tiene tests.

## Migraciones

**Ninguna en la entrega L.** Comprobado:
- Marca "de la web / solo lectura": se deduce de `remoto_id` no vacío más el backend de solo lectura, o del prefijo de
  id. Sin columna.
- `propia`: se deduce de `consulta.medico_id` frente al médico de la sesión (los ajenos usan `hcc-m…`). Sin columna.
- Laboratorio y pendientes sin `remoto_id`: no hace falta (id determinista; la app no escribe).
- Nombre del médico, avisos y `no_leidas`: filas de `medico_preferencia` y `paciente_extra`. Sin tabla nueva.
- Consulta SQL nueva `consultaAbiertaLocal`: solo cambia el `.sq`, no el esquema.

**Para la Entrega E** (no se hace ahora, **REQUIERE aprobación explícita**): `9.sqm` (esquema v10) con
`laboratorio.remoto_id` y `pendiente.remoto_id` **al final** de cada tabla (`upsertX` es posicional), y su `.sq`.
Q4 se decidió con la opción A (nombre del médico en `medico_preferencia`, sin columna propia) y Q15 con marcas en
`paciente_extra`, así que la L no necesita migración.

## Sin conexión
Todo lo traído queda en el dispositivo y se ve sin señal (RF-56). Sin conexión o con error: se muestra el motivo y lo
que ya estaba. Nada queda "a medias" (transacción por consulta). La L no encola nada para enviar.

## Decisiones (con la alternativa descartada)
- **D1. Tres rutas (lista, consulta, historial del paciente).** El motivo y los estudios muestran "consultas previas"
  de *todas* las fechas del paciente; si cada consulta trajera solo lo suyo, el historial estaría incompleto hasta abrir
  una por una (viola RF-41). Descartado: una sola `GET consultas/{id}` al estilo de pediatría (repetiría el laboratorio
  completo del paciente en cada consulta y dejaría el historial incompleto).
- **D2. Mapa en el servidor, ids de la app sin tocar** (Q12-A). Descartado: que la app conozca las tablas de la web.
- **D3. Backend de clínica independiente.** Descartado: extraer un backend base común de `HcPediatriaBackend` ahora
  (riesgo sobre lo que funciona en producción, RF-60); se evalúa al hacer la E.
- **D4. Falla cerrada en las escrituras** (`UnsupportedOperationException`) y cliente sin métodos de escritura.
  Descartado: métodos de escritura que no hacen nada (ocultan un error de configuración).
- **D5. Lo local sucio manda y no se toca** (RF-48, RF-54). Descartado: marcarlo limpio (pierde la información).
- **D6. Una consulta de la web se guarda como cualquier otra** (mismas tablas, `dirty = 0`), no en tablas espejo.
  Descartado: tablas nuevas (migración) para algo que el esquema ya expresa.
- **D7. Debounce en el backend, no en la pantalla**, para que valga para todas las pantallas que piden la lista.
- **D8. Nunca borrar lo traído; marcarlo** (Q15, decidido por el usuario el 2026-10-28). Descartado: el "espejo" que el
  planificador recomendaba (sacar de la copia lo que la web ya no tiene), por ser una lectura más laxa de la
  constitución, punto 3. Costo aceptado: un texto que la web vació puede verse desactualizado, siempre con la marca.
  Marca en `paciente_extra` (sin migración) en lugar de una columna nueva.

## Estrategia de tests

Primero en rojo, después el código (flujo de siempre). `features/*` no tiene tests: la lógica va a `core/data`.

| RF | Test |
|---|---|
| RF-40, RF-42 (nombres) | PHP `MapaClinicaTest` (cada analito del catálogo de la app, ids derivados con la regla de la app, tiene columna; los 5 + `otros`; `seliaco`; `dependientes_terceros`); `commonTest` `LecturaClinicaTest` (cada dato en su casilla) |
| RF-40 (serologías, Child-Pugh, alimentación) | PHP `ConsultasReglasTest` (0 = vacío, 1 = SI, 2 = NO; `0|` no se manda) y `LecturaClinicaTest` |
| RF-38, RF-39, RF-41, RF-53 | `jvmTest` `HcClinicaBackendTest` (servidor HTTP falso en `127.0.0.1`, como `RevocarSesionClientesTest`): guarda cabeceras, paginado, historial, nada `dirty`, traer dos veces no duplica |
| RF-43, RF-44 | PHP (`no_leidas`, `omitidas`); `commonTest` `AvisosLecturaTest` |
| RF-45, RF-46, RF-62 | `commonTest` `ConsultaWebTest` (`esDeLaWeb`, `esPropia`, grupos de la lista), `CargasLaboratorioTest` ampliado (`puedeBorrar`), `HcRepositoryTest` (`abrirConsulta` ignora la de la web) |
| RF-47, RF-48 | `jvmTest` `HcApiSyncSoloLecturaTest` (backend falso que cuenta llamadas; base con consultas, secciones, examen, registros, archivos y antecedentes `activo = 0` sucios; `empujarTodo`, `empujarYa`, `marcarSucia`: cero llamadas, filas siguen sucias); mismo test con una base migrada de hepatología (`MigracionHepatologiaTest` como base de partida); mezcla con pediatría: esa sí envía |
| RF-47 (solo `GET`) | `HcClinicaBackendSoloLecturaTest`: el servidor falso registra método y ruta de todo pedido; sólo `GET`; las operaciones de escritura lanzan |
| RF-49 | `HcClinicaBackendTest`: sin ficha conocida, 0 pedidos y aviso |
| RF-50, RF-51 | PHP/curl en E4.2 y suma de control en E4.3 y E8.2 |
| RF-52 | `LecturaClinicaTest` (prefijos disjuntos con `hcp-c` y `hc-clinica-p`) |
| RF-54, RF-55 | `HcClinicaBackendTest`: clave sucia no se pisa; `500`, JSON inválido, respuesta cortada y servidor apagado no cambian nada; un valor vacío o que no viene no pisa el que había |
| RF-63 | `commonTest` `LecturaClinicaTest` (`marcarAusentes`: devuelve solo marcas; página que no cubre → ninguna; respuesta con error → ninguna); `jvmTest` `HcClinicaBackendAusentesTest` (servidor falso): **una consulta que desaparece de la respuesta queda en el dispositivo y se marca**, y lo mismo con una dada de baja, un texto vaciado, una carga de laboratorio, un estudio y un pendiente; el contenido sigue idéntico (comparación fila por fila antes y después); si vuelve, la marca se quita y se aplica el valor; ninguna operación del backend emite `DELETE` sobre tablas clínicas |
| RF-56 | `MotivoHcTest` (caso `429`) y `HcClinicaBackendTest` (servidor apagado) |
| RF-57 | `HcClinicaBackendTest`: cuenta de pedidos (2 + 1), debounce con reloj falso, `429` sin reintento |
| RF-58 | Revisión de código (nadie más llama al backend) y manual |
| RF-59 | curl de `hc_no_habilitada`, `licencia_vencida` (ya cubierto por el middleware) y `MotivoHcTest` |
| RF-60 | `.\gradlew :core:data:jvmTest` completo; `HcPediatriaE2ETest` y `HcCarteraE2ETest` con `--rerun` contra la copia servida de pediatría (porque se toca `HcApiSync`) |
| RF-61 | `jvmTest` `AdminRepositoryLicenciasTest`: `guardarLicencia` de un médico solo de clínica sigue en `true` sin llamar al backend |
| RF-38 a RF-46, RF-56, RF-63 (pantalla) | Manual en `devWeb` con la lista de E8.3; E2E `HcClinicaConsultasE2ETest` (se saltea sin propiedades) |

E2E nuevo `HcClinicaConsultasE2ETest` (`jvmTest`, solo contra la copia local; se saltea sin
`salud360.test.hc.clinica`, `.clinica.token` y `.clinica.ficha`): SQLite temporal, **sin `resolver`** (se siembra
`hc_paciente_id` en `paciente_extra`), trae la lista y cada consulta del paciente de prueba, compara con lo esperado y
verifica que ninguna fila quedó sucia.

## Orden de despliegue
1. Servidor en la copia local (E3, E4) → app contra la copia (E5 a E7) → validación local (E8). Dentro de la app, el
   registro del backend (E6.5) va **después** de la pantalla en solo lectura (E7.3) y antes de E7.4 y de E8 (decisión D6 de la
   spec, 2026-10-29): antes de E7.3, `cerrarConsulta`, `reabrirConsulta`, `anularConsulta` y `guardar*` no distinguen las
   consultas de la web y la pantalla dejaría editar una.
2. Producción (E9) **solo con aprobación aparte**: respaldo verificado → servidor → comprobación sin token (debe dar
   `401`, no `404`) → lectura con un paciente de la cartera de prueba, corriendo la app **en la máquina local con
   `-Pentorno=release`** (no se publica la app).
3. **No pushear `main` con el backend registrado antes de que producción tenga las rutas**: la web publicada mostraría
   en cada historia de clínica "No se pudo traer la historia clínica" (404). Pushear `main` publica (AGENTS.md).
4. Producción de `release.hc.clinica` ya está en `gradle.properties`; `dev.hc.clinica` sale de `local.properties`.

## Riesgos de la entrega L

| Riesgo | Mitigación |
|---|---|
| **H2**: se envía el historial local viejo a producción | Tres candados (E5) con test de base migrada; el registro del backend va *después* de los candados y de E7.3 (D6 de la spec) |
| Una carga de laboratorio o un pendiente de la web se edita/borra/resuelve en una consulta local y diverge | RF-62; `puedeBorrar` y pendientes (E6.6) |
| Antecedente local sucio tapa el de la web | RF-54 lo manda; queda registrado (Q7) |
| Producción estricta / esquema distinto del local (columnas, `activo` con valores no previstos) | E3.1/E3.2 comparan columnas en la copia; E9 prueba una lectura con un paciente de la cartera de prueba antes de cualquier uso |
| Límite de 60 pedidos por minuto por IP y pocas conexiones en el hosting | 2 + 1 pedidos, debounce de 60 s, sin reintento ante `429` |
| La lista de la web no filtra por médico: aparecen consultas de médicos de otro consultorio con pacientes compartidos | Se muestran solo de pacientes de la cartera; `propia` y nombre (Q4) |
| Texto vaciado, consulta anulada o dato borrado en la web sigue en la app (desactualizado) | Q15 = nunca borrar: se conserva y se marca "Ya no figura en la web" (RF-63); es la contracara aceptada por el usuario |
| El contador "pendientes de enviar" ya cuenta lo local sin API y seguirá contando la HC general | Q16-A (sin cambio); documentado |
| Publicar la app antes de que producción tenga las rutas | Orden de despliegue 3 |
| La web de producción tiene `APP_DEBUG` posiblemente encendido: un `500` muestra trazas con rutas | Verificar en E9; la app muestra solo el `mensaje`, pero el cuerpo viaja |
| Pediatría estampa el médico de la sesión en consultas ajenas (RF-25 incumplido allí) | Fuera de la L (Q17) |
| `HcApiSync` se toca y pediatría depende de él | `HcPediatriaE2ETest`/`HcCarteraE2ETest` con `--rerun` antes de cerrar E5 |

## No verificado
- **Esquema real de producción** (columnas, `activo`, modo estricto) y **permisos del hosting**: solo se pudo
  comprobar que las rutas existen o no sin token; ninguna lectura con token.
- **Respaldos**: no se sabe si existe un respaldo reciente de producción ni cómo se restaura.
- **Contradicción para reconciliar**: `MEMORY.md` dice que los repos hermanos siguen sin push, pero producción contesta
  `401` en `pacientes` (la ruta existe). Hay que saber qué versión de hclinica corre en producción antes de E9.
- Qué significa exactamente `consultas.activo = 0` en la web de clínica (nadie lo escribe en el código leído) y si
  `tipo_consulta` 2 o 3 aparecen con datos; E3.1 lo cuenta (solo agregados) en la copia local.
- Cómo muestra la web las filas con `activo = 2` de una consulta abierta (la tabla de pesos y laboratorio las ignora;
  el código de exámenes e interconsultas tiene un `orWhere` sin agrupar). La L no las muestra.
- Nombres de las tablas de audio y fotos de consulta (para `no_leidas`): E3.1.
- Tamaño real del historial de un paciente con muchos años (E4.1; el paciente de prueba es chico, así que el número real
  de producción no se conoce hasta E9).
- Que `HcApiClient` en la web (wasm) lea bien `429` con cuerpo no JSON (cae en el mensaje por defecto).
- La prueba de que la app web publicada no rompe con el backend registrado se hace en E8, no en producción.

## Entrega E, reservada
Escritura: crear/abrir (reutiliza), secciones, examen, antecedentes, laboratorio, pendientes, cerrar y reabrir,
adjuntos, aviso de licencia, sin conexión. Hay que rehacer el plan viejo (abajo) con: motor separado del mapa
(reutilizando `MapaClinica` al revés), `9.sqm`, filtro de antecedentes `activo = 0`, qué hacer con lo viejo sin
enviar, `tabaquismo`, respaldo y limpieza probados, y las pruebas locales primero. No tiene tareas.

---

# Borrador previo (reemplazado) — Plan 002 original

> **Reemplazado el 2026-10-28** por el plan de la entrega L (arriba) en todo lo que toca a leer. Se conserva como
> referencia para la entrega E. Las contradicciones con el código real están en la tabla de arriba.

Estado: borrador previo. Se escribió antes de separar la spec 001 (historias clínicas configurables) y se rehace
cuando la 001 esté aprobada y planificada. Lo que cambia: qué historia clínica usa un médico y qué secciones ve ya
no lo decide el perfil, sino la configuración de la 001; en la app, la definición fija de clínica y hepatología
se reemplaza por la que arma la 001; el catálogo de secciones del Laravel (motor + un mapa por sección) se arma en
la 001 y esta spec lo llena de datos. Hepatología no es una historia clínica aparte sino un perfil: donde este
borrador dice "backend de clínica y de hepatología" o `hc.hepatologia`, pasa a ser un solo backend de la HC general.

## Qué ya tenemos (de pediatría)

| Pieza | Dónde | Sirve tal cual |
|---|---|---|
| Ingreso único con el token de turnos, validado por la HC | Laravel pediatría: `Salud360Api`, `TobbAuthService`, `Salud360Cors`, `config/salud360.php` | Sí, cambiando `hc_codigo` |
| Reintento ante MySQL saturado, relleno de columnas legacy | `ReintentoMySqlConnector`, `ColumnasLegacy` | Sí |
| Vínculo de pacientes por `paciente_id_tobb` + DNI con desempate | `PacienteVinculoService` | El algoritmo; las columnas de la ficha son otras |
| Motor de secciones, registros y fotos | `SeccionesService`, `RegistrosService`, `FotosService` | El motor sí; los mapas son de pediatría y están dentro de la misma clase |
| Licencias para el admin | `LicenciaController` (tiene un bug: falta un `use`) | Sí, misma tabla `medico_licencias` |
| Cliente HTTP, interfaz de backend, envío diferido | app: `HcApiClient`, `HcBackend`, `HcApiSync`, `HcRepository` | Casi: `HcApiSync` usa el examen de pediatría |
| Médico sin agenda | app: `tieneTurnos`, cartera vía `HcBackend.traerPacientes`, alta en turnos sin publicar | Sí (con un bug en el aviso de lista vacía) |
| Alta de usuarios con historias clínicas | app → `POST usuarios` de turnos | Sí, "clinica" ya se puede marcar |
| Pruebas de punta a punta | `HcPediatriaE2ETest`, `HcCarteraE2ETest`, `HcLicenciasE2ETest` | Como modelo |

`hclinica` es Laravel 5.8 (pediatría también), tiene `users.medico_id_tobb`, `usuario_tipo` 1/2/3, `medico_licencias`
igual a pediatría, `medico_pacientes` para la cartera y `medico_infos` (que no se toca nunca). No tiene
`paciente_id_tobb` ni nada de salud360.

## Qué falta

### A. Laravel `hclinica` (repo aparte, rama `salud360-api`)
1. Copiar la base genérica de pediatría: middleware, `TobbAuthService`, CORS, config, conector con reintento,
   `ColumnasLegacy`, `AuthController`, rutas. **`hc_codigo` deja de ser uno fijo**: lo decide la asignación del
   médico (spec 001), y se exige que turnos tenga habilitada esa. (RF-1 a RF-6)
   - Arreglar acá (y en pediatría) los dos bugs encontrados: el modo "turnos caído" se saltea la licencia y deja
     afuera al admin; `LicenciaController` sin `use ColumnasLegacy`. (RF-4, RF-6)
2. `pacientes.paciente_id_tobb` creada en caliente + `PacienteVinculoService` con la ficha de clínica (sin
   padre/madre; `nombre_familiar`, `telefono_familiar`, obra social). `GET pacientes` (cartera de `medico_pacientes`),
   `POST pacientes/resolver`, `GET pacientes/{id}`. (RF-8 a RF-13)
3. Consultas: `GET consultas`, `abrir` (tipos 1 consulta, 4 telemedicina; reutiliza la abierta), `GET {id}` con
   todo junto, `cerrar` (replica `establecerActivo`: confirma laboratorios, peso, exámenes e interconsultas),
   `reabrir`, `DELETE`. (RF-15, RF-24, RF-25)
4. Separar el **motor** del **mapa** en `SeccionesService`/`RegistrosService`/`FotosService` y escribir el mapa
   de clínica:
   - Texto (`descripcion` por consulta): motivo (`motivo_consultas`, con `fecha`), conductas, notas, datos
     subjetivos/objetivos, hábitos. (RF-16)
   - Formularios: `alimentacions`, `antecedentes_familiares` (opción + detalle, 1/0, + `nota`),
     `serologias_hepatitis` (0 sin cargar / 1 SI / 2 NO — **al revés que pediatría**), `c_p_s`. (RF-18, RF-19, RF-26, RF-27)
   - Examen físico `examen_fisicos`: peso, talla, ta, imc, nota. (RF-17)
   - Registros: `examenes_complementarios` e `interconsultas` (nacen `activo = 2`), y los cinco estudios de
     hepatología como lectura "acumulativa" (historial del paciente). (RF-22, RF-28)
   - Pendientes `aux_pendientes`. (RF-23)
5. **Laboratorio longitudinal (nuevo, pediatría no lo tiene)**: `GET/PUT/DELETE` de cargas de
   `laboratorio_generals`, `laboratorio_general2s` y `pesos`, con fecha (sin fecha → la de la consulta, nunca
   `2000-01-01`). (RF-20, RF-21, RF-29)
6. Adjuntos: PDFs (`consulta_pdfs`, 15 MB) y fotos de consulta/exámenes, a una carpeta fija creada a mano
   (`img/salud360`), como pediatría. (RF-30 a RF-32)
7. Licencias: `GET licencias` / `PUT licencias/{medicoIdTobb}` y `licencia` en el perfil. (RF-5, RF-7)
8. Documentar en `md/API_SALUD360_CLINICA.md`.

### B. App Salud 360 (este repo)
1. **Backend genérico por especialidad**: hoy `DataModule` solo crea backend para `"pediatria"`. Sacar de
   `HcPediatriaBackend` lo común a un backend base y dejar en cada especialidad solo lo propio (examen, galerías,
   tipos de registro). Registrar `clinica` y `hepatologia` contra la misma URL. Quitar la dependencia de
   `HcApiSync` en `HcPediatriaBackend.aCampos`. (RF-1)
2. **Ids locales con la especialidad**: las consultas remotas hoy son `hcp-c<id>`; pasan a incluir el código para
   que la 123 de clínica no choque con la 123 de pediatría. Mantener compatibles las ya guardadas. (RNF de no mezclar)
3. DTOs de paciente sin campos de pediatría obligatorios (`pediatria_paciente_id` → nombre neutro, con
   compatibilidad con la API de pediatría). (RF-10, RF-11)
4. Examen físico de clínica en el backend de clínica: peso, talla, IMC, TA y nota (FC y CA salen). (RF-17)
5. **Laboratorio al servidor (nuevo)**: migración `6.sqm` con `laboratorio.remoto_id` (al final), marcar sucio al
   guardar y borrar, y enviar/traer desde `HcBackend`. Pendientes, igual. (RF-20, RF-21, RF-23, RF-29)
6. Hepatología: serologías y Child-Pugh ya existen como secciones; asegurar que viajan los puntos y el total, y
   que "sin cargar" no se mande como NO. (RF-26, RF-27)
7. **Licencia por especialidad**: migración que agrega la especialidad a la tabla `licencia` (hoy la clave es solo
   el médico), modelo, `AdminRepository` y pantalla de licencias. (RF-7)
8. Errores de la HC con mensaje propio por código (`hc_no_habilitada`, `medico_no_vinculado`,
   `licencia_vencida`), y arreglar que la cartera **sí** informe el error (hoy `runCatching` lo traga). (RF-2 a RF-4, RF-9)
9. URLs en `gradle.properties`, una sola para la HC general:
   - `salud360.entorno.release.hc.clinica=https://clinica.hclinicadigital.com` (mismo esquema que pediatría).
   - `salud360.entorno.dev.hc.clinica`: la del MAMP de la Mac, a confirmar al armarlo allá. En esta máquina se
     prueba pasando la URL local al test (`http://localhost/HClinica-salud360/public/index.php`), sin tocar
     `gradle.properties`, igual que pediatría.
10. Desactivar `FALLBACK_HC_POR_ESPECIALIDAD` cuando turnos informe las historias clínicas (ya lo hace).

### C. turnosonlinebb
- Nada nuevo de código, si ya están arriba el alta de usuarios y `historias_clinicas` en el perfil (ver pendientes
  en `MEMORY.md`). Habilitar `clinica` / `hepatologia` a cada médico en `salud360_medico_hc`. (RF-2, RF-14)

### D. Operación (no es código)
- Vincular `users.medico_id_tobb` de cada médico de hclinica (2 de 6 ya lo tienen); dar de alta en turnos a los
  que no estén, sin publicar. (RF-3, RF-8)
- Entorno local en esta máquina (aprobado el 2026-10-09), igual que pediatría:
  - Clon para commitear: `C:\Users\flor\rebonline\hclinica`, rama `salud360-api` desde `main` de
    `rodri89/hclinica`. **No usar `C:\Users\flor\hclinica`**: tiene cambios sin commitear del transcriptor en `main`.
  - Commits y push como `rodri89` (ya configurado en los repos).
  - Copia servida: `C:\xampp\htdocs\HClinica-salud360` (con `vendor/` copiado, no enlazado). La vieja
    `htdocs\HClinica` no se toca.
  - Base `hc_clinica` restaurada en el MySQL de XAMPP desde el volcado (MySQL 5.7, datos reales: nunca a este repo).
  - Turnos no se consulta desde acá: tokens de prueba sembrados en `salud360_token_cache`.
  - URL: `http://localhost/HClinica-salud360/public/index.php`.
- Producción: crear `img/salud360`, `config:clear` al subir, revisar `APP_DEBUG`.
- `hclinica` (repo privado) tiene versionados `hc_clinica.sql` y fotos con datos reales en `documentacion/`:
  sacarlos del repo.

## Decisiones (con la alternativa descartada)
- **Traducir en el servidor, no en la app** (igual que pediatría): la app manda el modelo genérico y el Laravel
  lo pasa a sus tablas. Descartado: que la app conozca las tablas de la web (rompe al cambiar la web).
- **Un Laravel, varias historias clínicas, decide la configuración de la spec 001**: la web no marca de qué HC
  es una consulta. Descartado: guardar la especialidad en una columna nueva de `consultas` (la web no la leería).
- **Motor separado del mapa** en el Laravel (catálogo de secciones, spec 001), para que la próxima historia
  clínica sea solo configuración. Descartado: copiar las clases de pediatría y editarlas (copias divergentes).
- **Laboratorio por cargas con id remoto**, no como secciones de texto: la web lo muestra como tabla por fechas.

## Sin conexión
Igual que pediatría: todo se guarda en el dispositivo con `dirty`, `HcApiSync` agrupa a los 4 s y reintenta; la
consulta se abre local y se reconcilia con `abrir` (que reutiliza). Los laboratorios y adjuntos usan `remoto_id`
para no duplicarse. (RF-33, RF-34)

## Estrategia de tests
- `core/data` commonTest: mapeo del examen de clínica, ids con especialidad (y compatibilidad con los viejos),
  licencia por especialidad, serología "sin cargar", Child-Pugh total/clase, laboratorio con y sin fecha.
- `core/data` commonTest con motor HTTP simulado (`HcApiClient` acepta `engine`): errores por código y que la
  cartera informe el fallo (RF-9).
- `jvmTest` E2E contra la copia local: `HcClinicaE2ETest` (todas las secciones, ida y vuelta, deja todo como
  estaba), `HcHepatologiaE2ETest`, cartera y licencias con `-Psalud360.test.hc.clinica=...`.
- Manual con `devWeb`: médico sin agenda, adjuntos, modo avión, y comparar con la web.

## Orden de trabajo propuesto (fases, como en pediatría)
1. **Fase 0 – Base**: entorno local + Laravel con ingreso, perfil, licencias y cartera; app con backend genérico,
   ids con especialidad, licencia por especialidad y errores visibles. (RF-1 a RF-14)
2. **Fase 1 – Consulta**: abrir/cerrar, textos, examen físico, antecedentes, alimentación, pendientes. (RF-15 a RF-19, RF-23 a RF-25, RF-33, RF-34)
3. **Fase 2 – Estructurado**: laboratorio longitudinal, peso, exámenes complementarios e interconsultas,
   serologías, Child-Pugh, estudios de hepatología. (RF-20 a RF-22, RF-26 a RF-29)
4. **Fase 3 – Adjuntos**: PDFs y fotos. (RF-30 a RF-32)
5. **Fase 4 – Producción**: subir el Laravel, vincular médicos, habilitar en turnos, prueba sin señal.

Con más de 10 tareas en total, conviene que `tasks.md` cubra una fase por vez (o dividir en specs 001a…001d).
