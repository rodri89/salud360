# Plan 002, parte 1 — Pacientes y cartera de la HC general

Estado: aprobado (2026-10-19), junto con la parte 1 de la spec · Cubre RF-8 a RF-14 y RF-35 a RF-37 de
`spec.md` · Lo usa la spec 003 (RF-8 a RF-12: vínculo al abrir la sección y elegir ficha).

## Resumen
Se copia de pediatría la cartera (`GET pacientes`) y el vínculo de pacientes (`POST pacientes/resolver`,
`GET pacientes/{id}`) a `hclinica`, adaptados a su ficha (sin padre/madre, con familiar de contacto, DNI entero) y
corrigiendo al copiar los agujeros que tiene la versión de pediatría (elegir cualquier ficha, documento con signo o
letras, `int` de 32 bits, columna creada dos veces a la vez, vínculo guardado `dirty`). En la app, la cartera y el
vínculo salen de `HcPediatriaBackend` a un repositorio propio, `PacientesHcRepository`, que atiende pediatría y la
HC general **sin registrar un `HcBackend` para "clinica"**: así `HcApiSync` no intenta mandar consultas a
endpoints que todavía no existen. Se arregla el error tragado de la cartera (RF-9) y la cartera se pide solo para el
médico de la sesión y sus historias clínicas habilitadas. La pantalla de "elegir ficha" la estrena la spec 003.

## Servidor: `hclinica` (repo aparte, rama `salud360-api`; este repo no lo implementa)
Dependencia de este plan. Orden de despliegue: primero `hclinica` (local: copiar los archivos a la copia servida
`HClinica-salud360` y `php artisan config:cache` si se toca `config/`), después la app. Producción, con la 001/002
completas.

| Archivo | Qué | RF |
|---|---|---|
| `app/Services/Salud360/ColumnasLegacy.php` (nuevo, copiado) | Relleno de las columnas NOT NULL sin valor por defecto, leído de `information_schema`. **Arreglo al copiar**: la de pediatría usa `SeccionesService::SIN_CARGAR`, que acá no existe: constante propia. | RF-10 |
| `app/Services/Salud360/PacienteVinculoReglas.php` (nuevo, sin base) | Reglas puras, testeables sin MySQL: `normalizarDni` (definición de la spec: quita puntos y espacios; cualquier otro no dígito → inválido; compara **como texto** contra `2147483647` antes de convertir, porque el PHP del XAMPP es de 32 bits y `(int)` satura; rechaza < 1000 y 99999), `normalizarFecha` (`1000-01-01` y `0000-00-00` = sin fecha, como la web), `normalizarTexto` (mayúsculas, sin tildes, espacios juntos), `posibles($candidatos, $idTobb)` (saca las vinculadas con otro paciente de turnos), `desempatar` (fecha y apellido; solo si queda exactamente una), `cambiosParaCompletar($fila, $datos)` (solo columnas vacías; nunca pisa; mismas mayúsculas que la web en el alta). | RF-11, RF-12, RF-13, RF-36 |
| `app/Services/Salud360/PacienteVinculoService.php` (nuevo, adaptado) | `asegurarColumna()`: `pacientes.paciente_id_tobb` `unsignedInteger default 0` con índice, en caliente (el hosting no corre migraciones). **Arreglos**: recuerda en estático que ya existe (no un `hasColumn` por pedido) y, si dos pedidos la crean a la vez, el "duplicate column" se toma como hecho. `resolver($medico, $datos)`: (1) por `paciente_id_tobb`; (2) elegida (`hc_paciente_id`): activa, **mismo documento** y no vinculada con otro paciente de turnos, si no 422 `ficha_no_elegible`; (3) por documento: ninguna ficha → crea; fichas pero ninguna posible → 409 `paciente_ya_vinculado` sin candidatas; una posible → vincula; varias → desempata o 409 `paciente_ambiguo` con candidatas (id, nombre, apellido, dni, fecha, cantidad de consultas con `activo > 0`). Alta: `ColumnasLegacy::vacias('pacientes')` + los campos de la app (nombre, apellido, dni, teléfono, mail, domicilio, localidad, obra social, afiliado, plan, sexo, `nombre_familiar`, `telefono_familiar`) en mayúsculas como `altaPaciente` de la web; sin fecha → `1000-01-01`. Siempre `medico_pacientes` activo (crea o reactiva). Todo en una transacción con `GET_LOCK('salud360_pac_<dni>')` para que dos pedidos con el mismo documento no creen dos fichas. | RF-10 a RF-13, RF-35, RF-36 |
| `app/Http/Controllers/Api/Salud360/Salud360Controller.php` | `formatearPaciente` de clínica (sin padre/madre; con `nombre_familiar`, `telefono_familiar`, `paciente_id_tobb`), `fechaCorta`, `atiende`, `soloMedico` (admin y secretaria → 403 `sin_permiso`). | RF-8 |
| `app/Http/Controllers/Api/Salud360/PacienteController.php` (nuevo) | `index`: cartera paginada (`desde`, `limite` 1..1000) con `distinct` (hay filas repetidas en `medico_pacientes`), orden apellido, nombre, id; `total`, `hay_mas`. `resolver`: 200/201 + `paciente`, `creado`, `vinculado_por`; errores `dni_invalido` 422 (mensaje de RF-12), `paciente_ambiguo` 409, `paciente_ya_vinculado` 409, `ficha_no_elegible` 422. `show`: 404 `paciente_no_encontrado`, 403 `fuera_de_cartera` (mismos códigos que la spec 003 RF-50). Sin `medico_infos`. No replica el caso especial de la web (usuario 8 → cartera del 6). | RF-8, RF-10 a RF-12 |
| `routes/api.php` | `GET pacientes`, `POST pacientes/resolver`, `GET pacientes/{id}` dentro del grupo `salud360`. | RF-8, RF-10 |
| `md/API_SALUD360_CLINICA.md` | Sección "Pacientes": cuerpos, respuestas, códigos, curl de humo (sin credenciales), y la columna creada en caliente. | — |
| `tests/Unit/PacienteVinculoReglasTest.php` (nuevo) | Ver "Tests". | RF-11 a RF-13, RF-36 |

