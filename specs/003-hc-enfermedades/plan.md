# Plan 003 — Sección "Enfermedades" de la HC general

Estado: aprobado (2026-10-20) · Spec: `spec.md` (aprobada el 2026-10-19, D1-D19; cambio del 2026-10-20: RF-10,
RF-14, RF-30, RF-31, RF-57 y RF-59 nuevo) · Depende de la 001 (catálogo,
perfiles, `claveDeNombre`, RF-28, RF-34) y de la 002 parte 1 (implementada y validada: `PacientesHcRepository.vinculo`/
`elegirFicha`, `ResultadoVinculo`, `MotivoHc`).

## Resumen
"Enfermedades" entra al catálogo de secciones de hclinica y de la app con el id `enfermedades` (RF-1 a RF-3). En
hclinica se suman cinco endpoints bajo `/api/salud360/enfermedades`, solo para médicos, que leen y escriben las tablas
de la web (`enfermedades`, `medico_enfermedades`, `paciente_enfermedades`) con bajas lógicas, cartera y licencia, y
devuelven siempre el estado completo (RF-49 a RF-55). Las reglas que no necesitan base (nombre, clave, reutilización,
armado del estado) van en una clase PHP pura con phpunit. En la app la sección es `CUSTOM` con su renderer, como
Child-Pugh: no escribe en `seccion_valor` ni pasa por el autoguardado; cada toque es un pedido directo a la web
(RF-21). La lógica (orden, vista, motivos, marcado con respuestas desordenadas, administrar, nombre) vive en
`core/data/hcgeneral` como funciones puras; un controlador en `core/data` orquesta los pedidos con el tiempo de 10 s
y una tabla nueva (`7.sqm`) guarda la copia, nunca `dirty` (RF-30 a RF-34). La pantalla de "Elegí la ficha del
paciente" se hace reutilizable (002 RF-11). Sin conexión, solo lectura (excepción aprobada al principio 2).

## Servidor: `hclinica` (repo aparte, rama `salud360-api`; este repo no lo implementa)
Dependencia de este plan. Mismas reglas que la tanda E1 de la 002: PHP 7.3 de 32 bits (nada de `fn`, propiedades
tipadas ni `(int)` de números grandes sin comparar como texto), copia servida `HClinica-salud360` a mano, `php -l`,
phpunit en la copia servida, nunca `DROP TABLE`, nunca filas de pacientes a la vista.

### Archivos
| Archivo | Qué | RF |
|---|---|---|
| `app/Services/Salud360/CatalogoSecciones.php` | `'enfermedades' => ['nombre' => 'Enfermedades', 'descripcion' => 'Casillas con las enfermedades del paciente, de la lista de cada médico.']`, entre `antecedentes_familiares` y `motivo_consulta` (segunda, orden de la web en el perfil 3; PD9). `PerfilesService::validar` la acepta sin más cambios (usa `existe`). | RF-1, RF-2 |
| `app/Services/Salud360/EnfermedadesReglas.php` (nuevo, sin base) | Estática, como `PacienteVinculoReglas`. `normalizar($s)`: recorta y junta los **espacios** de la spec (`[ \t\n\r\x0B\x0C]`, explícitos: el `trim` de PHP sin lista incluye `\0` y no `\f`). `clave($s)`: `normalizar` + `mb_strtolower(..., 'UTF-8')` + `strtr` con la tabla fija de `claveDeNombre` (à…å→a, è…ë→e, ì…ï→i, ò…ö→o, ù…ü→u, ý ÿ→y, ñ→n, ç→c). `errorDeNombre($normalizado)`: `vacio`, `largo` (`mb_strlen > 255`), `invisible` (`/[\p{Cc}\p{Cf}\p{Zl}\p{Zp}]\|(?! )\p{Zs}/u`), o null; con los textos de RF-43. `elegirExistente($filas, $clave)`: de las filas `{id, nombre, activo}` con esa clave, la de menor id activa o, si ninguna lo está, la de menor id; null si no hay. `ordenar($filas)`: por clave y, a igual clave, id ascendente (comparando ids como texto de igual largo, por 32 bits). `armarSeccion($lista, $delPaciente)`: marcables = lista activa ∩ catálogo activo con `tiene`; otras = del paciente que no son marcables (incluye dadas de baja), ambas ordenadas. `armarAdministrar($lista, $activas)`: lista + activas que no están en ella, ordenadas. | RF-16, RF-43, RF-44, RF-50, RF-52, RF-54 |
| `app/Services/Salud360/EnfermedadesService.php` (nuevo) | Acceso a la base; usa `EnfermedadesReglas`. `estadoSeccion($medicoId, $pacienteId)`, `estadoAdministrar($medicoId)` (dos o tres `SELECT` cada uno, sin paginar: cientos de filas). `marcar($medicoId, $pacienteId, $enfermedadId, $tiene)`: con `tiene` → `INSERT … ON DUPLICATE KEY UPDATE activo = 1, updated_at = ?` sobre el `unique(paciente_id, enfermedad_id)` (atómico: dos pedidos a la vez no chocan); sin `tiene` → `UPDATE … SET activo = 0 WHERE … AND activo = 1` (nunca crea una fila para desmarcar). Pedir el estado que ya tiene no cambia nada (RF-51). `enLista($medicoId, $enfermedadId, $enLista)`: igual sobre `unique(medico_user_id, enfermedad_id)`; quitar = `activo = 0` en `medico_enfermedades` y **nada** en `paciente_enfermedades` (D1). `crear($medicoId, $nombre, $pacienteId)`: ver abajo. Nunca `DELETE`, nunca `medico_infos` (RF-55). | RF-50 a RF-55 |
| `app/Http/Controllers/Api/Salud360/EnfermedadController.php` (nuevo) | Todos con `soloMedico` (admin y secretaria → `403 sin_permiso`; no mira `users.perfil`, D2). Ids de la ruta validados como `show` de pacientes (`ctype_digit` y ≤ 9 cifras, si no `422 datos_invalidos`). Ficha (`pacientes.activo = 1`) y cartera (`atiende`) como `PacienteController@show`. | RF-49 a RF-54 |
| `app/Http/Controllers/Api/Salud360/Salud360Controller.php` | Si hace falta, un `fichaYCartera($medico, $id)` común con `PacienteController@show` (mismos códigos y textos). | RF-50 |
| `routes/api.php` | Las cinco rutas, dentro del grupo `salud360` (middleware de siempre + `throttle:60,1` del grupo `api`). | RF-49 |
| `md/API_SALUD360_CLINICA.md` | Sección "Enfermedades": rutas, cuerpos, respuestas, códigos, curl de humo sin credenciales; tabla "Código" con los archivos. | — |
| `tests/Unit/EnfermedadesReglasTest.php` (nuevo, sin base) | Ver "Tests". | RF-16, RF-43, RF-44, RF-50, RF-52, RF-54 |

### Endpoints (todos bajo `/api/salud360`, token de turnos del médico)
Formato de siempre: `{ok: true, …}` o `{ok: false, mensaje, codigo}`. Rechazos del middleware sin cambios (`401
sin_token`/`token`, `403 hc_no_habilitada`, `403 medico_no_vinculado`, `403 licencia_vencida`, `503 tobb_caido`) y
`429` del `throttle` (cuerpo de Laravel, sin `codigo`). Con cualquier otro token: `403 sin_permiso` (RF-49).

