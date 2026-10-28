# Tareas 001 — Perfiles configurables de la HC general

Plan: `plan.md` (aprobado el 2026-10-09). Las tareas van por tandas; cada tanda se aprueba antes de generar la siguiente.
Reglas para todas: commits locales como `rodri89` (ya configurado), **sin push**; nada contra producción; ningún
dato real, contraseña ni token en este repo (los valores de prueba se anotan solo en las máquinas locales).

## Tanda A1 — Entorno local, base de la API de hclinica y endpoint de turnos

Estado verificado al generar la tanda: el MySQL de XAMPP (MariaDB 10.1) ya tiene `hc_clinica` (61 tablas, igual que
el volcado), `turnosonlinebb` y `tobb`; Apache responde; `htdocs\TurnosOnlineBB-salud360` responde
`POST .../public/index.php/api/salud360/auth/login` y no tiene git.

- [x] **T1. Clon de hclinica, rama y copia servida.** Entorno (fase 0)
  - Clonar `rodri89/hclinica` en `C:\Users\flor\rebonline\hclinica` y crear la rama `salud360-api` desde `main`
    (no tocar `C:\Users\flor\hclinica` ni `htdocs\HClinica`).
  - Copiar a `C:\xampp\htdocs\HClinica-salud360` (con `vendor/` copiado de `C:\Users\flor\hclinica`, no enlazado) y un
    `.env` local: base `hc_clinica` del XAMPP, `APP_DEBUG=true` solo acá.
  - Hecho cuando: `git -C rebonline\hclinica branch --show-current` dice `salud360-api` y
    `http://localhost/HClinica-salud360/public/index.php/login` responde 200.

- [x] **T2. Turnos local al día y con acceso de prueba.** Entorno (fase 0)
  - Comparar `htdocs\TurnosOnlineBB-salud360` con `main` de `rebonline\turnosonlinebb` (API `Api/Salud360`, rutas,
    `config`) y actualizar la copia servida si está atrasada (respaldo de la copia antes de pisar).
  - En la base local `turnosonlinebb`: una contraseña de prueba para un médico que esté vinculado en `hc_clinica`
    (`users.medico_id_tobb`) y para un administrador, y la HC `clinica` habilitada a ese médico en
    `salud360_medico_hc`. Los valores no se anotan en este repo.
  - Hecho cuando: `POST auth/login` de turnos local con el médico de prueba devuelve un token y su perfil trae
    `historias_clinicas` con `clinica`; lo mismo para el administrador con `rol: admin`.

- [x] **T3. La app contra todo local.** Entorno (fase 0) (login en navegador probado por el usuario el 2026-10-09)
  - Confirmar el comando de `devWeb` con `-Psalud360.entorno.dev.turnos=...` (turnos local) y
    `-Psalud360.entorno.dev.hc.clinica=http://localhost/HClinica-salud360/public/index.php`, sin tocar
    `gradle.properties`, y anotarlo en `AGENTS.md` (sección de comandos) y en el plan.
  - Hecho cuando: con `.\gradlew devWeb` y esos `-P`, el login en http://localhost:8080 entra con el médico y con el
    administrador de prueba, y el login muestra el entorno `dev`.

- [x] **T4. Configuración y CORS de la API en hclinica.** RNF solo el administrador (base)
  - En `rebonline\hclinica` (rama `salud360-api`): `config/salud360.php` (copia de pediatría con
    `hc_codigos = ['clinica', 'hepatologia']`), `app/Http/Middleware/Salud360Cors.php` registrado primero en el
    `$middleware` global de `app/Http/Kernel.php`. Copiar a la copia servida.
  - Hecho cuando: `curl -X OPTIONS .../api/salud360/auth/perfil` responde 204 con `Access-Control-Allow-Origin: *` y
    el encabezado `X-Salud360-Token` permitido, y la web de hclinica sigue entrando normal.

- [x] **T5. Validación del token de turnos.** RF-29 (servidor), RNF solo el administrador
  - `app/Services/Salud360/TobbAuthService.php` y `app/Http/Middleware/Salud360Api.php` (alias `salud360`), copiados
    de pediatría, con: acepta el médico si turnos le habilita **cualquiera** de `hc_codigos`; admin = `rol: admin` →
    usuario `usuario_tipo = 1`; licencia con `medico_licencias` igual que la web; **arreglo del modo "turnos caído"**
    (resolver por el `user_id` de la caché, incluido el admin, y pasar siempre por la licencia); tablas
    `salud360_token_cache` y `salud360_vinculos_pendientes` en caliente. `SALUD360_TOBB_URL` = turnos local.
  - Hecho cuando: contra la copia servida, sin token → `401 sin_token`; token inventado → `401 token` (no
    `tobb_caido`); médico sin `clinica` ni `hepatologia` habilitada → `403 hc_no_habilitada`.

- [x] **T6. `auth/perfil` y rutas.** RF-29 (servidor), RNF solo el administrador
  - `app/Http/Controllers/Api/Salud360/Salud360Controller.php` (base: respuestas `{ok, mensaje, codigo}`, rol por
    `usuario_tipo`, sin tocar nunca `medico_infos`), `AuthController@perfil`, y `routes/api.php` con el grupo
    `salud360`. `md/API_SALUD360_CLINICA.md` con lo hecho hasta acá.
  - Hecho cuando: con el token del médico de prueba → 200 con `rol: medico`, `medico_id_tobb` y `licencia`; con el del
    administrador → 200 con `rol: admin` y sin licencia; con licencia vencida del médico (cambiada en la copia local y
    restaurada después) → `403 licencia_vencida`; con `SALUD360_TOBB_URL` apuntando a un puerto muerto y el token ya
    en caché → sigue entrando el médico y el administrador, y la licencia vencida sigue bloqueando.

- [x] **T7. Endpoint de turnos para editar las HC de un médico.** RF-18 (servidor)
  - En `rebonline\turnosonlinebb`, rama nueva `salud360-hc-general` desde `main`: `PUT /api/salud360/medicos/{id}/historias-clinicas`
    `{historias_clinicas: [...]}`, solo administrador, que reemplaza las filas de `salud360_medico_hc` de ese médico
    con el mismo criterio que `admin_hc_medico` de la web; códigos fuera del catálogo → 422. Documentarlo en
    `md/API_SALUD360.md`. Copiar a la copia servida.
  - Hecho cuando: con el token del administrador, el `PUT` cambia las filas y el `auth/perfil` de turnos del médico
    devuelve las nuevas `historias_clinicas`; con el token de un médico → 403; con un código inventado → 422.

Al terminar la tanda: `MEMORY.md` con el estado del entorno (sin credenciales) y propuesta de la tanda A2.

## Tanda A2 — Modelo y API de configuración en hclinica

Fase 2 del plan (secciones 2, 3, 6, 9 y 11): RF-1 a RF-12, RF-15, RF-17, RF-31 y RF-32, lado servidor. Todo en
`rebonline\hclinica` (rama `salud360-api`), un commit local por tarea como `rodri89`, **sin push**, y copiado a
`C:\xampp\htdocs\HClinica-salud360` antes de probar (`php -l` de cada archivo tocado). Si se toca `config/` o `.env`:
`php artisan config:cache` en las dos copias servidas.

Reglas de prueba de la tanda:
- URL base: `http://localhost/HClinica-salud360/public/index.php/api/salud360/...`; tokens del médico y del
  administrador de prueba sacados de `auth/login` de turnos local, sin escribir credenciales en ningún repo.
- La licencia del médico de prueba (`users.id` 9, perfil web 2) está vencida a propósito: para probar como médico,
  extenderla y al terminar restaurarla con `UPDATE` puntual (incluidos `activo` y `updated_at`, ver `MEMORY.md`).
- Los perfiles y asignaciones de prueba se borran con `DELETE` puntuales al terminar cada tarea (nunca `DROP TABLE`);
  lo único que queda en la base local es lo que deja la carga inicial (T12).
- phpunit corre en la copia servida (el clon no tiene `vendor/`; PHPUnit 7.5):
  `C:\xampp\php\php.exe vendor\bin\phpunit tests\Unit\PerfilesTest.php`. El test extiende
  `PHPUnit\Framework\TestCase` (sin la app de Laravel ni base).

- [x] **T8. Catálogo de secciones y tablas en caliente.** RF-1, RF-2 (base), RF-16
  - `app/Services/Salud360/CatalogoSecciones.php`: constante `SECCIONES` con los 24 ids de la tabla del plan
    (sección 2), en ese orden, con `nombre` y `descripcion`, más `ids()`/`existe($id)`.
  - `app/Services/Salud360/PerfilesService.php` con `asegurarTablas()` (`Schema::hasTable`/`Schema::create`, como
    `TobbAuthService::asegurarTablas`): `salud360_perfiles`, `salud360_perfil_pestanias`, `salud360_perfil_secciones`,
    `salud360_perfil_medicos` y `salud360_cargas` con las columnas y claves del plan (InnoDB, sin claves foráneas).
  - `ConfigController@catalogo` (`GET config/catalogo`, solo admin: médico → `403 sin_permiso`) y la ruta en
    `routes/api.php`; los endpoints de configuración llaman a `asegurarTablas()` al empezar.
  - Hecho cuando: con el token del administrador → 200 con `secciones` de 24 elementos `{id, nombre, descripcion}`
    en el orden del plan; con el del médico → `403 sin_permiso`; `SHOW CREATE TABLE` de las cinco tablas muestra los
    `unique` (`nombre`; `perfil_id`+`nombre`; `perfil_id`+`seccion`; `medico_id_tobb`; `nombre`) y un segundo pedido
    no falla (tablas ya creadas).

- [x] **T9. Validación de perfiles (pura) con phpunit.** RF-2, RF-4, RF-8, RF-9
  - `PerfilesService::validar(array $perfil, array $otrosNombres): array` sin base: nombres de perfil, pestaña y
    sección no vacíos y ≤ 60 (`mb_strlen` después de `trim`); al menos una sección; nombre de perfil no usado por otro
    (sin mayúsculas ni espacios de más); pestañas sin nombres repetidos en el perfil; cada sección del catálogo, una
    sola vez y con al menos un tipo. Cada error `{campo, mensaje}` con el lugar (`nombre`, `pestanias.1.nombre`,
    `pestanias.1.secciones.0`) y qué corregir.
  - `tests/Unit/PerfilesTest.php` con un caso por regla (válido sin errores; vacío; 61 caracteres; nombre repetido
    con otra capitalización; pestaña repetida; sección inventada; sección dos veces en pestañas distintas; sección sin
    tipos; perfil sin secciones) y que cada error traiga el `campo` esperado.
  - Hecho cuando: phpunit de `PerfilesTest.php` en verde en la copia servida y `php -l` sin errores.

- [x] **T10. CRUD de perfiles (administrador).** RF-3 a RF-12, RF-15
  - `PerfilController`: `GET config/perfiles` (`{perfiles: [{id, nombre, medicos}]}`), `GET config/perfiles/{id}`,
    `POST config/perfiles` (201), `PUT config/perfiles/{id}` y `DELETE config/perfiles/{id}`, solo admin
    (`403 sin_permiso`). Perfil = `{id, nombre, pestanias: [{nombre, secciones: [{seccion, nombre, consulta,
    telemedicina}]}]}`; el orden es el del array. Errores de `validar` → `422 validacion` con `errores`.
  - `PUT`: valida primero; reemplazo total en `DB::transaction` con `lockForUpdate` sobre la fila del perfil (borra
    pestañas y secciones y las vuelve a insertar), conserva el `id`. `DELETE` con médicos asignados →
    `409 perfil_con_medicos` `{medicos: n}`. Id inexistente → `404 no_encontrado`.
  - Hecho cuando, con curl y el token del administrador: `POST` de un perfil de prueba con dos pestañas y tres
    secciones → 201 y el `GET {id}` lo devuelve igual y en orden; `POST` con el mismo nombre en otra capitalización y
    con una sección inventada → 422 con el `campo` correcto; `PUT` que mueve una sección de pestaña, la renombra y
    cambia tipos → mismo `id` y el `GET` refleja todo; dos `PUT` seguidos → el `GET` devuelve el último completo;
    `PUT`/`DELETE` de un id inexistente → 404; `DELETE` con una fila de prueba en `salud360_perfil_medicos`
    (insertada y borrada por SQL) → 409 con `medicos: 1`, y sin ella → `{ok: true}` y no quedan filas del perfil en
    las tres tablas; con el token del médico → 403 en los cinco.

- [x] **T11. Asignaciones y configuración del médico.** RF-10, RF-17 (y la base de RF-19/RF-20 para la tanda B)
  - `GET config/asignaciones` (admin): `{asignaciones: [{medico_id_tobb, nombre, vinculado, activo, perfil_id, perfil_web}]}`
    (`activo` es null si no está vinculado) = médicos de `users` (`usuario_tipo = 2`, `medico_id_tobb <> 0`) + asignaciones de números todavía no vinculados.
  - `PUT config/asignaciones/{medicoIdTobb}` (admin) `{perfil_id: n | null}` → `{asignacion}`; `null` borra la fila;
    perfil inexistente → 404; número no entero positivo → 422. Acepta números no vinculados (para el alta, RF-18).
  - `ConfigController@config` (`GET config`, solo médico; admin → `403 sin_permiso`): `{perfil: {...} | null}` del
    perfil asignado a su `medico_id_tobb`, pestañas y secciones en orden.
  - Hecho cuando: el médico de prueba sin asignación → `GET config` da `perfil: null`; el administrador le asigna un
    perfil de prueba → el médico lo recibe con esos nombres y orden; un `PUT` del perfil → el médico ve el cambio con
    el mismo `id` (RF-10); `GET config/asignaciones` lista al médico con `vinculado: true`, `perfil_web: 2` y el
    perfil, y un número inventado asignado aparece con `vinculado: false`; `perfil_id: null` lo borra; perfil
    inexistente → 404, número `abc` → 422. Al terminar: sin asignaciones ni perfiles de prueba y licencia restaurada.

- [x] **T12. Carga inicial de perfiles.** RF-31, RF-32
  - `app/Services/Salud360/CargaInicialPerfiles.php`, llamada por `asegurarTablas()`: si `salud360_cargas` tiene
    `perfiles_iniciales`, no hace nada; si no, en una transacción crea "Clínica general" y "Hepatología" (solo si no
    existe un perfil con ese nombre) con las pestañas "Antec. Familiares" y "Consulta" y las secciones, tipos y orden
    de la tabla de la spec (datos subjetivos y objetivos antes de exámenes complementarios), definidos como array en
    la clase; asigna con `perfilPara(int $perfilWeb)` (pura: 2 → Hepatología; 1, 5, 9 → Clínica general; resto →
    ninguno) a `usuario_tipo = 2` con `medico_id_tobb <> 0` sin asignación previa (`WHERE NOT EXISTS`); inserta el marcador.
  - `PerfilesTest.php` suma `perfilPara` (1, 2, 5, 9, 3, 4, 0) y que los dos perfiles del array pasen `validar`.
  - Hecho cuando: phpunit en verde; después del primer pedido del administrador, SQL sobre `hc_clinica`: "Clínica
    general" tiene 1 + 11 secciones (Antec. Familiares: `antecedentes_familiares` C+T; Consulta: `datos_subjetivos` T,
    `datos_objetivos` T, `alimentacion`, `diuresis_catarsis`, `somnia`, `habitos`, `examen_fisico` C,
    `examenes_complementarios`, `interconsulta`, `conductas` C+T, `nota` C) y "Hepatología" 1 + 16 (Consulta:
    `motivo_consulta`, `documentos`, `laboratorio_general`, `eco_abdominal`, `serologias`, `tabla_peso`, `child_pugh`,
    `veda`, `biopsia_hepatica`, `estudios_cardiologicos`, `tc_rmn` C; `datos_subjetivos`, `datos_objetivos`,
    `examenes_complementarios`, `interconsulta`, `conductas` T), en ese orden y sin `laboratorio_reducido`; el agrupado
    `users.perfil` → perfil asignado da 2 → Hepatología, 1/5/9 → Clínica general y el resto sin asignación; el médico
    de prueba (`users.id` 9) queda en Hepatología. Un segundo pedido no cambia nada, y borrando solo el marcador y
    pidiendo otra vez no se duplican perfiles, pestañas, secciones ni asignaciones (mismos ids y conteos).

- [x] **T13. Documentación y cierre de la API de configuración.** RF-1 a RF-12, RF-15, RF-17, RF-31, RF-32
  - `md/API_SALUD360_CLINICA.md`: tabla "Código" con los archivos nuevos; endpoints `config`, `config/catalogo`,
    `config/perfiles[/{id}]` y `config/asignaciones[/{medicoIdTobb}]` con quién, request/response de ejemplo
    (sin datos reales), errores (`sin_permiso`, `no_encontrado`, `validacion` con `errores`, `perfil_con_medicos`),
    tablas en caliente, carga inicial y su marcador, cómo correr phpunit y curl de ejemplo; quitar lo ya hecho de
    "Lo que viene".
  - Hecho cuando: `PerfilesTest.php` en verde, `php -l` de todos los archivos de la tanda sin errores, la copia
    servida igual al clon (sin contar `.env`/`vendor/`), una pasada de curl de humo por cada endpoint con el código
    documentado, y el `.md` no tiene tokens, contraseñas ni nombres de médicos.

Al terminar la tanda: validación del reviewer y MEMORY.md.

Validada por el reviewer el 2026-10-10: APROBADO.

## Tanda B — Lógica y datos de la app

Fases 3 y 3b del plan (secciones 4 `core/model`, `core/database`, `core/data` y `especialidades/clinica`; 5; 7; 9
y 11). RF del lado app: RF-2, RF-4, RF-7, RF-8, RF-9, RF-11, RF-12, RF-16, RF-20 a RF-22, RF-27, RF-28 (lógica),
RF-31 (catálogo de la app) y RF-23, RF-25, RF-26 (caché). Todo en este repo.

Reglas de la tanda:
- Al empezar T14: rama `hc-general` creada desde `main` (ahí se entregan juntas la 001 y la 002). Un commit local
  por tarea como `rodri89`, **sin push** (push a `main` publica la web).
- Tests primero: cada tarea escribe su test, lo ve fallar y después el código. Si Gradle pide `JAVA_HOME`:
  `$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`.
- **No se toca** `AppDi.kt` (la contribución de clínica sigue siendo `clinicaContribution`), ni `hepatologiaContribution`,
  ni pantallas, ni `HcApiSync`/`SyncEngine`: eso es de las tandas C y D (RF-33: pediatría y las demás siguen igual).
- La lógica pura va en `core/data/src/commonMain/kotlin/com/salud360/core/data/hcgeneral/` y sus tests en
  `core/data/src/commonTest/kotlin/com/salud360/core/data/hcgeneral/`: sin red, sin Compose, sin base, sin "hoy".
- Ningún dato real en los tests: perfiles, nombres y números de médico inventados.
- `normalizarHistoriasClinicas` (RF-29) queda para la tanda D, junto con `7.sqm` (fase 6 del plan), y las consultas
  de lectura de `HistoriaClinica.sq` para RF-22 van en la tanda C, con su pantalla.

- [x] **T14. Tipos del modelo para perfiles y pestañas agrupadas.** RF-7, RF-20 (base), RF-33
  - Rama `hc-general` desde `main`.
  - `core/model/.../especialidad/EspecialidadDefinition.kt`: `data class PestaniaDef(id, titulo, secciones: List<String>)`
    y `TipoConsultaDef.grupos: List<PestaniaDef> = emptyList()` **como último parámetro** (los constructores
    posicionales de las demás especialidades no cambian; con `grupos` vacío todo sigue igual).
  - `core/model/.../especialidad/PerfilHc.kt` (nuevo, `@Serializable` lo que va al caché): `TipoConsultaHc`
    (`CONSULTA` ↔ `SeccionesComunes.TIPO_CONTROL`, `TELEMEDICINA` ↔ `TIPO_TELEMEDICINA`), `SeccionPerfil(seccion,
    nombre, tipos)`, `PestaniaPerfil(nombre, secciones)`, `PerfilHc(id: Long?, nombre, pestanias)` (`id` null = todavía
    no guardado), `SeccionCatalogoRemota(id, nombre, descripcion)`, `ResumenPerfil(id, nombre, medicos)`,
    `AsignacionPerfil(medicoIdTobb, nombre?, vinculado, activo?, perfilId?, perfilWeb?)` (nulos como en el contrato de
    `config/asignaciones`), `CatalogoHc` (base `EspecialidadDefinition` + secciones de la app por id con lo que cada
    una necesita: `SeccionDef`, categorías de antecedentes, laboratorios, registros; y la lista de secciones legadas)
    y `CodigosHc.GENERAL = "clinica"`.
  - Hecho cuando: `.\gradlew :core:model:compileKotlinJvm` compila, y `.\gradlew :core:data:jvmTest` y
    `.\gradlew :composeApp:compileKotlinWasmJs` siguen en verde sin tocar ninguna especialidad.

- [x] **T15. Validación del perfil y sección nueva.** RF-2, RF-4, RF-8, RF-9
  - `hcgeneral/PerfilValidacion.kt`: `validarPerfil(perfil, otrosNombres, catalogoIds): List<ErrorPerfil>` espejo de
    `PerfilesService::validar` de hclinica: mismas reglas, mismos `campo` (`nombre`, `pestanias`,
    `pestanias.i.nombre`, `pestanias.i.secciones.j.nombre`, `pestanias.i.secciones.j.seccion`,
    `pestanias.i.secciones.j`), repeticiones marcadas en la segunda aparición, nombres de perfil y de pestaña
    comparados con `trim`, espacios colapsados, sin mayúsculas y **sin tildes** (más estricto que el servidor, a
    propósito: avisa antes del 422; "Decidido" B4), largo ≤ 60 contando caracteres como `mb_strlen` (code points, no unidades UTF-16).
    `catalogoIds` son los del catálogo **remoto** (`config/catalogo`), no los de la app, para no marcar como error
    lo que la app no conoce pero el perfil ya tiene (RF-16). `ErrorPerfil(campo, mensaje)` es la misma forma que
    devuelve la API en `errores`, así la pantalla muestra igual los dos.
  - `nuevaSeccion(seccionId, catalogo): SeccionPerfil`: nombre por defecto del catálogo y solo `CONSULTA` (RF-8).
  - `PerfilValidacionTest`: válido sin errores; vacío y 61 caracteres en perfil, pestaña y sección; nombre usado por
    otro con otra capitalización y espacios; "Clínica general" vs "clinica  general" (perfil y pestaña) repetido;
    pestaña repetida; sección fuera del catálogo; sección dos veces en
    pestañas distintas (error en la segunda); sección sin tipos; perfil sin secciones; `nuevaSeccion` sale con
    Consulta y el nombre por defecto. Cada caso comprueba el `campo` exacto.
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*PerfilValidacionTest*'` en verde.