Diferencias con pediatría que **no** se copian (spec, P4 a P6): elegir cualquier id; `\D` que acepta signo y
letras; `(int)` de 32 bits; `hasColumn` por pedido y carrera al crear la columna; alta escrita a mano en vez de
`ColumnasLegacy`. Decidido (spec, PD1, 2026-10-19): se corrigen también en pediatría, en E1.5, sin cambiar lo
que responde a la app.

## App (este repo)

### Datos de red (`core/data/network/hc`)
- `HcDtos.kt`:
  - `HcPacienteRequest` sin el campo elegido (se va el `@SerialName("pediatria_paciente_id")` fijo) y con
    `nombre_familiar`, `telefono_familiar` opcionales. Los nulos viajan como `null`, que las dos APIs toman como
    "no vino" (`isset`). RF-13.
  - `HcPacienteFicha`: suma `nombre_familiar`, `telefono_familiar` con valor por defecto (pediatría no los manda;
    clínica no manda padre/madre). RF-8.
  - `HcPacienteCandidato`: ya sirve; la usa `ResultadoVinculo.ElegirFicha`. RF-11.
- `PacientesHcApi.kt` (nuevo): `interface PacientesHcApi { pagina(desde, limite); resolver(pedido, elegida: Long?) }`
  y `PacientesHcApiRemota(cliente: HcApiClient, claveElegida: String)`, que agrega la ficha elegida al cuerpo con la
  clave de cada API: `"pediatria_paciente_id"` en pediatría (sin cambios allá), `"hc_paciente_id"` en la HC
  general. `class PacientesHcApis(val porEspecialidad: Map<String, PacientesHcApi>)`, envuelta por lo de Koin y los
  genéricos (bug 2 de pediatría). Mismo patrón que `ConfigHcApi`: interfaz para poder probar con una falsa.

### Lógica pura (`core/data/.../hcgeneral/VinculoPacientes.kt`, commonMain, sin red ni Compose)
- `documentoParaHc(dni: String): String?` — el documento normalizado o null, con la misma regla que el servidor
  (para avisar antes de pedir). `mensajeDocumentoInvalido(dni)` con el texto de RF-12. RF-12.
- `pedidoResolver(paciente, idTobb): HcPacienteRequest` — vacíos → `null` (nunca se manda un vacío como dato),
  sexo `M/F/X` a lo que guarda la web (ver Riesgos). RF-13.
