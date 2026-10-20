# Specs

Una carpeta por funcionalidad: `specs/NNN-nombre/` con `spec.md`, `plan.md` y `tasks.md`
(flujo y plantillas en `.agents/skills/sdd/SKILL.md`). Se crean con `/sdd-spec NNN-nombre idea…`.

| Spec | Estado |
|---|---|
| [001-hc-configurable](001-hc-configurable/spec.md) — Perfiles configurables de la HC general (hclinica) desde el admin | spec y plan aprobados; tandas A1, A2 y B completas; tandas C (10/10), C2 (4/4) y C3 (3/3) y C4 (2/2) completas: compilan, falta validación del reviewer y prueba manual del usuario; tanda C5 (2/2) completa: compila, falta validación del reviewer y prueba manual del usuario; tanda C6 (2/2) completa: compila, falta validación del reviewer y prueba manual del usuario; tanda C7 (2/2) completa: compila, falta validación del reviewer y prueba manual del usuario; después tanda D |
| [002-hc-clinica](002-hc-clinica/spec.md) — La HC general lee y escribe la base de hclinica (depende de 001) | parte 1 (pacientes y cartera: RF-8 a RF-14, RF-35 a RF-37) y su plan (`plan-parte1.md`) aprobados el 2026-10-19 (PD1: también se corrige pediatría); `tasks.md` con la tanda E1 (servidor, 5/5, validada por el reviewer el 2026-10-20) y la tanda E2 (app, 8/8, validada por el reviewer el 2026-10-20): parte 1 completa y validada, falta la prueba manual del usuario (RF-10, RF-14 y la lista de E2.7); el resto de la 002 en borrador (plan previo); la parte 1 va antes porque la necesita la 003 |
| [003-hc-enfermedades](003-hc-enfermedades/spec.md) — Sección "Enfermedades" de la HC general contra la base de la web (depende de 001 y de la vinculación de pacientes y cartera de 002) | spec aprobada el 2026-10-19 (cambio del 2026-10-20: RF-10, RF-14, RF-30, RF-31, RF-57 y RF-59 nuevo); plan aprobado el 2026-10-20 (PD1-PD9 decididos); `tasks.md` con la tanda F1 (servidor, 0/7) |
