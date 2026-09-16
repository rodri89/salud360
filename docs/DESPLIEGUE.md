# Despliegue

## Servidor

```bash
./gradlew :server:installDist
PORT=8080 DB_PATH=/var/salud360/salud360.db ADJUNTOS_DIR=/var/salud360/adjuntos \
JWT_SECRET='un-secreto-largo' ADMIN_EMAIL=admin@tudominio.com ADMIN_PASSWORD='cambiar' \
server/build/install/server/bin/server
```

- Base SQLite en `DB_PATH` (con SQLDelight es sencillo pasar a PostgreSQL más adelante).
- Adjuntos en `ADJUNTOS_DIR`.
- Poner un proxy HTTPS delante (nginx / Caddy) y restringir CORS en `Application.kt` al dominio de la web.
- El primer arranque crea el administrador (`ADMIN_EMAIL` / `ADMIN_PASSWORD`) y el catálogo de especialidades.

Endpoints: `POST /auth/login`, `POST /auth/password`, `POST /admin/usuarios`, `POST /sync/push`,
`GET /sync/pull?tabla=&desde=`, `POST|GET /archivos/{id}`, `GET /health`, `ANY /tobb/{ruta}`.

### Integración con turnosonlinebb

Con `TURNOS_API_URL=https://turnosonlinebb.com` el servidor:

1. **Login**: si el mail no valida localmente consulta `POST /api/salud360/auth/login` de la web de turnos,
   importa el perfil (usuario, médico o secretaria, consultorios, especialidad, módulos y configuración de
   agenda) con ids `tobb-…` y guarda el token de Laravel del usuario (tabla interna `tobb_sesion`).
2. **Agenda**: todo lo que la app pide a `/tobb/<ruta>` se reenvía a `/api/salud360/<ruta>` con ese token y
   se devuelve tal cual (mismo estado HTTP y mismo JSON). Laravel sigue siendo el único dueño de la base
   MySQL de turnos; la app guarda las respuestas en su base local solo como caché. Si la web responde 401 el
   token se olvida y el usuario tiene que volver a iniciar sesión (`codigo: sesion_tobb_vencida`); si el usuario
   ingresó con una cuenta propia de Salud 360 (por ejemplo el administrador) la ruta responde 412
   (`sin_sesion_tobb`).

La API del lado de turnosonlinebb está documentada en `API_SALUD360.md` de ese repositorio
(`rodri89/turnosonlinebb`). Sin `TURNOS_API_URL` la app funciona con agenda local únicamente.

Prueba local:

```bash
PORT=8765 DB_PATH=test.db TURNOS_API_URL=https://turnosonlinebb.com ./gradlew :server:run
./gradlew :composeApp:wasmJsBrowserDevelopmentRun   # abre http://localhost:8080 (usa http://localhost:8765)
```

e ingresar con el mail y la contraseña de un médico o secretaria de turnosonlinebb.

## Web (Compose para Web / Wasm)

```bash
./gradlew :composeApp:wasmJsBrowserDistribution
# resultado en composeApp/build/dist/wasmJs/productionExecutable

### Publicar en el hosting (subdominio, Apache/LiteSpeed)

Es un sitio estático: no necesita PHP, Node ni base de datos. Alcanza con subir a la carpeta raíz del
subdominio (por ejemplo `salud360.turnosonlinebb.com` → `public_html/salud360` o la que asigne el panel)
todo el contenido de `productionExecutable` **menos** los `*.map`:

- `index.html`, `salud360.js`, `salud360.js.LICENSE.txt`
- todos los `*.wasm` (la app y `sql-wasm.wasm` de la base local)
- los chunks `*.js` numerados (el worker de la base)
- la carpeta `composeResources`
- `.htaccess` (tipo MIME de `.wasm`, compresión y caché; viene de `composeApp/src/wasmJsMain/resources`)

El subdominio tiene que servirse por HTTPS (la API de turnosonlinebb es HTTPS y el navegador bloquea
contenido mixto). Fuera de `localhost` la app usa `API_BASE_URL_DEFAULT` (turnosonlinebb.com), así que no
hay nada que configurar; las fotos y la API ya aceptan pedidos desde otro origen (middleware `Salud360Cors`).
Para actualizar, volver a generar el paquete y reemplazar los archivos: los `.wasm` cambian de nombre en cada
build (hash), así que conviene borrar los viejos.

### Publicación automática (GitHub Actions → rsync por SSH)

`.github/workflows/deploy-web.yml` compila la web y la sube por `rsync` sobre SSH en cada push a `main` (o a
mano desde la pestaña Actions con "Run workflow"). El hosting compartido no puede compilar Kotlin, por eso el
build se hace en GitHub y al servidor solo llegan los archivos estáticos; el "Git" de hPanel (que hace
`git pull`) no sirve para este caso.

Configuración, una sola vez, en GitHub → Settings → Secrets and variables → Actions (Repository secrets):

| Secret | Valor |
|---|---|
| `HOSTINGER_SSH_HOST` | IP/host SSH que muestra hPanel (Avanzado → Acceso SSH) |
| `HOSTINGER_SSH_PORT` | `65002` |
| `HOSTINGER_SSH_USER` | usuario del hosting (`u…`) |
| `HOSTINGER_SSH_PASSWORD` | contraseña SSH del hosting |
| `HOSTINGER_WEB_DIR` | carpeta del subdominio relativa al home, ej. `domains/salud360.turnosonlinebb.com/public_html` |

Después, push a `main` y seguir el progreso en la pestaña Actions. `rsync --delete` sube solo lo que cambió y
borra en el servidor los `.wasm` viejos (cambian de nombre en cada build). Los `.map` no se suben.
```

Servir esa carpeta como sitio estático (por ejemplo `app.turnosonlinebb.com`). Requiere navegadores
con soporte de WasmGC (Chrome/Edge 119+, Firefox 120+, Safari 18.2+). Cambiar `API_BASE_URL_DEFAULT`
en `AppDi.kt` antes de compilar.

## Android

```bash
./gradlew :androidApp:assembleRelease
```

Configurar firma en `androidApp/build.gradle.kts` (`signingConfigs`) antes de publicar.

## iOS

En macOS: `cd iosApp && xcodegen && open iosApp.xcodeproj`. El proyecto compila el framework
`ComposeApp` desde Gradle en cada build. El selector de archivos y la grabación de audio en iOS se
conectan desde Swift registrando implementaciones en `IosPlatformHooks`
(`features/hc/src/iosMain/.../Platform.ios.kt`).

## Transcripción de audio (opcional)

`hclinica` transcribía las grabaciones con whisper.cpp en el servidor. En Salud 360 los audios se
suben a `/archivos/{id}`; un proceso externo puede leerlos del directorio de adjuntos, transcribirlos
y escribir el texto en `seccion_valor` (`seccion = 'audio'`, `campo = 'transcripcion_<id>'`) registrando
el cambio en la tabla `cambios` para que llegue a los dispositivos.