- `sealed interface ResultadoVinculo`: `Vinculado(fichaId)`, `ElegirFicha(candidatas)`, `DocumentoInvalido(mensaje)`,
  `FichaDeOtroPaciente(mensaje)`, `NoSePudo(motivo: MotivoHc)`; `resultadoDe(e: TobbException)` traduce los códigos
  (`paciente_ambiguo` → candidatas, `paciente_ya_vinculado` → RF-36, `dni_invalido`, `ficha_no_elegible`, el resto
  → motivo). RF-11, RF-12, RF-36.
- `MotivoHc` + `motivoHc(nombreHc, e: TobbException)`: sin conexión (`red`, `tobb_caido`), sesión vencida
  (`token`, `sin_token`), `hc_no_habilitada`, `medico_no_vinculado`, `licencia_vencida`, otro (con el mensaje de la
  web); el texto nombra la historia clínica. Es el mismo que van a usar el resto de la 002 y la 003 (RF-13 de la
  003). RF-9.
- `historiasConCartera(especialidadesHc, conApi: Set<String>): Set<String>` — "hepatologia" cuenta como
  `CodigosHc.GENERAL` (spec 001 RF-29); solo las que tienen API. RF-8.
- `idLocalDeFicha(especialidad, ficha)` — `TobbIds.paciente(...)` si trae `paciente_id_tobb`, si no
  `"hc-<especialidad>-p<id>"` (ya es el formato de pediatría: los ids ya incluyen la especialidad). RF-37.

### Repositorio (`core/data/.../repos/PacientesHcRepository.kt`, nuevo)
`PacientesHcRepository(db, apis: PacientesHcApis)`:
- `traerCartera(medicoId, especialidad): Int` — lo que hoy es `HcPediatriaBackend.traerPacientes`, movido y
  genérico: páginas de 300 en una transacción por página; no pisa al paciente que ya está (RF-13); asegura
  `medico_paciente`; guarda `hc_paciente_id` en `paciente_extra` con la especialidad, **`dirty = false`** (RF-35);
  llena familiar de contacto. RF-8, RF-13, RF-35, RF-37.
- `sincronizarCarteras(medicoId): ResultadoCarteras(traidos, motivos: List<MotivoHc>)` — una por HC habilitada,
  cada fallo se informa y no frena a las demás. **Arreglo de RF-9**: hoy `HcRepository.sincronizarPacientes` envuelve
  cada backend en `runCatching` y devuelve 0, así el `onFailure` del ViewModel nunca se dispara. RF-8, RF-9.
- `fichaConocida(pacienteId, especialidad): Long?` — RF-35.
- `vinculo(pacienteId, especialidad): ResultadoVinculo` — si hay vínculo guardado lo devuelve sin red; si no,
  valida el documento en el dispositivo (RF-12, sin pedido), arma el pedido y llama a `resolver`; si queda
  vinculado lo guarda (`dirty = false`). Lo llama quien lo necesita (la 003 al abrir la sección; el resto de la
  002 al enviar la consulta). RF-10 a RF-12, RF-35, RF-36.
- `elegirFicha(pacienteId, especialidad, fichaId): ResultadoVinculo` — reenvía con la elegida y guarda. RF-11.
- Arreglo al mover: `HcPediatriaBackend.resolverPaciente` guardaba el vínculo con `dirty = true`; un dato cuyo dueño
  es remoto no queda `dirty` (AGENTS.md). RF-35.

### Lo que cambia alrededor
- `HcPediatriaBackend`: crea su `PacientesHcRepository` con la API de pediatría (el constructor no cambia, así los
  E2E siguen iguales). `resolverPaciente` delega en `vinculo` y, si no queda vinculado, **lanza la misma
  `TobbException` que hoy**: `HcApiSync` de pediatría no cambia de comportamiento. `traerPacientes` queda como
  atajo público para `HcCarteraE2ETest`. RF-33 de la spec 001 (no cambiar pediatría).
