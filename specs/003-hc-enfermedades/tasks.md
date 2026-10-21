# Tareas 003 — Enfermedades

Plan: `plan.md` (aprobado el 2026-10-20, con PD1 a PD9 decididos). Spec: `spec.md`, aprobada el 2026-10-19, con el
cambio del 2026-10-20 (RF-10, RF-14, RF-30, RF-31, RF-57 y RF-59 nuevo). Las tareas van por tandas; cada tanda se
aprueba antes de generar la siguiente (F2, lógica y datos de la app, se genera después de validar la F1).

Reglas para todas:
- Commits locales como `rodri89` (ya configurado), uno por tarea, **sin push** en ningún repo. Subir a producción
  (hclinica o pediatría) lo decide el usuario después.
- Nada contra producción. Ningún dato real, contraseña ni token en ningún repo (tampoco en `.md`, tests ni
  mensajes de commit). Las pruebas que necesiten token usan los usuarios de prueba locales, sin escribir
  credenciales en ningún lado.
- Las bases locales `hc_clinica` y `hc_pediatrica` tienen datos reales: solo se mira **estructura**
  (`information_schema`, `SHOW CREATE TABLE`, conteos), nunca se muestran filas de pacientes ni de sus
  enfermedades. Los datos de prueba se crean con el prefijo "Prueba Salud360 …" (enfermedades) y "PRUEBA SALUD360"
  (pacientes, con documentos verificados libres antes con `SELECT COUNT(*)`), y se borran al terminar cada tarea.
- Restaurar siempre con `UPDATE`/`INSERT`/`DELETE` puntuales; **nunca** restores con `DROP TABLE`.
- Copias servidas (sin git; los archivos se copian a mano desde el clon): `C:\xampp\htdocs\HClinica-salud360`
  (`http://localhost/HClinica-salud360/public/index.php`, clon `C:\Users\flor\rebonline\hclinica`) y
  `C:\xampp\htdocs\HCDPediatria-salud360` (`http://localhost/HCDPediatria-salud360/public/index.php`, clon
  `C:\Users\flor\rebonline\hc_pediatria`). La copia vieja `htdocs\HCDPediatria` no se toca. `php -l` de cada archivo
  tocado antes de probar.
- En XAMPP las dos apps Laravel comparten el entorno del proceso de Apache: después de tocar `.env` o `config/`,
  `php artisan config:cache` en las copias servidas (no `config:clear`).
- phpunit en la copia servida (el clon no tiene `vendor/`): `C:\xampp\php\php.exe vendor\bin\phpunit tests\Unit\...`.
  Si un test falla el printer se cae y no muestra el mensaje: verlo llamando el código con `php -r`.
- PHP 7.3 de 32 bits (`PHP_INT_SIZE` 4): nada de sintaxis 7.4 (`fn`, propiedades tipadas) y números grandes
  comparados como texto antes de `(int)`; `mb_*` con `'UTF-8'` explícito.
- Licencia del médico de prueba de hclinica: extendida a propósito para las pruebas manuales del usuario; no
  restaurarla (ver `MEMORY.md`).
- No matar el `devWeb` (Gradle/Node) que tenga abierto el usuario.
- Tokens de prueba de pediatría: en `C:\Users\flor\AppData\Local\Temp\opencode\respaldos\tokens-prueba-pediatria-local.txt`
  (fuera de los repos; no copiarlos a ningún lado). Los de clínica salen de `auth/login` de turnos local.
- Al final de las pruebas de cada tarea, vaciar `salud360_token_cache` en `hc_clinica` (y en `hc_pediatrica` si se
  usó), porque cerrar sesión no la invalida hasta F1.7.

## Tanda F1 — Servidor (hclinica y pediatría)

Sección "Servidor" del plan. F1.1 a F1.6 en `rebonline\hclinica` (rama `salud360-api`); F1.7 en `hclinica` y en
`rebonline\hc_pediatria` (rama `salud360-api`). URL base de las pruebas de clínica:
`http://localhost/HClinica-salud360/public/index.php/api/salud360/...`, con los tokens del médico y del
administrador de prueba sacados de `auth/login` de turnos local.

- [x] **F1.1. Reglas de enfermedades (puras) con phpunit.** RF-16, RF-43, RF-44, RF-50, RF-52, RF-54
  - `app/Services/Salud360/EnfermedadesReglas.php` (estática, sin base, como `PacienteVinculoReglas`):
    `normalizar` (recorta y junta solo los espacios de la spec, `[ \t\n\r\x0B\x0C]`, sin el `trim` por defecto de
    PHP), `clave` (`normalizar` + `mb_strtolower(..., 'UTF-8')` + `strtr` con la tabla fija de `claveDeNombre`),
    `errorDeNombre` (`vacio` / `largo` con `mb_strlen > 255` / `invisible` con
    `/[\p{Cc}\p{Cf}\p{Zl}\p{Zp}]|(?! )\p{Zs}/u`, o null; con los textos exactos de RF-43), `elegirExistente`
    (activa de menor id; si ninguna activa, la de menor id; null si no hay), `ordenar` (clave e id, ids comparados
    como texto de igual largo), `armarSeccion` (marcables = lista activa ∩ catálogo activo con `tiene`; otras = del
    paciente que no son marcables, dadas de baja incluidas) y `armarAdministrar` (lista + activas fuera de ella).
  - `tests/Unit/EnfermedadesReglasTest.php` (extiende `PHPUnit\Framework\TestCase`, sin Laravel ni base) con la tabla
    del plan: "Diabetes", "diabetes ", "DIABÉTES", "Diabetes  tipo 2"/"Diabetes tipo 2", tab y salto de línea, `\f`
    y `\v` como espacios; NBSP y U+200B → `invisible`; 255 caracteres con emojis sí, 256 no; vacío y solo espacios →
    `vacio`; `elegirExistente` (activa de menor id, sin activas → menor id, varias con la misma clave); orden con
    desempate por id; `armarSeccion` (lista vacía + otras, dada de baja en otras, marcada por otro médico en otras, no
    en la lista pero marcada → otras); `armarAdministrar` (dadas de baja afuera; las de la lista fuera del catálogo).
  - Hecho cuando: phpunit de `EnfermedadesReglasTest.php`, `PacienteVinculoReglasTest.php` y `PerfilesTest.php` en
    verde en la copia servida; `php -l` sin errores en los dos archivos nuevos; buscar `fn (` y `DB::` en ellos no
    da resultados; `git diff --stat` en `hclinica` muestra solo esos dos archivos.

