# Tareas 005 — La HC general escribe sus consultas (E0 y E1 detalladas; E2 a E5 en lista)

Estado: **plan y tareas aprobados por el usuario el 2026-11-04**. Spec: `spec.md` (aprobada el 2026-11-04, RF-1 a
RF-70). Plan: `plan.md`. Apoyo técnico: `notas-para-el-plan.md`.

**Por qué solo E0 y E1 detalladas.** La skill pide proponer dividir si salen más de 10 tareas; esta spec ya está partida en
subentregas (D5). Se detallan **E0 y E1** (lo que se puede empezar tras la aprobación); **E2 a E5 quedan como lista de tareas con
título y RF** y se detallan **al llegar a cada subentrega, con aprobación aparte** (cada una necesita lo que enseñe la anterior y,
E3, una migración con su número). Las tareas de producción **no se detallan**: «E9 de la escritura: aprobación aparte».

Formato: `- [ ] **Tn. Descripción.** RF-x — S/M — Toca API Laravel: sí/no` + «Hecho cuando». S = hasta 20 min, M = hasta 30 min; si una
M no entra se divide antes de empezar. Orden de dependencia: modelo → base → datos → interfaz, y **el servidor local antes que la
app que lo usa**.

## Reglas para todas las tareas (además de las de la 002)
- Commits locales como `rodri89`, uno por tarea, **sin push** en ningún repo. Pushear `main` publica la app (no antes de cerrar E1 a E3,
  D23, con el servidor ya en producción).
- **Nada contra producción** salvo las etapas «E9 de la escritura», que tienen aprobación aparte y no usan tests automáticos. Las pruebas
  de la app corren con `-Pentorno=dev` (en esta máquina las URLs locales salen de `local.properties`).
- **Toca API Laravel: sí** = autorizado en D3 **solo para la copia local** (clon `C:\Users\flor\rebonline\hclinica`, rama `salud360-api`;
  copia servida `C:\xampp\htdocs\HClinica-salud360`). Producción: aprobación aparte y respaldo probado (D4).
- **Migración** = «pregunta antes: ya aprobada en D26 (E1) y D8 (E3)»; el número se reserva **justo antes de crearla**, mirando todas las
  ramas, y se vuelve a preguntar si otra rama ya lo tomó (`plan.md`, «Migraciones locales»).
- Datos de prueba **inventados** («PRUEBA SALUD360», documentos verificados libres con `SELECT COUNT(*)`) y **creados por la propia
  prueba**. Las bases locales tienen datos reales: solo estructura y agregados; nunca filas de pacientes, nombres, tokens ni contraseñas
  en el repo, los tests o los `.md`. Limpieza **por id**, nunca `DROP`. Scripts y volcados fuera del repo: `%TEMP%\opencode\e0\…`.
- Servidor: PHP 7.3 de 32 bits (sin `fn`, sin propiedades tipadas, ids como texto antes de `(int)`); `php -l` de cada archivo; copiar a la
  servida y `php artisan route:clear`; phpunit con `C:\xampp\php\php.exe vendor\bin\phpunit tests\Unit\<Test>.php`; si un test falla
  el *printer* se cae: correr la clase con un `TestSuite`/`TestResult` propio (`%TEMP%\opencode\lectura\corre.php`) o con `php -r`. Pruebas
  con base: scripts con el bootstrap de Laravel contra una base descartable, con `sql_mode` estricto (`STRICT_TRANS_TABLES`) **y** sin
  él; cargar y limpiar por stdin (con `mysql -e source` un error no corta). Throttle de 60 pedidos por minuto por IP: curl espaciado ~1,1 s.
  La licencia del médico de prueba de hclinica está extendida a propósito: no restaurarla salvo en las tareas de licencia, con script y
  restauración verificada. Vaciar `salud360_token_cache` solo donde la tarea lo diga.
- App: tests primero (en rojo), después el código, tests en verde; `.\gradlew :core:data:jvmTest --tests '*Nombre*'`. Si Gradle falla por
  `JAVA_HOME`: `$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`. **RAM:** cerrar el navegador antes de compilar; el `jvmTest`
  completo solo al cerrar cada tarea. `features/*` no tiene tests: la lógica nueva vive en `core/data` (commonMain), con el reloj como
  parámetro. No usar `build` (compilaría contra producción). No matar el `devWeb` del usuario; servir esta rama en **otro puerto**
  (la base web se descarta si es de un esquema más nuevo).
- Si al hacer una tarea aparece un envío a la web que no está en la tarea, o una escritura de `medico_infos`, se para todo y se avisa.

---

# E0 — Precondiciones (D4): limpieza, respaldo y scripts (no tocan el código de la app)

Se hacen **antes** de cualquier tarea de E1 que escriba en la copia local. Preguntas previas al usuario en `plan.md`
(«Qué hay que preguntarle al usuario antes de empezar»).