- [x] **T16. Duplicar, borrar y catálogo del administrador.** RF-1, RF-11, RF-12, RF-16
  - `hcgeneral/PerfilCopia.kt`: `nombreCopia(nombre, existentes)` ("Copia de X", "Copia de X (2)", …, comparando
    como `validarPerfil`; recorta X para no pasar 60 con el sufijo) y `duplicarPerfil(perfil, existentes)` (mismas
    pestañas, secciones, nombres y tipos; `id` null). `puedeBorrar(perfil, asignaciones)` = ningún médico asignado.
  - `hcgeneral/CatalogoAdmin.kt`: `catalogoParaAdmin(remoto, catalogoApp)` en el orden del remoto, con nombre y
    descripción, marcando `soportada` las que la app sabe mostrar; las no soportadas no se ofrecen para agregar.
  - `PerfilCopiaTest` (copia simple, con "(2)" y "(3)" ocupados, nombre de 60 recortado, la copia pasa
    `validarPerfil`, borrar con y sin médicos) y `CatalogoAdminTest` (sección del remoto que la app no tiene →
    no soportada; orden del remoto).
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*PerfilCopiaTest*' --tests '*CatalogoAdminTest*'` en verde.

- [x] **T17. Definición de la HC general, visibles y estado de la configuración.** RF-7, RF-19, RF-20, RF-21, RF-23,
  RF-24, RF-25, RF-26, RF-27, RF-28
  - `hcgeneral/DefinicionHcGeneral.kt`: `armarDefinicion(catalogo, perfil?): DefinicionArmada` → definición
    "clinica" con todas las secciones que la app conoce (título del perfil si está en él, si no el por defecto),
    tipos `control` y `telemedicina` con `grupos` = pestañas del perfil filtradas por tipo, en orden y con sus
    nombres; pestañas sin secciones para ese tipo, fuera; ids que la app no conoce, omitidos y listados en
    `desconocidas`; sin perfil (RF-19), tipos sin grupos. Tipo `foto` solo para leer consultas viejas ("Decidido" 5 del plan).
  - `seccionesVisibles(tipo, ocultas)`: grupos sin las ocultas por el médico, nunca algo fuera del perfil; las
    ocultas que ya no están en el perfil se ignoran pero no se podan (RF-28).
  - `hcgeneral/EstadoConfig.kt`: `estadoConfig(guardada, remota): EstadoConfigHc` (`ConPerfil` / `SinPerfil` /
    `PrimeraVezSinConexion`): la remota buena manda; si falla, vale la guardada sin vencimiento; distingue "sin
    perfil" (guardada con `null`) de "nunca recibida" (sin fila). Cuando no hay config usable lleva el motivo del
    fallo (`codigo` y `mensaje`: no vinculado, licencia vencida, HC no habilitada, sin conexión), igual que el aviso
    de la cartera de pacientes ("Decidido" B2).
  - `DefinicionHcGeneralTest` (con un catálogo chico armado en el test: nombres propios por perfil y por pestaña,
    pestaña vacía en telemedicina, id desconocido, sin perfil, mismo id en dos perfiles con nombres distintos) y
    `EstadoConfigTest` (remota buena con y sin caché, remota caída con caché con perfil y "sin perfil", remota caída
    sin caché con motivo `red` y con un motivo de la API como licencia vencida, remota con perfil `null`).
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*DefinicionHcGeneralTest*' --tests '*EstadoConfigTest*'` en verde.

- [x] **T18. Secciones con datos fuera del perfil, solo para leer.** RF-22, RF-28, RF-30
  - `hcgeneral/SeccionesSoloLectura.kt`: `campoTieneDato(campo, valor)` (`0`, `0|` y vacío no cuentan) y
    `seccionesConDatos(datos, def)` según el tipo de sección (texto/form, examen físico, registros, laboratorios,
    archivos, antecedentes, diagnósticos de esa consulta), con `datos` como un tipo propio que la tanda C llena desde
    la base; `seccionesSoloLectura(catalogo, enPerfilParaTipo, conDatos, ocultas)`: con datos y fuera del perfil para
    ese tipo, con el **nombre por defecto** (el del catálogo del servidor, "Decidido" B1), al final, en orden de catálogo y después las legadas (diagnósticos,
    audio, fotos, antecedentes personales).
  - `SeccionesSoloLecturaTest`: sección quitada con datos → aparece; sin datos (o solo `0|`) → no; sección de
    Consulta en una telemedicina → aparece; legada con datos (diagnóstico) → aparece después de las del catálogo;
    oculta por el médico pero en el perfil → no aparece como solo lectura (RF-28).
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*SeccionesSoloLecturaTest*'` en verde.

- [x] **T19. Catálogo de la HC general en la app.** RF-31, RF-33
  - `especialidades/clinica/.../HcGeneral.kt` (nuevo; **convive** con `ClinicaMedica.kt` hasta la tanda C):
    `catalogoHcGeneral: CatalogoHc` con las 24 secciones de `CatalogoSecciones.php`, mismos ids, orden y **nombres**:
    el título por defecto de cada sección es el del catálogo del servidor ("Decidido" B1); reutiliza las `SeccionDef`
    de clínica, hepatología y `SeccionesGenericas` copiándolas con el título cambiado cuando difiere (lo que hoy es
    `private` en `ClinicaMedica.kt` pasa a `internal` sin cambiar `clinicaMedica` ni `hepatologia`); `diuresis_catarsis` y `somnia` como texto; base
    `hcGeneralBase` (código "clinica", nombre "Clínica", examen físico con peso, talla, IMC, TA y nota) y las legadas
    (diagnósticos, audio, fotos, antecedentes personales) solo para leer. `hcGeneralContribution` todavía **no** se
    crea ni se registra (tanda C).
  - `especialidades/clinica/src/commonTest/.../CatalogoHcGeneralTest.kt` (primer test del módulo): ids y nombres por
    defecto iguales, en orden, a la lista de `CatalogoSecciones.php` (copiada en el test); cada id del
    catálogo tiene `SeccionDef` y sus dependencias (laboratorio, registro o categoría de antecedentes); los dos
    perfiles iniciales (copiados en el test de la tabla de T12) armados con `armarDefinicion` dan las pestañas y
    secciones de la tabla de la spec, sin `desconocidas`; `clinicaMedica` y `hepatologia` siguen iguales. Pediatría
    no se prueba desde acá: alcanza con sus tests existentes y la compilación ("Decidido" B5).
  - Hecho cuando: `.\gradlew :especialidades:clinica:jvmTest --tests '*CatalogoHcGeneralTest*'` en verde y
    `.\gradlew :composeApp:compileKotlinWasmJs` compila.

- [x] **T20. Caché de la configuración del médico.** RF-23, RF-25, RF-26
  - `core/database/.../migrations/6.sqm` + `HcConfig.sq` (nuevo) con la misma tabla:
    `hc_config(medico_id TEXT, hc_codigo TEXT, config_json TEXT NOT NULL, recibida_en INTEGER NOT NULL,
    PRIMARY KEY(medico_id, hc_codigo))`, sin `dirty`/`deleted` (dueño remoto); consultas `configGuardada` y
    `guardarConfig` (`INSERT OR REPLACE`). `config_json` es el perfil o `null` (sin perfil, RF-19).
  - `core/data/.../repos/ConfigHcRepository.kt`: interfaz `ConfigHcApi { suspend fun config(): PerfilHc? }`;
    `refrescar(medicoId)` (pide y guarda; si falla, lanza la `TobbException` sin tocar lo guardado), `guardada(medicoId)`
    y `estado(medicoId, conRed)` con `estadoConfig`, propagando el motivo del fallo (`codigo`/`mensaje` de la
    `TobbException`) para el aviso ("Decidido" B2). Sin cliente "clinica" configurado (dev sin URL de hclinica) se
    comporta como sin conexión; en producción la URL siempre existe ("Decidido" B6). Nunca se llama desde el autoguardado ni pasa por `HcApiSync`/`SyncEngine`.
  - `core/data/src/jvmTest/.../repos/ConfigHcRepositoryTest.kt` (base SQLite en archivo temporal, como los E2E, y una
    `ConfigHcApi` falsa): guarda y relee con la API caída (RF-25); sin fila y sin red → `PrimeraVezSinConexion`
    (RF-26) con motivo `red`; fallo de la API (p. ej. no vinculado) sin fila → el estado trae ese `codigo`;
    sin cliente "clinica" → como sin conexión; `perfil: null` → `SinPerfil`; una respuesta nueva reemplaza la anterior; un fallo no borra lo guardado;
    y la migración: borrando `hc_config` de una base nueva y corriendo `Schema.synchronous().migrate` de la versión 6 a
    la 7, la tabla vuelve y funciona.
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*ConfigHcRepositoryTest*'` y `.\gradlew :core:data:jvmTest`
    completos en verde.

- [x] **T21. DTOs, repositorio del administrador, Koin y URL de producción.** RF-1 a RF-17 (datos), RF-13, RF-23, RF-25
  - `core/data/.../network/hc/HcConfigDtos.kt`: DTOs del contrato real (`md/API_SALUD360_CLINICA.md`): perfil
    `{id, nombre, pestanias: [{nombre, secciones: [{seccion, nombre, consulta, telemedicina}]}]}`, catálogo, resumen
    (`medicos`), asignación (con nulos) y errores `{campo, mensaje}`, y su paso a/desde `PerfilHc`. `ConfigHcApi`
    implementada sobre `HcApiClient` (`GET config`).
  - `repos/PerfilesHcRepository.kt` (administrador, **nada local**): catálogo, lista, leer, crear (`POST`), guardar
    (`PUT`), borrar (`DELETE`, 409 con `medicos`), asignaciones y asignar. Los fallos devuelven el `mensaje`, el
    `codigo` y los `errores` de la API (RF-13); `codigo` `red` = sin conexión (RF-14).
  - Errores campo por campo ("Decidido" B3): `TobbException` suma `errores: List<ErrorPerfil>` (o equivalente
    `{campo, mensaje}`) opcional, **al final** y con valor por defecto (vacío), así no cambia ninguna llamada
    existente; `HcApiClient.interpretar` lo llena desde el 422 `validacion`.
  - `DataModule.kt`: `single` de `ConfigHcRepository` y `PerfilesHcRepository` sobre `porEspecialidad["clinica"]`
    (nullable). **No** se agrega "clinica" a `HcBackends`.
  - `core/data/build.gradle.kts`: propiedades `salud360.test.hc.clinica`, `.clinica.token`, `.clinica.token.admin`.
  - `gradle.properties`: **solo** la línea `salud360.entorno.release.hc.clinica=https://clinica.hclinicadigital.com`.
    Ojo: cambia el build de producción (la web release crea un cliente para hclinica y le copia el token al
    entrar); no se usa hasta la tanda C y no se publica sin la 002.
  - `PerfilesDtoTest` (commonTest): ida y vuelta del JSON de ejemplo del `.md` (perfil, `perfil: null`, asignaciones
    con nulos, 422 con `errores`) y `HcApiClient.interpretar` con la respuesta 422 del servidor → `TobbException`
    con `codigo` `validacion` y los `errores` campo por campo.
  - Hecho cuando: `.\gradlew :core:data:jvmTest` completo en verde (incluido `PerfilesDtoTest`),
    `.\gradlew :composeApp:compileKotlinWasmJs` compila y `git diff main -- gradle.properties` muestra una sola línea.

### Decidido (2026-10-10)
1. **Nombre por defecto de cada sección**: manda el del catálogo del servidor (`CatalogoSecciones.php` de hclinica).
   `catalogoHcGeneral` (T19) usa esos nombres como título por defecto (puede reutilizar las `SeccionDef` existentes
   copiándolas con el título cambiado). `CatalogoHcGeneralTest` compara ids **y** nombres por defecto contra la
   lista del servidor (copiada en el test). RF-22 muestra lo de solo lectura con ese nombre (T18).
2. **Avisos con motivo**: `estadoConfig` (T17) lleva el código/mensaje del fallo cuando no hay config (no vinculado,
   licencia vencida, HC no habilitada, sin conexión), igual que el aviso de la cartera de pacientes.
   `ConfigHcRepository` (T20) lo propaga.
3. **Errores campo por campo (RF-13)**: `TobbException` suma un campo `errores` opcional, al final y con valor por
   defecto, que `HcApiClient.interpretar` llena desde el 422 (T21; test con la respuesta del servidor).
4. **Tildes**: `validarPerfil` (T15) compara nombres de perfil y de pestaña ignorando tildes, mayúsculas y espacios
   de más (más estricto que el servidor, a propósito: avisa antes del 422). Test con "Clínica general" vs
   "clinica  general".
5. **Pediatría**: alcanza con sus tests existentes y la compilación (no se prueba desde `especialidades/clinica`).
6. **Sin URL de hclinica en dev**: se trata como sin conexión (en producción la URL siempre existe) (T20).
7. **AGENTS.md** (módulos con tests) se actualiza en el cierre (tanda D).

Al terminar la tanda: validación del reviewer y MEMORY.md.

Validada por el reviewer el 2026-10-12: APROBADO.

## Tanda C — Administración y pantalla del médico

Fases 4 y 5 del plan (sección 4: `features/admin`, `features/hc`, `composeApp` y las consultas de lectura de
`HistoriaClinica.sq`; secciones 7, 9 y 11; "Decidido" 5 a 8). RF: RF-1 a RF-18 (interfaz del administrador),
RF-19 a RF-28, RF-33 y RF-34 (médico). Todo en este repo, rama `hc-general`.

Reglas de la tanda:
- Un commit local por tarea como `rodri89`, **sin push** (push a `main` publica la web).
- Lo que tenga lógica va a `core/data/.../hcgeneral/` (o al repositorio) con su test, primero en rojo; los
  ViewModels y las pantallas no tienen tests en este repo: solo muestran y delegan (constitución 4).
- Interfaz: el agente deja compilado `.\gradlew :composeApp:compileKotlinWasmJs` y los pasos manuales; los prueba el
  usuario con el `devWeb` contra XAMPP de `AGENTS.md`. La tarea no se marca hecha hasta que el usuario confirme.
- Datos de prueba: perfiles con nombres inventados que empiezan con "Prueba C", borrados al terminar cada tarea (o
  al final de la tanda si la siguiente los usa). "Clínica general" y "Hepatología" de la carga inicial no se
  editan; si se cambia la asignación del médico de prueba, al terminar vuelve a "Hepatología". Licencia del médico
  de prueba: extenderla y restaurarla como dice `MEMORY.md`. Ningún dato real ni credencial en el repo.
- **No se toca** (tanda D, "Decidido" C5): `7.sqm`, `normalizarHistoriasClinicas`, `hepatologiaContribution`. Tampoco
  `HcApiSync`/`SyncEngine`, ni se registra un `HcBackend` para "clinica"; la configuración nunca se pide desde el
  autoguardado.
- `HistoriaClinica.sq` solo suma consultas de lectura: sin columnas ni tablas nuevas, sin migración.

### Decidido (2026-10-13)
1. **C1 — Datos por paciente en "Datos"** (decisión del usuario): los antecedentes personales del paciente y, en
   general, los datos que se guardan por paciente y no por consulta, de secciones que no están en el perfil, **no**
   salen como solo lectura al final de cada consulta: se muestran una sola vez, solo lectura, en la pestaña "Datos"
   (ficha del paciente) de la HC general. Al final de la consulta van solo las secciones con datos en **esa**
   consulta. Se ajustan `seccionesConDatos`/`seccionesSoloLectura` (`hcgeneral/SeccionesSoloLectura.kt`, de T18) con
   su test (T27) y se muestra en T30.
2. **C2 — Copia de diagnósticos** (reviewer, bloqueante): `HistoriaClinicaViewModel.nuevaConsulta`
   (`ConsultaViewModel.kt`, ~línea 379) copia los últimos diagnósticos si `definicion.secciones` tiene una sección
   de diagnósticos; con la legada `diagnostico` en la definición armada eso dispararía. Tiene que mirar las secciones
   de los tipos de consulta, con test, en la **misma** tarea que registra `hcGeneralContribution` (T28).
3. **C3 — `enPerfil` obligatorio** (reviewer): `seccionesSoloLectura` pierde el valor por defecto de `enPerfil`, así
   la pantalla tiene que pasarlo (T27, usado en T30).
4. **C4 — Borrar un perfil** (reviewer): el `409 perfil_con_medicos` no trae el número; alcanza con mostrar el
   mensaje del servidor y recargar la lista. Borrar se deshabilita con `ResumenPerfil.medicos` > 0 (T23).
5. **C5 — Hepatología sigue hasta la D**: en C, `hcGeneralContribution` reemplaza a `clinicaContribution` en
   `AppDi.kt` (T28); `hepatologiaContribution`, `7.sqm` y `normalizarHistoriasClinicas` son de la tanda D.
6. **C6 — Migración en el navegador**: se prueba en `devWeb` que un navegador con la base en versión 6 (de antes de
   la T20) migra a 7 sin recrearla vacía (T23, primer paso manual de la tanda).
7. **C7 — C1 pasa a la spec** (decisión del usuario, opción 1): cambio aprobado en `spec.md`: RF-22 queda acotado a
   los datos de **esa** consulta y RF-34 (nuevo) muestra los datos por paciente fuera del perfil una sola vez, solo
   lectura, en "Datos". `plan.md` suma RF-34 a la tabla de trazabilidad. Lo implementan T27 y T30.
8. **C8 — Hepatología visible hasta la D**: la casilla "Hepatología" sigue en el alta y la ficha del médico hasta la
   tanda D. Las asignaciones de T25 listan a los médicos con `clinica` **o** `hepatologia` habilitada.
9. **C9 — Espera de `DefinicionesHc`**: hasta 5 s esperando la configuración; si vence, se usa la guardada
   (`estadoConfig` como sin conexión, RF-25/RF-26) (T28).
10. **C10 — Consulta abierta sin perfil**: el médico que queda sin perfil con una consulta ya abierta puede seguirla
    (solo "Datos"), cerrarla y anularla; no puede crear consultas nuevas (RF-19) (T28, T29).

- [x] **T22. Edición del perfil en borrador (lógica pura).** RF-5, RF-6, RF-7, RF-8, RF-16
  - `hcgeneral/EditorPerfil.kt`: funciones sobre `PerfilHc` que devuelven el perfil cambiado, sin red ni Compose:
    `agregarPestania` (al final, con un nombre por defecto que no repita otro, comparando como `claveDeNombre`),
    `renombrarPestania`, `moverPestania(i, ±1)`, `quitarPestania` (con sus secciones), `agregarSeccion(pestania,
    seccionId, catalogo)` con `nuevaSeccion` (nombre por defecto y solo Consulta, RF-8), `renombrarSeccion`,
    `marcarTipo(pestania, j, tipo, marcado)`, `moverSeccion(pestania, j, ±1)`, `moverSeccionAPestania(origen, j,
    destino)` (va al final de la otra) y `quitarSeccion`. Índices fuera de rango → perfil sin cambios. Las secciones
    que la app no conoce se conservan intactas en todas las operaciones (RF-16); el `id` del perfil nunca cambia.
  - `EditorPerfilTest`: cada operación (orden resultante exacto), bordes (subir la primera, bajar la última, mover a
    la misma pestaña), nombre por defecto con "Pestaña 1" ya usada, quitar la pestaña con una sección desconocida
    adentro (se va con ella; mover o renombrar otras no la toca), la sección agregada sale con Consulta.
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*EditorPerfilTest*'` en verde.

- [x] **T23. Solapa "Perfiles HC": lista, duplicar y borrar.** RF-1 (lectura), RF-11, RF-12, RF-13, RF-14; C4, C6
  (compila; falta que el usuario confirme los pasos manuales 1 a 4)
  - `features/admin/.../PerfilesHcViewModel.kt` (registrado en `AdminModule.kt`; recibe `PerfilesHcRepository` y el
    `CatalogoHc`): al abrir pide catálogo, perfiles y asignaciones; estado con la lista, `cargando`, `mensaje` y
    `sinConexion` (fallo con `codigo` `red`, RF-14) y `reintentar()`. Duplicar = `perfil(id)` → `duplicarPerfil` con
    los nombres de la lista → `crear` → recargar (RF-12). Borrar con confirmación, solo si `medicos == 0`; un `409
    perfil_con_medicos` muestra el mensaje del servidor y recarga (C4, RF-11). Los fallos muestran el `mensaje` de la
    `TobbException` (RF-13).
  - `PerfilesHcScreens.kt` (nuevo): solapa "Perfiles HC" al final de `SOLAPAS` en `AdminScreens.kt`; por perfil:
    nombre, médicos asignados, Editar (sin acción hasta T24), Duplicar y Borrar (deshabilitado con médicos). Sin
    conexión: aviso, acciones deshabilitadas y "Reintentar".
  - `composeApp/.../AppDi.kt`: `single<CatalogoHc> { catalogoHcGeneral }` (la lista de contribuciones no cambia).
  - Hecho cuando: `.\gradlew :features:admin:compileKotlinJvm` y `.\gradlew :composeApp:compileKotlinWasmJs`
    compilan, y el usuario confirma en `devWeb`:
    1. (C6) En el navegador que ya usaba la app antes de la T20, sin borrar datos del sitio: la app arranca sin error
       en la consola y, entrando como el médico de prueba, siguen sus pacientes y consultas (la base pasó de la
       versión 6 a la 7, no se recreó vacía).
    2. Como administrador, "Perfiles HC" lista "Clínica general" y "Hepatología" con su número de médicos, y Borrar
       está deshabilitado en los dos.
    3. Duplicar "Hepatología" dos veces → "Copia de Hepatología" y "Copia de Hepatología (2)", con 0 médicos; Borrar
       las dos → desaparecen.
    4. Con Apache de XAMPP detenido, "Reintentar" → aviso de sin conexión y nada se puede tocar; prendido otra vez,
       "Reintentar" → vuelve la lista.

