import { readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';

const [, , dependencyTreeFile, outputFile] = process.argv;

if (!dependencyTreeFile || !outputFile) {
  throw new Error('用法：node generate-frontend-sbom.mjs <pnpm-list.json> <output.json>');
}

const dependencyTrees = JSON.parse(await readFile(dependencyTreeFile, 'utf8'));
const root = dependencyTrees[0];

if (!root) {
  throw new Error('pnpm 依赖树为空，无法生成前端 SBOM');
}

const components = new Map();
const dependencyGraph = new Map();

function packageUrl(name, version) {
  const encodedName = name.startsWith('@')
    ? `${encodeURIComponent(name.split('/')[0])}/${encodeURIComponent(name.split('/').slice(1).join('/'))}`
    : encodeURIComponent(name);
  return `pkg:npm/${encodedName}@${encodeURIComponent(version)}`;
}

function normalizeLicenses(manifest) {
  const values = [];
  if (typeof manifest.license === 'string') values.push(manifest.license);
  if (Array.isArray(manifest.licenses)) {
    for (const license of manifest.licenses) {
      if (typeof license === 'string') values.push(license);
      if (license && typeof license.type === 'string') values.push(license.type);
    }
  }
  return [...new Set(values.length > 0 ? values : ['NOASSERTION'])]
    .sort()
    .map(name => ({ license: { name } }));
}

function repositoryUrl(manifest) {
  const repository = typeof manifest.repository === 'string' ? manifest.repository : manifest.repository?.url;
  return repository?.replace(/^git\+/, '').replace(/\.git$/, '');
}

async function readManifest(dependency, fallbackName) {
  if (!dependency.path) return { name: fallbackName, version: dependency.version ?? '0.0.0' };
  try {
    return JSON.parse(await readFile(path.join(dependency.path, 'package.json'), 'utf8'));
  } catch {
    return { name: fallbackName, version: dependency.version ?? '0.0.0' };
  }
}

function addDependency(from, to) {
  if (!dependencyGraph.has(from)) dependencyGraph.set(from, new Set());
  dependencyGraph.get(from).add(to);
}

async function visitDependencies(container, parentRef, scope) {
  if (!container) return;
  for (const [requestedName, dependency] of Object.entries(container)) {
    if (!dependency || typeof dependency !== 'object') continue;
    const manifest = await readManifest(dependency, requestedName);
    const name = manifest.name ?? dependency.from ?? requestedName;
    const version = String(manifest.version ?? dependency.version ?? '0.0.0');
    const bomRef = packageUrl(name, version);
    addDependency(parentRef, bomRef);

    if (!components.has(bomRef)) {
      const component = {
        type: 'library',
        'bom-ref': bomRef,
        group: name.startsWith('@') ? name.slice(1).split('/')[0] : '',
        name: name.startsWith('@') ? name.split('/').slice(1).join('/') : name,
        version,
        scope,
        licenses: normalizeLicenses(manifest),
        purl: bomRef
      };
      const repository = repositoryUrl(manifest);
      if (repository) component.externalReferences = [{ type: 'vcs', url: repository }];
      components.set(bomRef, component);
    }

    if (!dependencyGraph.has(bomRef)) dependencyGraph.set(bomRef, new Set());
    await visitDependencies(dependency.dependencies, bomRef, scope);
    await visitDependencies(dependency.optionalDependencies, bomRef, 'optional');
  }
}

const rootName = root.name ?? '@bosco/mine-web';
const rootVersion = String(root.version ?? '0.0.0');
const rootRef = packageUrl(rootName, rootVersion);
dependencyGraph.set(rootRef, new Set());
await visitDependencies(root.dependencies, rootRef, 'required');
await visitDependencies(root.optionalDependencies, rootRef, 'optional');
await visitDependencies(root.devDependencies, rootRef, 'optional');

const bom = {
  $schema: 'http://cyclonedx.org/schema/bom-1.6.schema.json',
  bomFormat: 'CycloneDX',
  specVersion: '1.6',
  version: 1,
  metadata: {
    component: {
      type: 'application',
      'bom-ref': rootRef,
      group: rootName.startsWith('@') ? rootName.slice(1).split('/')[0] : '',
      name: rootName.startsWith('@') ? rootName.split('/').slice(1).join('/') : rootName,
      version: rootVersion,
      licenses: normalizeLicenses(root),
      purl: rootRef
    }
  },
  components: [...components.values()].sort((left, right) => left['bom-ref'].localeCompare(right['bom-ref'])),
  dependencies: [...dependencyGraph.entries()]
    .map(([ref, dependencies]) => ({ ref, dependsOn: [...dependencies].sort() }))
    .sort((left, right) => left.ref.localeCompare(right.ref))
};

await writeFile(outputFile, `${JSON.stringify(bom, null, 2)}\n`, 'utf8');
