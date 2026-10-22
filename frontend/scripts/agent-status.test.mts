// Agent 状态映射的纯逻辑测试：这条规则一旦退回「不映射 status」，
// 真实模式下草稿 Agent 的「草稿」徽标与「发布」按钮就会双双消失（用户只会在建会话时撞 5008）。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { agentStatus } from '../src/lib/agent-status.ts'

test('DRAFT 原样识别', () => {
  assert.equal(agentStatus('DRAFT'), 'DRAFT')
})

test('大小写与空白不敏感', () => {
  assert.equal(agentStatus(' draft '), 'DRAFT')
})

test('PUBLISHED 与其它枚举按已发布处理', () => {
  assert.equal(agentStatus('PUBLISHED'), 'PUBLISHED')
  assert.equal(agentStatus('ARCHIVED'), 'PUBLISHED')
})

test('缺失状态不误判成草稿', () => {
  assert.equal(agentStatus(null), 'PUBLISHED')
  assert.equal(agentStatus(undefined), 'PUBLISHED')
  assert.equal(agentStatus(''), 'PUBLISHED')
})
