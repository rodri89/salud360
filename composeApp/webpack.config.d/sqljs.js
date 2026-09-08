// Configuración necesaria para el driver web de SQLDelight (sql.js dentro de un Web Worker).
const CopyWebpackPlugin = require('copy-webpack-plugin');

config.resolve = config.resolve || {};
config.resolve.fallback = Object.assign({}, config.resolve.fallback, { fs: false, path: false, crypto: false });

config.plugins.push(
    new CopyWebpackPlugin({
        patterns: [
            { from: '../../node_modules/sql.js/dist/sql-wasm.wasm', to: '.' }
        ]
    })
);
