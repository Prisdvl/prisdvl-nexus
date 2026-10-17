/** 轻量 className 合并（Magic UI / shadcn 组件常用） */
export function cn(...parts: Array<string | false | null | undefined>): string {
  return parts.filter(Boolean).join(' ')
}
