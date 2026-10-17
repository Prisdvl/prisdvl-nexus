# Prisdvl Nexus · 设计文件

本目录是**从当前前端实现抽出的设计交付物**，不是另一套平行规范。

| 文件 | 用途 |
|------|------|
| [`prisdvl-nexus-ui-kit.html`](./prisdvl-nexus-ui-kit.html) | 可浏览器打开的视觉 UI Kit（品牌 / 色板 / 字号 / 圆角 / 控件 / 动效） |
| [`tokens.json`](./tokens.json) | 机器可读令牌摘要（对接设计工具或脚本） |
| 源码真相 | `frontend/src/styles/tokens.css` |

## 使用方式

1. 用浏览器直接打开 `prisdvl-nexus-ui-kit.html`（无需起前端）。
2. 改色 / 改间距时**先改** `frontend/src/styles/tokens.css`，再视需要同步本目录摘要。
3. 历史文档（Vue 时代 / R1 草稿）在 `docs/archive/`，勿与本目录混用。

## 当前默认

- 主题：`data-theme="dark"` + `data-palette="prisdvl"`
- 主色：`#2457D6`（深钴蓝）
- 字体：Space Grotesk + 系统中文
- 圆角：6 / 10 / 14 / 18（落地页 CTA 行统一 12）