- [x] **T24. Editor de perfiles.** RF-1 a RF-9, RF-13, RF-14, RF-15, RF-16
  (compila; falta prueba manual del usuario, pasos 1 a 6)
  - En `PerfilesHcViewModel`: borrador del perfil en edición ("Nuevo perfil" arranca con una pestaña vacía; Editar
    lo lee con `perfil(id)`), operaciones de T22, y Guardar: primero `validarPerfil(perfil, otros nombres de la
    lista, ids del catálogo remoto)`; si pasa, `guardar` (POST o PUT). Errores locales o del 422
    (`TobbException.errores`) se guardan por `campo`; cualquier fallo deja el borrador intacto para reintentar
    (RF-13); `red` → sin conexión y edición deshabilitada (RF-14). Guardado bien → vuelve a la lista y recarga.
  - En `PerfilesHcScreens.kt`: nombre del perfil; pestañas (agregar, renombrar, subir/bajar, quitar); en cada una sus
    secciones (renombrar, casillas Consulta/Telemedicina, subir/bajar, mover a otra pestaña, quitar) y "Agregar
    sección" con `seccionesParaAgregar` (nombre y descripción). El catálogo completo (`catalogoParaAdmin`) se ve con
    nombre y descripción; las no soportadas, "requiere actualizar la app" y sin poder agregarlas; si el perfil ya
    tiene una, se muestra marcada así y se conserva al guardar (RF-16). Cada error junto a su campo. Salir con
    cambios sin guardar pide confirmación.
  - Hecho cuando: `.\gradlew :composeApp:compileKotlinWasmJs` compila y el usuario confirma en `devWeb`:
    1. Crear "Prueba C" con pestaña "Uno" (Motivo de consulta, Nota) y "Dos" (Conductas, Consulta y Telemedicina) →
       aparece en la lista; al reabrirlo está igual y en ese orden.
    2. No guarda y marca el campo: nombre vacío, de 61 caracteres, "clinica  general"; dos pestañas "Uno"; una
       sección sin tipos; perfil sin secciones.
    3. Renombrar "Nota" a "Nota final", moverla a "Dos", subir "Dos" y guardar → al reabrir, mismo perfil (misma
       fila en la lista) con todo eso.
    4. Abrir "Prueba C" en dos pestañas del navegador, guardar un cambio distinto en cada una → al reabrir queda
       completo el último guardado (RF-15).
    5. Con Apache detenido, Guardar → mensaje y el borrador sigue en pantalla; prendido otra vez, Guardar anda (RF-13).
    6. (RF-16) Agregando por un rato en la **copia servida** (no en el clon) una sección `prueba_c` a
       `CatalogoSecciones.php` y una fila de `prueba_c` en "Prueba C" por SQL puntual: el catálogo la muestra como
       "requiere actualizar la app" sin poder agregarla y, guardando el perfil desde la app, la fila sigue. Después
       se quitan las dos cosas.

- [x] **T25. Asignación de perfiles a médicos.** RF-10, RF-14, RF-17; C8
  (compila; falta prueba manual del usuario)
  - `PerfilesHcViewModel` suma `AdminRepository.observarMedicos()`: médicos con "clinica" **o** "hepatologia"
    habilitada (C8: hepatología sigue como casilla aparte hasta la tanda D), cruzados por
    número de turnos (`TobbIds.numero`) con `asignaciones()`, como la solapa de licencias. Asignar = `asignar(número,
    perfilId | null)` y recargar perfiles y asignaciones (los conteos de la lista cambian solos).
  - Pantalla: sección "Médicos" en "Perfiles HC" con un selector de perfil por médico que incluye "Sin perfil"; si la
    asignación es de un número no vinculado en hclinica (`vinculado == false`), se indica. Sin conexión, selectores
    deshabilitados (RF-14).
  - Hecho cuando: `.\gradlew :composeApp:compileKotlinWasmJs` compila y el usuario confirma en `devWeb`: el médico de
    prueba aparece con "Hepatología"; asignarle "Prueba C" → "Prueba C" muestra 1 médico y Borrar se deshabilita;
    renombrar "Prueba C" → sigue asignado (RF-10); "Sin perfil" → queda sin perfil (y `GET config` como médico da
    `perfil: null`); se le vuelve a poner "Hepatología".

- [x] **T26. Alta y ficha del médico con la HC general y su perfil.** RF-18, RF-14
  (compila; falta prueba manual del usuario)
  - `core/data`: `AgendaTurnosOnline` + `TurnosRepository.guardarHistoriasClinicas(medicoId, codigos)` →
    `PUT medicos/{id}/historias-clinicas` de turnos (T7), requiere conexión como `crearUsuario`; `crearUsuario` deja
    disponible el `medico_id` de `TobbUsuarioCreado` (hoy devuelve solo `Usuario`).
  - `AdminViewModel.setEspecialidadHc`: manda la lista completa a turnos y, si la acepta, guarda el médico en el
    dispositivo **sin** `dirty` (dueño remoto; hoy `AdminRepository.setEspecialidadHc` solo cambia la base local);
    si falla, mensaje y nada cambia.
  - Alta (`UsuariosTab`): al marcar "Clínica" (`CodigosHc.GENERAL`) aparece el selector de perfil (perfiles de
    `PerfilesHcRepository` + "Sin perfil"); después del alta, si hay perfil y `medico_id`, se asigna; si la
    asignación falla, "Usuario creado; falta asignarle el perfil: <motivo>". Ficha (`MedicoCard`): con "Clínica"
    habilitada, el mismo selector que T25.
  - Test en `core/data` commonTest del cuerpo `{historias_clinicas: [...]}` y de la lectura de `medico_id` del alta.
  - Hecho cuando: `.\gradlew :core:data:jvmTest` en verde, `.\gradlew :composeApp:compileKotlinWasmJs` compila y el
    usuario confirma en `devWeb`: alta de un médico "Prueba C" con "Clínica" y perfil "Prueba C" → turnos local le
    habilita `clinica` y `config/asignaciones` lo trae con `vinculado: false` y ese perfil; en la ficha del médico de
    prueba, destildar y volver a tildar "Clínica" → `auth/perfil` de turnos lo refleja; con Apache detenido, tildar
    → mensaje y la casilla queda como estaba. Al terminar: el usuario de prueba desactivado en turnos local por SQL
    puntual y su asignación quitada (`perfil_id: null`).

- [x] **T27. Datos de la consulta para el bloque de solo lectura (C1, C3, C7).** RF-22, RF-28, RF-30, RF-34
  - `HistoriaClinica.sq`, solo lectura: `tiposRegistroDeConsulta`, `tiposLaboratorioDeConsulta` y
    `seccionesArchivoDeConsulta` (`SELECT DISTINCT ... WHERE consulta_id = ? AND deleted = 0 AND activo = 1`).
  - `HcRepository`: flujo de `DatosConsulta` de una consulta (valores, examen físico, tipos de registro y de
    laboratorio, secciones de archivo y cantidad de diagnósticos de esa consulta) y, aparte, los antecedentes del
    paciente en la HC.
  - `SeccionesSoloLectura.kt` (C1): `seccionesConDatos` cuenta solo lo cargado en la consulta (las secciones de
    antecedentes, y cualquier registro `porPaciente`, ya no cuentan; `DatosConsulta.antecedentes` sale de ahí); nueva
    `seccionesDelPacienteSoloLectura(catalogo, antecedentes, enPerfil, ocultas)`: las secciones por paciente con datos
    que no están en el perfil en ningún tipo, con el nombre por defecto, en orden de catálogo y después las legadas,
    para la pestaña "Datos" (RF-34). (C3) `enPerfil` de `seccionesSoloLectura` sin valor por defecto.
  - `SeccionesSoloLecturaTest` ajustado: antecedentes personales con datos → no salen en la consulta y sí en las del
    paciente; antecedentes familiares en el perfil → no salen en ninguna; oculta por el médico y en el perfil → en
    ninguna. `core/data/src/jvmTest/.../repos/DatosConsultaTest.kt` (SQLite en archivo temporal, como
    `ConfigHcRepositoryTest`): una consulta con registro, laboratorio, archivo y diagnóstico, más uno borrado, uno
    inactivo y uno de otra consulta → `DatosConsulta` exacto.
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*SeccionesSoloLecturaTest*' --tests '*DatosConsultaTest*'`
    y `.\gradlew :core:data:jvmTest` completo en verde.

- [x] **T28. HC general registrada: definición resuelta, historia clínica y copia de diagnósticos (C2, C5, C9,
  C10).**
  RF-19, RF-24, RF-25, RF-26, RF-33
  (compila; falta prueba manual del usuario)
  - `EspecialidadRegistry.kt`: `EspecialidadContribution.catalogo: CatalogoHc? = null` al final; el registro lo
    expone por código. `especialidades/clinica/.../HcGeneral.kt`: `hcGeneralContribution` con
    `armarDefinicion(catalogoHcGeneral, null).definicion` (para nombre y `existe()`), el renderer de Child-Pugh y
    `catalogo = catalogoHcGeneral`. `AppDi.kt`: `clinicaContribution` → `hcGeneralContribution`;
    `hepatologiaContribution` se queda (C5).
  - `features/hc/.../DefinicionesHc.kt` (`single` en `HcModule.kt`): `resolver(codigo, medicoId): DefinicionResuelta`
    (definición + `sinPerfil` + `primeraVezSinConexion` con `codigo`/`mensaje` + `desconocidas`). Sin catálogo →
    la definición estática de siempre (RF-33). "clinica": `ConfigHcRepository.estado(medicoId, conRed = true)` con
    espera de hasta 5 s (C9: vencida = `estadoConfig` como sin conexión, vale lo guardado, RF-25/RF-26) y
    `armarDefinicion`. `AvisosHcGeneral` en el
    mismo archivo (`single`), para T30.
  - `HistoriaClinicaViewModel`/`HistoriaClinicaScreen.kt`: definición resuelta en un `StateFlow`. Sin perfil →
    "Falta que el administrador te asigne un perfil" (RF-19); primera vez sin configuración → "La primera vez hace
    falta conexión" con el motivo (RF-26). En los dos casos no se ofrece "Nueva consulta" y las anteriores se abren
    para leer ("Decidido" 8); una consulta que ya estaba abierta se puede seguir, cerrar y anular (C10, T29). Con perfil, solo los tipos con secciones (nunca "HC digitalizada"); con uno solo,
    directo.
  - C2: `copiaDiagnosticos(def)` en `hcgeneral/DefinicionHcGeneral.kt`: true si una sección de diagnósticos está en
    `secciones` o `pestanias` de **algún** tipo (no en `definicion.secciones`): unión de tipos para que hematología,
    que la tiene solo en Control, siga copiando igual (RF-33). `nuevaConsulta` la usa. Test en
    `DefinicionHcGeneralTest`: definición armada con la legada de diagnósticos → false; diagnósticos en un solo tipo
    → true; en `secciones` pero en ningún tipo → false.
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*DefinicionHcGeneralTest*'`,
    `.\gradlew :especialidades:clinica:jvmTest` en verde, `.\gradlew :composeApp:compileKotlinWasmJs` compila, y el
    usuario confirma en `devWeb` con el médico de prueba: con "Hepatología" la HC "Clínica" de un paciente ofrece
    Consulta y Telemedicina; con un perfil "Prueba C" sin nada en Telemedicina, "Nueva consulta" abre Consulta
    directo; sin perfil → aviso de RF-19, sin "Nueva consulta" y las anteriores se abren; con
    `-Psalud360.entorno.dev.hc.clinica` apuntando a un puerto sin servicio: en un navegador sin datos del sitio →
    aviso de RF-26, y en el navegador que ya recibió la configuración → se arma con la guardada (RF-25).

- [x] **T29. Consulta con las pestañas del perfil y definición congelada.** RF-19, RF-20, RF-21, RF-23, RF-24,
  RF-33; C10
  (compila; falta prueba manual del usuario)
  - `ConsultaViewModel`: resuelve la definición **una vez** al abrir con `DefinicionesHc` y la guarda fuera del
    `flatMapLatest` de `ui` (RF-23: un cambio de perfil con la consulta abierta se ve la próxima vez, sin perder lo
    cargado); `ConsultaUi` suma lo resuelto (sin perfil, primera vez). `seccionesVisibles` delega en la función pura
    de T17 cuando el tipo tiene `grupos`.
  - `ConsultaScreen.kt`: si `tipo.grupos` no está vacío, pestañas = "Datos" + grupos con al menos una sección visible
    (`seccionesVisibles(tipo, ocultas)` y `cumpleCondicion`), abriendo en "Datos" ("Decidido" 7); cada pestaña
    renderiza sus secciones con `RenderSeccion` y no queda nada suelto debajo. Sin perfil o primera vez: solo "Datos"
    y el aviso; una consulta abierta se puede seguir (solo "Datos"), cerrar y anular (C10, RF-19). Sin grupos
    (pediatría y las demás): el código de hoy, intacto.
  - Hecho cuando: `.\gradlew :composeApp:compileKotlinWasmJs` compila y el usuario confirma en `devWeb`: con "Prueba C"
    asignado, una Consulta nueva muestra "Datos", "Uno", "Dos" con esos nombres y secciones en ese orden y abre en
    "Datos"; una Telemedicina solo muestra las pestañas con secciones de Telemedicina; con la consulta abierta, el
    administrador (en otra ventana privada) renombra "Uno" → la pantalla abierta no cambia y lo cargado sigue; al
    reabrirla, el nombre nuevo (RF-23, RF-24); con una consulta abierta, el administrador le quita el perfil → al
    reabrirla solo "Datos" y el aviso, y se puede cerrar o anular, pero la HC no ofrece "Nueva consulta" (C10); se
    le vuelve a poner "Prueba C"; una consulta de pediatría u otra especialidad se ve igual que antes (RF-33).

- [x] **T30. Solo lectura al final de la consulta, datos del paciente en "Datos" y aviso de secciones
  desconocidas.** RF-22, RF-27, RF-30, RF-34; C1, C3, C7
  (compila; falta prueba manual del usuario)
  - `ConsultaViewModel`: con los flujos de T27, `seccionesSoloLectura(catalogo, enPerfilParaTipo = secciones de los
    grupos del tipo, conDatos = seccionesConDatos(...), ocultas, enPerfil = las del perfil en todos los tipos)`
    (C3, explícito) y `seccionesDelPacienteSoloLectura(...)` para "Datos" (C1).
  - `ConsultaScreen.kt`: debajo de la pestaña elegida ("Decidido" 7), el bloque de solo lectura con un
    `SeccionContext` envuelto en `soloLectura = true`; en "Datos", debajo de la ficha y una sola vez, las secciones
    por paciente fuera del perfil, también solo lectura (C1, RF-34). RF-27: si hay `desconocidas` y `AvisosHcGeneral` no
    avisó en esta sesión, un aviso de que hace falta actualizar la app (una vez por sesión, en la HC o la consulta).
  - Hecho cuando: `.\gradlew :composeApp:compileKotlinWasmJs` compila y el usuario confirma en `devWeb`: en una
    consulta con datos en "Nota final" de "Prueba C", el administrador quita esa sección del perfil → al reabrir, sale
    al final, solo lectura, como "Nota" (nombre por defecto); con antecedentes familiares cargados y la sección
    quitada del perfil, se ven en "Datos" y **no** al final de cada consulta (C1); con una fila `prueba_c` en el
    perfil por SQL puntual → el resto se ve y el aviso sale una vez por sesión (otra vez al recargar la página); al
    terminar se borra la fila.

- [x] **T31. Configuración de secciones con el perfil.** RF-28, RF-21, RF-33
  (compila; falta prueba manual del usuario)
  - `hcgeneral/DefinicionHcGeneral.kt`: `mostrarTodas(ocultas, enPerfil): Set<String>` = las ocultas sin las del perfil
    (las de fuera del perfil se conservan); test en `DefinicionHcGeneralTest`.
  - `ConfigSeccionesHcViewModel`/`ConfigSeccionesHcScreen.kt`: para "clinica" usa `DefinicionesHc`: lista las
    secciones del perfil (todas las pestañas, con el nombre del perfil, sin legadas ni desconocidas), misma
    preferencia `secciones_ocultas_clinica`; `setVisible` nunca poda las ocultas de fuera del perfil y "Mostrar
    todas" usa `mostrarTodas` (hoy guarda vacío y las borraría, contra RF-28). Sin perfil o primera vez: el aviso en
    vez de la lista. Las demás especialidades, igual que hoy.
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*DefinicionHcGeneralTest*'` en verde,
    `.\gradlew :composeApp:compileKotlinWasmJs` compila y el usuario confirma en `devWeb`: Configuración → "Clínica"
    lista las secciones de "Prueba C" con sus nombres; ocultar "Conductas" → no se ve en la consulta ni sale como
    solo lectura aunque tenga datos; el administrador la quita del perfil y la repone → sigue oculta; "Mostrar todas"
    → vuelve; si el médico tiene pediatría, se ve aparte e igual que antes.

Al terminar la tanda: validación del reviewer, prueba manual del usuario y MEMORY.md.

## Tanda C2 — Cambios de la prueba manual (2026-10-14)

Cambio aprobado por el usuario el 2026-10-14 (spec: definiciones "Cuerpo de la consulta" y "Estudios con historial",
RF-20 ajustado, RF-35 a RF-42; plan: "Decidido" 9 y 10, que reemplazan al 7). Lo de T29/T30 sobre dónde van las
secciones y el bloque de solo lectura queda reemplazado por T32/T33. Todo en este repo, rama `hc-general`.

Reglas de la tanda (las mismas de la tanda C):
- Un commit local por tarea como `rodri89`, **sin push** (push a `main` publica la web).
- Tests primero: la lógica va a `core/data/.../hcgeneral/` con su test en `commonTest`, que se ve fallar antes del
  código. ViewModels y pantallas no tienen tests: solo muestran y delegan (constitución 4). Si Gradle pide
  `JAVA_HOME`: `$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`.
- Interfaz: el agente deja compilado `.\gradlew :features:hc:compileKotlinJvm` y
  `.\gradlew :composeApp:compileKotlinWasmJs` y los pasos manuales; los prueba el usuario con el `devWeb` contra
  XAMPP de `AGENTS.md`. La tarea no se marca hecha hasta que el usuario confirme.
- Sin migración ni columnas nuevas: `registro_clinico` queda como está. No se toca `HcApiSync`/`SyncEngine`, no se
  registra un `HcBackend` para "clinica" y el guardado de los estudios nunca llama a la API.
- Datos de prueba: el médico de prueba con "Hepatología" (ya tiene "Antec. Familiares" + "Consulta" con los cinco
  estudios); los perfiles extra con nombres que empiezan con "Prueba C2", borrados al terminar; al terminar, el
  médico vuelve a "Hepatología". "Clínica general" y "Hepatología" no se editan. Licencia del médico de prueba:
  extenderla y restaurarla como dice `MEMORY.md`. Ningún dato real ni credencial en el repo.
- RF-33: pediatría y las demás especialidades se ven igual (sin `grupos` y con registros en modo `LISTA`).

- [x] **T32. Cuerpo de la consulta (lógica pura).** RF-20, RF-21, RF-28, RF-35, RF-36
  - `hcgeneral/DefinicionHcGeneral.kt`: `data class LayoutConsulta(pestanias: List<PestaniaDef>, cuerpo:
    List<String>)` y `layoutConsulta(tipo, ocultas, cumple)` en lugar de `pestaniasConsulta`: con los grupos visibles
    de hoy (sin ocultas ni los que no cumplen la condición), `cuerpo` = las secciones del primer grupo cuyo
    `claveDeNombre(titulo) == claveDeNombre("Consulta")` y `pestanias` = "Datos" + los demás grupos en orden. Sin
    grupos: pestañas y cuerpo vacíos (pantalla de siempre). `ConsultaViewModel.pestanias` pasa a devolver el
    `LayoutConsulta` (y `seccionesVisibles` junta pestañas y cuerpo), sin tocar todavía la pantalla más que para
    compilar.
  - `DefinicionHcGeneralTest`: los casos de `pestaniasConsulta` adaptados; "Antec. Familiares" + "Consulta" →
    pestañas "Datos", "Antec. Familiares" y cuerpo con las secciones de "Consulta" en orden; " CONSULTA " y
    "Cónsulta" también son cuerpo, "Consulta del día" no; sin "Consulta" → todo pestañas y cuerpo vacío; solo
    "Consulta" → pestañas = solo "Datos" + cuerpo; "Consulta" con todas sus secciones ocultas → cuerpo vacío y las
    demás pestañas igual; Telemedicina con "Consulta" sin secciones de ese tipo → sin cuerpo; sin grupos → vacío.
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*DefinicionHcGeneralTest*'` en verde,
    `.\gradlew :core:data:jvmTest` completo en verde y `.\gradlew :features:hc:compileKotlinJvm` compila.

- [x] **T33. Consulta con "Datos" y pestañas arriba, cuerpo debajo y solo lectura al final.** RF-20, RF-22, RF-34,
  RF-35, RF-36, RF-37, RF-33 — compila; falta prueba manual del usuario.
  - `ConsultaScreen.kt`, solo la rama con `grupos`: `ScrollableTabRow` con `layout.pestanias` (abre en "Datos"; en
    "Datos" la ficha y, debajo, lo de RF-34; en las demás, sus secciones con `RenderSeccion`); después, fuera de la
    pestaña elegida y siempre visibles, las secciones de `layout.cuerpo` con `RenderSeccion`, como las secciones
    sueltas de pediatría; al final, `SoloLecturaFueraDelPerfil(ui.fueraDelPerfil.alFinal, ...)` (RF-37). Sin grupos
    (pediatría y las demás, sin perfil, HC digitalizada): el código de hoy, intacto.
  - Hecho cuando: `.\gradlew :features:hc:compileKotlinJvm` y `.\gradlew :composeApp:compileKotlinWasmJs` compilan y
    el usuario confirma en `devWeb`:
    1. Con "Hepatología", una Consulta nueva muestra arriba solo "Datos" y "Antec. Familiares" (abre en "Datos") y
       debajo, sin pestaña "Consulta", Motivo de consulta … TC / RMN / colangio-RMN en ese orden; al pasar a
       "Antec. Familiares" el cuerpo sigue debajo, con lo cargado.
    2. Una Telemedicina con "Hepatología": arriba "Datos" y "Antec. Familiares", debajo Datos subjetivos, Datos
       objetivos, Exámenes complementarios, Interconsultas y Conductas.
    3. Con un perfil "Prueba C2 sin consulta" (pestañas "Uno" y "Dos"): las dos como pestañas junto a "Datos" y nada
       debajo (RF-36); con "Prueba C2 solo consulta" (una pestaña "consulta" con Nota): arriba solo "Datos" y Nota
       debajo.
    4. Con datos en "Nota" de una consulta (cargada con el médico asignado a "Clínica general") y el médico vuelto a
       "Hepatología" (que no tiene Nota): al reabrirla,
       "Nota" sale al final, solo lectura, debajo del cuerpo, con "Datos" y con "Antec. Familiares" elegidas (RF-37).
    5. Una consulta de pediatría u otra especialidad se ve igual que antes (RF-33).

