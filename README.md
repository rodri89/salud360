# Salud 360

Aplicación Kotlin Multiplatform (Android, iOS y Web) que unifica en un solo código las historias clínicas
de **hclinica**, **hc_pediatria**, **hc_gineco**, **hc_cardiologia**, **hc_endocrinologia**, **hc_hematologia**
y **desarrollo_infantil**, junto con la agenda de **turnosonlinebb.com**. Está pensada para médicos,
secretarias y un administrador (no para pacientes) y funciona sin conexión.

## Qué incluye

- **Login por mail**: el médico no elige especialidad; la app resuelve por su cuenta qué historias
  clínicas tiene habilitadas (puede tener más de una) y si tiene agenda de turnos.
- **Pacientes** unificados por DNI, compartidos entre todas las especialidades.
- **Historia clínica** por especialidad definida de forma declarativa (`especialidades/*`), con
  secciones genéricas reutilizadas (motivo, examen físico, laboratorios longitudinales, exámenes
  complementarios, interconsultas, antecedentes, adjuntos, audio, pendientes) y secciones a medida
  (calendario de vacunas, desarrollo madurativo, dibujo sobre esquema PAP / silueta, Child-Pugh).
- **Turnos** para médicos y secretarias: agenda del día, semana, asignar, sobreturnos, bloquear,
  horarios fijos y fechas especiales, configuración, módulos, obras sociales, recetas.
  Los médicos y secretarias de **turnosonlinebb** ingresan con sus credenciales de la web y la agenda
  se lee y escribe directamente en la base de turnos a través de su API `/api/salud360/...`
  (ver [docs/DESPLIEGUE.md](docs/DESPLIEGUE.md)); los pacientes siguen usando la web.
- **Administración**: usuarios, médicos (historias clínicas habilitadas, agenda), secretarias,
  consultorios, especialidades, feriados y licencias.
- **Offline-first**: base SQLite local (SQLDelight) en cada dispositivo y sincronización con el
  servidor (`server/`, Ktor) cuando hay conexión.

## Estructura

```
salud360/
├── build-logic/           plugins de convención de Gradle
├── core/
│   ├── model/             modelos de dominio y definición declarativa de especialidades
│   ├── database/          esquema SQLDelight + drivers por plataforma
│   ├── data/              repositorios, sincronización, cliente HTTP, archivos
│   └── ui/                sistema de diseño (paleta teal original, componentes)
├── features/
│   ├── auth/              login
│   ├── pacientes/         listado, ficha, alta/edición
│   ├── hc/                motor de historia clínica (secciones genéricas, consulta)
│   ├── turnos/            agenda, horarios, configuración, obras sociales, recetas
│   └── admin/             panel del administrador
├── especialidades/        clinica (+ hepatología), pediatria, gineco, cardiologia,
│                          endocrinologia, hematologia, desarrollo-infantil
├── composeApp/            módulo compartido de la app (navegación, DI, entradas iOS y Web/Wasm)
├── androidApp/            aplicación Android (Activity, manifiesto, íconos)
├── iosApp/                proyecto Xcode (XcodeGen)
├── server/                servidor Ktor: auth JWT, sync, adjuntos
└── docs/                  arquitectura, migración de datos, despliegue
```

## Requisitos

- JDK 17 (Gradle lo descarga solo gracias a `gradle/gradle-daemon-jvm.properties`)
- Gradle 9.7 (wrapper incluido), Kotlin 2.4, Compose Multiplatform 1.12, Android Gradle Plugin 9.4
- Android Studio reciente (Otter o posterior, con AGP 9) con el plugin Kotlin Multiplatform
- Para iOS: macOS con Xcode 16 y, opcionalmente, XcodeGen
- Para Web: Node se descarga automáticamente por Gradle

## Compilar y ejecutar

```bash
# Android
./gradlew :androidApp:assembleDebug

# Web (abre http://localhost:8080)
./gradlew :composeApp:wasmJsBrowserDevelopmentRun

# Servidor (http://localhost:8080; primer admin: admin@salud360.local / admin123)
./gradlew :server:run

# Servidor conectado a turnosonlinebb (login y agenda con la base de la web de turnos)
TURNOS_API_URL=https://turnosonlinebb.com ./gradlew :server:run

# iOS (en macOS)
cd iosApp && xcodegen && open iosApp.xcodeproj
```

La URL del servidor se configura en `composeApp/src/commonMain/kotlin/com/salud360/app/AppDi.kt`
(`API_BASE_URL_DEFAULT`).

## Documentación

- [docs/ARQUITECTURA.md](docs/ARQUITECTURA.md): decisiones de diseño y cómo agregar una especialidad.
- [docs/MIGRACION.md](docs/MIGRACION.md): correspondencia entre las tablas de los proyectos Laravel y el esquema unificado.
- [docs/DESPLIEGUE.md](docs/DESPLIEGUE.md): servidor, variables de entorno y publicación de la web.
