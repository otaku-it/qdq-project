import { chromium } from 'playwright-core'
import { spawn } from 'node:child_process'
import { existsSync } from 'node:fs'
import { resolve } from 'node:path'

const ADMIN_URL = process.env.DOUBAO_ADMIN_URL || 'https://admin.doubao.com/ask/doubao/builtin-skill'
const CDP_ENDPOINT = process.env.DOUBAO_CDP_ENDPOINT
const USER_DATA_DIR = process.env.DOUBAO_USER_DATA_DIR || resolve(process.cwd(), '.doubao-profile')
const AUTHORIZATION_WAIT_MS = Number(process.env.DOUBAO_AUTHORIZATION_WAIT_MS || 45_000)
const configuredTimeout = (value, fallback) => {
  const parsed = Number(value)
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback
}
const AUTO_CDP_PORT = configuredTimeout(process.env.DOUBAO_AUTO_CDP_PORT, 9222)
const AUTO_CDP_ENDPOINT = `http://127.0.0.1:${AUTO_CDP_PORT}`
const SHOULD_AUTO_CDP = !CDP_ENDPOINT && !/^https?:\/\/127\.0\.0\.1(?::|\/)/i.test(ADMIN_URL)
const BROWSER_LAUNCH_TIMEOUT_MS = configuredTimeout(process.env.DOUBAO_BROWSER_LAUNCH_TIMEOUT_MS, 20_000)
const NAVIGATION_TIMEOUT_MS = configuredTimeout(process.env.DOUBAO_NAVIGATION_TIMEOUT_MS, 30_000)
const CHROME_EXECUTABLE_PATH = process.env.DOUBAO_CHROME_EXECUTABLE_PATH

function resolveChromeExecutable() {
  if (CHROME_EXECUTABLE_PATH) return CHROME_EXECUTABLE_PATH
  const candidates = process.platform === 'darwin'
    ? ['/Applications/Google Chrome.app/Contents/MacOS/Google Chrome']
    : process.platform === 'win32'
      ? [
          `${process.env.PROGRAMFILES || ''}\\Google\\Chrome\\Application\\chrome.exe`,
          `${process.env['PROGRAMFILES(X86)'] || ''}\\Google\\Chrome\\Application\\chrome.exe`,
          `${process.env.LOCALAPPDATA || ''}\\Google\\Chrome\\Application\\chrome.exe`,
        ]
      : ['/usr/bin/google-chrome', '/usr/bin/google-chrome-stable', '/usr/bin/chromium', '/usr/bin/chromium-browser']
  return candidates.find((candidate) => candidate && existsSync(candidate))
}

async function connectToCdp(endpoint) {
  await ensureCdpTarget(endpoint)
  const browser = await chromium.connectOverCDP(endpoint, { timeout: BROWSER_LAUNCH_TIMEOUT_MS })
  const context = browser.contexts()[0]
  if (!context) throw new Error('CDP 浏览器没有可用上下文')
  context.setDefaultTimeout(NAVIGATION_TIMEOUT_MS)
  context.setDefaultNavigationTimeout(NAVIGATION_TIMEOUT_MS)
  return { browser, context, ownsContext: false }
}

async function launchStandaloneCdpBrowser() {
  const executablePath = resolveChromeExecutable()
  if (!executablePath) {
    throw new Error('未找到 Google Chrome，请通过 DOUBAO_CHROME_EXECUTABLE_PATH 配置浏览器路径')
  }

  // Playwright 自带的持久化启动会使用 remote-debugging-pipe。不能再给同一进程追加
  // remote-debugging-port，否则 Chrome 会同时启用两套调试传输，真实豆包 iframe 可能
  // 出现高 CPU 和永久 loading。这里独立启动 Chrome，再让 Playwright 只通过 CDP 连接。
  const chromeProcess = spawn(executablePath, [
    `--remote-debugging-port=${AUTO_CDP_PORT}`,
    '--remote-debugging-address=127.0.0.1',
    `--user-data-dir=${USER_DATA_DIR}`,
    '--no-first-run',
    '--no-default-browser-check',
    ADMIN_URL,
  ], {
    detached: true,
    stdio: 'ignore',
  })
  chromeProcess.unref()

  const deadline = Date.now() + BROWSER_LAUNCH_TIMEOUT_MS
  let lastFailure
  while (Date.now() < deadline) {
    if (chromeProcess.exitCode !== null) {
      throw new Error(`Chrome 启动后立即退出 (${chromeProcess.exitCode})，请确认浏览器用户目录未被占用`)
    }
    try {
      const handle = await connectToCdp(AUTO_CDP_ENDPOINT)
      return { ...handle, ownsBrowserProcess: true }
    } catch (error) {
      lastFailure = error
      await new Promise((resolvePromise) => setTimeout(resolvePromise, 250))
    }
  }
  const detail = lastFailure instanceof Error ? `：${lastFailure.message}` : ''
  throw new Error(`Chrome 启动超时${detail}`)
}

