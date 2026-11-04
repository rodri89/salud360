---
description: Planifica un cambio chico antes de tocar código (sin spec; para lo grande usar /sdd-spec)
agent: plan
---
Quiero hacer este cambio: $ARGUMENTS

Antes de escribir código, leé AGENTS.md, MEMORY.md y docs/constitution.md, y prepará un plan con:
1. Cómo lo vas a implementar, respetando AGENTS.md y la constitución.
2. Qué módulos y archivos vas a modificar y qué cambia en cada uno.
3. Los casos límite y las dudas que tengo que decidir yo antes de empezar.
4. Cómo se verifica (qué test o qué paso manual).
5. Qué actualizarías en AGENTS.md y en MEMORY.md.

Si el cambio es grande (varias pantallas, esquema nuevo o cambios en las APIs de turnos/HC), decímelo y
proponé seguir el flujo SDD con /sdd-spec.

No modifiques ningún archivo hasta que apruebe el plan.
