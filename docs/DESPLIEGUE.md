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
`GET /sync/pull?tabla=&desde=`, `POST|GET /archivos/{id}`, `GET /health`.

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
