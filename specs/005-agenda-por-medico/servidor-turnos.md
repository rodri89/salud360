# Spec 005 — Lo que hace falta en turnosonlinebb (servidor)

Brief para quien trabaje el repo Laravel de turnosonlinebb (otra PC). Es la dependencia de la spec 005 (`spec.md`, esta misma carpeta): la app de Salud 360 no implementa nada de esto, solo lo consume. Se puede pegar tal cual a una IA en esa PC (hay un prompt al final).

Todo en español: código, mensajes, docs y commits. Este archivo es público: no escribir tokens, contraseñas ni datos reales en ningún lado.

## Qué se busca
Hoy el panel de administración de la app tiene dos interruptores por médico, «Tiene agenda de turnos» y «Visible para pacientes en la web», que solo cambian la copia local del dispositivo. Para que sean reales, turnos tiene que:

1. Guardar **«tiene agenda»** en una columna propia (decisión del usuausa erio: no se reutiliza la que decide la visibilidad).
2. Seguir usando `medicos.castigo_automatico` ("Mostrar Medico", `1` = se muestra) como **«visible para pacientes»**. Esa columna ya decide qué médicos ve el paciente en la web; no se cambia su significado ni la web antigua.
3. Dejar que el **administrador** cambie los dos desde la API de Salud 360.
4. **Informar** los dos en `catalogos` y en `auth/perfil`.

Combinaciones válidas: sin agenda; con agenda interna (no visible); con agenda pública (visible). **«Sin agenda y visible» no es válida** para lo que se escribe desde la API.

## Antes de tocar nada (descubrimiento, solo lectura)
1. **Partí de la rama correcta.** La app usa hoy `POST usuarios` (alta de usuarios), `PUT medicos/{id}/historias-clinicas` y `tiene_horarios` en `catalogos`. (Corrección 2026-11-05: la copia de MAMP de la Mac hoy es un repo git sincronizado con producción y sí tiene `POST usuarios` y `tiene_horarios`; `historias-clinicas` sigue sin existir ahí.) Partí de la rama que los tenga (la desplegada o la que se va a desplegar) y anotá cuál es y su hash para el informe del final.
2. **Mirá cómo se calcula hoy `tiene_horarios`** en `catalogos` (qué tabla de horarios y qué condiciones). La columna nueva se rellena con exactamente ese mismo criterio.
3. **Copiá el patrón de `PUT medicos/{id}/historias-clinicas`**: misma autorización de solo administrador, mismo formato de errores (`codigo` y mensaje), mismo estilo de controlador/servicio. No inventes un formato nuevo.
4. **Diagnóstico de datos (solo `SELECT`, sobre la copia local, nunca producción).** Contá y anotá solo los números, sin nombres ni datos de personas:
   - médicos activos con horarios cargados y `castigo_automatico = 0` (agenda interna);
   - médicos activos con `castigo_automatico = 1` y **sin** horarios (visibles sin agenda: caso anómalo);
   - médicos activos con horarios y `castigo_automatico = 1` (agenda pública);
   - médicos activos sin horarios y `castigo_automatico = 0` (solo historia clínica).
   No corrijas nada de lo que aparezca: se informa.
5. **Mirá dónde se arman los médicos del perfil.** `auth/perfil` devuelve un médico en `medico` (rol médico) y una lista en `secretaria.medicos[]` (rol secretaria), y `catalogos` devuelve otra lista en `medicos`. Fijate si las tres salen del mismo
   serializador/función o si la secretaria tiene **otra ruta de código** que arma sus médicos (y si ahí ya sale `tiene_horarios`). Los campos nuevos tienen que llegar a los tres lugares; anotá cuáles son y si tuviste que tocar más de uno.

## Cambios a hacer

### 1. Columna nueva `medicos.tiene_agenda`
- Migración nueva (tinyint(1), no nulo). `DEFAULT 1`: un médico creado por la web antigua se da por médico con agenda.
- **Relleno inicial, en la misma migración:** `tiene_agenda = 1` si el médico tiene horarios cargados (el criterio del punto 2 del descubrimiento), `0` si no. Es lo que hace que **ningún médico cambie de estado** al activar la función (RF-17). `castigo_automatico` no se toca.
- `down()` borra la columna. Agregarla a `$fillable` de `App\Medico` solo si el código la asigna en masa.
- La migración **no se corre en producción desde acá**: se prueba en la copia local y se entrega junto con el SQL equivalente para correrlo a mano en producción con respaldo previo.

