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
<aside id="conversations"></aside>
<div id="conversation-menu" role="menu" hidden><div id="pin-task" role="menuitem">置顶</div></div>
<script>
const composer = document.querySelector('#composer');
const picker = document.querySelector('#picker');
const messages = document.querySelector('#messages');
const conversations = document.querySelector('#conversations');
const conversationMenu = document.querySelector('#conversation-menu');
const records = [];
let taskCounter = 0;
let selectedConversation = null;
const resumeFixture = new URLSearchParams(location.search).has('resume');
function addConversation(taskId, skillId) {
  const conversation = document.createElement('a');
  conversation.href = '/chat/' + taskId + (resumeFixture ? '?resume=1' : '');
  conversation.textContent = '初始化' + skillId + '能力';
  const menuTrigger = document.createElement('button');
  menuTrigger.setAttribute('aria-haspopup', 'menu');
  menuTrigger.textContent = '更多';
  menuTrigger.onclick = (menuEvent) => {
    menuEvent.preventDefault();
    selectedConversation = conversation;
    conversationMenu.hidden = false;
  };
  conversation.append(menuTrigger);
  conversations.append(conversation);
}
document.querySelector('#new-task').onclick = () => {
  composer.replaceChildren();
  // 模拟豆包切换会话时短暂残留上一会话正文，Worker 发送前必须清理它。
  const stalePrompt = document.createElement('span');
  stalePrompt.textContent = '残留提示词';
  composer.append(stalePrompt);
  picker.hidden = true;
  // 豆包切换新任务后仍可能缓存上个会话的文本节点。
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
  taskCounter += 1;
  const attachedSkills = Array.from(composer.querySelectorAll('button'), (button) => button.textContent);
  const prompt = Array.from(composer.childNodes)
    .filter((node) => node.nodeType === Node.TEXT_NODE)
    .map((node) => node.textContent)
    .join('');
  records.push({ attachedSkills, prompt });
  const message = document.createElement('div');
  // 模拟豆包真实渲染：Skill 标签和正文相邻时，斜杠前空白会被折叠掉。
  message.textContent = composer.textContent.replace(' / ', ' /');
  messages.append(message);
  composer.replaceChildren();
  const taskId = 'fixture-task-' + taskCounter;
  addConversation(taskId, attachedSkills[0]);
  const recordsQuery = '?records=' + encodeURIComponent(JSON.stringify(records));
  // 豆包会先给新任务分配 local 临时 ID，服务端落盘后才替换为真实会话 ID。
  history.pushState({}, '', '/chat/local_' + taskCounter + recordsQuery);
  setTimeout(() => {
    history.replaceState({}, '', '/chat/' + taskId + recordsQuery);
  }, 80);
};
document.querySelector('#pin-task').onclick = () => {
  selectedConversation.dataset.pinned = 'true';
  records[Number(selectedConversation.href.match(/fixture-task-(\\d+)/)[1]) - 1].pinned = true;
  conversationMenu.hidden = true;
  history.replaceState({}, '', location.pathname + '?records=' + encodeURIComponent(JSON.stringify(records)));
};
if (resumeFixture) {
  localStorage.setItem(
    'zhiling-launcher:initialization:fixture-resume-job-1',
    JSON.stringify({ 'project-plan': location.origin + '/chat/local_legacy_project-plan' }),
  );
  taskCounter = 1;
  records.push({
    attachedSkills: ['project-plan'],
    prompt: '[智灵启动器任务 fixture-resume-job-1 / project-plan]',
  });
  const existingMessage = document.createElement('div');
  existingMessage.textContent = '[智灵启动器任务 fixture-resume-job-1 /project-plan]';
  messages.append(existingMessage);
  addConversation('fixture-task-1', 'project-plan');
  const keepRoot = new URLSearchParams(location.search).has('root');
  history.replaceState({}, '', (keepRoot ? '/chat' : '/chat/fixture-task-1') + '?records=' + encodeURIComponent(JSON.stringify(records)));
}
if (new URLSearchParams(location.search).has('history')) {
  // 与本次所选 Skill 同名的历史会话不能被当成本次幂等结果复用。
  addConversation('fixture-history', 'requirement-analysis');
}
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
    DOUBAO_WORK_URL: `http://127.0.0.1:${port}/chat/skills?history=1`,
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
const taskUrl = new URL(output.taskUrl)
if (!taskUrl.pathname.endsWith('/chat/fixture-task-2')) throw new Error(stdout)
const taskRecords = JSON.parse(taskUrl.searchParams.get('records') ?? '[]')
if (taskRecords.length !== 2) throw new Error(stdout)
if (!taskRecords.every((record) => record.pinned === true)) throw new Error(stdout)
if (taskRecords[0].attachedSkills.join(',') !== 'project-plan') throw new Error(stdout)
if (taskRecords[1].attachedSkills.join(',') !== 'requirement-analysis') throw new Error(stdout)
if (taskRecords[0].prompt !== '') throw new Error(stdout)
if (taskRecords[1].prompt !== '') throw new Error(stdout)
if (!output.initializedSkillIds.includes('project-plan')) throw new Error(stdout)
if (!output.initializedSkillIds.includes('requirement-analysis')) throw new Error(stdout)
if (!/已创建并置顶 2 个豆包工作任务/.test(output.message)) throw new Error(stdout)
process.stdout.write('Playwright per-Skill initialization Worker E2E verification passed\n')