- `HcBackend`: se quita `traerPacientes` de la interfaz (la cartera ya no es del backend de consultas).
- `HcRepository`: se quita `sincronizarPacientes`.
- `DataModule`: `PacientesHcApis` con `"pediatria"` y `CodigosHc.GENERAL` (si hay URL) y
  `single { PacientesHcRepository(get(), get()) }`. **`HcBackends` sigue con `"pediatria" -> …; else -> null`**:
  para "clinica" no hay `HcBackend`, así `HcApiSync`, `tieneApi` y `especialidadesConApi` no la ven y no se envía
  ninguna consulta de la HC general hasta el resto de la 002.
- `features/pacientes`: `PacientesListViewModel(…, PacientesHcRepository, traerCarteras: Boolean)` en lugar de
  `HcRepository`: pide `sincronizarCarteras(medicoId)` solo si `traerCarteras` (el repositorio lee del dispositivo
  las historias clínicas habilitadas del médico y aplica `historiasConCartera`); expone
  `motivos: StateFlow<List<String>>` en lugar de `mensaje`. `PacientesScreens`: los motivos arriba de la lista y,
  con la lista vacía, en lugar de "no hay pacientes". `MainShell`: `traerCarteras = rol == Rol.MEDICO` (la
  secretaria y el administrador no la piden). RF-8, RF-9.
- Médico sin agenda (RF-8, RF-14) y alta del paciente en turnos (RF-10): ya funcionan (`tieneTurnos` por
  `tiene_horarios`; alta de usuarios con "Sin agenda"; `guardarPacienteRemoto`). No se tocan; se verifican a mano.
- Sin migración local: el vínculo vive en `paciente_extra` (especialidad, clave `hc_paciente_id`), que ya existe.

## Sin conexión
- Cartera: sin señal se ve lo que ya había en el dispositivo y el motivo "sin conexión" nombrando la HC (RF-9);
  nada queda pendiente (es de la web).
- Vínculo: sin señal `vinculo` devuelve el guardado si lo hay; si no, `NoSePudo(sin conexión)` y quien lo pidió
  muestra su motivo (la 003: RF-30/RF-31). No se guarda nada a medias ni `dirty`. El paciente creado sin señal se
  vincula la primera vez que hace falta con conexión (RF-10).

## Decisiones (con la alternativa descartada)
- **Repositorio de pacientes aparte de `HcBackend`**, con su propia API por especialidad. Descartado: registrar un
  `HcBackend` de clínica con métodos vacíos — `HcApiSync` le pasaría las consultas sucias de "clinica" y las
  marcaría como enviadas o reintentaría contra endpoints que no existen. Descartado también: un flag en
  `HcBackend` "solo pacientes", que obliga a revisar cada uso de `HcBackends`.
- **Clave de la ficha elegida por API** (`pediatria_paciente_id` / `hc_paciente_id`). Descartado: cambiar la API
  de pediatría para aceptar un nombre neutro (toca otro Laravel sin necesidad).
- **Las reglas del vínculo en una clase PHP sin base** (`PacienteVinculoReglas`), probada con phpunit sin MySQL.
  Descartado: tests de phpunit contra la base local (tiene datos reales; los cambios de la prueba quedarían ahí).
- **`GET_LOCK` por documento** para el alta. Descartado: `lockForUpdate` sobre `dni` sin índice, que bloquea toda
  `pacientes` mientras la web trabaja.
- **Mayúsculas como la web** en lo que se crea o completa. Descartado: guardarlo como viene, que haría ver distinto
  al paciente creado desde la app en los listados de la web.
- **Códigos de `show` iguales a la 003** (`paciente_no_encontrado`, `fuera_de_cartera`). Descartado: los de
  pediatría (`no_encontrado`, `sin_permiso`), que mezclan "fuera de la cartera" con "no sos médico".
- **La pantalla de elegir ficha no es de esta parte** (spec, P3).

## Riesgos
- **Dueño remoto, sin `dirty`**: cartera y vínculo se guardan `dirty = false` (test). El arreglo de pediatría
  (`dirty = true` hoy) cambia filas de `paciente_extra` que el servidor propio no usa: sin efecto visible.
- **Columnas al final**: no hay cambio de esquema local. En `hclinica` la columna nueva se agrega con `ALTER` en
  caliente sobre una tabla de la web (no afecta a la web, que usa nombres de columna).
