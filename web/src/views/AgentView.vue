<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, Promotion } from '@element-plus/icons-vue'
import { askAgent, type AgentAnswer } from '@/api/agent'
import { renderMarkdown } from '@/utils/markdown'

const router = useRouter()

const question = ref('')
const asking = ref(false)
const answer = ref<AgentAnswer | null>(null)
const answerHtml = ref('')

async function ask() {
  if (!question.value.trim()) {
    ElMessage.warning('请输入问题')
    return
  }
  asking.value = true
  try {
    answer.value = await askAgent(question.value.trim())
    answerHtml.value = renderMarkdown(answer.value.answer)
  } catch (error) {
    ElMessage.error('Agent 请求失败：' + (error instanceof Error ? error.message : String(error)))
  } finally {
    asking.value = false
  }
}
</script>

<template>
  <div class="page">
    <header class="page-head">
      <div>
        <h1>Agent</h1>
        <p class="sub">模型先决定是否检索你的知识库（工具循环），再基于结果回答，并展示每次检索的痕迹</p>
      </div>
      <el-button :icon="ArrowLeft" @click="router.push('/')">返回知识库</el-button>
    </header>

    <div class="agent-box">
      <div class="ask-row">
        <el-input
          v-model="question"
          placeholder="例如：我的笔记里缓存击穿怎么解决？"
          @keyup.enter="ask"
        />
        <el-button type="primary" :icon="Promotion" :loading="asking" @click="ask">提问</el-button>
      </div>
      <p class="hint">涉及笔记内容的问题才会触发检索；检索不可用时工具会如实降级为关键词匹配。</p>

      <template v-if="answer">
        <!-- 工具调用痕迹：'用户可控'的一半 = 看得见模型做了什么 -->
        <div v-if="answer.toolCalls.length" class="trace">
          <div class="outline-head">工具调用痕迹</div>
          <ol>
            <li v-for="(call, index) in answer.toolCalls" :key="index">
              <code>{{ call.tool }}({{ call.input }})</code>
              <span class="trace-result">{{ call.resultSummary }}</span>
            </li>
          </ol>
        </div>

        <div class="markdown answer">
          <!-- eslint-disable-next-line vue/no-v-html -- 内容经 marked + DOMPurify 处理 -->
          <div v-html="answerHtml" />
        </div>
        <p v-if="!answer.usedTools" class="hint">本次回答没有调用工具（问题与知识库内容无关）。</p>
      </template>
      <div v-else class="empty">问一个涉及你笔记的问题，看 Agent 如何检索并基于笔记回答</div>
    </div>
  </div>
</template>

<style scoped>
.page {
  max-width: 900px;
  margin: 0 auto;
  padding: var(--space-6);
}

.ask-row {
  display: flex;
  gap: var(--space-2);
}

.answer {
  padding: var(--space-4);
  background: var(--neutral-50);
  border: 1px solid var(--neutral-200);
  border-left: 2px solid var(--accent-400);
  border-radius: var(--radius-input);
  line-height: 1.7;
}

.trace {
  margin: var(--space-4) 0;
  padding: var(--space-3);
  background: var(--neutral-0);
  border: 1px solid var(--neutral-200);
  border-radius: var(--radius-input);
}

.trace ol {
  margin: var(--space-2) 0 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.trace code {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--accent-600);
  word-break: break-all;
}

.trace-result {
  font-size: 12px;
  color: var(--neutral-500);
  margin-left: var(--space-2);
}

.hint {
  font-size: 12px;
  color: var(--neutral-500);
  margin-top: var(--space-2);
}

.empty {
  margin-top: var(--space-4);
  padding: var(--space-6);
  text-align: center;
  color: var(--neutral-400);
  border: 1px dashed var(--neutral-200);
  border-radius: var(--radius-input);
}
</style>