### 2. `PUT /api/salud360/medicos/{id}/agenda` (solo administrador)
Dentro del grupo con el middleware `salud360`, junto a `medicos/{id}/historias-clinicas`. `{id}` es el número de médico de turnos (el mismo que usa `historias-clinicas`).

Cuerpo (al menos uno de los dos campos):
```json
{ "tiene_agenda": true, "visible_en_turnos": false }
```
Reglas, en este orden:
1. No es administrador → `403` con el código de error que ya usa `historias-clinicas` para eso.
2. Médico inexistente → `404` con el mismo código que usa `historias-clinicas`.
3. Ningún campo, o valores que no son booleanos → `422` con código `datos_invalidos`.
4. **Si `tiene_agenda = false` → el médico queda también con `castigo_automatico = 0`**, venga o no `visible_en_turnos` (RF-5). Si además viene `visible_en_turnos = true` en el mismo pedido, `422` `agenda_requerida`.
5. **Si `visible_en_turnos = true` y el médico no tiene agenda** (ni la tiene ya, ni la trae este pedido) → `422` con código `agenda_requerida` (RF-6). No se guarda nada.
6. Si solo viene `tiene_agenda` y es `true`, `castigo_automatico` no cambia.
7. Todo se guarda en una sola transacción. Si falla, no queda a medias.

Respuesta `200`, siempre con el estado final de los dos y el aviso de turnos futuros (RF-18):
```json
{
  "medico_id": 12,
  "tiene_agenda": false,
  "visible_en_turnos": false,
  "turnos_futuros": 3
}
```
- `turnos_futuros`: cantidad de turnos **vigentes** del médico con fecha de hoy en adelante (con el mismo criterio de «vigente» que usa la web: no cancelados). Se informa **siempre**, pero solo importa cuando se apagó la agenda. **No se cancela ni se modifica ningún turno.**
- Idempotente: repetir el mismo pedido da el mismo resultado.
- No toca historias clínicas (`salud360_medico_hc`) ni licencias ni horarios (RF-16): apagar la agenda **no borra** los horarios cargados.

### 3. `GET catalogos`: dos campos nuevos por médico
En cada elemento de `medicos`, **sin sacar ni cambiar** ninguno de los actuales (la app publicada sigue leyendo `tiene_horarios`):
```json
{ "tiene_horarios": 1, "tiene_agenda": 1, "visible_en_turnos": 0 }
```
- `tiene_agenda`: `0` o `1`, de la columna nueva.
- `visible_en_turnos`: `0` o `1`, igual a `castigo_automatico == 1`.
- No «corregir» combinaciones raras que ya existan (por ejemplo visible sin agenda): se informan tal cual están.

### 4. `GET auth/perfil`: los mismos dos campos
En el objeto `medico` del perfil, `tiene_agenda` y `visible_en_turnos` con el mismo formato. Los demás campos quedan como están.

**También para la secretaria:** cuando el rol es secretaria, **cada médico de `secretaria.medicos[]`** lleva los mismos dos campos (`tiene_agenda` y `visible_en_turnos`, enteros `0`/`1`, con la misma regla de `catalogos`), sin sacar ni cambiar ninguno de los
campos que ya trae. La app importa a esos médicos con el mismo código que al médico que entra (decisión del usuario, 2026-11-04): sin estos campos la secretaria quedaría con el criterio viejo de `tiene_horarios` y no vería bien quién tiene agenda.
Si esa lista se arma en otro lugar que `medico` y `catalogos` (ver el punto 5 del descubrimiento), hay que tocarlo también.

### 5. `POST usuarios` (alta de usuarios): `tiene_agenda`
Hoy recibe `mostrar_en_turnos` (booleano, por omisión `false`). Agregar `tiene_agenda` (booleano, opcional):
- Si **viene**, se guarda tal cual. Si además `mostrar_en_turnos = true` y `tiene_agenda = false` → `422` `agenda_requerida`.
- Si **no viene** (la app vieja no lo manda), `tiene_agenda` = el valor de `mostrar_en_turnos`. Así un alta con `mostrar_en_turnos = true` de la app publicada sigue funcionando y deja al médico con agenda.
- Por omisión, un médico creado así queda **sin agenda y no visible** (RF-14).

