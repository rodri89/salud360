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