- [x] **F1.2. "Enfermedades" en el catálogo de secciones.** RF-1, RF-2
  - `app/Services/Salud360/CatalogoSecciones.php`: `'enfermedades' => ['nombre' => 'Enfermedades', 'descripcion' =>
    'Casillas con las enfermedades del paciente, de la lista de cada médico.']`, segunda, entre
    `antecedentes_familiares` y `motivo_consulta` (PD9). `PerfilesService::validar` no cambia (usa `existe`).
  - Hecho cuando: `php -l` sin errores; phpunit de `PerfilesTest.php` y `EnfermedadesReglasTest.php` en verde en la
    copia servida; copiado a la servida, `curl` con el token del administrador a `GET config/catalogo` → `200` y
    `enfermedades` es la segunda sección (solo se miran los ids); `git diff` limitado a `CatalogoSecciones.php`.

- [x] **F1.3. Servicio: estado de la sección, marcar, estado de administrar, agregar y quitar.** RF-50, RF-51,
  RF-52, RF-53, RF-55
  - Antes de escribir: SQL de solo lectura sobre `hc_clinica` (estructura): `SHOW CREATE TABLE` de `enfermedades`,
    `medico_enfermedades` y `paciente_enfermedades` (columnas `activo`, `created_at`/`updated_at`, `unique(nombre)`,
    `unique(medico_user_id, enfermedad_id)`, `unique(paciente_id, enfermedad_id)`, intercalación de `nombre`) y la
    de `pacientes` (`activo`). Si algún `unique` o columna no está como en `database/migrations`, parar y avisar
    (el plan cuenta con ellos para `ON DUPLICATE KEY UPDATE`). Anotar lo verificado en el PHPDoc del servicio.
  - `app/Services/Salud360/EnfermedadesService.php` (nuevo, usa `EnfermedadesReglas`): `estadoSeccion($medicoId,
    $pacienteId)`, `estadoAdministrar($medicoId)`, `marcar(...)` (con `tiene`: `INSERT … ON DUPLICATE KEY UPDATE
    activo = 1, updated_at = ?`; sin `tiene`: `UPDATE … SET activo = 0 WHERE … AND activo = 1`, nunca crea una fila
    para desmarcar) y `enLista(...)` (igual sobre `medico_enfermedades`; quitar no toca `paciente_enfermedades`, D1).
    Los controles de lista activa y catálogo activo con los códigos del plan (`no_en_tu_lista`,
    `enfermedad_dada_de_baja`; id inexistente según PD8). Nunca `DELETE`, nunca `medico_infos`.
  - Hecho cuando: `php -l` sin errores; phpunit de F1.1 en verde; `git diff` muestra solo el archivo nuevo; buscar
    `delete`, `DELETE` y `medico_infos` en él no da resultados; y se ejercita con `php artisan tinker` (o `php -r`
    con el bootstrap) en la copia servida con el médico de prueba, un paciente "PRUEBA SALUD360" de su cartera y una
    enfermedad "Prueba Salud360 1" creados por SQL: estado con listas vacías; agregar a la lista → marcable sin
    marcar; marcar dos veces → una sola fila activa en `paciente_enfermedades` (`SELECT COUNT(*)`); desmarcar → la
    fila queda con `activo = 0`; quitar de la lista → el conteo de `paciente_enfermedades` del paciente de prueba no
    cambia y la marcada pasa a `otras`; marcar una que no está en la lista → `no_en_tu_lista`; agregar una dada de
    baja por SQL → `enfermedad_dada_de_baja`. Filas de prueba borradas con `DELETE` puntual (conteos con el prefijo
    de prueba y el documento de prueba en 0).

- [x] **F1.4. Servicio: crear (reutilizar, reactivar, `unique`, marcar al paciente).** RF-44, RF-54, RF-55
  - `EnfermedadesService::crear($medicoId, $nombre, $pacienteId)` como el plan: `normalizar` → `errorDeNombre`
    (`nombre_invalido` con el texto de RF-43) → todas las filas de `enfermedades` y `elegirExistente` por clave; sin
    existente, `INSERT` y, si choca con `unique(nombre)`, `WHERE nombre = ?` y se usa esa; inactiva → `activo = 1`;
    después `enLista(…, true)` (`ya_en_lista`) y, con paciente, ficha y cartera → `marcar(…, true)` y `seccion`, si
    no `sin_marcar_por` (`paciente_no_encontrado` / `fuera_de_cartera`). Devuelve `creada` (para el 201),
    `reutilizada`, `reactivada`, `ya_en_lista`, `marcada`. Sin transacción global ni `GET_LOCK`.
  - Hecho cuando: `php -l` sin errores; phpunit de F1.1 en verde; con tinker en la copia servida (mismos datos de
    prueba que F1.3): "Prueba Salud360 2" nueva → creada, en la lista y marcada al paciente; "prueba  salud360 2 " →
    reutilizada sin otra fila (`SELECT COUNT(*)` por el prefijo); dada de baja por SQL y vuelta a crear → reactivada
    (`activo = 1`); ya marcada sigue marcada; sin paciente → no marca; paciente fuera de la cartera → en la lista,
    `sin_marcar_por = fuera_de_cartera`; nombre con U+200B → `nombre_invalido`; un nombre que la intercalación
    iguala y la clave no (p. ej. distinto solo en un carácter que `utf8mb4_unicode_ci` iguala fuera de la tabla de
    tildes) → usa la existente sin error. Buscar `DELETE` y `medico_infos` en el diff no da resultados. Filas de
    prueba borradas con `DELETE` puntual.

