import assert from 'node:assert/strict'
import test from 'node:test'
import { CHUNK_STATUS_DONE, chunkStatusCounts } from '../src/lib/chunk-status.ts'

test('终态常量是 DONE（后端 PENDING/DONE/FAILED/STALE，没有 READY）', () => {
  assert.equal(CHUNK_STATUS_DONE, 'DONE')
})

test('全 DONE：向量与全文都算就绪，没有待处理', () => {
  const stats = chunkStatusCounts([
    { vectorStatus: 'DONE', esStatus: 'DONE' },
    { vectorStatus: 'DONE', esStatus: 'DONE' },
  ])
  assert.deepEqual(stats, { total: 2, vectors: 2, fullText: 2, pending: 0, notVectorized: false })
})

test('只建了索引没向量化：向量 0、全文 2，并标记需要向量化', () => {
  const stats = chunkStatusCounts([
    { vectorStatus: 'PENDING', esStatus: 'DONE' },
    { vectorStatus: 'PENDING', esStatus: 'DONE' },
  ])
  assert.equal(stats.vectors, 0)
  assert.equal(stats.fullText, 2)
  assert.equal(stats.pending, 2)
  assert.equal(stats.notVectorized, true)
})

test('READY 不算就绪：那正是旧的假状态（面板因此永远显示 0）', () => {
  const stats = chunkStatusCounts([{ vectorStatus: 'READY', esStatus: 'READY' }])
  assert.equal(stats.vectors, 0)
  assert.equal(stats.fullText, 0)
  assert.equal(stats.notVectorized, true)
})

test('部分失败不冒充就绪，也不再提示"没向量化"（已有成功的那些）', () => {
  const stats = chunkStatusCounts([
    { vectorStatus: 'DONE', esStatus: 'DONE' },
    { vectorStatus: 'FAILED', esStatus: 'DONE' },
  ])
  assert.equal(stats.vectors, 1)
  assert.equal(stats.fullText, 2)
  assert.equal(stats.pending, 0)
  assert.equal(stats.notVectorized, false)
})

test('空库：没有切片就没有"未向量化"这回事', () => {
  assert.deepEqual(chunkStatusCounts([]), {
    total: 0,
    vectors: 0,
    fullText: 0,
    pending: 0,
    notVectorized: false,
  })
})