### Lo que NO hay que cambiar
- Las listas de la web para el paciente (`seleccionar_medico`, `mostrar_medicos_index`, etc.) y el significado de «Mostrar Medico».
- Los endpoints `agenda/*`, `turnos/*`, `horarios/*`, `recetas/*`: la app solo oculta las pantallas; el servidor no bloquea por «tiene agenda». Si querés bloquearlos también, es otra decisión para otra spec.
- `tiene_horarios`: sigue igual, es el respaldo cuando el servidor todavía no informa `tiene_agenda`.
- Nada de `salud360_medico_hc`, licencias ni los repos de las historias clínicas.

## Cómo probarlo (copia local, nunca producción)
Con `curl` y un token de **administrador** y otro de **médico** de la copia local (no escribirlos en ningún archivo del repo):

| Caso | Esperado |
|---|---|
| `PUT …/agenda` sin token o con token de médico | `401`/`403` según el patrón existente |
| Médico inexistente | `404` |
| Cuerpo vacío / valor no booleano | `422` `datos_invalidos` |
| `{tiene_agenda:false}` en un médico visible | `200`, `tiene_agenda:false`, `visible_en_turnos:false`; en la base `castigo_automatico = 0` |
| `{tiene_agenda:false, visible_en_turnos:true}` | `422` `agenda_requerida`, sin cambios en la base |
| `{visible_en_turnos:true}` en un médico sin agenda | `422` `agenda_requerida` |
| `{tiene_agenda:true, visible_en_turnos:true}` en un médico sin agenda | `200`, los dos en `true` |
| `{tiene_agenda:true}` en un médico visible | `200`, `castigo_automatico` no cambia |
| Apagar la agenda de un médico con turnos futuros | `200` con `turnos_futuros` correcto; los turnos siguen igual |
| Repetir el mismo `PUT` | mismo resultado |
| `GET catalogos` | los tres campos (`tiene_horarios`, `tiene_agenda`, `visible_en_turnos`) en cada médico |
| `GET auth/perfil` de un médico | `tiene_agenda` y `visible_en_turnos` en `medico` |
| `GET auth/perfil` de una secretaria | cada elemento de `secretaria.medicos[]` trae `tiene_agenda` y `visible_en_turnos` (además de lo que ya traía); coinciden con los de `GET catalogos` para ese médico |
| `POST usuarios` sin `tiene_agenda` y con `mostrar_en_turnos:true` | médico con agenda y visible |
| `POST usuarios` sin ninguno | médico sin agenda y no visible |
| `POST usuarios` con `mostrar_en_turnos:true` y `tiene_agenda:false` | `422` `agenda_requerida` |
| Después de la migración, contar médicos con `tiene_agenda = 1` | igual a los que antes tenían horarios (RF-17) |
| La web del paciente (`seleccionar_medico`) | lista a los mismos médicos que antes |

Si el proyecto tiene `phpunit`, agregar tests para las reglas de `PUT agenda` y del alta. Si no, dejar los `curl` documentados.

Antes y después de probar, **comparar conteos** de `medicos` (total, por `tiene_agenda`, por `castigo_automatico`) y de `turnos`, para demostrar que no cambió nada fuera de lo probado. Restaurar por `UPDATE` puntual lo que se haya tocado en las pruebas.

## Documentación
Actualizar `md/API_SALUD360.md`: la ruta nueva, los campos nuevos de `catalogos` y `auth/perfil`, `tiene_agenda` en `POST usuarios`, los códigos de error y los ejemplos de arriba.

## Orden de despliegue
1. Respaldar la base de producción.
2. Correr la migración (o su SQL) a mano y verificar el conteo de RF-17.
3. Subir los archivos PHP (el controlador, `routes/api.php`, el modelo si cambió) y `php artisan config:clear` / `route:clear` como se hace siempre.
4. Probar con `curl` contra producción **solo `GET`** (sin escribir).
5. Recién entonces se publica la versión de la app que usa los campos nuevos. Mientras el servidor no informe `tiene_agenda`, la app usa `tiene_horarios` y al intentar guardar avisa que turnos todavía no lo permite (RF-13).

Pushear el repo de turnos **antes** de subir por FTP. `APP_DEBUG=false` en producción. Commits como `rodri89`, sin push sin pedido del usuario.