- [x] **F1.5. Controlador y rutas.** RF-49, RF-50, RF-51, RF-52, RF-53, RF-54
  - `app/Http/Controllers/Api/Salud360/EnfermedadController.php` (nuevo): todos con `soloMedico` (`403 sin_permiso`,
    sin mirar `users.perfil`); ids de la ruta con `ctype_digit` y ≤ 9 cifras (si no, `422 datos_invalidos`); ficha
    (`404 paciente_no_encontrado`) y cartera (`403 fuera_de_cartera`) como `PacienteController@show` (si hace falta,
    `fichaYCartera` común en `Salud360Controller`, mismos códigos y textos); `tiene`/`en_lista` obligatorios
    (`422 datos_invalidos`); `POST enfermedades` → 201 si creó una fila, 200 si no. Respuestas con `seccion` y
    `administrar` en el formato del plan (listas siempre presentes).
  - `routes/api.php`: `GET enfermedades/pacientes/{pacienteId}`, `PUT enfermedades/pacientes/{pacienteId}/{enfermedadId}`,
    `GET enfermedades/lista`, `PUT enfermedades/lista/{enfermedadId}`, `POST enfermedades`, dentro del grupo
    `salud360`.
  - Hecho cuando: `php -l` sin errores; copiado a la servida, `php artisan route:list` muestra las cinco rutas con el
    middleware `salud360` (y el `throttle` del grupo `api`); con curl: sin token → `401 sin_token`; token del
    administrador en las cinco → `403 sin_permiso`; médico: `GET enfermedades/lista` → 200 con `administrar.lista` y
    `administrar.catalogo` (solo se miran claves y conteos); `GET enfermedades/pacientes/0` →
    `404 paciente_no_encontrado`; `GET enfermedades/pacientes/1234567890` → `422 datos_invalidos`; `PUT` sin `tiene`
    → `422 datos_invalidos`; la web de hclinica sigue entrando normal. `salud360_token_cache` vaciada al terminar.

- [x] **F1.6. Documentación y prueba de humo completa.** RF-49, RF-50, RF-51, RF-52, RF-53, RF-54, RF-55
  - `md/API_SALUD360_CLINICA.md`: sección "Enfermedades" (rutas, cuerpos, respuestas, códigos, PD8, curl de humo sin
    credenciales) y tabla "Código" con los archivos nuevos.
  - Humo con curl contra la copia servida (lista del plan, "Tests" → hclinica), con datos de prueba creados por SQL
    (enfermedades "Prueba Salud360 …", paciente "PRUEBA SALUD360" en la cartera y otro fuera): médico con
    `users.perfil ≠ 3` → 200 (RF-49); estado con listas vacías → 200 con `marcables: []` y `otras: []`; ficha de baja
    → `404 paciente_no_encontrado`; fuera de la cartera → `403 fuera_de_cartera`; marcar dos veces igual → 200 y el
    mismo estado; `no_en_tu_lista` y `enfermedad_dada_de_baja` → 422; id inexistente → `422 no_en_tu_lista` al
    marcar y `422 enfermedad_dada_de_baja` al agregar (PD8); quitar → 200 y el conteo de `paciente_enfermedades`
    igual antes y después; `PUT enfermedades/lista` con el paciente de la cartera trae `seccion` y con uno fuera no
    la trae (200); crear nueva → 201, reutilizada por clave → 200 `reutilizada: true`, reactivada → 200
    `reactivada: true`, `nombre_invalido` → 422, alta concurrente con dos curl del mismo nombre → una sola fila;
    paciente fuera de la cartera al crear → 200 `sin_marcar_por: "fuera_de_cartera"`.
  - Hecho cuando: todos los códigos de arriba salen como se listan; phpunit de F1.1 en verde; la copia servida es
    igual al clon en los archivos de la tanda (comparación de contenido, sin `.env`/`vendor/`); el `.md` no tiene
    tokens, contraseñas ni nombres de médicos o pacientes; al terminar, `SELECT COUNT(*)` de `enfermedades` con
    `nombre LIKE 'Prueba Salud360%'`, de sus filas en `medico_enfermedades` y `paciente_enfermedades`, y de
    `pacientes`/`medico_pacientes` con los documentos de prueba da 0; ninguna fila preexistente cambió de `activo`
    (conteos por `activo` iguales a los de antes de empezar); `salud360_token_cache` vaciada.

- [x] **F1.7. `auth/salir` en hclinica y pediatría.** RF-59 (PD7)
  - En los dos repos: `POST salud360/auth/salir` **fuera** del middleware `salud360` (en `routes/api.php`, mismo
    prefijo y namespace): toma el token con `TobbAuthService::tokenDelPedido`, borra la fila de
    `salud360_token_cache` con su `hash('sha256', …)` (`token_hash`) y contesta siempre `200 {ok: true}`, sin decir
    si existía (también sin token). Sin más autenticación. `cache_minutos` queda en 10.
    `md/API_SALUD360_CLINICA.md` y `md/API_SALUD360_PEDIATRIA.md` documentan la ruta y por qué existe.
  - Hecho cuando, en cada copia servida: `php -l` sin errores; `php artisan route:list` muestra `auth/salir` **sin**
    el middleware `salud360`; con curl y un token nuevo del médico de prueba sacado de `auth/login` de turnos local:
    `GET` de una ruta protegida (clínica: `enfermedades/lista`; pediatría: `auth/perfil`) → 200; `POST auth/logout`
    de turnos → el mismo `GET` sigue en 200 (la caché); `POST auth/salir` → 200; el mismo `GET` → `401`; `POST
    auth/salir` sin token y con un token cualquiera → 200; el conteo de `salud360_token_cache` baja en una fila
    tras el `auth/salir` (solo conteos). No usar con `auth/salir` los tokens sembrados de pediatría del archivo de
    respaldos (los sacaría de la caché). `HcPediatriaE2ETest` con `--rerun` contra la copia servida de pediatría
    sigue en verde. `git diff` en cada repo limitado a la ruta, el controlador (o método) nuevo y el `.md`.

Decidido (2026-10-20, coordinador):
- C1 `ficha_no_elegible` (RF-10): el texto es "La ficha elegida no corresponde a este paciente. Elegí otra o pedile
  al administrador que revise el documento." Reflejado en `spec.md` RF-10.
