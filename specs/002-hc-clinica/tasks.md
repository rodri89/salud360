# Tareas 002 — parte 1: pacientes y cartera

Plan: `plan-parte1.md` (aprobado el 2026-10-19, con PD1 decidido: E1.5 entra). Spec: `spec.md`, parte 1 (RF-8 a
RF-14 y RF-35 a RF-37), aprobada el 2026-10-19. Las tareas van por tandas; cada tanda se aprueba antes de generar la
siguiente (la E2, app, se genera después de validar la E1).

Reglas para todas:
- Commits locales como `rodri89` (ya configurado), uno por tarea, **sin push** en ningún repo. Subir a producción
  (hclinica o pediatría) lo decide el usuario después.
- Nada contra producción. Ningún dato real, contraseña ni token en ningún repo (tampoco en `.md`, tests ni
  mensajes de commit). Las pruebas que necesiten token usan los usuarios de prueba locales, sin escribir
  credenciales en ningún lado.
- Las bases locales `hc_clinica` y `hc_pediatrica` tienen datos reales: solo se mira **estructura**
  (`information_schema`, `SHOW CREATE TABLE`, valores distintos de una columna de códigos), nunca se muestran filas
  de pacientes. Los datos de prueba se crean con nombre "PRUEBA SALUD360" y documentos verificados libres antes de
  usarlos (`SELECT COUNT(*)`), y se borran al terminar cada tarea.
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
  comparados como texto antes de `(int)`.
- Licencia del médico de prueba de hclinica: vencida a propósito; para probar como médico extenderla y al terminar
  restaurarla con `UPDATE` puntual (incluidos `activo` y `updated_at`, ver `MEMORY.md`).
- No matar el `devWeb` (Gradle/Node) que tenga abierto el usuario.

## Tanda E1 — Servidor (hclinica y pediatría)

Sección "Servidor" del plan y diferencias con pediatría (P4 a P6, PD1). E1.1 a E1.4 en
`rebonline\hclinica` (rama `salud360-api`); E1.5 en `rebonline\hc_pediatria` (rama `salud360-api`). URL base de
las pruebas de clínica: `http://localhost/HClinica-salud360/public/index.php/api/salud360/...`, tokens del médico y
del administrador de prueba sacados de `auth/login` de turnos local.

- [ ] **E1.1. Relleno de columnas y reglas del vínculo (puras) con phpunit.** RF-11, RF-12, RF-13, RF-36
  - `app/Services/Salud360/ColumnasLegacy.php` copiada de pediatría, con una constante propia en lugar de
    `SeccionesService::SIN_CARGAR` (que en hclinica no existe).
  - `app/Services/Salud360/PacienteVinculoReglas.php` sin base: `normalizarDni` (quita puntos y espacios; cualquier
    otro no dígito → inválido; compara como texto contra `2147483647` antes de convertir; < 1000 y 99999 →
    inválido), `normalizarFecha` (`1000-01-01` y `0000-00-00` = sin fecha), `normalizarTexto` (mayúsculas, sin
    tildes, espacios juntos), `posibles($candidatos, $idTobb)`, `desempatar` (fecha y apellido; solo si queda
    exactamente una) y `cambiosParaCompletar($fila, $datos)` (solo columnas vacías, mayúsculas como `altaPaciente`
    de la web).
  - `tests/Unit/PacienteVinculoReglasTest.php` (extiende `PHPUnit\Framework\TestCase`, sin Laravel ni base):
    documento con puntos y espacios válido; `-12345678`, `12345678A`, `0`, `1`, `999`, `99999`, `2147483648`,
    `99999999999` inválidos; `2147483647` válido; fechas vacías; desempate por fecha y apellido con y sin tildes;
    mellizos (misma fecha y apellido) sin desempate; todas vinculadas con otro paciente de turnos → ninguna posible;
    completar: vacío no pisa, cargado no cambia, fecha `1000-01-01` cuenta como vacía.
  - Hecho cuando: phpunit de `PacienteVinculoReglasTest.php` y de `PerfilesTest.php` en verde en la copia servida,
    `php -l` sin errores en los dos archivos nuevos, y buscar `fn (` y `SeccionesService` en ellos no da
    resultados.

