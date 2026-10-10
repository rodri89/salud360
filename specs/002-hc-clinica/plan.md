# Plan 002 — HC de clínica y hepatología contra su propia base

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
