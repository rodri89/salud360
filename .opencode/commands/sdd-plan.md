---
description: SDD · Genera el plan técnico de una spec aprobada
agent: plan
---
Leé docs/constitution.md, AGENTS.md, specs/$1/spec.md y el código afectado. Usá la skill sdd.
NO escribas código.

Generá specs/$1/plan.md con lo que pide la skill sdd: módulos y archivos con su responsabilidad, funciones de
lógica (con "hoy" como parámetro), migraciones, cambios necesarios fuera de este repo (APIs de turnos o de la
HC), comportamiento sin conexión, interfaz por rol, decisiones con su alternativa descartada y estrategia de
tests con Gradle.

Todo debe respetar la constitución y cubrir todos los RF. Marcá qué RF cubre cada parte. Si la spec no está
aprobada o tiene dudas abiertas, pará y avisame.
