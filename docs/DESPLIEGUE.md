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

Prueba local del servidor Ktor (opcional, hoy la app no lo usa):

```bash
PORT=8765 DB_PATH=test.db TURNOS_API_URL=https://turnosonlinebb.com ./gradlew :server:run
```

## Entornos: dev (MAMP local) y release (producción)

La app se compila contra un entorno fijo. Las URLs están en `gradle.properties`
(`salud360.entorno.dev.*` y `salud360.entorno.release.*`) y Gradle genera `Entornos.kt` en `composeApp` con ellas:

| Entorno | turnosonlinebb | HC pediatría |
|---|---|---|
| `dev` | `http://{host}:8888/TurnosOnlineBB/TurnosImage/public/index.php` (MAMP) | `http://{host}:8888/HCPediatria/public_html/pediatria/HCDPediatria/public/index.php` |
| `release` | `https://turnosonlinebb.com` | `https://hcpediatrica.com` |

`{host}` se reemplaza en cada plataforma: `localhost` en web y simulador iOS, `10.0.2.2` en el emulador Android
(así ve a la Mac), o lo que se pase con `-PdevHost=192.168.x.x` para probar en un teléfono físico contra la Mac
(misma red Wi-Fi). El login muestra el entorno activo abajo del formulario.

Comandos:

```bash
./gradlew devWeb            # web en http://localhost:8080 contra el MAMP local
./gradlew releaseWeb        # build web de producción (composeApp/build/dist/wasmJs/productionExecutable)
./gradlew devAndroid        # instala el APK debug en el emulador/teléfono, contra el MAMP local
./gradlew releaseAndroid    # APK release contra producción
./gradlew devAndroid -PdevHost=192.168.0.10   # teléfono físico: la Mac por su IP de la red
```

Sin decir nada, Gradle elige `dev` para las tareas de desarrollo (`*DevelopmentRun`, `*Debug`, `dev*`) y
`release` para el resto. Para forzarlo: `-Pentorno=dev` o `-Pentorno=release` (por ejemplo,
`./gradlew :composeApp:wasmJsBrowserDevelopmentRun -Pentorno=release` prueba la web local contra producción).
El deploy por GitHub Actions corre `wasmJsBrowserDistribution`, es decir `release`.

Requisitos del MAMP para `dev`: Apache en 8888 y MySQL en 8889 levantados, y la API de turnos respondiendo en
`http://localhost:8888/TurnosOnlineBB/TurnosImage/public/index.php/api/salud360/auth/login` (el `index.php` va en
la URL porque el `public/` local no tiene `.htaccess`). Como la sesión se guarda en el dispositivo, al cambiar de
entorno en un mismo dispositivo conviene cerrar sesión y volver a entrar.

## Web (Compose para Web / Wasm)

```bash
./gradlew :composeApp:wasmJsBrowserDistribution
# resultado en composeApp/build/dist/wasmJs/productionExecutable

### Publicar en el hosting (subdominio, Apache/LiteSpeed)

Es un sitio estático: no necesita PHP, Node ni base de datos. Alcanza con subir a la carpeta raíz del
subdominio (`salud360.turnosonlinebb.com` → `domains/turnosonlinebb.com/public_html/salud360`)
todo el contenido de `productionExecutable` **menos** los `*.map`:

- `index.html`, `salud360.js`, `salud360.js.LICENSE.txt`
- todos los `*.wasm` (la app y `sql-wasm.wasm` de la base local), más `sql-wasm.js` y `sqljs-persistente.worker.js`
  (motor y worker de la base local, con copia persistente en IndexedDB)
- los chunks `*.js` numerados (el worker de la base)
- la carpeta `composeResources`
- `.htaccess` (tipo MIME de `.wasm`, compresión y caché; viene de `composeApp/src/wasmJsMain/resources`)

El subdominio tiene que servirse por HTTPS (la API de turnosonlinebb es HTTPS y el navegador bloquea
contenido mixto). El build de producción se compila con el entorno `release` (turnosonlinebb.com), así que no
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
| `HOSTINGER_WEB_DIR` | carpeta del subdominio relativa al home: `domains/turnosonlinebb.com/public_html/salud360` (el subdominio `salud360.turnosonlinebb.com` apunta ahí) |

Después, push a `main` y seguir el progreso en la pestaña Actions. `rsync --delete` sube solo lo que cambió y
borra en el servidor los `.wasm` viejos (cambian de nombre en cada build). Los `.map` no se suben.
```

Servir esa carpeta como sitio estático (por ejemplo `app.turnosonlinebb.com`). Requiere navegadores
con soporte de WasmGC (Chrome/Edge 119+, Firefox 120+, Safari 18.2+). Compilar con `./gradlew releaseWeb`
(entorno `release`; las URLs salen de `gradle.properties`).

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
