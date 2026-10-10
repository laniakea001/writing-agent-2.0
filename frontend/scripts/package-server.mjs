import {cp,mkdir} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
const source=fileURLToPath(new URL('../dist/',import.meta.url));
const destination=fileURLToPath(new URL('../../src/main/resources/static/workbench/',import.meta.url));
await mkdir(destination,{recursive:true});
await cp(source,destination,{recursive:true});
console.log('Packaged the workbench for the Java service at /workbench/.');
