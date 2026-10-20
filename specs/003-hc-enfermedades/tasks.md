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

- [ ] **F1.2. "Enfermedades" en el catálogo de secciones.** RF-1, RF-2
  - `app/Services/Salud360/CatalogoSecciones.php`: `'enfermedades' => ['nombre' => 'Enfermedades', 'descripcion' =>
    'Casillas con las enfermedades del paciente, de la lista de cada médico.']`, segunda, entre
    `antecedentes_familiares` y `motivo_consulta` (PD9). `PerfilesService::validar` no cambia (usa `existe`).
  - Hecho cuando: `php -l` sin errores; phpunit de `PerfilesTest.php` y `EnfermedadesReglasTest.php` en verde en la
    copia servida; copiado a la servida, `curl` con el token del administrador a `GET config/catalogo` → `200` y
    `enfermedades` es la segunda sección (solo se miran los ids); `git diff` limitado a `CatalogoSecciones.php`.

- [ ] **F1.3. Servicio: estado de la sección, marcar, estado de administrar, agregar y quitar.** RF-50, RF-51,
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

- [ ] **F1.4. Servicio: crear (reutilizar, reactivar, `unique`, marcar al paciente).** RF-44, RF-54, RF-55
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

- [ ] **F1.5. Controlador y rutas.** RF-49, RF-50, RF-51, RF-52, RF-53, RF-54
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

- [ ] **F1.6. Documentación y prueba de humo completa.** RF-49, RF-50, RF-51, RF-52, RF-53, RF-54, RF-55
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

- [ ] **F1.7. `auth/salir` en hclinica y pediatría.** RF-59 (PD7)
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

Al terminar la tanda: validación del reviewer y MEMORY.md.