## Qué devolver a la app (para cerrar el plan)
Pegá esto de vuelta en la sesión de la app (sin tokens ni datos reales):
1. Rama y hash del repo de turnos sobre el que trabajaste, y los archivos que cambiaron.
2. Los cuatro números del diagnóstico de datos.
3. Un ejemplo real (con datos inventados) de: `GET catalogos` (un médico), `GET auth/perfil` (médico), `GET auth/perfil` (secretaria, con al menos un médico en `secretaria.medicos[]`), respuesta `200` de `PUT agenda`, y cada error `422`/`403`/`404` tal como sale (para fijar los DTO y los tests de la app).
4. El formato exacto de error que usa la API (campo del código, del mensaje).
5. Si algo de este brief no se pudo cumplir tal cual, y por qué.
6. La URL local donde probarlo, para el E2E de la app.

## Prompt para la IA en la otra PC
```
Trabajás en el repo Laravel de turnosonlinebb (no en la app Kotlin). Tu tarea está descrita en
specs/005-agenda-por-medico/servidor-turnos.md (te lo paso entero). Seguilo punto por punto y empezá por
el "Antes de tocar nada" (solo lectura). Todo en español, sin tokens ni datos reales en archivos.
No corras migraciones ni escribas en producción. No hagas push ni toques ramas ajenas sin que lo pida.
Una cosa por vez: probá cada cambio contra la copia local con curl, comparando conteos antes y después.
Al terminar, devolveme el informe de la sección "Qué devolver a la app".
```

## Hecho en la copia local de MAMP (2026-11-05)
Implementado por una IA en `/Applications/MAMP/htdocs/TurnosOnlineBB/TurnosImage/` (la copia local de turnosonlinebb de esta Mac). Sin commit ni push; nada contra producción.
Actualización 2026-11-05 (más tarde): el usuario ya lo llevó al repo git real, lo pusheó y lo subió a **producción**.

**Punto de partida.** La carpeta ya es un repo git (rama `main`, árbol limpio al empezar, commit `6ee3b81` «sincronizar repo con el estado real de prod», 2026-11-05 10:14), así que el aviso de D3
(«no es git y está desactualizada») ya no vale. Esa sincronización trajo `POST usuarios` (`AltaUsuarioController`) y `tiene_horarios` en `catalogos`. **Sigue sin existir** la ruta `PUT medicos/{id}/historias-clinicas`
(solo el guardado de la web en `MedicoController`), así que los códigos de error se copiaron de los controladores que sí hay (`sin_permiso` 403, `no_encontrado` 404, `datos` 422, `{ok, mensaje, codigo}`).

**Qué se hizo**
- Migración `2026_11_05_000000_add_tiene_agenda_to_medicos_table`: `medicos.tiene_agenda` `tinyint(1) NOT NULL DEFAULT 1`, rellenada con el criterio de `tiene_horarios`
  (existe un `horario_medicos` con `activo = 1`; se rellenan también los médicos dados de baja); `down()` borra la columna. Aplicada **solo esa** con `php artisan migrate --path=…` en la base local
  (hay otras 17+ migraciones viejas sin registrar en `migrations`, por eso nunca un `migrate` a secas). RF-17: 40 médicos con `tiene_agenda = 1` = 40 con horarios activos, 0 discrepancias; `castigo_automatico` intacto.
- `PUT /api/salud360/medicos/{id}/agenda` (`MedicoAgendaController@guardar`, reglas puras en `AgendaMedicoReglas`): exactamente las reglas y el orden de este brief; 403 → 404 → 422 `datos_invalidos` → apagar agenda apaga visible →
  422 `agenda_requerida`; transacción con la fila bloqueada; solo escribe lo que cambia; `turnos_futuros` cuenta `turno_registrados` del médico con `fechaTurno >= hoy` y `activo` en 1 o 2 (no cancelados), sin tocar nada.
  Respuesta `200`: `{ok, medico_id, tiene_agenda, visible_en_turnos, turnos_futuros}` con booleanos JSON.
- `GET catalogos`, `GET auth/perfil` (médico) y cada médico de `secretaria.medicos[]` llevan `tiene_agenda` y `visible_en_turnos` (enteros `0`/`1`): los tres salen de `Salud360Controller::formatearMedico`, **un solo lugar**. Si la columna no existe
  todavía los campos no salen (la app usa `tiene_horarios`) y el `PUT` da `422 no_disponible`.
