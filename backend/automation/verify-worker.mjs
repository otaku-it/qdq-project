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
process.stdout.write('Playwright Worker E2E verification passed\n')
