// 诊断：设置页「外观」分区的橡皮分段，初次挂载时拇指几何是否异常（全部选中）
import { chromium } from 'playwright-core'
const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
await page.addInitScript(() => {
  localStorage.setItem('nx-proto-tour-done', '1')
  localStorage.setItem('nx-proto-auth', '1')
  localStorage.setItem('nx-proto-theme-mode', 'light')
  localStorage.setItem('nx-proto-tool-policy', JSON.stringify(['js.sandbox']))
})
await page.goto('http://localhost:5175/#/app/account', { waitUntil: 'networkidle' })
await page.waitForTimeout(1800)

// 装一个采样器：切到外观分区后，按帧记录两个分段的拇指宽度 / 轨道宽度 / 渲染宽度
const samples = await page.evaluate(async () => {
  const out = []
  const btn = Array.from(document.querySelectorAll('.settings-item')).find((b) => b.textContent.includes('外观'))
  btn.click()
  const t0 = performance.now()
  for (let i = 0; i < 40; i++) {
    await new Promise((r) => requestAnimationFrame(r))
    const segs = Array.from(document.querySelectorAll('.settings-body .rubber-segment'))
    if (segs.length) {
      const row = segs.map((s) => {
        const thumb = s.querySelector('.rubber-segment__thumb, [class*=thumb]')
        const track = s.getBoundingClientRect()
        const th = thumb ? thumb.getBoundingClientRect() : null
        const item = s.querySelector('.rubber-segment__item')?.getBoundingClientRect()
        return {
          trackW: Math.round(track.width),
          thumbW: th ? Math.round(th.width) : null,
          itemW: item ? Math.round(item.width) : null,
          thumbX: th ? Math.round(th.left - track.left) : null,
          itemCount: s.querySelectorAll('.rubber-segment__item').length,
        }
      })
      out.push({ ms: Math.round(performance.now() - t0), row })
    }
    if (out.length > 14) break
  }
  return out
})
console.log('时间(ms) | 分段1: 轨道/拇指/单项/拇指X | 分段2 同')
samples.forEach((s) => {
  const fmt = (r) => `${r.trackW}/${r.thumbW}/${r.itemW}/x${r.thumbX}(${r.itemCount}项)`
  console.log(`  ${String(s.ms).padStart(4)} | ${fmt(s.row[0])} | ${s.row[1] ? fmt(s.row[1]) : '-'}`)
})
await browser.close()
