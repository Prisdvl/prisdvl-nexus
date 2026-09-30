<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, DataAnalysis, Promotion } from '@element-plus/icons-vue'
import { askAgent, DEFAULT_EVAL_CASES, runEval, type AgentAnswer, type EvalReport } from '@/api/agent'
import { listKnowledgeBases } from '@/api/knowledge'
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

/** 评测：对默认评测集跑一遍 Agent，报告要点命中率与引用有效性（用第一个知识库）。 */
const evalRunning = ref(false)
const evalReport = ref<EvalReport | null>(null)
const evalKbName = ref('')

async function runEvaluation() {
  evalRunning.value = true
  try {
    const kbs = (await listKnowledgeBases()).list
    if (!kbs.length) {
      ElMessage.warning('还没有知识库，先导一份笔记再评测')
      return
    }
    evalKbName.value = kbs[0].name
    evalReport.value = await runEval(kbs[0].id, DEFAULT_EVAL_CASES)
  } catch (error) {
    ElMessage.error('评测失败：' + (error instanceof Error ? error.message : String(error)))
  } finally {
    evalRunning.value = false
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

    <!-- 评测：把"回答质量"变成可反复点、能看出回归的数字 -->
    <section class="eval-box">
      <div class="eval-head">
        <h3>评测</h3>
        <el-button size="small" :icon="DataAnalysis" :loading="evalRunning" @click="runEvaluation">
          跑评测集
        </el-button>
      </div>
      <p class="hint">
        对「问题 + 预期要点」跑一遍 Agent，报告要点命中率与引用有效性
        <template v-if="evalKbName">（知识库：{{ evalKbName }}）</template>
      </p>

      <div v-if="evalReport" class="eval-report">
        <div class="eval-summary">
          <span class="num">平均命中率 <b>{{ evalReport.avgPointRecall.toFixed(2) }}</b></span>
          <span class="num">通过 <b>{{ evalReport.passed }}</b> / {{ evalReport.results.length }}</span>
        </div>
        <ul>
          <li v-for="(result, index) in evalReport.results" :key="index">
            <span class="eval-q">{{ result.testCase.question }}</span>
            <el-tag size="small" :type="result.pointRecall === 1 ? 'success' : 'warning'">
              {{ result.pointRecall.toFixed(2) }}
            </el-tag>
            <el-tag v-if="result.citationsValid" size="small" effect="plain" type="success">有来源</el-tag>
            <span v-if="result.missingPoints.length" class="eval-missing">缺：{{ result.missingPoints.join('、') }}</span>
          </li>
        </ul>
      </div>
    </section>
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

.eval-box {
  margin-top: var(--space-6);
  padding-top: var(--space-4);
  border-top: 1px solid var(--neutral-200);
}

.eval-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.eval-head h3 {
  font-size: 15px;
  margin: 0;
}

.eval-report {
  margin-top: var(--space-3);
  padding: var(--space-3);
  background: var(--neutral-0);
  border: 1px solid var(--neutral-200);
  border-radius: var(--radius-input);
}

.eval-summary {
  display: flex;
  gap: var(--space-5);
  font-size: 13px;
  color: var(--neutral-600);
}

.eval-summary b {
  font-family: var(--font-mono);
  font-size: 16px;
  color: var(--neutral-900);
}

.eval-report ul {
  list-style: none;
  margin: var(--space-2) 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.eval-report li {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: 12.5px;
}

.eval-q {
  color: var(--neutral-700);
}

.eval-missing {
  color: var(--neutral-500);
  font-size: 12px;
}
</style>