import { mkdtemp, rm } from 'node:fs/promises'
import { createServer } from 'node:http'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { spawn } from 'node:child_process'

const fixture = `<!doctype html><html><body>
<nav><div id="new-task">新工作任务</div></nav>
<main>
  <div id="composer" role="textbox" contenteditable="true"></div>
  <button id="more-skills">更多技能</button>
  <div id="picker" role="listbox" hidden>
    <div role="group" aria-label="常用 (1)">
      <div role="option">project-plan 常用</div>
    </div>
    <div role="group" aria-label="企业 (2)">
      <div role="option" data-skill="project-plan">project-plan 企业</div>
      <div role="option" data-skill="requirement-analysis">requirement-analysis 企业</div>
    </div>
  </div>
  <div id="messages"></div>
</main>
<script>
const composer = document.querySelector('#composer');
const picker = document.querySelector('#picker');
document.querySelector('#new-task').onclick = () => {
  composer.replaceChildren();
  history.pushState({}, '', '/chat?channel=test');
};
document.querySelector('#more-skills').onclick = () => { picker.hidden = false; };
for (const option of document.querySelectorAll('[data-skill]')) {
  option.onclick = () => {
    const token = document.createElement('button');
    token.type = 'button';
    token.textContent = option.dataset.skill;
    token.contentEditable = 'false';
    composer.append(token);
    picker.hidden = true;
    composer.focus();
  };
}
composer.onkeydown = (event) => {
  if (event.key !== 'Enter') return;
  event.preventDefault();
  const message = document.createElement('div');
  message.textContent = composer.textContent;
  document.querySelector('#messages').append(message);
  composer.replaceChildren();
  history.pushState({}, '', '/chat/fixture-task-1?channel=test');
};
</script></body></html>`

const unauthenticatedFixture = `<!doctype html><html><body>
<button id="login">登录</button>
<div id="qr" hidden>请扫码登录豆包</div>
<script>
if (location.search.includes('openDuplicate=1') && !location.search.includes('duplicate=1')) {
  setTimeout(() => window.open('/chat/skills?unauthenticated=1&duplicate=1', '_blank'), 50);
}
document.querySelector('#login').onclick = () => {
  document.querySelector('#login').hidden = true;
  document.querySelector('#qr').hidden = false;
};
</script></body></html>`

const server = createServer((request, response) => {
  response.setHeader('content-type', 'text/html; charset=utf-8')
  response.end(request.url?.includes('unauthenticated') ? unauthenticatedFixture : fixture)
})
await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
const port = server.address().port
const directory = await mkdtemp(join(tmpdir(), 'zhiling-initialization-test-'))

const child = spawn(process.execPath, [new URL('./initialize-doubao-agent.mjs', import.meta.url).pathname], {
  env: {
    ...process.env,
    DOUBAO_WORK_URL: `http://127.0.0.1:${port}/chat/skills`,
    DOUBAO_USER_DATA_DIR: join(directory, 'profile'),
  },
  stdio: ['pipe', 'pipe', 'pipe'],
})
child.stdin.end(JSON.stringify({
  tenantName: '测试租户',
  operatorName: '普通用户',
  larkUser: '普通用户',
  idempotencyKey: 'fixture-job-1',
  skills: ['project-plan', 'requirement-analysis'],
}))

let stdout = ''
let stderr = ''
child.stdout.on('data', (chunk) => { stdout += chunk })
child.stderr.on('data', (chunk) => { stderr += chunk })
const exitCode = await new Promise((resolve) => child.on('close', resolve))

await rm(directory, { recursive: true, force: true })
if (exitCode !== 0) throw new Error(stderr || stdout || `worker exited ${exitCode}`)
const output = JSON.parse(stdout)
if (!output.success) throw new Error(stdout)
if (!output.taskUrl.includes('/chat/fixture-task-1')) throw new Error(stdout)
if (!output.initializedSkillIds.includes('project-plan')) throw new Error(stdout)
if (!output.initializedSkillIds.includes('requirement-analysis')) throw new Error(stdout)
process.stdout.write('Playwright initialization Worker E2E verification passed\n')

const unauthDirectory = await mkdtemp(join(tmpdir(), 'zhiling-initialization-unauth-test-'))
const unauthChild = spawn(process.execPath, [new URL('./initialize-doubao-agent.mjs', import.meta.url).pathname], {
  env: {
    ...process.env,
    DOUBAO_WORK_URL: `http://127.0.0.1:${port}/chat/skills?unauthenticated=1&openDuplicate=1`,
    DOUBAO_USER_DATA_DIR: join(unauthDirectory, 'profile'),
  },
  stdio: ['pipe', 'pipe', 'pipe'],
})
unauthChild.stdin.end(JSON.stringify({
  tenantName: '测试租户',
  operatorName: '普通用户',
  larkUser: '普通用户',
  idempotencyKey: 'fixture-unauth-job-1',
  skills: ['project-plan'],
}))

let unauthStdout = ''
let unauthStderr = ''
unauthChild.stdout.on('data', (chunk) => { unauthStdout += chunk })
unauthChild.stderr.on('data', (chunk) => { unauthStderr += chunk })
const unauthExitCode = await new Promise((resolve) => unauthChild.on('close', resolve))
await rm(unauthDirectory, { recursive: true, force: true })
if (unauthExitCode !== 0) throw new Error(unauthStderr || unauthStdout || `unauth worker exited ${unauthExitCode}`)
const unauthOutput = JSON.parse(unauthStdout)
if (unauthOutput.success || !unauthOutput.requiresUserAction) throw new Error(unauthStdout)
if (!/扫码登录/.test(unauthOutput.message)) throw new Error(unauthStdout)
if (!/已关闭 1 个重复豆包工作台页面/.test(unauthOutput.message)) throw new Error(unauthStdout)
server.close()
process.stdout.write('Playwright unauthenticated login and duplicate-page verification passed\n')