- C2 Los criterios de finalización de `spec.md` se alinean con PD4 y PD5: `ColumnasEnfermedadesTest` va en
  `:core:ui:jvmTest` (reutiliza `columnasPara`) y no hay `tests/Feature` de phpunit que escriban en la base (Unit de
  reglas + curl + E2E con limpieza). Corregido en `spec.md`.
- C3 El test del logout de la app (F3.8, RF-59) usa una interfaz propia inyectable, sin agregar dependencias (nada de
  `ktor-client-mock`).
- C4 La revocación del token al cerrar sesión corre en segundo plano y no demora el cierre en el dispositivo (RF-59:
  si alguna HC no responde, igual cierra).
- C5 `crear` va sin transacción global (como lo implementó F1.4): cada paso es idempotente y la búsqueda después del
  rechazo del `unique` tiene que ver lo que otro pedido ya confirmó. Aceptado.

Al terminar la tanda: validación del reviewer y MEMORY.md.

Tanda F1 validada por el reviewer el 2026-10-21: **APROBADO**. Lo que dejó "Para la tanda F2 (app)" está incorporado
en F2.1, F2.5 y F2.6.

## Tanda F2 — Lógica y datos de la app

Rama `hc-general` de este repo. Sección "App" del plan: catálogo, datos de red, lógica pura, base local, repositorio y
controlador (las pantallas son la F3). La lógica pura va en `core/data/src/commonMain/kotlin/com/salud360/core/data/hcgeneral/`
(sin Compose ni red) con sus tests en `core/data/src/commonTest/.../hcgeneral/`, que corren con `:core:data:jvmTest`.

Reglas de la F2, además de las de arriba:
- **Tests primero**: cada test nuevo o cambiado se corre y se ve en **rojo** antes de escribir el código (falla por
  lo que falta, no por no compilar algo ajeno); después en verde. Al terminar cada tarea, además del comando de la
  tarea, `.\gradlew :core:data:jvmTest` completo en verde.
- Commits locales como `rodri89`, uno por tarea, con el formato del historial (`hc: …`, `datos: …`, `base: …`), **sin
  push** (pushear `main` publica).
- Nada contra producción: ni tests ni `devWeb` con `-Pentorno=release`; la compilación web siempre con
  `-Pentorno=dev` (sin `-Pentorno`, `compileKotlinWasmJs` queda contra producción).
- E2E solo contra la copia local de hclinica (`http://localhost/HClinica-salud360/public/index.php`), con
  enfermedades "Prueba Salud360 …" y pacientes "PRUEBA SALUD360" con DNI desde 99000090 (verificado libre antes con
  `SELECT COUNT(*)`), y limpieza por SQL puntual (`DELETE` por id/prefijo, nunca `DROP TABLE`).
- Nada de `.\gradlew --stop` ni matar el `devWeb` (Gradle/Node) que tenga abierto el usuario.
- La copia local de enfermedades **nunca `dirty`**: su tabla no tiene esa columna, y ningún dato de enfermedades va a
  `seccion_valor` ni a `paciente_extra`. Nada de enfermedades desde el autoguardado, `HcApiSync` ni `SyncEngine`.
- Migración nueva `7.sqm` (v7 → v8) **y** `HcEnfermedades.sq` con la misma tabla; columnas nombradas, tabla nueva
  entera (nada al final de otra); el paso de versión 7 a 8 se prueba sobre una base con datos (como
  `ConfigHcRepositoryTest`).
- Textos de la spec copiados tal cual (RF-11, RF-12, RF-18, RF-24 a RF-31, RF-35, RF-43, RF-44, RF-58).

- [x] **F2.1. `claveDeNombre` con los espacios de la spec + nombre de enfermedad.** RF-42, RF-43, RF-44
  - `hcgeneral/PerfilValidacion.kt` (`claveDeNombre`, l. 108-129): hoy usa `trim()` + `Regex("\\s+")`, que no
    coinciden con el PHP (`EnfermedadesReglas::normalizar`, solo `[ \t\n\r\x0B\x0C]`) y dependen de la plataforma
    (`trim()` usa `isWhitespace`, que en JVM recorta además NBSP, los demás `Zs`, U+2028/U+2029 y U+001C–U+001F; `\s`
    no es el mismo en JVM y en wasm). Pasa a recortar y juntar **solo** esos seis caracteres, con un conjunto explícito
    (sin `trim()`, `isWhitespace` ni `\s`), mediante `nombreNormalizado` de `NombreEnfermedad.kt`. KDoc: es la misma
    regla que `EnfermedadesReglas::normalizar`/`clave` de hclinica.
  - `hcgeneral/NombreEnfermedad.kt` (nuevo): `nombreNormalizado(s)`; `errorDeNombreEnfermedad(normalizado): String?`
    con los tres textos de RF-43 (sin punto final, iguales a `MENSAJES_NOMBRE` del PHP), largo con `caracteres()`
    (> 255), invisibles con `Char.category` (CONTROL, FORMAT, LINE_SEPARATOR, PARAGRAPH_SEPARATOR, SPACE_SEPARATOR
    salvo U+0020) más la tabla de `Cf` fuera del plano básico del plan (U+1BCA0–1BCA3, U+1D173–1D17A, U+E0001,
    U+E0020–E007F); `mismoNombre(a, b)`.
  - `NombreEnfermedadTest` (nuevo, commonTest): copia de los proveedores `nombres` (escrito → normalizado, clave y
    sin error) e `invalidos` (escrito → error) de `hclinica/tests/Unit/EnfermedadesReglasTest.php`, caso por caso y
    con el mismo nombre de caso en un comentario (`\u{XXXX}` → `\uXXXX`; U+E0001 como el par sustituto
    `"\uDB40\uDC01"`; `str_repeat` → `repeat`); también `testMismoNombrePorClave` y los tres textos. El caso de UTF-8
    inválido (`"Diabetes \xC3"`) no tiene equivalente en un `String` de Kotlin: no se copia (comentario en el test).
    Casos de NBSP al borde que el PHP no tiene: `"\u00A0Diabetes"` y `"Diabetes\u00A0"` → `invisible` (no se
    recortan) y `claveDeNombre("\u00A0Diabetes") != claveDeNombre("Diabetes")`.
  - Comportamiento fijado en JVM: `claveDeNombre` deja en la clave NBSP, U+3000, U+2028 y U+001F al borde y en el
    medio (antes `trim()` los sacaba de los bordes en JVM). Nota en el KDoc del test: commonTest solo corre en JVM (no
    hay tests en wasm); como la implementación no usa nada que dependa de la plataforma, en wasm da lo mismo, pero no
    se verifica allá.
  - Diferencia aceptada, como comentario en el test (sin caso): `lowercase()` de Kotlin (JVM y JS) aplica la sigma
    final (`Σ` al final de palabra → `ς`) y `mb_strtolower` de PHP 7.3 no (`σ`): un nombre en griego que termine en
    `Σ` puede tener otra clave en la app que en la API. Solo afecta nombres en griego; la API decide (D7).
  - Perfiles de la 001 (usan `claveDeNombre`: `validarPerfil`, que recorta con `trim()` antes pero compara contra
    `otrosNombres` sin recortar; `nombreCopia`; `nuevaPestania`; `CLAVE_CUERPO` de `DefinicionHcGeneral.kt`): sus tests
    siguen en verde **sin tocarlos**. Si algún caso cambia de resultado, no se ajusta el test: se anota en el commit y
    en esta tarea, y se avisa.
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*NombreEnfermedadTest*'` en verde (rojo antes);
    `.\gradlew :core:data:jvmTest --tests '*PerfilValidacionTest*' --tests '*PerfilCopiaTest*' --tests
    '*EditorPerfilTest*' --tests '*DefinicionHcGeneralTest*' --tests '*CatalogoAdminTest*'` en verde;
    `.\gradlew :core:data:jvmTest` y `.\gradlew :especialidades:clinica:jvmTest` en verde; buscar `trim(`,
    `isWhitespace` y `\\s` en `claveDeNombre` y en `NombreEnfermedad.kt` no da resultados.