Objetos: `seccion = {marcables: [{id, nombre, tiene}], otras: [{id, nombre}]}` (listas siempre presentes, aunque
vacías, y ya ordenadas); `administrar = {lista: [{id, nombre}], catalogo: [{id, nombre}]}` (catálogo = activas que no
están en la lista).

| Método y ruta | Cuerpo | 200 | Errores | RF |
|---|---|---|---|---|
| `GET enfermedades/pacientes/{pacienteId}` | — | `{seccion}` | `404 paciente_no_encontrado` (no existe o `activo = 0`), `403 fuera_de_cartera` | RF-50 |
| `PUT enfermedades/pacientes/{pacienteId}/{enfermedadId}` | `{tiene: true\|false}` (el estado elegido, nunca "invertir") | `{seccion}` | los de arriba; `422 no_en_tu_lista`; `422 enfermedad_dada_de_baja`; `422 datos_invalidos` (falta `tiene`) | RF-51 |
| `GET enfermedades/lista` | — | `{administrar}` | — | RF-52 |
| `PUT enfermedades/lista/{enfermedadId}` | `{en_lista: true\|false, paciente_id?: int}` | `{administrar, seccion?}` (`seccion` solo si vino un paciente existente, activo y de la cartera; si no, se omite sin error) | agregar una dada de baja → `422 enfermedad_dada_de_baja` | RF-53 |
| `POST enfermedades` | `{nombre, paciente_id?: int}` | `{administrar, enfermedad: {id, nombre}, reutilizada, reactivada, ya_en_lista, marcada, sin_marcar_por: null\|"paciente_no_encontrado"\|"fuera_de_cartera", seccion?}`; 201 si creó una fila en el catálogo, 200 si no | `422 nombre_invalido` (`mensaje` = texto de RF-43) | RF-54 |

Orden de los controles en `PUT …/pacientes/…`: ficha → cartera → en la lista activa → activa en el catálogo
(RF-51). Una enfermedad inexistente: ver PD8.

`crear` (RF-44, RF-54, D7, D19): `normalizar` → `errorDeNombre` → se leen **todas** las filas de `enfermedades`
(`id, nombre, activo`, cientos) y `elegirExistente` por `clave`. Sin existente: `INSERT`; si MySQL lo rechaza por el
`unique(nombre)` (otro médico a la vez, o la intercalación iguala lo que la clave no), se busca con `WHERE nombre = ?`
(la intercalación encuentra la que chocó) y se usa esa como si tuviera el mismo nombre. Si la elegida está inactiva se
reactiva (`activo = 1`). Después, `enLista(…, true)` (`ya_en_lista` si ya estaba activa) y, si vino `paciente_id`: ficha
y cartera; si están, `marcar(…, true)` (nunca desmarca) y `seccion`; si no, `sin_marcar_por`. Sin transacción global
(cada paso es idempotente; un corte a la mitad deja la enfermedad creada y quizás en la lista, que es lo que el médico
pidió); sin `GET_LOCK` (el `unique` alcanza).

### Tests (phpunit, sin base)
`EnfermedadesReglasTest` (extiende `PHPUnit\Framework\TestCase`): la **misma tabla de nombres** que
`NombreEnfermedadTest` de la app ("Diabetes", "diabetes ", "DIABÉTES", "Diabetes  tipo 2"/"Diabetes tipo 2", tab y
salto de línea, `\f` y `\v`, NBSP y U+200B → inválidos, 255 caracteres con emojis sí / 256 no, vacío y solo espacios);
`elegirExistente` con activa de menor id, sin activas (reactiva la de menor id), varias con la misma clave; orden con
desempate por id; `armarSeccion` (lista vacía + otras, dada de baja en otras, marcada por otro médico en otras, no en
la lista pero marcada → otras); `armarAdministrar` (dadas de baja afuera; las de la lista afuera del catálogo).
Lo que necesita base (idempotencia, quitar no desmarca, unique, reactivación, sin `DELETE`, `sin_permiso`) se prueba
con el humo con curl y el E2E de la app contra la copia local (PD5).

## Servidor: caché de tokens tras cerrar sesión (hallazgo del reviewer de la E2)
Hoy `TobbAuthService` guarda cada token aceptado en `salud360_token_cache` por `cache_minutos` (10): un token revocado
en turnos sigue entrando a hclinica (y a pediatría, que tiene la misma tabla) hasta que vence la fila. Cuando la fila
vence y turnos contesta 401, no hay gracia (ya está bien). Además, **la app hoy no revoca el token en turnos al cerrar
sesión**: `AuthRepository.logout` solo borra la sesión local; `POST auth/logout` de turnos existe y no se llama.

Aprobado como cambio aparte dentro de la 003 (RF-59, PD7; tareas F1.7 y F3.8):
- hclinica y pediatría: `POST auth/salir` **fuera** del middleware `salud360` (si el token ya no sirve, igual tiene que
  poder borrarse): toma el token del pedido (`tokenDelPedido`), borra la fila de `salud360_token_cache` con su
  `sha256` y contesta siempre `200 {ok: true}` (sin decir si existía). No requiere más autenticación: quien tiene el
  token puede invalidarlo.
- App: `AuthRepository.logout` llama, antes de olvidar el token y sin bloquear la salida (a lo sumo 3 s, errores
  ignorados), a `POST auth/logout` de turnos y a `POST auth/salir` de cada `HcApiClient`. Sin conexión no hace nada
  más: queda la ventana de `cache_minutos`.
- `cache_minutos` queda en 10 (configurable por `.env` si el usuario quiere acortarlo).
- Descartado: que turnos avise a cada HC al revocar (turnos tendría que conocer las URLs y un secreto compartido de
  cada HC). Descartado: acortar `cache_minutos` como única medida (cada pedido de la app pasaría más seguido por
  turnos, con su latencia, y la ventana no se cierra). Descartado: sin caché (duplica la latencia de cada toque).

## App (este repo, rama `hc-general`)

### Catálogo (`especialidades/clinica`)
- `HcGeneral.kt`: `SeccionCatalogo(SeccionDef("enfermedades", "Enfermedades", TipoSeccion.CUSTOM, renderKey =
  "enfermedades", icono = …))` en la misma posición que el servidor; `hcGeneralContribution` registra
  `"enfermedades" to { s, ctx -> EnfermedadesSeccion(s, ctx) }` junto a Child-Pugh. RF-1, RF-2.
- `CatalogoHcGeneralTest`: la copia del catálogo del servidor suma `"enfermedades" to "Enfermedades"`; los renderers
  registrados pasan a `{child_pugh, enfermedades}`; `enfermedades` no es `diagnostico` ni legada (RF-56). RF-1, RF-56.
- RF-3 (versión anterior): no hay código; la 001 ya trata un id desconocido (RF-16 admin, RF-27 médico). Se verifica
  con `CatalogoAdminTest` (id fuera del catálogo de la app → "requiere actualizar") y a mano.

### Datos de red (`core/data/network/hc`)
- `EnfermedadesDtos.kt` (nuevo): `EnfermedadDto(id: Long, nombre)`, `MarcableDto(id, nombre, tiene)`,
  `SeccionEnfermedadesDto(marcables, otras)`, `AdministrarEnfermedadesDto(lista, catalogo)`,
  `RespuestaListaDto(administrar, seccion?)`, `RespuestaCrearDto(administrar, enfermedad, reutilizada, reactivada,
  yaEnLista, marcada, sinMarcarPor?, seccion?)`, cuerpos `MarcarRequest(tiene)`, `ListaRequest(enLista, pacienteId?)`,
  `CrearRequest(nombre, pacienteId?)`.
