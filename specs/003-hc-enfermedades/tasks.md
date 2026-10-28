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

- [x] **F2.6. DTOs y `EnfermedadesApi`.** RF-50, RF-51, RF-52, RF-53, RF-54
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

- [x] **F2.7. Migración `7.sqm`, copia en el dispositivo y borrado al cambiar de sesión.** RF-11, RF-30, RF-31,
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
  - Hecho (2026-10-22). Quedó así: `EnfermedadesRepository(db, api, nombreHc, espera, scope)` con un `pedir…` por
    endpoint (devuelve el `Deferred`), `esperar(pedido)` (`Ok` / `Error` / `SinRespuesta`) y `abrirSeccion` →
    `CargaSeccion` (`Actual`, `SoloLectura`, `SinCopia`, `Motivo`); `AuthRepository.borrarCopiaEnfermedadesAlEntrar`
    (`internal`). Sin `pacientesHc` en el constructor: el vínculo es del controlador (F2.8). Los mutadores de SQLDelight
    son `suspend` (`generateAsync`). Para que `ConfigHcRepositoryTest` (base v6) siga migrando, su armado también borra
    `hc_enfermedades` (la `7.sqm` la crea); `7.sqm` no lleva `IF NOT EXISTS` para ser igual al `.sq`.

- [x] **F2.8. Controlador de la sección y `DataModule`.** RF-8, RF-15, RF-21 a RF-28, RF-40, RF-41, RF-44, RF-45,
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
  - Hecho (2026-10-22). Quedó así: `EnfermedadesControlador(repo, pacientesHc, scope, medicoId, pacienteId, editable: () ->
    Boolean)` con dos `StateFlow` (`estado: SeccionEnfermedadesUi` y `administrar: AdministrarEnfermedadesUi`, que además
    lleva lo `buscado`, RF-46) y `puedeAdministrar()`. Las funciones no bloquean (lanzan en el `scope`). Detalles que la
    tarea no fijaba: (a) la `espera` no es un parámetro del controlador: la única es la del repositorio
    (`esperar`), así no hay dos tiempos; (b) `pacientesHc` es del controlador, no del repositorio (como dejó F2.7); (c) el
    repositorio suma `nombreHc` público y `cargaSinFicha(motivo)` (vínculo sin resolver: motivo, o RF-31 si es sin conexión,
    porque sin ficha no hay copia); (d) una respuesta de un cambio de la lista con `seccion` pasa por `confirmado(secuencia,
    enfermedadId, estado)` con una secuencia reservada con `pedirEstado()`; (e) un rechazo al pedir el estado que no es de
    lugar 5 (p. ej. `fuera_de_cartera`) muestra ese motivo en lugar de las casillas, y uno de lugar 5 es "sin respuesta"
    (RF-28); (f) una respuesta tardía del estado se aplica pero sigue en solo lectura. Rojo previo: el test no compilaba
    (`Unresolved reference 'EnfermedadesControlador'`, y `alCerrarSesion`/`cerrarSesion` de E12); E12 además se probó con una
    sonda temporal sobre el código de F2.7 (esperaba 0 filas en `hc_enfermedades`, había 1) y, ya implementado, anulando
    la guardia a propósito (el test de E12 falla con "esperaba 0, había 2"); la sonda y la anulación no quedaron.
    Límite conocido: una confirmación de un cambio de la lista que llega descartada (más vieja que lo aplicado) deja la
    casilla de esa enfermedad con lo que dijo esa respuesta (misma regla que `confirmado` para los toques), que solo
    discreparía de la web si se tocó esa misma casilla en medio; no debería darse con la ventana de administrar abierta (es
    modal). Si se quisiera cerrar del todo, haría falta una transición propia en `MarcadoEnfermedades` (no se agregó).

- [x] **F2.9. "Enfermedades" en el catálogo de la app.** RF-1, RF-2, RF-3, RF-56, RF-57
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
  - Hecho (2026-10-22): rojo previo en `ids_y_nombres_por_defecto_...` y en el caso nuevo
    `enfermedades_es_custom_segunda_y_no_es_diagnostico_ni_legada`; luego los tres comandos en verde (`:core:data:jvmTest`
    con `--rerun`, porque `core:data` no depende de `clinica`). Ícono `"diagnostico"` (el de la sección que reemplaza; no hay
    ícono de enfermedades en `Iconos.kt` y no se agregó uno). Ningún test de otro módulo cambió de resultado.

- [x] **F2.10. E2E contra la copia local de hclinica.** RF-15, RF-41, RF-44, RF-51, RF-53, RF-54
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
  - Hecho (2026-10-23). Sin propiedades se saltea (imprime que no se corre) y `:core:data:jvmTest` completo queda en
    verde; con las `-P` de la copia local (DNI 99000090, libre antes) corre de verdad (~8 s) y pasa. El 201 y el 200 de
    `POST enfermedades` se prueban con un `HttpClient` de la JVM (`HcApiClient` trata los dos como éxito y no devuelve el
    estado) y el cuerpo se lee con `EnfermedadesApiRemota.crearDe`; el resto va por `EnfermedadesApiRemota` y
    `PacientesHcApiRemota.resolver`. Los asserts sobre listas son `assertTrue` con mensaje fijo, para que una falla no
    imprima enfermedades de la base. Mutación puntual (201 → 200) falla con "expected 200 but was 201"; ya revertida.
    Limpieza con el SQL del KDoc tras cada corrida: conteos por `activo` de las tres tablas, `pacientes` y
    `medico_pacientes` iguales a los de antes, prefijo y DNI de prueba en 0, `salud360_token_cache` vacía.

- [x] **F2.11. Ajustes tras la validación del reviewer de la tanda F2.** RF-28, RF-34, RF-37, RF-45, RF-58
  - La tanda F2 quedó **aprobada con observaciones** (2026-10-23); el usuario aprobó cerrar estos puntos antes de la F3.
    No hay pantallas, ni ícono, ni renderer; la decisión de "motivos 1 a 4 detrás del diálogo" y la de "Reintentar con
    motivos 6 a 9" se toman en las tareas de F3.
  1. `CopiaEnfermedadesTest.una_respuesta_lenta_es_sin_conexion_pero_igual_actualiza_la_copia`: sin márgenes de reloj
     (antes `espera` 100 ms y demora 300 ms). La API falsa retiene la respuesta en una compuerta que el test abre a mano;
     la carga inicial de la copia va con la `espera` larga. Sigue probando que a la `espera` es sin conexión y que la
     respuesta tardía igual actualiza la copia.
  2. `EnfermedadesRepository.cerrarSesion()` borra toda la copia **y** sube la generación dentro del mismo `withLock` (el
     borrado primero, la generación después, para que un pedido que leyó la generación vieja y espera el candado no
     escriba tras el borrado); `AuthRepository.logout` ya no borra `hc_enfermedades` por su cuenta (lo hace
     `alCerrarSesion`; lo demás sigue igual). Test: el contrato de `cerrarSesion` (rojo antes: no borraba) y que un
     pedido que salió antes y contesta después no escribe. `logout_borra_toda_la_copia` pasa a armar el `AuthRepository`
     con `alCerrarSesion` (como `DataModule`), porque el borrado ya no vive en `logout`.
  3. `EnfermedadesControlador`: `abrirAdministrar`, `agregar`, `quitar` y `crear` salen sin hacer nada si no se puede
     administrar (RF-30, RF-37: sin conexión, solo lectura o motivos 1 a 5; `abrirAdministrar` también con la consulta no
     editable). Los tres cambios de la lista no miran `editable`: RF-58 pide que "Crear" funcione con la consulta que se
     cerró con la ventana abierta (y `crear_con_la_consulta_no_editable_no_manda_la_ficha` lo prueba). Nuevo
     `cerrarAdministrar()` (limpia `buscado`, `aviso` y el `estado` viejo; una respuesta que llega después de cerrar no
     los vuelve a escribir). Doble `abrirAdministrar` con un pedido en vuelo y doble `elegirFicha` seguidos: el segundo no
     hace nada. Tests nuevos en `EnfermedadesControladorTest`.
  4. `MarcadoEnfermedades.kt`: se borra `ESPERA_MARCADO` (duplicaba a `EnfermedadesRepository.ESPERA` y no se usaba).
  5. `spec.md`: se reflejan las dos desviaciones aceptadas, con fecha y "ajuste tras validación F2": RF-58 / E10 (el aviso
     de «ya en la lista» con el motivo) y RF-28 (un rechazo al pedir el estado es "sin respuesta" si es de lugar 5 y un
     motivo en cualquier otro caso). Sin tocar otros RF.
  6. Tests: (a) `guardarSesion` → `borrarCopiaEnfermedadesAlEntrar` (`guardarSesion` pasa a `internal`, sin pasar por
     `login` ni `restaurar`); (b) el test de migración compara también tipo, `notnull`, valor por defecto y clave
     primaria de `hc_enfermedades` (`PRAGMA table_info`) entre la base migrada 7 → 8 y la fresca.
  - Hecho cuando: `.\gradlew :core:data:jvmTest --rerun` en verde tres veces seguidas; `:core:ui:jvmTest` y
    `:especialidades:clinica:jvmTest` en verde; `:composeApp:compileKotlinWasmJs -Pentorno=dev` compila. Sin E2E.
  - Hecho (2026-10-23). Rojo previo (8 tests): la copia no se borraba en `cerrarSesion`, los cuatro métodos de administrar
    pedían igual sin conexión / con motivo / con la consulta no editable, el segundo `abrirAdministrar` y el segundo
    `elegirFicha` repetían el pedido y `cerrarAdministrar` (vacío) no limpiaba; `guardarSesion` se probó por mutación
    (sin su línea, el test falla). `:core:data:jvmTest --rerun` tres veces en verde (456 tests), `:core:ui:jvmTest`,
    `:especialidades:clinica:jvmTest` y `:composeApp:compileKotlinWasmJs -Pentorno=dev` en verde. Dos tests existentes
    se ajustaron: `logout_borra_toda_la_copia` (arma el `AuthRepository` con `alCerrarSesion`) y
    `una_respuesta_lenta_despues_del_logout_no_reescribe_la_copia` (abre administrar con la sección ya cargada, que es lo
    que exige RF-37; sigue probando lo mismo). Sobre el punto 2: con el borrado y la generación en un solo paso no queda
    un "medio" dentro del repositorio; no se pudo reproducir una intercalación concreta de forma determinista (la guardia
    es privada), por eso se cubre el contrato (`cerrarSesion` borra y lo que estaba en camino no reescribe).