- [x] **F2.2. Estado y vista de la sección.** RF-5, RF-6, RF-7, RF-15, RF-16, RF-17, RF-18, RF-19, RF-35, RF-36
  - `hcgeneral/EnfermedadesSeccion.kt` (nuevo): `Enfermedad(id: Long, nombre)`, `Marcable(id, nombre, tiene)`,
    `EstadoSeccionEnfermedades(marcables, otras)`, `EstadoAdministrar(lista, catalogo)`; `ordenAlfabetico` (por
    `claveDeNombre` y, a igual clave, id); `vistaSeccion(estado, editable)` (abierta: grilla o el texto de RF-18, más
    "Otras enfermedades del paciente" si hay; cerrada/solo lectura: "Estado actual del paciente" con las marcadas y las
    otras juntas y ordenadas, o "Sin enfermedades registradas"); `enfermedadesDelPaciente(estado)` (para "Datos");
    `seccionEnfermedadesVisible(def, tipo, ocultas)` y `enfermedadesEnDatos(def, ocultas)` (en "Datos" solo si no está
    en el perfil para **ningún** tipo, o está oculta; sin perfil también).
  - `EnfermedadesSeccionTest` (nuevo, commonTest): grilla con su marca (RF-15); orden por clave con desempate por id
    y tildes (`"ácido úrico"` antes de `"Asma"`, como el caso de orden del PHP) (RF-16); sin marcables → texto de RF-18
    y siguen "Otras" (RF-18); "Otras" solo si hay (RF-19); cerrada con marcadas + otras ordenadas, cerrada vacía,
    desmarcadas fuera (RF-35); visible en Consulta y no en Telemedicina si solo está en Consulta (RF-5); sin perfil,
    fuera del perfil, oculta → no visible y sí en "Datos"; en el perfil solo para Consulta → ni en Telemedicina ni en
    "Datos" (RF-6, RF-7); nunca visible y en "Datos" a la vez (recorrer las combinaciones); la sección `CUSTOM`
    `enfermedades` no sale en `soloLecturaConsulta(...).alFinal` (con una definición de prueba que la tiene, RF-7).
  - `core/ui` `ColumnasEnfermedadesTest` (nuevo, PD4, sobre `columnasPara` de `SiNoVacio.kt`): 599,9 → 2, 600 → 4,
    320 → 2, 1200 → 4 (RF-17).
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*EnfermedadesSeccionTest*'` y
    `.\gradlew :core:ui:jvmTest --tests '*ColumnasEnfermedadesTest*'` en verde (rojo antes); `:core:data:jvmTest` y
    `:core:ui:jvmTest` completos en verde.

