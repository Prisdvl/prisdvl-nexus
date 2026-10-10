// 主题/配色的测试助手：走开发环境钩子（不刷新，避免丢内存态）
export async function setTheme(page, mode) {
  await page.evaluate((m) => window.__nxTheme.setMode(m), mode)
  await page.waitForTimeout(260)
}
export async function setPalette(page, palette) {
  await page.evaluate((p) => window.__nxTheme.setPalette(p), palette)
  await page.waitForTimeout(260)
}
export async function readAppearance(page) {
  return page.evaluate(() => ({
    mode: document.documentElement.dataset.themeMode,
    theme: document.documentElement.dataset.theme,
    palette: document.documentElement.dataset.palette,
  }))
}