- `EnfermedadesApi.kt` (nuevo): `interface EnfermedadesApi { seccion(fichaId); marcar(fichaId, enfermedadId, tiene);
  administrar(); enLista(enfermedadId, enLista, fichaId?); crear(nombre, fichaId?) }` y `EnfermedadesApiRemota(cliente:
  HcApiClient)` (con `pedir`/`leerTodo`, como `PacientesHcApiRemota`). Interfaz para probar sin red. RF-50 a RF-54.

### Lógica pura (`core/data/.../hcgeneral`, commonMain, sin Compose ni red)
| Archivo | Qué | RF |
|---|---|---|
| `PerfilValidacion.kt` | `claveDeNombre` usa los **espacios de la spec** en lugar de `trim()`/`\s+` (que en JVM y en wasm no son lo mismo y que incluyen NBSP en un lado): `[ \t\n\r\u000B\u000C]`. En JVM no cambia nada de la 001 (`\s` ya era eso; `trim()` además recortaba NBSP); `PerfilValidacionTest` sigue en verde. | RF-42, RF-44 |
| `NombreEnfermedad.kt` (nuevo) | `nombreNormalizado(s)`; `errorDeNombreEnfermedad(normalizado): String?` (los tres textos de RF-43; largo con `caracteres()` de la 001; invisibles con `Char.category` —CONTROL, FORMAT, LINE_SEPARATOR, PARAGRAPH_SEPARATOR, SPACE_SEPARATOR ≠ U+0020— y, como common no da la categoría de un par sustituto, una tabla de los `Cf` fuera del plano básico: U+1BCA0–1BCA3, U+1D173–1D17A, U+E0001, U+E0020–E007F); `mismoNombre(a, b)` = misma `claveDeNombre`. | RF-42, RF-43, RF-44 |
| `EnfermedadesSeccion.kt` (nuevo) | Modelo `Enfermedad(id, nombre)`, `Marcable(id, nombre, tiene)`, `EstadoSeccionEnfermedades(marcables, otras)`, `EstadoAdministrar(lista, catalogo)`. `ordenAlfabetico` (clave, id). `vistaSeccion(estado, editable)`: abierta → grilla (o el texto de RF-18 si no hay marcables) + "Otras" si hay; cerrada/solo lectura → "Estado actual del paciente" con las marcadas + otras ordenadas, o "Sin enfermedades registradas". `enfermedadesDelPaciente(estado)` para "Datos". `seccionEnfermedadesVisible(def, tipo, ocultas)` y `enfermedadesEnDatos(def, ocultas)`: en "Datos" solo si no está en el perfil para **ningún** tipo o está oculta (RF-6/RF-7; distinto de las secciones de antecedentes de la 001, donde una oculta no sale: lo pide esta spec). | RF-5, RF-6, RF-7, RF-15, RF-16, RF-18, RF-19, RF-35 |
| `MotivosEnfermedades.kt` (nuevo) | `sealed interface MotivoEnfermedades { prioridad; texto }`: `General(MotivoHc)` con prioridad por `MotivoHc.Tipo` (sesión 1, HC 2, médico 3, licencia 4, sin conexión y `OTRO` 5, PD3), `ElegirFicha(candidatas)` 6, `SinFicha(texto)` 7 (`DocumentoInvalido`, `FichaDeOtroPaciente` y `FichaNoElegible`, con el texto de la 002, PD2), `FichaNoDisponible` 8, `FueraDeCartera` 9. `motivoDeVinculo(ResultadoVinculo, nombreHc)`, `motivoDeError(TobbException, nombreHc)` (`404 paciente_no_encontrado` → 8, `403 fuera_de_cartera` → 9, el resto por `motivoHc`), `primerMotivo(motivos)`. `muestraCopia(motivo)`: sin conexión y `OTRO` (RF-30, con el texto del `MotivoHc` en `OTRO`); 1-4 y 6-9 no muestran enfermedades (RF-11 a RF-13). | RF-9 a RF-14, RF-30 |
| `MarcadoEnfermedades.kt` (nuevo) | Estado puro `EstadoMarcado(confirmado: EstadoSeccionEnfermedades?, enCamino: Map<Long, EnCamino(tiene, secuencia)>, ultimaAplicada: Long, aviso: AvisoEnfermedades?, guardado: Int, soloLectura)` y transiciones `tocar`, `confirmado(secuencia, id, estado)`, `rechazado(secuencia, id, error)`, `sinRespuesta(secuencia, id)`, `estadoPedido(secuencia, estado)`, `estadoPedidoSinRespuesta`. Cada pedido (toque, re-pedido de estado, cambio de lista) lleva una **secuencia** creciente al enviarse; una respuesta se aplica solo si su secuencia es mayor que `ultimaAplicada` (RF-23). Vista = `confirmado` con los elegidos de `enCamino` encima. "Guardado" = contador que sube con cada confirmación (la pantalla lo muestra 3 s desde el último cambio del contador: uno solo, que se renueva; RF-24). `aviso` = el último rechazo (RF-29), se limpia con la próxima confirmación. 429 → revierte y "Demasiados cambios seguidos, esperá un momento" (RF-26); 422 `no_en_tu_lista`/`enfermedad_dada_de_baja` → aviso con el nombre y pedir estado (RF-25); motivo de RF-11 a RF-13 → revierte y motivo (RF-27); otro → revierte y "No se pudo guardar el cambio. Probá de nuevo." (RF-27); sin respuesta → no revierte, pedir estado; si también sin respuesta → último confirmado, aviso de RF-28 y `soloLectura` (RF-28, RF-30). | RF-21 a RF-29 |
| `AdministrarEnfermedades.kt` (nuevo) | `filtrar(estado, buscado)` (clave de lo escrito contenida en la clave del nombre; buscador vacío o solo espacios → "Del catálogo" con "Escribí para buscar en el catálogo"; duplicados se ven todos); `ofrecerCrear(estado, buscado)` (normalizado no vacío y nadie con el mismo nombre en la lista ni en "Del catálogo"); `pacienteParaCrear(fichaId, motivo, editable)` → la ficha a mandar o el motivo de RF-58; `avisoCrear(respuesta, motivoLocal)` con los textos de RF-44 y RF-58; `puedeAdministrar(editable, propia, conConexion, motivo)` (RF-37: también con motivos 6-9; no con 1-5). | RF-37, RF-39, RF-42, RF-44, RF-45, RF-58 |

### Base local (`core/database`)
- `migrations/7.sqm` (v7 → v8; PD1) y `HcEnfermedades.sq` (misma tabla):
  `hc_enfermedades(medico_id TEXT NOT NULL, parte TEXT NOT NULL, ficha_id INTEGER NOT NULL, datos_json TEXT NOT
  NULL, recibida_en INTEGER NOT NULL, PRIMARY KEY (medico_id, parte, ficha_id))`. `parte` = `administrar` (con
  `ficha_id = 0`: lista + catálogo) o `seccion` (estado de la sección de esa ficha de la web). Sin `dirty` ni
  `deleted`: el dueño es la web (como `hc_config`). Consultas: `copia`, `guardar` (`INSERT OR REPLACE` con columnas
  **nombradas**), `borrarFicha`, `borrarTodo`, `borrarDeOtros(medico_id)`. Tabla nueva entera: no hay columnas que
  agregar al final de otra. RF-32.

