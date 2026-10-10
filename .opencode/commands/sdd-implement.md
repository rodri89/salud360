---
description: SDD · Implementa UNA tarea, tests primero (uso - /sdd-implement 002-nombre T3)
agent: build
---
Implementá SOLO la tarea $2 de specs/$1/tasks.md, siguiendo specs/$1/plan.md, docs/constitution.md, AGENTS.md
y la skill sdd.

1. Escribí primero los tests y comprobá que fallan.
2. Escribí el código hasta que pasen.
3. Corré `.\gradlew :<módulo>:jvmTest` de los módulos tocados y mostrame el resultado.
4. Marcá $2 como hecha en tasks.md e indicá qué RF cubre.

Si la tarea es solo de interfaz y no tiene test, compilá (`compileKotlinJvm` y, si toca web,
`:composeApp:compileKotlinWasmJs`) y dejame los pasos para probarla a mano.

Después PARÁ. No empieces la siguiente tarea. No hagas commit ni push.
