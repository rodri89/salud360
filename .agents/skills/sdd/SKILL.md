---
name: sdd
description: Úsala siempre que trabajes con Spec-Driven Development en Salud 360 (docs/constitution.md o cualquier archivo dentro de specs/) - redactar, revisar o cambiar specs, planes y tareas, o implementar y validar tareas de una spec.
---

# Spec-Driven Development (SDD) en Salud 360

## Flujo
Constitución → Spec → Clarificación → Plan → Tareas → Implementación → Validación → Cambio.

- Nunca pases a la siguiente fase sin la aprobación explícita del usuario.
- La spec manda: si algo no está en la spec, no se implementa. Si falta una decisión, pará y preguntá.
- Un cambio de requisitos va primero a la spec, después al plan y las tareas, y por último al código.
- Cada spec vive en `specs/NNN-nombre/` (`NNN` = siguiente número libre, nombre en kebab-case) con `spec.md`,
  `plan.md` y `tasks.md`. Actualizá la tabla de `specs/README.md` al crearla y al cambiar su estado.
- Al terminar cada fase, actualizá `MEMORY.md`.

## Plantilla de spec (spec.md)
```
# Spec NNN — <Nombre>

Estado: borrador | aprobada | implementada

## Contexto y objetivo
## Usuarios (médico, secretaria, administrador; con o sin agenda; con qué historias clínicas)
## Historias de usuario
- HU-1. Como <rol>, quiero <acción> para <beneficio>.
## Definiciones (solo si hay términos que puedan interpretarse de varias formas)
## Requisitos funcionales
## Requisitos no funcionales (sin conexión, plataformas web/Android/iOS, datos sensibles...)
## Casos límite
## Fuera de alcance
## Criterios de finalización
## Dudas abiertas
- [NECESITA ACLARACIÓN] <duda>
```
La spec describe el QUÉ y el POR QUÉ. Nada de stack, módulos, clases ni nombres de archivos.

Preguntas que casi siempre hacen falta en este proyecto: ¿quién es el dueño del dato (turnos o la HC de la
especialidad)? ¿qué pasa sin señal? ¿qué ve cada rol? ¿aplica a todas las especialidades o solo a una?

## Requisitos en EARS (en español)
- RF-x: CUANDO <evento>, EL SISTEMA <respuesta>.
- RF-x: SI <condición no deseada>, ENTONCES EL SISTEMA <respuesta>.
- RF-x: MIENTRAS <estado>, EL SISTEMA <respuesta>.
- RF-x: EL SISTEMA <comportamiento permanente>.

Cada RF debe ser verificable: nada de "rápido", "claro" o "cómodo" sin un criterio medible.

## Plan (plan.md)
- Módulos y archivos que se crean o modifican, con la responsabilidad de cada uno
  (`core/model`, `core/database`, `core/data`, `features/*`, `especialidades/*`, `composeApp`).
- Funciones de lógica sin Compose ni red, con "hoy"/reloj como parámetro, en `commonMain`.
- Cambios de esquema: migración `migrations/N.sqm` nueva, columnas siempre al final.
- Cambios que hacen falta **fuera de este repo** (APIs Laravel de turnos o de la especialidad): listarlos como
  dependencia, con el endpoint y el orden de despliegue. Este repo no los implementa.
- Comportamiento sin conexión y reconciliación.
- Interfaz: qué pantalla cambia y para qué rol.
- Decisiones justificadas con su alternativa descartada.
- Estrategia de tests: qué test (`commonTest`/`jvmTest`, módulo) cubre cada RF, y qué RF de interfaz se
  verifican a mano con `.\gradlew devWeb`.
- Indicá qué RF cubre cada parte.

## Tareas (tasks.md)
```
- [ ] **Tn. <Descripción>.** RF-x, RF-y
  - Hecho cuando: <comprobación verificable>.
```
Máximo 20-30 min por tarea, en orden de dependencia (modelo → base → datos → interfaz). Si salen más de 10,
proponé dividir la spec.

## Implementación
Una sola tarea cada vez: tests primero (en rojo), después el código, tests en verde, marcar la tarea y parar.

- Tests: `.\gradlew :<módulo>:jvmTest` (uno solo: `--tests '*NombreTest*'`).
- Compila sin tests: `.\gradlew :<módulo>:compileKotlinJvm`; si toca web, también `:composeApp:compileKotlinWasmJs`.
- Si Gradle no arranca por `JAVA_HOME`: `$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`.

## Validación
RF por RF: qué test lo cubre y el resultado. Los RF de interfaz se verifican con `.\gradlew devWeb`
(http://localhost:8080, necesita el MAMP/XAMPP local) o se dejan como lista de pasos para que los pruebe el usuario.
Nunca validar escribiendo en producción.
