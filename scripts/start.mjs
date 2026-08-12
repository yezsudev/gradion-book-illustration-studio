import { spawn } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const npm = process.platform === 'win32' ? 'npm.cmd' : 'npm';
const maven = process.platform === 'win32' ? 'mvn.cmd' : 'mvn';
const root = resolve(import.meta.dirname, '..');
function loadEnvFile(path) {
  if (!existsSync(path)) return {};
  return Object.fromEntries(readFileSync(path, 'utf8').split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line && !line.startsWith('#') && line.includes('='))
    .map((line) => {
      const separator = line.indexOf('=');
      const key = line.slice(0, separator).trim();
      let value = line.slice(separator + 1).trim();
      if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) value = value.slice(1, -1);
      return [key, value];
    }));
}

const env = { ...loadEnvFile(resolve(root, '.env')), ...process.env };
const children = [
  spawn(maven, ['spring-boot:run'], { cwd: resolve(root, 'backend'), stdio: 'inherit', env, shell: process.platform === 'win32' }),
  spawn(npm, ['run', 'dev'], { cwd: resolve(root, 'frontend'), stdio: 'inherit', env, shell: process.platform === 'win32' }),
];

let stopping = false;
function stop() {
  if (stopping) return;
  stopping = true;
  for (const child of children) child.kill();
}

process.on('SIGINT', stop);
process.on('SIGTERM', stop);
for (const child of children) {
  child.on('exit', (code) => {
    if (!stopping && code) process.exitCode = code;
    stop();
  });
}