- [x] **T1. Respaldo de `hc_clinica` local restaurado en una base descartable y verificado.** D4 — S — Toca API Laravel: no
  (base local de pruebas)
  - Volcado de la base local con `mysqldump` (por stdin/archivo fuera del repo) y restauración **en otra base** (`hc_clinica_respaldo`,
    o la que indique el usuario), sin `DROP` de nada existente. Solo estructura y agregados; sin mostrar filas de pacientes.
  - Hecho cuando: `CHECKSUM TABLE` y `COUNT(*)` de las **68 tablas** de la base original y de la restaurada son idénticos (archivo
    `%TEMP%\opencode\e0\respaldo-verificacion.txt`); el volcado queda fuera del repo; anotado qué base descartable quedó y cómo
    borrarla.
  - Resultado (2026-11-04): las 68 tablas (todas InnoDB, ~7,3 MB) dan conteo y `CHECKSUM TABLE` idénticos entre `hc_clinica` y la
    restaurada, 68 iguales y 0 distintas. Volcado de 2.921.584 bytes, sin `CREATE DATABASE` ni `USE`. Quedó la base descartable
    `hc_clinica_respaldo` (borrarla con `DROP DATABASE hc_clinica_respaldo;` cuando haga falta, no antes); volcado y
    `respaldo-verificacion.txt` en `%TEMP%\opencode\e0\`, fuera del repo. Sin datos sensibles: solo estructura y agregados.
- [x] **T2. Saldar la deuda de limpieza de la copia local — datos de la L.** D4 — M — Toca API Laravel: no
  - La lista exacta está en `MEMORY.md` («Limpieza pendiente en la base local de clínica»): una consulta de la web con sus filas
    hijas, un PDF de consulta con su archivo en disco, y la restauración de una fila de `medico_infos` a sus valores originales. **Se
    confirma la lista con el usuario antes de borrar nada.**
  - `DELETE`/`UPDATE` puntuales por id en el orden de las claves foráneas (hijas → `consultas` → `medico_pacientes` → `pacientes`);
    jamás `DROP`. Con `START TRANSACTION … ROLLBACK` ensayado primero.
  - Hecho cuando: los ids de la lista dan `COUNT(*) = 0` (y la fila de `medico_infos` vuelve a sus valores); el archivo del PDF
    de prueba borrado del disco; los conteos y sumas de las 68 tablas igualan a los de T1 **salvo exactamente** las filas borradas
    (diferencia explicada fila por fila en el archivo de apoyo).
  - Resultado (2026-11-04): lista confirmada por el usuario. Ensayo con `ROLLBACK` (InnoDB, las 68 tablas idénticas al respaldo
    después) y ejecución real con `COMMIT`: 11 hijas en tablas de la web + 1 PDF de consulta (12 filas con `consulta_id` de la
    consulta; el recorrido de las 41 tablas con esa columna no halló más), la consulta (1) y `medico_infos` restaurada (1 fila, solo
    `consulta_id`, `es_nueva_consulta` y `paciente_id`). El archivo del PDF (2,8 MB, SHA-256 verificado antes) estaba en
    `public/img/` de la copia servida y se borró; las carpetas quedan. Frente al respaldo difieren exactamente 14 de las 68 tablas
    (las 13 con filas borradas, −1 cada una, y `medico_infos` en esas 3 columnas); las otras 54, incluida `salud360_token_cache`
    (3 filas de E8.2, fuera de la lista), idénticas. Las 13 vuelven al conteo y checksum de la línea base previa a E8.2;
    `medico_infos` no vuelve al checksum de esa línea base (el `UPDATE` pedido toca solo 3 columnas; el resto, probablemente `updated_at`, conserva lo que escribió la web). Fixture de la tanda S sin tocar. Detalle por tabla en
    `%TEMP%\opencode\e0\t2-resultado.txt`, fuera del repo.
- [x] **T3. Saldar la deuda de limpieza de la copia local — datos de la spec 003.** D4 — M — Toca API Laravel: no
  - Enfermedades «Prueba Salud360», la lista vaciada del médico de prueba (restaurar sus filas inactivas con `UPDATE` puntual, según
    `%TEMP%\opencode\f37\limpieza.sql` y `estado-inicial.txt`), pacientes «PRUEBA SALUD360» A a F, el perfil de prueba y el vínculo
    del médico con él; antes, mirar en el registro de Apache a qué pacientes y enfermedades apuntan los `PUT enfermedades/…` de esas
    pruebas, para restaurar esas marcas. **Confirmar la lista con el usuario.**
  - Hecho cuando: los totales de las tablas de enfermedades, pacientes y `medico_enfermedades` vuelven al estado inicial anotado en
    `estado-inicial.txt`; `limpieza.sql` aplicado y archivado; conteos de las 68 tablas explicados contra T2.
  - Resultado (2026-11-04), **hecha con excepciones al «Hecho cuando» por decisión del usuario del mismo día.** Alcance autorizado: solo
    enfermedades, `medico_enfermedades`, pacientes y sus hijas. Antes de borrar se vio con el registro de Apache que los 9 `PUT
    enfermedades/pacientes/…` del 26 y 27 de octubre apuntaban solo a 2 fichas de prueba (ningún paciente real): sus 4 marcas no existían
    antes y se fueron con las fichas. Ensayo con `ROLLBACK` (las 68 tablas idénticas después) y ejecución real con `COMMIT`: 6
    `medico_enfermedades` (las que creó la prueba; se borraron en vez de reactivarse, para volver al estado inicial), 4
    `paciente_enfermedades`, 5 `medico_pacientes`, 5 pacientes de prueba y 3 enfermedades «Prueba Salud360»; cero consultas ni otras
    hijas (recorridas las 47 tablas con `paciente_id`). Totales: `enfermedades` 29, `medico_enfermedades` 29, `paciente_enfermedades`
    1925 activas y 30 inactivas, `pacientes` 1395 y `medico_pacientes` 1400 (= inicial + fixture de la tanda S, que no se tocó: 2
    pacientes y 1 cartera). Frente al respaldo difieren 19 de las 68 tablas: las 14 de T2 y estas 5, −3, −6, −4, −5 y −5 filas; las otras 49,
    incluidas `salud360_token_cache` y las de perfiles, idénticas. Detalle en `%TEMP%\opencode\e0\t3-resultado.txt`, fuera del repo.
  - **Excepciones y lo NO saldado:** (1) el perfil de prueba 4 se **conservó** (con sus pestañas y secciones); (2) el médico de prueba
    **sigue con el perfil 2** (el original era el 1); (3) el **perfil 2 (Hepatología) NO está como al inicio**: se editó el 26/10 con 2
    `PUT` a `config/perfiles/2` y hoy no tiene las 17 secciones de la carga inicial (tiene 5); es reconstruible desde la carga inicial de
    perfiles de hclinica y el usuario decidió no tocarlo: **NO SALDADO**; (4) los pacientes E y F de la prueba (en `turnosonlinebb`) no
    se tocaron. Por eso los totales de perfiles (3 perfiles, 6 pestañas, 29 secciones) **no** vuelven al estado inicial (2, 4 y 29).
- [x] **T4. Scripts de la escritura: cargar, contar, sumar y limpiar por id.** D4, RF-15, RF-16 — M — Toca API Laravel: no
  - En `%TEMP%\opencode\e0\`: `escritura-contar.sql` (conteos de las tablas de consulta y de las hijas por `consulta_id` y por
    `paciente_id` de prueba, más `salud360_envios` si existe), `escritura-suma.sql` (`CHECKSUM TABLE` de las tablas que la escritura
    puede tocar **incluida `medico_infos`**), `escritura-limpiar.sql` (borra **por id** lo que cree la API: hijas →
    `consultas` → filas de `salud360_envios` **por clave**, nunca vacía la tabla; devuelve el contador de cada paso) y `cargar-prueba.sql`
    (paciente «PRUEBA SALUD360» en la cartera del médico de prueba con un documento verificado libre; **solo para ensayar los scripts**:
    los E2E crean sus propios datos).
  - Hecho cuando: en la base descartable de T1, cargar → limpiar → cargar → limpiar deja las sumas idénticas a las de antes; `suma.sql`
    da lo mismo dos veces seguidas sin tocar nada; ningún dato real copiado.
  - Resultado (2026-11-04): scripts en `%TEMP%\opencode\e0\` (fuera del repo; `README.md` ahí explica parámetros): `escritura-contar.sql`
    y `escritura-suma.sql` (solo lectura; incluyen `medico_infos` y `salud360_envios` si existe, que hoy no), `cargar-prueba.sql`,
    `escritura-limpiar.sql` (por id, todo o nada, con transacción) y `correr.cmd` (la base va como parámetro; se niega a cargar o
    limpiar en `hc_clinica`) más `ensayo-t4.cmd`. Ensayo en `hc_clinica_respaldo`, con `STRICT_TRANS_TABLES` y sin él: cargar → limpiar →
    cargar → limpiar deja idénticas la suma de escritura, la de la lectura y las 68 tablas (incluida `medico_infos`); el `AUTO_INCREMENT`
    avanza y el `CHECKSUM TABLE` no depende de él. `limpiar` se negó con consulta ajena, propia+ajena, paciente real, documento
    inexistente, lista mal formada y hija de otro paciente (sin borrar nada, salida con error); no toca `medico_infos` y deja la ficha si
    le queda algo colgado. Sobre `hc_clinica` solo se corrieron los de solo lectura: sin cambios. `limpiar` supone `user_id` y `clave` en
    `salud360_envios` (aún no existe): confirmar al llegar a T8/T40.
- [x] **T5. Línea base y cierre de E0.** D4 — S — Toca API Laravel: no (hecha la línea base y la verificación del entorno; **E0 cerrada: el
  usuario la confirmó el 2026-11-04**)
  - Línea base de conteos y sumas de las 68 tablas **antes** de E1; definir con el usuario el médico de prueba local y confirmar el
    documento libre del paciente «PRUEBA SALUD360» que usarán los E2E; `MEMORY.md` actualizado (sin datos sensibles).
  - Hecho cuando: archivo `%TEMP%\opencode\e0\linea-base.txt`; el usuario confirma E0 cerrada; la deuda de la L y de la 003 figura
    saldada en `MEMORY.md`.
  - Resultado (2026-11-04): `%TEMP%\opencode\e0\linea-base.txt` (fuera del repo, solo lectura, sin datos de filas): filas y `CHECKSUM TABLE`
    de las 68 tablas (23.643 filas), `escritura-suma.sql` y `lectura\suma.sql`, con las excepciones conocidas en la cabecera. Medido dos veces:
    salidas idénticas byte a byte (también `salud360_token_cache`). Frente a `hc_clinica_respaldo` difieren 19 tablas (las de T2 y T3), sin
    otras. Entorno de E1 verificado sin tocarlo: Apache y MySQL vivos; la copia servida de hclinica y el clon (`salud360-api`, HEAD
    `64849c9`, sin cambios sin commitear) tienen los mismos 24 archivos de `Api/Salud360`, `Services/Salud360` y `routes` (SHA-256; la servida
    no es un repo git); el médico de prueba existe en ambas bases, activo, con perfil de la app 2 y licencia vigente; el documento 99000900
    está libre en ambas bases; conteos de médicos por `users.perfil` de la web para T11 anotados.
  - **Confirmado por el usuario el 2026-11-04** (lo que estaba pendiente): (a) **E0 cerrada**; (b) el **médico de prueba** para los E2E es el
    user 9 de `hc_clinica` (`medico_id_tobb` 38, perfil 2, licencia vigente) y el **documento libre 99000900** es el del paciente «PRUEBA
    SALUD360». El pedido de T6 (T9 de la 004) lo hizo el usuario el mismo día; T7 sigue sin pedido.

---

# E1 — Crear, textos, examen físico, cerrar, el envío, el histórico y la licencia en el ingreso
RF-1 a RF-34 y RF-50 a RF-69. Orden: precondición (T6, T7) → servidor local (T8 a T14) → app: modelo y base (T15 a T18), datos (T19 a
T35), interfaz (T36 a T39) → pruebas y cierre (T40 a T47).

## Precondición: licencia del servidor en `hc-general` (D22)
- [x] **T6. T9 de la spec 004: cherry-pick de T1 a T7 desde `origin/main` a `hc-general`.** RF-58 (D22) — M — Toca API Laravel: no
  - **La T9 requería pedido aparte del usuario según la spec 004; lo hizo el 2026-11-04** (el plan no la ejecuta por su cuenta). Pasos y cuadro de conflictos: `specs/004-licencia-del-servidor/
    tasks.md` («Integración en `hc-general`»): los 9 commits en orden, sin merge; `HcBackend` sin reintroducir `traerPacientes`;
    parámetros nuevos de `AuthRepository` **después** de los de `hc-general`; `TobbException.cuerpo` al final; `cerrarPorLicencia` llama a
    `logout(token)`.
  - Hecho cuando: `.\gradlew :core:data:jvmTest` completo y `:composeApp:compileKotlinWasmJs -Pentorno=dev` en verde en `hc-general`;
    `docs/hc-pediatria-estado-y-plan.md` («Las licencias») actualizado; `MEMORY.md` al día.
  - **Resultado (2026-11-04): HECHA.** Los 9 cherry-picks (`-x`, en orden, sin merge) están en `hc-general` (`ba679f5`, `ec5835a`, `a01bcb1`, `bd41e0c`,
    `022674c`, `313a2f6`, `66a4886`, `9b3eb0c`, `6d2cf4d`); los conflictos del cuadro (HcBackend, imports y constructor de AuthRepository, TobbException,
    `login`, DataModule) se resolvieron como dice, y la integración solo agregó el parámetro opcional `tokenARevocar` a `cerrarPorLicencia` (detalle en la T9
    de la 004). `:core:data:jvmTest` completo: **1305 tests, 1305 pasan, 0 fallan, 0 salteados**; `:features:auth:compileKotlinWasmJs` y
    `:composeApp:compileKotlinWasmJs -Pentorno=dev`: salida 0. Hubo que adaptar tres tests de la 004 al código de `hc-general` (commit `tests:` aparte:
    `nombresHc` en `DataModuleLicenciaTest`, `cuerpo` por nombre en `HcApiClientErrorTest` y el test de `HcBackends`, que ahora dice que pediatría lleva la
    licencia y `clinica` es de solo lectura y contesta `SinDato` sin pedir nada). T7 sigue sin pedido.
- [x] **T7. Enmienda de texto a la spec 004 (D22/D25).** RF-58 — S — Toca API Laravel: no
  - Con el mismo pedido de T6: aplicar a la spec 004 la lista de `notas-para-el-plan.md` §7 (definiciones, RF-1 a RF-5, RF-7 a RF-9, RF-14,
    RF-15, RF-17 a RF-19, HU-2, casos límite 2, 5, 7 y 14, caso 10 y «Fuera de alcance»), **donde se integre la T9** y **avisando en
    las dos ramas** (`origin/main` y `hc-general`).
  - Hecho cuando: la 004 dice lo mismo en ambas ramas; el usuario aprueba el cambio; nota en `MEMORY.md`.
  - **Resultado (2026-11-04): HECHA en `hc-general`** (el usuario aprobó la enmienda al aprobar el plan y las tareas). Solo texto, ningún
    código. `specs/004-licencia-del-servidor/spec.md` con marca «[enmendado por la 005, D22/D25, 2026-11-04]» y lo original tachado donde
    hace falta: aclaración inicial (es TEXTO; el código de clínica va en T35 y relacionadas; T1 a T9 de la 004 hechas, sin tareas nuevas),
    contexto, usuarios, HU-2, definiciones (por HC), RF-1 a RF-10 (con la regla D25 de combinación), RF-11, RF-12, RF-14, RF-15, RF-17 a
    RF-19, RF-21, RF-22, casos límite 2, 5, 7 (deja de valer), 10 y 14 (los demás se leen por HC), «Fuera de alcance»; `tasks.md` de la 004
    con el mismo aviso de estado. **Pendiente: avisar en `origin/main`** (la 004 allí sigue con el texto de antes; sin push no se puede
    igualar desde acá), así que «la 004 dice lo mismo en ambas ramas» queda abierto hasta que se haga con pedido.

## Servidor local de E1 (hclinica, rama `salud360-api`) — todas «Toca API Laravel: sí; autorizado en D3 solo para la copia local»
- [x] **T8. Reglas puras de escritura y su test (phpunit).** RF-14, RF-20, RF-21, RF-22, RF-23, RF-34, RF-69 — M — Toca API Laravel: sí
  - Tests primero: `tests/Unit/EscrituraReglasTest.php` (sin Laravel ni base): tipo `control`/`telemedicina` (1/4; otro → inválido);
    fecha `AAAA-MM-DD` válida y la fecha clínica como `created_at`; edad; secciones de texto permitidas (motivo, datos subjetivos y
    objetivos, conductas, nota, hábitos, diuresis y catarsis, somnia) y `desconocidas`; texto con tildes, ñ, emojis, saltos de línea;
    **vaciado a propósito** (`""` se escribe) frente a clave ausente (no se toca); examen físico: solo claves presentes, `0.00` =
    vacío, `0` y `0.0` son datos; qué filas confirma el cierre (solo exámenes e interconsultas con `activo = 2`, nunca laboratorio ni
    peso ni las dadas de baja); autorización pura (administrador, secretaria, otro médico).
  - Código: `app/Services/Salud360/EscrituraReglas.php` (sin base; usa `MapaClinica::columnaWeb`).
  - Hecho cuando: el test en verde (antes, en rojo) en la copia servida; `php -l` limpio; buscar `fn (`, propiedades tipadas, `DB::` y
    `medico_infos` en el archivo no da resultados; `tests/Unit` completo sigue en verde (469 tests al cierre de la L).
  - **Resultado (2026-11-04): HECHA.** hclinica `e35d8e7` (rama `salud360-api`, sin push; solo `EscrituraReglas.php` y `EscrituraReglasTest.php`),
    copiados a `HClinica-salud360` (idénticos por hash) y `route:clear`. Primero rojo (215 errores, la clase no existía) y después verde:
    `EscrituraReglasTest` **215 tests, 828 aserciones**; `tests/Unit` completo **684 tests en verde** (469 + 215). `php -l` limpio y sin
    `fn (`, propiedades tipadas, `DB::`, `Schema::` ni `medico_infos` en el archivo; 9 mutaciones del código (fecha con `$`, `0.00`,
    cierre sobre `activo` 0, recorte, baja que repite, propiedad de la consulta, médico del cuerpo, bytes, mayor id) las detecta el test. Reglas
    (todas estáticas, sin base, red ni reloj; reutilizan `MapaClinica` y `ConsultasReglas`): tipo 1/4 (RF-14), fecha clínica `AAAA-MM-DD` y
    `created_at` (RF-14, RF-22), edad (255), clave de repetibilidad `c:<id>` de hasta 191 (RF-15), texto a escribir con vaciado a propósito
    (RF-20, RF-69), `secciones` solo con lo presente y `desconocidas` (RF-20, RF-34), fila destino `activo = 1` de mayor id, examen físico
    con `MapaClinica::columnaWeb`, `0.00` = vacío y `0`/`0.0` datos (RF-21), `crear`/`consultaNueva` a nombre del médico del token (RF-14),
    `cierre` y `filasQueConfirmaElCierre` (RF-22, RF-23), `esMedico`/`autorizarRol` (RF-52) y `autorizarConsulta` (404, 403 `consulta_no_propia`,
    409 `consulta_cerrada`, repetir; RF-52, RF-53). **Decisiones de contrato para revisar** (el porqué está en el KDoc de la clase): sin
    recortar los extremos de un texto con contenido; texto en blanco = vaciado; nulo o no texto = 422 (nunca un vaciado); números JSON
    en el examen rechazados (un `0.00` llegaría como `0`); tope de 65535
    bytes por texto; fecha entre 1970-01-02 y 2038-01-18 (`timestamp`), sin exigir que sea pasada; edad en blanco al cerrar = no cambia; baja
    de una consulta dada de baja y cierre de una cerrada = repetir; escribir sobre una dada de baja = 409 `consulta_cerrada` (no 404);
    `dispositivo` lo valida T9.
- [x] **T9. Registro de lo recibido (`salud360_envios`) y su test.** RF-15, RF-52 — M — Toca API Laravel: sí
  - Tests primero: `tests/Unit/EnviosRegistroTest.php` (parte pura: validación de la clave y del dispositivo, armado de la fila,
    «ya registrada → misma respuesta»). Con base (script contra la base descartable): crea la tabla sola con `Schema::create` (privilegio
    `CREATE`), `UNIQUE (user_id, clave)`; dos registros simultáneos de la misma clave → uno solo y los dos reciben lo mismo;
    `consultaCreadaPorLaApp(user, consulta)`.
  - Código: `app/Services/Salud360/EnviosRegistro.php` (sin vencimiento ni limpieza automática). **No toca columnas de tablas de la web.**
  - Hecho cuando: phpunit en verde; el script de base descartable (modo estricto y sin él) pasa; la tabla creada una sola vez; el
    respaldo de la base la incluye (nota en el `.md`).
  - **Resultado (2026-11-04): HECHA.** hclinica `9819292` (rama `salud360-api`, sin push; solo `EnviosRegistro.php` y `EnviosRegistroTest.php`),
    copiados a `HClinica-salud360` (idénticos por hash) y `route:clear`. Tabla **`salud360_envios`** (InnoDB, utf8mb4; `Schema::create` fuera de
    toda transacción, nunca se altera): `id` bigint, `user_id` int unsigned, `dispositivo` varchar(100), `clave` varchar(191) **binaria**
    (`utf8mb4_bin`), `tipo` varchar(20), `remoto_id` bigint unsigned null, `consulta_id` int unsigned null, `respuesta_json` text null,
    `created_at`, `updated_at`; **único `(user_id, clave)`** (`salud360_envios_user_id_clave_unique`) e índice `(user_id, consulta_id)`.
    Sin datos clínicos (ids, estado y lo que se le contestó a la app). Servicio: puras estáticas (`validarClave`, `validarDispositivo`,
    `validarTipo`, `filaNueva`, `resultadoDeFila`, `esClaveRepetida`) y con base `asegurarTabla`, `buscar`, `registrar`, **`conTransaccion`**
    (la escritura y su registro en la misma transacción; carrera: el segundo revierte todo, incluida la escritura de la web, lee lo del primero
    y contesta lo mismo), `consultaCreadaPorLaApp`, `consultasCreadasPorLaApp`. **Cómo lo usa T10:** `conTransaccion($medico, $dispositivo,
    $clave, 'consulta', function () { …escribe en la web…; return ['consulta_id' => $id, 'respuesta' => […]]; })` → `{repetida, registro,
    resultado}`; `repetida = true` contesta `200` con `registro.respuesta`. **Pruebas:** `EnviosRegistroTest` 66 tests y `tests/Unit` completo
    **750 en verde** (684 + 66; `hc_clinica` sigue con 68 tablas y sin `salud360_envios` tras correr toda la suite). Con base: script fuera
    del repo `%TEMP%\opencode\t9reg\envios-con-base.php` contra la base descartable **`hc_clinica_t9`** (guardas: aborta si la base no es esa;
    no se editó ningún `.env`), 79 controles OK en modo estricto y 79 sin él (creación, reintento, claves y médicos independientes, carrera
    simulada con otra conexión, **carrera real con un proceso hijo bloqueado en el candado**, rollback si falla la escritura o el registro,
    DDL dentro de transacción rechazado, consultas creadas por la app, tabla testigo de la web intacta); las 8 mutaciones del servicio
    (sin manejo de la carrera, sin único, clave no binaria, sin lectura previa, MyISAM…) las detectó el script. `escritura-limpiar.sql` (T4) usa
    `user_id` y `clave`: coincide, no hubo que cambiarlo. Pendiente de T14: la nota en `md/API_SALUD360_CLINICA.md` (tabla propia, privilegio `CREATE`, entra
    en el respaldo). Decisiones de contrato: se aplicó la firma del plan (con `dispositivo`, que se valida y se guarda); `registrar` toma
    `{remoto_id, consulta_id, respuesta}`; la clave es binaria (distingue mayúsculas); prefijos de clave `c r l p` (los de adjuntos, a definir en E4); dispositivo
    `[A-Za-z0-9._:-]{1,100}`; respuesta de hasta 65535 bytes; `registrar` fuera de transacción devuelve lo existente, dentro relanza el choque.
- [ ] **T10. Crear la consulta: servicio, controlador y ruta.** RF-12, RF-14, RF-15, RF-52, RF-59 — M — Toca API Laravel: sí
  - Código: `EscrituraService::crear` (en `DB::transaction`; `medico_id` = el del token, tipo, `created_at` = la fecha clínica del
    cuerpo, `edad_mostrar`, `activo = 2`, relleno con `ColumnasLegacy::vacias`; registra en `salud360_envios`; devuelve `otras_abiertas`
    = consultas `activo = 2` del paciente de cualquier médico con su nombre), `ConsultaEscrituraController@store` con `soloMedico` +
    `pacienteVisible`, y la ruta en `routes/api.php`. **No escribe `medico_infos` y no crea filas vacías de `inicializarCampos`** (se
    decide en T11).
  - Hecho cuando: con curl y el token del médico de prueba: sin token `401 sin_token`; administrador y secretaria `403 sin_permiso`;
    paciente fuera de cartera `403 fuera_de_cartera`; inexistente `404`; datos inválidos `422`; creación `201`; **la misma clave otra
    vez `200` con la misma consulta y una sola fila nueva en `consultas`**; `otras_abiertas` informa la abierta de otro médico del
    fixture; `suma.sql` solo cambia en `consultas` y `salud360_envios`; `medico_infos` idéntico; limpieza por id con los scripts de T4.
- [ ] **T11. Verificación de RF-17: la web abre la consulta creada con cada perfil.** RF-17 — M — Toca API Laravel: no (verificación a mano)
  - **A mano, con el usuario** (las credenciales las pone él en la sesión). Con la consulta creada por la API (T10) y médicos locales con
    perfiles 1, 2, 3, 4 y 9: abrirla en la web local y comprobar que no hay errores y que las secciones del perfil aparecen vacías y
    editables, y cargar un dato desde la web. Decidir y anotar si hace falta replicar `inicializarCampos` (perfil 2): si la web falla sin
    esas filas vacías, se agrega a `EscrituraService::crear` con su test; si no, se deja afuera.
  - Hecho cuando: resultado por perfil anotado en esta tarea (sin datos de pacientes); la decisión de `inicializarCampos` registrada;
    si se agregó, T10 vuelve a pasar sus pruebas y `medico_infos` sigue idéntico.
- [ ] **T12. Textos y examen físico: servicio, controlador y rutas.** RF-20, RF-21, RF-34, RF-69 — M — Toca API Laravel: sí
  - Código: `EscrituraService::secciones` y `::examen` (solo las claves presentes; fila `activo = 1` de mayor id o alta nueva;
    `""` se escribe como vaciado a propósito; el motivo lleva su `fecha`; `desconocidas` devuelve lo que no traduce) y las dos rutas.
    Última escritura gana por campo (la hora de llegada es la del servidor).
  - Hecho cuando: con PHP-base (modo estricto y sin él) y curl: lo escrito se lee de vuelta con `GET consultas/{id}` de la L; un campo
    ausente no cambia; `""` vacía el texto; el caso del ejemplo (la web cambió un campo a las 10:00, el dispositivo manda el suyo
    a las 10:05) deja el del dispositivo y un campo no enviado conserva el de la web; sobre una consulta cerrada, de la web, ajena o de otro
    médico → los códigos de RF-52/RF-53; `medico_infos` idéntico.
- [ ] **T13. Cerrar y dar de baja: servicio, controlador y rutas.** RF-22, RF-23, RF-26, RF-53 — M — Toca API Laravel: sí
  - Código: `EscrituraService::cerrar` (`activo = 1`, `created_at` = fecha clínica, edad; confirma **solo** exámenes complementarios e
    interconsultas con `activo = 2` de esa consulta; **no toca laboratorio ni peso**; repetir devuelve lo mismo) y `::baja` (`activo = 0`
    solo si está abierta y la creó la app; nunca `DELETE` físico), y las rutas.
  - Hecho cuando: PHP-base: un examen dado de baja (`activo = 0`) **no reaparece** al cerrar; un examen y una interconsulta pendientes
    pasan a `activo = 1`; laboratorio y peso quedan como estaban; cerrar dos veces = una; baja de una cerrada → `409 consulta_cerrada`;
    escritura posterior sobre una cerrada o dada de baja → `409`, salvo repetir una ya registrada (misma respuesta).
- [ ] **T14. Prueba de punta a punta del servidor de E1 y documentación.** RF-15, RF-16, RF-34, RF-52, RF-53 — M — Toca API Laravel: sí
  - Pasada completa con curl espaciado (~1,3 s): todos los códigos de las tareas T10 a T13 en un solo recorrido, `403` por rol y por
    consulta de la web/otro médico, repetición, 422, estricto/no estricto; `suma.sql` antes y después (solo cambian las tablas esperadas
    y `salud360_envios`); **`medico_infos` idéntico**, también tras crear, escribir y cerrar; grep estático (con `\b`) de `medico_infos`,
    `DELETE`/`delete(` físico y `Schema::` fuera de `EnviosRegistro` en los archivos de escritura; limpieza por id y por clave.
  - Docs: `md/API_SALUD360_CLINICA.md`, sección «Escritura (E1)» (rutas, cuerpos, códigos, repetibilidad, qué no hace, la tabla propia y
    el privilegio `CREATE`), sin tokens ni nombres; tabla «Código» y «Lo que viene» actualizadas.
  - Hecho cuando: phpunit completo en verde (el runner propio si el printer se cae); la copia servida igual al clon (comparación de
    contenido); cero escrituras de `medico_infos`; el registro de Apache sin métodos inesperados; `salud360_token_cache` vaciada.

## App: modelo y base
- [ ] **T15. Migración de la marca de histórico/propio (`consulta.propia`).** RF-8 (D26) — M — Toca API Laravel: no — **Migración: pregunta
  antes: ya aprobada en D26; reservar el número justo antes (plan.md); si otra rama lo tomó, volver a preguntar**
  - Tests primero: `core/data/src/jvmTest/.../repos/MigracionMarcaTest.kt` (como `MigracionHepatologiaTest`): una base de la versión
    anterior (v9) con consultas de clínica sucias, de pediatría y de la web, migrada por el driver: **todas las existentes quedan en 0
    (histórico)**; una consulta nueva guardada con `propia = true` queda en 1; `dirty`, `updated_at` y el resto intactos.
  - Código: la migración (`ALTER TABLE consulta ADD COLUMN propia INTEGER NOT NULL DEFAULT 0;`), `HistoriaClinica.sq` con la columna
    **al final** de `consulta`, `Consulta.propia` en `core/model` y `toRow`/`toModel` en `Mappers.kt`, y `marcarPropia(id)`.
  - Hecho cuando: el test en verde (antes, en rojo); `.\gradlew :core:data:jvmTest` completo en verde (ninguno existente cambia);
    `:composeApp:compileKotlinWasmJs -Pentorno=dev` compila; anotado en `MEMORY.md` el número reservado y que no hay vuelta atrás.
- [ ] **T16. Origen de la consulta, lista y «Nueva consulta» con la marca.** RF-8, RF-11, RF-62, RF-64, RF-65 — M — Toca API Laravel: no
  - Tests primero: common `OrigenDeConsultaTest` (propia / histórico / de la web; una propia abierta ya enviada es «Consulta en curso»
    y no «Abierta en la web»; una propia que otro dispositivo creó es «de la web», RF-64/RF-65); jvm `HcRepositoryTest` ampliado:
    `abrirConsulta` en clínica crea con `propia = 1` y **reutiliza** solo una propia abierta del mismo paciente y tipo, nunca una de la
    web ni una histórica.
  - Código: `hcgeneral/OrigenDeConsulta.kt`, `agruparConsultas` (`ConsultaWeb.kt`), consulta SQL `consultaAbiertaPropia`,
    `HcRepository.abrirConsulta`.
  - Hecho cuando: ambos tests en verde (antes, en rojo) y el `jvmTest` de `core:data` completo en verde; ningún `copy`/`fusionar` pierde
    `propia` (test que recorre cerrar, reabrir, anular y `fusionarConsulta`).
- [ ] **T17. Qué se envía y qué no: catálogo (D9) y candado por sección.** RF-3, RF-4 — M — Toca API Laravel: no
  - Tests primero: common `SeccionesEnviablesTest` (en esta versión se ofrecen textos y examen físico; el resto de la HC general
    «todavía no se envía»; nunca se ofrecen tabaquismo, FC, CA, diagnósticos, audio ni «antecedentes personales»; lo ya cargado de eso
    se conserva y se marca «No se envía a la web»); `especialidades/clinica` `LecturaClinicaCatalogoTest` **reescrito** (el catálogo ya
    no trae tabaquismo, FC, CA, diagnósticos, audio ni antecedentes personales en lo que se envía; las 25 secciones siguen decididas).
  - Código: `hcgeneral/SeccionesEnviables.kt` (por subentrega); `ClinicaMedica.kt` (cambio de requisitos sobre la 001: se declara en el
    commit y en `MEMORY.md`).
  - Hecho cuando: ambos tests en verde (antes, en rojo); `:especialidades:clinica:jvmTest` y `:core:data:jvmTest` en verde; lo cargado
    antes en el dispositivo no se borra (test con datos de tabaquismo existentes).
- [ ] **T18. «Cambio real» al guardar y datos por paciente asociados a la consulta.** RF-68, RF-69 — M — Toca API Laravel: no
  - Tests primero: common `CambioRealTest` (vacío sobre «nada» no es un cambio; vacío sobre texto es un vaciado a propósito;
    igual al anterior no cambia); jvm `DatosPorPacienteTest` (un dato por paciente es propio solo si su `consulta_id` apunta a una
    propia; los de histórico o sin consulta no viajan; editar una casilla/fila del histórico dentro de una propia la reasigna y la
    marca sucia, y viaja solo eso).
  - Código: `hcgeneral/CambioReal.kt`; `HcRepository.guardarValor/guardarSeccion` (clínica) y la reasignación de `consulta_id` en
    `guardarAntecedente/guardarRegistro` (laboratorio y pendiente lo harán en E3).
  - Hecho cuando: los tests en verde (antes, en rojo) y `jvmTest` completo en verde.

## App: datos
- [ ] **T19. API de escritura de E1 (DTOs y remota) con servidor falso.** RF-12 a RF-15, RF-20 a RF-22, RF-26 — M — Toca API Laravel: no
  - Tests primero: jvm `ClinicaEscrituraApiTest` con `com.sun.net.httpserver.HttpServer` en `127.0.0.1`: método, ruta y cuerpo de
    `crear` (con `clave`), `secciones`, `examen`, `cerrar`, `baja`; los 401/403/404/409/422/429/500 llegan con `status` y `codigo`; un
    solo pedido por error; JSON cortado. `PayloadsClinicaTest` (puro): los cuerpos llevan solo lo tocado, el vaciado a propósito como `""`,
    la fecha clínica y la edad que cargó el médico (no la del envío) y nunca un campo no tocado.
  - Código: `network/hc/ClinicaEscrituraApi.kt` y `HcClinicaEscrituraDtos.kt` (la remota recibe el `HcApiClient` completo).
  - Hecho cuando: el test en verde (antes, en rojo) y compila el wasm.
- [ ] **T20. Clasificación de errores, estados de envío y motivos que nombran la HC.** RF-6, RF-19, RF-30 — M — Toca API Laravel: no
  - Tests primero: common `ClasificacionDeEnvioTest`: causa ajena (401, 403 `hc_no_habilitada|medico_no_vinculado|licencia_vencida|
    fuera_de_cartera`, sin red, 5xx, 429, 503) → «En espera: <motivo con el nombre de la HC>»; causa del dato (422, 404 consulta, 409) →
    «No se pudo enviar»; precedencia de RF-19 (paciente de baja → consulta de baja → otro rechazo); persistencia de los estados en
    `paciente_extra` (clave `hce_estado_…`, `dirty = 0`) que sobrevive a reabrir la app.
  - Código: `core/model/.../EstadoEnvio.kt`, `hcgeneral/ClasificacionDeEnvio.kt`, `MotivoHc` ampliado.
  - Hecho cuando: el test en verde (antes, en rojo); `MotivoHcTest` existente sin cambios.
- [ ] **T21. Plan de envío puro: qué viaja, en qué orden y qué se queda.** RF-9, RF-56, RF-60, RF-68, RF-69 — M — Toca API Laravel: no
  - Tests primero: common `PlanDeEnvioTest`: solo consultas `propia = 1`; solo lo `dirty` (tocado); vaciado a propósito se envía; nunca
    un vacío no tocado; antecedentes desactivados y secciones no enviables no viajan **y no quedan pendientes ni figuran enviados**;
    orden creación → contenido → cierre; el cierre no sale mientras haya algo pendiente, «En espera» o rechazado.
  - Código: `hcgeneral/PlanDeEnvio.kt` (el reloj y las filas entran como parámetros).
  - Hecho cuando: el test en verde (antes, en rojo).
- [ ] **T22. `envioPropio` y `administraLicencia`; `HcApiSync` y `AdminRepository` los respetan.** RF-5, RF-66 — S — Toca API Laravel: no
  - Tests primero: jvm `HcApiSyncSoloLecturaTest` ampliado (un backend con `envioPropio` no recibe nada de `HcApiSync` ni se cuenta);
    `HcBackendsSoloLecturaTest` **reescrito** (cada backend registrado declara `soloLectura`, `envioPropio` y `administraLicencia`);
    `AdminRepositoryLicenciasTest` ampliado (médico solo de clínica: `guardarLicencia` devuelve `true`, **cero llamadas** al backend de
    clínica y la licencia local queda como hoy; pediatría igual que antes).
  - Código: `HcBackend.kt` (dos propiedades con valor por defecto), `HcApiSync.kt`, `AdminRepository.destinosDeLicencia`.
  - Hecho cuando: los tests en verde (antes, en rojo) y `:core:data:jvmTest` completo en verde (pediatría sin cambios).
- [ ] **T23. `HcClinicaBackend` pasa de solo lectura a lectura y escritura.** RF-1 — M — Toca API Laravel: no
  - Tests primero: jvm `HcClinicaBackendEscrituraTest` (declara `soloLectura = false`, `envioPropio = true`, `administraLicencia =
    false`; recibe `ClinicaEscrituraApi`; la lectura sigue haciendo solo `GET`); **se reescriben** `HcClinicaBackendSoloLecturaTest`
    (ya no puede exigir «ningún constructor recibe un cliente de escritura») y `DataModuleClinicaTest` (Koin real: el backend de
    clínica trae el cliente completo; el motor viejo no manda; licencias como antes).
  - Código: `HcClinicaBackend.kt`, `DataModule.kt`.
  - Hecho cuando: los tests en verde (antes, en rojo); la lectura de la L (`HcClinicaBackend*Test`, `HcClinicaLecturaE2ETest`) sigue verde.
- [ ] **T24. `HcClinicaEnvio`: cola, espera de 4 s y un envío por consulta a la vez.** RF-27, RF-28, RF-54 — M — Toca API Laravel: no
  - Tests primero: jvm `HcClinicaEnvioRitmoTest` con reloj falso: cambios cada 600 ms durante 30 s → **ningún** envío durante la
    escritura y **uno solo** a los 4 s del último cambio; abrir una consulta no hace pedidos; `HcClinicaEnvioConcurrenciaTest`: dos
    disparos de la misma consulta = un envío a la vez (el segundo espera y manda lo que quedó); consultas distintas sí en paralelo;
    common `ContadorDeEnvioTest` (`contarPendientes` cuenta todo tipo de dato de las propias, excluye el histórico y separa «En espera»
    de «No se pudo enviar»; RF-31).
  - Código: `sync/HcClinicaEnvio.kt` (scope inyectable, `Mutex` por consulta, `marcarSucia`, `empujarYa`, `contarPendientes`).
  - Hecho cuando: ambos tests en verde (antes, en rojo).
- [ ] **T25. Envío ordenado: crear → contenido → cierre; parcial, cambios en vuelo y atomicidad local.** RF-14, RF-15, RF-32, RF-55,
  RF-56, RF-61 — M — Toca API Laravel: no
  - Tests primero: jvm `HcClinicaEnvioOrdenTest` (la creación va primero con `clave = c:<id local>`; las secciones y el examen después;
    el cierre al final y **solo** cuando todo llegó; una parte rechazada o «En espera» frena el cierre); `HcClinicaEnvioRepetibleTest`
    (se pierde la respuesta de la creación → el reenvío repite la misma clave y queda una consulta); `HcClinicaEnvioAtomicoTest` (corte
    entre «el servidor confirmó» y «anotado»: nada queda pendiente ni duplicado; el id de la web y el `dirty = 0` se guardan en **una**
    transacción); parcial (se marca enviado solo lo confirmado); cambio en vuelo (se limpia solo lo que viajó comparando `updated_at`).
  - Código: `HcClinicaEnvio.enviar(consultaId)` con `PlanDeEnvio`, `ClinicaEscrituraApi` y `HcClinicaBackend`.
  - Hecho cuando: los tres tests en verde (antes, en rojo) con un servidor falso.
- [ ] **T26. Vínculo al enviar, «En espera» y sesión/médico.** RF-6, RF-13, RF-19, RF-50 — M — Toca API Laravel: no
  - Tests primero: jvm `VinculoAlEnviarTest` (primera consulta de un paciente sin ficha: resuelve el vínculo antes de enviar con
    `PacientesHcRepository`; ficha ambigua / documento inválido / ficha de otro paciente → «En espera: <motivo>» sin enviar;
    paciente dado de baja → «En espera», sin crear ficha); `SesionYEnvioTest` (la cola toma solo las consultas del médico de la
    sesión; otro médico en el mismo dispositivo no envía ni modifica lo del primero; `401` a mitad de un envío deja el resto «En espera:
    sesión vencida»).
  - Código: `HcClinicaEnvio` + `PacientesHcRepository.vinculo`.
  - Hecho cuando: los tests en verde (antes, en rojo).
- [ ] **T27. Reintentos, reintento periódico, rechazo y descarte.** RF-29, RF-30, RF-32 — M — Toca API Laravel: no
  - Tests primero: jvm `HcClinicaEnvioReintentoTest` (15 s, 60 s y 300 s, y después cada 300 s mientras la app esté abierta y haya algo
    pendiente o «En espera»; sin ráfaga con `429`; servidor caído un rato y vuelve → sale solo); `HcClinicaEnvioEsperaTest` (servidor falso:
    401, 403 `hc_no_habilitada|medico_no_vinculado|licencia_vencida|fuera_de_cartera`, 503, 429 y sin red dejan «En espera: <motivo con la
    HC>», sin perder ni modificar nada, y salen solos cuando cambia la causa); `HcClinicaEnvioRechazoTest` (422, 404,
    409: «No se pudo enviar» con el motivo, sin reintento automático, «Reintentar» a mano, «Descartar» → «Descartado: no se envió a la
    web» y el cierre sigue; no frena a las demás consultas).
  - Código: `HcClinicaEnvio` (estados persistentes con `ClasificacionDeEnvio`).
  - Hecho cuando: los tests en verde (antes, en rojo).
- [ ] **T28. Cerrar, reabrir y anular con estado de envío.** RF-22 a RF-26, RF-55 — M — Toca API Laravel: no
  - Tests primero: jvm `HcRepositoryCierreTest`: cerrar deja «Falta enviar» hasta confirmar; reabrir solo mientras el cierre no se
    envió **y** no está viajando («Enviando el cierre…»); anular sin haberse creado en la web = nada que enviar; anular una creada y
    abierta = descarta lo pendiente, espera el envío en curso y manda la baja; una cerrada con el cierre enviado no se reabre ni se
    anula.
  - Código: `HcRepository.cerrarConsulta/reabrirConsulta/anularConsulta` y su integración con `HcClinicaEnvio`.
  - Hecho cuando: el test en verde (antes, en rojo); `HcRepositoryGuardasDeLaWebTest` ajustado (las guardas por `hcc-…` siguen; las de
    `remoto_id` con backend de solo lectura ya no aplican a clínica).
- [ ] **T29. La lectura refresca lo propio y no duplica.** RF-33 — M — Toca API Laravel: no
  - Tests primero: jvm `HcClinicaBackendPropiasTest`: una propia ya enviada se refresca con lo que cambió en la web **salvo lo
    pendiente** (la regla de la L: lo sucio no se pisa, lo vacío no pisa); se ve una sola vez (no hay una fila `hcc-c…` más); empareja
    sus filas por `remoto_id`; si la web la dio de baja → «Ya no figura en la web» y no se recrea; **`propia` se conserva**.
  - Código: `HcClinicaBackend.aplicarLista` y `traerConsulta` (ya no tratan como intocable a la propia).
  - Hecho cuando: el test en verde (antes, en rojo); todos los `HcClinicaBackend*Test` de la L en verde.
- [ ] **T30. Cerrar el riesgo del motor viejo (`SyncEngine`).** NFR riesgo declarado, RF-7 — S — Toca API Laravel: no
  - Tests primero: `HcApiSyncBaseMigradaTest` (último test, el que documenta lo que empuja `SyncEngine`) **reescrito**: contra un servidor
    local que contesta 404, `sincronizar()` ya no manda las tablas clínicas de contenido y no limpia nada; las demás tablas siguen igual.
  - Código: `SyncAdapters.todos(db)` sin `CONSULTAS`, `SECCION_VALORES`, `EXAMEN_FISICO`, `ANTECEDENTES`, `REGISTROS`, `LABORATORIOS`,
    `ARCHIVOS`, `PENDIENTES`.
  - Hecho cuando: el test en verde (antes, en rojo); `:core:data:jvmTest` completo en verde; anotado en `AGENTS.md` (propuesta) que el
    riesgo quedó cerrado.
- [ ] **T31. Disparadores: abrir la app, iniciar sesión y recuperar la conexión (web y Android).** RF-57 — M — Toca API Laravel: no
  - Tests primero: jvm `HcClinicaEnvioDisparadoresTest` con un observador falso: `alAbrirOIniciar()` y `alRecuperarConexion()` pasan
    por la **misma espera de 4 s**, nunca por el ritmo del autoguardado, y no corren en paralelo con un envío en curso.
  - Código: `sync/ObservadorDeConexion.kt` (interfaz común) + `expect/actual` de `wasmJs` (evento `online`) y `android`
    (`ConnectivityManager`), registro en `DataModule`/`AppDi`, y `App.kt` que llama a `alAbrirOIniciar()` con la sesión.
  - Hecho cuando: el test en verde (antes, en rojo); compilan `:core:data:compileKotlinJvm`, `:composeApp:compileKotlinWasmJs
    -Pentorno=dev` y `:androidApp` si el entorno lo permite; prueba manual: modo avión y vuelta en el navegador.
- [ ] **T32. Observador de conexión de iOS (a mano, en macOS).** RF-57 — S — Toca API Laravel: no
  - `actual` de iOS con `NWPathMonitor`. **En Windows los targets iOS se saltean: se escribe y se verifica solo en la Mac.**
  - Hecho cuando: compila en macOS; el usuario lo prueba en el simulador (modo avión y vuelta); anotado si queda pendiente.
- [ ] **T33. Histórico: «Enviar a la web» consulta por consulta.** RF-7, RF-9, RF-10, RF-63, RF-67 — M — Toca API Laravel: no
  - Tests primero: common `HistoricoDelDispositivoTest` (`puedeEnviarse` solo si todas las secciones **con datos** —no vacías, sin filas
    de baja ni antecedentes desactivados, con los datos por paciente asociados— tienen envío habilitado; si no, la lista de las que faltan;
    qué no viajará; solo su dueño); jvm `EnviarHistoricoTest` sobre la base de `HcApiSyncBaseMigradaTest` migrada: **cero pedidos** al
    abrir la app, iniciar sesión, volver la conexión y reintentar; «Enviar a la web» (con la confirmación ya dada) pone `propia = 1`
    y recién entonces se envía; una abierta se envía abierta; sin conexión queda pendiente con la confirmación dada; no cuenta como
    pendiente de envío mientras no se confirme.
  - Código: `hcgeneral/HistoricoDelDispositivo.kt`, `HcRepository.enviarHistorico(consultaId)`.
  - Hecho cuando: los tests en verde (antes, en rojo).
- [ ] **T34. Aviso de consulta abierta en la web.** RF-18, RF-59 — S — Toca API Laravel: no
  - Tests primero: common `AvisoDeAbiertaTest` (dato fresco = lista leída hace menos de un minuto con el reloj inyectado; avisa por una
    abierta de la web de cualquier médico con su nombre; **no** por una propia abierta de otro tipo; sin dato fresco o sin conexión no
    avisa ni bloquea, y el aviso sale al enviar con `otras_abiertas`).
  - Código: `hcgeneral/AvisoDeAbierta.kt`.
  - Hecho cuando: el test en verde (antes, en rojo).
- [ ] **T35. Licencia de la HC general en el ingreso (D22/D25).** RF-58 — M — Toca API Laravel: no
  - Requiere T6 y T7. Tests primero: jvm `AuthRepositoryLicenciaClinicaTest` con servidores falsos de pediatría y clínica: **todas** las
    HC con licencia contestan «vencida con fecha» → no entra; una vigente, sin fila o sin respuesta concluyente → entra y la vencida
    queda frenada; médico solo de clínica: vencida no entra, sin fila entra, vigente entra, sin respuesta entra (no hay copia local de
    clínica); una licencia que vence con la sesión abierta no bloquea (lo pendiente queda «En espera»).
  - Código: `HcClinicaBackend.licenciaDelServidor()` (`auth/perfil` y `403 licencia_vencida` con `licencia: null`), `decidirLicencia` por
    HC con la regla de D25, el texto «licencia vencida de <HC>» (enmienda RF-18 de la 004).
  - Hecho cuando: el test en verde (antes, en rojo); los tests de licencia de la 004 (T1 a T7) siguen en verde.

## App: interfaz
- [ ] **T36. `ConsultaViewModel`: la propia es editable y envía por `HcClinicaEnvio`.** RF-2, RF-24, RF-27, RF-62 — M — Toca API Laravel: no
  - La lógica ya está testeada en `core/data`; acá solo se cablea. `soloLecturaWeb` vale solo para `hcc-…`/de la web; en una propia o un
    histórico local el autoguardado llama a `HcClinicaEnvio.marcarSucia` (nunca a la API); `ConsultaUi` expone el estado de envío.
  - Hecho cuando: `:features:hc:compileKotlinJvm` y `:composeApp:compileKotlinWasmJs -Pentorno=dev` compilan; `:core:data:jvmTest` completo
    en verde; revisión de código: ninguna ruta del autoguardado llama a la API.
- [ ] **T37. `ConsultaScreen`: estados, acciones y textos.** RF-3, RF-4, RF-24, RF-30, RF-55 — M — Toca API Laravel: no
  - Banner de estado («Falta enviar», «En espera: …», «No se pudo enviar: …» con «Reintentar» y «Descartar», «Descartado: no se envió
    a la web», «Enviando el cierre…»), secciones sin envío («Esta sección todavía no se envía a la web»), lo que la web no guarda
    («No se envía a la web»), sin «Reabrir» una vez enviado el cierre. Una sola tabla de textos (constantes de `core/data`).
  - Hecho cuando: compilan `:features:hc` y `:composeApp:compileKotlinWasmJs`; revisión visual en `devWeb` (otro puerto) en la prueba manual.
- [ ] **T38. `HistoriaClinicaScreen`: grupos, «Enviar a la web» y contador.** RF-8, RF-10, RF-18, RF-31, RF-62, RF-64, RF-65, RF-67 — M — Toca API Laravel: no
  - «Consulta en curso» (propias abiertas), «Solo en este dispositivo» con el botón y el diálogo de confirmación (paciente, fecha, estado,
    secciones, lo que no viajará, ficha), o la lista de secciones que faltan; «Abierta en la web»; el contador de pendientes de clínica;
    el aviso previo de abierta.
  - Hecho cuando: compilan los módulos; lista de pasos anotada para la prueba manual.
- [ ] **T39. Elegir ficha desde «En espera» y cerrar sesión con pendientes.** RF-13, RF-50, RF-51 — S — Toca API Laravel: no
  - Reutiliza `ElegirFichaPaciente` (003) desde la consulta «En espera: elegí la ficha»; diálogo de cierre de sesión con la cantidad de
    consultas sin enviar (quedarse e intentar, o cerrar igual sin borrar nada).
  - Hecho cuando: compilan los módulos; `SesionYEnvioTest` (T26) cubre la lógica; pasos manuales anotados.

## Pruebas y cierre de E1
- [ ] **T40. E2E de la escritura de E1 contra la copia local.** RF-12 a RF-16, RF-20 a RF-23, RF-26, RF-34, RF-52, RF-53 — M — Toca API Laravel: no
  - `HcClinicaEscrituraE2ETest` en `core/data/src/jvmTest` (**se saltea sin las propiedades** `-Psalud360.test.hc.clinica`,
    `.clinica.token`, `.clinica.dni`…; agregarlas a `core/data/build.gradle.kts`): sobre SQLite temporal y la copia local, con el
    paciente «PRUEBA SALUD360» **creado por la propia prueba** (documento libre verificado): crea la consulta, escribe textos y examen,
    **repite** el envío (una sola consulta), cierra, la lee de vuelta con la API de lectura y compara; un vaciado a propósito; una
    escritura sobre la cerrada y sobre una consulta de la web → rechazadas. Pedidos espaciados 1,1 s. El KDoc trae el comando y el
    SQL de limpieza.
  - Hecho cuando: en verde contra la copia servida; `suma.sql` antes/después igual salvo lo esperado y **`medico_infos` idéntico
    (puerta bloqueante, RF-16)**; limpieza por id y por clave con los scripts de T4; sin propiedades, `:core:data:jvmTest` en verde y el
    E2E se saltea; token revocado y `salud360_token_cache` vacía (el registro de repetibilidad **no** se vacía: solo sus filas de prueba,
    por clave).
- [ ] **T41. Regresión: pediatría, lectura de la L y compilaciones.** RF-5 — S — Toca API Laravel: no
  - Hecho cuando: `.\gradlew :core:data:jvmTest --rerun` completo, `:especialidades:clinica:jvmTest`, `:features:hc:compileKotlinJvm`,
    `:composeApp:compileKotlinWasmJs -Pentorno=dev` en verde; `HcPediatriaE2ETest` y `HcCarteraE2ETest` con `--rerun` contra la copia local
    de pediatría en verde (conteos de sus 55 tablas idénticos tras limpiar); `HcClinicaLecturaE2ETest` en verde.
- [ ] **T42. Comprobación de solo lectura de licencias de clínica antes de publicar.** RF-58 (D25) — S — Toca API Laravel: no
  - **Tarea del plan, no decisión.** `SELECT` de solo lectura sobre la copia local: cuántos médicos con la HC general tienen la licencia
    vencida o sin fila (quedarían afuera o con la HC frenada). En producción, **solo con la aprobación de producción**. Sin nombres ni datos
    de pacientes en el resultado.
  - Hecho cuando: el conteo anotado en esta tarea; el usuario lo conoce antes de publicar.
- [ ] **T43. Validación del reviewer de E1.** RF-1 a RF-34, RF-50 a RF-69 — S — Toca API Laravel: no
  - RF por RF: qué test lo cubre y el resultado (`plan.md`, «Estrategia de tests»); lista de los candados con su test; los tests de la L
    reescritos y por qué; el riesgo de `SyncEngine` cerrado; contradicciones y riesgos del plan resueltos o anotados.
  - Hecho cuando: el reviewer aprueba o devuelve observaciones y quedan cerradas o anotadas.
- [ ] **T44. Prueba manual del usuario en `devWeb` (E1).** RF-8, RF-10, RF-12 a RF-14, RF-17, RF-18, RF-24 a RF-26, RF-29 a RF-31, RF-50, RF-51, RF-57, RF-58, RF-62, RF-64, RF-65, RF-67 — S — Toca API Laravel: no
  - Copia local, **otro puerto** que el publicado, paciente «PRUEBA SALUD360»: (1) crear una consulta, escribir y ver en la web
    lado a lado; (2) modo avión: escribir, volver, una sola consulta en la web; (3) cerrar y ver «Falta enviar» → cerrada en la web;
    (4) no se puede reabrir una cerrada ya enviada; (5) histórico: no sale solo; «Enviar a la web» pide confirmación, muestra lo que
    no viaja y, si faltan secciones, la lista; (6) licencia: médico de prueba con licencia vencida/sin fila (script con restauración);
    (7) un rechazo (dato inválido) deja «No se pudo enviar» con «Reintentar»/«Descartar»; (8) otro médico en el mismo dispositivo no
    envía lo del primero; cerrar sesión con pendientes avisa; (9) aviso de abierta en la web; (10) en Android/iOS el observador de
    conexión (iOS en macOS).
  - Hecho cuando: el usuario confirma cada paso o anota qué falla; `limpiar` aplicado; token revocado.
- [ ] **T45. Limpieza de E1 y respaldo.** D4 — S — Toca API Laravel: no
  - Con los scripts de T4: lo que dejaron T10 a T14 y T40 a T44, por id y por clave; conteos y sumas de las 68 tablas contra la línea
    base de T5.
  - Hecho cuando: todo igual a la línea base salvo `salud360_envios` (con 0 filas de prueba) y la licencia de prueba restaurada.
- [ ] **T46. Documentación y memoria de E1.** — S — Toca API Laravel: no
  - `md/API_SALUD360_CLINICA.md` al día; `docs/ARQUITECTURA.md` (escritura de la HC general); `MEMORY.md` (sin datos sensibles); tabla de
    `specs/README.md`; propuesta de cambios permanentes para `AGENTS.md`.
  - Hecho cuando: el usuario aprueba el cierre de E1.
- [ ] **T47. Cierre de E1: decisión del usuario sobre E2.** — S — Toca API Laravel: no
  - Hecho cuando: el usuario aprueba el cierre de E1 y autoriza detallar E2.

---

# E2 a E5 — lista de tareas (se detallan al llegar a cada subentrega, con aprobación aparte)
No llevan «Hecho cuando» detallado todavía. Cada subentrega se detalla con lo que enseñe la anterior y antes de empezarla.

## E2 — Formularios y listas sin migración (RF-35 a RF-40, RF-70)
- E2-1. Servidor: reglas puras de formularios, antecedentes familiares y registros (con baja) + phpunit. RF-35 a RF-40, RF-70.
- E2-2. Servidor: formularios (alimentación, serologías, Child-Pugh) con `0|1|2` correctos. RF-35, RF-37, RF-38.
- E2-3. Servidor: antecedentes familiares mezclados con la última fila activa, sin tabaquismo. RF-36.
- E2-4. Servidor: registros (los 5 estudios, exámenes complementarios, interconsultas) con ids y **baja de exámenes e interconsultas
  (capacidad nueva)**; límite de cardiológicos. RF-39, RF-40, RF-70.
- E2-5. Servidor: el cierre confirma exámenes e interconsultas nuevos y no reactiva los de baja (ajuste de T13). RF-22, RF-23.
- E2-6. Servidor: prueba de punta a punta, `medico_infos` intacto, documentación.
- E2-7. App: API de formularios, antecedentes y registros + DTOs. RF-35 a RF-40.
- E2-8. App: `SeccionesEnviables` habilita alimentación, serologías, Child-Pugh, antecedentes y listas. RF-4.
- E2-9. App: antecedentes (solo activos, solo lo tocado) y límite de 191 en cardiológicos. RF-36, RF-39.
- E2-10. App: borrar examen/interconsulta = baja; cierre. RF-70.
- E2-11. App: refresco y emparejamiento por `remoto_id` de las listas. RF-33.
- E2-12. E2E de E2; regresión; reviewer; prueba manual; limpieza.

## E3 — Laboratorio, peso y pendientes (RF-41 a RF-43) — con la migración de E3 (D8)
- E3-1. **Migración** del identificador de la web en laboratorio y pendiente (reservar número mirando todas las ramas; **pregunta antes: ya
  aprobada en D8**; si otra rama lo tomó, volver a preguntar). RF-41, RF-43.
- E3-2. Servidor: laboratorio general/reducido y peso (visibles al instante, fecha de la consulta, baja) + phpunit. RF-41, RF-42.
- E3-3. Servidor: texto único de pendientes. RF-43.
- E3-4. Servidor: prueba de punta a punta, repetibilidad de las filas hijas, `medico_infos` (la web escribe `es_nueva_consulta = 8` al cargar
  laboratorio y peso: la API no lo replica). RF-15, RF-16.
- E3-5. App: API de laboratorio, peso y pendientes. RF-41 a RF-43.
- E3-6. App: laboratorio y peso (cargas, borrar = baja, fecha de la consulta, reasignar al editar). RF-41, RF-42, RF-68.
- E3-7. App: `PendientesTexto` (uno por línea, fila portadora, huella) y emparejamiento al leer. RF-43, RF-33.
- E3-8. App: sin «Resuelto» en pendientes que se envían. RF-43.
- E3-9. App: `SeccionesEnviables` habilita laboratorio, peso y pendientes; el histórico con esas secciones pasa a poder enviarse. RF-4, RF-67.
- E3-10. E2E de E3; regresión; reviewer; prueba manual; limpieza.
- E3-11. Cierre de E1 a E3: **recién acá puede pedirse la publicación (D23), con aprobación aparte y el servidor ya en producción.**

## E4 — Adjuntos (RF-44 a RF-46)
- E4-1. Verificar en la copia local que la web muestra las fotos de exámenes complementarios (D24); decidir su alcance. RF-44.
- E4-2. Servidor: PDF (subir, bajar, baja), límite del hosting leído sin escribir, subida repetible. RF-44 a RF-46.
- E4-3. Servidor: fotos de exámenes complementarios (si D24 lo confirma). RF-44.
- E4-4. App: subir adjuntos (una sola vez, subida cortada sin duplicar), nunca de otra HC ni sin consulta. RF-44, RF-46.
- E4-5. App: listar y abrir adjuntos de la web con la sesión. RF-45.
- E4-6. E2E de E4; regresión; reviewer; prueba manual; limpieza.

## E5 — Aviso de vencimiento y panel de licencias (RF-47 a RF-49)
- E5-1. Servidor: leer y guardar la licencia de cada médico para la HC general. RF-47, RF-48.
- E5-2. App: `HcClinicaBackend.avisoDeLicencia()` y el cartel de «por vencer». RF-47.
- E5-3. App: panel de licencias con una licencia por médico y por HC, sin copia local por HC; `administraLicencia = true` para clínica
  (cierra el RF-66 de E1). RF-48.
- E5-4. E2E con la licencia de prueba movida y restaurada; reviewer; prueba manual; limpieza.

## Producción — **E9 de la escritura: aprobación aparte**
No se detalla. Requiere: respaldo restaurable verificado, paciente y médico de prueba que indique el usuario, tabla de repetibilidad
aprobada, comprobación sin token, `APP_DEBUG=false`, lectura de los límites del hosting (D18) y la comprobación de licencias (T42).
Después, y solo con aprobación aparte, la publicación de la app (E1 a E3 cerradas).