### Repositorio y controlador (`core/data`)
- `repos/EnfermedadesRepository.kt` (nuevo), `EnfermedadesRepository(db, api: EnfermedadesApi?, pacientesHc:
  PacientesHcRepository, nombreHc: String, espera: Duration = 10.seconds)`: cada pedido va en su `Deferred` y se lo
  espera a lo sumo `espera` (`withTimeoutOrNull` sobre el `await`, **no** sobre el pedido: así una respuesta tardía
  igual llega y RF-23 decide si se aplica). Error de red, `tobb_caido` o tiempo cumplido = sin conexión. Guarda en la
  copia **solo** respuestas correctas (también listas vacías) de la parte que corresponde (RF-33); `403
  fuera_de_cartera` borra la `seccion` de esa ficha (RF-11). Sin API (entorno sin URL de hclinica) = sin conexión, como
  `ConfigHcRepository`. Nunca lo llama el autoguardado ni `HcApiSync`/`SyncEngine`. RF-8, RF-11, RF-15, RF-30 a RF-33.
- `hcgeneral/…` o `repos/EnfermedadesControlador.kt` (nuevo, coroutines sin Compose): estado observable de la sección
  para una consulta (`StateFlow`), con `abrir()` (vínculo con `PacientesHcRepository.vinculo` → estado; un pedido,
  más el vínculo la primera vez; RF-8, RF-15), `elegirFicha(id)` (RF-9), `tocar(id, tiene)`, `reintentar()` (RF-30),
  `abrirAdministrar()`, `agregar`, `quitar`, `crear(nombre)` (un cambio de lista por vez, RF-45; conserva lo escrito
  ante un rechazo, RF-46). Aplica las transiciones de `MarcadoEnfermedades` y `AdministrarEnfermedades`; las
  respuestas de cambios de la lista que traen `seccion` pasan por la misma regla de secuencias (RF-40, RF-41, RF-44,
  RF-48). Recibe el `CoroutineScope` (el del ViewModel) y el `espera`, para probarlo con `runBlocking` y tiempos
  cortos (no hay `coroutines-test`).
- `AuthRepository`: `logout` → `borrarTodo()` de la copia; `guardarSesion` → `borrarDeOtros(medicoId)` (un médico
  distinto del dueño, el administrador o la secretaria borran todo). RF-34. Además (RF-59, PD7, tarea F3.8):
  `logout` llama a `POST auth/logout` de turnos (`TurnosOnlineClient`, que hoy no lo tiene) y a `POST auth/salir` de
  cada `HcApiClient`, a lo sumo 3 s en total y con errores ignorados, antes de olvidar el token; la sesión local se
  borra siempre.
- `DataModule`: `single { EnfermedadesRepository(get(), clinica?.let(::EnfermedadesApiRemota), get(), <nombre de la
  HC general>) }` sobre el cliente `CodigosHc.GENERAL`, fuera de `HcBackends` (como `ConfigHcRepository`).

### Pantallas
- `core/ui/components/CasillaElegible.kt` (nuevo): casilla de ≥ 48 × 48 dp con `toggleable` y `Role.Checkbox` (el
  lector anuncia nombre y "marcada"/"sin marcar"; tabulador y espacio), mismo aspecto y **mismos `ColoresSiNo`** que
  `OpcionElegible` (contraste ya probado en `ColoresSiNoTest`), nombre completo en las líneas que haga falta,
  `habilitada` (RF-22) y una marca "Guardando…" debajo del nombre. Descartado: reutilizar `OpcionElegible` (es
  `Role.RadioButton` + `selectable`: el lector diría "botón de opción"). RF-20, RF-21, RF-22.
- `features/hc/secciones/ElegirFichaPaciente.kt` (nuevo, reutilizable por la 002): lista de candidatas (nombre,
  apellido, documento, fecha de nacimiento) con "Elegir"; recibe `List<HcPacienteCandidato>` y un `onElegir(id)`.
  RF-9 (y 002 RF-11).
- `features/hc/secciones/EnfermedadesSeccion.kt` (nuevo; el renderer CUSTOM que registra `especialidades/clinica`):
  `SectionCard` con "Guardado" junto al título 3 s (RF-24); motivo único (`primerMotivo`, RF-14); `ElegirFichaPaciente`
  (RF-9); grilla con `BoxWithConstraints` y `columnasPara(maxWidth)` (2 / 4, filas con `weight(1f)` e
  `IntrinsicSize.Min` para casillas parejas; RF-17); "Otras enfermedades del paciente" (RF-19); "Sin conexión: las
  enfermedades se pueden ver pero no cambiar" + "Reintentar" (RF-30) o "Hace falta conexión para ver las enfermedades"
  (RF-31); en cerrada/solo lectura, "Estado actual del paciente" (RF-35); botón "Administrar enfermedades" según
  `puedeAdministrar` (RF-37). Lee el estado con `collectAsState` del ViewModel, nunca `ctx.valor` (MEMORY: bug de
  Child-Pugh). No llama a `ctx.setValor`.
- `features/hc/secciones/AdministrarEnfermedadesDialog.kt` (nuevo): ventana con buscador, "Tu lista" con "Quitar",
  "Del catálogo" con "Agregar", "Crear «…»" según `ofrecerCrear`, error de nombre (RF-43), avisos (RF-44, RF-46,
  RF-58), todo deshabilitado con un cambio en camino (RF-45); `LazyColumn`/desplazamiento propio en cada lista, sin
  paginado (RF-38). Sin renombrar, dar de baja ni "eliminar" (RF-47).
- `features/hc/EnfermedadesViewModel.kt` (nuevo, en `HcModule`, `koinViewModel(key = "enfermedades-$consultaId")`): crea
  el `EnfermedadesControlador` con `viewModelScope`; parámetros `pacienteId`, `medicoId`, `editable`
  (`!ui.soloLectura && consulta.medicoId == medicoId`, RF-35). Así cambiar de pestaña no repite el pedido.
- `ConsultaViewModel`: `medicoId` pasa a `val` pública (el renderer lo necesita y `SeccionContext` no lo tiene;
  descartado cambiar la interfaz `SeccionContext`, que implementan otras pantallas).
- `ConsultaScreen` ("Datos", RF-7, D12): si `enfermedadesEnDatos(def, ocultas)`, debajo de `DatosPacienteSeccion` un
  bloque "Enfermedades" solo lectura (`EnfermedadesEnDatos`, mismo ViewModel en modo lectura: un pedido, vínculo si
  falta; sin motivos ni "Elegí la ficha"; si no hay nada, no dibuja nada). La sección `CUSTOM` no deja valores en
  `seccion_valor`, así que nunca sale "al final" por RF-22 (test). RF-6, RF-7.
- Administrador y secretaria: no abren consultas como médico; la sección no se dibuja fuera de `ConsultaScreen`.
  RF-4 (manual).

## Sin conexión y copia (excepción aprobada al principio 2)
- Nada se escribe sin conexión ni queda pendiente: no hay `dirty`, ni cola, ni reintento automático (D5).
- Abrir sin conexión (error de red o 10 s): con copia de esa ficha, se muestra con el aviso y "Reintentar" y sin
  casillas que tocar ni "Administrar"; sin copia, "Hace falta conexión…" (RF-30, RF-31). El vínculo guardado se usa sin
  red (002); sin vínculo y sin red, RF-31.
