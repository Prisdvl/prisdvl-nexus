/**
 * 围栏规整的定向测试（node:test，无框架、无新依赖）。
 *
 * <p>跑法（Node ≥ 22.6）：
 * <pre>node --experimental-strip-types --test frontend/scripts/markdown-fences.test.mts</pre>
 *
 * <p>只测纯文本规则：模型把收尾围栏粘在代码行尾、或干脆漏掉，后续正文不该被当成代码。
 * 渲染结果（marked 的 HTML）与界面视觉不在这层——那部分由浏览器 E2E 覆盖。
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { normalizeFences } from '../src/lib/markdown-fences.ts'

const fenced = (body: string) => '```java\n' + body + '\n```'

test('没有围栏的正文一个字节都不动', () => {
  const src = '## 标题\n\n- 列表项 **粗体** `行内代码`\n\n> 引用'
  assert.equal(normalizeFences(src), src)
  /* 行内代码里的单反引号不是围栏 */
  assert.equal(normalizeFences('用 `Class` 反射'), '用 `Class` 反射')
})

test('合法的围栏原样保留（幂等）', () => {
  const src = fenced('Class<?> c = Class.forName("java.lang.String");')
  assert.equal(normalizeFences(src), src)
  assert.equal(normalizeFences(normalizeFences(src)), normalizeFences(src))
})

test('收尾围栏粘在代码行尾：拆出来，后续正文不再被吞', () => {
  const src = [
    '```java',
    'Class<?> c3 = Class.forName("java.lang.String"); //全限定名加载```',
    '',
    '**3. 常用 API**',
    '',
    '- `getMethods()`：全部 public 方法',
  ].join('\n')
  const out = normalizeFences(src)
  const lines = out.split('\n')
  assert.equal(lines[1], 'Class<?> c3 = Class.forName("java.lang.String"); //全限定名加载')
  assert.equal(lines[2], '```')
  /* 围栏之后再无围栏字符，后面是正文 */
  assert.equal(out.split('```').length, 3) /* 开 + 闭 */
  assert.ok(out.trimEnd().endsWith('- `getMethods()`：全部 public 方法'))
})

test('围栏后跟着标点（引号 / 括号 / 空格）也能拆', () => {
  const src = ['```js', 'const a = 1```”', '', '后续正文'].join('\n')
  const out = normalizeFences(src)
  assert.deepEqual(out.split('\n').slice(0, 3), ['```js', 'const a = 1', '```'])
  assert.ok(out.endsWith('后续正文'))
})

test('围栏到文档结束仍未闭合：补一个同长度的收尾', () => {
  const src = '正文\n\n````python\nprint(1)\n'
  const out = normalizeFences(src)
  assert.ok(out.endsWith('````'))
  assert.equal(out.split('````').length, 3)
})

test('波浪号围栏用同字符收尾（不混用反引号）', () => {
  const src = '~~~txt\nhello~~~\n\n结尾'
  const out = normalizeFences(src)
  assert.deepEqual(out.split('\n').slice(0, 3), ['~~~txt', 'hello', '~~~'])
  assert.ok(out.endsWith('结尾'))
})

test('围栏内的空行与正文不受影响', () => {
  const src = ['```md', '第一行', '', '第三行', '```', '', '正文'].join('\n')
  assert.equal(normalizeFences(src), src)
})

test('一个回答里多个围栏：每个都独立规整', () => {
  const src = [
    '```java',
    'int a = 1```',
    '中间段落',
    '```java',
    'int b = 2```',
    '结尾段落',
  ].join('\n')
  const out = normalizeFences(src)
  assert.equal(out, [
    '```java',
    'int a = 1',
    '```',
    '中间段落',
    '```java',
    'int b = 2',
    '```',
    '结尾段落',
  ].join('\n'))
})