- [x] **T34. Estudios con historial: modo del registro y lógica pura.** RF-38, RF-39, RF-33
  - `core/model/.../EspecialidadDefinition.kt`: `enum class ModoRegistro { LISTA, UNO_POR_CONSULTA }` y
    `RegistroDef.modo: ModoRegistro = ModoRegistro.LISTA` como último parámetro.
  - `especialidades/clinica/.../HcGeneral.kt`: los registros de `eco_abdominal`, `veda`, `biopsia_hepatica`,
    `estudios_cardiologicos` y `tc_rmn` copiados con `modo = UNO_POR_CONSULTA` (los de `hepatologia` no cambian).
  - `hcgeneral/EstudioConHistorial.kt`: `entradaDeConsulta(registros, consultaId)` (la de fecha más reciente de esa
    consulta, `null` si no hay), `historialEstudio(registros, editada)` (todas menos la editada, sin `detalle` en
    blanco, por `fecha DESC` y `updated_at DESC`) y `cambioEstudio(actual, fecha, texto)` → `Crear`/`Actualizar`/
    `Borrar`/`Nada` (texto en blanco con entrada → `Borrar`; sin entrada → `Nada`; mismo texto y fecha → `Nada`).
  - `EstudioConHistorialTest` (commonTest, registros inventados): consulta sin entrada → `null` y todas en el
    historial; con entrada → no sale en el historial; dos entradas en la misma consulta → se edita la más reciente y
    la otra va al historial; vacías y `"   "` fuera; orden por fecha y, a igual fecha, por `updated_at`; los cuatro
    casos de `cambioEstudio`. `CatalogoHcGeneralTest`: los cinco estudios con `UNO_POR_CONSULTA`, exámenes
    complementarios e interconsultas con `LISTA`.
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*EstudioConHistorialTest*'`,
    `.\gradlew :core:data:jvmTest` completo y `.\gradlew :especialidades:clinica:jvmTest` en verde, y
    `.\gradlew :composeApp:compileKotlinWasmJs` compila.

- [x] **T35. Pantalla de los estudios con historial.** RF-38, RF-39, RF-40, RF-41, RF-42, RF-22 — compila; falta
  prueba manual del usuario.
  - `secciones/EstudioConHistorialSeccion.kt` (nuevo) y `RegistrosSeccion` que delega en él con `UNO_POR_CONSULTA`,
    como dice el plan (sección 4, `features/hc`): campo de esta consulta (fecha + texto) a la izquierda y "Consultas
    previas" a la derecha con desplazamiento y el mismo alto; debajo con menos de 600 dp de ancho; "Ver todo" con la
    ventana grande; con `ctx.soloLectura`, sin editar. `ConsultaViewModel.guardarEstudio` con `cambioEstudio`, en el
    dispositivo, con la espera y el volcado al salir del autoguardado; nunca la API.
  - Hecho cuando: `.\gradlew :features:hc:compileKotlinJvm` y `.\gradlew :composeApp:compileKotlinWasmJs` compilan y
    el usuario confirma en `devWeb` con "Hepatología", en un paciente de prueba:
    1. Consulta A: escribir en Biopsia hepática "texto A" (fecha por defecto = la de la consulta) y cerrarla.
       Consulta B: el campo arranca vacío y a la derecha "Consultas previas" muestra la fecha y "texto A"; escribir
       "texto B", salir enseguida y volver → "texto B" sigue (no se pierde lo último).
    2. Borrar todo el texto de B → B queda sin entrada: no sale en el historial de otra consulta ni como dato.
    3. Con cuatro o más entradas largas, la derecha se desplaza sola sin estirar la sección; "Ver todo" abre la
       ventana grande con "Esta consulta" primero y las previas debajo; cerrarla no cambia nada.
    4. Con la ventana del navegador angosta (menos de 600 px, o el modo teléfono de las herramientas de desarrollo),
       las previas salen debajo del campo.
    5. Consulta A (cerrada): fecha y texto se ven sin poder editarlos; previas y "Ver todo" andan.
    6. Las otras cuatro (Ecografía abdominal, Videoendoscopía digestiva alta, Estudios cardiológicos, TC / RMN /
       colangio-RMN) se ven y guardan igual; Exámenes complementarios e Interconsultas siguen como antes (lista con
       "Agregar"), y un registro de pediatría también (RF-33).
    7. Con el médico en un perfil "Prueba C2" sin Biopsia hepática, la consulta A muestra Biopsia hepática al final,
       solo lectura, con el mismo formato (RF-22, RF-42).

### Decidido (2026-10-14)
1. **Arrastre del último estudio al campo:** no se arrastra. El campo arranca vacío y la última entrada se ve
   primera en "Consultas previas" (RF-38/RF-39, T34/T35).
2. **Fecha del estudio:** se mantiene el selector de fecha, por defecto la fecha de la consulta.
3. **Historial en una consulta vieja:** "Consultas previas" muestra todas las demás entradas del paciente, también
   las posteriores, como la web.
4. **Estudios de hepatología viejos en el dispositivo:** aparecen recién con la tanda D (`7.sqm`); en la prueba
   manual, usar consultas creadas en "Clínica".

Al terminar la tanda: validación del reviewer y prueba manual del usuario.

## Tanda C3 — Laboratorio (2026-10-14)

Cambio aprobado por el usuario el 2026-10-14 (2) (spec: definiciones "Laboratorio" y "Filas ocultas", RF-43 a
RF-54, RF-33 ajustado; plan: sección 4 `features/hc` y `core/ui`, sección 5, "Decidido" 11 a 16). Todo en este repo,
rama `hc-general`.

Reglas de la tanda (las mismas de la tanda C2):
- Un commit local por tarea como `rodri89`, **sin push** (push a `main` publica la web).
- Tests primero: la lógica va a `core/data/.../hcgeneral/` (y los colores a `core/ui`) con su test, que se ve fallar
  antes del código. ViewModels y pantallas no tienen tests: solo muestran y delegan (constitución 4). Si Gradle pide
  `JAVA_HOME`: `$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`.
- Compilación de interfaz: `.\gradlew :features:hc:compileKotlinJvm` y
  `.\gradlew :composeApp:compileKotlinWasmJs -Pentorno=dev "-Psalud360.entorno.dev.turnos=http://localhost/TurnosOnlineBB-salud360/public/index.php" "-Psalud360.entorno.dev.hc.clinica=http://localhost/HClinica-salud360/public/index.php"`.
  Para que el `devWeb` que ya está corriendo tome el cambio:
  `.\gradlew :composeApp:wasmJsDevelopmentExecutableCompileSync -Pentorno=dev` con las mismas dos `-P`, y recargar
  la página. **No** correr `--stop` ni matar el `devWeb` en uso.
- La tarea no se marca hecha hasta que el usuario confirme los pasos manuales, en modo claro **y** en modo oscuro
  (el tema sigue al sistema: en el navegador, DevTools → Rendering → "Emulate CSS prefers-color-scheme: dark", o el
  modo oscuro del sistema).
- Sin migración ni columnas nuevas: las filas ocultas van en `medico_preferencia` como `secciones_ocultas_clinica`.
  No se toca `HcApiSync`/`SyncEngine`, no se registra un `HcBackend` para "clinica" y nada llama a la API.
- Datos de prueba: el médico de prueba con "Hepatología" (tiene Laboratorio general y Peso) y un perfil
  "Prueba C3" (Consulta: Laboratorio general, Laboratorio reducido, Peso), borrado al terminar; el médico vuelve a
  "Hepatología". Pacientes de prueba con dos o más cargas de laboratorio. Licencia del médico de prueba: extenderla y
  restaurarla como dice `MEMORY.md`. Ningún dato real ni credencial en el repo.

- [x] **T36. Filas visibles del laboratorio (lógica pura).** RF-44, RF-45, RF-47, RF-48, RF-49
  - `hcgeneral/FilasLaboratorio.kt`: `clavePreferenciaFilasOcultas(codigo, laboratorioTipo)`,
    `ocultasDePreferencia(valor?)`, `puedeElegirFilas(codigo, analitos)`, `filasVisibles(analitos, ocultas)`,
    `alternarFila(analitos, ocultas, clave, visible)`, `esUltimaVisible(analitos, ocultas, clave)`,
    `mostrarTodasLasFilas(analitos, ocultas)` y `textoBotonFilas(analitos, ocultas)`, como dice el plan (sección 5).
  - `FilasLaboratorioTest` (commonTest, analitos inventados con dos grupos): clave
    `filas_ocultas_clinica_laboratorio_general` y distinta para `laboratorio_reducido`; `ocultasDePreferencia` con
    null, "" y "a,,b"; `filasVisibles` sin ocultas → todas en orden, con una oculta → sin ella y el resto en orden,
    con todas ocultas → todas, con una clave que el laboratorio no tiene → se ignora; `alternarFila` oculta, muestra,
    se niega a ocultar la última visible y conserva claves de afuera; `esUltimaVisible`; `mostrarTodasLasFilas`
    conserva las de afuera; `textoBotonFilas` sin ocultas, con 3 y sin contar las de afuera; `puedeElegirFilas`
    true para "clinica" con 2 analitos, false con 1 (Peso) y false para "pediatria".
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*FilasLaboratorioTest*'` y `.\gradlew :core:data:jvmTest`
    completo en verde.

- [x] **T37. "Elegir filas" en el laboratorio.** RF-43, RF-44, RF-45, RF-46, RF-47, RF-48, RF-49, RF-33
  (compila; falta prueba manual del usuario)
  - `secciones/LaboratorioSeccion.kt`, como dice el plan (sección 4, `features/hc`): filas de
    `filasVisibles(labDef.analitos, ocultas)` (cabeceras de grupo de la lista filtrada), botón en el `trailing` del
    `SectionCard` solo con `puedeElegirFilas` (también en solo lectura) con `textoBotonFilas`, y diálogo con una
    `CheckboxField` por analito (agrupadas, con desplazamiento), la última visible deshabilitada con "Tiene que
    quedar al menos una fila visible", "Mostrar todas" y "Cerrar"; cada casilla guarda con
    `ctx.vm.guardarPreferencia`. El mapa `valores` de la carga sigue con todas las claves.
  - Hecho cuando: `.\gradlew :features:hc:compileKotlinJvm` y `:composeApp:compileKotlinWasmJs` (con las `-P` de
    arriba) compilan, el `devWeb` se refresca con `wasmJsDevelopmentExecutableCompileSync`, y el usuario confirma,
    en modo claro y en modo oscuro:
    1. Con "Hepatología", en Laboratorio general, "Elegir filas" abre la lista con las 51 filas en orden y todas
       tildadas (los laboratorios de la HC general no tienen grupos; las cabeceras de grupo salen de la lista
       filtrada y se cubren con el código de siempre); destildar dos (una con valores en una carga anterior) →
       desaparecen al momento de todas las columnas, y el botón dice "Elegir filas (2 ocultas)".
    2. Ocultar todas menos una → la casilla de la última queda deshabilitada y sale el mensaje.
    3. "Nueva carga" (o "Editar" una carga con valores en una fila oculta): la columna editable no muestra la fila
       oculta; guardar; "Mostrar todas" → la fila vuelve con su valor intacto en esa carga y en las anteriores.
    4. Recargar la página y reabrir la consulta → las filas siguen ocultas; una consulta cerrada del mismo paciente
       también las oculta y ofrece el botón.
    5. Con "Prueba C3": ocultar "Glucemia" en Laboratorio reducido no la oculta en Laboratorio general, y Peso no
       muestra "Elegir filas".
    6. Con otro médico de prueba de la HC general, si lo hay en la base local, el mismo paciente muestra todas las
       filas (si no lo hay, se anota como no probado); un laboratorio de pediatría no tiene el botón y se ve igual
       que antes, salvo la celda editable de T38 (RF-33, RF-49).

- [x] **T38. Celda editable que se distingue.** RF-50, RF-51, RF-52, RF-53, RF-54
  (compila; falta prueba manual del usuario)
  - `core/ui`: `theme/ColoresCelda.kt` (`ColoresCelda`, `coloresCelda(oscuro)`, `contraste`) con los valores de la
    tabla del plan, y `CeldaEditable` en `components/FormFields.kt` (fondo, borde 1 dp, borde de foco 2 dp, cursor
    y texto de ayuda "Valor"), como dice el plan (sección 4, `core/ui`).
  - `LaboratorioSeccion.kt`: la `CeldaEditable` privada se borra y se usa la de `core/ui`; las celdas de solo
    lectura no cambian.
  - `core/ui/src/jvmTest/.../theme/ColoresCeldaTest.kt`: `contraste(negro, blanco)` = 21 (±0,1); en los dos modos,
    borde y bordeFoco contra fondo ≥ 3, borde contra `surface` y contra `surfaceVariant` al 50 % sobre `surface` ≥ 3,
    texto y ayuda contra fondo ≥ 4,5, fondo ≠ `surface`.
  - Hecho cuando: `.\gradlew :core:ui:jvmTest` en verde, `.\gradlew :features:hc:compileKotlinJvm` y
    `:composeApp:compileKotlinWasmJs` (con las `-P` de arriba) compilan, el `devWeb` se refresca con
    `wasmJsDevelopmentExecutableCompileSync`, y el usuario confirma, en modo claro y en modo oscuro:
    1. En "Nueva carga" de Laboratorio general, cada celda de la columna editable tiene fondo y borde visibles en
       las filas blancas y en las grises, y las de las fechas anteriores no tienen ni borde ni fondo propio.
    2. Las celdas vacías dicen "Valor" (atenuado); al escribir desaparece, y al guardar no queda "Valor" guardado.
    3. Con clic y con tabulador, la celda con foco tiene el borde más grueso y de otro color, y se ve el cursor
       (también en oscuro); al salir vuelve el borde normal.
    4. Un laboratorio de pediatría muestra la misma celda nueva y carga y guarda igual que antes (RF-54).

Al terminar la tanda: validación del reviewer y prueba manual del usuario.

## Tanda C4 — Varias cargas de laboratorio (2026-10-14)

Cambio aprobado por el usuario el 2026-10-14 (3) (spec: definición "Laboratorio", RF-45 ajustado, RF-55 a RF-64;
plan: sección 4 `features/hc` y `core/database`, sección 5, "Decidido" 17 a 21 y C4-1). Todo en este
repo, rama `hc-general`. Lo de T37/T38 sobre "Nueva carga"/"Editar" en la HC general queda reemplazado por T40;
pediatría y las demás siguen igual (RF-64).

Reglas de la tanda (las mismas de la tanda C3):
- Un commit local por tarea como `rodri89`, **sin push** (push a `main` publica la web).
- Tests primero: la lógica va a `core/data/.../hcgeneral/` con su test, que se ve fallar antes del código.
  ViewModels y pantallas no tienen tests: solo muestran y delegan (constitución 4). Si Gradle pide `JAVA_HOME`:
  `$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`.
- Compilación de interfaz: `.\gradlew :features:hc:compileKotlinJvm` y
  `.\gradlew :composeApp:compileKotlinWasmJs -Pentorno=dev "-Psalud360.entorno.dev.turnos=http://localhost/TurnosOnlineBB-salud360/public/index.php" "-Psalud360.entorno.dev.hc.clinica=http://localhost/HClinica-salud360/public/index.php"`.
  Para que el `devWeb` que ya está corriendo tome el cambio:
  `.\gradlew :composeApp:wasmJsDevelopmentExecutableCompileSync -Pentorno=dev` con las mismas dos `-P`, y recargar
  la página. **No** correr `--stop` ni matar el `devWeb` en uso.
- La tarea de pantalla no se marca hecha hasta que el usuario confirme los pasos manuales.
- Sin migración ni columnas nuevas: solo cambia el `ORDER BY` de `laboratoriosDePaciente`. No se toca
  `HcApiSync`/`SyncEngine`, no se registra un `HcBackend` para "clinica" y nada llama a la API.
- Datos de prueba: el médico de prueba con "Hepatología" (Laboratorio general y Peso) en pacientes de prueba; si se
  usa un perfil "Prueba C4" (Laboratorio reducido), se borra al terminar y el médico vuelve a "Hepatología".
  Licencia del médico de prueba: extenderla y restaurarla como dice `MEMORY.md`. Ningún dato real ni credencial en
  el repo.

- [x] **T39. Columnas y acciones de las cargas (lógica pura y orden).** RF-55, RF-57, RF-60, RF-61, RF-62, RF-63,
  RF-64
  - `hcgeneral/CargasLaboratorio.kt`: `cargasMultiples(codigo)`, `data class ColumnaLaboratorio(carga,
    deEstaConsulta, puedeBorrar)`, `columnasLaboratorio(historico, consultaId, editable, maxOtras = 8)`,
    `mostrarNuevaCarga(editable, abierta)` y `cargaNuevaValida(valores)`, como dice el plan (sección 5).
  - `HistoriaClinica.sq`: `laboratoriosDePaciente` con `ORDER BY fecha DESC, updated_at DESC` (solo la consulta).
  - `CargasLaboratorioTest` (commonTest, cargas inventadas): dos cargas de esta consulta y tres de otras → todas,
    intercaladas por fecha descendente, con `deEstaConsulta` correcto; misma fecha → se conserva el orden de entrada
    (dos de la misma consulta y una de esta con otra); una inactiva no sale; 12 de otras + 2 de esta con fecha vieja
    → 8 de otras y las 2 de esta, en su lugar por fecha; `editable` true → `puedeBorrar` en todas, false → en
    ninguna; `mostrarNuevaCarga` (editable con y sin cargas → true; abierta → false; solo lectura → false);
    `cargaNuevaValida` con `{}`, `{"a" to "  "}` → false y con un valor → true; `cargasMultiples` true para
    "clinica" y false para "pediatria".
  - `core/data/src/jvmTest/.../repos/LaboratoriosDePacienteTest.kt` (SQLite en archivo temporal, como
    `DatosConsultaTest`): dos cargas con la misma fecha guardadas una después de la otra → `observarLaboratorios`
    trae primero la última; una dada de baja con `eliminarLaboratorio` no sale.
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*CargasLaboratorioTest*' --tests '*LaboratoriosDePacienteTest*'`
    y `.\gradlew :core:data:jvmTest` completo en verde.

- [x] **T40. Pantalla: "Nueva carga" agrega una columna.** RF-55 a RF-64, RF-45, RF-50, RF-33 (compila; falta
  prueba manual del usuario)
  - `secciones/LaboratorioSeccion.kt`, como dice el plan (sección 4, `features/hc`, cambio (3)): con
    `cargasMultiples(def.codigo)`, columna editable primera (fecha de la consulta, `CeldaEditable` en las filas
    visibles), "Nueva carga" según `mostrarNuevaCarga`, "Guardar laboratorio" con `cargaNuevaValida` (si no,
    "Cargá al menos un valor" y sigue abierta) y `guardarLaboratorio(tipo, null, fecha, valores)`, "Cancelar",
    columnas de `columnasLaboratorio` con "Esta consulta" y el tacho según `puedeBorrar`, sin "Editar"; sin
    `cargasMultiples`, el código de hoy intacto.
  - Hecho cuando: `.\gradlew :features:hc:compileKotlinJvm` y `:composeApp:compileKotlinWasmJs` (con las `-P` de
    arriba) compilan, el `devWeb` se refresca con `wasmJsDevelopmentExecutableCompileSync`, y el usuario confirma
    con "Hepatología", en Laboratorio general de un paciente de prueba con cargas de otras consultas:
    1. Consulta abierta sin cargas: "Nueva carga" abre la columna editable primera con la fecha de la consulta;
       escribir dos valores y "Guardar laboratorio" → la columna se cierra, la carga aparece como columna con
       "Esta consulta" y tacho, y "Nueva carga" sigue disponible (RF-55 a RF-57).
    2. "Nueva carga" otra vez, cambiar la fecha a una anterior a alguna de otra consulta, guardar → dos columnas
       "Esta consulta", cada una en su lugar por fecha; ninguna tiene "Editar" (RF-59, RF-61).
    3. "Nueva carga" y "Guardar laboratorio" sin escribir nada → "Cargá al menos un valor" y la columna sigue
       abierta; "Cancelar" → se cierra sin crear nada (RF-57, RF-58).
    4. Otra carga con la misma fecha que una existente → dos columnas con esa fecha, la nueva primero; el tacho de
       una (con confirmación) borra solo esa (RF-60, RF-61).
    5. Con una fila oculta ("Elegir filas"), la columna editable no la muestra y las guardadas tampoco (RF-45).
    6. Cerrar la consulta → se ven sus cargas y las de otras, sin "Nueva carga" ni tachos; una consulta cerrada sin
       cargas de ese laboratorio muestra la sección vacía como antes (RF-63).
    7. Con 9 o más cargas de otras consultas, se ven 8 de ellas y todas las de esta consulta (RF-62).
    8. Peso se comporta igual (varias cargas); un laboratorio de pediatría sigue con "Nueva carga"/"Editar" de una
       carga por consulta, como antes (RF-64, RF-33).

### Decidido (2026-10-14)
1. **C4-1 — Varias cargas solo en la HC general** (plan, "Decidido" C4-1): las tareas lo implementan así.
   Extenderlo a las demás historias clínicas es un cambio aparte que requiere relevar la API de pediatría.

Al terminar la tanda: validación del reviewer y prueba manual del usuario.

## Tanda C5 — Serologías en grilla (2026-10-15)

Cambio aprobado por el usuario el 2026-10-15 (spec: RF-65 a RF-73, casos límite de serologías; plan: sección 4
`core/model`, `especialidades/clinica`, `features/hc` y `core/ui`, sección 5, "Decidido" 22 a 26 y "Decidido"
C5-1 a C5-3). Todo en este repo, rama `hc-general`. Solo cambia Serologías de hepatitis de la HC general; la de
`hepatologia` (`ClinicaMedica.kt`), los demás formularios y las demás historias clínicas, igual (RF-73).

Reglas de la tanda (las mismas de la tanda C4):
- Un commit local por tarea como `rodri89`, **sin push** (push a `main` publica la web).
- Tests primero: la lógica y los colores van a `core/ui` (y el catálogo a `especialidades/clinica`) con su test, que
  se ve fallar antes del código. Pantallas no tienen tests: solo muestran y delegan (constitución 4). Si Gradle pide
  `JAVA_HOME`: `$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`.
- Compilación de interfaz: `.\gradlew :features:hc:compileKotlinJvm` y
  `.\gradlew :composeApp:compileKotlinWasmJs -Pentorno=dev "-Psalud360.entorno.dev.turnos=http://localhost/TurnosOnlineBB-salud360/public/index.php" "-Psalud360.entorno.dev.hc.clinica=http://localhost/HClinica-salud360/public/index.php"`.
  Para que el `devWeb` que ya está corriendo tome el cambio:
  `.\gradlew :composeApp:wasmJsDevelopmentExecutableCompileSync -Pentorno=dev` con las mismas dos `-P`, y recargar
  la página. **No** correr `--stop` ni matar el `devWeb` en uso.
