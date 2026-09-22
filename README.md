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
  (tabla de desarrollo madurativo, curvas de crecimiento OMS, dibujo sobre esquema PAP / silueta, Child-Pugh).
  Cada médico elige en Configuración qué secciones ve en cada historia clínica.
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

La app se compila contra un **entorno**: `dev` (turnosonlinebb y las historias clínicas del MAMP local) o
`release` (producción). Ver [docs/DESPLIEGUE.md](docs/DESPLIEGUE.md#entornos-dev-mamp-local-y-release-producción).

```bash
# Web (abre http://localhost:8080)
./gradlew devWeb          # contra el MAMP local
./gradlew releaseWeb      # build de producción

# Android
./gradlew devAndroid      # instala el debug contra el MAMP local (emulador; teléfono: -PdevHost=<ip de la Mac>)
./gradlew releaseAndroid  # APK release contra producción

# iOS (en macOS)
cd iosApp && xcodegen && open iosApp.xcodeproj

# Servidor Ktor (opcional, hoy la app no lo usa)
./gradlew :server:run
```

Las URLs de cada entorno están en `gradle.properties` (`salud360.entorno.dev.*` / `salud360.entorno.release.*`);
`-Pentorno=dev|release` fuerza uno.

## Documentación

- [docs/ARQUITECTURA.md](docs/ARQUITECTURA.md): decisiones de diseño y cómo agregar una especialidad.
- [docs/MIGRACION.md](docs/MIGRACION.md): correspondencia entre las tablas de los proyectos Laravel y el esquema unificado.
- [docs/DESPLIEGUE.md](docs/DESPLIEGUE.md): servidor, variables de entorno y publicación de la web.
