interface BrandMarkProps {
  size?: number
  wordmark?: boolean
  tagline?: boolean
}

/**
 * 品牌标识：统一使用品牌图标 `public/img/prisdvl-nexus.png`（与站点 favicon 同源）。
 *
 * 2026-10-11 按要求改造：原先是一个内联的几何四点星 SVG，只存在于应用内部，
 * 与浏览器标签页的图标（prisdvl-nexus.png 的派生裁切版）不是同一个视觉。
 * 现在改成图片后，侧栏 / 移动栏 / 登录页 / 落地页与 favicon 是同一个品牌图标。
 *
 * ⚠️ 原图 719×564 非方形，这里按高度等比缩放（width: auto），不做裁切拉伸。
 */
export default function BrandMark({ size = 34, wordmark = true, tagline = false }: BrandMarkProps) {
  return (
    <span className="brand">
      <span className="mark" aria-hidden="true">
        <img
          src="/img/prisdvl-nexus.png"
          alt=""
          height={size}
          style={{ height: size, width: 'auto', display: 'block' }}
        />
      </span>
      {wordmark && <span className="wordmark">Prisdvl Nexus</span>}
      {tagline && <span className="tagline">个人知识库 · AI 对话 · 自定义 Agent</span>}
    </span>
  )
}