- **Nunca datos reales**: tests y docs sin pacientes ni tokens; no se usa `hc_clinica.sql` más que para la
  estructura. El E2E y la prueba de humo escriben en la copia local con nombres "PRUEBA SALUD360" y documentos de
  prueba que se verifican libres antes de usarlos; al terminar se borran por SQL esas filas (`pacientes` y
  `medico_pacientes`) en la copia local. Nunca contra producción.
- **Valores de `sexo` y `cantidad_hermanos` de la web**: antes de E1.2, mirar en la copia local qué valores
  distintos usa la web en `sexo` (solo los valores, sin datos de pacientes) y mapear `M/F/X`; si no hay
  equivalencia clara, se manda vacío (RF-13: completa solo vacíos).
- **`throttle:60,1`**: la cartera va de a 300 (≈ 3 pedidos para el médico más grande) y solo al abrir la lista.
- **Secretaria con la lista de un médico**: con el arreglo de RF-9 vería "sin permiso"; por eso `traerCarteras`.
- **PHP 7.3 / 32 bits**: nada de sintaxis 7.4 (`fn`, propiedades tipadas); documento comparado como texto.
- **phpunit en la copia servida**: si falla un test el printer se cae (MEMORY.md); el mensaje se ve con `php -r`.

## RF → dónde → cómo se prueba
| RF | Dónde | Cómo se prueba |
|---|---|---|
| RF-8 | `PacienteController@index`; `PacientesHcRepository.traerCartera`/`sincronizarCarteras`; `historiasConCartera`; `PacientesListViewModel`, `MainShell` | `VinculoPacientesTest` (hepatología = HC general, solo con API); `PacientesHcRepositoryTest` (API falsa: páginas, ids, no duplica); `HcClinicaPacientesE2ETest`; manual: médico sin agenda ve su cartera, secretaria no la pide |
| RF-9 | `motivoHc`, `sincronizarCarteras`, `PacientesScreens` | `MotivoHcTest` (cada código y su texto con el nombre de la HC); `PacientesHcRepositoryTest` (una falla y la otra llega; el error ya no se traga); manual: modo avión y licencia vencida en `devWeb` |
| RF-10 | `PacienteVinculoService::resolver`, `PacientesHcRepository.vinculo` | `HcClinicaPacientesE2ETest` (paciente nuevo → crea ficha y la suma a la cartera; segunda vez sin pedido); `PacientesHcRepositoryTest` (guardado no vuelve a pedir); humo con curl |
| RF-11 | `PacienteVinculoReglas::posibles/desempatar`, `resolver` (elegida), `resultadoDe`, `elegirFicha` | `PacienteVinculoReglasTest` (desempate por fecha y apellido sin tildes; mellizos sin desempate); `VinculoPacientesTest` (409 → candidatas); `PacientesHcRepositoryTest` (elegir guarda y manda la clave de cada API); humo con dos fichas de prueba con el mismo documento, elegida de otro documento → `ficha_no_elegible` |
| RF-12 | `normalizarDni` (PHP) y `documentoParaHc` (app) | `PacienteVinculoReglasTest` y `VinculoPacientesTest` con la misma tabla: puntos sí; `-12345678`, `12345678A`, 0, 1, 999, 99999, 2147483648, `99999999999` (32 bits) no; `PacientesHcRepositoryTest`: inválido no hace pedido |
| RF-13 | `cambiosParaCompletar`, `pedidoResolver`, `traerCartera` | `PacienteVinculoReglasTest` (vacío no pisa, cargado no se cambia, fecha `1000-01-01` = vacía); `VinculoPacientesTest` (vacíos → null); `PacientesHcRepositoryTest` (cartera no pisa el paciente local) |
| RF-14 | Alta de usuarios existente | Manual: alta de un médico con la HC general y sin agenda; no aparece para sacar turno |
| RF-35 | `paciente_extra` por especialidad, `dirty = false` | `PacientesHcRepositoryTest` (vínculo de clínica ≠ de pediatría; nada `dirty`, también el de pediatría) |
| RF-36 | `posibles`, `resolver`, `resultadoDe` | `PacienteVinculoReglasTest` (todas vinculadas con otro → ninguna posible); `VinculoPacientesTest` (`paciente_ya_vinculado` → mensaje); humo |
| RF-37 | `idLocalDeFicha`, `traerCartera` | `VinculoPacientesTest`; `PacientesHcRepositoryTest` (mismo paciente de turnos en dos carteras → una ficha; sin vínculo → `hc-clinica-p<id>` estable) |

