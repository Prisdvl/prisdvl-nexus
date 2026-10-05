/**
 * 后端通用包装类型 —— 严格对应 Java 侧定义，**不猜测、不增字段**。
 *
 * 权威来源：
 * ```text
 * nexus-common/src/main/java/com/lky/nexus/common/api/Result.java
 *   record Result<T>(int code, String message, T data)
 *
 * nexus-common/src/main/java/com/lky/nexus/common/api/PageResult.java
 *   record PageResult<T>(List<T> list, long total, int pageNum, int pageSize, int pages)
 * ```
 *
 * ## 为什么类型定义在这里而不在各个 api 文件里
 *
 * R3 任务书 §17 明确「禁止同一个 DTO 出现两套略有不同的定义」。
 * 把它们集中在 `api/types/`，任何人需要时从同一处 import。
 */

/**
 * 后端统一响应包装。
 *
 * `code === 0` 表示成功（见 `ErrorCode.SUCCESS`），其余是业务错误码。
 * 注意 `data` 可能是 `null`——例如 `Result<Void>` 的删除接口。
 */
export interface Result<T> {
  code: number
  message: string
  data: T
}

/** 分页结果。`pages` 是后端算好的总页数，前端不需要自己算。 */
export interface PageResult<T> {
  list: T[]
  /** 后端是 `long`，可能超过 JS 安全整数——但计数场景不会，保留 number。 */
  total: number
  pageNum: number
  pageSize: number
  pages: number
}

/** 业务成功码。与 `ErrorCode.SUCCESS` 一致。 */
export const SUCCESS_CODE = 0

/** 页面默认分页。与 Vue 侧 `listKnowledgeBases(1, 50)` 的默认值一致。 */
export const DEFAULT_PAGE_NUM = 1
export const DEFAULT_PAGE_SIZE = 50
