// Archivos estáticos de la base SQLite web (sql.js): el motor (sql-wasm.js + sql-wasm.wasm) los usa el
// worker persistente `sqljs-persistente.worker.js` (recurso de composeApp) que crea `core/database`.
const CopyWebpackPlugin = require('copy-webpack-plugin');

config.resolve = config.resolve || {};
config.resolve.fallback = Object.assign({}, config.resolve.fallback, { fs: false, path: false, crypto: false });

config.plugins.push(
    new CopyWebpackPlugin({
        patterns: [
            { from: '../../node_modules/sql.js/dist/sql-wasm.wasm', to: '.' },
            { from: '../../node_modules/sql.js/dist/sql-wasm.js', to: '.' }
        ]
    })
);