- La copia se reemplaza por parte (`administrar` / `seccion` de una ficha) solo con un 200; un 4xx/5xx o la falta de
  respuesta nunca la tocan, salvo `fuera_de_cartera`, que borra esa ficha (RF-11, RF-33). Se borra entera al cerrar
  sesión y se borra lo de otros médicos al entrar (RF-34).
- Una respuesta tardía (llega después de declarar "sin respuesta") se aplica si RF-23 lo permite y actualiza la copia,
  pero la sección sigue en solo lectura hasta "Reintentar" (RF-28: el médico ya vio el aviso).

## Decisiones (con la alternativa descartada)
- **Tipo de sección: `CUSTOM` con `renderKey = "enfermedades"`**, registrado por `hcGeneralContribution` como
  Child-Pugh. Descartado: un `TipoSeccion.ENFERMEDADES` nuevo — obliga a tocar el `when` exhaustivo de
  `RenderSeccionSola`, `seccionTieneDatos`, `esDelPaciente` y cualquier `when` sobre el enum en otros módulos, para una
  sección que solo existe en la HC general; `CatalogoHcGeneralTest` ya exige `renderKey == id` para los `CUSTOM`.
- **Normalización en los dos lados con la misma tabla** (`claveDeNombre` en Kotlin, `EnfermedadesReglas::clave` en
  PHP) y tests con la misma tabla de casos; la API es la que decide (D7). Descartado: solo en la app (otra app o la
  web podrían crear duplicados por clave) y solo en la API (la app no podría ofrecer "Crear" ni avisar sin un pedido
  por tecla, RF-39, RF-42).
- **Respuestas viejas por secuencia de envío** (contador en el dispositivo), no por hora del servidor ni por versión:
  la API no tiene versión del estado y la hora de dos servidores no ordena pedidos de un mismo dispositivo. Una
  confirmación descartada igual saca su casilla de "en camino" y deja esa casilla con el estado elegido (la web lo
  confirmó para esa casilla y RF-22 impide otro pedido sobre ella), así una respuesta desordenada no la muestra
  revertida. Descartado: aplicar siempre la última que llega (RF-23 lo prohíbe).
- **Copia en una tabla propia con el JSON de cada parte** (como `hc_config`). Descartado: tablas normalizadas
  (enfermedades, lista, marcas): más migración y consultas para algo que solo se muestra y se reemplaza entero.
  Descartado: `paciente_extra`/`seccion_valor` (tienen `dirty` y pasan por la sincronización: el bug de licencias).