- La tarea de pantalla no se marca hecha hasta que el usuario confirme los pasos manuales, en modo claro **y** en modo
  oscuro (DevTools → Rendering → "Emulate CSS prefers-color-scheme: dark", o el del sistema) y con la ventana ancha
  **y** angosta (menos de 600 px, o el modo teléfono de DevTools).
- Sin migración ni columnas nuevas: los valores siguen siendo "SI", "NO" o vacío en las mismas claves. No se toca
  `HcApiSync`/`SyncEngine`, no se registra un `HcBackend` para "clinica" y nada llama a la API.
- Datos de prueba: el médico de prueba con "Hepatología" (tiene Serologías de hepatitis) en pacientes de prueba, con
  una consulta que ya tenga serologías cargadas con la versión anterior; si se usa un perfil "Prueba C5" (sin
  serologías, para RF-22), se borra al terminar y el médico vuelve a "Hepatología". Licencia del médico de prueba:
  extenderla y restaurarla como dice `MEMORY.md`. Ningún dato real ni credencial en el repo.

- [x] **T41. Lógica, colores y catálogo de la grilla SI / NO.** RF-66, RF-67, RF-68, RF-70, RF-71 (contraste del
  foco), RF-73
  - `core/model/.../EspecialidadDefinition.kt`: `enum class ModoForm { FILAS, GRILLA_SI_NO }` y
    `SeccionDef.modoForm: ModoForm = ModoForm.FILAS` como último parámetro.
  - `core/ui`: `components/SiNoVacio.kt` con las puras `alternarSiNo(actual, tocado)` y `columnasPara(anchoDp)`
    (todavía sin el composable), y `theme/ColoresSiNo.kt` (`ColoresSiNo`, `coloresSiNo(oscuro)`) con los valores de
    la tabla del plan, usando la `contraste` de `ColoresCelda.kt`.
  - `especialidades/clinica/.../HcGeneral.kt`: serologías copiada con `modoForm = GRILLA_SI_NO` (la de `hepatologia`
    no cambia).
  - Tests: `core/ui/src/jvmTest/.../components/SiNoVacioTest.kt` y `.../theme/ColoresSiNoTest.kt`, y
    `CatalogoHcGeneralTest` ampliado, con los casos de la sección 9 del plan.
  - Hecho cuando: `.\gradlew :core:ui:jvmTest` y `.\gradlew :especialidades:clinica:jvmTest` en verde,
    `.\gradlew :core:data:jvmTest` sigue en verde y `.\gradlew :features:hc:compileKotlinJvm` compila.

- [x] **T42. Pantalla: serologías en tarjetas.** RF-65 a RF-73, RF-22
  (compila; falta prueba manual del usuario)
  - `core/ui/.../components/SiNoVacio.kt`: el composable `SiNoVacio(etiqueta, valor, onCambio, modifier,
    soloLectura)` como dice el plan (sección 4, `core/ui`): etiqueta arriba sin cortar, "SI" y "NO" de al menos
    48 × 48 dp, elegido relleno con tilde y no elegido con borde, `selectable` con rol de opción, foco de 2 dp por
    fuera del botón con ~2 dp de margen (C5-4),
    descripciones para el lector de pantalla, valor desconocido como texto, y en solo lectura etiqueta + valor sin
    botones.
  - `features/hc/.../secciones/FormSeccion.kt`: `CamposForm(..., modo = FILAS)`; con `GRILLA_SI_NO`, la grilla de
    `columnasPara` con `chunked` + `weight` y `Spacer` en la última fila; `FormSeccion` le pasa `s.modoForm`. Con
    `FILAS`, el código de hoy intacto.
  - Hecho cuando: `.\gradlew :core:ui:jvmTest` en verde, `.\gradlew :features:hc:compileKotlinJvm` y
    `:composeApp:compileKotlinWasmJs` (con las `-P` de arriba) compilan, el `devWeb` se refresca con
    `wasmJsDevelopmentExecutableCompileSync`, y el usuario confirma con "Hepatología", en modo claro y en modo oscuro:
    1. Ventana ancha: Serologías de hepatitis muestra 16 tarjetas, 4 por fila, en el orden de RF-65 (IgM HAV … AMA),
       con los nombres completos y los dos botones sin elegir.
    2. Ventana angosta (menos de 600 px o modo teléfono): 2 por fila, mismo orden, nombres sin cortar; achicar y
       agrandar con valores cargados no pierde nada.
    3. Tocar "SI" en IgM HAV → queda elegido (relleno y tilde); tocar "NO" → pasa a NO; tocar "NO" otra vez → sin
       cargar (los dos con borde). Salir, volver a entrar → se ve lo último.
    4. Una consulta con serologías cargadas antes de este cambio muestra los mismos valores; un marcador dejado sin
       cargar no aparece como "NO" al reabrir (RF-68).
    5. Con el tabulador se llega a cada botón con el foco visible, y Enter o espacio lo elige o lo vuelve a sin
       cargar; con el lector de pantalla del sistema (si está a mano; si no, se anota como no probado) se oye
       "IgM HAV, SI, elegido" o "sin cargar".
    6. Cerrar la consulta → solo los marcadores cargados, en la misma grilla, con su valor y sin botones; una
       consulta cerrada sin serologías muestra la sección vacía como antes (RF-72).
    7. Con el médico en "Prueba C5" (sin serologías), una consulta con serologías cargadas las muestra al final, solo
       lectura, en la grilla (RF-22, RF-72).
    8. Los demás formularios (Alimentación, Hábitos, Examen físico) y una consulta de pediatría se ven igual que
       antes; también serologías si queda a mano una HC de hepatología aparte (RF-73).

### Decidido (2026-10-15)
1. **C5-1 — Hepatología estática sin cambios** (plan, "Decidido" C5-1): sigue con los desplegables hasta que
   desaparece en la tanda D.
2. **C5-2 — Mismo relleno para SI y NO** (plan, "Decidido" C5-2).
3. **C5-3 — Solo lectura con solo los marcadores cargados** (plan, "Decidido" C5-3 y 26).
4. **C5-4 — Foco por fuera del botón** (plan, "Decidido" C5-4): borde de foco de 2 dp con ~2 dp de margen fuera del
   botón, sobre `fondoTarjeta`; `ColoresSiNoTest` pide bordeFoco contra `fondoTarjeta` ≥ 3 y bordeFoco ≠ relleno.

Al terminar la tanda: validación del reviewer y prueba manual del usuario.

## Tanda C6 — Child-Pugh como la web (2026-10-15)

Cambio aprobado por el usuario el 2026-10-15 (2) (spec: RF-74 a RF-82, casos límite de Child-Pugh; plan: sección 4
`especialidades/clinica` y `core/ui` (cambio 2026-10-15 (2)), sección 5, sección 9, "Decidido" 27 a 32). Todo en este
repo, rama `hc-general`. Cambia Child-Pugh de la HC general y de la hepatología aparte, que comparten el renderer
(RF-82); serologías solo cambia por dentro (`OpcionElegible`) y se tiene que ver igual; lo demás, igual.

Reglas de la tanda (las mismas de la tanda C5):
- Un commit local por tarea como `rodri89`, **sin push** (push a `main` publica la web).
- Tests primero: la lógica pura va a `especialidades/clinica` con su test, que se ve fallar antes del código.
  Pantallas no tienen tests: solo muestran y delegan (constitución 4). Si Gradle pide `JAVA_HOME`:
  `$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`.
- Compilación de interfaz: `.\gradlew :features:hc:compileKotlinJvm`, `.\gradlew :especialidades:clinica:compileKotlinJvm`
  y
  `.\gradlew :composeApp:compileKotlinWasmJs -Pentorno=dev "-Psalud360.entorno.dev.turnos=http://localhost/TurnosOnlineBB-salud360/public/index.php" "-Psalud360.entorno.dev.hc.clinica=http://localhost/HClinica-salud360/public/index.php"`.
  Para que el `devWeb` que ya está corriendo tome el cambio:
  `.\gradlew :composeApp:wasmJsDevelopmentExecutableCompileSync -Pentorno=dev` con las mismas dos `-P`, y recargar
  la página. **No** correr `--stop` ni matar el `devWeb` en uso.
- La tarea de pantalla no se marca hecha hasta que el usuario confirme los pasos manuales, en modo claro **y** en modo
  oscuro (DevTools → Rendering → "Emulate CSS prefers-color-scheme: dark", o el del sistema) y con la ventana ancha
  **y** angosta (menos de 600 px, o el modo teléfono de DevTools).
- Sin migración ni columnas nuevas: los valores siguen siendo "1", "2", "3" o vacío en las mismas claves; no se guardan
  total ni clase. No se toca ninguna `SeccionDef`, `HcApiSync`/`SyncEngine`, no se registra un `HcBackend` para
  "clinica" y nada llama a la API.
- Datos de prueba: el médico de prueba con "Hepatología" (tiene Child-Pugh) en pacientes de prueba, con una consulta
  que ya tenga Child-Pugh cargado con la versión anterior; si se usa un perfil "Prueba C6" (sin Child-Pugh, para
  RF-22), se borra al terminar y el médico vuelve a "Hepatología". Licencia del médico de prueba: extenderla y
  restaurarla como dice `MEMORY.md`. Ningún dato real ni credencial en el repo.

- [x] **T43. Lógica pura de Child-Pugh.** RF-74 (parámetros y textos), RF-76, RF-77, RF-78
  - `especialidades/clinica/.../ChildPugh.kt` (nuevo, sin Compose): `ParametroChildPugh(clave, nombre, criterios)`,
    `parametrosChildPugh` (las cinco claves y los textos de hoy, sin el sufijo "(n)"), `puntosDe`, `alternarPuntos`
    (llama a `alternarSiNo` de `core/ui`), `totalChildPugh`, `completosChildPugh` y `claseChildPugh`, como la tabla de
    la sección 5 del plan. `ChildPughSeccion.kt` todavía no cambia.
  - Test: `especialidades/clinica/src/commonTest/.../ChildPughTest.kt` con los casos de la sección 9 del plan.
  - Hecho cuando: `.\gradlew :especialidades:clinica:jvmTest` en verde (con `ChildPughTest` visto fallar antes del
    código) y `.\gradlew :core:ui:jvmTest` y `.\gradlew :core:data:jvmTest` siguen en verde.

- [x] **T44. Pantalla: Child-Pugh en tabla.** RF-74 a RF-82, RF-22 — compila; falta prueba manual del usuario.
  Bug de la prueba manual (2026-10-15): `ChildPughSeccion` leía con `ctx.valor` (StateFlow `.value`, no observado)
  y Compose salteaba la sección al cambiar un valor: se guardaba pero no se pintaba ni cambiaba el total — arreglado
  en `b73f115` (`ctx.vm.valores.collectAsState()`).
  - `core/ui/.../components/OpcionElegible.kt`: el `BotonSiNo` de `SiNoVacio.kt` pasa a `OpcionElegible(texto,
    descripcion, elegido, onClick, modifier, colores)`, público, con el texto centrado en varias líneas; `SiNoVacio`
    lo usa con `"$etiqueta, $opcion"` (mismo dibujo y misma descripción que hoy).
  - `especialidades/clinica/.../ChildPughSeccion.kt` reescrito como dice el plan (sección 4, `especialidades/clinica`):
    encabezado "Total: N · Clase X" + leyenda, tabla con `OpcionElegible` desde 600 dp, bloques con opciones apiladas
    por debajo, `selectableGroup` + `stateDescription`, valor desconocido como texto, solo lectura como texto y
    `SeccionVacia` sin valores. Se van `RadioGroupField` y `PARAMETROS`.
  - Test (C6-4): `ColoresSiNoTest` suma el color del texto "Total: N · Clase X" contra `fondoTarjeta` ≥ 4,5 en claro y
    oscuro, visto fallar antes si el color no llega; si `Salud360Colors.Indigo` no llega en oscuro, se usa `texto`.
  - Hecho cuando: `.\gradlew :core:ui:jvmTest` y `.\gradlew :especialidades:clinica:jvmTest` en verde,
    `.\gradlew :features:hc:compileKotlinJvm` y `:composeApp:compileKotlinWasmJs` (con las `-P` de arriba) compilan,
    el `devWeb` se refresca con `wasmJsDevelopmentExecutableCompileSync`, y el usuario confirma con "Hepatología", en
    modo claro y en modo oscuro:
    1. Ventana ancha: Child-Pugh muestra "Total: 0 · Clase —", la leyenda "A: ≤ 6 · B: 7-9 · C: ≥ 10", la tabla
       Parámetro | 1 | 2 | 3 bajo "Puntos" con los cinco parámetros y sus criterios de RF-74 completos, sin
       "(1)"/"(2)"/"(3)", y ninguna celda elegida.
    2. Ventana angosta (menos de 600 px o modo teléfono): un bloque por parámetro con las tres opciones apiladas
       ("1 punto · Ausente (I)", …), nada se desborda ni se corta; achicar y agrandar con valores cargados no pierde
       nada.
    3. Tocar "Moderada (II)" en Ascitis → queda elegida (relleno y tilde) y el total pasa a 2 al instante; tocar
       "Severa (III) o refractaria" → pasa a 3; tocarla otra vez → Ascitis sin cargar (las tres con borde). Tocar el
       nombre "Ascitis" no hace nada.
    4. Cargar los cinco con 1 → "Total: 5 · Clase A"; con total 7 → B; con 10 → C; dejar uno sin cargar → clase "—".
       Salir, volver a entrar → se ve lo último.
    5. Una consulta con Child-Pugh cargado antes de este cambio muestra los mismos puntajes, total y clase (RF-78).
    6. Con el tabulador se llega a cada celda con el foco visible, y Enter o espacio la elige o la vuelve a sin
       cargar; con el lector de pantalla del sistema (si está a mano; si no, se anota como no probado) se oye
       "Ascitis, 2 puntos, Moderada (II), elegido", "sin cargar" y el total nuevo al cambiar.
    7. Cerrar la consulta → total, clase, leyenda y los cinco parámetros como texto ("2 puntos · Moderada (II)" o
       "—"), sin nada que tocar; una consulta cerrada sin Child-Pugh no muestra la sección, como las demás (RF-81).
    8. Con el médico en "Prueba C6" (sin Child-Pugh), una consulta con Child-Pugh cargado lo muestra al final, solo
       lectura, como en el paso 7 (RF-22, RF-81).
    9. Serologías de hepatitis se ve y se usa igual que en la tanda C5 (botones, tilde, foco, vuelta a sin cargar);
       los demás formularios y una consulta de pediatría, igual que antes; Child-Pugh en una HC de hepatología aparte,
       si queda a mano, con la tabla nueva (RF-82).

### Decidido (2026-10-15)
1. **C6-1 — Las dos Child-Pugh** (plan, "Decidido" 29): HC general y hepatología aparte, por ser el mismo renderer.
2. **C6-2 — Tocar el nombre no limpia** (plan, "Decidido" 27): se limpia tocando la celda elegida, como serologías.
3. **C6-3 — Solo lectura como texto, con los cinco** (plan, "Decidido" 31).
4. **C6-4 — Contraste del total** (plan, "Decidido" 33): el color de "Total: N · Clase X" entra a `ColoresSiNoTest`
   (≥ 4,5:1 contra `fondoTarjeta`, claro y oscuro) en T44; si `Salud360Colors.Indigo` no llega en oscuro, se usa
   `texto` de `ColoresSiNo`.

Al terminar la tanda: validación del reviewer y prueba manual del usuario.

## Tanda C7 — Motivo de consulta con historial (2026-10-15)

Cambio aprobado por el usuario el 2026-10-15 (3) (spec: RF-83 a RF-90, casos límite del motivo; plan: sección 4
`core/model`, `core/database`, `core/data`, `especialidades/clinica` y `features/hc` (cambio 2026-10-15 (3)), sección
5, sección 9, "Decidido" 34 a 37 y "Decidido" C7-1 y C7-2). Todo en este repo, rama `hc-general`. Solo cambia
Motivo de consulta de la HC general; los estudios con historial cambian solo por dentro (`CampoConHistorial`) y se
tienen que ver igual; las demás secciones de texto e historias clínicas, igual (RF-90).

Reglas de la tanda (las mismas de la tanda C6):
- Un commit local por tarea como `rodri89`, **sin push** (push a `main` publica la web).
- Tests primero: la lógica pura va a `core/data/.../hcgeneral/` (y la consulta de lectura a `core/data` jvmTest, el
  catálogo a `especialidades/clinica`) con su test, que se ve fallar antes del código. Pantallas no tienen tests: solo
  muestran y delegan (constitución 4). Si Gradle pide `JAVA_HOME`:
  `$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`.
- Compilación de interfaz: `.\gradlew :features:hc:compileKotlinJvm`, `.\gradlew :especialidades:clinica:compileKotlinJvm`
  y
  `.\gradlew :composeApp:compileKotlinWasmJs -Pentorno=dev "-Psalud360.entorno.dev.turnos=http://localhost/TurnosOnlineBB-salud360/public/index.php" "-Psalud360.entorno.dev.hc.clinica=http://localhost/HClinica-salud360/public/index.php"`.
  Para que el `devWeb` que ya está corriendo tome el cambio:
  `.\gradlew :composeApp:wasmJsDevelopmentExecutableCompileSync -Pentorno=dev` con las mismas dos `-P`, y recargar
  la página. **No** correr `--stop` ni matar el `devWeb` en uso.
- **Los renderers que leen valores se suscriben con `collectAsState`** (aprendido en C6, bug de T44 arreglado en
  `b73f115`): el texto de esta consulta sale de los `valores` que pasa `RenderSeccion` (de
  `vm.valores.collectAsState()`) o de un `collectAsState` propio, **nunca** de `ctx.valor` (lee el `.value` de un
  StateFlow sin observarlo: se guarda pero no se pinta). Los flujos que se crean en el renderer (el historial) van
  dentro de `remember(...)` antes del `collectAsState`.
- La tarea de pantalla no se marca hecha hasta que el usuario confirme los pasos manuales, con la ventana ancha **y**
  angosta (menos de 600 px, o el modo teléfono de DevTools), en modo claro y en modo oscuro.
- Sin migración ni columnas nuevas: `HistoriaClinica.sq` solo suma la consulta de lectura `valoresDeCampoDePaciente`;
  el motivo se sigue guardando en `seccion_valor` (`motivo_consulta`, `texto`) con el autoguardado de siempre. No se
  toca `HcApiSync`/`SyncEngine`, no se registra un `HcBackend` para "clinica" y nada llama a la API.
- "Decidido" C7-1 y C7-2 (confirmados por el usuario el 2026-10-15): el motivo no tiene fecha propia (usa la de su
  consulta) y la marca `conHistorial` va por sección, activada solo en motivo.
- Datos de prueba: el médico de prueba con "Hepatología" (tiene Motivo de consulta en "Consulta") en un paciente de
  prueba con tres o más consultas en "Clínica"; si se usa un perfil "Prueba C7" (sin Motivo de consulta, para RF-22),
  se borra al terminar y el médico vuelve a "Hepatología". Licencia del médico de prueba: extenderla y restaurarla
  como dice `MEMORY.md`. Ningún dato real ni credencial en el repo.

- [x] **T45. Historial del motivo: lógica pura, consulta de lectura y marca en el catálogo.** RF-83, RF-84, RF-85,
  RF-90
  - `core/model/.../EspecialidadDefinition.kt`: `SeccionDef.conHistorial: Boolean = false` como último parámetro.
  - `HistoriaClinica.sq`: `valoresDeCampoDePaciente` (solo lectura, sin anuladas ni borradas) y
    `HcRepository.observarValoresDeCampo(pacienteId, especialidad, seccion, campo): Flow<Map<Id, String>>`, como dice
    el plan (sección 4, `core/database` y `core/data`). `historialCampo` no se toca.
  - `hcgeneral/TextoConHistorial.kt`: `EntradaTexto(consultaId, fecha, texto)` e `historialTexto(consultas, valores,
    consultaActual)`, como la tabla de la sección 5 del plan.
  - `especialidades/clinica/.../HcGeneral.kt`: `SeccionCatalogo(G.motivo.copy(conHistorial = true))`;
    `SeccionesGenericas.motivo` no cambia.
  - Tests: `core/data` commonTest `TextoConHistorialTest`, `core/data` jvmTest `ValoresDeCampoDePacienteTest` y
    `CatalogoHcGeneralTest` ampliado, con los casos de la sección 9 del plan (cambio 2026-10-15 (3)).
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*TextoConHistorialTest*' --tests '*ValoresDeCampoDePacienteTest*'`
    (vistos fallar antes del código), `.\gradlew :core:data:jvmTest` completo y
    `.\gradlew :especialidades:clinica:jvmTest` en verde, y `.\gradlew :features:hc:compileKotlinJvm` compila.