const resumeDirectory = await mkdtemp(join(tmpdir(), 'zhiling-initialization-resume-test-'))
const resumeChild = spawn(process.execPath, [new URL('./initialize-doubao-agent.mjs', import.meta.url).pathname], {
  env: {
    ...process.env,
    DOUBAO_WORK_URL: `http://127.0.0.1:${port}/chat/fixture-existing?resume=1`,
    DOUBAO_USER_DATA_DIR: join(resumeDirectory, 'profile'),
  },
  stdio: ['pipe', 'pipe', 'pipe'],
})
resumeChild.stdin.end(JSON.stringify({
  tenantName: '测试租户',
  operatorName: '普通用户',
  larkUser: '普通用户',
  idempotencyKey: 'fixture-resume-job-1',
  skills: ['project-plan', 'requirement-analysis'],
}))

let resumeStdout = ''
let resumeStderr = ''
resumeChild.stdout.on('data', (chunk) => { resumeStdout += chunk })
resumeChild.stderr.on('data', (chunk) => { resumeStderr += chunk })
const resumeExitCode = await new Promise((resolve) => resumeChild.on('close', resolve))
await rm(resumeDirectory, { recursive: true, force: true })
if (resumeExitCode !== 0) throw new Error(resumeStderr || resumeStdout || `resume worker exited ${resumeExitCode}`)
const resumeOutput = JSON.parse(resumeStdout)
if (!resumeOutput.success) throw new Error(resumeStdout)
const resumeRecords = JSON.parse(new URL(resumeOutput.taskUrl).searchParams.get('records') ?? '[]')
if (resumeRecords.length !== 2) throw new Error(resumeStdout)
if (!resumeRecords.every((record) => record.pinned === true)) throw new Error(resumeStdout)
if (resumeRecords.filter((record) => record.attachedSkills.includes('project-plan')).length !== 1) throw new Error(resumeStdout)
if (resumeRecords.filter((record) => record.attachedSkills.includes('requirement-analysis')).length !== 1) throw new Error(resumeStdout)
process.stdout.write('Playwright partial-failure retry verification passed\n')

const sidebarResumeDirectory = await mkdtemp(join(tmpdir(), 'zhiling-initialization-sidebar-resume-test-'))
const sidebarResumeChild = spawn(process.execPath, [new URL('./initialize-doubao-agent.mjs', import.meta.url).pathname], {
  env: {
    ...process.env,
    DOUBAO_WORK_URL: `http://127.0.0.1:${port}/chat/fixture-existing?resume=1&root=1`,
    DOUBAO_USER_DATA_DIR: join(sidebarResumeDirectory, 'profile'),
  },
  stdio: ['pipe', 'pipe', 'pipe'],
})
sidebarResumeChild.stdin.end(JSON.stringify({
  tenantName: '测试租户',
  operatorName: '普通用户',
  larkUser: '普通用户',
  idempotencyKey: 'fixture-resume-job-1',
  skills: ['project-plan', 'requirement-analysis'],
}))

let sidebarResumeStdout = ''
let sidebarResumeStderr = ''
sidebarResumeChild.stdout.on('data', (chunk) => { sidebarResumeStdout += chunk })
sidebarResumeChild.stderr.on('data', (chunk) => { sidebarResumeStderr += chunk })
const sidebarResumeExitCode = await new Promise((resolve) => sidebarResumeChild.on('close', resolve))
await rm(sidebarResumeDirectory, { recursive: true, force: true })
if (sidebarResumeExitCode !== 0) throw new Error(sidebarResumeStderr || sidebarResumeStdout || `sidebar resume worker exited ${sidebarResumeExitCode}`)
const sidebarResumeOutput = JSON.parse(sidebarResumeStdout)
if (!sidebarResumeOutput.success) throw new Error(sidebarResumeStdout)
const sidebarResumeRecords = JSON.parse(new URL(sidebarResumeOutput.taskUrl).searchParams.get('records') ?? '[]')
if (sidebarResumeRecords.length !== 2) throw new Error(sidebarResumeStdout)
if (sidebarResumeRecords.filter((record) => record.attachedSkills.includes('project-plan')).length !== 1) throw new Error(sidebarResumeStdout)
if (sidebarResumeRecords.filter((record) => record.attachedSkills.includes('requirement-analysis')).length !== 1) throw new Error(sidebarResumeStdout)
if (!sidebarResumeRecords.every((record) => record.pinned === true)) throw new Error(sidebarResumeStdout)
process.stdout.write('Playwright sidebar recovery verification passed\n')

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