async function readPayload() {
  const chunks = []
  for await (const chunk of process.stdin) chunks.push(chunk)
  return JSON.parse(Buffer.concat(chunks).toString('utf8'))
}

async function ensureCdpTarget(endpoint) {
  const endpointUrl = new URL(endpoint)
  if (!['http:', 'https:'].includes(endpointUrl.protocol)) return
  const origin = endpointUrl.origin
  const response = await fetch(`${origin}/json/list`, { signal: AbortSignal.timeout(3_000) })
  if (!response.ok) throw new Error(`CDP 状态检查失败 (${response.status})`)
  const targets = await response.json()
  if (Array.isArray(targets) && targets.some((target) => target.type === 'page')) return

  const createResponse = await fetch(`${origin}/json/new?${encodeURIComponent(ADMIN_URL)}`, {
    method: 'PUT',
    signal: AbortSignal.timeout(5_000),
  })
  if (!createResponse.ok) throw new Error(`CDP 无可用页面且自动新建页面失败 (${createResponse.status})`)
}

async function openBrowser() {
  let cdpFailure
  // 继续任务时优先复用上一次等待扫码的浏览器。显式 CDP 地址优先；本地自动启动
  // 的受控 Chrome 使用固定端口，这样 Worker 退出后浏览器仍可保留给用户扫码。
  const cdpEndpoint = CDP_ENDPOINT || (SHOULD_AUTO_CDP ? AUTO_CDP_ENDPOINT : null)
  try {
    if (cdpEndpoint) {
      return await connectToCdp(cdpEndpoint)
    }
  } catch (error) {
    cdpFailure = error
  }
  if (CDP_ENDPOINT) {
    try {
      // Chrome 窗口全部关闭时进程和 9222 仍可能存活，但没有 page target；先自动恢复一个页面。
      return await connectToCdp(CDP_ENDPOINT)
    } catch (error) {
      cdpFailure = error
    }
  }
  if (SHOULD_AUTO_CDP) {
    try {
      return await launchStandaloneCdpBrowser()
    } catch (error) {
      const cdpMessage = cdpFailure instanceof Error ? `CDP 连接失败：${cdpFailure.message}；` : ''
      const launchMessage = error instanceof Error ? error.message : String(error)
      throw new Error(`${cdpMessage}浏览器自动启动失败：${launchMessage}`)
    }
  }
  try {
    const launchOptions = {
      headless: false,
      timeout: BROWSER_LAUNCH_TIMEOUT_MS,
      args: ['--no-first-run', '--no-default-browser-check'],
    }
    if (CHROME_EXECUTABLE_PATH) launchOptions.executablePath = CHROME_EXECUTABLE_PATH
    else launchOptions.channel = 'chrome'
    const context = await chromium.launchPersistentContext(USER_DATA_DIR, launchOptions)
    context.setDefaultTimeout(NAVIGATION_TIMEOUT_MS)
    context.setDefaultNavigationTimeout(NAVIGATION_TIMEOUT_MS)
    return { browser: null, context, ownsContext: true }
  } catch (error) {
    const cdpMessage = cdpFailure instanceof Error ? `CDP 连接失败：${cdpFailure.message}；` : ''
    const launchMessage = error instanceof Error ? error.message : String(error)
    throw new Error(`${cdpMessage}浏览器自动启动失败：${launchMessage}`)
  }
}

function isFeishuAuthorizationUrl(value) {
  try {
    const url = new URL(value)
    return url.hostname === 'accounts.feishu.cn'
      || url.hostname === 'passport.feishu.cn'
      || (url.hostname === 'www.doubao.com' && url.pathname.startsWith('/auth/callback'))
  } catch {
    return false
  }
}

