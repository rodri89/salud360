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

- [ ] **T16. Duplicar, borrar y catálogo del administrador.** RF-1, RF-11, RF-12, RF-16
  - `hcgeneral/PerfilCopia.kt`: `nombreCopia(nombre, existentes)` ("Copia de X", "Copia de X (2)", …, comparando
    como `validarPerfil`; recorta X para no pasar 60 con el sufijo) y `duplicarPerfil(perfil, existentes)` (mismas
    pestañas, secciones, nombres y tipos; `id` null). `puedeBorrar(perfil, asignaciones)` = ningún médico asignado.
  - `hcgeneral/CatalogoAdmin.kt`: `catalogoParaAdmin(remoto, catalogoApp)` en el orden del remoto, con nombre y
    descripción, marcando `soportada` las que la app sabe mostrar; las no soportadas no se ofrecen para agregar.
  - `PerfilCopiaTest` (copia simple, con "(2)" y "(3)" ocupados, nombre de 60 recortado, la copia pasa
    `validarPerfil`, borrar con y sin médicos) y `CatalogoAdminTest` (sección del remoto que la app no tiene →
    no soportada; orden del remoto).
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*PerfilCopiaTest*' --tests '*CatalogoAdminTest*'` en verde.

- [ ] **T17. Definición de la HC general, visibles y estado de la configuración.** RF-7, RF-19, RF-20, RF-21, RF-23,
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

- [ ] **T18. Secciones con datos fuera del perfil, solo para leer.** RF-22, RF-28, RF-30
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

- [ ] **T19. Catálogo de la HC general en la app.** RF-31, RF-33
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

- [ ] **T20. Caché de la configuración del médico.** RF-23, RF-25, RF-26
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

- [ ] **T21. DTOs, repositorio del administrador, Koin y URL de producción.** RF-1 a RF-17 (datos), RF-13, RF-23, RF-25
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
