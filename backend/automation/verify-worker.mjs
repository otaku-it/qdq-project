import { mkdtemp, rm, writeFile } from 'node:fs/promises'
import { createServer } from 'node:http'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { spawn } from 'node:child_process'

const fixture = `<!doctype html><html><body><iframe src="/outer.html"></iframe></body></html>`
const outer = `<!doctype html><html><body><iframe src="/skills.html"></iframe></body></html>`
const skills = `<!doctype html><html><body>
<h1>内置技能配置</h1><input placeholder="搜索名称"><button id="upload">上传</button>
<div id="list"></div><dialog aria-label="批量上传技能"><h2>批量上传技能</h2>
<input id="files" type="file" multiple><button id="complete" disabled>完成</button></dialog>
<script>
const dialog=document.querySelector('dialog');const files=document.querySelector('#files');
document.querySelector('#upload').onclick=()=>dialog.showModal();
files.onchange=()=>document.querySelector('#complete').disabled=!files.files.length;
document.querySelector('#complete').onclick=()=>{for(const file of files.files){const item=document.createElement('div');item.textContent=file.name.replace(/\\.skill$/, '');document.querySelector('#list').append(item)}dialog.close()};
</script></body></html>`

const server = createServer((request, response) => {
  response.setHeader('content-type', 'text/html; charset=utf-8')
  response.end(request.url === '/outer.html' ? outer : request.url === '/skills.html' ? skills : fixture)
})
await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
const port = server.address().port
const directory = await mkdtemp(join(tmpdir(), 'zhiling-worker-test-'))
const skillFile = join(directory, 'project-plan.skill')
await writeFile(skillFile, 'worker verification package')

const child = spawn(process.execPath, [new URL('./upload-agent-skills.mjs', import.meta.url).pathname], {
  env: { ...process.env, DOUBAO_ADMIN_URL: `http://127.0.0.1:${port}`, DOUBAO_USER_DATA_DIR: join(directory, 'profile') },
  stdio: ['pipe', 'pipe', 'pipe'],
})
child.stdin.end(JSON.stringify({ tenantName: '测试租户', operatorName: '测试用户', skills: [{ id: 'project-plan', file: skillFile }] }))
let stdout = ''
let stderr = ''
child.stdout.on('data', (chunk) => { stdout += chunk })
child.stderr.on('data', (chunk) => { stderr += chunk })
const exitCode = await new Promise((resolve) => child.on('close', resolve))

server.close()
await rm(directory, { recursive: true, force: true })
if (exitCode !== 0) throw new Error(stderr || stdout || `worker exited ${exitCode}`)
const output = JSON.parse(stdout)
if (!output.success || !output.uploadedSkillIds.includes('project-plan')) throw new Error(stdout)

const delayedAuthorization = `<!doctype html><html><body><p>正在进入企业后台</p>
<script>setTimeout(() => location.href = '/oauth/delayed', 2200)</script></body></html>`
const authorizationPage = `<!doctype html><html><body><h1>扫码授权</h1><p>请使用飞书移动端扫描二维码</p></body></html>`
const delayedServer = createServer((request, response) => {
  response.setHeader('content-type', 'text/html; charset=utf-8')
  response.end(request.url === '/oauth/delayed' ? authorizationPage : delayedAuthorization)
})
await new Promise((resolve) => delayedServer.listen(0, '127.0.0.1', resolve))
const delayedPort = delayedServer.address().port
const delayedDirectory = await mkdtemp(join(tmpdir(), 'zhiling-delayed-auth-test-'))
const delayedSkillFile = join(delayedDirectory, 'project-plan.skill')
await writeFile(delayedSkillFile, 'delayed authorization package')
const delayedChild = spawn(process.execPath, [new URL('./upload-agent-skills.mjs', import.meta.url).pathname], {
  env: {
    ...process.env,
    DOUBAO_ADMIN_URL: `http://127.0.0.1:${delayedPort}/admin`,
    DOUBAO_USER_DATA_DIR: join(delayedDirectory, 'profile'),
    DOUBAO_AUTHORIZATION_WAIT_MS: '8000',
  },
  stdio: ['pipe', 'pipe', 'pipe'],
})
delayedChild.stdin.end(JSON.stringify({
  tenantName: '延迟授权租户',
  operatorName: '测试用户',
  skills: [{ id: 'project-plan', file: delayedSkillFile }],
}))
let delayedStdout = ''
let delayedStderr = ''
delayedChild.stdout.on('data', (chunk) => { delayedStdout += chunk })
delayedChild.stderr.on('data', (chunk) => { delayedStderr += chunk })
const delayedExitCode = await new Promise((resolve) => delayedChild.on('close', resolve))
delayedServer.close()
await rm(delayedDirectory, { recursive: true, force: true })
if (delayedExitCode !== 0) throw new Error(delayedStderr || delayedStdout || `delayed worker exited ${delayedExitCode}`)
const delayedOutput = JSON.parse(delayedStdout)
if (delayedOutput.success || !delayedOutput.requiresUserAction) throw new Error(delayedStdout)

const duplicateAuthorization = `<!doctype html><html><body><p>正在进入企业后台</p>
<script>window.open('/oauth/second', '_blank');location.href = '/oauth/first'</script></body></html>`
const duplicateServer = createServer((request, response) => {
  response.setHeader('content-type', 'text/html; charset=utf-8')
  response.end(request.url?.startsWith('/oauth/') ? authorizationPage : duplicateAuthorization)
})
await new Promise((resolve) => duplicateServer.listen(0, '127.0.0.1', resolve))
const duplicatePort = duplicateServer.address().port
const duplicateDirectory = await mkdtemp(join(tmpdir(), 'zhiling-duplicate-auth-test-'))
const duplicateSkillFile = join(duplicateDirectory, 'project-plan.skill')
await writeFile(duplicateSkillFile, 'duplicate authorization package')
const duplicateChild = spawn(process.execPath, [new URL('./upload-agent-skills.mjs', import.meta.url).pathname], {
  env: {
    ...process.env,
    DOUBAO_ADMIN_URL: `http://127.0.0.1:${duplicatePort}/admin`,
    DOUBAO_USER_DATA_DIR: join(duplicateDirectory, 'profile'),
    DOUBAO_AUTHORIZATION_WAIT_MS: '8000',
  },
  stdio: ['pipe', 'pipe', 'pipe'],
})
duplicateChild.stdin.end(JSON.stringify({
  tenantName: '重复授权租户',
  operatorName: '测试用户',
  skills: [{ id: 'project-plan', file: duplicateSkillFile }],
}))
let duplicateStdout = ''
let duplicateStderr = ''
duplicateChild.stdout.on('data', (chunk) => { duplicateStdout += chunk })
duplicateChild.stderr.on('data', (chunk) => { duplicateStderr += chunk })
const duplicateExitCode = await new Promise((resolve) => duplicateChild.on('close', resolve))
duplicateServer.close()
await rm(duplicateDirectory, { recursive: true, force: true })
if (duplicateExitCode !== 0) throw new Error(duplicateStderr || duplicateStdout || `duplicate worker exited ${duplicateExitCode}`)
const duplicateOutput = JSON.parse(duplicateStdout)
if (duplicateOutput.success || !duplicateOutput.requiresUserAction || !duplicateOutput.message.includes('重复授权页')) {
  throw new Error(duplicateStdout)
}

process.stdout.write('Playwright Worker upload, delayed authorization, and single-tab verification passed\n')