async function isAuthorizationPage(page) {
  if (page.isClosed()) return false
  if (isFeishuAuthorizationUrl(page.url())) return true
  const loginPrompt = page.getByText(/扫码授权|请使用飞书移动端扫描二维码|登录飞书账号/).first()
  return loginPrompt.isVisible().catch(() => false)
}

async function normalizeAuthorizationPages(context) {
  const authorizationPages = []
  for (const page of context.pages()) {
    if (await isAuthorizationPage(page)) authorizationPages.push(page)
  }
  if (authorizationPages.length === 0) return { page: null, duplicatesClosed: 0 }

  // context.pages() 按创建顺序返回，保留最新授权页，关闭旧页，避免每次重试累积二维码标签页。
  const activePage = authorizationPages.at(-1)
  const duplicatePages = authorizationPages.slice(0, -1)
  for (const duplicatePage of duplicatePages) {
    await duplicatePage.close().catch(() => {})
  }
  return { page: activePage, duplicatesClosed: duplicatePages.length }
}

async function isExpiredAuthorizationPage(page) {
  const expiredPrompt = page.getByText(/二维码.{0,8}(已过期|已失效)|授权.{0,8}(已过期|已失效)|重新获取二维码|刷新二维码/).first()
  return expiredPrompt.isVisible().catch(() => false)
}

async function findAdminPage(context) {
  const pages = context.pages().filter((candidate) => (
    candidate.url().includes('admin.doubao.com/ask/doubao/builtin-skill')
      || candidate.url() === ADMIN_URL
  ))
  if (pages.length === 0) return null
  const activePage = pages.at(-1)
  for (const duplicatePage of pages.slice(0, -1)) {
    await duplicatePage.close().catch(() => {})
  }
  return activePage
}

async function hasListFrame(page) {
  for (const frame of page.frames()) {
    try {
      if ((await frame.getByRole('heading', { name: '内置技能配置' }).count()) > 0) return true
    } catch (error) {
      if (!(error instanceof Error) || !/Frame was detached|Target page, context or browser has been closed/.test(error.message)) {
        throw error
      }
    }
  }
  return false
}

async function waitForAdminOrAuthorization(context, page, timeoutMs = AUTHORIZATION_WAIT_MS) {
  const deadline = Date.now() + timeoutMs
  const reloadDeadline = Date.now() + 8_000
  let reloaded = false
  let duplicatesClosed = 0
  while (Date.now() < deadline) {
    const authorization = await normalizeAuthorizationPages(context)
    duplicatesClosed += authorization.duplicatesClosed
    if (authorization.page) {
      return { type: 'authorization', page: authorization.page, duplicatesClosed }
    }
    if (!page.isClosed() && await hasListFrame(page)) {
      return { type: 'ready', page, duplicatesClosed }
    }
    // 豆包后台偶发停留在首屏 loading（旧标签页缓存或网络请求中断）。只对同一后台页
    // 自动刷新一次，避免用户看到永久转圈，也不重复创建授权窗口。
    if (!reloaded && Date.now() >= reloadDeadline && !page.isClosed()) {
      reloaded = true
      await page.reload({ waitUntil: 'domcontentloaded', timeout: NAVIGATION_TIMEOUT_MS }).catch(() => {})
    }
    await new Promise((resolve) => setTimeout(resolve, 250))
  }

  const authorization = await normalizeAuthorizationPages(context)
  if (authorization.page) {
    return {
      type: 'authorization',
      page: authorization.page,
      duplicatesClosed: duplicatesClosed + authorization.duplicatesClosed,
    }
  }
  throw new Error('豆包企业后台页面结构未就绪')
}

async function findFrame(page, predicate, timeoutMs = 20_000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    for (const frame of page.frames()) {
      try {
        if (await predicate(frame)) return frame
      } catch (error) {
        // 豆包在上传完成、关闭弹窗或搜索时会重建内层 iframe，继续使用新 Frame 即可。
        if (!(error instanceof Error) || !error.message.includes('Frame was detached')) throw error
      }
    }
    await page.waitForTimeout(250)
  }
  throw new Error('豆包企业后台页面结构未就绪')
}

async function findFrameOptional(page, predicate, timeoutMs = 3_000) {
  try {
    return await findFrame(page, predicate, timeoutMs)
  } catch (error) {
    if (error instanceof Error && /Target page, context or browser has been closed/.test(error.message)) return null
    return null
  }
}