- Tests phpunit: `tests/Unit/Salud360AgendaMedicoReglasTest.php` (28 casos, sin base de datos; el `phpunit.xml` no aísla la base, por eso no hay tests de Feature). `tests/Unit` completo: 35 tests, 97 aserciones, en verde.
- `md/API_SALUD360.md` documentado (ruta, campos, errores, ejemplos con datos inventados).
- **`POST usuarios` con `tiene_agenda` (punto 5 del brief): hecho** (`AltaUsuarioController::crear` + `AgendaMedicoReglas::resolverAlta`, reglas puras). Rol médico: si `tiene_agenda` viene se guarda tal cual; si no viene vale `mostrar_en_turnos`
  (la app publicada sigue funcionando: con `mostrar_en_turnos = true` queda con agenda y visible); sin ninguno, sin agenda y no visible (RF-14). `mostrar_en_turnos = true` con `tiene_agenda = false` → `422 agenda_requerida` y no se crea nada;
  `tiene_agenda` no booleano (incluido `null`) → `422 datos_invalidos`. La columna se escribe siempre explícita (el `DEFAULT 1` no se usa) y `castigo_automatico` sale de la misma decisión. Secretaria y administrador: sin cambios.
  Si la columna no existe (falta la migración) el alta sigue como antes. Respuesta `201` y `403` sin cambios. Tests: 62 tests, 170 aserciones en `tests/Unit`; 18 casos de curl contra la base local, con altas de prueba borradas por id
  (conteos y checksums de `users`, `medicos`, `salud360_medico_hc`, `secretarias` y `salud360_tokens` idénticos antes y después). Patch regenerado (`git apply --check` sobre `6ee3b81` en una copia limpia).
- Prueba con curl contra la base local (22 casos; todos los de la tabla de arriba salvo los de `POST usuarios` (más abajo) y la web del paciente): todo igual a lo esperado; conteos de `medicos` y `turnos` antes/después idénticos; las filas tocadas se restauraron por `UPDATE` puntual (incluido `updated_at`).

**Qué quedó sin hacer**
- Verificación contra la web antigua de pacientes (`seleccionar_medico`): no se tocó ni el código ni `castigo_automatico` (33 médicos activos visibles, igual que antes); no se abrió la web.

**Decisiones que el brief no cubría**
- «Médico inexistente» se mira por `id` en `medicos`, esté activo o dado de baja (el panel solo lista activos).
- «Vigente» = `turno_registrados.activo IN (1, 2)` (`AgendaService::ACTIVOS_QUE_OCUPAN`); hoy nada escribe `activo = 2` en turnos, así que equivale a `activo = 1`. Las videollamadas (`turno_registrado_videollamadas`) no cuentan.
- Los tokens de las pruebas se emitieron con el `TokenService` de la app para usuarios que ya existían en la base local (no hay usuarios de prueba propios), con nombre `prueba-spec005-borrar`, y se borraron al terminar (`salud360_tokens` volvió a 30 filas). No se usaron ni guardaron contraseñas.
- Diagnóstico de datos de la base local (activos): agenda interna 5, visibles sin agenda 0, agenda pública 33, solo historia clínica 1.

**Dónde está todo** (fuera de este repo): `/Users/rodrigobanegas/salud360-turnos-backup-2026-11-05/`
- `agenda-por-medico.patch` — `git apply -p1` sobre `6ee3b81` (comprobado con `git apply --check`); 7 archivos, sin `.env`.
- `2026_11_05_000000_add_tiene_agenda_to_medicos_table.php` y `produccion-tiene-agenda.sql` (SQL equivalente con conteos de control, verificación de RF-17 y marcha atrás; pide respaldo previo).
- `original/` (los 3 archivos tal como estaban) y `dump/tablas-antes-2026-11-05.sql` (`medicos`, `horario_medicos`, `migrations` antes de migrar; tiene datos reales, no subir a ningún repo).

**URL local para el E2E de la app:** la de `salud360.entorno.dev.turnos` de `gradle.properties`, `http://<host>:8888/TurnosOnlineBB/TurnosImage/public/index.php` (Apache de MAMP encendido). Esa misma carpeta, con la migración ya aplicada en la base local.