- [x] **F2.12. Estabilizar los tests intermitentes de enfermedades.** (sin RF: solo tests)
  - Tres fallas intermitentes, siempre con la máquina cargada (Gradle compilando en paralelo): (1)
    `EnfermedadesControladorTest.un_cambio_de_la_lista_por_vez` ("No se cumplió a tiempo: terminado", nunca reproducida);
    (2) `CopiaEnfermedadesTest.una_respuesta_lenta_es_sin_conexion_pero_igual_actualiza_la_copia` (arreglada en F2.11); (3)
    `EnfermedadesControladorTest.abrir_hace_un_solo_pedido_y_reemplaza_la_copia` (falló una vez en `hasta("la sección
    abierta")` con la sección todavía `cargando`, `ultimaEnviada=1` y `llamadas: [seccion]`: la API falsa recibió el pedido
    y la sección no llegó a cargar).
  - Se busca la causa raíz de (1) y (3) y se hacen deterministas los tests de enfermedades que dependen del reloj
    (`EnfermedadesControladorTest`, `CopiaEnfermedadesTest`; `MarcadoEnfermedadesTest`, `PanelEnfermedadesTest` y el resto
    se revisan): la `espera` del repositorio es larga salvo en los tests que prueban justamente "a la espera es sin
    conexión", y esos retienen y liberan la respuesta con compuertas, no con `delay`; los `hasta` con tope holgado
    y sondeo corto. Sin tocar lo que prueba cada test ni el código de producción (si la causa fuera un bug real, se
    para y se avisa antes de cambiarlo).
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*EnfermedadesControladorTest*' --tests '*CopiaEnfermedadesTest*'
    --rerun` en verde 10 veces seguidas, al menos 3 con carga de CPU simultánea (procesos propios, que se matan al
    terminar); `.\gradlew :core:data:jvmTest --rerun` completo en verde tres veces seguidas; `:core:ui:jvmTest` y
    `:especialidades:clinica:jvmTest` en verde; `:composeApp:compileKotlinWasmJs -Pentorno=dev` compila. Sin E2E.
  - Hecho (2026-10-23). Solo tests (`EnfermedadesControladorTest`, `CopiaEnfermedadesTest`); producción sin tocar.
    **Diagnóstico.**
    - (3) y (1): no eran por una `espera` corta. En ambos tests la `espera` del repositorio era la de `conControlador`
      (5 s, no ~100 ms) y el tope de `hasta` también 5 s de reloj de pared. Para fallar, la API falsa tenía que haber
      recibido el pedido y la respuesta (que contesta sola, sin retener) no completarse en 5 s: la sección
      `cargando=true` con `llamadas: [seccion]` es esa firma. No hay carrera entre el `launch` del controlador y la
      compuerta (en (3) no hay compuertas); **no se reprodujeron** (19 corridas del código original con 4 giros de CPU,
      con CPU + escritura a disco con `flush`, y sin carga mía). Medido: un hilo vigía con 4 giros de CPU vio pausas de
      proceso de 114 ms como máximo, y la carga de disco no cambió los tiempos (los tests más lentos, ~2 s); o sea, la CPU
      y el disco "normales" no alcanzan. Lo que sí apareció, sin que yo cargara nada, fueron frenones de la máquina de
      varios segundos en tests de base sin ninguna espera: `guarda_administrar_y_seccion_...` 11,2 s (normal 1,4 s),
      `cerrar_sesion_borra_...` 7,8 s, `un_rechazo_conserva_...` 8,7 s (normal 0,4 s) y, con carga,
      `una_respuesta_lenta_despues_del_logout_...` 14,6 s (normal 0,8 s). Con un frenón así, 5 s (la `espera` y el `hasta`)
      se pasan con el código bien: es la causa más probable de (1) y (3), **pero no está demostrada** (nunca vi fallar
      esos dos). Además `hasta` tenía un defecto propio: si el proceso se frenaba justo al vencer el tope, salía sin
      mirar la condición una última vez.
    - Carreras reales **de los tests** (esas sí reproducidas): `agregar_y_quitar_...`,
      `crear_manda_el_nombre_...` y `una_respuesta_que_llega_despues_de_cerrar_administrar_...` esperaban el `StateFlow`
      de la ventana y asertaban sobre el de la sección, que `cambiarLista` actualiza un paso después (la ventana primero,
      `marcar { confirmado }` después). Una vez cayó sola `agregar_y_quitar_...` con carga (1 de 10 corridas; el mensaje no
      se guardó). Rojo determinista: un `delay(400)` temporal entre los dos pasos de `cambiarLista` hace fallar los tres
      ("expected Marcable(id=4 ...) but was Marcable(id=3 ...)", "expected 1 but was 0"); con los tests arreglados y el
      mismo retraso pasan 30/30; el retraso ya está revertido.
    - `sin_respuesta_y_el_estado_tambien_sin_respuesta_...` (la única `espera` de 100 ms): la copia de partida y la
      apertura del controlador también corrían con 100 ms, así que una apertura lenta lo dejaba en solo lectura desde el
      principio. Rojo con un retraso de 300 ms inyectado en los dos primeros pedidos de la API falsa ("No se cumplió a
      tiempo: solo lectura", `textoSinCopia=Hace falta conexión…`); con el test nuevo y el mismo retraso, verde.
    **Arreglo.** `ESPERA_LARGA` y `TOPE_HASTA` de 30 s en `EnfermedadesControladorTest` (y 30 s por defecto en
    `repo()` de `CopiaEnfermedadesTest`, antes 10 s); `hasta` sondea cada 5 ms y mira una última vez al vencer; se
    sacó el `espera = 5.seconds` de `un_cambio_de_la_lista_por_vez` y del test del logout; los tres tests con carrera
    esperan también a la sección (`marcado.guardado`); el de 100 ms crea su copia con `pedirSeccion(...).await()` (sin
    `esperar`) y abre con `abiertaConConexion()` (reintenta hasta abrir con conexión, tope 100); `seccion` y `lista` de la API
    falsa pasan a `@Volatile`. Lo que prueba cada test no cambió. `unRato()` (`delay(200)`) se deja: solo comprueba que
    algo NO pasó (el retraso solo puede dar un falso verde, nunca un falso rojo) y no hay forma determinista de probar una
    ausencia. `MarcadoEnfermedadesTest`, `PanelEnfermedadesTest` y el resto no usan reloj. Los dos tests que prueban "a la
    espera es sin conexión" (`CopiaEnfermedadesTest.una_respuesta_lenta_...` y el de 100 ms) retienen con compuerta.
    **Verificación.** Las dos clases con `--rerun`: 10 corridas seguidas en verde con el código final (5 sin carga mía y 5
    con 4 giros de CPU + 2 de escritura a disco, procesos propios, matados al terminar), más 5 en verde antes del último
    ajuste de `CopiaEnfermedadesTest`; `:core:data:jvmTest --rerun` completo 3 veces en verde (509 tests); `:core:ui:jvmTest`
    (17) y `:especialidades:clinica:jvmTest` (23) en verde; `:composeApp:compileKotlinWasmJs -Pentorno=dev` compila.
    Quedan sin demostrar la causa exacta de (1) y (3); si vuelven a fallar con 30 s, el mensaje de `hasta` ya trae las
    dos secciones y las llamadas, y entonces sí sería un bug real a investigar.

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
- E10 (decisión de F2.5, aceptada) Crear con una enfermedad ya en la lista y sin marcar al paciente: «X» ya estaba en
  tu lista. No se marcó al paciente porque …
- E11 (decisión de F2.5, aceptada) Crear una enfermedad nueva y marcada al paciente: sin aviso.
- E12 (2026-10-22, incluida por el usuario en F2.8; hueco que dejó F2.7) Una respuesta lenta que llega después del `logout`
  no reescribe la copia. Solución: `EnfermedadesRepository` tiene una "generación" (`MutableStateFlow<Long>`) que cada
  pedido recuerda al salir y un `Mutex`; un pedido solo toca la copia si sigue en su generación, comprobándolo y
  escribiendo dentro del `Mutex`; `cerrarSesion()` toma el `Mutex` y sube la generación, y `AuthRepository.logout` la llama
  antes de borrar (parámetro nuevo `alCerrarSesion`, con valor por defecto; `DataModule` le pasa
  `EnfermedadesRepository::cerrarSesion`). Por qué esto y no cancelar el scope: el repositorio es un singleton y su scope
  tiene que servir a la sesión siguiente (cancelarlo lo inutilizaría; `cancelChildren` no frena una escritura ya empezada
  ni a un pedido de la sesión nueva que salga justo después). Por qué no comprobar la sesión leyendo `sesion` de la base:
  comprobar y escribir serían dos pasos separados (y con el driver web asíncrono otro `logout` puede colarse en el medio),
  mientras que con el `Mutex` es "o la escritura terminó antes de `cerrarSesion`, y el borrado de `logout` va después, o
  ve la generación nueva y no escribe". Los pedidos que ya salieron igual terminan y el controlador los muestra
  (la pantalla ya no está en pantalla al cerrar sesión); solo se evita tocar el disco. No se cubre el cambio de médico sin
  `logout` (no hay camino en la app); `guardarSesion` sigue con su `borrarDeOtros`. Test:
  `EnfermedadesControladorTest.una_respuesta_lenta_despues_del_logout_no_reescribe_la_copia`.

Al terminar la tanda: validación del reviewer y MEMORY.md.

Tanda F2 validada por el reviewer el 2026-10-23: **APROBADA CON OBSERVACIONES**; las observaciones se cerraron en F2.11
(commit `35d0529`). Lo que dejó "Para la tanda F3" está incorporado en la tanda de abajo.

## Tanda F3 — Pantallas  ✔ aprobada por el usuario el 2026-10-23

Propuesta redactada el 2026-10-23; las decisiones de diseño quedaron cerradas ese mismo día (sección "Decididas" del
final) y el usuario aprobó las tareas ese día. Sección "App → Pantallas" del plan. Numeración
del plan (F3.1 a F3.8); F3.4 se parte en F3.4a/b/c para que cada tarea entre en 20-30 min y la lógica de "qué se ve"
quede en `core/data` con test (constitución, principio 4). Son 10 tareas.

Reglas de la F3, además de las de arriba y las de la F2:
- **Pantallas finitas, decisiones en `core/data`**: no hay tests de Compose en el repo (ni `compose.uiTest` ni fuente de
  test en `features/hc`; agregarlos sería una dependencia nueva: no). Una pantalla se verifica con (1) toda decisión de
  "qué se ve" movida a una función pura con test en `commonTest` (`.\gradlew :core:data:jvmTest`), (2) compilación
  (`.\gradlew :features:hc:compileKotlinJvm` y `.\gradlew :composeApp:compileKotlinWasmJs -Pentorno=dev`) y (3) la
  checklist manual del usuario de F3.7. Un test de pantalla "con Compose" no es un criterio de "Hecho cuando".
- **Tests primero** para lo puro (se ve en rojo, después en verde), como en F2. Al terminar cada tarea, además del comando
  de la tarea: `.\gradlew :core:data:jvmTest :core:ui:jvmTest :especialidades:clinica:jvmTest` en verde y
  `:composeApp:compileKotlinWasmJs -Pentorno=dev` compila.
- Los **textos de la spec** van como constantes en `core/data` (como `TEXTO_SIN_MARCABLES`), no como literales en la
  pantalla. Los textos que la spec no fija (rótulos de la ventana, "Elegir", "Cargando…": lista final en "Decididas", P6)
  van como literales en el composable que los usa, exactamente como están escritos ahí.
- **Leer siempre con `collectAsState`**: el renderer no usa `ctx.valor` ni `ctx.setValor` (bug de Child-Pugh en MEMORY) y
  nunca llama al controlador desde el autoguardado.
- Una tarea por vez, un commit por tarea (`hc: …`, `ui: …`, `datos: …`) con `git commit -F <archivo>` (archivo de mensaje
  sin BOM), como `rodri89`, sin push. Sin tokens ni datos reales en tests, docs ni mensajes. Todo `devWeb` y toda
  compilación web contra el entorno dev (`-Pentorno=dev`); no matar el `devWeb` del usuario.
- Cualquier camino que la tarea encuentre y que contradiga la spec o el plan: parar y preguntar (principio 1).

- [x] **F3.1. `CasillaElegible` (core/ui).** RF-20, RF-21 (marca "Guardando…"), RF-22
  - `core/ui/src/commonMain/kotlin/com/salud360/core/ui/components/CasillaElegible.kt` (nuevo), al lado de
    `OpcionElegible.kt`: `CasillaElegible(nombre: String, marcada: Boolean, onCambio: (Boolean) -> Unit, modifier:
    Modifier = Modifier, habilitada: Boolean = true, marca: String? = null, colores: ColoresSiNo =
    coloresSiNo(LocalIsDarkTheme.current))`. `toggleable(value = marcada, enabled = habilitada, role = Role.Checkbox,
    interactionSource, indication = null, onValueChange = onCambio)` (el lector anuncia el nombre y "marcada"/"sin
    marcar"; tabulador y espacio la tocan). Alto y ancho mínimos de 48 dp. Mismo aspecto y **mismos `ColoresSiNo`** que
    `OpcionElegible` (marcada = relleno + tilde, no solo color; foco de 2 dp por fuera): no se agregan colores. El nombre
    se ve completo (sin `maxLines`), en las líneas que haga falta. `marca` ("Guardando…") va debajo del nombre y es parte
    del mismo nodo semántico. `habilitada = false` no atenúa los colores (el estado elegido tiene que seguir leyéndose
    mientras el pedido está en camino); solo ignora toques. Filas parejas: quien la usa le da `weight(1f)` y
    `fillMaxHeight()` dentro de un `Row` con `IntrinsicSize.Min`, como la tabla de `ChildPughSeccion`.
  - Sin tests nuevos: no tiene lógica y no suma colores, así que `ColoresSiNoTest` (contraste) sigue siendo el que vale.
    El texto "Guardando…" llega por parámetro (core/ui no depende de core/data). El tamaño táctil, el foco y el lector se
    verifican en F3.7 (tanda M5).
  - Hecho cuando: `.\gradlew :core:ui:jvmTest` en verde; `.\gradlew :core:ui:compileKotlinJvm` y
    `.\gradlew :composeApp:compileKotlinWasmJs -Pentorno=dev` compilan; `git diff --stat` muestra solo el archivo nuevo;
    buscar `Role.RadioButton`, `selectable(` y `Color(0x` en el archivo no da resultados; `OpcionElegible.kt` sin cambios.

- [x] **F3.2. "Elegí la ficha del paciente" (`ElegirFichaPaciente`).** RF-9
  - `core/data/.../hcgeneral/FichaCandidata.kt` (nuevo, puro): `lineaDeCandidata(c: HcPacienteCandidato)` →
    `LineaCandidata(nombre, documento, nacimiento: String?)`: nombre = `"<nombre> <apellido>"` recortado, documento = `dni`,
    nacimiento = `dd/MM/aaaa` desde el `yyyy-MM-dd` de la web; sin fecha o ilegible → `null` (no se inventa texto; la tarjeta no muestra esa línea).
    No se muestra `cantidad_consultas` (la spec pide nombre, apellido, documento y fecha de nacimiento).
  - `FichaCandidataTest` (nuevo, commonTest): formato de fecha, sin fecha, fecha ilegible, nombre o apellido vacío.
  - `features/hc/src/commonMain/.../secciones/ElegirFichaPaciente.kt` (nuevo, reutilizable por la 002, sin nada de
    enfermedades): `ElegirFichaPaciente(titulo: String, candidatas: List<HcPacienteCandidato>, habilitado: Boolean,
    onElegir: (Long) -> Unit)`. El título es `MotivoEnfermedades.ElegirFicha.texto` ("Elegí la ficha del paciente"), pasado
    por parámetro. Una tarjeta por candidata (con `lineaDeCandidata`) y un botón **"Elegir"** de al menos 48 dp,
    deshabilitado con `habilitado = false` (la sección lo apaga mientras `cargando`).
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*FichaCandidataTest*'` en verde (rojo antes);
    `:core:data:jvmTest` completo en verde; `.\gradlew :features:hc:compileKotlinJvm` y
    `:composeApp:compileKotlinWasmJs -Pentorno=dev` compilan; buscar `Enfermedad` en `ElegirFichaPaciente.kt` no da
    resultados (reutilizable).
  - Hecho (2026-10-23). Rojo previo: el test no compilaba solo por `Unresolved reference 'lineaDeCandidata'`; luego
    `FichaCandidataTest` (7 casos) y `:core:data:jvmTest` (463 tests) en verde, `:core:ui:jvmTest`,
    `:especialidades:clinica:jvmTest`, `:features:hc:compileKotlinJvm` y `:composeApp:compileKotlinWasmJs -Pentorno=dev` OK.
    Detalles que la tarea no fijaba: el nombre pasa por `nombreNormalizado` (recorta y junta espacios; con nombre o
    apellido vacío queda solo el otro); la fecha solo vale como `yyyy-MM-dd` de fecha real (`1990-1-2`, `1990-02-30` o
    con hora → sin línea); la tarjeta rotula con "DNI" y "Fecha de nacimiento" (los de `DatosPacienteSeccion`, no son
    textos nuevos); el botón "Elegir" es un `OutlinedButton` con el estilo de `AcceptButton` pero de 48 dp. El lector de
    pantalla dirá solo "Elegir" en cada botón (sin la ficha); si en F3.7-M5 molesta, se le agrega una descripción.

- [x] **F3.3. Acceso por sesión, `EnfermedadesViewModel` y `HcModule`.** RF-4, RF-8, RF-15, RF-35
  - **Corrige al plan** (ver "Discrepancias"): el `medicoId` de la sección es `PerfilMedico.medicoId` **de la sesión**, no
    el que le llega a `ConsultaScreen` (en `MainShell.kt:281` es el médico que eligió la secretaria, o `usuario.id` del
    administrador); por eso `ConsultaViewModel.medicoId` **no** pasa a `val` pública.
  - `core/data/.../hcgeneral/AccesoEnfermedades.kt` (nuevo, puro): `medicoParaEnfermedades(s: Sesion): Id?` (solo si
    `usuario.rol == MEDICO` y `medico != null` → `medico.medicoId`; administrador y secretaria → `null`, aunque tengan un
    médico elegido, RF-4); `consultaEditable(soloLectura: Boolean, medicoDeLaConsulta: Id, medicoPropio: Id?): Boolean`
    (abierta y propia, RF-35); `visibleParaSesion(idSeccion: String, medicoPropio: Id?): Boolean` (`enfermedades` solo con
    médico propio; el resto, siempre).
  - `AccesoEnfermedadesTest` (nuevo, commonTest): médico → su `medicoId`; secretaria (con y sin `medico` cargado) y
    administrador → `null`; editable: abierta propia sí, cerrada no, de otro médico no, sin médico propio no;
    `visibleParaSesion` con y sin médico y con otra sección.
  - `features/hc/.../EnfermedadesViewModel.kt` (nuevo): `EnfermedadesViewModel(repo: EnfermedadesRepository, pacientesHc:
    PacientesHcRepository, auth: AuthRepository, pacienteId: Id)` (de Koin y de `parametersOf(pacienteId)`). `val medicoId:
    Id? = medicoParaEnfermedades(sesión actual)`; `val controlador: EnfermedadesControlador?` (`null` si `medicoId` es
    `null`; si no, con `viewModelScope`, `medicoId` y `editable = { editable }`); `@Volatile var editable: Boolean`
    (no observable: la pantalla lo actualiza con `SideEffect`; el controlador lo lee en cada uso); `var guardadoVisto:
    Int` (último contador de "Guardado" ya mostrado, para que cambiar de pestaña no lo repita). Nada de lógica propia.
  - `HcModule.kt`: `viewModel { (pacienteId: Id) -> EnfermedadesViewModel(get(), get(), get(), pacienteId) }`; la pantalla
    lo pide con `koinViewModel(key = "enfermedades-$consultaId") { parametersOf(pacienteId) }` (así "Datos" y la sección
    comparten controlador y un solo pedido).
  - `ConsultaViewModel`: recibe `AuthRepository` (Koin `get()`; ajustar la línea de `HcModule`) y, en `pestanias(ui)`, el
    predicado de `layoutConsulta` suma `visibleParaSesion(id, medicoPropio)`: una secretaria o un administrador no ven la
    pestaña ni la sección (RF-4), en lugar de una pestaña vacía.
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*AccesoEnfermedadesTest*'` en verde (rojo antes); `:core:data:jvmTest`
    completo en verde; `:features:hc:compileKotlinJvm` y `:composeApp:compileKotlinWasmJs -Pentorno=dev` compilan;
    `ConsultaViewModel.medicoId` sigue `private`; buscar `ctx.valor`, `setValor` y `HcApiSync` en `EnfermedadesViewModel.kt`
    no da resultados; la app arranca (Koin resuelve el ViewModel nuevo: se mira en la primera apertura de F3.7).
  - Hecho (2026-10-23). Rojo previo: `AccesoEnfermedadesTest` no compilaba solo por `Unresolved reference` de las tres
    funciones nuevas; luego sus 11 casos y `:core:data:jvmTest --rerun` (474 tests), `:core:ui:jvmTest`,
    `:especialidades:clinica:jvmTest`, `:features:hc:compileKotlinJvm` y `:composeApp:compileKotlinWasmJs -Pentorno=dev` OK.
    El código real coincide con el texto de la tarea (`MainShell.kt:241/281`). Detalles que la tarea no fijaba: (a)
    `EnfermedadesViewModel.editable` arranca en `false` (solo lectura hasta que la pantalla lo diga con `SideEffect`); (b) el
    filtro de `visibleParaSesion` va solo en `pestanias(ui)` (solo la HC general tiene `grupos`) y no en la rama sin grupos de
    `seccionesVisibles`: `DesarrolloInfantil` tiene una sección propia con id `enfermedades` que no es esta; (c) `ConsultaViewModel`
    recibe `AuthRepository` y no lo usa para otra cosa; (d) el `medicoId` del controlador sale de `auth.sesion.value` al crear el
    ViewModel (una sesión no cambia con la consulta abierta); el scope es `viewModelScope`, sin scope propio.

- [x] **F3.4a. Panel de la sección (lógica pura).** RF-9 a RF-14, RF-18, RF-19, RF-21, RF-24, RF-28 a RF-31, RF-35, RF-37
  - `core/data/.../repos/PanelEnfermedades.kt` (nuevo, junto a `SeccionEnfermedadesUi`, para no hacer que `hcgeneral`
    importe `repos`): constantes de la spec `TEXTO_GUARDANDO` ("Guardando…", RF-21), `TEXTO_GUARDADO` ("Guardado",
    RF-24), `TEXTO_REINTENTAR` ("Reintentar", RF-30) y `TEXTO_ADMINISTRAR` ("Administrar enfermedades", RF-18/RF-37);
    `sealed interface PanelEnfermedades` y `panelDeSeccion(ui: SeccionEnfermedadesUi, editable: Boolean): PanelEnfermedades`
    (firma exacta a definir en la tarea) con estos casos, en el orden de RF-14:
    1. `Cargando`: `ui.cargando` y todavía no hay vista, motivo ni texto sin copia.
    2. `ElegirFicha(candidatas, habilitado = !ui.cargando)`: el motivo mostrado es `ElegirFicha` (RF-9), con `administrar`.
    3. `Motivo(texto, reintentar, administrar)`: cualquier otro motivo (RF-10 a RF-13, también el rechazo de un toque con
       motivo, RF-27); `administrar = puedeAdministrar(editable, true, ui.conConexion, motivo)`, o sea sí con 7 a 9 y no
       con 1 a 4 (RF-37). Con los motivos 1 a 4 la sección de atrás no cambia aunque la ventana de administrar rechace por
       uno de ellos (P1-A): el panel solo muestra el motivo cuando lo trae `ui`, como hoy. `reintentar = !ui.cargando`
       solo con los motivos 7, 8 y 9 (sin ficha, ficha no disponible, fuera de la cartera) y también con el 5 si llegara
       acá; nunca con el 6 (ya tiene "Elegir") ni con 1 a 4 (P2-B). El texto del botón es `TEXTO_REINTENTAR`.
    4. `SinCopia(texto, reintentar)`: `textoSinCopia` (RF-31, "Hace falta conexión para ver las enfermedades" o el texto de
       la web); `reintentar = !ui.cargando` (P2-B); sin "Administrar".
    5. `Contenido(vista, aviso, avisoSoloLectura, reintentar, administrar, guardado)`: `vista = vistaSeccion(ui.vista,
       editable)` (grilla con "Otras" o "Estado actual del paciente", RF-18, RF-19, RF-35); `aviso` = el último rechazo
       (RF-29, solo los `Rechazo`: los `Motivo` ya son el caso 3); `avisoSoloLectura` y `reintentar` (= hay aviso de solo
       lectura y `!ui.cargando`, RF-30); `administrar` por `puedeAdministrar`; `guardado = ui.marcado.guardado` (RF-24).
       Con solo lectura por falta de conexión (RF-28, RF-30) y consulta editable se sigue viendo la grilla con el último
       estado confirmado, con las casillas **no tocables** (`marcado.puedeTocar` ya lo da), el aviso "Sin conexión: las
       enfermedades se pueden ver pero no cambiar" y "Reintentar"; no pasa a la vista "Estado actual" (P8-A).
  - `PanelEnfermedadesTest` (nuevo, commonTest; arma `SeccionEnfermedadesUi` a mano, sin coroutines): cada caso de arriba;
    prioridad (un aviso con motivo 2 y un motivo 6 muestran el 2); editable con y sin conexión; cerrada con copia y aviso
    → "Estado actual" + "Reintentar" + sin "Administrar"; consulta editable sin conexión con copia → `Contenido` con la
    grilla, `puedeTocar` falso en todas, el aviso de sin conexión y `reintentar` (P8-A); `reintentar` por motivo (sí con
    7, 8 y 9 y en `SinCopia`; no con 1 a 4 ni con 6, P2-B); `reintentar` apagado mientras `cargando`; `administrar` por
    motivo (1 a 9), cerrada y sin conexión (RF-37); lista de marcables vacía → `sinMarcables` con "Otras" y "Administrar".
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*PanelEnfermedadesTest*'` en verde (rojo antes);
    `:core:data:jvmTest` completo en verde; el archivo no importa nada de `androidx` ni de Compose.
  - Hecho (2026-10-23). Rojo previo: el test no compilaba solo por `Unresolved reference` de `panelDeSeccion`,
    `PanelEnfermedades` y las cuatro constantes; luego `PanelEnfermedadesTest` (35 casos) y `:core:data:jvmTest --rerun`
    (509 tests), `:core:ui:jvmTest` (17), `:especialidades:clinica:jvmTest` (23) y `:composeApp:compileKotlinWasmJs
    -Pentorno=dev` en verde. Mutación puntual (reintentar con 1 y 6, grilla que pasa a "Estado actual" en solo lectura,
    "Reintentar" que ignora `cargando`) falla en 4 tests; ya revertida. `AVISO_SIN_CONEXION` y `TEXTO_HACE_FALTA_CONEXION`
    ya existían en `EnfermedadesRepository.kt` (mismo paquete `repos`); no se movieron. Detalles que la tarea no fijaba:
    (a) `ElegirFicha(candidatas, habilitado, administrar)` y `Motivo(texto, reintentar, administrar)`; `Contenido` y
    `SinCopia` como en el texto; (b) "Administrar" usa `ui.conConexion` (la misma regla del controlador), así que se apaga
    también mientras `cargando`; un test compara el panel contra `puedeAdministrar` con todas las combinaciones; (c) un
    estado sin vista, motivo ni texto y sin `cargando` (no debería darse) cae en `Cargando`; (d) las casillas no tocables
    en solo lectura las da `ui.marcado.puedeTocar` (el panel no las repite; el test lo comprueba). Una corrida de
    `EnfermedadesControladorTest.abrir_hace_un_solo_pedido_y_reemplaza_la_copia` falló una vez ("No se cumplió a tiempo: la
    sección abierta", `cargando=true`, llamadas `[seccion]`, bajo la carga de la compilación) y pasó en la corrida
    siguiente: es el flake de la `espera` corta ya anotado en `MEMORY.md`, sin relación con esta tarea.

- [x] **F3.4b. Pantalla de la sección (`EnfermedadesSeccion`).** RF-5, RF-9, RF-14 a RF-24, RF-28 a RF-31, RF-35
  - `features/hc/.../secciones/EnfermedadesSeccion.kt` (nuevo): `@Composable fun EnfermedadesSeccion(s: SeccionDef, ctx:
    SeccionContext)`. (1) `koinViewModel<EnfermedadesViewModel>(key = "enfermedades-${ctx.consultaId}") {
    parametersOf(ctx.pacienteId) }`; si `controlador == null` no dibuja nada (RF-4). (2) `editable =
    consultaEditable(ctx.soloLectura, consulta.medicoId, vm.medicoId)` con la consulta de `ctx.vm.ui.collectAsState()`, y
    `SideEffect { vm.editable = editable }`. (3) `LaunchedEffect(Unit) { controlador.abrir() }` (idempotente). (4)
    `val ui by controlador.estado.collectAsState()` y `when (val p = panelDeSeccion(ui, editable))`: solo dibuja y delega
    (`tocar`, `elegirFicha`, `reintentar`); `puedeAdministrar` se calcula acá, en la misma composición que `ui` y
    `editable`, nunca con `controlador.puedeAdministrar()` leído fuera de ella (`editable` no es observable).
  - Dibujo: `SectionCard(s.titulo, icon = iconoSeccion(s.icono ?: "enfermedades"), initiallyExpanded =
    s.inicialmenteExpandida, trailing = …)` con **"Guardado"** junto al título 3 s: `LaunchedEffect(p.guardado) { if
    (p.guardado > vm.guardadoVisto) { vm.guardadoVisto = p.guardado; visible = true; delay(DURACION_GUARDADO); visible =
    false } }` (una nueva confirmación reinicia los 3 s; cambiar de pestaña no lo repite). Grilla con `BoxWithConstraints`
    y `columnasPara(maxWidth.value)`, `chunked(columnas)`, `Row(IntrinsicSize.Min)` y la última fila completada con
    `Spacer(weight(1f))` (mismo ancho, RF-17); cada casilla con `CasillaElegible(m.nombre, m.tiene, { controlador.tocar(m.id,
    it) }, habilitada = ui.marcado.puedeTocar(m.id), marca = TEXTO_GUARDANDO.takeIf { ui.marcado.guardando(m.id) })`. Aviso
    único (RF-29) en color de error con `liveRegion = Polite`. "Otras" (RF-19) y "Estado actual del paciente" (RF-35) como
    texto, sin casillas. Motivos, sin copia y solo lectura con sus textos; el botón **"Reintentar"**
    (`controlador.reintentar()`) se dibuja cuando `p.reintentar` (solo lectura con copia, "Hace falta conexión…" y motivos
    7, 8 y 9; no con el 6 ni con 1 a 4). `Cargando` muestra el texto **"Cargando…"**.
    `ElegirFichaPaciente` (F3.2) para RF-9. Botón "Administrar enfermedades" (RF-37) con una lambda `onAdministrar` que
    cablea F3.5; hasta F3.5 no hace nada (sin push, aceptado como E7).
  - Sin tests nuevos: toda la decisión está en `panelDeSeccion` (F3.4a). Se verifica compilando y en F3.7 (tandas M2 a M7).
  - Hecho cuando: `:features:hc:compileKotlinJvm` y `:composeApp:compileKotlinWasmJs -Pentorno=dev` compilan;
    `:core:data:jvmTest` completo en verde; buscar `ctx.valor`, `setValor`, `LazyColumn` y literales de los textos de la
    spec (p. ej. `"Guardando"`, `"Sin conexión"`, `"Estado actual"`) en el archivo no da resultados.
  - Hecho (2026-10-23). `:features:hc:compileKotlinJvm`, `:composeApp:compileKotlinWasmJs -Pentorno=dev` y
    `:core:data:jvmTest --rerun` (509 tests), `:core:ui:jvmTest` y `:especialidades:clinica:jvmTest` en verde; las búsquedas
    del "Hecho cuando" no dan resultados; sin lógica nueva (no hay rojo previo) y sin tocar core ni el registro:
    `hcGeneralContribution.renderers` sigue `{child_pugh}` (F3.4c). Detalles que la tarea no fijaba: (a) `onAdministrar` es
    una lambda vacía local, que F3.5 reemplaza; (b) "Reintentar" y "Administrar enfermedades" son `AcceptButton` (40 dp, como
    el resto de la app; "Elegir" de F3.2 es de 48 dp) en un `ActionRow`; (c) "Guardado" va en `trailing` de `SectionCard`, con
    la clave `panel.guardado` (0 si el panel no es `Contenido`) y un `finally` que lo oculta solo si ninguna confirmación más
    nueva tomó el relevo; (d) el aviso de solo lectura (`avisoSoloLectura`) también es `liveRegion = Polite`, en color de
    texto normal (el de rechazo va en color de error); (e) el título de "Elegí la ficha del paciente" sale de
    `MotivoEnfermedades.ElegirFicha(candidatas).texto`, que no tiene constante; (f) si `ui.consulta` fuera null, `editable`
    es falso; (g) "Otras" y "Estado actual del paciente" son líneas de texto con un título con semántica de encabezado. El
    ícono es el de reserva (`Description`) hasta F3.4c, porque la clave `"enfermedades"` aún no existe en `iconoSeccion`.

- [x] **F3.4c. Registro del renderer, ícono propio y catálogo.** RF-1, RF-2, RF-5, RF-56, RF-57
  - `especialidades/clinica/.../HcGeneral.kt`: `hcGeneralContribution.renderers` pasa de `{child_pugh}` a `{child_pugh,
    enfermedades}` (`"enfermedades" to { s, ctx -> EnfermedadesSeccion(s, ctx) }`); la sección del catálogo pasa a
    `icono = "enfermedades"`; se actualiza el comentario "sin renderer" de las líneas 63-64.
  - `features/hc/.../Iconos.kt` (no está en `core/ui`): clave nueva `"enfermedades" -> Icons.Default.<ícono Material>`
    distinta de `"diagnostico"` (el ojo de la sección legada) y de las demás. El ícono es `Icons.Default.HealthAndSafety`
    (decidido, P4-A; ya compila en `MainShell`, así que está en el classpath de wasm). Sin dependencias nuevas.
  - `CatalogoHcGeneralTest`: `hcGeneralContribution.renderers.keys == setOf("child_pugh", "enfermedades")` (hoy l. 341) y
    `hepatologiaContribution` sigue `{child_pugh}` (l. 333); caso nuevo: `iconoSeccion(def.icono)` de `enfermedades` es
    distinto de `iconoSeccion("diagnostico")` y distinto del ícono de reserva (`iconoSeccion("clave_inexistente")`). El test
    del ícono va acá porque `features/hc` no tiene fuente de test y agregarla es una dependencia de build.
  - Hecho cuando: `.\gradlew :especialidades:clinica:jvmTest` en verde (rojo antes por los renderers y el ícono);
    `:core:data:jvmTest` y `:core:ui:jvmTest` en verde; `:composeApp:compileKotlinWasmJs -Pentorno=dev` compila; buscar
    `icono = "diagnostico"` en `HcGeneral.kt` no da resultados; `clinica_medica_sigue_igual` y `hepatologia_sigue_igual`
    siguen en verde sin tocarlos (RF-57).
  - Hecho (2026-10-23). Rojo previo: `:especialidades:clinica:jvmTest` con 3 fallos (renderers `{child_pugh}` en vez de
    `{child_pugh, enfermedades}`, `enfermedades` sin renderer registrado e ícono `"diagnostico"` en vez del propio); luego
    `:especialidades:clinica:jvmTest` (25 tests: `CatalogoHcGeneralTest` 19, `ChildPughTest` 6), `:core:data:jvmTest --rerun`
    (509), `:core:ui:jvmTest` (17), `:features:hc:compileKotlinJvm` y `:composeApp:compileKotlinWasmJs -Pentorno=dev` en verde.
    `HealthAndSafety` ya estaba en el classpath (sin dependencias nuevas, `*.kts` y `libs.versions.toml` intactos);
    `clinica_medica_sigue_igual`, `hepatologia_sigue_igual` y los perfiles iniciales sin tocar. El único camino al cartel
    "Sección no disponible" es `ConsultaScreen.kt:337` (renderer ausente en el registro): ya no se da para `enfermedades`
    porque `hcGeneralContribution` está en `AppDi`. Detalles que la tarea no fijaba: (a) se agregó un caso
    `cada_renderKey_del_catalogo_tiene_su_renderer_registrado` (toda `renderKey` del catálogo tiene su renderer: protege el
    cartel para cualquier sección CUSTOM); (b) el test del ícono compara además con el de `"form"` y con todas las demás
    claves conocidas de `iconoSeccion`; (c) el caso `la_contribucion_registra_la_hc_general_…` cambió de nombre al sumar
    `enfermedades`; (d) "Administrar enfermedades" sigue sin abrir nada (F3.5).

- [x] **F3.5. Ventana "Administrar enfermedades".** RF-37 a RF-47, RF-58 (avisos), RF-43
  - `core/data/.../repos/PanelAdministrar.kt` (nuevo, puro): `panelDeAdministrar(ui: AdministrarEnfermedadesUi):
    PanelAdministrar` con `Cargando` (`ui.cargando` y sin estado), `SoloAviso(texto)` (sin estado y con `aviso`: falló al
    abrir, con el texto que ya arma el controlador, también con los motivos 1 a 5: solo la ventana muestra el motivo,
    RF-46, y la sección de atrás queda como estaba; el controlador no cambia, P1-A) y `Contenido(lista, catalogo,
    textoCatalogo, crear: CrearVisible?, aviso, accionesHabilitadas = !ui.enCamino)`; `CrearVisible(nombre, error?)`
    sale de `ui.oferta` (RF-42): con `error` (RF-43) no hay botón, el texto del error se ve en rojo apenas se escribe
    (P5-A); sin `error`, "Crear «nombre»" habilitado si `!enCamino` (RF-45). `PanelAdministrarTest` (nuevo, commonTest):
    cargando, aviso sin estado, buscador vacío con "Escribí para buscar en el catálogo" y lista entera, duplicados del catálogo visibles, oferta con y
    sin error (vacío, 256 caracteres, invisible), todo deshabilitado con un cambio en camino, y que no hay ningún caso de
    renombrar, dar de baja ni eliminar (RF-47).
  - `features/hc/.../secciones/AdministrarEnfermedadesDialog.kt` (nuevo): `AdministrarEnfermedadesDialog(controlador:
    EnfermedadesControlador, onCerrar: () -> Unit)`, **modal** (F2, hallazgo 6): `AlertDialog` como `VerTodoDialog` de
    `CampoConHistorial.kt` (`DialogProperties(usePlatformDefaultWidth = false)`, 90 % del alto y del ancho); al mostrarse,
    `LaunchedEffect(Unit) { controlador.abrirAdministrar() }` (un pedido); `onDismissRequest` y "Cerrar" llaman a
    `controlador.cerrarAdministrar()` y a `onCerrar()`. Textos (P6): título de la ventana **"Administrar enfermedades"**;
    etiqueta del buscador **"Buscar o crear una enfermedad"** (`controlador.buscar`, sin pedidos, RF-39); lista **"Tu lista"**
    con **"Quitar"** y **"Del catálogo"** con **"Agregar"** (deshabilitados con `enCamino`, RF-45); **"Crear «…»"** o el
    error de RF-43 (P5-A); botón **"Cerrar"**; mientras carga, **"Cargando…"**; una lista que no coincide con lo escrito
    no dice nada. El aviso (RF-25 a RF-28, RF-44, RF-46, RF-58) con `liveRegion = Polite`. Lo escrito se conserva ante un
    rechazo (lo hace el controlador) y tras crear: el buscador no se limpia (P3-A). La ventana no tiene "Reintentar".
    Desplazamiento propio en la ventana y en cada lista (RF-38), sin paginar: el alto de cada lista va **acotado** (`LazyColumn(Modifier.heightIn(max = …))`); una lista con alto infinito dentro de un
    contenedor con scroll vertical hace caer la composición.
  - `EnfermedadesSeccion.kt` (F3.4b): `onAdministrar` abre la ventana (`var abierta by rememberSaveable`) y se dibuja
    `if (abierta) AdministrarEnfermedadesDialog(…)`. Hay que revisar que ningún camino cambie `puedeAdministrar` con la
    ventana abierta (es modal, no se tocan casillas): si aparece uno, parar y preguntar.
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*PanelAdministrarTest*'` en verde (rojo antes);
    `:core:data:jvmTest` completo en verde; `:features:hc:compileKotlinJvm` y `:composeApp:compileKotlinWasmJs
    -Pentorno=dev` compilan; buscar `Renombrar`, `Eliminar`, `Dar de baja` y `delete` en el diálogo no da resultados
    (RF-47); el botón de F3.4b ya abre la ventana.
  - Hecho (2026-10-23). Rojo previo: `PanelAdministrarTest` no compilaba solo por `Unresolved reference` de
    `PanelAdministrar` y `panelDeAdministrar`; luego sus 27 casos y `:core:data:jvmTest --rerun` (536 tests),
    `:core:ui:jvmTest`, `:especialidades:clinica:jvmTest`, `:features:hc:compileKotlinJvm` y `:composeApp:compileKotlinWasmJs
    -Pentorno=dev` en verde; las búsquedas del "Hecho cuando" no dan resultados; sin dependencias nuevas. Mutación puntual
    (`accionesHabilitadas` siempre `true`, sin el error de "vacío") falla en 2 tests; ya revertida. Detalles que la tarea no
    fijaba: (a) **"vacío" de RF-43**: `ofrecerCrear` devuelve `null` con el nombre normalizado vacío (RF-42, F2.5), así que
    por ahí nunca sale "El nombre está vacío"; para cumplir P5-A y F3.7-M3(4) el panel arma `CrearVisible("", error)` cuando
    lo escrito no está vacío pero el nombre normalizado sí (solo espacios); con el buscador sin tocar no hay error;
    `ofrecerCrear` y el controlador no cambian. (b) Sin estado, sin pedido y sin aviso (el instante antes de que el
    controlador marque el pedido, o si no pudo pedir) el panel es `Cargando`, nunca una ventana vacía; con `cargando` y un
    aviso viejo sin estado, también `Cargando`. (c) El aviso de `Contenido` va en color normal (el controlador no distingue
    un rechazo de un "ya existía…"); el de `SoloAviso` y el error de nombre, en rojo; todos `liveRegion = Polite`. (d) Las
    listas son `LazyColumn(heightIn(max = 240.dp))` (el catálogo puede traer miles con una letra escrita) dentro de la
    `Column` con scroll de la ventana: el alto está acotado, no hay alto infinito. (e) Cada fila lleva
    `semantics(mergeDescendants = true)` para que el lector diga "Asma, Quitar" y no un "Quitar" suelto; el teclado no
    cambia. (f) Botones de 48 dp como "Elegir" (F3.2); el de "Crear «…»" ocupa el ancho y crece si el nombre es largo (hasta
    255 caracteres). (g) La ventana se controla solo con `administrarAbierto` (`rememberSaveable`) y no con `puedeAdministrar`,
    así que sigue abierta si la consulta se cierra (RF-58). **Camino que cambia `puedeAdministrar` con la ventana abierta**
    (la tarea pedía parar y preguntar): una casilla tocada justo antes de abrir la ventana puede quedar sin respuesta y pasar la
    sección a solo lectura (RF-28) con la ventana abierta; entonces `agregar`/`quitar`/`crear` del controlador salen sin hacer
    nada (`puedeCambiarLista`) y los botones siguen habilitados. No rompe nada ni es nuevo (el controlador es de F2.11). **Decidido
    el 2026-10-23: la ventana se cierra sola en ese caso** (ver "Ajuste 2026-10-23" abajo). (h) No hay foco inicial en el buscador ni
    `onDispose` que llame a `cerrarAdministrar()` si la pantalla sale con la ventana abierta (el estado viejo se refresca al
    reabrir).
  - **Ajuste 2026-10-23 (decisión del usuario).** Sin tarea nueva. (1) Si con la ventana abierta la sección deja de poder
    administrar por falta de conexión o por un motivo de los lugares 1 a 5 de RF-14 (`puedeAdministrar` pasa a falso por
    conexión o motivo), la ventana **se cierra sola**, por el mismo camino que "Cerrar" (`controlador.cerrarAdministrar()` y
    `onCerrar()`). No hay texto nuevo: la sección de atrás ya muestra su aviso ("Sin conexión: las enfermedades se pueden ver
    pero no cambiar" o el motivo). Cerrar la consulta con la ventana abierta (cambia solo `editable`) **no** la cierra: es el
    caso RF-58 y "Crear" sigue funcionando, sin mandar la ficha. (2) El "vacío" de RF-43 queda como está (error armado en
    `panelDeAdministrar`; `ofrecerCrear` y el controlador no cambian). Implementación: `debeCerrarAdministrar(ui:
    SeccionEnfermedadesUi): Boolean` en `repos/PanelAdministrar.kt` (pura) = `!puedeAdministrar(editable = true, propia =
    true, conConexion = ui.cargando || ui.conConexion, motivo = ui.motivoMostrado)`, la misma pregunta que hace
    `puedeCambiarLista` del controlador; el diálogo la observa con `controlador.estado` y un `LaunchedEffect(hayQueCerrar)`
    que llama a `cerrar` (el mismo lambda de "Cerrar"). **Mientras la sección carga no cierra:** `conConexion` es falso por
    definición con `cargando`, pero cargando todavía no es "sin conexión" (no se sabe), así que se supone con conexión; un
    motivo 1 a 5 ya conocido sí cierra aunque cargue. Hoy no se da con la ventana recién abierta (el botón solo existe con la
    sección cargada y la ventana es modal), así que no se cierra al abrir; el "Cargando…" de la ventana es el de su propio
    pedido (`PanelAdministrar.Cargando`) y no entra en la regla. Tests: `DebeCerrarAdministrarTest` (nuevo, 11 casos: sin
    conexión, sin copia, cada motivo 1 a 5 cierra y por rechazo de un toque, 6 a 9 no, consulta cerrada no, todo normal no,
    cargando no, y equivalencia con `puedeAdministrar`). Rojo previo: no compilaba por `Unresolved reference:
    debeCerrarAdministrar`. Verde: `:core:data:jvmTest --rerun` (547 tests), `:core:ui:jvmTest`,
    `:especialidades:clinica:jvmTest`, `:features:hc:compileKotlinJvm` y `:composeApp:compileKotlinWasmJs -Pentorno=dev`.
    Alcance real: el estado de la sección solo cambia con la ventana abierta por el camino de (g) (un toque sin respuesta
    que pasa a solo lectura); un cambio de la lista que falla sin conexión no toca ese estado (`cambioRechazado` solo escribe
    el aviso de la ventana), así que ahí la ventana sigue abierta con su aviso. Tocados: `PanelAdministrar.kt`, `AdministrarEnfermedadesDialog.kt`, comentario de `EnfermedadesSeccion.kt`, el test nuevo,
    este archivo y `MEMORY.md`. Queda como duda abierta (solo Android): si el sistema recrea el proceso con la ventana abierta
    (`administrarAbierto` es `rememberSaveable`), la sección nueva arranca cargando, la ventana no cierra y su
    `abrirAdministrar()` no hace nada (aún no puede administrar); se queda en "Cargando…" hasta tocar "Cerrar". (Resuelta en F3.9: la ventana usa `remember`.)

- [x] **F3.6. "Datos": enfermedades del paciente cuando la sección no se muestra.** RF-4, RF-6, RF-7, RF-57
  - **Trampa del plan** (ver "Discrepancias"): `enfermedadesEnDatos(def, ocultas)` da `true` para **cualquier**
    especialidad cuyo perfil no tenga la sección (pediatría incluida); hay que limitarla a la HC general y al médico.
  - `core/data/.../hcgeneral/AccesoEnfermedades.kt`: `enfermedadesEnDatosDeConsulta(def, ocultas, porPerfil: Boolean,
    medicoPropio: Id?): Boolean = porPerfil && medicoPropio != null && enfermedadesEnDatos(def, ocultas)`, más la
    constante del título `TITULO_ENFERMEDADES_EN_DATOS = "Enfermedades"` (el nombre por defecto, spec 001 RF-34).
    `AccesoEnfermedadesTest`: pediatría (`porPerfil = false`) no aunque su definición no tenga la sección; secretaria o
    administrador no; sin perfil sí; sección fuera del perfil para todos los tipos sí; oculta sí; solo en "Consulta"
    (telemedicina) no (RF-6); en el perfil y visible no (nunca a la vez que la sección, RF-7).
  - `ConsultaViewModel`: `fun enfermedadesEnDatos(ui: ConsultaUi): Boolean` (usa `ui.resuelta?.porPerfil`, su `ocultas(ui)`
    privada y el médico de la sesión de F3.3).
  - `features/hc/.../secciones/EnfermedadesEnDatos.kt` (nuevo): bloque de solo lectura con el mismo ViewModel
    (`key = "enfermedades-$consultaId"`): `abrir()` (idempotente) y `enfermedadesDelPaciente(ui.vista)` en un `SectionCard`
    titulado `TITULO_ENFERMEDADES_EN_DATOS`, **una sola vez**, en orden alfabético; sin motivos ni "Elegí la ficha" (PD6): con
    cualquier motivo, sin copia, cargando o sin enfermedades no dibuja nada. Funciona con la consulta cerrada.
    Como dice la spec (RF-7, RF-8; decidido, P7-A): `abrir()` resuelve el vínculo y **puede crear la ficha** del paciente
    en la web de clínica, también para médicos que nunca usaron "Enfermedades"; no hay modo "solo con vínculo guardado" ni
    cambio sobre RF-8.
  - `ConsultaScreen.kt`: el bloque va debajo de `DatosPacienteSeccion` en **los dos** lugares donde se dibuja "Datos":
    l. 196-198 (pestañas agrupadas) y l. 216-219 (la pestaña "Datos" sola, que es lo único que ve el médico **sin perfil**,
    RF-6), antes de `SoloLecturaFueraDelPerfil`, solo si `vm.enfermedadesEnDatos(ui)`.
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*AccesoEnfermedadesTest*'` en verde (rojo antes);
    `:core:data:jvmTest` completo en verde; `:features:hc:compileKotlinJvm` y `:composeApp:compileKotlinWasmJs
    -Pentorno=dev` compilan; buscar `EnfermedadesEnDatos` en `ConsultaScreen.kt` da exactamente dos usos; pediatría y las
    demás especialidades no pasan por el bloque (lo confirma el test de `porPerfil` y F3.7, tanda M7).
  - Hecho (2026-10-23). Rojo previo: `AccesoEnfermedadesTest` no compilaba solo por `Unresolved reference` de
    `enfermedadesEnDatosDeConsulta` y `TITULO_ENFERMEDADES_EN_DATOS`; luego sus 20 casos (9 nuevos) y `:core:data:jvmTest
    --rerun` (556 tests), `:core:ui:jvmTest` (17), `:especialidades:clinica:jvmTest` (25), `:features:hc:compileKotlinJvm` y
    `:composeApp:compileKotlinWasmJs -Pentorno=dev` en verde. Mutación puntual (quitar `porPerfil` y `medicoPropio` de la
    función) falla en `pediatria_y_las_hc_sin_perfil_...` y `la_secretaria_y_el_administrador_...`; ya revertida. Buscar
    `EnfermedadesEnDatos` en `ConsultaScreen.kt` da dos usos (más el `import`); buscar `ctx.valor`, `setValor`, `HcApiSync`,
    `seccion_valor` y `paciente_extra` en `EnfermedadesEnDatos.kt` no da resultados; `*.kts` y `libs.versions.toml` intactos.
    El código real coincide con el texto de la tarea (`ConsultaScreen.kt` l. 196-198 y 216-219; `enfermedadesEnDatos` da `true`
    para una definición sin grupos con la sección, como dice la trampa). Cómo se cubren los **dos lugares**: (1) pestañas
    agrupadas (médico con perfil, pestaña "Datos"): el bloque va entre `DatosPacienteSeccion` y `SoloLecturaFueraDelPerfil`;
    (2) pestaña "Datos" sola (médico **sin perfil**, sin configuración la primera vez, o un tipo sin pestañas como "HC
    digitalizada"): el `if` que solo tenía `SoloLecturaFueraDelPerfil` pasa a un bloque con el mismo `if
    (vm.enfermedadesEnDatos(ui)) EnfermedadesEnDatos(ctx)` antes de él. Los dos son excluyentes (`agrupadas.isNotEmpty()` o no), así
    que el bloque sale una sola vez (RF-7); y la sección y "Datos" no coinciden nunca (`enfermedadesEnDatos` es falso si la sección
    está en el perfil para algún tipo y no está oculta). Detalles que la tarea no fijaba: (a) la decisión de "qué se ve" que no
    estaba en `core/data` es solo "con `vista` null (motivo, sin copia o cargando sin copia) o sin enfermedades no dibuja": sale
    de `ui.vista` y `enfermedadesDelPaciente`, que ya tenían test, y no se agregó otra función; (b) con una copia en solo
    lectura (sin conexión) el bloque sí se ve: es la copia de RF-7/RF-32; (c) `SectionCard(collapsible = false)` con el ícono
    de la sección (`iconoSeccion("enfermedades")`) y un `Text` por enfermedad, sin casillas ni botones; (d) la secretaria y el
    administrador no tienen `controlador` (`vm.controlador == null`), así que aunque se llamara no pediría nada; el filtro por
    `medicoPropio` ya los saca antes. Sin `/sdd-change` ni texto nuevo (el título es el nombre por defecto de la spec 001 RF-34).

- [x] **F3.7. Prueba manual completa en `devWeb` y de punta a punta app ↔ web (la hace el usuario, en tandas cortas).** — **cerrada con excepciones aceptadas por el usuario; NO verificada completa, y con la limpieza de datos de prueba pendiente (ver la nota al final de esta tarea)**
  RF-1 a RF-7, RF-9 a RF-12, RF-15 a RF-24, RF-28, RF-30, RF-31, RF-35, RF-37 a RF-47, RF-56, RF-57, RF-58, RF-59
  - **El agente prepara, el usuario mira.** Preparación (agente): (a) compilar con `.\gradlew
    :composeApp:wasmJsDevelopmentExecutableCompileSync -Pentorno=dev` y confirmar que el `devWeb` del usuario sirve el
    build nuevo (no se mata; el usuario recarga); (b) datos de prueba por SQL puntual en las copias locales, con el
    prefijo "Prueba Salud360 …" (2-3 enfermedades) y pacientes "PRUEBA SALUD360 A/B/C/D/E" con documentos desde 99000091
    verificados libres con `SELECT COUNT(*)`: A (una ficha, en la cartera del médico de prueba), B (dos fichas con el mismo
    documento), C (ficha de otro médico, fuera de la cartera), D (ficha dada de baja, `activo = 0`), E (documento no válido
    para la historia clínica); (c) un perfil de prueba nuevo (copia del del médico de prueba, nunca los perfiles 1 y 2 de la
    carga inicial) al que el usuario agrega "Enfermedades"; (d) los ids creados anotados en un archivo fuera del repo para
    la limpieza; (e) las contraseñas se las pide al usuario en el momento y no se guardan. No se muestran filas de
    pacientes ni de enfermedades reales.
  - **Checklist para el usuario** (cada punto: qué hacer → qué debería verse → RF). Se hace una tanda, se contesta "ok" o
    qué falló, y se sigue con la siguiente; lo que falle se arregla antes de seguir.
    - **M1. Administrador (5 min).** (1) Administración → Perfiles HC → perfil de prueba → agregar sección: aparece
      "Enfermedades" (descripción "Casillas con las enfermedades del paciente, de la lista de cada médico.") como segunda del
      catálogo; ponerle un nombre propio, una pestaña y tipo Consulta; guardar → queda en el perfil (RF-1, RF-2). (2) Como
      administrador, abrir un paciente y su historia: no hay sección ni pestaña de enfermedades (RF-4). (3) Si hay una
      secretaria de prueba: elegir al médico, abrir una consulta suya: no aparece la sección (RF-4; si no hay secretaria,
      queda cubierto solo por `AccesoEnfermedadesTest` y se anota).
    - **M2. La sección en la consulta (5 min).** (1) Como médico, paciente A → HC → nueva consulta "Consulta": en la pestaña
      del perfil está la tarjeta con el nombre propio; con la lista vacía dice "No tenés enfermedades en tu listado.
      Podés agregarlas con «Administrar enfermedades»." y muestra el botón (RF-5, RF-15, RF-18). (2) Nueva consulta
      "Telemedicina" (si la sección no está marcada para ese tipo): no aparece (RF-5). (3) Ícono de la sección: es el
      nuevo (cruz con escudo, `HealthAndSafety`), no el ojo de "Diagnósticos" (RF-56).
    - **M3. Administrar la lista (8 min).** (1) "Administrar enfermedades": se abre una ventana que no deja tocar lo de atrás,
      con buscador, la lista y "Del catálogo" que dice "Escribí para buscar en el catálogo" (RF-38, RF-39). (2) Escribir
      parte de "Prueba Salud360 1": aparece en "Del catálogo" con "Agregar"; Agregar → pasa a la lista; cerrar → la grilla
      tiene su casilla, desmarcada (RF-40). (3) Escribir un nombre nuevo → ofrece "Crear «…»" con espacios juntados;
      Crear → se suma a la lista y queda **marcada** en la grilla; el buscador conserva lo escrito (RF-42, RF-44, P3-A).
      (4) Escribir solo espacios → no ofrece crear y muestra "El nombre está vacío" en rojo, sin botón "Crear" (P5-A,
      RF-43). (5) "Quitar" una marcada → desaparece de la lista; al cerrar está en "Otras enfermedades del paciente" y no
      se desmarcó (RF-41). (6) No hay renombrar, dar de baja ni eliminar
      (RF-47). (7) Con DevTools → Network → "Slow 3G": mientras un cambio está en camino, "Agregar", "Quitar" y "Crear"
      están deshabilitados (RF-45). (8) Sin conexión con el diálogo abierto → se cierra solo (ajuste de F3.5, 2026-10-23): con
      "Slow 3G" o Offline, tocar una casilla y enseguida "Administrar enfermedades"; cuando el toque no se confirma
      (RF-28) la sección pasa a "Sin conexión" y la ventana se cierra sola, con el aviso "Sin conexión: las enfermedades se
      pueden ver pero no cambiar" atrás. Ojo: poner Offline con la ventana ya abierta y tocar "Quitar" o "Agregar" **no** la
      cierra (el controlador solo muestra "No se pudo confirmar…" dentro de la ventana y no toca el estado de la sección).
      (9) Solo Android (F3.9): con la ventana abierta, rotar el teléfono → la ventana se cierra sola, la sección sigue como
      estaba (sin quedarse en "Cargando…"); "Administrar enfermedades" de nuevo → abre con la lista actual, sin lo escrito antes y
      con un solo pedido (en el log de Apache, una sola línea de `/enfermedades` de administrar por apertura). Si no hay
      Android a mano se anota "no verificado".
    - **M4. Marcar y desmarcar (8 min).** (1) Tocar una casilla: queda marcada, un instante "Guardando…" y "Guardado" junto
      al título unos 3 s (RF-21, RF-24). Cambiar de pestaña y volver no repite "Guardado". (2) Recargar la página (F5) y
      reabrir la consulta: sigue marcada (RF-15, copia). (3) Abrir la web de clínica local en el mismo paciente: la tiene
      marcada; marcar otra en la web, volver a la app, reabrir la sección: aparece (RF-21, RF-48; D4). (4) Con "Slow 3G",
      tocar dos casillas seguidas: las dos dicen "Guardando…", la tocada no se puede volver a tocar y la otra sí (RF-22,
      RF-23).
    - **M5. Aspecto, teclado y lector (8 min).** (1) Angostar la ventana (modo dispositivo de DevTools): menos de 600 de
      ancho de la sección → 2 casillas por fila; más → 4; nombres largos completos y casillas de la fila del mismo ancho
      (RF-17). (2) Tabulador recorre las casillas con el foco visible y la barra espaciadora marca y desmarca (RF-20).
      (3) Lector de pantalla (Narrador/NVDA) sobre una casilla: dice el nombre y si está marcada. **Riesgo**: puede que
      Compose para web no exponga ese árbol; si no se oye nada, se anota "no verificable en web" y queda para probarlo en
      Android con TalkBack (RF-20). (4) El área táctil de 48 × 48 se mira en Android/emulador o por tamaño relativo.
      (5) Ventana angosta con los dos botones (F3.9): con el paciente C de M6 (muestra "Reintentar" y "Administrar enfermedades"),
      angostar a menos de 400 de ancho → los botones pasan a dos renglones, o crecen en alto, y el texto se lee completo, sin
      recortarse; en ancho normal quedan juntos a la derecha como antes. (6) Lector sobre "Elegir" (F3.9): con el paciente B de M6,
      el lector dice el nombre y el DNI de la ficha junto con "Elegir" en una sola parada, y el botón sigue activándose con teclado
      (Tab y Enter) y con toque; si en web no se oye nada, "no verificable en web" y se prueba con TalkBack.
    - **M6. Motivos y sin conexión (10 min).** (1) Paciente B: "Elegí la ficha del paciente" con nombre, apellido,
      documento y fecha de nacimiento de cada una, sin casillas, y "Administrar enfermedades" disponible; elegir una →
      carga la sección (RF-9, RF-37); aquí **no** hay "Reintentar" (P2-B). (2) Paciente E: el texto de la 002 que dice qué
      hacer, sin casillas y con "Reintentar" (RF-10, P2-B). (3) Paciente C: "Este paciente no está en tu cartera", sin
      enfermedades, con "Administrar enfermedades" y "Reintentar" (RF-11, RF-37, P2-B). (4) Paciente D: "La ficha de este
      paciente no está disponible en la web", con "Reintentar" (RF-12, P2-B). (5) Con los motivos 1 a 4 (si se pueden
      provocar, p. ej. sesión vencida) no hay "Reintentar" ni "Administrar" (P2-B, RF-37); si la ventana de administrar
      rechaza por uno de ellos, solo la ventana muestra el motivo y la sección de atrás queda igual (P1-A, RF-46). (6) Abrir
      A con conexión; DevTools → Network → Offline; salir y volver a entrar: la grilla con lo último confirmado, las
      casillas deshabilitadas (no se pueden tocar), el aviso "Sin conexión: las enfermedades se pueden ver pero no
      cambiar", sin "Administrar enfermedades" y con "Reintentar" (RF-30, P8-A); volver a Online y "Reintentar" → vuelve a
      editarse. (7) Un paciente que nunca se abrió, ya en Offline: "Hace falta conexión para ver las enfermedades" con
      "Reintentar" (RF-31, P2-B); volver a Online y "Reintentar" → carga la sección.
      (8) En Online, abrir A, poner Offline y tocar una casilla: pide el estado, falla y muestra "No se pudo confirmar el cambio en «…»: puede haberse guardado. Volvé a abrir la sección con
      conexión." y queda en solo lectura (RF-28). (9) Sin conexión con el diálogo abierto → se cierra solo: lo de M3 (8); con
      la ventana cerrada por esa vía, la sección muestra el aviso de solo lectura y no ofrece "Administrar enfermedades".
    - **M7. Cerrada, "Datos" y lo que no debe cambiar (8 min).** (1) Cerrar la consulta de A y volver a abrirla en lectura:
      "Estado actual del paciente" con las marcadas y las "Otras" en orden, sin casillas ni "Administrar"; con un paciente
      sin ninguna: "Sin enfermedades registradas" (RF-35). (2) Quitar "Enfermedades" del perfil de prueba (o ocultarla en
      Configuración): en "Datos" aparece el bloque "Enfermedades" con las del paciente, una sola vez, y no hay sección;
      un paciente sin enfermedades: no aparece nada (RF-6, RF-7). (3) Con la sección solo en "Consulta": en Telemedicina ni
      sección ni "Datos" (RF-6). (4) La sección legada "Diagnósticos / enfermedades" se ve y funciona igual (RF-56). (5)
      Abrir una consulta de pediatría: "Datos" no muestra enfermedades y el agente confirma en el log de Apache (solo
      conteo de líneas) que no hubo pedidos a `/enfermedades` desde ahí (RF-57). (6) Abrir "Datos" de un paciente nuevo en
      un perfil sin la sección crea su ficha en la web de clínica (efecto conocido y aceptado, RF-8, P7-A): el agente lo
      mira y lo anota.
    - **M8. Cerrar sesión (5 min).** (1) El agente anota antes los conteos de `salud360_token_cache` (hc_clinica, y
      hc_pediatrica si el entorno la usa) y de `salud360_tokens` (turnos). (2) El usuario entra como médico, abre una
      consulta con la sección y cierra sesión: la app vuelve al ingreso al instante. (3) El agente vuelve a contar: cada
      tabla bajó en una fila (solo conteos) (RF-59). (4) Repetir con DevTools en Offline al cerrar: la sesión se cierra
      igual (RF-59).
  - Limpieza (agente, al terminar o si se corta): el SQL del KDoc de `HcClinicaEnfermedadesE2ETest` más los ids anotados
    (pacientes de prueba, `medico_pacientes`, enfermedades "Prueba Salud360%" y sus filas en `medico_enfermedades` y
    `paciente_enfermedades`, el perfil de prueba y las marcas restauradas por `UPDATE` puntual); `salud360_token_cache`
    vaciada; nunca `DROP TABLE`.
  - Hecho cuando: el usuario contestó "ok" (o dejó anotado lo que no se pudo verificar) en cada una de M1 a M8; las fallas
    quedaron arregladas y re-probadas; después de la limpieza, `SELECT COUNT(*)` de `enfermedades` con el prefijo, de sus
    filas en `medico_enfermedades` y `paciente_enfermedades`, de `pacientes`/`medico_pacientes` de los documentos de
    prueba y del perfil de prueba da 0, y los conteos por `activo` de las tres tablas son los de antes de empezar;
    `git status` sin cambios en datos (esta tarea no cambia código salvo los arreglos que salgan, cada uno en su commit).
  - **Cerrada por decisión del usuario el 2026-10-28** ("marcá enfermedades como terminada"). Es una **cerrada con
    excepciones aceptadas por el usuario**: el "Hecho cuando" de arriba (un "ok" en cada una de M1 a M8 y la limpieza con
    conteos iguales a los iniciales) **no se cumplió**. La marca `[x]` no significa que la prueba manual se haya hecho entera.
    - **Confirmado por el usuario** (lo único): M2 puntos 1 y 2 (la sección se ve en Consulta y no en Telemedicina, con la
      lista vacía y con una enfermedad marcada); M3 agregar, quitar y marcar (mirado por el usuario en el log de Apache);
      M7 "consulta cerrada" y «Datos» (puntos 1 y 2). La pregunta del ícono (M2 punto 3) quedó **resuelta por el usuario**
      ("ya está resuelto"); no hay registro de qué se miró.
    - **SIN verificar a mano:**
      - M1 (3): la secretaria. RF-4 queda cubierto solo por `AccesoEnfermedadesTest`.
      - M3, punto "Crear": el log local de Apache muestra 6 altas a la lista y 9 marcados y **ningún** `POST enfermedades`,
        así que RF-42, RF-43 y RF-44 no tienen prueba manual. Tampoco los puntos 8 (sin conexión con la ventana abierta) y 9
        (rotar en Android).
      - M4 punto 1 ("Guardando…" / "Guardado" unos 3 s), punto 3 (contra la web de clínica, en los dos sentidos) y punto 4
        ("Slow 3G").
      - M5 completo: aspecto (columnas), teclado, lector de pantalla y ventana angosta (incluidos los puntos de F3.9).
      - M6 completo: los motivos de los pacientes B a F y el caso sin conexión. No se corrió `preparar-M6.sql`.
      - M7: todo lo que no sea la consulta cerrada y «Datos» (puntos 3 a 6: sección solo en Consulta, "Diagnósticos /
        enfermedades", pediatría sin pedidos a `/enfermedades` y la ficha que crea «Datos» en un paciente nuevo).
      - M8: el cierre de sesión con el conteo de tokens antes y después (RF-59, en la copia local).
      - Sin registro de confirmación ni de falla (no estaban en la decisión del usuario, así que **no se dan por
        verificados**): M1 puntos 1 y 2, M3 puntos 1 y 4 a 7, M4 punto 2.
    - **Lo que sí respalda la spec sin esa prueba manual:** los tests automáticos (servidor y lógica de la app), el E2E
      `HcClinicaEnfermedadesE2ETest` contra la copia local y la validación del reviewer de las tandas F1 a F3. No
      reemplaza la prueba manual de lo anterior.
    - **LIMPIEZA DE DATOS DE PRUEBA PENDIENTE (deuda explícita, no un olvido).** La base local `hc_clinica` **no volvió al
      estado inicial**: quedan las enfermedades "Prueba Salud360 1/2/3"; la lista de enfermedades del médico de prueba quedó
      vaciada por `UPDATE` (6 filas inactivas); el paciente de prueba A y otros pacientes tienen marcas; hay pacientes
      "PRUEBA SALUD360 A" a "F" (y sus filas de `medico_pacientes`) y el perfil de prueba 4; el médico de prueba quedó con
      el perfil 2 (el original es el 1, "Clínica general"); y los totales difieren de los iniciales. Los archivos están
      fuera del repo, en `%TEMP%\opencode\f37`: `estado-inicial.txt` (los conteos de antes), `ids-y-limpieza.txt` (los ids
      creados y la restauración), `lista-medico9-antes.txt` (la lista del médico de prueba antes del `UPDATE`) y
      `limpieza.sql` / `verificar-limpieza.sql`. La limpieza se hará **al final**. **ANTES de limpiar** hay que mirar en el
      log de Apache (`C:\xampp\apache\logs\access.log`) a qué pacientes y enfermedades apuntan los 9 `PUT
      enfermedades/pacientes/{n}/{n}` del 26 y 27 de octubre, para **restaurar esas marcas** (pueden ser de fichas de la
      base local que no son de prueba) con `UPDATE` puntuales, y recién entonces correr `limpieza.sql`. La licencia del
      médico de prueba sigue extendida a propósito (no restaurarla). Vaciar `salud360_token_cache` y nunca `DROP TABLE`.

- [x] **F3.8. `AuthRepository.logout` revoca el token en turnos y en cada HC con API.** RF-59 (C3, C4, PD7)
  - **C4 manda sobre el texto del plan** ("antes de olvidar el token, a lo sumo 3 s"): la revocación corre **en segundo
    plano** y no demora el cierre; la sesión local se cierra siempre y primero.
  - `core/data/.../repos/` (o `network/`): `fun interface RevocaSesion { suspend fun revocar(token: String) }` (C3: interfaz
    propia e inyectable, sin `ktor-client-mock` ni dependencias nuevas). Implementaciones: `TurnosOnlineClient.cerrarSesion(
    token)` (`POST auth/logout`) y `HcApiClient.salir(token)` (`POST auth/salir`, ya existe en hclinica y pediatría desde
    F1.7). Reciben el token **por parámetro**: cuando corren, `logout()` ya borró el de los clientes. Sin cambiar la firma de
    los métodos existentes. Un 404 de una API vieja y cualquier otro error se ignoran.
  - `AuthRepository`: parámetros nuevos `revocar: List<RevocaSesion> = emptyList()` (**por defecto vacío**: el
    `TurnosOnlineClient()` por defecto apunta a producción y un test no puede pegarle), `scopeRevocacion: CoroutineScope`
    (`SupervisorJob() + Dispatchers.Default`, como el del repositorio de enfermedades) y `limite: Duration = 3.seconds`.
    `logout()`: toma el token (`_sesion.value?.token ?: turnos.token`) **antes** de limpiar; hace lo de siempre
    (`alCerrarSesion()`, sesión local, tokens de los clientes); y recién entonces lanza en `scopeRevocacion` un
    `withTimeoutOrNull(limite)` que llama a todos en paralelo (`async` + `runCatching`, sin tragarse `CancellationException`
    del propio ámbito) y **no lo espera**. Sin token no hace nada. Los `logout()` internos (licencia vencida en `login`,
    `restaurar`, `refrescarPerfil`) también revocan; en `login` el token recién emitido todavía no está en los clientes, así
    que `logout` recibe el token a revocar de forma explícita (`logout(tokenARevocar)`).
  - `DataModule`: arma la lista con el cliente de turnos y cada `HcApiClient` de `HcApiClients` (hclinica y pediatría).
  - `AuthRepositoryLogoutTest` (nuevo, jvmTest, mismo armado de base y de `AuthRepository` que
    `CopiaEnfermedadesTest.logout_borra_toda_la_copia`, con revocadores falsos): todos reciben el token que tenía la sesión
    aunque los clientes ya lo hayan olvidado; uno que lanza no impide a los otros; uno que no contesta (compuerta que el
    test no abre) no demora a `logout()` y la sesión local ya está borrada cuando vuelve; pasado un `limite` corto
    inyectado se cancela (se observa con una compuerta que se completa en `finally`, sin dormir a ciegas); sin token no se
    llama a nadie; `logout(tokenARevocar)` revoca ese token; un `AuthRepository` armado sin revocadores sigue cerrando como
    antes (los tests existentes no cambian).
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*AuthRepositoryLogoutTest*'` en verde (rojo antes);
    `:core:data:jvmTest --rerun` completo en verde; `:composeApp:compileKotlinWasmJs -Pentorno=dev` compila; `git diff` de
    los `*.kts` y de `libs.versions.toml` vacío (sin dependencias nuevas); buscar `ktor-client-mock` no da resultados; el
    cierre real contra la copia local se mira en F3.7, tanda M8.
  - Hecho (2026-10-24). `repos/RevocaSesion.kt` (`fun interface`), `TurnosOnlineClient.cerrarSesion(token)` y
    `HcApiClient.salir(token)` (no existían: se crearon; mandan Bearer y `X-Salud360-Token` con el token del parámetro, no
    miran el estado de la respuesta y dejan propagar el fallo de red); `AuthRepository` con `revocar` (vacío por defecto),
    `scopeRevocacion` y `limite`; `logout(tokenARevocar)`; `DataModule` arma turnos + cada `HcApiClient`. El token se toma
    antes de `alCerrarSesion()` y la revocación se lanza al final, después de limpiar sesión y clientes: el camino de E12 y
    F2.11 no cambió (si `alCerrarSesion` falla, `logout` se corta antes de `borrarSesion` y no revoca). Token vacío = sin
    token. `login` (licencia vencida, en línea y sin conexión) pasa el token explícito; `restaurar` y `refrescarPerfil` usan
    el de la sesión o el del cliente de turnos. El `catch (Throwable)` de cada revocador solo corta si cancelaron el propio
    ámbito (`ensureActive`). Rojo previo (con el esqueleto de la interfaz y los parámetros sin lógica, para que compile): 8 de
    10 tests de `AuthRepositoryLogoutTest` fallan por lo que falta (los dos que pasan prueban lo que ya existía: cierre sin
    revocadores y sin token no llama a nadie). Mutación (hacer que `logout` espere la revocación): falla el de «no demora».
    Verdes: `AuthRepositoryLogoutTest` (10) y `RevocarSesionClientesTest` (3, servidor local de la JVM en `127.0.0.1`, sin
    dependencias: comprueba método, ruta y encabezados de los dos pedidos y que 401/404/500 no son error),
    `:core:data:jvmTest --rerun` (569 tests), `:core:ui:jvmTest`, `:especialidades:clinica:jvmTest`,
    `:features:hc:compileKotlinJvm` y `:composeApp:compileKotlinWasmJs -Pentorno=dev`. Sin cambios en `*.kts` ni en
    `libs.versions.toml`; `ktor-client-mock` no aparece en el código.

- [x] **F3.9. Ajustes tras la validación del reviewer de F3.** RF-9, RF-20, RF-37, RF-38 (sin spec nueva)
  - La tanda F3 se validó el 2026-10-24: **APROBADA CON OBSERVACIONES**. El usuario aprobó cerrar estos cuatro puntos antes
    de su prueba manual (F3.7). Los otros menores del reviewer quedan fuera de esta tarea. Sin cambios de spec, de texto, de
    pantallas ni de dependencias.
  - (1) **Android, rotación y recreación** (`EnfermedadesSeccion.kt`, `AdministrarEnfermedadesDialog.kt`): `administrarAbierto`
    pasa de `rememberSaveable` a `remember`. La ventana no sobrevive a la rotación ni a la recreación del proceso: no repite
    `abrirAdministrar()` (un pedido de más contra RF-38) ni reaparece con la sección cargando y "Cargando…" para siempre. Como el
    `ViewModel` sí sobrevive, la ventana llama a `cerrarAdministrar()` al salir de la composición (`DisposableEffect`), así que
    la próxima apertura no ve la lista ni lo escrito de la anterior. "¿Hay que pedir al abrir?" ya es una regla pura con test
    (`puedeAdministrar` dentro de `abrirAdministrar()`, F2.11); el efecto de la ventana corre una vez por apertura, así que no se
    agrega otra función.
  - (2) **Lector de pantalla en `ElegirFichaPaciente.kt`**: la tarjeta de cada candidata lleva `semantics(mergeDescendants = true)`
    (como las filas de la ventana de administrar) para que el lector diga nombre y DNI junto con "Elegir". Sin textos ni
    `contentDescription` nuevos (P6); el botón sigue siendo tocable y de 48 dp.
  - (3) **Pantalla angosta** (`EnfermedadesSeccion.kt`): "Reintentar" y "Administrar enfermedades" van en un `FlowRow` (pasan a
    dos renglones si no entran) y cada botón crece en alto si su texto ocupa dos líneas, en vez de recortarlo (`AcceptButton`
    fija 40 dp). Misma apariencia que antes en pantallas anchas. Sin cambios en `core/ui` ni en los textos.
  - (4) **DNI de prueba**: `FichaCandidataTest` pasa al rango `99000xxx`; la búsqueda con `grep` de DNIs de 8 dígitos en los tests
    de la tanda F3 no deja ninguno fuera de ese rango.
  - Tests: lo de pantalla no tiene lógica pura nueva (se verifica compilando y en F3.7). `FichaCandidataTest` se mantiene en verde
    con el DNI nuevo.
  - Hecho cuando: los puntos 1 a 4 hechos; `.\gradlew :core:data:jvmTest --rerun`, `:core:ui:jvmTest --rerun` y
    `:especialidades:clinica:jvmTest --rerun` en verde; `.\gradlew :features:hc:compileKotlinJvm` y
    `:composeApp:compileKotlinWasmJs -Pentorno=dev` compilan; buscar `rememberSaveable` en `EnfermedadesSeccion.kt` no da
    resultados; F3.7 (M3, M5, M6) con los pasos manuales nuevos.
  - Hecho (2026-10-25). Sin rojo previo: no hay lógica pura nueva (el único test tocado, `FichaCandidataTest`, solo cambia de DNI).
    Verdes: `:core:data:jvmTest --rerun` (569 tests, `FichaCandidataTest` 7), `:core:ui:jvmTest --rerun` (17),
    `:especialidades:clinica:jvmTest --rerun` (25), `:features:hc:compileKotlinJvm` y `:composeApp:compileKotlinWasmJs
    -Pentorno=dev`. `*.kts` y `libs.versions.toml` intactos. Decisiones: (1) `remember` en vez de `rememberSaveable` y no un
    arreglo del efecto: es lo más simple (la rotación cierra la ventana y no hay nada que repetir) y no depende de que el efecto
    "sepa" si hay un pedido; además `DisposableEffect { onDispose { controlador.cerrarAdministrar() } }` en la ventana, porque el
    `ViewModel` sobrevive a la rotación y sin eso la ventana siguiente mostraría la lista vieja mientras recarga. Cerrar por
    "Cerrar" llama dos veces a `cerrarAdministrar()`; es idempotente (conserva `enCamino`). Un cambio de la lista en camino al
    rotar sigue contando hasta que conteste (RF-45) y su respuesta no reescribe la ventana. (2) La tarjeta de `ElegirFichaPaciente`
    fusiona sus hijos (el `LabelValue` de la tarjeta no tiene clic ni "Copiar"); el foco y el 48 dp del botón no cambian. (3)
    `AcceptButton` fija 40 dp y es de `core/ui` (usado en toda la app): no se tocó; en la sección hay un botón local `Accion`
    con el mismo estilo y `heightIn(min = 40.dp)` (como `Elegir` y el `Boton` de la ventana, que ya repiten el estilo), dentro de
    un `FlowRow` con `Alignment.End` (mismo aspecto que `ActionRow` en pantallas anchas). (4) Los otros tests de la tanda F3 ya
    usaban `99000xxx`; `30111222` queda en tests de F2 y de la 002 (`MotivosEnfermedadesTest`, `EnfermedadesControladorTest`,
    `VinculoPacientesTest` y otros, también anteriores a la spec 003): no se tocaron por estar fuera de esta tarea.

Al terminar la tanda: validación del reviewer y MEMORY.md.

### Decididas (2026-10-23, usuario)
Las contestó el usuario (P1, P2, P4, P7, P8) o quedaron por defecto con la recomendación, sin objeción del usuario (P3, P5,
P6, P9, marcadas): esas cuatro se pueden corregir al aprobar las tareas. Las tareas de arriba ya tienen el comportamiento
definitivo; si cambia una, se ajustan las tareas citadas.

- **P1 = A. Motivos 1 a 4 (sesión vencida, HC no habilitada, médico no vinculado, licencia vencida) con la ventana de
  administrar abierta** (F3.5, F3.4a). Solo la ventana muestra el motivo (RF-46); la sección de atrás queda como estaba y,
  al cerrar, el próximo toque en una casilla trae el mismo motivo (RF-27). El controlador no cambia.
- **P2 = B. Botón "Reintentar" ampliado** (F3.4a, F3.4b, F3.7-M6). Además del caso con copia en solo lectura (RF-30), en
  "Hace falta conexión…" (RF-31) y con los motivos 7, 8 y 9 (sin ficha, ficha no disponible, fuera de la cartera). No con
  el 6 (ya tiene "Elegir") ni con 1 a 4 (no se arreglan reintentando). Cambio respecto de la spec: RF-30 solo lo pide con
  copia; queda como ampliación de pantalla, sin cambio de RF.
- **P3 = A (decidida por defecto, sin objeción del usuario). El buscador se conserva después de crear** (F3.5): la creada
  aparece en "Tu lista" (coincide con lo escrito) y el aviso la nombra; "Crear" desaparece solo porque ya hay una con ese
  nombre. No toca el controlador.
- **P4 = A. Ícono propio**: clave nueva `"enfermedades"` en `Iconos.kt` con `Icons.Default.HealthAndSafety`, sin
  dependencias (F3.4c).
- **P5 = A (decidida por defecto, sin objeción del usuario). El error de nombre se muestra al escribir** (en rojo, sin
  botón "Crear"), porque la app no lo enviaría igual (F3.5, RF-43).
- **P6 (decidida por defecto, sin objeción del usuario). Textos que la spec no fija**, tal cual están en la lista de
  abajo (F3.2, F3.4b, F3.5).
- **P7 = A. "Datos" como la spec** (RF-7, RF-8; D3/PD2): resuelve el vínculo y puede crear la ficha en la web de clínica,
  también para médicos que nunca usaron "Enfermedades"; gasta pedidos del `throttle` por IP. Sin `/sdd-change` sobre RF-8;
  F3.7-M7 lo mira (F3.6).
- **P8 = A. Solo lectura por falta de conexión con la consulta abierta** (RF-28, RF-30): la grilla con el último estado
  confirmado, las casillas deshabilitadas y el aviso de sin conexión (F3.4a). No pasa a "Estado actual del paciente".
- **P9 = A (decidida por defecto, sin objeción del usuario). Candidatas con los cuatro datos de la spec** (nombre,
  apellido, documento y fecha de nacimiento); no se muestra la cantidad de consultas (F3.2).

**Textos de interfaz nuevos (para aprobar de un vistazo)** — los que la spec no fija (P6) y los de "Reintentar" (P2):
- Ventana: título "Administrar enfermedades"; etiqueta del buscador "Buscar o crear una enfermedad"; listas "Tu lista" y
  "Del catálogo"; botones "Quitar", "Agregar", "Crear «nombre»" y "Cerrar"; mientras carga, "Cargando…".
- Sección: botón "Elegir" en cada ficha candidata (F3.2); "Cargando…" mientras carga; botón "Reintentar" (texto de
  `TEXTO_REINTENTAR`, que ya fija RF-30) en solo lectura con copia, en "Hace falta conexión para ver las enfermedades" y
  con los motivos 7, 8 y 9.
- Sin texto nuevo: la lista que no coincide con lo escrito no dice nada; sin fecha de nacimiento no se muestra esa línea;
  el aviso de sin conexión ("Sin conexión: las enfermedades se pueden ver pero no cambiar") y "Hace falta conexión para ver
  las enfermedades" ya existen en `core/data`.

### Discrepancias y huecos detectados (plan/spec contra el código real)
1. **RF-4 no se cumple "solo" y el plan lo da por hecho.** El plan dice "administrador y secretaria no abren consultas como
   médico". Pero `MainShell.kt:241` deja a la secretaria abrir las HC del médico que eligió, y `MainShell.kt:281` le pasa a
   `ConsultaScreen` el `medicoId` de **ese médico** (el administrador cae en `usuario.id`). Con el `medicoId` de la pantalla,
   la secretaria vería las casillas (y la consulta le resultaría "propia"); la API le contestaría `403 sin_permiso`. Se
   resuelve en F3.3 con `medicoParaEnfermedades(sesión)` y se filtra la pestaña en `ConsultaViewModel.pestanias`.
2. **`ConsultaViewModel.medicoId` pública (plan) no hace falta y sería el id equivocado** (punto 1); el reviewer de F2 pidió
   el de `PerfilMedico.medicoId`. F3.3 no la expone.
3. **`enfermedadesEnDatos` es `true` para pediatría** y cualquier definición sin la sección (`def.tiposConsulta.none {…}`):
   sin un filtro por HC general (`ui.resuelta.porPerfil`) la pestaña "Datos" de pediatría pediría enfermedades a hclinica y
   rompería RF-57. F3.6 lo filtra y lo prueba.
4. **"Datos" se dibuja en dos lugares** de `ConsultaScreen` (l. 196-198 y l. 216-219); el segundo es el del médico **sin
   perfil** (RF-6). El plan no lo dice; F3.6 cubre los dos.
5. **Logout: plan (bloqueante, "antes de olvidar el token, a lo sumo 3 s") contra C4 (segundo plano)**. Manda C4 (F3.8). Y
   los clientes ya no tienen el token cuando la revocación corre: hay que pasarlo por parámetro. Además el default de
   `TurnosOnlineClient()` es producción: los revocadores por defecto de `AuthRepository` tienen que ser una lista vacía.
6. **RF-31 no menciona "Reintentar"** (sí RF-30): sin él, "Hace falta conexión…" no tiene salida (se agrega por P2-B, ver "Decididas").
7. **RF-5 vs. `seccionEnfermedadesVisible`**: esa función pura y su test existen, pero la visibilidad real la da
   `layoutConsulta` (perfil + ocultas); no hace falta cablearla. Solo importa que F3.6 use `enfermedadesEnDatos`.
8. **Ubicación de archivos**: `iconoSeccion` está en `features/hc/Iconos.kt` (no en `core/ui`) y `features/hc` no tiene
   fuente de test; el AGENTS dice "solo hay tests en core:data, core:crecimiento y core:ui" pero `especialidades/clinica`
   también los tiene (`CatalogoHcGeneralTest`, `ChildPughTest`): ahí va el test del ícono.
9. **Sin RF de pantalla huérfano**: RF-1 a RF-3 (F2.9 + F3.7-M1), RF-4 (F3.3/F3.6, hueco del punto 1), RF-5 a RF-7 (F3.4b,
   F3.6), RF-9 a RF-31 y RF-35 (F3.4a/b), RF-37 a RF-47 y RF-58 (F3.5), RF-56 y RF-57 (F3.4c, F3.7), RF-59 (F3.8). RF-26
   (429) no se prueba a mano (harían falta 60 pedidos por minuto): queda cubierto por `MarcadoEnfermedadesTest` y
   `EnfermedadesDtoTest`.

### Riesgos de la F3
- **Sin tests de Compose**: lo que no está en una función pura (disposición, foco, desplazamiento, "Guardado" de 3 s) solo
  se comprueba compilando y a mano en F3.7.
- **Lector de pantalla en web (RF-20)**: Compose para web puede no exponer el árbol de accesibilidad al navegador; puede
  quedar "no verificable en web" hasta probar en Android.
- **Lista con alto infinito** dentro de la ventana con scroll (F3.5): hace caer la composición; por eso el alto acotado.
- **"Guardado"** reiniciado al cambiar de pestaña (la sección sale de la composición): se evita con `guardadoVisto` en el
  ViewModel (F3.3, F3.4b).
- **`editable` no observable**: el estado de "puede administrar" se calcula dentro de la composición (F3.4b); si alguien lo
  llama fuera, queda una recomposición atrasado.
- **Throttle `60,1` por IP** compartido por todos los pedidos del consultorio (abrir, tocar, administrar, "Datos"): el 429 es
  esperable (RF-26) y no se prueba a mano.
- **Revocación en segundo plano** (F3.8): si la pestaña se cierra justo después de cerrar sesión, el navegador puede cortar
  el pedido; queda la ventana de `cache_minutos` (10) ya aceptada.