- **Clave de la copia por ficha de la web**, no por paciente local: el dato es de la ficha (RF-11 borra "ese
  paciente" según la web) y el vínculo guardado ya da la ficha sin red.
- **Controlador en `core/data`** y ViewModel finito en `features/hc`: `features/` no tiene `coroutines-test` (E2.7) y la
  constitución pide las reglas en `core`. Descartado: la lógica en el ViewModel.
- **Pedido directo, nunca desde el autoguardado**: la sección no usa `setValor`; descartado guardar la marca en
  `seccion_valor` y mandarla con `HcApiSync` (marcado diferido = lo que la excepción prohíbe).
- **Caché de tokens: `auth/salir` + logout de turnos desde la app**; alternativas descartadas arriba.

## Riesgos
- **Columnas al final / migraciones**: tabla nueva; `verifyMigrations` está apagado: `7.sqm` y `HcEnfermedades.sq`
  tienen que ser idénticas (revisión en la tarea y `CopiaEnfermedadesTest` sobre una base creada por el esquema).
- **Dueño remoto sin `dirty`**: la tabla no tiene la columna; ningún dato de enfermedades va a `paciente_extra` ni a
  `seccion_valor` (test: después de marcar, `seccion_valor` de la consulta sigue vacío para `enfermedades`).
- **Nada a la API desde el autoguardado**: el renderer no llama a `setValor`; los pedidos salen solo de acciones del
  médico y de abrir la sección.
- **Datos reales**: tests y docs sin pacientes ni enfermedades de la base local; la prueba usa nombres "Prueba
  Salud360 …" y un paciente "PRUEBA SALUD360" con documento verificado libre; limpieza por SQL puntual (D4). Nunca
  `hc_clinica.sql` más que para estructura; nunca producción.
- **`throttle:60,1` por IP**: abrir = 1 (+1-2 la primera vez); cada toque = 1; RF-28 suma uno. Un médico que toca muy
  rápido puede llegar a 429 (RF-26). En el consultorio varios médicos comparten IP: el 429 es esperable y se avisa.
- **PHP 7.3 de 32 bits**: ids de la ruta validados como texto (≤ 9 cifras); comparaciones de ids como texto de igual
  largo; `mb_*` con `'UTF-8'` explícito; nada de `fn`.
- **Unicode distinto entre PHP y Kotlin**: categorías de caracteres nuevos (PCRE vs. la tabla de Kotlin) y
  minúsculas especiales (`İ`) pueden diferir: la API decide y la app solo avisa antes; misma tabla de tests en los dos.
- **`claveDeNombre` cambia en wasm** (NBSP ya no cuenta como espacio): lo pide la definición de la spec; en JVM no
  cambia; `PerfilValidacionTest` y `PerfilCopiaTest` tienen que seguir en verde.
- **Cancelación en web**: `withTimeoutOrNull` sobre el pedido abortaría el `fetch` y RF-28 diría "puede haberse
  guardado" sin chance de recibirlo; por eso se espera el `Deferred`, no el pedido.
- **Ficha creada al abrir la sección**: `resolver` crea la ficha en la web si no hay ninguna (002); abrir "Enfermedades"
  o "Datos" de un paciente nuevo la crea (aceptado en D3; PD2).

## Cambios fuera de este repo y orden de despliegue
1. `hclinica` (rama `salud360-api`): catálogo, reglas, servicio, controlador, rutas, md (tanda F1). Copia servida local
   para probar. Antes que la app: una app con "enfermedades" contra un hclinica sin la sección vería el perfil sin ella
   (el administrador no podría agregarla) y los pedidos darían 404.
2. `auth/salir` (RF-59, PD7) en hclinica y en `hc_pediatria` (rama `salud360-api`), antes de la app que lo llama
   (la app ignora el 404 de una API vieja, así que el orden no rompe nada).
3. La app (tandas F2 y F3).
Producción: con la 001/002 completas, cuando el usuario lo decida; nada se sube en estas tandas.

## Tests
- `core/data` commonTest: `NombreEnfermedadTest`, `EnfermedadesSeccionTest`, `MotivosEnfermedadesTest`,
  `MarcadoEnfermedadesTest`, `AdministrarEnfermedadesTest`, `EnfermedadesDtoTest` (cuerpos y respuestas; 429 sin
  `codigo` → status 429).
- `core/data` jvmTest: `CopiaEnfermedadesTest` (SQLite en archivo temporal, como `ConfigHcRepositoryTest`; API falsa) y
  `EnfermedadesControladorTest` (API falsa con respuestas demoradas y desordenadas, `espera` de ~100 ms,
  `runBlocking`).
- `core/data` jvmTest E2E: `HcClinicaEnfermedadesE2ETest`, se saltea sin `salud360.test.hc.clinica`/`.clinica.token`
  (+ `salud360.test.hc.dni/.nombre/.apellido` para el paciente de prueba): administrar; crear "Prueba Salud360 <n>" con
  el paciente → marcada en la sección; repetir con otro espaciado/tildes → reutilizada; desmarcar y volver a marcar
  (idempotente); quitar → sigue en "Otras"; agregar → marcada otra vez; crear sin paciente → no marca. El KDoc trae el
  comando y el SQL de limpieza (`DELETE` de `paciente_enfermedades` y `medico_enfermedades` con `enfermedad_id` de
  nombres `LIKE 'Prueba Salud360%'`, después esas filas de `enfermedades`, y la ficha y `medico_pacientes` del
  documento de prueba), sin credenciales.
- `core/ui` jvmTest: `ColumnasEnfermedadesTest` (PD4) y, si `CasillaElegible` suma colores, su contraste en
  `ColoresSiNoTest`.
- `especialidades/clinica` commonTest: `CatalogoHcGeneralTest` actualizado.
- hclinica: `EnfermedadesReglasTest` + humo con curl (como E1.4): admin → `sin_permiso`; médico con `users.perfil ≠ 3`
  → 200 (RF-49); estado con listas vacías; `404`/`403 fuera_de_cartera` con fichas de prueba; marcar dos veces igual;
  `no_en_tu_lista`; `enfermedad_dada_de_baja` (una de prueba dada de baja por SQL); quitar no cambia
  `paciente_enfermedades` (conteo antes/después); crear nueva, reutilizada por clave, reactivada, `nombre_invalido`,
  choque con el `unique` (nombre que la intercalación iguala y la clave no, o alta concurrente con dos curl); `SELECT
  COUNT(*)` de filas con `activo` cambiado y ninguna borrada. Limpieza por SQL puntual.
- Manual con `.\gradlew devWeb` (en esta máquina ya apunta al XAMPP por `local.properties`; para refrescar,
  `:composeApp:wasmJsDevelopmentExecutableCompileSync -Pentorno=dev`): el administrador agrega "Enfermedades" a un perfil
  (RF-1, RF-2) y la secretaria/el administrador no la ven (RF-4); el médico la ve en Consulta (RF-5) y no en
  Telemedicina si no está marcada; grilla de 2 y 4 columnas al cambiar el ancho (RF-17); teclado y lector de pantalla
  (RF-20); "Administrar" (RF-38); marcar en la app y verlo en la web, y al revés (de punta a punta, D4); modo avión
  (RF-30); sin perfil/oculta → "Datos" (RF-6, RF-7); diagnósticos legados intactos (RF-56); pediatría igual (RF-57).
  Limpieza por SQL al terminar.

## Orden en tandas (≤ 10 tareas; `tasks.md` se genera después de aprobar este plan)
**F1 — servidor `hclinica`** (commits en `hclinica`, rama `salud360-api`, sin push)
1. F1.1 `EnfermedadesReglas` + `EnfermedadesReglasTest`. RF-16, RF-43, RF-44, RF-50, RF-52, RF-54
2. F1.2 `enfermedades` en `CatalogoSecciones` (+ `PerfilesTest` en verde). RF-1, RF-2
3. F1.3 `EnfermedadesService`: estado de la sección, marcar, estado de administrar, agregar/quitar. RF-50 a RF-53, RF-55
4. F1.4 `EnfermedadesService::crear` (reutilización, reactivación, `unique`, marcar al paciente). RF-44, RF-54, RF-55
5. F1.5 `EnfermedadController` + rutas. RF-49 a RF-54
6. F1.6 md + copia servida + humo con curl + limpieza. RF-49 a RF-55
7. F1.7 `auth/salir` en hclinica y pediatría + md de las dos (PD7). RF-59

**F2 — lógica y datos de la app** (rama `hc-general`)
1. F2.1 `claveDeNombre` con los espacios de la spec + `NombreEnfermedad.kt` + `NombreEnfermedadTest`. RF-42, RF-43, RF-44
2. F2.2 `EnfermedadesSeccion.kt` + `EnfermedadesSeccionTest`; `ColumnasEnfermedadesTest`. RF-5 a RF-7, RF-15 a RF-19, RF-35, RF-36
3. F2.3 `MotivosEnfermedades.kt` + `MotivosEnfermedadesTest`. RF-9 a RF-14
4. F2.4 `MarcadoEnfermedades.kt` + `MarcadoEnfermedadesTest`. RF-21 a RF-29
5. F2.5 `AdministrarEnfermedades.kt` + `AdministrarEnfermedadesTest`. RF-37, RF-39, RF-42, RF-44 a RF-46, RF-48, RF-58
6. F2.6 DTOs + `EnfermedadesApi` + `EnfermedadesDtoTest`. RF-50 a RF-54
7. F2.7 `7.sqm` + `HcEnfermedades.sq` + `EnfermedadesRepository` (copia) + borrado en `AuthRepository` +
   `CopiaEnfermedadesTest`. RF-11, RF-30 a RF-34
8. F2.8 `EnfermedadesControlador` + `EnfermedadesControladorTest` + `DataModule`. RF-8, RF-15, RF-21 a RF-28, RF-40, RF-41, RF-44 a RF-46, RF-48
9. F2.9 catálogo de la app (`HcGeneral.kt` sin renderer todavía + `CatalogoHcGeneralTest`). RF-1 a RF-3, RF-56, RF-57
10. F2.10 `HcClinicaEnfermedadesE2ETest` contra la copia local + limpieza. RF-15, RF-41, RF-44, RF-51, RF-53, RF-54

**F3 — pantallas**
1. F3.1 `CasillaElegible` (core/ui). RF-20, RF-22
2. F3.2 `ElegirFichaPaciente`. RF-9
3. F3.3 `EnfermedadesViewModel` + `HcModule` + `ConsultaViewModel.medicoId`. RF-8, RF-15
4. F3.4 `EnfermedadesSeccion` (grilla, otras, motivos, Guardando/Guardado, sin conexión, cerradas) + registro del
   renderer. RF-5, RF-10 a RF-31, RF-35
5. F3.5 `AdministrarEnfermedadesDialog`. RF-37 a RF-47, RF-58
6. F3.6 "Datos" en `ConsultaScreen`. RF-6, RF-7
7. F3.7 Prueba manual completa en `devWeb` y de punta a punta app ↔ web con limpieza. RF-1 a RF-5, RF-17, RF-20, RF-38, RF-56, RF-57
8. F3.8 `AuthRepository.logout` revoca el token en turnos y en cada HC con API (PD7; en F3 porque F2 ya tiene 10
   tareas). RF-59

## RF → dónde → cómo se prueba
| RF | Dónde | Cómo se prueba |
|---|---|---|
| RF-1 | `CatalogoSecciones.php`; `HcGeneral.kt` | `CatalogoHcGeneralTest` (id, nombre, orden igual al servidor); humo `GET config/catalogo`; manual |
| RF-2 | Editor de perfiles de la 001 (sin cambios) | Manual: agregar con nombre propio, pestaña, orden y tipos |
| RF-3 | 001 RF-16/RF-27 (sin cambios) | `CatalogoAdminTest`/`DefinicionHcGeneralTest` existentes (id desconocido); manual |
| RF-4 | `ConsultaScreen` (solo médico); `soloMedico` | Manual (admin y secretaria); humo `sin_permiso` |
| RF-5 | `seccionEnfermedadesVisible`; `layoutConsulta` | `EnfermedadesSeccionTest`; manual |
| RF-6 | `seccionEnfermedadesVisible`, `enfermedadesEnDatos` | `EnfermedadesSeccionTest` (sin perfil, fuera del perfil, oculta, solo Consulta en telemedicina) |
| RF-7 | `enfermedadesEnDatos`, `enfermedadesDelPaciente`, `ConsultaScreen` | `EnfermedadesSeccionTest` (orden, vacío no muestra, nunca en sección y Datos a la vez, nunca "al final": `soloLecturaConsulta` no la lista); manual |
| RF-8 | `EnfermedadesControlador.abrir` → `PacientesHcRepository.vinculo` | `EnfermedadesControladorTest` (sin vínculo resuelve y guarda; con vínculo no pide); E2E |
| RF-9 | `MotivosEnfermedades.ElegirFicha`; `ElegirFichaPaciente`; `elegirFicha` | `MotivosEnfermedadesTest`; `EnfermedadesControladorTest` (elegir guarda y carga); manual con dos fichas de prueba |
| RF-10 | `MotivosEnfermedades.SinFicha` (PD2) | `MotivosEnfermedadesTest` (documento inválido, ficha de otro paciente y ficha no elegible → lugar 7 con el texto de la 002) |
| RF-11 | `motivoDeError` (`fuera_de_cartera`); `EnfermedadesRepository` borra la ficha | `MotivosEnfermedadesTest`; `CopiaEnfermedadesTest` (borra esa ficha, no otras; no muestra copia); humo |
| RF-12 | `motivoDeError` (`paciente_no_encontrado`) | `MotivosEnfermedadesTest`; humo 404 |
| RF-13 | `motivoDeError` → `motivoHc` | `MotivosEnfermedadesTest` (sesión, HC, médico, licencia: mismos textos que `MotivoHc`, sin enfermedades) |
| RF-14 | `primerMotivo` | `MotivosEnfermedadesTest` (los nueve de a pares; `OTRO` en el lugar 5; lista vacía no es motivo) |
| RF-15 | `EnfermedadesControlador.abrir`, `vistaSeccion`, copia | `EnfermedadesSeccionTest`; `EnfermedadesControladorTest` (un pedido; reemplaza la copia); E2E |
| RF-16 | `ordenAlfabetico`; `EnfermedadesReglas::ordenar` | `EnfermedadesSeccionTest`, `EnfermedadesReglasTest` (clave y desempate por id) |
| RF-17 | `columnasPara`; `EnfermedadesSeccion` | `ColumnasEnfermedadesTest` (599 → 2, 600 → 4); manual (ancho, nombres completos) |
| RF-18 | `vistaSeccion` | `EnfermedadesSeccionTest` (texto, con "Otras" y "Administrar") |
| RF-19 | `vistaSeccion`; `armarSeccion` | `EnfermedadesSeccionTest`; `EnfermedadesReglasTest` (dadas de baja y de otros médicos en otras) |
| RF-20 | `CasillaElegible` | `ColoresSiNoTest` (contraste); manual (tabulador, espacio, 48 dp, lector) |
| RF-21 | `MarcadoEnfermedades.tocar`; controlador | `MarcadoEnfermedadesTest` (estado elegido y "Guardando…"); `EnfermedadesControladorTest` (manda el estado elegido, no espera autoguardado) |
| RF-22 | `MarcadoEnfermedades` (`enCamino`); `CasillaElegible.habilitada` | `MarcadoEnfermedadesTest` |
| RF-23 | secuencias en `MarcadoEnfermedades` | `MarcadoEnfermedadesTest` (desordenadas; en camino conservan; confirmación descartada deja su casilla con lo elegido); `EnfermedadesControladorTest` (API falsa que contesta al revés) |
| RF-24 | contador `guardado`; `EnfermedadesSeccion` | `MarcadoEnfermedadesTest` (uno solo, se renueva); manual (3 s) |
| RF-25 | `rechazado` (`no_en_tu_lista`, `enfermedad_dada_de_baja`) | `MarcadoEnfermedadesTest`; `EnfermedadesControladorTest` (pide el estado); humo |
| RF-26 | `rechazado` (429) | `MarcadoEnfermedadesTest`; `EnfermedadesDtoTest` (429 sin código) |
| RF-27 | `rechazado` (motivos y no previsto) | `MarcadoEnfermedadesTest` |
| RF-28 | `sinRespuesta`, `estadoPedidoSinRespuesta`; espera de 10 s | `MarcadoEnfermedadesTest`; `EnfermedadesControladorTest` (espera corta, respuesta tardía) |
| RF-29 | `aviso` único | `MarcadoEnfermedadesTest` |
| RF-30 | `muestraCopia`, `soloLectura`, `reintentar` | `CopiaEnfermedadesTest` (también con un 500: copia, "Reintentar" y el texto de la web); `MotivosEnfermedadesTest`; manual (modo avión) |
| RF-31 | controlador sin copia | `CopiaEnfermedadesTest` (sin red; con un 500, el texto de la web) |
| RF-32 | `hc_enfermedades` (sin `dirty`) | `CopiaEnfermedadesTest` (guarda las tres partes por médico; `seccion_valor`/`paciente_extra` sin filas `dirty` nuevas) |
| RF-33 | `EnfermedadesRepository` | `CopiaEnfermedadesTest` (error no pisa; lista vacía sí) |
| RF-34 | `AuthRepository.logout`/`guardarSesion` | `CopiaEnfermedadesTest` (borrado al cerrar sesión y con otro médico) |
| RF-35 | `vistaSeccion(editable = false)`; `EnfermedadesViewModel.editable` | `EnfermedadesSeccionTest` ("Estado actual del paciente", "Sin enfermedades registradas", motivos igual) |
| RF-36 | Diseño: nada por consulta | `EnfermedadesSeccionTest`/riesgo: `seccion_valor` vacío tras marcar (controlador) |
| RF-37 | `puedeAdministrar` | `AdministrarEnfermedadesTest` (sin vínculo, con motivos 6-9 sí; 1-5 y cerrada no) |
| RF-38 | `AdministrarEnfermedadesDialog`; `abrirAdministrar` | `EnfermedadesControladorTest` (un pedido); manual (desplazamiento, sin paginado) |
| RF-39 | `filtrar` | `AdministrarEnfermedadesTest` (clave contenida, duplicados, vacío/espacios) |
| RF-40 | controlador `agregar`; `PUT enfermedades/lista` | `AdministrarEnfermedadesTest`/`EnfermedadesControladorTest` (muestra administrar y sección de la respuesta); E2E |
| RF-41 | `quitar`; `EnfermedadesService::enLista` | `EnfermedadesControladorTest`; E2E (pasa a "Otras" y vuelve marcada); humo (conteo de `paciente_enfermedades`) |
| RF-42 | `ofrecerCrear` | `AdministrarEnfermedadesTest`; `NombreEnfermedadTest` |
| RF-43 | `errorDeNombreEnfermedad`; `EnfermedadesReglas::errorDeNombre` | `NombreEnfermedadTest` y `EnfermedadesReglasTest` (misma tabla) |
| RF-44 | `crear` (controlador, servicio); `avisoCrear` | `AdministrarEnfermedadesTest` (nueva, reutilizada, reactivada, ya en la lista; marcada; ya marcada sigue); `EnfermedadesReglasTest` (`elegirExistente`); E2E; humo |
| RF-45 | controlador (un cambio por vez) | `AdministrarEnfermedadesTest`/`EnfermedadesControladorTest` |
| RF-46 | controlador; avisos | `AdministrarEnfermedadesTest` (conserva lo escrito; 429; sin conexión pide administrar) |
| RF-47 | Ausencia en `AdministrarEnfermedadesDialog` y en la API | Revisión de código; manual |
| RF-48 | controlador (estado completo de cada respuesta) | `AdministrarEnfermedadesTest`, `EnfermedadesControladorTest` (respuesta con lista cambiada desde otro lado) |
| RF-49 | `EnfermedadController` (`soloMedico`) | Humo: admin → `sin_permiso`; médico con `users.perfil ≠ 3` → 200 |
| RF-50 | `estadoSeccion`, `armarSeccion` | `EnfermedadesReglasTest`; humo (listas vacías, 404, `fuera_de_cartera`); E2E |
| RF-51 | `marcar` | Humo (dos veces igual, `no_en_tu_lista`, `enfermedad_dada_de_baja`); E2E |
| RF-52 | `estadoAdministrar`, `armarAdministrar` | `EnfermedadesReglasTest`; humo |
| RF-53 | `enLista` | Humo (quitar no toca pacientes; con paciente de la cartera trae `seccion`); E2E |
| RF-54 | `crear` | `EnfermedadesReglasTest`; humo (reutilizada por clave, menor id, reactivada, `nombre_invalido`, `unique`, con/sin paciente, paciente inexistente o fuera de la cartera → 200 sin marcar con el motivo); E2E |
| RF-55 | `EnfermedadesService` | Revisión (sin `DELETE` ni `medico_infos`; buscar en el diff); humo (conteos sin filas borradas) |
| RF-56 | Ids distintos; sin copia entre secciones | `CatalogoHcGeneralTest`; manual (diagnósticos legados igual) |
| RF-57 | Solo `catalogoHcGeneral` | `CatalogoHcGeneralTest` (`clinica_medica_sigue_igual`, `las_secciones_de_hepatologia_se_leen_desde_la_hc_general`); manual pediatría |
| RF-58 | `pacienteParaCrear`, `avisoCrear`; `sin_marcar_por` | `AdministrarEnfermedadesTest` (sin vínculo, fuera de la cartera, consulta no editable: no manda o no marca, con el texto); humo |
| RF-59 | `auth/salir` (hclinica y pediatría); `AuthRepository.logout` | Curl (token revocado en turnos sigue entrando por la caché hasta `auth/salir`, después `401`); test de `AuthRepository` (llama a los tres, una HC que falla o no contesta no impide cerrar; ojo: el proyecto no tiene `ktor-client-mock`, así que hace falta un punto de inyección para el revocado o preguntar por la dependencia, se define en F3.8); manual |

## Decidido (2026-10-20)
Aprobado por el usuario junto con el plan.
- **PD1.** La migración nueva es `7.sqm` (existen `1.sqm`…`6.sqm`; lleva el esquema de v7 a v8).
- **PD2.** Los tres casos "sin ficha" (documento inválido sin pedido, `paciente_ya_vinculado`, `ficha_no_elegible`
  tras elegir) ocupan el lugar 7 de RF-14 con el texto de la 002 que dice qué hacer. `/sdd-change` chico aplicado en
  `spec.md` (RF-10, criterios y nota al pie).
- **PD3.** Los errores no previstos al abrir (`500`, `ocupado` agotado, respuesta ilegible = `MotivoHc.Tipo.OTRO`)
  se tratan como sin conexión (lugar 5): copia en solo lectura + "Reintentar", con el texto de la web (el del
  `MotivoHc`). Reflejado en `spec.md` (RF-14, RF-30, RF-31) en el mismo cambio; RF-33 ya dice que un error no pisa la
  copia.
- **PD4.** Se reutiliza `columnasPara` de `core/ui` (`SiNoVacio.kt`); `ColumnasEnfermedadesTest` va en
  `:core:ui:jvmTest`.
- **PD5.** Sin test Feature de phpunit que escriba en la base (la local tiene datos reales):
  `tests/Unit/EnfermedadesReglasTest.php` (sin base) + humo con curl sobre datos de prueba + el E2E de la app, con
  limpieza por SQL puntual, cubriendo la misma lista de casos.
- **PD6.** En "Datos", con varias fichas posibles no se ofrece elegir: no se muestra nada (como sin datos).
- **PD7.** Entra la invalidación de la caché de tokens como cambio aparte dentro de la 003 (RF-59 nuevo en `spec.md`,
  RF-57 ajustado): `POST auth/salir` en hclinica y en pediatría (F1.7) y `AuthRepository.logout` lo llama junto con
  `POST auth/logout` de turnos, que hoy la app tampoco llama (F3.8).
- **PD8.** Id de enfermedad inexistente: marcar → `422 no_en_tu_lista`; agregar → `422 enfermedad_dada_de_baja`; sin
  códigos nuevos (la app re-pide el estado).
- **PD9.** `enfermedades` va segunda en el catálogo, después de `antecedentes_familiares`.

## Notas para la tanda F3 — decisiones cerradas; aprobadas por el usuario (2026-10-23)
Surgen de leer el código real al redactar la tanda F3 de `tasks.md`. No cambian el texto aprobado de arriba hasta que el
usuario apruebe las tareas; el detalle y las decisiones (P1 a P9) están en `tasks.md`, sección "Decididas".
- **F3.4 se parte en F3.4a/b/c** (lógica pura del panel, pantalla, registro e ícono). Archivos nuevos de lógica en
  `core/data`: `repos/PanelEnfermedades.kt`, `repos/PanelAdministrar.kt`, `hcgeneral/AccesoEnfermedades.kt`,
  `hcgeneral/FichaCandidata.kt`, cada uno con su test; así las pantallas solo dibujan (constitución 4) y no hace falta
  ningún test de Compose (no hay y no se agrega la dependencia).
- **Cambia "Pantallas → `EnfermedadesViewModel`" y "`ConsultaViewModel`"**: el `medicoId` sale de la sesión
  (`PerfilMedico.medicoId` si el rol es médico), no del que recibe `ConsultaScreen` (que para la secretaria es el médico
  elegido: `MainShell.kt:281`). `ConsultaViewModel.medicoId` **no** pasa a pública; `ConsultaViewModel` recibe
  `AuthRepository` para sacar la pestaña de enfermedades a la secretaria y al administrador (RF-4). El plan decía que no
  abren consultas como médico: la secretaria sí abre las HC del médico elegido.
- **Cambia "ConsultaScreen ('Datos')"**: `enfermedadesEnDatos` hay que limitarla a la HC general (`resuelta.porPerfil`) y al
  médico (da `true` para pediatría), y el bloque va en los dos lugares donde se dibuja "Datos" (con perfil y sin perfil).
- **Cambia "`AuthRepository`" (RF-59)**: C4 (segundo plano, no demora) reemplaza el "antes de olvidar el token, a lo sumo 3
  s" de arriba; interfaz `RevocaSesion` inyectable (C3), el token se pasa por parámetro y los revocadores por defecto son
  una lista vacía (el cliente de turnos por defecto apunta a producción).
- **Ícono**: `iconoSeccion` está en `features/hc/Iconos.kt`; el ícono propio es una clave nueva `"enfermedades"` con `HealthAndSafety` (P4-A), y su
  test va en `especialidades/clinica` (`features/hc` no tiene fuente de test).
- **Riesgo nuevo**: "Datos" resuelve el vínculo y puede crear la ficha en la web de clínica aun para médicos que nunca
  usaron "Enfermedades" (RF-7 + RF-8, P7-A: aceptado, como la spec).
