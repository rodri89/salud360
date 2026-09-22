# Nota del paciente en turnosonlinebb

Pasos para agregar la columna `nota` a la tabla `pacientes` de la web (Laravel 5.8, `/Applications/MAMP/htdocs/TurnosOnlineBB/TurnosImage`).

La app de Salud 360 ya está lista: manda `nota` en el alta (`POST pacientes`) y en la edición (`PUT pacientes/{id}`), y la lee de `formatearPaciente`. Mientras la web no tenga la columna, la app sigue funcionando: la nota queda guardada solo en el dispositivo y el campo que manda se ignora.

Es una nota interna del consultorio, del estilo "no cobrar, es familiar del médico". La ve el equipo de salud en la agenda y en el listado de pacientes; **no** se le muestra al paciente.

---

## Antes de empezar: el historial de migraciones está desincronizado

Dos migraciones sobre `pacientes` existen como archivo pero **no** figuran en la tabla `migrations`, y sus columnas ya fueron creadas a mano en la base:

- `2026_02_03_161943_add_fcm_token_to_pacientes_table.php`
- `2026_02_04_000000_add_google_calendar_oauth_to_pacientes_table.php`

Correr `php artisan migrate` hoy intentaría aplicarlas y fallaría con `Duplicate column name` **antes** de llegar a cualquier migración nueva. Por eso el camino recomendado es el `ALTER TABLE` directo del paso 1.

Si preferís destrabar `migrate`, marcá esas dos como aplicadas antes de correrlo:

```sql
INSERT INTO migrations (migration, batch) VALUES
  ('2026_02_03_161943_add_fcm_token_to_pacientes_table', 22),
  ('2026_02_04_000000_add_google_calendar_oauth_to_pacientes_table', 22);
```

---

## 1. La columna

```sql
ALTER TABLE pacientes ADD COLUMN nota TEXT NULL AFTER localidad;
```

**Nullable a propósito.** Cuatro controladores crean pacientes asignando campo por campo (`PacienteController@registrarPacientePendiente`, `PacienteController@crearPaciente`, `SecretariaController@registrarPaciente`, `MedicoController@registrarPaciente`) y ninguno conoce esta columna. Una columna `NOT NULL` sin valor por defecto los rompería con MySQL en modo estricto. Es el mismo problema latente que ya tiene `google_calendar_token_expires_at`, que quedó `varchar(360) NOT NULL` sin default y no se inicializa en el alta de la API.

Verificación:

```sql
SHOW COLUMNS FROM pacientes LIKE 'nota';
```

### Migración equivalente, si querés mantener el historial

`database/migrations/2026_09_21_000000_add_nota_to_pacientes_table.php`. Sintaxis vieja, que es la del proyecto, y con guarda para que sea idempotente si el ALTER ya se aplicó a mano:

```php
<?php

use Illuminate\Support\Facades\Schema;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Database\Migrations\Migration;

class AddNotaToPacientesTable extends Migration
{
    /**
     * Nota interna del consultorio sobre el paciente (la ve el equipo de salud, no el paciente).
     * Nullable: los altas de paciente de la web asignan campo por campo y no la conocen.
     */
    public function up()
    {
        if (Schema::hasColumn('pacientes', 'nota')) {
            return;
        }
        Schema::table('pacientes', function (Blueprint $table) {
            $table->text('nota')->nullable()->after('localidad');
        });
    }

    public function down()
    {
        if (!Schema::hasColumn('pacientes', 'nota')) {
            return;
        }
        Schema::table('pacientes', function (Blueprint $table) {
            $table->dropColumn('nota');
        });
    }
}
```

## 2. Modelo `app/Paciente.php`

Sumar `'nota'` a `$fillable`:

```php
protected $fillable = ['nombre', 'apellido', 'dni', 'telefono', 'domicilio', 'localidad', 'mail', 'nota', 'fecha_nacimiento', /* … el resto igual … */];
```

Hoy ningún flujo usa asignación masiva, así que no es imprescindible, pero mantiene la lista completa por si en algún momento se usa `fill()`.

## 3. Exponerla en la API

`app/Http/Controllers/Api/Salud360/Salud360Controller.php`, método `formatearPaciente`. Agregar una línea junto a las demás:

```php
'nota' => isset($p->nota) ? (string) $p->nota : '',
```

El `isset` importa: algunas consultas arman el objeto con un `select()` parcial, y sin él tiraría un notice. Es el mismo criterio que ya se usa con `sexo` en `formatearMedico`.

## 4. Aceptarla al guardar

`app/Http/Controllers/Api/Salud360/PacienteController.php`, método privado `aplicarCampos`. Agregar `'nota'` al array de campos de texto:

```php
$texto = ['nombre', 'apellido', 'telefono', 'domicilio', 'localidad', 'mail', 'nota', 'obra_social', 'numero_afiliado', 'obra_social_plan'];
```

Con eso `store` (alta) y `update` (edición) la aceptan, porque ambos delegan en ese helper. No hace falta tocar nada más: `update` ya solo pisa los campos que recibe.

## 5. Probar

Con un token válido y el id de un paciente real:

```bash
curl -X PUT "https://turnosonlinebb.com/api/salud360/pacientes/123" \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"nota":"no cobrar, es familiar del medico"}'
```

La respuesta tiene que traer la nota dentro de `paciente`:

```json
{"ok":true,"paciente":{"id":123,"nombre":"...","nota":"no cobrar, es familiar del medico"}}
```

Y confirmar que no se pisó nada más:

```sql
SELECT id, nombre, apellido, telefono, nota FROM pacientes WHERE id = 123;
```

Después, desde la app: editar la nota de un paciente, refrescar la agenda del día y verificar que sigue ahí y que aparece el punto al lado del nombre.

---

## Qué hace la app de este lado

| Momento | Pedido | Qué manda |
|---|---|---|
| Guardar la ficha de un paciente que ya existe en la web | `PUT pacientes/{id}` | nombre, apellido, dni, teléfono, mail, domicilio, localidad, obra social, número de afiliado, plan, fecha de nacimiento y nota |
| Guardar la ficha de un paciente que todavía no existe | `POST pacientes` | lo mismo, más `medico_id` |
| Leer pacientes | `pacientes/buscar`, `pacientes/vinculados`, turnos del día | toma `nota` de `formatearPaciente` |

Dos detalles de comportamiento:

- Al guardar viajan **todos** los campos editables, no solo la nota. Antes de este cambio la app nunca llamaba a `update`, así que editar un teléfono desde la app se perdía en cuanto la web volvía a informar al paciente.
- Si la web devuelve la nota vacía, la app **conserva** la que tenga guardada localmente. Así no se pierde nada mientras la columna no exista.