- [x] **T46. Pantalla: motivo de consulta con consultas previas.** RF-83 a RF-90, RF-22, RF-38 a RF-42 (sin cambios)
  — compila; falta prueba manual del usuario.
  - `features/hc/.../secciones/CampoConHistorial.kt` (nuevo) extraído de `EstudioConHistorialSeccion.kt`
    (`EntradaHistorial`, layout ancho/angosto, `ConsultasPrevias`, `VerTodoDialog`), y `EstudioConHistorialSeccion`
    usándolo sin cambiar lo que se ve ni cómo guarda.
  - `secciones/TextoConHistorialSeccion.kt` (nuevo) y `TextoSeccion` que delega en él con `s.conHistorial` (sin la
    marca, el código de hoy intacto); `RenderSeccionSola` le pasa `ui`; `ConsultaViewModel.historialTexto(seccion,
    campo)` con `combine` de `observarConsultas` y `observarValoresDeCampo`, como dice el plan (sección 4,
    `features/hc`, cambio 2026-10-15 (3)): texto de los `valores` observados (nunca `ctx.valor`), historial con
    `remember` + `collectAsState`, `TextAreaField` sin `DateField`, `ctx.setValor` como hoy, "Ver todo" con la fecha
    de la consulta, `SeccionVacia` en solo lectura sin texto ni previas.
  - Hecho cuando: `.\gradlew :features:hc:compileKotlinJvm` y `:composeApp:compileKotlinWasmJs` (con las `-P` de
    arriba) compilan, el `devWeb` se refresca con `wasmJsDevelopmentExecutableCompileSync`, y el usuario confirma con
    "Hepatología", en un paciente de prueba, en modo claro y en modo oscuro:
    1. Consulta A: escribir el motivo "motivo A" y cerrarla. Consulta B (otra fecha): el campo arranca vacío y a la
       derecha "Consultas previas" muestra la fecha de A y "motivo A"; escribir "motivo B" → se ve al instante en el
       campo; salir enseguida y volver → "motivo B" sigue (RF-83, RF-86).
    2. Reabrir A (cerrada): el motivo se ve sin poder editarlo, y a la derecha sale "motivo B" aunque B sea posterior;
       "Ver todo" anda (RF-84, RF-89).
    3. Consulta C con el motivo vacío o solo espacios: no aparece en las previas de A ni de B (RF-83). Anular una
       consulta con motivo → deja de aparecer en las previas de las demás (RF-84).
    4. Cambiar la fecha de la consulta B → en las previas de otra consulta, "motivo B" sale con la fecha nueva y en su
       lugar por fecha; no hay selector de fecha propio en el motivo (RF-85).
    5. Con cuatro o más motivos largos, la derecha se desplaza sola sin estirar la sección; "Ver todo" abre la ventana
       grande con "Esta consulta" (con la fecha de la consulta) primero y las previas debajo; cerrarla no cambia nada
       (RF-87).
    6. Ventana angosta (menos de 600 px o modo teléfono): las previas salen debajo del campo; achicar y agrandar no
       pierde lo escrito (RF-88).
    7. Con el médico en "Prueba C7" (sin Motivo de consulta), una consulta con motivo lo muestra al final, solo
       lectura, con el mismo formato (RF-22, RF-89).
    8. Biopsia hepática y los otros cuatro estudios con historial se ven y guardan igual que en la tanda C2 (fecha,
       previas, "Ver todo", angosto). Conductas de una Telemedicina de la HC general, el motivo de una HC de hepatología aparte (si
       queda a mano) y el de pediatría se ven como antes, con su lista de previas arriba del campo (RF-90, RF-33).

Al terminar la tanda: validación del reviewer y prueba manual del usuario.

## Tanda D — Hepatología deja de ser una historia clínica aparte (redactada el 2026-10-26)

**Estado: completa; T52 (prueba manual del usuario) cerrada con excepciones aceptadas por el usuario el 2026-10-28 (ver su nota).** Aprobada por el usuario el 2026-10-26, incluida expresamente la migración `8.sqm` (T49 y T50; AGENTS.md:
"migraciones de esquema: preguntá antes"; constitución #3) y las decisiones D1 a D7 como las recomendó el planificador
(ver "Decididas" más abajo).

Origen: prueba manual del usuario (2026-10-26). En Administración, la tarjeta del médico ofrece la casilla
"Hepatología" como historia clínica aparte (código `hepatologia`, la HC vieja de lista fija). Al habilitársela, la app
muestra esa HC y no el perfil "Hepatología" de la HC general (`clinica`) que el administrador configuró. La spec ya lo
prohíbe (RF-29) y el plan lo dejó para esta tanda (C5 y C8: "Hepatología sigue hasta la D"); la tanda nunca se redactó.

Qué pasa hoy, verificado en el código (2026-10-26):
- `composeApp/.../AppDi.kt` registra `hepatologiaContribution` en `especialidades`. De ahí salen la casilla del alta y
  de la ficha (`AdminViewModel.especialidadesHc` = código y nombre de cada contribución, `AdminScreens.kt` l. 142 y 215),
  el chip de la tarjeta y la HC que abre el médico (`MainShell` filtra con `registry.existe`).
- Turnos guarda y devuelve `hepatologia` tal cual (`HistoriaClinicaService::CATALOGO`, `salud360_medico_hc.hc_codigo`;
  `auth/perfil` y `catalogos` lo informan en `historias_clinicas`). La app lo guarda sin tocar en `medico.especialidades_hc`
  (`TurnosImportador.historiasClinicasDe`, `AgendaTurnosOnline` l. 434 y 468, `Mappers.kt` l. 110).
- `hepatologia` es también la fuente de las secciones del catálogo: `HcGeneral.kt` copia de `hepatologia` serologías,
  Child-Pugh, estudios, laboratorio reducido, peso y laboratorio general. **El `val hepatologia` no se puede borrar.**
- Ya resuelto por la 002 y la 001 (no se rehace): `historiasConCartera` (`VinculoPacientes.kt`) y `medicosParaAsignar`
  (`AsignacionesMedicos.kt`, C8) ya cuentan `hepatologia` como la HC general. hclinica acepta `clinica` **o**
  `hepatologia` en `salud360.hc_codigos` (T4, T5): no hay que tocarlo.

Reglas de la tanda (las de la C7 y estas):
- Un commit local por tarea como `rodri89`, **sin push** (push a `main` publica la web). Nada contra producción,
  `-Pentorno=dev`. Ningún dato real, contraseña ni token en el repo: pacientes, médicos y claves de prueba inventados.
- Tests primero (rojo, después el código). Si Gradle pide `JAVA_HOME`: `$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`.
- **No se tocan**: `spec.md` (los cambios de redacción que conviene hacer están en T53, para aprobar), las APIs
  Laravel de turnos y de hclinica (si el usuario elige tocarlas, es una tarea aparte y "preguntar antes"), `:server`
  (Ktor; la app no lo usa; sus copias de `codigoHcPorNombre` y del catálogo quedan como están), `HcApiSync`/`SyncEngine`,
  ni se registra un `HcBackend` para "clinica".
- **T51 no se publica sin T49 y T50.** Sin la migración, el médico que tenía datos en Hepatología los dejaría de ver
  (siguen en la base del dispositivo, ocultos, no perdidos); es aceptable en el `devWeb` del usuario, nunca en una
  versión publicada. Hasta que T50 esté hecha, no refrescar el `devWeb` en un navegador con datos que importen
  (T49 y T50 forman una sola migración: `8.sqm` no se publica ni se carga en un navegador entre una y otra).
- Compilación de interfaz: `.\gradlew :features:hc:compileKotlinJvm`, `.\gradlew :especialidades:clinica:compileKotlinJvm`,
  `.\gradlew :features:admin:compileKotlinJvm` y
  `.\gradlew :composeApp:compileKotlinWasmJs -Pentorno=dev "-Psalud360.entorno.dev.turnos=http://localhost/TurnosOnlineBB-salud360/public/index.php" "-Psalud360.entorno.dev.hc.clinica=http://localhost/HClinica-salud360/public/index.php"`.
  Para que el `devWeb` en uso tome el cambio:
  `.\gradlew :composeApp:wasmJsDevelopmentExecutableCompileSync -Pentorno=dev` con las mismas dos `-P`, y recargar.
  **No** correr `--stop` ni matar el `devWeb`.

### Dónde guarda el dispositivo el código de la HC (inventario de `core/database`, verificado)
| Tabla / clave | Qué es | Qué hace la tanda |
|---|---|---|
| `consulta.especialidad` | HC de cada consulta | `8.sqm`: pasa a `clinica`. No hay clave única con `especialidad`: no choca; el paciente puede quedar con dos consultas ABIERTAS (se conservan las dos) |
| `registro_clinico.especialidad`, `laboratorio.especialidad` | estudios, exámenes, interconsultas, laboratorios, peso | `8.sqm`: pasan. Solo la clave primaria `id`: no chocan |
| `antecedente.especialidad` | antecedentes por paciente | `8.sqm`: pasan, **resolviendo antes los choques** (misma clave en las dos HC): ver T49 |
| `paciente_extra.especialidad` | `PRIMARY KEY (paciente_id, especialidad, clave)`: un `UPDATE` ingenuo puede fallar | `8.sqm`: pasa solo la fila que no choca (ver T49) |
| `medico_preferencia` clave `secciones_ocultas_hepatologia` | secciones que el médico oculta; `PRIMARY KEY (medico_id, clave)` | `8.sqm`: se une a `secciones_ocultas_clinica` y la vieja queda con `deleted = 1` |
| `seccion_valor`, `examen_fisico`, `consulta_diagnostico`, `archivo` | cuelgan de `consulta_id` | siguen a su consulta, sin tocarlos |
| `medico.especialidades_hc`, `especialidad.codigo_hc` | códigos que informa turnos (dueño remoto) | **no van en `8.sqm`**: se normalizan al leer (T48) y se reescriben limpios en el próximo ingreso. Menos SQL = menos riesgo en el driver web (ver "Riesgos") |
| `hc_config.hc_codigo` | solo se escribe con `clinica` (`ConfigHcRepository`) | nada; un test lo comprueba |
| `interconsultor.especialidad`, `turno.especialidad` | texto libre (especialidad del interconsultor / de turnos), **no** es un código de HC | **no se tocan**; un test lo fija para que nadie lo "arregle" |
| `filas_ocultas_*` | solo existen con `clinica` (`puedeElegirFilas`) | nada |

### Decisiones (aprobadas por el usuario el 2026-10-26)
1. **Se normaliza en la app, no en los servidores** (pregunta D1): `hepatologia` en turnos cuenta como `clinica`; turnos y
   hclinica no cambian. Una app vieja y una nueva conviven sin coordinar el despliegue.
2. **Se normaliza al leer y al escribir**: `MedicoRow.toModel` (único punto por el que la base alimenta la sesión, el admin,
   las carteras y las licencias) más las escrituras de `TurnosImportador` y `AgendaTurnosOnline`.
3. **`8.sqm` solo mueve datos**: ninguna tabla ni columna nueva, así que **ningún `.sq` cambia** (la regla "columnas
   nuevas al final" no aplica) y `Salud360Db.Schema.version` pasa de 8 a 9. Solo `UPDATE`, `INSERT OR REPLACE ... SELECT` y
   `EXISTS` simples, que corren igual en SQLite (JVM, Android, iOS) y en sql.js (web).
4. **No se tocan `updated_at` ni `dirty`** de las filas que se mueven (no es una edición del médico y no reordena
   `consultasDePaciente`, que ordena por `fecha DESC, updated_at DESC`). El conteo de filas y la suma de `dirty` antes y
   después tienen que ser iguales (T50).
5. **Choques** (preguntas D2 a D4): antecedente repetido → queda activa la modificada más reciente y la otra pasa a
   `activo = 0` (no se borra); consultas abiertas en las dos HC → se conservan las dos abiertas; `paciente_extra` repetido →
   gana la de `clinica` y la de `hepatologia` queda donde estaba; secciones ocultas → unión de las dos listas.
6. **`val hepatologia` pasa a `internal`** (con KDoc: "ya no es una HC; fuente de secciones del catálogo") y se borra
   `hepatologiaContribution`. `clinicaMedica` no cambia: sigue de fuente (`HcGeneral.kt` l. 62, 69 y 81: antecedentes familiares, `labGeneralClinica` y `alimentacionClinica`).

### Mientras tanto: solución inmediata sin código
En Administración → Médicos → tarjeta del médico: **destildar "Hepatología", tildar "Clínica"** (la app manda la lista
completa a turnos con `PUT medicos/{id}/historias-clinicas`: hace falta conexión; turnos deja `hepatologia` con
`activo = 0` —no borra la fila— y `clinica` con `activo = 1`). Debajo aparece "Perfil de la historia clínica general":
elegir **"Hepatología"** (la asignación es por número de médico de turnos y se conserva si ya existía: el médico ya
figura en "Perfiles HC → Médicos" por C8). Después, el médico cierra sesión y vuelve a entrar (o recarga) para tomar la lista nueva.
- **Qué pasa con lo ya cargado como `hepatologia` en el dispositivo:** no se pierde nada y queda oculto. Cambiar las HC
  habilitadas solo modifica la lista del médico (`medico.especialidades_hc`); nadie borra `consulta`, `antecedente`,
  `registro_clinico`, `laboratorio` ni `seccion_valor` (ni `logout`, que solo borra la sesión y `hc_enfermedades`). La HC
  "Hepatología" no se ofrece sin la casilla y la de "Clínica" lista solo `especialidad = 'clinica'`: las consultas viejas
  no se ven en ninguna. Se vuelven a ver (a) re-tildando "Hepatología" (reversible) o (b) cuando exista T49/T50, que las
  pasa a "Clínica".
- **Cuidado:** esos datos **solo están en ese navegador/dispositivo** (hepatología nunca tuvo API ni servidor). No borrar
  los datos del sitio, ni probar en otro navegador esperando verlos. Si mientras tanto el médico carga cosas en
  "Clínica" del mismo paciente, cuando llegue la migración habrá datos en las dos HC: es el caso de choque de T49/T50, ya
  resuelto sin pérdida.
- Médicos con `hepatologia` y **sin perfil asignado** no pueden crear consultas nuevas en la HC general (RF-19: "Falta que el
  administrador te asigne un perfil"); antes de cambiarles la casilla, mirar en "Perfiles HC → Médicos" que tengan perfil.

### Orden de despliegue y convivencia de versiones
Ningún cambio de servidor es necesario (pregunta D1, opción A). Orden: (1) esta tanda entera (T47 a T53) en `hc-general`,
(2) prueba manual (T52), (3) recién ahí publicar la rama junto con la 002 (push a `main` publica la web; requiere pedido
explícito). Los servidores quedan como están.
- App vieja + turnos con `hepatologia` (hoy): sigue el problema (HC vieja). App nueva + turnos con `hepatologia`, `clinica`
  o las dos: una sola HC "Clínica", sin coordinar nada.
- El administrador que usa la app nueva y toca las casillas de un médico manda la lista normalizada (`clinica` en vez de
  `hepatologia`): turnos desactiva `hepatologia` y activa `clinica`. Un médico con app vieja que recibe eso abre la HC general
  y deja sus datos de `hepatologia` ocultos hasta actualizar (no perdidos).
- La web de turnos (`alta_medico`, `MedicoController@estadoPara`) sigue mostrando "Hepatología"; si alguien la tilda ahí, la
  app nueva la lee como `clinica`. Inofensivo.
- **No hay vuelta atrás en un dispositivo ya migrado:** la base queda en versión 9. En la web, un bundle viejo (versión 8)
  ve una base "de otra versión" y **descarta todas las tablas** (`DriverFactory.wasmJs.kt`, l. 43 a 46). En Android/iOS no se
  verificó; el SQLite estándar no permite bajar de versión. Si algo sale mal, se corrige hacia adelante.
- Si quedara una pestaña vieja abierta durante el despliegue y volviera a guardar la imagen de la base de la versión 8
  (no se verificó cómo lo trata el worker persistente), la migración es idempotente a propósito: la próxima carga la rehace.

### Riesgos
1. **Los datos de `hepatologia` solo existen en el dispositivo.** Un error de migración o borrar los datos del sitio los pierde.
   Mitigación: pruebas con choques e idempotencia (T50), conteos antes y después, y la prueba manual con datos de prueba.
2. **El driver web recrea la base vacía si una migración falla** (`DriverFactory.wasmJs.kt` l. 29 a 46): cualquier error en
   `8.sqm` en el navegador borra todo lo del navegador. Por eso `8.sqm` es chica, sin `DROP`, sin recrear tablas, sin
   cláusulas que dependan de la versión de SQLite, y `paciente_extra` se mueve con `NOT EXISTS` (una clave repetida
   haría fallar el `UPDATE`). Endurecer ese driver (no descartar nunca) es un cambio aparte: pregunta D5.
3. **Antecedentes repetidos:** `AntecedentesSeccion` indexa por clave (`associateBy`, gana la última) pero
   `HcRepository.guardarAntecedente` actualiza la primera: con dos filas por clave, editar parecería no hacer nada. Por eso
   T49 deja una sola activa por clave.
4. **Dos consultas abiertas** del mismo paciente y médico en `clinica`: `consultaAbierta` (`LIMIT 1`, la más reciente) abre
   una y la otra queda abierta en la lista; se pueden abrir las dos desde la lista y nada se pierde. Cerrar una sola es una
   edición clínica y no la hace una migración.
5. **Médicos con `hepatologia` sin perfil** (ver arriba): dejan de poder crear consultas hasta que el administrador les asigne
   uno. Revisar "Perfiles HC → Médicos" antes de publicar.
6. **002 (envío de consultas):** las consultas ex-hepatología quedan en `clinica` con `remoto_id = ''`, así que serán candidatas a
   `consultasSinEnviar("clinica")` cuando se registre el `HcBackend`: sale un envío en bloque de historial viejo. Es lo
   esperado, pero la 002 tiene que contemplarlo antes de enviar.
7. **`updated_at`/`dirty` intactos:** `SyncEngine` no está apagado: arranca tras cada login (`App.kt`) y cada minuto empuja las filas
   `dirty` a `<turnos>/api/salud360/sync/push` (y pide `sync/pull`), rutas que no existen en turnos (404 que se ignora; corregido en T54).
   Si alguien crea esa ruta en turnos, las filas movidas con `dirty = 1` se reenviarían con la HC nueva, junto con todas las demás
   filas `dirty` del dispositivo.
8. **Mantenimiento:** quien lea `ClinicaMedica.kt` puede creer que `hepatologia` es código muerto y borrarlo: el catálogo se
   rompería. El KDoc y el test de T51 lo fijan.
9. **Diferencia sql.js vs SQLite de la JVM** (los tests corren en la JVM): la prueba manual (T52, paso 1) es la que confirma la
   migración en el motor real de la web.

### Decididas (2026-10-26, usuario)
D1 a D7 = la opción recomendada de cada una: **D1-A** (normalizar solo en la app), **D2-A** (gana la modificada más reciente; la otra
queda `activo = 0`), **D3-A** (se conservan las dos consultas abiertas), **D4-A** (unión de las dos listas de secciones ocultas),
**D5-A** (no se toca el driver web), **D6-A** (toda la tanda, en orden T47 → T53), **D7-A** (`HcPerfilesE2ETest` afuera). El texto de las
preguntas queda como registro de las alternativas descartadas.
- **D1. Una habilitación de `hepatologia` que ya existe en turnos, ¿dónde se normaliza?**
  A) Solo en la app (T47, T48). Turnos y hclinica no cambian; no hay orden de despliegue. **Recomendada.**
  B) Además en turnos (Laravel, "preguntar antes"): `HistoriaClinicaService::habilitadas` devolvería `clinica` en vez de
     `hepatologia` y `CATALOGO` la sacaría de la web de turnos y del `PUT`. Limpia, pero las apps viejas con `hepatologia`
     en el `PUT` recibirían 422 y hay que desplegar turnos en coordinación.
  C) Además migrar las filas de `salud360_medico_hc` (`hepatologia` → `clinica`) en la base de turnos (producción). Dato
     real: solo con pedido y respaldo explícitos.
- **D2. Antecedente repetido (mismo paciente, categoría y clave en `clinica` y en `hepatologia`).**
  A) Gana la modificada más reciente (a igual fecha, la de `clinica`); la otra queda `activo = 0`, sin borrar. **Recomendada**
     (misma regla "lo último guardado manda" del resto de la app; deja una sola fila que se ve y se edita).
  B) Gana la que está tildada (`flag`), y a igual `flag` la más reciente: no esconde un antecedente positivo, pero
     una tilde vieja puede tapar una destilda nueva.
  C) Dejar las dos activas y corregir la pantalla para que lea y escriba la misma: toca `features/hc` y no es una migración.
- **D3. Paciente con consulta abierta en las dos HC.** A) Se conservan las dos abiertas, el médico cierra la que quiera.
  **Recomendada** (no se decide por él). B) La migración cierra la más vieja: es una edición clínica, no recomendada.
- **D4. Secciones ocultas.** A) Unión de lo oculto en las dos HC. **Recomendada** (RF-28: lo oculto se mantiene; lo que no
  está en el perfil se ignora igual). B) Gana la de `clinica`. C) Gana la de `hepatologia`.
- **D5. Driver web que recrea la base si la migración falla.** A) No tocarlo en esta tanda y mitigar con las pruebas y la
  prueba manual con datos de prueba. **Recomendada para D.** B) Cambio aparte, antes de publicar: que ante un fallo no
  descarte las tablas (por ejemplo, las deje y muestre un error). Toca `core/database` (`wasmJsMain`); requiere su
  aprobación y su propia tarea. C) Las dos cosas.
- **D6. Alcance y orden.** A) Toda la tanda, en orden (T47 → T53). **Recomendada.** B) Dividir: primero T47, T48 y T51 (arreglan
  lo que se ve; los datos viejos quedan ocultos, ver la regla de la tanda) y T49, T50 después, con su aprobación.
- **D7. `HcPerfilesE2ETest`** (fase 7 del plan original, nunca escrito; el plan lo menciona en las secciones 9 y 11). A) Fuera
  de esta tanda, en una tanda D2 aparte. **Recomendada** (no arregla este problema). B) Dentro de esta.

- [x] **T47. Normalización de los códigos de HC (lógica pura).** RF-29
  - `core/data/src/commonMain/kotlin/com/salud360/core/data/hcgeneral/HistoriasClinicas.kt` (nuevo, sin red ni base):
    `normalizarHistoriasClinicas(codigos: List<String>): List<String>` (cada código con `trim` y minúsculas; vacíos fuera;
    `"hepatologia"` → `CodigosHc.GENERAL`; sin repetidos, en el orden de la primera aparición),
    `normalizarCodigoHc(codigo: String?): String?` (lo mismo para uno solo) y `codigoHcPorNombre(nombre: String?): String?`
    (la tabla de `TurnosImportador.codigoHcPorNombre`, que hoy es un método `internal` de la clase y no se puede probar sin
    base, movida acá como función suelta; `"hepato"` → `CodigosHc.GENERAL`). `TurnosImportador` la llama.
  - `VinculoPacientes.kt` (`historiasConCartera`) y `AsignacionesMedicos.kt` (`medicosParaAsignar`, `CODIGOS_CON_PERFIL`)
    pasan por `normalizarHistoriasClinicas` en vez de su mapeo propio; el comentario de C8 ("sale en la tanda D") se
    reemplaza por la razón nueva. Su comportamiento no cambia.
  - Tests: `core/data/src/commonTest/.../hcgeneral/HistoriasClinicasTest.kt` (nuevo): `[]` → `[]`; `["hepatologia"]` →
    `["clinica"]`; `["clinica","hepatologia"]` y `["hepatologia","clinica"]` → `["clinica"]`; `["pediatria","hepatologia","hematologia"]`
    → `["pediatria","clinica","hematologia"]`; `[" Hepatologia ","HEPATOLOGIA"]` → `["clinica"]`; `["","  "]` → `[]`; códigos que
    no son hepatología intactos y en su orden; idempotente (`f(f(x)) == f(x)`); `normalizarCodigoHc` con `null`, `""`,
    `"hepatologia"`, `"pediatria"`; `codigoHcPorNombre`: "Hepatología" y "Hepatólogo" → `clinica`, "Clínica médica" →
    `clinica`, "Pediatría" → `pediatria`, "Ginecología y Obstetricia" → `gineco`, "Cardiología", "Endocrinología",
    "Hematología", "Desarrollo infantil", "Otra" → `null`, `null` → `null`. Se mantienen en verde
    `VinculoPacientesTest.hepatologia_cuenta_como_la_hc_general` y la primera de `AsignacionesMedicosTest` (renombrarla a
    `solo_los_que_tienen_la_hc_general_aunque_turnos_diga_hepatologia`).
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*HistoriasClinicasTest*' --tests '*VinculoPacientesTest*' --tests '*AsignacionesMedicosTest*'`
    en verde (`HistoriasClinicasTest` visto fallar antes del código) y `.\gradlew :core:data:jvmTest` completo en verde.
  - **Hecho (2026-10-26).** `HistoriasClinicasTest` visto fallar con un esqueleto que no hacía nada (7 de sus 10 tests, por aserción); después, los
    tres filtros en verde y `:core:data:jvmTest --rerun` completo en verde (579 tests, 0 fallas). Una desviación de "mover la tabla tal cual":
    `codigoHcPorNombre` busca `"hepat"` y no `"hepato"`, porque "Hepatólogo" (tilde en la ó) no contiene `"hepato"` y el test de la tarea lo pide.
    `TurnosImportador` ya no tiene su método `internal`: llama a la función suelta (un nombre "Hepatología" sugiere ahora `clinica`, no
    `hepatologia`). Las dos funciones de cartera y asignación pasan por `normalizarHistoriasClinicas`; `AsignacionesMedicos` ya no tiene
    `CODIGOS_CON_PERFIL`. La copia de `:server` no se toca.

