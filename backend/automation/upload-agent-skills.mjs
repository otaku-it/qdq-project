import { resolve } from 'node:path'
import { NAVIGATION_TIMEOUT_MS, openDoubaoBrowser } from './doubao-browser.mjs'

const ADMIN_URL = process.env.DOUBAO_ADMIN_URL || 'https://admin.doubao.com/ask/doubao/builtin-skill'
const USER_DATA_DIR = process.env.DOUBAO_USER_DATA_DIR || resolve(process.cwd(), '.doubao-profile')
const AUTHORIZATION_WAIT_MS = Number(process.env.DOUBAO_AUTHORIZATION_WAIT_MS || 45_000)

async function readPayload() {
  const chunks = []
  for await (const chunk of process.stdin) chunks.push(chunk)
  return JSON.parse(Buffer.concat(chunks).toString('utf8'))
}

async function openBrowser() {
  return openDoubaoBrowser({ targetUrl: ADMIN_URL, userDataDir: USER_DATA_DIR })
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
    // 豆包后台外层偶发停留在 loading。只刷新当前页一次，既不新开标签页也不重复授权。
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
      // 独立 Chrome 首次启动可能自带 about:blank，复用该标签避免多开一个空白页。
      page = browserHandle.context.pages().find((candidate) => candidate.url() === 'about:blank')
        ?? await browserHandle.context.newPage()
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
  const browserUnavailable = /Target page, context or browser has been closed|ECONNREFUSED|connectOverCDP|Browser\.setDownloadBehavior|浏览器自动启动失败/.test(errorMessage)
  workerResult = {
    success: false,
    requiresUserAction: browserUnavailable,
    message: browserUnavailable
      ? '受控浏览器已关闭或会话已失效，已尝试重新打开；请完成飞书登录后继续'
      : errorMessage,
    uploadedSkillIds: [],
  }
  workerExitCode = browserUnavailable ? 0 : 1
} finally {
  if (browserHandle?.ownsContext && !(browserHandle.preserveOnUserAction && workerResult?.requiresUserAction)) {
    await browserHandle.context.close().catch(() => {})
  }
  await browserHandle?.releaseLock?.().catch(() => {})
}

// CDP 连接会保持 Node 事件循环存活。先完整写出 JSON，再显式退出，只断开 Worker，不关闭用户 Chrome。
await new Promise((resolve, reject) => {
  process.stdout.write(JSON.stringify(workerResult), (error) => error ? reject(error) : resolve())
})
process.exit(workerExitCode)
