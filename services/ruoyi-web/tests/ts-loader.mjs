/**
 * Lets Node's built-in test runner execute the TypeScript sources directly, using the
 * `typescript` package that is already a devDependency of this package.
 *
 * Why not a test framework: this package has no test runner configured, and adding one
 * would mean a new dependency plus a lockfile change. Node 22 ships `node:test`, and
 * `typescript` is already installed, so the only missing piece is type stripping — which
 * is all this file does. Nothing is type-checked here; run the tests tsconfig for that:
 *
 *   node --import ./tests/ts-loader.mjs --test tests/*.test.ts   # run
 *   npx tsc -p tsconfig.tests.json --noEmit                     # types
 */

import { createRequire } from 'node:module';
import { readFile } from 'node:fs/promises';
import { register } from 'node:module';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);

/**
 * Resolves the already-installed `typescript` package without assuming a hoisted layout.
 * pnpm keeps dependencies in `node_modules/.pnpm/<pkg>/node_modules/<pkg>`, so a plain
 * `import 'typescript'` fails whenever this package is not a direct dependency of the
 * workspace root.
 */
async function loadTypeScript() {
  const candidates = [
    'typescript', // normal resolution (hoisted or direct dependency)
    join(here, '..', 'node_modules', 'typescript', 'lib', 'typescript.js'),
    join(here, '..', '..', 'node_modules', 'typescript', 'lib', 'typescript.js'),
    join(here, '..', '..', '..', 'node_modules', 'typescript', 'lib', 'typescript.js'),
  ];

  const errors = [];
  for (const candidate of candidates) {
    try {
      const resolved = candidate === 'typescript' ? require.resolve(candidate) : candidate;
      const module = await import(pathToFileURL(resolved).href);
      return module.default ?? module;
    }
    catch (error) {
      errors.push(`${candidate}: ${error.code ?? error.message}`);
    }
  }
  throw new Error(
    'ts-loader could not locate the "typescript" package. Install dependencies first '
    + '(pnpm install --frozen-lockfile), or run pnpm add -D typescript.\nTried:\n'
    + errors.join('\n'),
  );
}

const ts = await loadTypeScript();

register('./ts-loader.mjs', import.meta.url);

/** Transpile one TypeScript file to ESM JavaScript. */
export async function load(url, context, nextLoad) {
  if (!url.endsWith('.ts') || url.endsWith('.d.ts')) return nextLoad(url, context);

  const path = fileURLToPath(url);
  const source = await readFile(path, 'utf8');
  const { outputText } = ts.transpileModule(source, {
    fileName: path,
    compilerOptions: {
      target: ts.ScriptTarget.ES2022,
      module: ts.ModuleKind.ESNext,
      moduleResolution: ts.ModuleResolutionKind.Bundler,
      sourceMap: true,
      inlineSources: true,
      // Drop type-only imports so the emitted JavaScript has no dangling imports.
      importsNotUsedAsValues: ts.ImportsNotUsedAsValues.Remove,
    },
  });

  return { format: 'module', source: outputText, shortCircuit: true };
}

/** Resolve `./Foo` to `./Foo.ts` when only the TypeScript file exists. */
export async function resolve(specifier, context, nextResolve) {
  try {
    return await nextResolve(specifier, context);
  }
  catch (error) {
    if (specifier.startsWith('.') && !/\.[a-z]+$/i.test(specifier)) {
      return nextResolve(`${specifier}.ts`, context);
    }
    throw error;
  }
}