- [x] **T48. Aplicar la normalización donde la app lee y guarda las HC habilitadas.** RF-29
  - Lectura (obligatoria, un solo punto): `core/data/.../mappers/Mappers.kt` l. 110, `MedicoRow.toModel`:
    `especialidades_hc.splitCsv()` → `normalizarHistoriasClinicas(...)`. Con eso la ven normalizada `resolverPerfil`
    (`MainShell` filtra con `registry.existe`), el administrador (casillas y chips), `PacientesHcRepository`,
    `AdminRepository.guardarLicencia` y `medicosParaAsignar`. También `EspecialidadRow.toModel` (l. 121): `codigoHc`
    por `normalizarCodigoHc` (la tabla "Especialidades" muestra "—" para un código que ya no está en el registro).
  - Escritura (para que la base quede limpia): `TurnosImportador.historiasClinicasDe` (`normalizarHistoriasClinicas` en
    sus tres salidas) e `importarEspecialidad` (`previa?.codigoHc` por `normalizarCodigoHc`);
    `AgendaTurnosOnline.guardarHistoriasClinicas` (l. 434 a 436: guarda y devuelve la lista normalizada) y
    `sincronizarAdministracion` (l. 468). `AdminViewModel.setEspecialidadHc` no cambia: parte de `m.especialidadesHc`, ya
    normalizada, así que el administrador manda `clinica` en vez de `hepatologia` (turnos desactiva la fila, no la borra).
  - Tests: `core/data/src/jvmTest/.../repos/HistoriasHabilitadasTest.kt` (nuevo, SQLite en archivo temporal, como
    `PacientesHcRepositoryTest`; datos inventados): un `medico` con `especialidades_hc = 'hepatologia'` →
    `medicoPorId(...).toModel().especialidadesHc == ["clinica"]` y `resolverPerfil(...).medico.especialidades == ["clinica"]`; con
    `'pediatria,hepatologia,clinica'` → `["pediatria","clinica"]`; `TurnosImportador.importar` de un `TobbPerfil` (armado como
    en `TobbPerfilTest`) con `historiasClinicas = ["hepatologia","pediatria"]` deja `["clinica","pediatria"]` en la base;
    el mismo perfil sin `historias_clinicas` y con la especialidad de turnos "Hepatología" deja `["clinica"]` (fallback);
    una `especialidad` previa con `codigo_hc = 'hepatologia'` sale como `clinica`. Las dos escrituras de `AgendaTurnosOnline`
    necesitan red: se verifican por compilación y en T52 (paso 8).
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*HistoriasHabilitadasTest*'` en verde (visto fallar antes) y
    `.\gradlew :core:data:jvmTest` completo en verde; `.\gradlew :composeApp:compileKotlinWasmJs` (con las `-P` de arriba) compila.
  - **Hecho (2026-10-26).** `HistoriasHabilitadasTest` (9 tests) visto fallar antes del código (7 por aserción) y en verde después;
    `:core:data:jvmTest --rerun` completo en verde (588 tests, 0 fallas); compilan `:features:admin:compileKotlinJvm` y
    `:composeApp:compileKotlinWasmJs` (solo el klib: no se generó el ejecutable web). Verificado con grep que `MedicoRow.toModel` y
    `EspecialidadRow.toModel` son los únicos lugares que leen `especialidades_hc` / `codigo_hc` (ningún `.sq` filtra por ellos, y
    `SyncAdapters`, `AdminRepository` y `resolverPerfil` pasan por `toModel`). Lectura: `Mappers.kt` (`MedicoRow.toModel` y
    `EspecialidadRow.toModel`). Escritura: `TurnosImportador` (`historiasClinicasDe` normaliza sus tres salidas; `importarEspecialidad`
    con `normalizarCodigoHc(previa?.codigoHc)`), `AgendaTurnosOnline.sincronizarAdministracion` y `guardarHistoriasClinicas`.
    Leer no reescribe la base: una fila que turnos informó como `hepatologia` queda así hasta el próximo ingreso o guardado (sin
    `dirty`). Un cambio sobre lo planeado: `guardarHistoriasClinicas` **también normaliza lo que manda** (`hepatologia` nunca
    sale hacia turnos, aunque alguien la tilde mientras la casilla siga, hasta T51) y devuelve/guarda la lista normalizada de
    la respuesta.
    **Cómo queda una fila `hepatologia` activa en turnos tras guardar desde la app nueva** (verificado leyendo
    `HistoriaClinicaController@actualizar` y `HistoriaClinicaService::guardar`, sin tocarlos): el administrador parte de la
    lista normalizada (`clinica`, + las demás) y la manda completa; `guardar` pone `activo = 0` a toda fila que no está en la
    lista (la de `hepatologia`, no se borra) y para `clinica` inserta la fila o la reactiva (`activo = 1`). Resultado: `hepatologia`
    queda inactiva, `clinica` activa, las demás HC igual; el `PUT` responde las activas (`clinica`, …) y `auth/perfil` también, así
    que el médico no pierde la HC general ni se le queda `hepatologia` activa. Si el administrador guarda otra HC de un médico que
    solo tenía `hepatologia`, pasa lo mismo (se manda `clinica` + la otra). Una app vieja que lo vuelva a leer verá `clinica`.
    La web de turnos sigue mostrando "Hepatología" como casilla (sin cambios).

