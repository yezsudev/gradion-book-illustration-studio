import { spawn } from 'node:child_process';
import { resolve } from 'node:path';

const npm = process.platform === 'win32' ? 'npm.cmd' : 'npm';
const maven = process.platform === 'win32' ? 'mvn.cmd' : 'mvn';
const root = resolve(import.meta.dirname, '..');
const children = [
  spawn(maven, ['spring-boot:run'], { cwd: resolve(root, 'backend'), stdio: 'inherit', shell: process.platform === 'win32' }),
  spawn(npm, ['run', 'dev'], { cwd: resolve(root, 'frontend'), stdio: 'inherit', shell: process.platform === 'win32' }),
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
