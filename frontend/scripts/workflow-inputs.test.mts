// 「试跑草稿」输入框默认值的纯逻辑测试。
// 这条规则曾经是写死的 `{"question": ""}`：模板里 {{ input.question }} 空着跑出来
// 是「问： 」半截文本，看着像模板坏了。默认值必须带一个示例问题，且两种语言都要有。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { WORKFLOW_COPY, defaultInputs } from '../src/pages/workflow/copy.ts'

test('默认输入是合法 JSON 对象', () => {
  const parsed = JSON.parse(defaultInputs(WORKFLOW_COPY.zh))
  assert.equal(typeof parsed, 'object')
})

test('question 预填了示例问题，不是空串', () => {
  const parsed = JSON.parse(defaultInputs(WORKFLOW_COPY.zh))
  assert.equal(parsed.question, WORKFLOW_COPY.zh.sampleQuestion)
  assert.ok(parsed.question.trim().length > 0)
})

test('英文界面预填英文示例', () => {
  const parsed = JSON.parse(defaultInputs(WORKFLOW_COPY.en))
  assert.equal(parsed.question, WORKFLOW_COPY.en.sampleQuestion)
  assert.ok(parsed.question.trim().length > 0)
})

test('两种语言的示例问题不是同一串（漏翻会显形）', () => {
  assert.notEqual(WORKFLOW_COPY.zh.sampleQuestion, WORKFLOW_COPY.en.sampleQuestion)
})
