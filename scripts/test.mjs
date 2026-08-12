import { spawn } from 'node:child_process';
import { resolve } from 'node:path';

const npm = process.platform === 'win32' ? 'npm.cmd' : 'npm';
const maven = process.platform === 'win32' ? 'mvn.cmd' : 'mvn';
const root = resolve(import.meta.dirname, '..');

function run(command, args, cwd) {
  return new Promise((resolveRun, reject) => {
    const child = spawn(command, args, { cwd, stdio: 'inherit', shell: process.platform === 'win32' });
    child.on('error', reject);
    child.on('exit', (code) => code === 0 ? resolveRun() : reject(new Error(`${command} exited with ${code}`)));
  });
}

await run(maven, ['-f', 'backend/pom.xml', 'test'], root);
await run(npm, ['--prefix', 'frontend', 'test', '--', '--run'], root);