## Tests
- `hclinica` `tests/Unit/PacienteVinculoReglasTest.php` (sin base): documento, fecha, texto, posibles, desempate,
  completar solo vacíos.
- `core/data` `commonTest`: `VinculoPacientesTest` (documento, pedido, `resultadoDe`, `historiasConCartera`,
  `idLocalDeFicha`), `MotivoHcTest`, `PacientesDtoTest` (clave de la elegida por API; ficha de clínica y de
  pediatría se leen con los defaults).
- `core/data` `jvmTest`: `PacientesHcRepositoryTest` con SQLite en un archivo temporal y una `PacientesHcApi` falsa
  (como `ConfigHcRepositoryTest`), sin red.
- `core/data` `jvmTest` E2E: `HcClinicaPacientesE2ETest`, se saltea sin propiedades; contra la copia local con
  `-Psalud360.test.hc.clinica=<url local> -Psalud360.test.hc.clinica.token=<token de prueba>` (ya registradas en
  `core/data/build.gradle.kts`) y `salud360.test.hc.dni/.nombre/.apellido` para el paciente de prueba: trae la
  cartera dos veces sin duplicar, resuelve el paciente de prueba (crea o vincula), lo vuelve a resolver sin pedido.
  El KDoc dice cómo limpiar por SQL lo creado. `HcCarteraE2ETest` y `HcPediatriaE2ETest` (pediatría) se vuelven a
  correr para ver que nada cambió.
- Manual (`.\gradlew devWeb`, XAMPP): médico sin agenda con la HC general ve su cartera; sin conexión y con la
  licencia vencida ve el motivo con el nombre de la HC; secretaria y administrador no ven motivos de cartera.

## Orden en tandas (≤ 10 tareas cada una; `tasks.md` se genera después)
**E1 — servidor `hclinica`** (commits en `hclinica`, rama `salud360-api`)
1. E1.1 `ColumnasLegacy` + `PacienteVinculoReglas` + `PacienteVinculoReglasTest`. RF-11 a RF-13, RF-36
2. E1.2 `PacienteVinculoService` (columna en caliente, resolver, alta, `medico_pacientes`, `GET_LOCK`). RF-10 a RF-13, RF-35, RF-36
3. E1.3 `Salud360Controller` (formato, `atiende`, `soloMedico`) + `PacienteController` + rutas. RF-8, RF-10 a RF-12
4. E1.4 Doc en `md/API_SALUD360_CLINICA.md` + copia a la servida + humo con curl (cartera, crear, vincular,
   ambiguo, elegir, ya vinculado, documento inválido, admin `sin_permiso`) y limpieza por SQL.
5. E1.5 (PD1 decidido: entra) mismos arreglos en `hc_pediatria`, sin cambiar sus respuestas.

**E2 — app** (rama `hc-general`)
1. E2.1 DTOs + `PacientesHcApi`/`PacientesHcApiRemota` + `PacientesDtoTest`. RF-11, RF-13
2. E2.2 `VinculoPacientes.kt` (documento, pedido, `ResultadoVinculo`, `historiasConCartera`, `idLocalDeFicha`) +
   `VinculoPacientesTest`. RF-8, RF-11 a RF-13, RF-36, RF-37
3. E2.3 `MotivoHc` + `MotivoHcTest`. RF-9
4. E2.4 `PacientesHcRepository`: cartera y `sincronizarCarteras` + `PacientesHcRepositoryTest`. RF-8, RF-9, RF-13, RF-35, RF-37
5. E2.5 `PacientesHcRepository`: `vinculo` y `elegirFicha` + tests. RF-10 a RF-12, RF-35, RF-36
6. E2.6 `HcPediatriaBackend` delega (sin cambio de comportamiento), se quitan `HcBackend.traerPacientes` y
   `HcRepository.sincronizarPacientes`, `DataModule`; correr los E2E de pediatría. RF-35
7. E2.7 `PacientesListViewModel` + `PacientesScreens` + `MainShell` (motivos, `traerCarteras`); prueba manual. RF-8, RF-9
8. E2.8 `HcClinicaPacientesE2ETest` contra la copia local + limpieza; verificación manual de RF-14 y del alta en
   turnos (RF-10). RF-8, RF-10, RF-14
