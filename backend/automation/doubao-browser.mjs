import { chromium } from 'playwright-core'
import { spawn } from 'node:child_process'
import { existsSync } from 'node:fs'
import { mkdir, open, unlink, stat, readFile, readlink, rename } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'

const configuredTimeout = (value, fallback) => {
  const parsed = Number(value)
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback
}

const BROWSER_LAUNCH_TIMEOUT_MS = configuredTimeout(process.env.DOUBAO_BROWSER_LAUNCH_TIMEOUT_MS, 20_000)
const NAVIGATION_TIMEOUT_MS = configuredTimeout(process.env.DOUBAO_NAVIGATION_TIMEOUT_MS, 30_000)
const AUTO_CDP_PORT = configuredTimeout(process.env.DOUBAO_AUTO_CDP_PORT, 9222)
const BROWSER_LOCK_TIMEOUT_MS = configuredTimeout(process.env.DOUBAO_BROWSER_LOCK_TIMEOUT_MS, 180_000)
const BROWSER_LOCK_STALE_MS = configuredTimeout(process.env.DOUBAO_BROWSER_LOCK_STALE_MS, 2 * 60_000)

/**
 * 上传和初始化由两个独立 Node 进程执行，单纯的进程内互斥无法阻止它们同时操作同一
 * Chrome。使用用户目录旁的原子锁文件串行化真实浏览器操作，避免重复标签和渲染线程
 * 被两个 Playwright 会话同时驱动。锁在 Worker 结束时释放；异常退出留下的旧锁会过期清理。
 */
async function acquireBrowserLock(userDataDir) {
  const lockPath = resolve(dirname(userDataDir), '.doubao-browser.lock')
  await mkdir(dirname(lockPath), { recursive: true })
  const deadline = Date.now() + BROWSER_LOCK_TIMEOUT_MS
  let handle
  while (Date.now() < deadline) {
    try {
      handle = await open(lockPath, 'wx')
      await handle.writeFile(JSON.stringify({ pid: process.pid, createdAt: new Date().toISOString() }))
      return async () => {
        await handle?.close().catch(() => {})
        await unlink(lockPath).catch(() => {})
      }
    } catch (error) {
      if (error?.code !== 'EEXIST') throw error
      try {
        const info = await stat(lockPath)
        let ownerAlive = true
        try {
          const owner = JSON.parse(await readFile(lockPath, 'utf8'))
          if (Number.isInteger(owner.pid) && owner.pid !== process.pid) {
            try { process.kill(owner.pid, 0) } catch { ownerAlive = false }
          }
        } catch { ownerAlive = false }
        if (!ownerAlive || Date.now() - info.mtimeMs > BROWSER_LOCK_STALE_MS) await unlink(lockPath).catch(() => {})
      } catch {
        // 锁可能刚好被持有者释放，下一轮直接重试。
      }
      await new Promise((resolveDelay) => setTimeout(resolveDelay, 250))
    }
  }
  throw new Error('豆包浏览器正被另一个任务占用，请稍后重试')
}

/** Chrome 异常退出后可能留下 SingletonLock，导致下一次启动误判为已有会话。 */
async function clearStaleChromeSingletonFiles(userDataDir) {
  const profile = resolve(userDataDir)
  const lockPath = resolve(profile, 'SingletonLock')
  let lockTarget
  try { lockTarget = await readlink(lockPath) } catch { return false }
  const pidMatch = String(lockTarget).match(/-(\d+)$/)
  if (pidMatch) {
    try { process.kill(Number(pidMatch[1]), 0); return false } catch { /* 进程已不存在 */ }
  }
  const stamp = Date.now()
  for (const name of ['SingletonLock', 'SingletonCookie', 'SingletonSocket']) {
    const source = resolve(profile, name)
    try { await rename(source, `${source}.stale-${stamp}`) } catch { /* 文件可能已被 Chrome 清理 */ }
  }
  return true
}

function isLocalUrl(value) {
  try {
    const url = new URL(value)
    return ['localhost', '127.0.0.1', '::1'].includes(url.hostname)
  } catch {
    return false
  }
}

