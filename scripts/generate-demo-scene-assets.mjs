#!/usr/bin/env node

import { createHash } from 'node:crypto';
import { mkdir, readFile, rename, rm, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));
const REPOSITORY_ROOT = resolve(SCRIPT_DIR, '..');
const OUTPUT_FILE = resolve(REPOSITORY_ROOT, 'deploy/demo/scene/demo-mine-grid.glb');
const EXPECTED_SHA256 = '4e6e5c0c2a35bcea48a9a6679583f3bed5c1d5b225869cb27f9998e4cc611c78';

const args = new Set(process.argv.slice(2));
const unknownArgs = [...args].filter(argument => argument !== '--check');
if (unknownArgs.length > 0) {
  fail(`不支持的参数：${unknownArgs.join(', ')}`);
}

const bytes = generateDemoMineGlb();
const digest = sha256(bytes);

if (args.has('--check')) {
  await checkExistingAsset(bytes, digest);
} else {
  await writeAsset(bytes);
  process.stdout.write(`已生成 ${relativeOutputPath()}\nSHA-256: ${digest}\n`);
}

/**
 * 生成只含仓库自制线框几何的确定性 GLB 2.0 文件。
 *
 * @returns {Buffer} 完整 GLB 文件内容
 */
function generateDemoMineGlb() {
  const positions = createLinePositions();
  const positionBytes = encodeFloat32LittleEndian(positions);
  const bounds = calculateBounds(positions);
  const gltf = {
    asset: {
      version: '2.0',
      generator: 'Bosco Mine deterministic demo scene generator v1',
      copyright: 'CC0-1.0; generated geometry, no third-party assets',
      extras: {
        assetId: 'bosco-mine-demo-grid-v1',
        coordinateConvention: 'local ENU metres',
        deterministic: true,
        license: 'CC0-1.0',
        source: 'repository-generated'
      }
    },
    extensionsUsed: ['KHR_materials_unlit'],
    scene: 0,
    scenes: [{ name: '演示露天矿网格', nodes: [0] }],
    nodes: [{ name: '矿区网格与采坑轮廓', mesh: 0 }],
    meshes: [{
      name: '离线演示几何',
      primitives: [{
        attributes: { POSITION: 0 },
        material: 0,
        mode: 1
      }]
    }],
    materials: [{
      name: '演示线框材质',
      doubleSided: true,
      pbrMetallicRoughness: {
        baseColorFactor: [0.18, 0.74, 0.52, 1],
        metallicFactor: 0,
        roughnessFactor: 1
      },
      extensions: { KHR_materials_unlit: {} }
    }],
    buffers: [{ byteLength: positionBytes.length }],
    bufferViews: [{
      buffer: 0,
      byteOffset: 0,
      byteLength: positionBytes.length,
      target: 34962
    }],
    accessors: [{
      bufferView: 0,
      byteOffset: 0,
      componentType: 5126,
      count: positions.length / 3,
      type: 'VEC3',
      min: bounds.min,
      max: bounds.max
    }]
  };

  const jsonBytes = Buffer.from(JSON.stringify(gltf), 'utf8');
  const paddedJson = padChunk(jsonBytes, 0x20);
  const paddedBinary = padChunk(positionBytes, 0x00);
  const totalLength = 12 + 8 + paddedJson.length + 8 + paddedBinary.length;
  const output = Buffer.alloc(totalLength);

  output.writeUInt32LE(0x46546c67, 0);
  output.writeUInt32LE(2, 4);
  output.writeUInt32LE(totalLength, 8);
  output.writeUInt32LE(paddedJson.length, 12);
  output.writeUInt32LE(0x4e4f534a, 16);
  paddedJson.copy(output, 20);

  const binaryHeaderOffset = 20 + paddedJson.length;
  output.writeUInt32LE(paddedBinary.length, binaryHeaderOffset);
  output.writeUInt32LE(0x004e4942, binaryHeaderOffset + 4);
  paddedBinary.copy(output, binaryHeaderOffset + 8);
  return output;
}

/**
 * 创建矿区坐标网格、分层采坑和运输道路的线段端点。
 *
 * @returns {number[]} 按 XYZ 排列的线段端点
 */
function createLinePositions() {
  const positions = [];
  const addLine = (x1, y1, z1, x2, y2, z2) => {
    positions.push(x1, y1, z1, x2, y2, z2);
  };

  for (let coordinate = -120; coordinate <= 120; coordinate += 30) {
    addLine(coordinate, -120, 0, coordinate, 120, 0);
    addLine(-120, coordinate, 0, 120, coordinate, 0);
  }

  const pitLevels = [
    { halfWidth: 90, halfHeight: 66, z: -8 },
    { halfWidth: 68, halfHeight: 48, z: -20 },
    { halfWidth: 46, halfHeight: 30, z: -32 },
    { halfWidth: 24, halfHeight: 14, z: -44 }
  ];
  for (const level of pitLevels) {
    addRectangle(positions, level.halfWidth, level.halfHeight, level.z);
  }

  for (let level = 0; level < pitLevels.length - 1; level += 1) {
    const current = pitLevels[level];
    const next = pitLevels[level + 1];
    addLine(current.halfWidth, -current.halfHeight, current.z, next.halfWidth, -next.halfHeight, next.z);
    addLine(-current.halfWidth, current.halfHeight, current.z, -next.halfWidth, next.halfHeight, next.z);
  }

  addLine(-120, 100, 2, -72, 70, 2);
  addLine(-72, 70, 2, -36, 26, -10);
  addLine(-36, 26, -10, 18, -2, -22);
  addLine(-112, 108, 2, -66, 78, 2);
  addLine(-66, 78, 2, -30, 34, -10);
  addLine(-30, 34, -10, 24, 6, -22);
  return positions;
}