async function dismissUploadSuccess(page) {
  const successFrame = await findFrameOptional(page, async (frame) => (
    await frame.getByRole('button', { name: '我知道了', exact: true }).count()
  ) > 0)
  if (!successFrame) return false
  await successFrame.getByRole('button', { name: '我知道了', exact: true }).click()
    .catch((error) => {
      if (!(error instanceof Error) || !/Frame was detached|Target page, context or browser has been closed/.test(error.message)) throw error
    })
  await page.waitForTimeout(1_000).catch((error) => {
    if (!(error instanceof Error) || !error.message.includes('Target page, context or browser has been closed')) throw error
  })
  return true
}

async function getListFrame(page) {
  return findFrame(page, async (frame) => (
    await frame.getByRole('heading', { name: '内置技能配置' }).count()
  ) > 0)
}

async function skillExists(page, skillId, { reuseCurrentSearch = false } = {}) {
  for (let attempt = 0; attempt < 3; attempt += 1) {
    try {
      const listFrame = await getListFrame(page)
      const search = listFrame.getByPlaceholder('搜索名称')
      const currentSearch = reuseCurrentSearch ? await search.inputValue().catch(() => '') : ''
      if (currentSearch !== skillId) {
        await search.fill(skillId)
        await search.press('Enter')
        await page.waitForTimeout(1_000)
      } else {
        // 上传弹窗关闭后筛选词通常仍保留，等待列表刷新即可，无需再次触发相同搜索。
        await page.waitForTimeout(500)
      }
      const refreshedListFrame = await getListFrame(page)
      const exactSkill = refreshedListFrame.getByText(skillId, { exact: true }).first()
      try {
        await exactSkill.waitFor({ state: 'visible', timeout: 5_000 })
        return true
      } catch (error) {
        if (error instanceof Error && error.message.includes('Frame was detached')) throw error
        return false
      }
    } catch (error) {
      if (!(error instanceof Error) || !error.message.includes('Frame was detached') || attempt === 2) throw error
      await page.waitForTimeout(500)
    }
  }
  return false
}

async function uploadMissing(page, skills) {
  const files = skills.map((skill) => skill.file)
  const uploadFrame = await findFrame(page, async (frame) => (
    await frame.getByRole('button', { name: '上传', exact: true }).count()
  ) > 0)

  await uploadFrame.getByRole('button', { name: '上传', exact: true }).click({ force: true })
  const dialogFrame = await findFrame(page, async (frame) => (
    await frame.getByRole('dialog', { name: '批量上传技能' }).count()
  ) > 0)
  const dialog = dialogFrame.getByRole('dialog', { name: '批量上传技能' })
  await dialog.locator('input[type="file"]').setInputFiles(files)

  const complete = dialog.getByRole('button', { name: '完成', exact: true })
  await complete.waitFor({ state: 'visible', timeout: 20_000 })
  const validationError = dialog.getByText(/失败|错误|不符合|无法解析/).first()
  const validationDeadline = Date.now() + 20_000
  while (!(await complete.isEnabled()) && Date.now() < validationDeadline) {
    if (await validationError.isVisible().catch(() => false)) {
      throw new Error(await validationError.innerText())
    }
    await page.waitForTimeout(250)
  }
  if (!(await complete.isEnabled())) throw new Error('技能包校验未通过，“完成”按钮不可用')
  await complete.click()
  await dialog.waitFor({ state: 'hidden', timeout: 60_000 }).catch((error) => {
    if (!(error instanceof Error) || !error.message.includes('Frame was detached')) throw error
  })
  await dismissUploadSuccess(page)
}

async function upload(page, skills) {
  // 上一次 Worker 即使在成功弹窗处中断，也可以从这里恢复，不重复上传同版本 Skill。
  await dismissUploadSuccess(page)
  const existingSkillIds = []
  for (const skill of skills) {
    if (await skillExists(page, skill.id)) existingSkillIds.push(skill.id)
  }
  const missingSkills = skills.filter((skill) => !existingSkillIds.includes(skill.id))
  if (missingSkills.length > 0) await uploadMissing(page, missingSkills)

  // 上传操作不会删除预先确认存在的 Skill，只校验本次新上传项即可避免重复搜索。
  for (let index = 0; index < missingSkills.length; index += 1) {
    const skill = missingSkills[index]
    if (!(await skillExists(page, skill.id, { reuseCurrentSearch: index === missingSkills.length - 1 }))) {
      throw new Error(`上传结束后未在企业技能列表中找到 ${skill.id}`)
    }
  }
  return { existingCount: existingSkillIds.length, uploadedCount: missingSkills.length }
}