function resolveChromeExecutable() {
  if (process.env.DOUBAO_CHROME_EXECUTABLE_PATH) return process.env.DOUBAO_CHROME_EXECUTABLE_PATH
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

async function cdpServerExists(endpoint) {
  try {
    const origin = new URL(endpoint).origin
    const response = await fetch(`${origin}/json/version`, { signal: AbortSignal.timeout(3_000) })
    return response.ok
  } catch {
    return false
  }
}

async function ensureCdpTarget(endpoint, targetUrl) {
  const origin = new URL(endpoint).origin
  const response = await fetch(`${origin}/json/list`, { signal: AbortSignal.timeout(3_000) })
  if (!response.ok) throw new Error(`CDP 状态检查失败 (${response.status})`)
  const targets = await response.json()
  if (Array.isArray(targets) && targets.some((target) => target.type === 'page')) return

  const createResponse = await fetch(`${origin}/json/new?${encodeURIComponent(targetUrl)}`, {
    method: 'PUT',
    signal: AbortSignal.timeout(5_000),
  })
  if (!createResponse.ok) throw new Error(`CDP 无可用页面且自动新建页面失败 (${createResponse.status})`)
}

async function connectToCdp(endpoint, targetUrl, { attempts = 5 } = {}) {
  let lastFailure
  // 页面跳转和扫码回调时 Chrome Browser 域偶发短暂不可用。先重试连接，避免把已有
  // 受控浏览器误判为失效后再启动第二个实例。
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    try {
      await ensureCdpTarget(endpoint, targetUrl)
      const browser = await Promise.race([
        chromium.connectOverCDP(endpoint, { timeout: BROWSER_LAUNCH_TIMEOUT_MS }),
        new Promise((_, reject) => setTimeout(() => reject(new Error('CDP 浏览器连接超时')), BROWSER_LAUNCH_TIMEOUT_MS)),
      ])
      const context = browser.contexts()[0]
      if (!context) throw new Error('CDP 浏览器没有可用上下文')
      context.setDefaultTimeout(NAVIGATION_TIMEOUT_MS)
      context.setDefaultNavigationTimeout(NAVIGATION_TIMEOUT_MS)
      return { browser, context, ownsContext: false, preserveOnUserAction: true }
    } catch (error) {
      lastFailure = error
      if (attempt < attempts - 1) await new Promise((resolve) => setTimeout(resolve, 500))
    }
  }
  throw lastFailure instanceof Error ? lastFailure : new Error('CDP 浏览器连接失败')
}

/** 页面 renderer 卡死时，Playwright 连接可能无法建立；通过 CDP HTTP 关闭旧 target，
 * 再创建一个干净页面，保留 Chrome 用户目录中的登录态和 Cookie。 */
async function resetCdpTargets(endpoint, targetUrl) {
  const origin = new URL(endpoint).origin
  try {
    const targets = await (await fetch(`${origin}/json/list`, { signal: AbortSignal.timeout(3_000) })).json()
    for (const target of targets.filter((item) => item.type === 'page')) {
      await fetch(`${origin}/json/close/${target.id}`, { signal: AbortSignal.timeout(3_000) }).catch(() => {})
    }
    await fetch(`${origin}/json/new?${encodeURIComponent(targetUrl)}`, {
      method: 'PUT', signal: AbortSignal.timeout(5_000),
    })
  } catch {
    // 仅作为恢复尝试，最终错误由 connectToCdp 返回。
  }
}

async function closeDuplicateTargetPages(context, targetUrl) {
  let expected
  try { expected = new URL(targetUrl) } catch { return 0 }
  const pages = context.pages().filter((page) => {
    try {
      const current = new URL(page.url())
      return current.origin === expected.origin && current.pathname === expected.pathname
    } catch { return false }
  })
  if (pages.length < 2) return 0
  let closed = 0
  for (const page of pages.slice(0, -1)) {
    await page.close().then(() => { closed += 1 }).catch(() => {})
  }
  return closed
}

/** 检查页面 renderer 是否仍能响应；卡死的后台页会让所有 Playwright 操作一直等待。 */
async function repairUnresponsiveTargetPage(context, targetUrl) {
  let expected
  try { expected = new URL(targetUrl) } catch { return 0 }
  let repaired = 0
  for (const page of context.pages()) {
    let current
    try { current = new URL(page.url()) } catch { continue }
    if (current.origin !== expected.origin || current.pathname !== expected.pathname) continue
    let responsive = false
    try {
      await Promise.race([
        page.evaluate(() => document.readyState),
        new Promise((_, reject) => setTimeout(() => reject(new Error('renderer timeout')), 4_000)),
      ])
      responsive = true
    } catch {
      await Promise.race([
        page.close({ runBeforeUnload: false }).catch(() => {}),
        new Promise((resolveDelay) => setTimeout(resolveDelay, 3_000)),
      ])
      repaired += 1
    }
    if (responsive) break
  }
  return repaired
}

