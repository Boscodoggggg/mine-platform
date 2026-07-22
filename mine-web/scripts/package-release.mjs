import { lstat, mkdir, readFile, readdir, rename, rm, writeFile } from 'node:fs/promises';
import { dirname, posix, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Uint8ArrayReader, Uint8ArrayWriter, ZipWriter } from '@zip.js/zip.js';

const projectDir = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const distDir = resolve(projectDir, 'dist');
const releaseDir = resolve(projectDir, '..', 'release');
const outputFile = resolve(releaseDir, 'mine-web.zip');
const temporaryFile = `${outputFile}.tmp-${process.pid}`;
const stableDate = new Date(1980, 0, 1, 0, 0, 0, 0);

async function collectFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  entries.sort((left, right) => (left.name < right.name ? -1 : left.name > right.name ? 1 : 0));

  const files = [];
  for (const entry of entries) {
    const absolutePath = resolve(directory, entry.name);
    if (entry.isSymbolicLink()) {
      throw new Error(`前端发行目录不得包含符号链接：${absolutePath}`);
    }
    if (entry.isDirectory()) {
      files.push(...(await collectFiles(absolutePath)));
    } else if (entry.isFile()) {
      files.push(absolutePath);
    }
  }
  return files;
}

async function packageRelease() {
  const indexStat = await lstat(resolve(distDir, 'index.html'));
  if (!indexStat.isFile() || indexStat.size === 0) {
    throw new Error('前端 dist/index.html 缺失或为空');
  }

  const files = await collectFiles(distDir);
  const writer = new Uint8ArrayWriter();
  const zipWriter = new ZipWriter(writer, { extendedTimestamp: false, useWebWorkers: false });

  for (const absolutePath of files) {
    const entryName = relative(distDir, absolutePath).split(sep).join(posix.sep);
    const content = await readFile(absolutePath);
    await zipWriter.add(entryName, new Uint8ArrayReader(content), {
      creationDate: stableDate,
      lastAccessDate: stableDate,
      lastModDate: stableDate
    });
  }

  const archive = await zipWriter.close();
  await mkdir(releaseDir, { recursive: true });
  await writeFile(temporaryFile, archive, { mode: 0o644 });
  await rename(temporaryFile, outputFile);
  console.log(`前端发行包已生成：${outputFile}`);
}

packageRelease().catch(async error => {
  await rm(temporaryFile, { force: true });
  console.error(error instanceof Error ? error.message : error);
  process.exitCode = 1;
});
