// Rebuild committed browser assets from the audited, locked dependency graph.
const {buildSync} = require('esbuild');
const {mkdirSync, copyFileSync} = require('node:fs');
const {resolve} = require('node:path');
const output = resolve(__dirname, '../../factory/src/main/resources/static/chat/vendor');
mkdirSync(output, {recursive: true});
buildSync({
  stdin: {contents: 'export {default} from "mermaid";', resolveDir: __dirname},
  bundle: true, minify: true, format: 'iife', globalName: 'FactoryMermaid',
  platform: 'browser', target: ['es2022'], legalComments: 'external',
  outfile: resolve(output, 'mermaid.js'),
});
copyFileSync(resolve(__dirname, 'node_modules/mermaid/LICENSE'), resolve(output, 'MERMAID-LICENSE'));