- [ ] **E1.2. Servicio del vínculo: columna en caliente, resolver y alta.** RF-10, RF-11, RF-12, RF-13, RF-35, RF-36
  - Antes de escribir: SQL de solo lectura sobre `hc_clinica` (estructura): `SHOW CREATE TABLE pacientes` y
    `medico_pacientes` (tipo de `dni`, columnas NOT NULL sin default) y `SELECT DISTINCT sexo FROM pacientes` (solo
    los valores) para el mapeo `M/F/X` (plan, Riesgos); anotar el mapeo en el KDoc/PHPDoc del servicio.
  - `app/Services/Salud360/PacienteVinculoService.php`: `asegurarColumna()` (`pacientes.paciente_id_tobb`
    `unsignedInteger default 0` con índice; recuerda en estático que existe; "duplicate column" de un pedido
    simultáneo = hecho); `resolver($medico, $datos)` en el orden del plan (por `paciente_id_tobb`; elegida
    `hc_paciente_id` activa, del mismo documento y no vinculada con otro, si no `ficha_no_elegible`; por documento:
    crea / `paciente_ya_vinculado` sin candidatas / vincula / desempata o `paciente_ambiguo` con candidatas con
    `cantidad_consultas` de `activo > 0`); alta con `ColumnasLegacy::vacias('pacientes')` + campos de la app en
    mayúsculas como la web, sin fecha → `1000-01-01`; `medico_pacientes` activo (crea o reactiva); todo en una
    transacción con `GET_LOCK('salud360_pac_<dni>')` y `RELEASE_LOCK` en `finally`. Usa `PacienteVinculoReglas`.
    Nunca toca `medico_infos`.
  - Hecho cuando: `php -l` sin errores; phpunit de E1.1 sigue en verde; `git diff` muestra solo el archivo nuevo; y
    se ejercita con `php artisan tinker` (o `php -r` con el bootstrap) en la copia servida con un médico de prueba
    y un documento "PRUEBA" libre: primera llamada crea (`creado` true), segunda vincula por `paciente_id_tobb` sin
    crear otra fila; `SHOW CREATE TABLE pacientes` muestra `paciente_id_tobb` con su índice una sola vez. Filas de
    prueba (`pacientes` y `medico_pacientes`) borradas con `DELETE` puntual; la columna nueva queda (es la misma
    que creará la API).

- [ ] **E1.3. Controladores y rutas de pacientes.** RF-8, RF-10, RF-11, RF-12
  - `Salud360Controller`: `formatearPaciente` de clínica (sin padre/madre; con `nombre_familiar`,
    `telefono_familiar`, `paciente_id_tobb`), `fechaCorta`, `atiende`, `soloMedico` (admin y secretaria →
    `403 sin_permiso`).
  - `PacienteController` (nuevo): `index` (cartera paginada `desde`, `limite` 1..1000, `distinct`, orden apellido,
    nombre, id; `total`, `hay_mas`), `resolver` (200/201 con `paciente`, `creado`, `vinculado_por`; `dni_invalido`
    422 con el mensaje de RF-12, `paciente_ambiguo` 409 con `candidatos`, `paciente_ya_vinculado` 409,
    `ficha_no_elegible` 422) y `show` (`404 paciente_no_encontrado`, `403 fuera_de_cartera`). Sin el caso especial
    usuario 8 → 6 de la web.
  - `routes/api.php`: `GET pacientes`, `POST pacientes/resolver`, `GET pacientes/{id}` dentro del grupo `salud360`.
  - Hecho cuando, copiado a la servida y con curl: sin token → `401 sin_token`; token del administrador en los tres
    → `403 sin_permiso`; médico (licencia extendida): `GET pacientes?limite=5` → 200 con a lo sumo 5, `total` y
    `hay_mas` (solo se miran los conteos y las claves, no los datos); `limite=0` y `limite=5000` se acotan;
    `POST pacientes/resolver` con `dni` `12345678A` → `422 dni_invalido`; `GET pacientes/0` →
    `404 paciente_no_encontrado`; `php artisan route:list` muestra las tres rutas con el middleware `salud360`;
    la web de hclinica sigue entrando normal.

