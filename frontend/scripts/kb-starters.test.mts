/**
 * 欢迎区示例问题的定向测试（node:test，无框架、无新依赖）。
 *
 * <p>跑法（Node ≥ 22.6）：
 * <pre>node --experimental-strip-types --test frontend/scripts/kb-starters.test.mts</pre>
 *
 * <p>只测"问题从哪来"这条线：没资料不问、多库不重复句型、描述缺失不编主题、
 * 主题截断有上限。文案拼接（pagesCopy）与界面渲染不在这层 —— 本轮不跑浏览器 E2E，
 * 那部分按未验证处理。
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { buildStarters, topicOf } from '../src/lib/kbStarters.ts'

test('没有资料的库不生成问题：问「讲了什么」是假问题', () => {
  assert.deepEqual(buildStarters([]), [])
  assert.deepEqual(buildStarters([{ key: 'a', name: '空库', docs: 0 }]), [])
  assert.deepEqual(buildStarters([{ key: 'a', name: '空库', desc: '还没导入', docs: 0 }]), [])
})

test('有资料才生成：一个库一条、最多三条、保持传入顺序', () => {
  const kbs = [
    { key: 'a', name: 'A', docs: 3 },
    { key: 'b', name: 'B', docs: 1 },
    { key: 'c', name: 'C', docs: 7 },
    { key: 'd', name: 'D', docs: 2 },
  ]
  const drafts = buildStarters(kbs)
  assert.equal(drafts.length, 3)
  assert.deepEqual(drafts.map((d) => d.kbKey), ['a', 'b', 'c'])
  assert.equal(buildStarters(kbs, 2).length, 2)
  assert.equal(buildStarters(kbs, 0).length, 0)
})

test('句型轮换：多库时不会给出一模一样的三句', () => {
  const kbs = [
    { key: 'a', name: 'A', desc: '集合框架与并发', docs: 3 },
    { key: 'b', name: 'B', desc: 'TCP/IP 与内存分页', docs: 1 },
    { key: 'c', name: 'C', desc: '事件循环与持久化', docs: 7 },
  ]
  assert.deepEqual(
    buildStarters(kbs).map((d) => d.template),
    ['overview', 'pitfalls', 'topic'],
  )
  // 第四轮回到第一种句型（同一个库不会重复出现，轮换只是避免三句同形）
  const five = ['a', 'b', 'c', 'd', 'e'].map((key) => ({ key, name: key, desc: '主题', docs: 1 }))
  assert.deepEqual(
    buildStarters(five, 4).map((d) => d.template),
    ['overview', 'pitfalls', 'topic', 'overview'],
  )
})

test('描述缺失时退回通用句型，不编主题', () => {
  const drafts = buildStarters([
    { key: 'a', name: 'A', docs: 2 },
    { key: 'b', name: 'B', docs: 2 },
    { key: 'c', name: 'C', docs: 2 },
  ])
  assert.deepEqual(
    drafts.map((d) => d.template),
    ['overview', 'pitfalls', 'overview'],
  )
  assert.equal(drafts.some((d) => d.topic), false)
})

test('主题只取第一句，并按长度截断', () => {
  assert.equal(topicOf('集合框架 / JVM / 并发，校招复习主线'), '集合框架 / JVM / 并发，校招复习主线')
  assert.equal(topicOf('第一句。第二句'), '第一句')
  assert.equal(topicOf('   '), null)
  assert.equal(topicOf(undefined), null)
  const long = topicOf('一'.repeat(60))
  assert.equal(long?.length, 24)
  assert.equal(long?.endsWith('…'), true)
})

test('规则与库名无关：空名字、任意 key 也照样生成', () => {
  assert.deepEqual(buildStarters([{ key: '1', name: '', docs: 1 }]), [{ kbKey: '1', template: 'overview' }])
})
