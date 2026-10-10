interface BrandMarkProps {
  size?: number
  wordmark?: boolean
  tagline?: boolean
}

/** 品牌标识：几何四点星徽标 + 品名 */
export default function BrandMark({ size = 34, wordmark = true, tagline = false }: BrandMarkProps) {
  return (
    <span className="brand">
      <span className="mark" aria-hidden="true">
        <svg width={size} height={size} viewBox="0 0 34 34" fill="none">
          <rect x="1" y="1" width="32" height="32" rx="9" fill="var(--primary)" />
          <path d="M17 8.4l2 4.9 4.9 2-4.9 2-2 4.9-2-4.9-4.9-2 4.9-2z" fill="var(--on-primary)" />
          <circle cx="24.2" cy="10.6" r="1.6" fill="var(--on-primary)" opacity="0.85" />
        </svg>
      </span>
      {wordmark && <span className="wordmark">Prisdvl Nexus</span>}
      {tagline && <span className="tagline">个人知识库 · AI 对话 · 自定义 Agent</span>}
    </span>
  )
}