/**
 * 向端点数组添加闭合矩形轮廓。
 *
 * @param {number[]} positions 目标端点数组
 * @param {number} halfWidth 矩形半宽
 * @param {number} halfHeight 矩形半高
 * @param {number} z 高程
 */
function addRectangle(positions, halfWidth, halfHeight, z) {
  const corners = [
    [-halfWidth, -halfHeight],
    [halfWidth, -halfHeight],
    [halfWidth, halfHeight],
    [-halfWidth, halfHeight]
  ];
  for (let index = 0; index < corners.length; index += 1) {
    const current = corners[index];
    const next = corners[(index + 1) % corners.length];
    positions.push(current[0], current[1], z, next[0], next[1], z);
  }
}

/**
 * 以固定小端序编码浮点位置，避免依赖宿主机端序。
 *
 * @param {number[]} values 浮点数组
 * @returns {Buffer} 二进制位置缓冲
 */
function encodeFloat32LittleEndian(values) {
  const bytes = Buffer.alloc(values.length * 4);
  values.forEach((value, index) => bytes.writeFloatLE(value, index * 4));
  return bytes;
}

/**
 * 计算 POSITION accessor 的确定性边界。
 *
 * @param {number[]} positions XYZ 数组
 * @returns {{min: number[], max: number[]}} 三轴边界
 */
function calculateBounds(positions) {
  const min = [Number.POSITIVE_INFINITY, Number.POSITIVE_INFINITY, Number.POSITIVE_INFINITY];
  const max = [Number.NEGATIVE_INFINITY, Number.NEGATIVE_INFINITY, Number.NEGATIVE_INFINITY];
  for (let index = 0; index < positions.length; index += 3) {
    for (let axis = 0; axis < 3; axis += 1) {
      min[axis] = Math.min(min[axis], positions[index + axis]);
      max[axis] = Math.max(max[axis], positions[index + axis]);
    }
  }
  return { min, max };
}

/**
 * 将 GLB 数据块补齐到四字节边界。
 *
 * @param {Buffer} bytes 原始块
 * @param {number} paddingByte 填充字节
 * @returns {Buffer} 对齐后的块
 */
function padChunk(bytes, paddingByte) {
  const paddingLength = (4 - (bytes.length % 4)) % 4;
  return paddingLength === 0
    ? bytes
    : Buffer.concat([bytes, Buffer.alloc(paddingLength, paddingByte)]);
}

/**
 * 校验工作区资产与生成结果、固定摘要完全一致。
 *
 * @param {Buffer} generatedBytes 本次生成内容
 * @param {string} generatedDigest 本次生成摘要
 */
async function checkExistingAsset(generatedBytes, generatedDigest) {
  const repeatedBytes = generateDemoMineGlb();
  if (!repeatedBytes.equals(generatedBytes) || sha256(repeatedBytes) !== generatedDigest) {
    fail('同一生成器进程的两次输出不一致');
  }
  if (!/^[a-f0-9]{64}$/.test(EXPECTED_SHA256)) {
    fail('生成器尚未固定 EXPECTED_SHA256');
  }
  if (generatedDigest !== EXPECTED_SHA256) {
    fail(`生成结果摘要漂移：期望 ${EXPECTED_SHA256}，实际 ${generatedDigest}`);
  }

  let existingBytes;
  try {
    existingBytes = await readFile(OUTPUT_FILE);
  } catch (error) {
    fail(`演示资产不存在：${relativeOutputPath()} (${error.code ?? 'UNKNOWN'})`);
  }
  if (!existingBytes.equals(generatedBytes)) {
    fail(`演示资产与生成器输出不一致：${relativeOutputPath()}`);
  }
  process.stdout.write(`演示资产校验通过\nSHA-256: ${generatedDigest}\n`);
}

/**
 * 原子写入生成资产。
 *
 * @param {Buffer} bytes GLB 文件内容
 */
async function writeAsset(bytes) {
  await mkdir(dirname(OUTPUT_FILE), { recursive: true });
  const temporaryFile = `${OUTPUT_FILE}.${process.pid}.tmp`;
  try {
    await writeFile(temporaryFile, bytes);
    await rename(temporaryFile, OUTPUT_FILE);
  } finally {
    await rm(temporaryFile, { force: true });
  }
}

/**
 * 计算 SHA-256 十六进制摘要。
 *
 * @param {Buffer} bytes 输入内容
 * @returns {string} 小写十六进制摘要
 */
function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}

/** @returns {string} 仓库相对输出路径 */
function relativeOutputPath() {
  return 'deploy/demo/scene/demo-mine-grid.glb';
}

/**
 * 输出错误并终止生成器。
 *
 * @param {string} message 错误说明
 */
function fail(message) {
  process.stderr.write(`FAIL: ${message}\n`);
  process.exit(1);
}