- [ ] **E1.4. Documentación y prueba de humo completa.** RF-8, RF-10, RF-11, RF-12, RF-36
  - `md/API_SALUD360_CLINICA.md`: sección "Pacientes" (cuerpos, respuestas, códigos, columna creada en caliente,
    curl de humo sin credenciales) y tabla "Código" con los archivos nuevos.
  - Humo con curl contra la copia servida, con fichas de prueba creadas por SQL ("PRUEBA SALUD360", documentos
    libres): paciente nuevo → 201 `creado: true` y aparece en `GET pacientes`; mismo paciente otra vez → 200
    `vinculado_por` = por id de turnos; dos fichas con el mismo documento sin desempate → `409 paciente_ambiguo` con
    2 candidatas; elegir una de ellas → 200 `vinculado_por` = elegida; elegir una ficha de otro documento →
    `422 ficha_no_elegible`; documento con una sola ficha vinculada a otro `paciente_id_tobb` →
    `409 paciente_ya_vinculado` sin candidatas; `GET pacientes/{id}` de una ficha de prueba fuera de la cartera →
    `403 fuera_de_cartera`.
  - Hecho cuando: todos los códigos de arriba salen como se listan; phpunit de E1.1 en verde; la copia servida es
    igual al clon en los archivos de la tanda (comparación de contenido, sin contar `.env`/`vendor/`); el `.md` no
    tiene tokens, contraseñas ni nombres de médicos o pacientes; al terminar, `SELECT COUNT(*)` de `pacientes` y
    `medico_pacientes` con los documentos de prueba da 0 y la licencia del médico de prueba está restaurada.

- [ ] **E1.5. Mismos arreglos en pediatría, sin cambiar lo que responde a la app.** PD1 (P4, P6), RF-11, RF-12
  - En `rebonline\hc_pediatria` (rama `salud360-api`): elegida (`pediatria_paciente_id`) solo si es activa, del
    mismo documento y no vinculada con otro paciente de turnos (P4); documento con la regla de E1.1 (P6: signo o
    letras → `dni_invalido`; comparación como texto para 32 bits); `asegurarColumna` con estático y "duplicate
    column" tolerado. Puede reutilizar una `PacienteVinculoReglas` copiada de E1.1 (con su test). Sin cambiar las
    claves ni los códigos de las respuestas que ya usa la app (`dni_invalido`, `paciente_ambiguo`,
    `paciente_ya_vinculado`, `no_encontrado`, formato del paciente). `md/API_SALUD360_PEDIATRIA.md` actualizado.
  - Hecho cuando: phpunit del test de reglas en verde en `HCDPediatria-salud360`; `php -l` sin errores; `git diff`
    en `hc_pediatria` limitado a los servicios, el test y el `.md`; con curl contra la copia servida y el token de
    prueba de pediatría (sembrado en `salud360_token_cache`, sin escribirlo): `dni` `-12345678` y `12345678A` →
    `422 dni_invalido`, elegida de otro documento → rechazada; `HcPediatriaE2ETest` y `HcCarteraE2ETest` con
    `--rerun` contra la copia servida de pediatría siguen en verde (respuestas a la app sin cambios). Filas de prueba
    borradas con `DELETE` puntual.
  - Pendiente antes de empezar E1.5: con qué código y estado rechaza pediatría la elegida que no corresponde (hoy
    solo existen `no_encontrado` 404 y `paciente_ya_vinculado` 409; `ficha_no_elegible` sería nuevo) y si la copia
    servida de pediatría necesita `config:cache` (hoy no tiene `bootstrap/cache/config.php`).

Al terminar la tanda: validación del reviewer y MEMORY.md.
