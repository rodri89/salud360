# Error 500 al ingresar con un médico (y no con el administrador)

Diagnóstico del fallo en producción: el administrador entra, cualquier médico recibe error 500.

## Qué está descartado

Lo verifiqué contra producción:

| Comprobación | Resultado |
|---|---|
| El sitio responde | 200 |
| Apunta al entorno correcto | `https://turnosonlinebb.com`, no al MAMP local |
| Permisos entre dominios | preflight 204 con los encabezados correctos |
| Credenciales inexistentes | 401 limpio, con su JSON |
| Ingreso sin datos | 422 con los mensajes de validación |
| Endpoint público de foto | 200, imagen |
| Endpoint protegido sin token | 401 `sin_token` |

El módulo está desplegado y sano. La contraseña tampoco es: la misma cuenta entra en turnosonlinebb.com, y el fallo ocurre **después** de validarla.

## La cadena exacta

`Api/Salud360/AuthController::login` hace, en orden: validar, `Auth::attempt`, chequear el tipo de usuario, emitir el token y armar el perfil. Como el administrador entra, los cuatro primeros pasos funcionan. Queda el quinto, y ahí administrador y médico se separan:

```
armarPerfil($user)
  └─ usuario_tipo == MEDICO ──> detalleMedico($m)          <-- el administrador NO pasa por acá
       ├─ DB::table('consultorios')
       ├─ formatearMedico($m)
       │    ├─ DB::table('especialidads')
       │    └─ HistoriaClinicaService::habilitadas($m->id)  <-- Salud360Controller.php:197
       │         └─ asegurarTabla()  ──> Schema::create('salud360_medico_hc')
       ├─ AgendaService::modulosActivos      (modulo_medicos)
       ├─ AgendaService::ventanaDias         (medico_configs)
       └─ AgendaService::cupoPrimerControl   (medico_primer_controls)
```

Las tres tablas de agenda las usa también la web normal (secretaría, médico, turnos), así que existen en producción y funcionan. El eslabón nuevo, y el único que no ejerce ningún flujo de la web, es `HistoriaClinicaService::habilitadas()`, agregado en el commit `ff70e4f`.

## Causa más probable

`habilitadas()` llama primero a `asegurarTabla()`, que ejecuta un `CREATE TABLE` cuando `salud360_medico_hc` no existe. En producción eso falla si el usuario de la base **no tiene permiso para crear tablas**, que es lo habitual en un hosting compartido con un usuario acotado. El resultado es una excepción no atrapada justo al armar el perfil del médico, es decir un 500, y solo para médicos.

Es una hipótesis, no una certeza: encaja con todo lo observado, pero lo confirma el log.

## Qué ejecutar en producción

Crear la tabla a mano, desde phpMyAdmin o el gestor de base del hPanel. Es idempotente y no toca datos:

```sql
CREATE TABLE IF NOT EXISTS `salud360_medico_hc` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `medico_id` bigint(20) unsigned NOT NULL,
  `hc_codigo` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `activo` tinyint(4) NOT NULL DEFAULT '1',
  `created_at` timestamp NULL DEFAULT NULL,
  `updated_at` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `salud360_medico_hc_medico_id_hc_codigo_unique` (`medico_id`,`hc_codigo`),
  KEY `salud360_medico_hc_medico_id_index` (`medico_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

Con la tabla creada, `asegurarTabla()` no intenta nada y el perfil se arma. Un médico sin filas ahí queda con la lista de historias clínicas vacía, y la app cae al criterio por especialidad, que hoy sigue activo.

Aprovechá y confirmá que la columna de la nota también esté, porque el código desplegado la espera al formatear pacientes:

```sql
SHOW COLUMNS FROM pacientes LIKE 'nota';
-- si no aparece:
ALTER TABLE pacientes ADD COLUMN nota TEXT NULL AFTER localidad;
```

Ojo con `php artisan migrate` en producción: hay dos migraciones sobre `pacientes` que figuran como archivo pero no están registradas, y cuyas columnas ya existen. El comando intentaría aplicarlas primero y fallaría con `Duplicate column name` antes de llegar a las nuevas.

## Cómo confirmarlo

El log de Laravel tiene el error con el stack completo:

```
storage/logs/laravel.log
```

Las últimas líneas tras un intento fallido dicen exactamente qué tabla o consulta reventó. Si el mensaje menciona `salud360_medico_hc` o `CREATE command denied`, era esto.

## Arreglo de fondo, recomendado

Que el ingreso no dependa de una tabla accesoria. En `Salud360Controller::formatearMedico`, cambiar la línea 197 por una versión que tolere el fallo:

```php
'historias_clinicas' => $this->historiasClinicasSeguras($m->id),
```

y agregar el método en la misma clase:

```php
/**
 * Historias clínicas habilitadas al médico. Si la tabla no existe y no se puede crear (hosting sin
 * permiso de CREATE), devuelve la lista vacía en lugar de tumbar el ingreso: la app cae al criterio
 * por especialidad y el médico entra igual.
 */
protected function historiasClinicasSeguras($medicoId)
{
    try {
        return app(HistoriaClinicaService::class)->habilitadas($medicoId);
    } catch (\Throwable $e) {
        \Log::warning('salud360: no se pudieron leer las historias clínicas del médico ' . $medicoId . ': ' . $e->getMessage());
        return [];
    }
}
```

Es el mismo criterio que ya usan `RecetaController` con `Schema::hasTable('paciente_recetas')` y `TokenService` con su `asegurarTabla()`: lo accesorio no debe romper lo principal.
