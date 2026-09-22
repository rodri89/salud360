// Worker de la base SQLite de Salud 360 en la web: sql.js en memoria + copia persistente en IndexedDB.
//
// Reemplaza al worker de @cashapp/sqldelight-sqljs-worker (mismo protocolo de mensajes: exec,
// begin_transaction, end_transaction, rollback_transaction) agregando persistencia: al arrancar se
// restaura la base guardada en IndexedDB y, después de cada escritura fuera de una transacción (o al
// cerrar una), se vuelve a guardar con un pequeño retardo. Así la base sobrevive a recargar la pestaña.
// Se sirve como archivo estático junto con sql-wasm.js y sql-wasm.wasm (ver webpack.config.d/sqljs.js).
importScripts("sql-wasm.js");

const IDB_NOMBRE = "salud360";
const IDB_STORE = "base";
const IDB_CLAVE = "principal";
const RETARDO_GUARDADO_MS = 400;

let db = null;
let enTransaccion = false;
let guardadoPendiente = null;
let guardando = Promise.resolve();

function abrirIdb() {
  return new Promise((resolver, rechazar) => {
    const pedido = indexedDB.open(IDB_NOMBRE, 1);
    pedido.onupgradeneeded = () => pedido.result.createObjectStore(IDB_STORE);
    pedido.onsuccess = () => resolver(pedido.result);
    pedido.onerror = () => rechazar(pedido.error);
  });
}

async function leerGuardada() {
  try {
    const idb = await abrirIdb();
    return await new Promise((resolver, rechazar) => {
      const tx = idb.transaction(IDB_STORE, "readonly");
      const pedido = tx.objectStore(IDB_STORE).get(IDB_CLAVE);
      pedido.onsuccess = () => resolver(pedido.result || null);
      pedido.onerror = () => rechazar(pedido.error);
    });
  } catch (e) {
    console.warn("Salud 360: no se pudo leer la base guardada, se arranca vacía", e);
    return null;
  }
}

async function escribirGuardada(bytes) {
  const idb = await abrirIdb();
  await new Promise((resolver, rechazar) => {
    const tx = idb.transaction(IDB_STORE, "readwrite");
    tx.objectStore(IDB_STORE).put(bytes, IDB_CLAVE);
    tx.oncomplete = () => resolver();
    tx.onerror = () => rechazar(tx.error);
  });
}

function programarGuardado() {
  if (enTransaccion) return; // se guarda al cerrar la transacción
  if (guardadoPendiente) clearTimeout(guardadoPendiente);
  guardadoPendiente = setTimeout(() => {
    guardadoPendiente = null;
    if (enTransaccion) return;
    // db.export() cierra y reabre la base internamente: nunca en medio de una transacción.
    const bytes = db.export();
    guardando = guardando.then(() => escribirGuardada(bytes)).catch(e => console.warn("Salud 360: no se pudo guardar la base", e));
  }, RETARDO_GUARDADO_MS);
}

function esEscritura(sql) {
  return !/^\s*(select|pragma\s+[a-z_]+\s*$|explain)/i.test(sql);
}

async function crearBase() {
  const SQL = await initSqlJs({ locateFile: archivo => archivo });
  const guardada = await leerGuardada();
  db = guardada ? new SQL.Database(guardada) : new SQL.Database();
}

function alRecibir() {
  const data = this.data;
  switch (data && data.action) {
    case "exec": {
      if (!data["sql"]) throw new Error("exec: Missing query string");
      const resultado = db.exec(data.sql, data.params)[0] ?? { values: [] };
      if (esEscritura(data.sql)) programarGuardado();
      return postMessage({ id: data.id, results: resultado });
    }
    case "begin_transaction":
      enTransaccion = true;
      return postMessage({ id: data.id, results: db.exec("BEGIN TRANSACTION;") });
    case "end_transaction": {
      const r = db.exec("END TRANSACTION;");
      enTransaccion = false;
      programarGuardado();
      return postMessage({ id: data.id, results: r });
    }
    case "rollback_transaction": {
      const r = db.exec("ROLLBACK TRANSACTION;");
      enTransaccion = false;
      return postMessage({ id: data.id, results: r });
    }
    default:
      throw new Error(`Unsupported action: ${data && data.action}`);
  }
}

function alFallar(err) {
  return postMessage({ id: this.data.id, error: err });
}

db = null;
const baseLista = crearBase();
self.onmessage = (event) => baseLista.then(alRecibir.bind(event)).catch(alFallar.bind(event));