async function startStandaloneChrome(endpoint, targetUrl, userDataDir) {
  const executablePath = resolveChromeExecutable()
  if (!executablePath) {
    throw new Error('未找到 Google Chrome，请通过 DOUBAO_CHROME_EXECUTABLE_PATH 配置浏览器路径')
  }
  const port = new URL(endpoint).port
  // Chrome.app 是 universal binary，但 macOS 可能因应用的“使用 Rosetta 打开”设置
  // 选择 x86_64。M1 上强制 arm64，避免 Rosetta 下的 GPU/Renderer 与 CDP 页面卡死。
  const launchCommand = process.platform === 'darwin' && process.arch === 'arm64'
    ? '/usr/bin/arch'
    : executablePath
  const launchArgs = process.platform === 'darwin' && process.arch === 'arm64'
    ? ['-arm64', executablePath]
    : []
  launchArgs.push(
    `--remote-debugging-port=${port}`,
    '--remote-debugging-address=127.0.0.1',
    `--user-data-dir=${userDataDir}`,
    '--no-first-run',
    '--no-default-browser-check',
    targetUrl,
  )
  const chromeProcess = spawn(launchCommand, launchArgs, { detached: true, stdio: 'ignore' })
  chromeProcess.unref()

  const deadline = Date.now() + BROWSER_LAUNCH_TIMEOUT_MS
  let lastFailure
  while (Date.now() < deadline) {
    if (chromeProcess.exitCode !== null) {
      throw new Error(`Chrome 启动后立即退出 (${chromeProcess.exitCode})，请确认浏览器用户目录未被占用`)
    }
    try {
      return await connectToCdp(endpoint, targetUrl)
    } catch (error) {
      lastFailure = error
      await new Promise((resolve) => setTimeout(resolve, 250))
    }
  }
  const detail = lastFailure instanceof Error ? `：${lastFailure.message}` : ''
  throw new Error(`Chrome 启动超时${detail}`)
}

/**
 * 真实豆包站点统一复用一个 CDP Chrome，避免 Playwright 的 persistent context 和浏览器
 * 用户目录发生竞争。fixture/local URL 保留持久化 context，确保现有 Worker 测试独立。
 */
export async function openDoubaoBrowser({ targetUrl, userDataDir, cdpEndpoint = process.env.DOUBAO_CDP_ENDPOINT }) {
  if (isLocalUrl(targetUrl)) {
    const launchOptions = {
      headless: false,
      timeout: BROWSER_LAUNCH_TIMEOUT_MS,
      args: ['--no-first-run', '--no-default-browser-check'],
    }
    if (process.env.DOUBAO_CHROME_EXECUTABLE_PATH) launchOptions.executablePath = process.env.DOUBAO_CHROME_EXECUTABLE_PATH
    else launchOptions.channel = 'chrome'
    const context = await chromium.launchPersistentContext(userDataDir, launchOptions)
    context.setDefaultTimeout(NAVIGATION_TIMEOUT_MS)
    context.setDefaultNavigationTimeout(NAVIGATION_TIMEOUT_MS)
    return { browser: null, context, ownsContext: true, preserveOnUserAction: false, releaseLock: async () => {} }
  }

  const releaseLock = await acquireBrowserLock(userDataDir)
  try {
  if (cdpEndpoint) {
    let handle
    try { handle = await connectToCdp(cdpEndpoint, targetUrl, { attempts: 2 }) } catch (error) {
      await resetCdpTargets(cdpEndpoint, targetUrl)
      handle = await connectToCdp(cdpEndpoint, targetUrl, { attempts: 2 })
    }
    handle.releaseLock = releaseLock
    handle.unresponsiveRepaired = await repairUnresponsiveTargetPage(handle.context, targetUrl)
    handle.duplicatesClosed = await closeDuplicateTargetPages(handle.context, targetUrl)
    return handle
  }

  if (!isLocalUrl(targetUrl)) {
    const endpoint = `http://127.0.0.1:${AUTO_CDP_PORT}`
    if (await cdpServerExists(endpoint)) {
      let handle
      try { handle = await connectToCdp(endpoint, targetUrl, { attempts: 2 }) } catch (error) {
        await resetCdpTargets(endpoint, targetUrl)
        handle = await connectToCdp(endpoint, targetUrl, { attempts: 2 })
      }
      handle.releaseLock = releaseLock
      handle.unresponsiveRepaired = await repairUnresponsiveTargetPage(handle.context, targetUrl)
      handle.duplicatesClosed = await closeDuplicateTargetPages(handle.context, targetUrl)
      return handle
    }
    await clearStaleChromeSingletonFiles(userDataDir)
    const handle = await startStandaloneChrome(endpoint, targetUrl, userDataDir)
    handle.releaseLock = releaseLock
    handle.unresponsiveRepaired = await repairUnresponsiveTargetPage(handle.context, targetUrl)
    handle.duplicatesClosed = await closeDuplicateTargetPages(handle.context, targetUrl)
    return handle
  }
  } catch (error) {
    await releaseLock().catch(() => {})
    throw error
  }
}

export { NAVIGATION_TIMEOUT_MS }
