/**
 * 响应式验收（§44）：在 1920×1080 与 1366×768 下检查核心页面有无溢出/遮挡。
 *
 * 判据刻意选"客观可测"的那几个，不做主观审美判断：
 *   1. 整页是否出现横向滚动条（scrollWidth > clientWidth）——溢出最常见的表现；
 *   2. 关键交互元素是否在视口内且尺寸可用（按钮不被压扁、输入框不小于可用线）；
 *   3. 侧栏 + 主区是否都还有位置（不被其中一个挤没）。
 *
 * 用法：node web/scripts/verify-responsive.mjs
 */
import { chromium } from 'playwright-core'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
/**
 * 截图统一放**仓库根**的 docs/screenshots。
 * 用相对路径会跟着 cwd 跑偏：从 web/ 目录运行时截图会落到 web/docs/ 去
 * （实际踩到过），而截图是给人看的产物，位置飘了就等于没有。
 */
const outDir = resolve(here, '../../docs/screenshots')

const base = process.env.WEB_BASE ?? 'http://localhost:8088'
const username = process.env.DEMO_USER ?? 'demo'
const password = process.env.DEMO_PASSWORD ?? 'Demo@12345'

const VIEWPORTS = [
  { name: '1920x1080', width: 1920, height: 1080 },
  { name: '1366x768', width: 1366, height: 768 }
]

/** 每个页面用一条选择器定位"这个页面必须看得见的东西" */
const PAGES = [
  { name: '知识库列表', path: '/#/', mustSee: '[data-testid="kb-card"], [data-testid="kb-empty"]' },
  { name: '对话', path: '/#/agent', mustSee: '[data-testid="chat-messages"], .welcome' },
  { name: 'Agent 管理', path: '/#/agents', mustSee: '[data-testid="agent-card"], [data-testid="agent-empty"]' }
]

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const failures = []

for (const viewport of VIEWPORTS) {
  const page = await browser.newPage({ viewport: { width: viewport.width, height: viewport.height } })
  try {
    await page.goto(base, { waitUntil: 'networkidle' })
    await page.waitForSelector('[data-testid="auth-username"]', { timeout: 20000 })
    await page.fill('[data-testid="auth-username"]', username)
    await page.fill('[data-testid="auth-password"]', password)
    await page.click('[data-testid="auth-submit"]')
    await page.waitForFunction(() => !location.hash.startsWith('#/login'), { timeout: 20000 })

    for (const target of PAGES) {
      await page.goto(base + target.path, { waitUntil: 'networkidle' })
      await page.waitForSelector(target.mustSee, { timeout: 20000 })
      await page.waitForTimeout(900) // 等入场动画与异步列表渲染稳定

      // ① 横向溢出
      const overflow = await page.evaluate(() => ({
        scrollWidth: document.documentElement.scrollWidth,
        clientWidth: document.documentElement.clientWidth
      }))
      const horizontalOverflow = overflow.scrollWidth > overflow.clientWidth + 1

      // ② 关键控件可用性：主内容区不为零宽、按钮高度正常
      //
      //    有两类按钮**天生就矮**，不能算"被压扁"，否则这个检查会假阳性满天飞：
      //      1) 图标按钮：只显示图标、靠 aria-label 表达含义，高度本来就只有一行；
      //      2) link 类型按钮：语义上就是文字链接（"退出登录""删除""跑评测集"），
      //         18px 是 Element Plus 对 link 按钮的标准高度。
      //    真正要抓的是"本该是正常尺寸却被挤坏"的按钮：既矮、又不属于以上两类。
      const layout = await page.evaluate(() => {
        const main = document.querySelector('.main')
        const sidebar = document.querySelector('.sidebar')
        const buttons = [...document.querySelectorAll('button')].filter((b) => b.offsetParent !== null)
        const small = buttons
          .filter((b) => b.getBoundingClientRect().height < 20)
          .map((b) => {
            const rect = b.getBoundingClientRect()
            const label = b.getAttribute('aria-label') || b.innerText.trim() || b.className
            const isIconLike = rect.width <= 44
            const isLinkStyle = b.classList.contains('is-link')
            return {
              label: label.slice(0, 24),
              h: Math.round(rect.height),
              w: Math.round(rect.width),
              benign: isIconLike || isLinkStyle
            }
          })
        return {
          mainWidth: main ? Math.round(main.getBoundingClientRect().width) : null,
          sidebarWidth: sidebar ? Math.round(sidebar.getBoundingClientRect().width) : null,
          smallButtons: small
        }
      })

      const problems = []
      if (horizontalOverflow) problems.push(`横向溢出 ${overflow.scrollWidth}>${overflow.clientWidth}`)
      if (layout.mainWidth !== null && layout.mainWidth < 400) problems.push(`主区被压到 ${layout.mainWidth}px`)
      if (layout.sidebarWidth !== null && layout.sidebarWidth < 60) problems.push(`侧栏被压到 ${layout.sidebarWidth}px`)
      const squashed = layout.smallButtons.filter((b) => !b.benign)
      if (squashed.length > 0) {
        problems.push(
          `${squashed.length} 个按钮被压扁：${squashed.map((b) => `"${b.label}"(${b.w}x${b.h})`).join(' ')}`
        )
      }

      const ok = problems.length === 0
      if (!ok) failures.push(`${viewport.name} / ${target.name}：${problems.join('，')}`)
      // 图标按钮数量只作说明，不计入失败：它们矮是设计，不是被压扁
      const iconNote = layout.smallButtons.length
        ? `  ·  另有 ${layout.smallButtons.length} 个图标按钮（h<20px，设计如此）`
        : ''
      console.log(
        `${ok ? '✓' : '✗'} ${viewport.name.padEnd(10)} ${target.name.padEnd(10)} ` +
          `主区=${layout.mainWidth ?? '-'}px 侧栏=${layout.sidebarWidth ?? '-'}px ` +
          (ok ? iconNote : `→ ${problems.join('，')}`)
      )
      await page.screenshot({
        path: `${outDir}/responsive-${viewport.name}-${target.name}.png`
      })
    }
  } catch (error) {
    failures.push(`${viewport.name}：${error.message}`)
    console.log(`✗ ${viewport.name} — ${error.message}`)
  } finally {
    await page.close()
  }
}

await browser.close()

console.log('\n' + '='.repeat(60))
console.log(failures.length ? `响应式验收未通过（${failures.length} 项）：` : '响应式验收通过')
for (const f of failures) console.log(`  ✗ ${f}`)
console.log('='.repeat(60))

process.exit(failures.length ? 1 : 0)