let browserHandle
let workerResult
let workerExitCode = 0
try {
  const payload = await readPayload()
  if (!Array.isArray(payload.skills) || payload.skills.length === 0) {
    throw new Error('没有待上传的 Agent Skill')
  }
  browserHandle = await openBrowser()
  let authorization = await normalizeAuthorizationPages(browserHandle.context)

  // 飞书二维码有平台有效期。过期页关闭后只触发一次新授权，未过期页直接复用，不再打开后台。
  if (authorization.page && await isExpiredAuthorizationPage(authorization.page)) {
    await authorization.page.close().catch(() => {})
    authorization = { page: null, duplicatesClosed: authorization.duplicatesClosed }
  }

  if (authorization.page) {
    await authorization.page.bringToFront()
    workerResult = {
      success: false,
      requiresUserAction: true,
      message: authorization.duplicatesClosed > 0
        ? `已关闭 ${authorization.duplicatesClosed} 个重复授权页，请在当前唯一飞书授权页扫码后点击“已完成扫码登录”`
        : '已复用当前飞书授权页，请扫码后点击“已完成扫码登录”',
      uploadedSkillIds: [],
    }
  } else {
    let page = await findAdminPage(browserHandle.context)
    if (!page) {
      page = await browserHandle.context.newPage()
      await page.goto(ADMIN_URL, { waitUntil: 'domcontentloaded', timeout: NAVIGATION_TIMEOUT_MS })
    }
    const state = await waitForAdminOrAuthorization(browserHandle.context, page)
    if (state.type === 'authorization') {
      await state.page.bringToFront()
      workerResult = {
        success: false,
        requiresUserAction: true,
        message: state.duplicatesClosed > 0
          ? `已关闭 ${state.duplicatesClosed} 个重复授权页，请在当前唯一飞书授权页扫码后点击“已完成扫码登录”`
          : '飞书授权页已打开，请扫码后点击“已完成扫码登录”',
        uploadedSkillIds: [],
      }
    } else {
      const counts = await upload(state.page, payload.skills)
      const detail = counts.uploadedCount === 0
        ? `企业列表已存在 ${counts.existingCount} 个 Skill，无需重复上传`
        : `新上传 ${counts.uploadedCount} 个、已存在 ${counts.existingCount} 个`
      workerResult = {
        success: true,
        requiresUserAction: false,
        message: `已通过 Playwright 验证 ${payload.skills.length} 个企业 Skill（${detail}）`,
        uploadedSkillIds: payload.skills.map((skill) => skill.id),
      }
    }
  }
} catch (error) {
  const errorMessage = error instanceof Error ? error.message : String(error)
  const browserUnavailable = /Target page, context or browser has been closed|ECONNREFUSED|connectOverCDP|Browser\.setDownloadBehavior|浏览器自动启动失败|企业后台页面结构未就绪/.test(errorMessage)
  workerResult = {
    success: false,
    requiresUserAction: browserUnavailable,
    message: browserUnavailable
      ? (errorMessage.includes('页面结构未就绪')
        ? '豆包企业后台页面加载超时，浏览器窗口已保留；请刷新页面后点击“继续”重试'
        : '受控浏览器已关闭或会话已失效，已尝试重新打开；请完成飞书登录后继续')
      : errorMessage,
    uploadedSkillIds: [],
  }
  workerExitCode = browserUnavailable ? 0 : 1
} finally {
  // 等待用户扫码时不能关闭受控浏览器，否则前端提示用户扫码但窗口已经消失。
  // 下一次“继续”会通过自动 CDP 端口复用该浏览器；成功或失败则正常清理上下文。
  if (browserHandle?.ownsContext && !workerResult?.requiresUserAction) {
    await browserHandle.context.close().catch(() => {})
  }
}

// CDP 连接会保持 Node 事件循环存活。先完整写出 JSON，再显式退出，只断开 Worker，不关闭用户 Chrome。
await new Promise((resolve, reject) => {
  process.stdout.write(JSON.stringify(workerResult), (error) => error ? reject(error) : resolve())
})
process.exit(workerExitCode)