- [x] **T49. Migración `8.sqm` (v8 → v9): lo guardado como `hepatologia` pasa a `clinica`.** RF-29, RF-30
  **Aprobada expresamente por el usuario el 2026-10-26** (migración de la base del dispositivo con datos
  clínicos que solo existen ahí; constitución #3, AGENTS.md "migraciones de esquema"). La numeración de la 001 (`7.sqm`) ya
  la usó la 003 (`hc_enfermedades`); esta es la siguiente libre.
  - `core/database/src/commonMain/sqldelight/com/salud360/core/database/migrations/8.sqm` (nuevo; ningún `.sq` cambia, no
    hay columnas ni tablas nuevas; `verifyMigrations` está apagado, el test es el único control). Borrador a validar con los
    tests; si un test lo contradice, manda el test:
    ```sql
    -- 1) Antecedentes repetidos (misma clave del mismo paciente en las dos HC): queda activa la modificada más reciente
    --    (a igual fecha, la de clinica) y la otra pasa a activo = 0. No se borra nada.
    UPDATE antecedente SET activo = 0
    WHERE especialidad = 'hepatologia' AND activo = 1 AND deleted = 0 AND EXISTS (
        SELECT 1 FROM antecedente c WHERE c.especialidad = 'clinica' AND c.paciente_id = antecedente.paciente_id
          AND c.categoria = antecedente.categoria AND c.clave = antecedente.clave
          AND c.activo = 1 AND c.deleted = 0 AND c.updated_at >= antecedente.updated_at);
    UPDATE antecedente SET activo = 0
    WHERE especialidad = 'clinica' AND activo = 1 AND deleted = 0 AND EXISTS (
        SELECT 1 FROM antecedente h WHERE h.especialidad = 'hepatologia' AND h.paciente_id = antecedente.paciente_id
          AND h.categoria = antecedente.categoria AND h.clave = antecedente.clave
          AND h.activo = 1 AND h.deleted = 0 AND h.updated_at > antecedente.updated_at);
    -- 2) Lo que no tiene clave única con la HC: pasa tal cual (sin tocar updated_at ni dirty).
    UPDATE consulta SET especialidad = 'clinica' WHERE especialidad = 'hepatologia';
    UPDATE antecedente SET especialidad = 'clinica' WHERE especialidad = 'hepatologia';
    UPDATE registro_clinico SET especialidad = 'clinica' WHERE especialidad = 'hepatologia';
    UPDATE laboratorio SET especialidad = 'clinica' WHERE especialidad = 'hepatologia';
    -- 3) paciente_extra tiene PRIMARY KEY (paciente_id, especialidad, clave): solo pasa la que no choca; la repetida
    --    queda donde estaba (no se pierde, y la migración no falla).
    UPDATE paciente_extra SET especialidad = 'clinica'
    WHERE especialidad = 'hepatologia' AND NOT EXISTS (
        SELECT 1 FROM paciente_extra c WHERE c.paciente_id = paciente_extra.paciente_id
          AND c.especialidad = 'clinica' AND c.clave = paciente_extra.clave);
    -- 4) Secciones ocultas: unión de las dos listas; la clave vieja queda con deleted = 1 (no se borra).
    INSERT OR REPLACE INTO medico_preferencia (medico_id, clave, valor, updated_at, deleted, dirty)
    SELECT h.medico_id, 'secciones_ocultas_clinica',
           CASE WHEN c.valor IS NULL OR c.valor = '' THEN h.valor
                WHEN h.valor = '' THEN c.valor
                ELSE c.valor || ',' || h.valor END,
           MAX(h.updated_at, COALESCE(c.updated_at, 0)), 0, COALESCE(c.dirty, h.dirty)
    FROM medico_preferencia h
    LEFT JOIN medico_preferencia c ON c.medico_id = h.medico_id AND c.clave = 'secciones_ocultas_clinica' AND c.deleted = 0
    WHERE h.clave = 'secciones_ocultas_hepatologia' AND h.deleted = 0;
    UPDATE medico_preferencia SET deleted = 1 WHERE clave = 'secciones_ocultas_hepatologia' AND deleted = 0;
    ```
    Idempotente: la segunda corrida no encuentra nada que mover (todo `hepatologia` ya pasó o quedó `deleted = 1`). No toca
    `medico`, `especialidad`, `hc_config`, `interconsultor` ni `turno`. Si SQLDelight no compila alguna forma, se reescribe
    equivalente (nunca con `DROP`, ni recreando tablas, ni `INSERT OR REPLACE` sobre las tablas clínicas).
  - Primer test, antes de escribir el `.sqm`: `core/data/src/jvmTest/.../repos/MigracionHepatologiaTest.kt` (nuevo; SQLite
    en archivo temporal como `ConfigHcRepositoryTest`; base creada con el esquema actual, sembrada con `INSERT` de columnas
    nombradas y datos inventados —ids `p-prueba-1`, `m-prueba-1`, nada real—, y migrada con
    `Salud360Db.Schema.synchronous().migrate(driver, 8, 9)`):
    1. `consulta`: tres de `hepatologia` (ABIERTA con `dirty = 1`, CERRADA con `remoto_id = ''`, ANULADA), una de `clinica` y una de
       `pediatria` → ninguna de `hepatologia`; mismos ids; cada fila igual a la de antes salvo `especialidad` (`estado`, `fecha`,
       `tipo`, `updated_at`, `dirty`, `deleted`, `remoto_id`); la de pediatría intacta.
    2. `seccion_valor`, `examen_fisico`, `consulta_diagnostico` y `archivo` de esas consultas siguen ahí y se leen por
       `valoresDeConsulta(consultaId)` y `examenFisicoDeConsulta(consultaId)`.
    3. `registro_clinico` (con `campos_json`), `laboratorio` (con `valores_json`) y `antecedente` sin choque pasan a `clinica`,
       con el contenido intacto.
    4. `interconsultor.especialidad = 'hepatologia'` (texto libre) y un `turno` con `especialidad` de texto **no cambian**.
    5. `hc_config`: sin filas `hepatologia` antes ni después.
    6. El driver: una base con `PRAGMA user_version = 8`, sembrada, se abre con `DriverFactory(ruta).createDriver()` (como
       `al_abrir_una_base_de_la_version_6_el_driver_la_migra`), queda en versión 9 y migrada, y conserva lo que ya tenía
       (`sync_estado`).
    7. `Salud360Db.Schema.version == 9`.
  - Versión del esquema en los tests que la fijan: `ConfigHcRepositoryTest` (l. 207 y 242: `8L` → `9L`) y `CopiaEnfermedadesTest`
    (l. 433 y 470: `8L` → `9L`); las llamadas `migrate(driver, 6, 7)` y `migrate(driver, 7, 8)` quedan igual. Es el único cambio a
    tests existentes de esta tarea.
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*MigracionHepatologiaTest*' --tests '*ConfigHcRepositoryTest*' --tests '*CopiaEnfermedadesTest*'`
    en verde (`MigracionHepatologiaTest` visto fallar antes del `.sqm`); `.\gradlew :core:data:jvmTest` completo en verde;
    `.\gradlew :composeApp:compileKotlinWasmJs -Pentorno=dev` (con las `-P`) compila (driver web con `generateAsync`);
    `git diff --stat` muestra `8.sqm` nuevo y ningún `.sq` cambiado. **No se refresca el `devWeb` hasta cerrar T50.**
  - **Hecho (2026-10-26).** `8.sqm` es el borrador de la tarea sin cambios (las cuatro partes; ningún `.sq` cambia). `MigracionHepatologiaTest` (7 tests,
    los de la tarea) visto fallar antes del `.sqm` (4 por aserción: versión 8 en vez de 9 y filas todavía en `hepatologia`); los otros tres afirman
    "no cambia" y pasaban de entrada, así que se comprobaron mutando el `.sqm` a propósito (agregándole `UPDATE` sobre `interconsultor`, `turno`,
    `hc_config`, `archivo` y `seccion_valor`): los tres fallaron y se restauró. El esquema sube de versión por la cantidad de migraciones
    (`Schema.version` = 1 + cantidad de `.sqm`): sin tocar ningún `.sq`, 8 → 9 por el solo hecho de existir `8.sqm`. `ConfigHcRepositoryTest` y
    `CopiaEnfermedadesTest`: `8L` → `9L` (con comentario), único cambio a tests existentes. `:core:data:jvmTest --rerun` completo en verde
    (595 tests, 0 fallas) y compila `:composeApp:compileKotlinWasmJs -Pentorno=dev` (solo el klib). `:core:database` no tiene tests propios.
    **Límite con T50:** T49 prueba el movimiento sin choques (los tests 1 a 7 de arriba); la parte de choques del `.sqm` (antecedente repetido,
    `paciente_extra` repetido, secciones ocultas) está escrita y se ejecuta (sin errores) pero sus resultados, la idempotencia, el conteo
    `count(*)`/`sum(dirty)` y la prueba con los repositorios son de T50. Para T50: el caso 4a (solo `secciones_ocultas_hepatologia`) **agrega** una fila
    a `medico_preferencia` (la `clinica` nueva + la vieja con `deleted = 1`), así que el "mismo `count(*)`" del caso 6 no vale para esa tabla en ese
    caso; el borrador copia el `dirty` de la fila de `clinica` (o el de la vieja si no hay) y no el máximo de las dos.

- [x] **T50. La migración con choques, idempotencia y sin pérdida.** RF-29, RF-30
  **Misma aprobación explícita que T49, del 2026-10-26** (es la otra mitad de `8.sqm`). Mismo archivo de test; si algún caso falla, se
  corrige `8.sqm` (todavía sin publicar ni cargar en ningún navegador).
  - Tests en `MigracionHepatologiaTest` (cada uno visto fallar o, si ya pasa por T49, mutando a propósito el `.sqm` para comprobar
    que el test lo detecta):
    1. Consultas: el mismo paciente y médico con una ABIERTA en `clinica` y otra en `hepatologia` → quedan las dos ABIERTA en
       `clinica`, con su `dirty`; `consultasDePaciente(paciente, 'clinica')` trae las dos; `consultaAbierta` devuelve la más reciente
       y ninguna se borra.
    2. Antecedentes: (a) `hepatologia` más nueva → gana (la de `clinica` queda `activo = 0`); (b) `clinica` más nueva → gana; (c) misma
       `updated_at` → gana `clinica`; (d) misma clave en otra categoría o en otro paciente → no chocan; (e) la fila perdedora conserva
       `flag` y `detalle` (no se borró); (f) `antecedentesDeCategoria(paciente, 'clinica', categoria)` trae una sola fila por clave; (g) una
       fila `deleted = 1` o `activo = 0` de antes no cuenta como choque.
    3. `paciente_extra`: una fila que choca (misma clave en `clinica`) → la migración **no falla**, `clinica` conserva su valor y la de
       `hepatologia` queda en `hepatologia`; una que no choca se mueve.
    4. Preferencias: (a) solo `secciones_ocultas_hepatologia` = `"a,b"` → `secciones_ocultas_clinica` = `"a,b"` y la vieja `deleted = 1`;
       (b) las dos (`"x"` y `"a,b"`) → `"x,a,b"` (leída como conjunto, sin pisar `x`); (c) la de `clinica` vacía; (d) la de `hepatologia`
       vacía; (e) la de `clinica` con `deleted = 1` más la de `hepatologia` → una `clinica` viva; (f) otro médico y otras claves
       (`filas_ocultas_clinica_laboratorio_general`) intactos; (g) `ConsultaViewModel` y `ConfigSeccionesHcScreen` leen con
       `split(',')...toSet()`: un test lo fija leyendo el resultado de (b) con esa misma regla.
    5. Idempotencia: `migrate(8, 9)` dos veces seguidas deja todas las tablas de (1) a (4) idénticas a la primera corrida (comparación de
       un volcado de `consulta`, `antecedente`, `registro_clinico`, `laboratorio`, `paciente_extra`, `medico_preferencia`).
    6. Sin pérdida: para `consulta`, `seccion_valor`, `registro_clinico`, `laboratorio`, `antecedente`, `paciente_extra` y
       `medico_preferencia`, `count(*)` y `sum(dirty)` antes y después son iguales (solo `antecedente.activo` y
       `medico_preferencia.deleted` cambian, y se comprueba cuántas).
    7. De punta a punta con los repositorios (como `DatosConsultaTest` y `ValoresDeCampoDePacienteTest`): después de migrar,
       `HcRepository.observarConsultas`, `observarRegistros`, `observarLaboratorios`, `observarAntecedentes` y
       `observarValoresDeCampo(paciente, "clinica", "motivo_consulta", "texto")` traen lo que estaba en `hepatologia` (caso límite de
       la spec: los motivos de la hepatología aparte salen en las consultas previas de la HC general).
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*MigracionHepatologiaTest*'` en verde y `.\gradlew :core:data:jvmTest` completo
    en verde. Recién ahí `8.sqm` queda lista para cargarse en un navegador.
  - **Hecho (2026-10-26).** `8.sqm` NO cambió (es la de T49): todos los casos de choque ya pasaban de entrada, así que se comprobó que cada test
    detecta su parte mutando el `.sqm` a propósito (y restaurándolo): `>=` por `>` en el antecedente (falla "misma fecha"), sin el segundo
    `UPDATE` de antecedentes (falla "hepatologia más nueva"), sin `activo = 1 AND deleted = 0` del `EXISTS` (falla "borrado o inactivo"),
    `ELSE h.valor` en la unión (falla "se unen sin pisar") y sin el `UPDATE ... deleted = 1` final (fallan los de preferencias); sin
    `h.deleted = 0` en la unión (falla idempotencia); un `DELETE` de antecedentes (fallan "la perdedora no se borra" y "ninguna fila se
    pierde"); `UPDATE OR REPLACE` en `paciente_extra` (fallan los dos de `paciente_extra`); y `paciente_extra` sin `NOT EXISTS` (la migración
    falla por la clave primaria y falla todo lo que migra con choques). `MigracionHepatologiaTest` pasa de 7 a 29 tests, con datos inventados
    (`p-prueba-2/3/4`, `m-pref-*`): consultas abiertas en las dos HC (`consultaAbierta` devuelve la más reciente, `consultasDePaciente` las
    dos); antecedentes (a) a (g) más "nada más cambia" (volcado completo: solo la HC y el `activo` de las tres perdedoras, mismo `dirty` y
    `updated_at`); `paciente_extra` que choca / límite conocido / otras HC; preferencias solo-hepatologia, ambas, vacías, clinica borrada, otros
    médicos y claves; idempotencia (2 y 3 corridas, una edición posterior, una app vieja que vuelve a guardar `hepatologia`); conteos y `dirty`
    antes/después; filas de otras HC intactas; y la lectura con `HcRepository` (`observarConsultas`, `abrirConsulta`, `observarRegistros`,
    `observarLaboratorios`, `observarAntecedentes`, `observarValoresDeCampo`, `observarPreferencias`) y `PacientesRepository.observarExtras`.
    `:core:data:jvmTest --rerun` completo en verde (617 tests, 0 fallas). **Los tres puntos que dejó T49:** (1) `medico_preferencia` gana una fila
    por cada médico con `secciones_ocultas_hepatologia` viva y sin ninguna fila `clinica`, ni borrada (esa se reemplaza); el test lo calcula con SQL
    antes de migrar (en la siembra, 1) y compara; (2) el `dirty` de la unión es el de la fila de `clinica` si existía y, si no, el de la vieja (no el
    máximo): un test lo fija sin cambiarlo; la preferencia no la recibe ninguna API que use la app, así que no se pierde; (3) **límite conocido:**
    `paciente_extra` de `hepatologia` frente a una fila `deleted = 1` de `clinica`: el dato vivo queda en `hepatologia` (no se pierde ni se toca
    `clinica`), pero la HC general no lo ve; un test lo fija y el KDoc de la clase lo explica; no se reescriben filas clínicas para arreglarlo.
    `8.sqm` ya se puede cargar en un navegador (el `devWeb` se puede refrescar con la v9). Quedan T51 a T53.

- [x] **T51. Hepatología fuera del registro y de lo que ofrece el administrador.** RF-29 (retira C5 y C8)
  - `composeApp/.../AppDi.kt`: se quita el import y `hepatologiaContribution` de `especialidades`. Con eso desaparecen solos la
    casilla "Hepatología" del alta y de la ficha (`AdminScreens.kt` l. 142 y 215 leen del registro) y el chip; el médico con
    `hepatologia` en turnos ve una sola HC "Clínica" (T48).
  - `especialidades/clinica/.../ClinicaMedica.kt`: se borra `hepatologiaContribution`; `val hepatologia` pasa a `internal` con KDoc
    ("ya no es una historia clínica: fuente de las secciones, laboratorios y registros de `catalogoHcGeneral`; no borrar"). Los comentarios
    de `HcGeneral.kt` (l. 18, 40 y 46: "hasta la tanda D") y de `AsignacionesMedicos.kt` quedan actualizados.
  - `CatalogoHcGeneralTest` (los tests que fijan `hepatologia`; decidido con la spec en la mano: RF-29/RF-30 piden que lo que la
    hepatología guardaba se siga leyendo desde la HC general, no que su definición siga igual):
    - `hepatologia_sigue_igual` → `las_secciones_de_hepatologia_se_leen_desde_la_hc_general`: para cada sección de `hepatologia.secciones`
      su id está en `catalogoHcGeneral` (o en `legadas`) con los mismos `campos` (clave y tipo), `registroTipo`, `laboratorioTipo` y
      `categoriaAntecedentes`; cada laboratorio de `hepatologia` (`laboratorio_general`, `laboratorio_reducido`, `peso`) y cada registro
      que usa una sección está en el catálogo con las mismas claves de analito o de campo; las categorías de antecedentes de
      `hepatologia` existen en el catálogo o en las legadas (RF-30: lo guardado se sigue leyendo). Se van las aserciones de código,
      nombre, `tiposConsulta`, `hepatologiaContribution.renderers` y `hepatologiaContribution.catalogo` (l. 302 a 313, 335 y 345).
    - Se **mantienen**: `clinica_medica_sigue_igual`; en `los_estudios_con_historial...` (l. 137), `ninguna_otra_seccion...` (l. 168) y
      `solo_el_motivo...` (l. 182) las aserciones sobre `hepatologia` siguen valiendo (la copia del catálogo no cambia lo compartido), con el
      comentario "hepatología, que se va en la tanda D" (l. 134) reescrito; `la_contribucion_registra_la_hc_general...` pierde solo la
      línea de `hepatologiaContribution.catalogo`; `copia_de_diagnosticos_con_el_catalogo_real` pierde `copiaDiagnosticos(hepatologia)`
      (l. 395: ya no es una HC). El fixture `hepatologiaInicial` (el **perfil** "Hepatología") queda: es lo que se arma con `armarDefinicion`.
  - Hecho cuando: `.\gradlew :especialidades:clinica:jvmTest` en verde (el test nuevo visto fallar antes de ajustar los otros);
    `git grep -n hepatologiaContribution -- '*.kt'` no da resultados; `.\gradlew :core:data:jvmTest` completo en verde;
    `.\gradlew :features:admin:compileKotlinJvm`, `.\gradlew :features:hc:compileKotlinJvm` y `.\gradlew :composeApp:compileKotlinWasmJs`
    (con las `-P`) compilan.
  - **Hecho (2026-10-27).** `hepatologiaContribution` borrada (ningún `.kt` la nombra); `AppDi.kt` ya no la importa ni la registra;
    `val hepatologia` es `internal` con KDoc (fuente del catálogo, no borrar). `hepatologia_sigue_igual` →
    `las_secciones_de_hepatologia_se_leen_desde_la_hc_general`, más las tres aserciones que nombraban la contribución (`.catalogo`, `.renderers`
    y `copiaDiagnosticos(hepatologia)`) y el comentario de la l. 134; los comentarios de `HcGeneral.kt` ya no dicen "hasta la tanda D" (el de
    `AsignacionesMedicos.kt` lo había cambiado T47). **Sobre el rojo:** no había nada que pudiera fallar antes del código (el test nuevo
    compara cosas que ya existen y la lista del registro vive en `composeApp`, que no tiene tests), así que el test se comprobó mutando:
    como `catalogoHcGeneral` copia de `hepatologia`, comparar solo contra ella es tautológico (mutar la clave de una serología no lo hacía
    fallar), por eso el test también fija a mano los ids de las 21 secciones, los analitos del laboratorio reducido y del peso y el tamaño del
    general; con "Hemograma" → "Hemograma X" falla y se restauró. Las claves de las serologías ya las fija `serologias_en_grilla...`. Verde:
    `:especialidades:clinica:jvmTest --rerun` (25 tests), `:core:data:jvmTest --rerun` (617, 0 fallas), `:core:ui:jvmTest`,
    `:features:admin:compileKotlinJvm`, `:features:hc:compileKotlinJvm` y `:composeApp:compileKotlinWasmJs -Pentorno=dev` (solo el klib).
    **Verificado leyendo el código:** `AdminViewModel.especialidadesHc` sale de `nombresHc` de `AppDi` (= el registro), así que el alta
    (`AdminScreens` l. 142), la ficha (l. 215), la asociación de especialidades de turnos (l. 303) y la tabla ya no ofrecen "Hepatología";
    el chip de la ficha muestra el código crudo si hubiera uno no registrado (`?: it`), pero `MedicoRow.toModel` ya lo normaliza (T48);
    `PerfilHcGeneral` depende de `CodigosHc.GENERAL in m.especialidadesHc`, no del registro. Una especialidad desconocida no rompe nada:
    `MainShell` filtra con `registry.existe`, `HistoriaClinicaScreen` muestra "no está disponible en esta versión", `ConsultaViewModel` tolera
    `definicion == null`, `registry.nombre` y `nombresHc[...] ?: codigo` caen al código y `Salud360Colors.especialidad` al color por
    defecto. Ningún otro código del repo (salvo `:server`, que no se toca) mira `hepatologiaContribution` ni el código `hepatologia`.

- [x] **T52. Prueba manual del usuario (`devWeb` contra la copia local) y limpieza.** RF-29, RF-30, RF-33 — **cerrada con excepciones aceptadas por el usuario (2026-10-28); no se hicieron todos los pasos, ver la nota al final de la tarea**
  Hecha por el usuario; la tarea no se marca hasta que confirme. Solo con datos de prueba y solo local (nunca producción).
  - Antes de refrescar (con el build viejo todavía cargado), en el navegador que ya tiene datos de Hepatología:
    **P1.** Entrar como el médico de prueba con "Hepatología". Elegir dos pacientes de prueba con consultas en Hepatología y anotar de
    cada uno: cuántas consultas (abiertas y cerradas), un motivo de consulta, una carga de laboratorio, serologías o Child-Pugh si hay, un
    antecedente familiar tildado, y qué secciones tiene ocultas en Configuración. **P2.** Si alguno ya tiene consultas en "Clínica", anotar
    cuántas (es el caso de choque). **P3.** Esos datos solo están en ese navegador: confirmar que son de prueba antes de seguir (si no lo
    son, parar y avisar; no borrar los datos del sitio).
  - Pasos (con `wasmJsDevelopmentExecutableCompileSync -Pentorno=dev` y las dos `-P`, y recargando la página):
    1. La consola del navegador **no** muestra "no se pudo migrar la base del navegador". Si lo muestra: parar, la base se recreó vacía.
    2. Administración → Médicos → tarjeta del médico: las casillas de HC son Clínica, Pediatría, etc., **sin Hepatología**, y los chips dicen
       "Clínica". En "Nuevo usuario" con rol Médico tampoco hay casilla Hepatología.
    3. Como el médico (con turnos todavía diciendo `hepatologia`, como hoy): una sola HC "Clínica", con el perfil "Hepatología" (pestañas, serologías
       en grilla, Child-Pugh en tabla, estudios con consultas previas), no la HC vieja.
    4. Los dos pacientes de P1: "Clínica" muestra las consultas anotadas (más las de P2), con las mismas abiertas y cerradas. Una consulta cerrada
       muestra motivo, laboratorio, serologías, Child-Pugh y antecedente iguales a lo anotado; en una consulta nueva, el motivo viejo sale en
       "Consultas previas".
    5. Choque: el paciente de P2 muestra todas sus consultas (ninguna perdida); un antecedente que estaba en las dos HC se ve una sola vez
       y al tildar/destildar el cambio se ve.
    6. Configuración → Clínica: las secciones que ocultabas en Hepatología siguen ocultas.
    7. Cerrar sesión, volver a entrar y recargar la página: los mismos números, sin duplicados ni faltantes.
    8. Administración: destildar y volver a tildar "Clínica" en el médico de prueba. En la base local de turnos
       (`SELECT hc_codigo, activo FROM salud360_medico_hc WHERE medico_id = <n>;`): `hepatologia` con `activo = 0` y `clinica` con `activo = 1`;
       el selector de perfil sigue mostrando el perfil.
    9. Un médico con pediatría (si hay) ve Pediatría igual que antes (RF-33).
    10. "Perfiles HC → Médicos": todo médico que tenía `hepatologia` tiene un perfil (o "Sin perfil" a propósito).
  - Limpieza: dejar las HC habilitadas del médico de prueba como estaban antes de la prueba (con la app o con un `UPDATE` puntual en la base
    local de turnos); borrar solo lo que la prueba creó con nombre "Prueba D" (pacientes o consultas); **no** borrar lo migrado; restaurar la
    licencia del médico de prueba como dice `MEMORY.md` si se extendió; no vaciar la base local de hclinica. Nada que commitear.
  - Hecho cuando: el usuario confirma los pasos 1 a 10 y la limpieza.
  - **Cerrada con excepciones aceptadas por el usuario el 2026-10-28** (junto con el cierre de la spec 003, "marcá
    enfermedades como terminada"). El "Hecho cuando" de arriba **no se cumplió entero**: la `[x]` no quiere decir que los
    10 pasos se hayan hecho.
    - **Confirmado por el usuario** (lo único): en el `devWeb` contra la copia local ya migrada, no aparece "no se pudo
      migrar la base del navegador" (paso 1); la HC vieja ya no existe como casilla (paso 2); el médico ve una sola HC,
      Clínica, con el perfil Hepatología (paso 3); y una consulta creada con un perfil se ve al cambiar de perfil, con las
      secciones que quedan fuera del perfil en solo lectura (spec 001, RF-22).
    - **No se pudo hacer: la comparación antes/después** (P1 a P3 y paso 4: que las consultas, el laboratorio, las
      serologías y los antecedentes de los pacientes de hepatología quedaran iguales a lo anotado). La base del navegador
      ya estaba migrada cuando se la respaldó, así que no existe el "antes". El respaldo posterior a la migración quedó
      fuera del repo, en `%TEMP%\opencode\respaldos`. Esto deja sin prueba manual la preservación de datos de `8.sqm` (RF-30);
      queda cubierta solo por `MigracionHepatologiaTest` y por la validación del reviewer de la tanda D.
    - **Sin confirmación** (no se dan por verificados): el paso 5 (choque, con el antecedente repetido y los cambios que
      se ven), el 6 (secciones ocultas en Configuración), el 7 (cerrar sesión, volver a entrar y recargar sin duplicados),
      el 8 (destildar y volver a tildar «Clínica» y mirar `salud360_medico_hc` en turnos), el 9 (médico con pediatría), el 10
      («Perfiles HC → Médicos»), el resto del paso 2 (chips y «Nuevo usuario») y la limpieza propia de esta tarea.

- [x] **T53. Cierre de la tanda: documentación.** RF-29
  - `AGENTS.md` (decisión 7 de la tanda B): la línea de "Tests" pasa a `:core:data:jvmTest`, `:core:crecimiento:jvmTest`, `:core:ui:jvmTest` y
    `:especialidades:clinica:jvmTest`, y se quita "(solo hay tests ahí)" (hoy hay `CatalogoHcGeneralTest` y `ChildPughTest` en
    `especialidades/clinica`; no hay tests en `features/*` ni en `composeApp`).
  - `MEMORY.md`: tanda D hecha (qué hace `8.sqm`, que la base está en v9 y **no hay vuelta atrás en un dispositivo migrado**, el aprendizaje de
    `hepatologia` como `internal val` fuente del catálogo, y los pendientes: 002 y el envío de lo ex-hepatología, `HcPerfilesE2ETest` si no se
    hizo). Sin datos sensibles.
  - `plan.md`: las "Notas de la tanda D" del final pasan a "Decidido"; la línea 385 de `specs/003-hc-enfermedades/plan.md`
    (`hepatologia_sigue_igual`) apunta al test nuevo.
  - `spec.md` (**para que el usuario apruebe, no se edita acá**): quitar "hasta la tanda D" y "hepatología aparte" de RF-73 (l. 288), RF-82
    (l. 322), RF-33 (l. 348) y del caso límite de motivos (l. 450); el sentido no cambia, la HC aparte ya no existe.
  - Hecho cuando: la validación del reviewer (RF por RF: RF-29 con `HistoriasClinicasTest`, `HistoriasHabilitadasTest` y T52; RF-30 con
    `MigracionHepatologiaTest` y `CatalogoHcGeneralTest`), `.\gradlew :core:data:jvmTest :core:ui:jvmTest :especialidades:clinica:jvmTest`
    en verde, `.\gradlew :composeApp:compileKotlinWasmJs -Pentorno=dev` (con las `-P`) compila, y `git status` solo muestra archivos de la tanda.
  - **Hecho (2026-10-27).** Solo documentación (no se tocó código ni tests). Se corrieron, uno por vez, `:core:data:jvmTest`, `:core:ui:jvmTest`,
    `:especialidades:clinica:jvmTest` y `:composeApp:compileKotlinWasmJs -Pentorno=dev` (con las `-P`): los cuatro `BUILD SUCCESSFUL`, pero
    con las tareas `UP-TO-DATE` (nada de código cambió desde la última corrida verde de T51, 617 tests en `:core:data`), así que confirman ese
    resultado y no volvieron a ejecutar los tests. La validación del reviewer de la tanda queda aparte. `AGENTS.md`: la línea de tests
    suma `:especialidades:clinica:jvmTest` (verificado: hay `CatalogoHcGeneralTest` y `ChildPughTest` en su `commonTest`; no hay tests en
    `features/*`, `composeApp` ni `:server`), y se corrigieron afirmaciones que habían quedado viejas: los E2E de la HC general
    (`HcClinicaPacientesE2ETest` y `HcClinicaEnfermedadesE2ETest`, con sus propiedades `.clinica`), que hepatología es un perfil y no una HC (con
    la trampa de no borrar `internal val hepatologia`) y que una migración que solo mueve datos (`8.sqm`) no toca el `.sq` y no tiene vuelta
    atrás en una base ya migrada. `plan.md` de la 001: las "Notas de la tanda D" pasan a "Decidido en la tanda D" (el texto de arriba se conserva
    como se aprobó y manda la sección nueva donde difieren: `7.sqm` → `8.sqm`); `specs/003-hc-enfermedades/plan.md` (RF-57) apunta a
    `las_secciones_de_hepatologia_se_leen_desde_la_hc_general`. `spec.md` (el usuario aprobó la tanda el 2026-10-26; solo redacción, sin cambiar
    ningún RF): se quitó "hasta la tanda D" y la hepatología aparte de RF-73, RF-82 y RF-90 (la tarea decía "RF-33", pero esa línea 348 es RF-90) y se
    reescribió el caso límite de los motivos ("salen... porque esas consultas pasaron a `clinica`"). No se tocó el registro del cambio del
    2026-10-15 (2) ("Vale para la HC general y para la hepatología aparte", l. 513): es historia de un cambio aprobado. `MEMORY.md`: traspaso al
    día. No se tocan las menciones históricas a `hepatologia_sigue_igual` en `specs/003-hc-enfermedades/tasks.md` (notas de tareas ya hechas).

- [x] **T54. Cierre de la validación del reviewer (hallazgos H1 a H8 de la tanda D).** RF-29, RF-30
  Aprobada por el usuario el 2026-10-27 tras el "APROBADO CON OBSERVACIONES" del reviewer. El usuario **no** aprobó reescribir historia
  ahora: se anota para resolverlo justo antes del push. No se toca `8.sqm`, ni pantallas, ni la spec 003, ni las APIs Laravel.
  1. **Historia (decisión del usuario):** `MEMORY.md` anota que antes de pushear la rama hay que sacar de la historia un nombre de persona real
     que entró en los commits `a26d676` y `dc94dc4` (rebase interactivo con `edit` o squash-merge al publicar); los hashes pueden cambiar.
  2. **H3, tests de la migración** (`MigracionHepatologiaTest`): casos que matan las cuatro mutaciones que sobrevivían: (a) sin `c.categoria`
     en el primer `UPDATE` de antecedentes, (b) sin `h.categoria` en el segundo, (c) sin `c.paciente_id` en el primero, (d) sin
     `h.especialidad = 'hepatologia'` en el segundo (una fila de pediatría no desactiva una de `clinica`). Datos donde la fila de `clinica`
     no pierde por otra razón; cada test visto fallar mutando el `.sqm` (y restaurado). Más el test genérico: toda tabla que la migración no
     mueve queda idéntica (con un `medico` con `hepatologia` en `especialidades_hc`, una `especialidad` con `codigo_hc`, una fila de
     `hc_enfermedades` y una de `sesion_local`: la migración no los normaliza, se normalizan al leer).
  3. **Bug de `codigoHc`** (`AgendaTurnosOnline.sincronizarAdministracion`): `EspecialidadTurnos(id, nombre, it.color)` pasa el color en la
     posición de `codigoHc`. Se corrige con parámetros nombrados, sin pisar nunca un `codigo_hc` guardado; el armado del modelo sale a una
     función `internal` pura y se prueba. Si el DTO no trae el dato y la solución exigiera tocar la API Laravel: parar y preguntar.
  4. **H4, H5, H6, documentación** (cada afirmación verificada contra el código): (H4) `SyncEngine` no está "sin uso": arranca tras cada
     login y apunta a `.../api/salud360/sync/push`, que no existe en turnos (404 que se ignora); si alguien crea esa ruta saldrían las filas
     `dirty` (`AGENTS.md` y el riesgo 7 de la tanda D). (H5) la web descarta las tablas si la versión guardada es más nueva o si la
     migración falla; si es más vieja, migra (`AGENTS.md`). (H6) `README.md`: la HC general con perfiles (hepatología es un perfil).
     `specs/README.md`: la fila de la 001.
  5. **H2 (solo anotar):** `MEMORY.md` y `specs/002-hc-clinica/tasks.md`: al registrar el backend de `clinica` en `HcApiSync` hay que filtrar los
     antecedentes `activo = 0`, y las consultas migradas de hepatología traen `dirty` de antes (riesgo de envío en bloque). Pendiente de la 002.
  - Hecho cuando: los cinco puntos hechos; `:core:data:jvmTest --rerun`, `:especialidades:clinica:jvmTest --rerun`, `:core:ui:jvmTest` en
    verde; compilan `:features:admin:compileKotlinJvm` y `:composeApp:compileKotlinWasmJs -Pentorno=dev` (klib); `git diff` de `8.sqm`
    vacío; sin tokens, contraseñas ni nombres reales en el diff.
  - **Hecho (2026-10-27).** (1) `MEMORY.md` anota lo de la historia (no se reescribió nada). (2) `MigracionHepatologiaTest` pasa de 29 a 35 tests:
    cuatro pares de antecedentes que no chocan (`sembrarParecidos`, pacientes `p-prueba-5/6`), un test con las ocho filas juntas y
    `toda_tabla_que_la_migracion_no_mueve_queda_identica` (volcado de todas las tablas fuera de las seis que mueve, con `medico`,
    `especialidad`, `hc_enfermedades` y `sesion_local` sembradas). Los tests de "no cambia" pasan de entrada con el `.sqm` real, así que se
    comprobaron mutando `8.sqm` a propósito (ocho corridas, `8.sqm` restaurado después de cada una y al final, `git diff` vacío): sin
    `c.categoria` falla el par (a); sin `h.categoria`, el (b); sin `c.paciente_id`, el (c); sin `h.especialidad = 'hepatologia'`, el (d)
    (más la idempotencia); y un `UPDATE` extra sobre `medico`, `especialidad`, `hc_enfermedades` o `sesion_local` hace fallar el test genérico.
    (3) El DTO de turnos (`TobbEspecialidad`) trae solo `id`, `nombre` y `color`, y `CatalogoController` de turnos tampoco manda `codigo_hc`:
    no hizo falta tocar la API. `toEspecialidadTurnos(previa)` (función `internal` pura en `AgendaTurnosOnline.kt`, porque
    `sincronizarAdministracion` necesita red) conserva el `codigo_hc` ya guardado (normalizado) y nunca lo pisa; el color es el de turnos y, si
    no manda, el guardado. `EspecialidadDeTurnosTest` (6 tests) visto fallar con el bug (5 por aserción) y en verde. Queda sin cubrir por test la
    lectura de `previa` (`especialidadPorId`, igual que `TurnosImportador`). Una especialidad nueva, sin `previa`, queda con `codigo_hc` vacío hasta
    el próximo ingreso (ahí `TurnosImportador` lo deduce por el nombre) o hasta que el administrador la asocie. (4) `AGENTS.md`, riesgo 7
    de esta tanda, `README.md` y `specs/README.md` corregidos tras verificar contra el código (`App.kt`, `SyncEngine`, `DataModule`,
    `DriverFactory.wasmJs.kt`; en `turnosonlinebb/routes` no hay ninguna ruta `sync`). (5) Anotado en `MEMORY.md` y `specs/002-hc-clinica/tasks.md`.
    Verde: `:core:data:jvmTest --rerun` (629 tests, 0 fallas), `:especialidades:clinica:jvmTest --rerun` (25), `:core:ui:jvmTest`
    (`UP-TO-DATE`, el módulo no cambió), `:features:admin:compileKotlinJvm` y `:composeApp:compileKotlinWasmJs -Pentorno=dev` (solo el klib).

Fuera de esta tanda: `HcPerfilesE2ETest` y los criterios de finalización "contra la copia local" del plan original (pregunta D7); la edición de la
web de turnos y de su `HistoriaClinicaService` (pregunta D1, opciones B y C); endurecer el driver web (pregunta D5, opción B).

Al terminar la tanda: validación del reviewer y prueba manual del usuario.