- [x] **F2.3. Motivos.** RF-9, RF-10, RF-11, RF-12, RF-13, RF-14, RF-30, RF-31
  - `hcgeneral/MotivosEnfermedades.kt` (nuevo): `sealed interface MotivoEnfermedades { prioridad; texto }` con
    `General(MotivoHc)` (prioridad por `MotivoHc.Tipo`: sesión 1, HC 2, médico 3, licencia 4, `SIN_CONEXION` y `OTRO`
    5, PD3), `ElegirFicha(candidatas)` 6, `SinFicha(texto)` 7 (`DocumentoInvalido` con su mensaje,
    `FichaDeOtroPaciente` con su mensaje, `FichaNoElegible` con el texto de RF-10, PD2), `FichaNoDisponible` 8 ("La
    ficha de este paciente no está disponible en la web"), `FueraDeCartera` 9 ("Este paciente no está en tu
    cartera"). `motivoDeVinculo(ResultadoVinculo, nombreHc)` (`Vinculado` → null; `NoSePudo(e)` → `motivoDeError`),
    `motivoDeError(TobbException, nombreHc)` (`404 paciente_no_encontrado` → 8; `403 fuera_de_cartera` → 9; el resto,
    `motivoHc`), `primerMotivo(motivos)`, `muestraCopia(motivo)` (solo lugar 5: `SIN_CONEXION` y `OTRO`, con el texto
    del `MotivoHc` en `OTRO`).
  - `MotivosEnfermedadesTest` (nuevo, commonTest): cada `ResultadoVinculo` a su motivo (los tres "sin ficha" en el
    lugar 7 con su texto; elegir ficha con las candidatas); 404/403 de la sección; sesión, HC, médico y licencia con
    el mismo texto que `motivoHc` (RF-13); `500` sin código, `500 enfermedades`, `503 ocupado` y respuesta ilegible
    (`respuesta`) → lugar 5 con el texto de la web y `muestraCopia` (RF-30, RF-31); red y `tobb_caido` → lugar 5 sin
    conexión; `primerMotivo` con los nueve de a pares y una lista vacía → null (RF-14); 1-4 y 6-9 no muestran copia.
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*MotivosEnfermedadesTest*'` en verde (rojo antes);
    `:core:data:jvmTest` completo en verde.

- [x] **F2.4. Marcado (respuestas desordenadas, Guardando/Guardado, rechazos).** RF-21 a RF-29
  - `hcgeneral/MarcadoEnfermedades.kt` (nuevo), como el plan: `EstadoMarcado(confirmado, enCamino: Map<Long,
    EnCamino(tiene, secuencia)>, ultimaAplicada, aviso, guardado, soloLectura)` y las transiciones `tocar`,
    `confirmado(secuencia, id, estado)`, `rechazado(secuencia, id, error)`, `sinRespuesta(secuencia, id)`,
    `estadoPedido(secuencia, estado)`, `estadoPedidoSinRespuesta`. Cada transición devuelve el estado nuevo y qué
    pedir después (p. ej. "pedir el estado"), sin hacer pedidos. Vista = `confirmado` con lo de `enCamino` encima.
  - `MarcadoEnfermedadesTest` (nuevo, commonTest): tocar muestra lo elegido y "Guardando…" (RF-21); no se puede tocar
    la que está en camino y sí las demás (RF-22); respuestas al revés: la vieja se descarta, saca su casilla de "en
    camino" y la deja con lo elegido; las que siguen en camino conservan lo elegido (RF-23); `guardado` sube con cada
    confirmación (RF-24); `422 no_en_tu_lista` / `enfermedad_dada_de_baja` → aviso con el nombre y pedir el estado
    (RF-25); 429 → revierte y "Demasiados cambios seguidos, esperá un momento" (RF-26); 403 `licencia_vencida` y
    otros de RF-11 a RF-13 → revierte y motivo; error no previsto → revierte y "No se pudo guardar el cambio. Probá de
    nuevo." (RF-27); sin respuesta → no revierte y pide el estado; ese también sin respuesta → último confirmado,
    aviso de RF-28 con el nombre y `soloLectura` (RF-28, RF-30); una respuesta tardía con secuencia mayor se aplica
    pero `soloLectura` sigue; un solo aviso, el último, que se limpia con la siguiente confirmación (RF-29).
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*MarcadoEnfermedadesTest*'` en verde (rojo antes);
    `:core:data:jvmTest` completo en verde.

- [x] **F2.5. Administrar (buscador, crear, avisos).** RF-37, RF-39, RF-42, RF-44, RF-45, RF-46, RF-48, RF-58
  - `hcgeneral/AdministrarEnfermedades.kt` (nuevo): `filtrar(estado, buscado)`, `ofrecerCrear(estado, buscado)`,
    `pacienteParaCrear(fichaId, motivo, editable)`, `puedeAdministrar(editable, propia, conConexion, motivo)` y
    `avisoCrear(resultado, motivoLocal)` como el plan. `avisoCrear` recibe un modelo propio de este archivo
    (`ResultadoCrear(enfermedad, reutilizada, reactivada, yaEnLista, marcada, sinMarcarPor: String?)`), no el DTO
    (que llega en F2.6 y se convierte a este modelo), así esta tarea no depende de la red. Avisos (reviewer F1, punto
    4): con `reutilizada` usa `enfermedad.nombre` (el del catálogo) en "Ya existía en el catálogo como «…»"; `ya_en_lista`
    → "«…» ya estaba en tu lista"; `sinMarcarPor` `fuera_de_cartera` → "el paciente no está en tu cartera",
    `paciente_no_encontrado` → "el paciente no está vinculado a una ficha de la web" (ficha no disponible, RF-58);
    cualquier otro valor (`no_en_tu_lista`/`enfermedad_dada_de_baja` en una carrera, o uno nuevo) = no se marcó, sin
    motivo: "Se agregó a tu lista; no se marcó al paciente."; el motivo local (sin vínculo, motivos 6-9, consulta no
    editable) gana sobre lo que diga la web.
  - `AdministrarEnfermedadesTest` (nuevo, commonTest): filtro por clave contenida (tildes y mayúsculas), de la lista y
    de "Del catálogo", duplicados visibles, buscador vacío o solo espacios → "Escribí para buscar en el catálogo"
    (RF-39); `ofrecerCrear` con el nombre normalizado, no si hay uno con el mismo nombre en la lista o en "Del
    catálogo", no si está vacío (RF-42); `pacienteParaCrear` manda la ficha solo con la consulta editable y vinculada
    (RF-44, RF-58); avisos de nueva, reutilizada (con el nombre del catálogo, distinto de lo escrito), reactivada, ya en
    la lista, marcada, sin marcar por cada motivo y por un `sin_marcar_por` desconocido (RF-44, RF-58);
    `puedeAdministrar` sí con motivos 6-9 y sin vínculo, no con 1-5, sin conexión ni cerrada (RF-37).
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*AdministrarEnfermedadesTest*'` en verde (rojo antes);
    `:core:data:jvmTest` completo en verde. (RF-45, RF-46 y RF-48 se terminan de probar en F2.8.)

- [ ] **F2.6. DTOs y `EnfermedadesApi`.** RF-50, RF-51, RF-52, RF-53, RF-54
  - `core/data/.../network/hc/EnfermedadesDtos.kt` (nuevo): los DTOs del plan con `@SerialName` en snake_case
    (`ya_en_lista`, `sin_marcar_por`, `en_lista`, `paciente_id`) según la sección "Enfermedades" de
    `hclinica/md/API_SALUD360_CLINICA.md`; `RespuestaCrearDto` suma `creada` (el servidor lo manda; el plan no lo
    nombra) y `sinMarcarPor: String?` crudo; conversión a los modelos de F2.2 y a `ResultadoCrear` de F2.5.
  - `EnfermedadesApi.kt` (nuevo): `interface EnfermedadesApi` y `EnfermedadesApiRemota(cliente: HcApiClient)`, con las
    rutas `enfermedades/pacientes/{ficha}`, `enfermedades/pacientes/{ficha}/{enfermedad}` (PUT), `enfermedades/lista`,
    `enfermedades/lista/{enfermedad}` (PUT) y `enfermedades` (POST). Como `PacientesHcApiRemota`: el armado de rutas,
    cuerpos y la lectura de respuestas en funciones `internal` para probar sin red.
  - `EnfermedadesDtoTest` (nuevo, commonTest): cuerpos (`{"tiene":true}`, `en_lista` con y sin `paciente_id`,
    `nombre` con y sin `paciente_id`); las respuestas de ejemplo del md (sección, administrar, crear con y sin
    `seccion`, listas vacías); `sin_marcar_por` null, conocido y desconocido; **201 y 200** de `POST enfermedades`
    pasan los dos por `HcApiClient.interpretar` como éxito (reviewer F1, punto 4); 429 de Laravel (`{"message": "Too
    Many Attempts."}`, sin `codigo`) → `TobbException` con status 429 y `codigo` null (RF-26); `422 nombre_invalido`
    trae el `mensaje`.
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*EnfermedadesDtoTest*'` en verde (rojo antes);
    `:core:data:jvmTest` completo en verde.

- [ ] **F2.7. Migración `7.sqm`, copia en el dispositivo y borrado al cambiar de sesión.** RF-11, RF-30, RF-31,
  RF-32, RF-33, RF-34
  - `core/database`: `migrations/7.sqm` (v7 → v8, PD1) y `HcEnfermedades.sq` con la misma tabla `hc_enfermedades`
    del plan (sin `dirty` ni `deleted`) y las consultas `copia`, `guardar` (`INSERT OR REPLACE` con columnas
    nombradas), `borrarFicha`, `borrarTodo`, `borrarDeOtros(medico_id)`. Comentario en los dos, como `HcConfig.sq`/`6.sqm`.
  - `repos/EnfermedadesRepository.kt` (nuevo), como el plan: espera con `withTimeoutOrNull` sobre el `await` del
    `Deferred`, no sobre el pedido; sin API = sin conexión; guarda solo respuestas correctas de su parte (también
    listas vacías); `403 fuera_de_cartera` borra la `seccion` de esa ficha; error de red, `tobb_caido`, tiempo cumplido
    y errores de lugar 5 → copia (si hay) en solo lectura con el texto que corresponda.
  - `AuthRepository`: `logout` → `borrarTodo`; `guardarSesion` → `borrarDeOtros(medicoId)` con el mismo id de médico
    que usa `ConfigHcRepository`, y `borrarTodo` si quien entra no es médico. Como `guardarSesion` es privada y solo se
    llega por `login`/`restaurar` (que piden a turnos), el borrado va en una función `internal` que se prueba sola; el
    test no llama a `login` ni a `restaurar` (pegarían a la URL por defecto de turnos).
  - `ConfigHcRepositoryTest`: hoy afirma `Salud360Db.Schema.version == 7` y `user_version` 7 tras abrir una base v6:
    se actualizan a 8 (el paso 6 → 7 sigue igual). Es el único cambio a un test existente.
  - `CopiaEnfermedadesTest` (nuevo, jvmTest, SQLite en archivo temporal como `ConfigHcRepositoryTest`, API falsa):
    guarda `administrar` y `seccion` por médico y ficha; lista vacía reemplaza; un 403/422/500 no pisa (RF-33);
    `fuera_de_cartera` borra solo esa ficha y no muestra copia (RF-11); sin red con copia → solo lectura con "Sin
    conexión: las enfermedades se pueden ver pero no cambiar"; con un 500 → copia y el texto de la web (RF-30); sin red
    y sin copia → "Hace falta conexión para ver las enfermedades", con un 500 el texto de la web (RF-31); sin API =
    sin conexión; respuesta lenta: a la `espera` (~100 ms) es sin conexión y la respuesta tardía igual actualiza la
    copia; columnas de la tabla sin `dirty`/`deleted` y, tras guardar, ni `seccion_valor` ni `paciente_extra` tienen
    filas nuevas (RF-32); `logout` borra todo; entrar con otro médico borra lo de los demás y conserva lo propio;
    administrador/secretaria borran todo (RF-34); migración: `Schema.version == 8`, `migrate(driver, 7, 8)` crea la
    tabla, y una base v7 **con datos** (fila en `hc_config` y en `sync_estado`, `user_version = 7`, sin la tabla) se
    abre con el driver, queda en 8 y conserva esas filas.
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*CopiaEnfermedadesTest*' --tests '*ConfigHcRepositoryTest*'`
    en verde (rojo antes); `:core:data:jvmTest` completo en verde; `7.sqm` y la tabla de `HcEnfermedades.sq` son iguales
    (comparación de texto del `CREATE TABLE`); `:composeApp:compileKotlinWasmJs -Pentorno=dev` compila (driver web con
    `generateAsync`).

- [ ] **F2.8. Controlador de la sección y `DataModule`.** RF-8, RF-15, RF-21 a RF-28, RF-40, RF-41, RF-44, RF-45,
  RF-46, RF-48
  - `repos/EnfermedadesControlador.kt` (nuevo, coroutines sin Compose), como el plan: `StateFlow` con el estado de la
    sección; `abrir()`, `elegirFicha(id)`, `tocar(id, tiene)`, `reintentar()`, `abrirAdministrar()`, `agregar`,
    `quitar`, `crear(nombre)`; aplica `MarcadoEnfermedades` y `AdministrarEnfermedades`; las respuestas de la lista con
    `seccion` pasan por las mismas secuencias; un cambio de la lista por vez; recibe el `CoroutineScope` y la `espera`.
  - `DataModule`: `single { EnfermedadesRepository(...) }` sobre el cliente `CodigosHc.GENERAL`, fuera de `HcBackends`,
    con el nombre visible de la HC general de `nombresHc` (como `ConfigHcRepository`).
  - `EnfermedadesControladorTest` (nuevo, jvmTest, `runBlocking`, base temporal, API falsa con respuestas que se
    liberan a mano y `espera` ~100 ms): sin vínculo resuelve con `PacientesHcRepository` y lo guarda; con vínculo no lo
    pide; abrir hace un solo pedido y reemplaza la copia (RF-8, RF-15); elegir ficha guarda y carga (RF-9); tocar manda
    el estado elegido sin pasar por autoguardado (RF-21); dos toques respondidos al revés (RF-23); 422 pide el estado
    (RF-25); sin respuesta y estado sin respuesta → solo lectura con la copia (RF-28); `abrirAdministrar` hace un
    pedido; agregar/quitar muestran administrar y sección de la respuesta, quitar la deja en "Otras" (RF-40, RF-41);
    crear manda la ficha y la grilla la muestra marcada; sin vínculo no la manda (RF-44, RF-58); un cambio de la lista
    por vez (RF-45); un rechazo conserva lo escrito y sin conexión pide administrar (RF-46); una respuesta con la lista
    cambiada desde otro lado se muestra entera (RF-48); tras marcar, `seccion_valor` sigue sin filas de `enfermedades`
    (RF-36).
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*EnfermedadesControladorTest*'` en verde (rojo antes);
    `:core:data:jvmTest` completo en verde; `.\gradlew :composeApp:compileKotlinWasmJs -Pentorno=dev` compila.

- [ ] **F2.9. "Enfermedades" en el catálogo de la app.** RF-1, RF-2, RF-3, RF-56, RF-57
  - `especialidades/clinica/HcGeneral.kt`: `SeccionCatalogo(SeccionDef("enfermedades", "Enfermedades",
    TipoSeccion.CUSTOM, renderKey = "enfermedades", icono = …))` segunda, entre `antecedentes_familiares` y
    `motivo_consulta` (PD9); el KDoc pasa de "24 secciones" a 25. El renderer se registra en F3.4: hasta entonces, un
    perfil con la sección muestra "Sección no disponible: enfermedades" (`ConsultaScreen.kt:337`); sin push, aceptado.
  - `CatalogoHcGeneralTest`: `catalogoServidor` suma `"enfermedades" to "Enfermedades"` en el segundo lugar; caso nuevo:
    es `CUSTOM` con `renderKey == id`, no es `diagnostico` ni legada (RF-56); `hcGeneralContribution.renderers` sigue
    `{child_pugh}` (pasa a `{child_pugh, enfermedades}` en F3.4); `clinica_medica_sigue_igual`, `hepatologia_sigue_igual`
    y los perfiles iniciales sin cambios (RF-57). RF-3: sin código; `CatalogoAdminTest` existente en verde.
  - Hecho cuando: `.\gradlew :especialidades:clinica:jvmTest` en verde (rojo antes por el catálogo);
    `.\gradlew :core:data:jvmTest` en verde; `.\gradlew :composeApp:compileKotlinWasmJs -Pentorno=dev` compila.

- [ ] **F2.10. E2E contra la copia local de hclinica.** RF-15, RF-41, RF-44, RF-51, RF-53, RF-54
  - `core/data/src/jvmTest/.../repos/HcClinicaEnfermedadesE2ETest.kt` (nuevo): se saltea solo sin
    `salud360.test.hc.clinica`, `.clinica.token` y `.dni`/`.nombre`/`.apellido` (ya declaradas en
    `core/data/build.gradle.kts`). Con `EnfermedadesApiRemota` y el `pacientes/resolver` de la 002 sobre un paciente
    "PRUEBA SALUD360" con DNI desde 99000090: administrar; crear "Prueba Salud360 <n>" con la ficha → 201, en la lista
    y marcada; repetir con otro espaciado/mayúsculas/tildes → 200, `reutilizada` y `enfermedad.nombre` el del catálogo;
    desmarcar y marcar dos veces (idempotente); quitar → sigue en "Otras"; agregar → vuelve marcada; crear sin
    paciente → no marca. KDoc con el comando (`--rerun`, `-P` sin valores) y el SQL de limpieza del plan, sin
    credenciales.
  - Hecho cuando: sin propiedades se saltea y `:core:data:jvmTest` sigue en verde; con
    `.\gradlew :core:data:jvmTest --tests '*HcClinicaEnfermedadesE2ETest*' --rerun` y las `-P` de la copia local
    (token del médico de prueba sacado de `auth/login` de turnos local) en verde; al terminar, `SELECT COUNT(*)` de
    `enfermedades` con `nombre LIKE 'Prueba Salud360%'`, de sus filas en `medico_enfermedades` y
    `paciente_enfermedades`, y de `pacientes`/`medico_pacientes` del DNI de prueba da 0; los conteos por `activo` de
    las tres tablas son los de antes de empezar; `salud360_token_cache` vaciada; ni el test ni el commit tienen tokens.

Decidido (2026-10-21, coordinador):
- E1 `claveDeNombre` se alinea con el servidor (solo `[ \t\n\r\x0B\x0C]`) aunque cambie algún caso raro de perfiles con
  espacios Unicode al borde: es lo deseado. Si algún test de perfiles cambia, se anota y se ajusta el test con un
  comentario que lo explique (reemplaza el "no se ajusta el test" de F2.1).
- E2 `ConfigHcRepositoryTest` se actualiza a la versión de esquema 8 en F2.7.
- E3 Aviso de crear con un `sin_marcar_por` desconocido: "Se agregó a tu lista; no se marcó al paciente.";
  `paciente_no_encontrado` usa el motivo "no vinculado" de RF-58.
- E4 Modelo propio `ResultadoCrear` en F2.5, que F2.6 convierte desde el DTO.
- E5 El DTO de crear incluye `creada`; 201 y 200 se distinguen por `creada`.
- E6 El borrado de la copia al cerrar sesión se prueba con una función `internal` de `AuthRepository`.
- E7 Hasta F3.4 un perfil con "Enfermedades" muestra "Sección no disponible": aceptado (sin push).
- E8 El caso de UTF-8 inválido del PHP no se copia a Kotlin; los surrogates sueltos no están en la lista de
  invisibles: aceptado.
- E9 `ColumnasEnfermedadesTest` se mantiene aunque repita `SiNoVacioTest`.

Al terminar la tanda: validación del reviewer y MEMORY.md.
