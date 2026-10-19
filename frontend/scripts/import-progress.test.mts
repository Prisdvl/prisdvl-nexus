/**
 * 导入进度模型的定向测试（node:test，无框架、无新依赖）。
 *
 * <p>跑法（Node ≥ 22.6）：
 * <pre>node --experimental-strip-types --test frontend/scripts/import-progress.test.mts</pre>
 *
 * <p>只测"诚不诚实"这条线：什么情况下允许给出数字、什么情况下必须是不确定态，
 * 以及"文件 → 明细"的匹配规则。界面渲染与网络调用不在这一层，故不在此覆盖。
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  barOf, baseName, fileRatio, isTerminalStatus, phaseOfAction, phaseOfStatus, pickItem,
} from '../src/lib/importProgress.ts'

test('单文件任务不给数字：后端只有 0 与 100 两个点', () => {
  assert.equal(fileRatio({ totalFiles: 1, progress: 99 }), null)
  assert.deepEqual(barOf({ task: { totalFiles: 1, progress: 99 } }), { kind: 'indeterminate' })
  // 连 totalFiles 都没给（旧后端 / 缺字段）时同样不给数字
  assert.deepEqual(barOf({ task: { progress: 60 } }), { kind: 'indeterminate' })
})

test('多文件任务用后端真比例，并夹在 0..100', () => {
  assert.equal(fileRatio({ totalFiles: 4, progress: 50 }), 50)
  assert.deepEqual(barOf({ task: { totalFiles: 4, progress: 50 } }), { kind: 'determinate', percent: 50 })
  assert.deepEqual(barOf({ task: { totalFiles: 4, progress: 250 } }), { kind: 'determinate', percent: 100 })
  assert.deepEqual(barOf({ task: { totalFiles: 4, progress: -5 } }), { kind: 'determinate', percent: 0 })
})

test('上传字节数是最优先的真数来源', () => {
  assert.deepEqual(barOf({ uploadPercent: 37 }), { kind: 'determinate', percent: 37 })
  // 同时有任务比例时，上传阶段的数字更新更及时，优先展示
  assert.deepEqual(
    barOf({ uploadPercent: 12, task: { totalFiles: 9, progress: 88 } }),
    { kind: 'determinate', percent: 12 },
  )
})

test('没有任何真数时只能是不确定态，绝不估算', () => {
  assert.deepEqual(barOf({}), { kind: 'indeterminate' })
  assert.deepEqual(barOf({ uploadPercent: null, task: null }), { kind: 'indeterminate' })
  assert.deepEqual(barOf({ uploadPercent: null, task: { totalFiles: 3 } }), { kind: 'indeterminate' })
})

test('任务状态：终态判定与行状态映射', () => {
  assert.equal(isTerminalStatus('RUNNING'), false)
  assert.equal(isTerminalStatus('QUEUED'), false)
  assert.equal(isTerminalStatus('partial'), true)
  assert.equal(isTerminalStatus('CANCELLED'), true)
  assert.equal(isTerminalStatus(null), false)

  assert.equal(phaseOfStatus('DONE'), 'done')
  assert.equal(phaseOfStatus('FAILED'), 'failed')
  assert.equal(phaseOfStatus('CANCELLED'), 'failed')
  // 单文件任务不该出现 PARTIAL；真出现时保守显示为有问题，而不是说成成功
  assert.equal(phaseOfStatus('PARTIAL'), 'failed')
  assert.equal(phaseOfStatus('QUEUED'), 'processing')
  assert.equal(phaseOfStatus('RUNNING'), 'processing')
})

test('明细 action：SKIPPED 既不是成功也不是失败，未知类别不外推成成功', () => {
  assert.equal(phaseOfAction('CREATED'), 'done')
  assert.equal(phaseOfAction('UPDATED'), 'done')
  assert.equal(phaseOfAction('SKIPPED'), 'skipped')
  assert.equal(phaseOfAction('DELETED'), 'skipped')
  assert.equal(phaseOfAction('FAILED'), 'failed')
  assert.equal(phaseOfAction('SOMETHING_NEW'), 'processing')
  assert.equal(phaseOfAction(null), 'processing')
})

test('文件 → 明细的匹配：同名优先、单条兜底、带目录也能对上', () => {
  const items = [
    { sourcePath: 'dir/a.md', action: 'CREATED' },
    { sourcePath: 'b.md', action: 'FAILED' },
  ]
  assert.equal(pickItem(items, 'b.md')?.action, 'FAILED')
  assert.equal(pickItem(items, '/abs/path/dir/a.md')?.action, 'CREATED')
  assert.equal(pickItem(items, 'c.txt'), null)
  // 明细只有一条时（单文件任务的常态）直接用那条
  assert.equal(pickItem([{ action: 'FAILED', sourcePath: null }], 'x.pdf')?.action, 'FAILED')
  assert.equal(pickItem([], 'x.pdf'), null)
  assert.equal(pickItem(null, 'x.pdf'), null)
})

test('路径取文件名同时认 / 与 \\', () => {
  assert.equal(baseName('a/b/c.md'), 'c.md')
  assert.equal(baseName('C:\\docs\\a.pdf'), 'a.pdf')
  assert.equal(baseName('plain.md'), 'plain.md')
})
