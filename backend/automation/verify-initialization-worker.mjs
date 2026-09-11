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

const server = createServer((request, response) => {
  response.setHeader('content-type', 'text/html; charset=utf-8')
  response.end(fixture)
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

server.close()
await rm(directory, { recursive: true, force: true })
if (exitCode !== 0) throw new Error(stderr || stdout || `worker exited ${exitCode}`)
const output = JSON.parse(stdout)
if (!output.success) throw new Error(stdout)
if (!output.taskUrl.includes('/chat/fixture-task-1')) throw new Error(stdout)
if (!output.initializedSkillIds.includes('project-plan')) throw new Error(stdout)
if (!output.initializedSkillIds.includes('requirement-analysis')) throw new Error(stdout)
process.stdout.write('Playwright initialization Worker E2E verification passed\n')
