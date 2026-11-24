<script setup lang="ts">
import { randomId, type Question, type Source } from './grading'

defineProps<{ questions: Question[]; submissions?: string[]; sources?: Source[]; awaiting?: boolean; standardsOnly?: boolean }>()
const emit = defineEmits<{ preview: [id: string]; add: [] }>()

const keys = new WeakMap<object, string>()
function keyOf(target: object) {
  let id = keys.get(target)
  if (!id) { id = randomId(); keys.set(target, id) }
  return id
}
function newId() { return randomId() }

function joinText(left: string, right: string) {
  return [left,right].filter(text => text.trim()).join('\n')
}

function mergeWithPrevious(questions: Question[], index: number) {
  const previous = questions[index-1]
  const current = questions[index]
  if (!previous || !current) return
  previous.prompt = joinText(previous.prompt,current.prompt)
  previous.source_ids = [...new Set([...(previous.source_ids ?? []),...(current.source_ids ?? [])])]
  for (const answer of current.answers) {
    const earlier = previous.answers.find(row => row.submission_id === answer.submission_id)
    if (!earlier) {
      previous.answers.push(answer)
      continue
    }
    earlier.text = joinText(earlier.text,answer.text)
    earlier.source_ids = [...new Set([...(earlier.source_ids ?? []),...(answer.source_ids ?? [])])]
    earlier.language ||= answer.language
    earlier.needs_input = true
  }
  questions.splice(index,1)
}

function setAnswers(question: Question, submissions?: string[]) {
  if (!submissions) {
    let index = question.answers.length + 1
    while (question.answers.some(a => a.submission_id === '答卷 ' + index)) index++
    question.answers.push({ submission_id: '答卷 ' + index, text: '' })
    return
  }
  for (const id of submissions) {
    if (!question.answers.some(a => a.submission_id === id)) question.answers.push({ submission_id: id, text: '' })
  }
}
</script>

<template>
  <div class="question-editor">
    <section v-for="(q, index) in questions" :key="keyOf(q)" class="question">
      <div class="row question-head">
        <label>题号<input v-model="q.id" maxlength="80" required></label>
        <label>题型<select v-model="q.kind"><option value="TEXT">简答、作文或解题过程</option><option value="EXACT">客观题或固定答案</option><option value="CODE">编程题</option></select></label>
        <label>满分<input v-model.number="q.max_score" type="number" min="1" max="10000" required></label>
        <button v-if="awaiting && index>0" class="quiet" type="button" @click="mergeWithPrevious(questions,index)">并入上一题</button>
        <button class="quiet danger" type="button" @click="questions.splice(index, 1)">移除题目</button>
      </div>
      <label>题干<textarea v-model="q.prompt" rows="2" placeholder="输入题目或补齐识别缺失的内容"></textarea></label>
      <div v-if="q.source_ids?.length" class="chips">
        <button class="chip" type="button" v-for="id in q.source_ids" :key="id" @click="emit('preview', id)">题干原文 {{ id }}</button>
      </div>
      <label v-if="q.kind === 'TEXT'">参考答案 <span class="muted">选填</span><textarea v-model="q.reference_answer" rows="2"></textarea></label>

      <template v-if="q.kind === 'TEXT'">
        <div class="between subhead"><strong>评分细则</strong>
          <button class="quiet" type="button" @click="(q.rubric ??= []).push({ id: newId(), description: '', points: 1 })">＋ 评分项</button></div>
        <div v-if="!q.rubric?.length" class="muted">可填写给分点，或由 Agent 在准备草案时拟定。</div>
        <div v-for="(criterion, n) in q.rubric" :key="criterion.id" class="criteria-edit">
          <label>给分与扣分条件<textarea v-model="criterion.description" rows="2"></textarea></label>
          <label>分值<input type="number" v-model.number="criterion.points" min="1"></label>
          <button class="quiet danger" type="button" @click="q.rubric?.splice(n, 1)">移除</button>
        </div>
      </template>

      <template v-else-if="q.kind === 'EXACT'">
        <label>接受的答案 <span class="muted">每行一个</span>
          <textarea :value="q.accepted?.join('\n')" @input="q.accepted = ($event.target as HTMLTextAreaElement).value.split('\n')" rows="2"></textarea>
        </label>
      </template>

      <template v-else>
        <div class="between subhead"><strong>判题用例</strong>
          <button class="quiet" type="button" @click="(q.tests ??= []).push({ id: newId(), input: '', expected: '', points: 1 })">＋ 用例</button></div>
        <div v-for="(test, n) in q.tests" :key="test.id" class="criteria-edit test-edit">
          <label>标准输入<textarea v-model="test.input" rows="2"></textarea></label>
          <label>预期输出<textarea v-model="test.expected" rows="2"></textarea></label>
          <label>分值<input type="number" v-model.number="test.points" min="1"></label>
          <button class="quiet danger" type="button" @click="q.tests?.splice(n, 1)">移除</button>
        </div>
      </template>

      <template v-if="!standardsOnly">
      <div class="between subhead"><strong>作答</strong>
        <button class="quiet" type="button" @click="setAnswers(q, submissions)">＋ {{ awaiting ? '补齐答卷' : '录入作答' }}</button></div>
      <div v-for="(answer, a) in q.answers" :key="keyOf(answer)" class="answer-edit">
        <div class="row">
          <label>答卷编号<input v-model="answer.submission_id" maxlength="100" :readonly="awaiting"></label>
          <label v-if="q.kind === 'CODE'">语言<select v-model="answer.language">
            <option value="">请选择</option><option>PYTHON</option><option>JAVA</option><option>CPP</option><option>C</option><option>JAVASCRIPT</option>
          </select></label>
          <button class="quiet danger" type="button" @click="q.answers.splice(a, 1)">移除作答</button>
        </div>
        <label>答案<textarea v-model="answer.text" :class="{ code: q.kind === 'CODE' }" :rows="q.kind === 'CODE' ? 8 : 3"></textarea></label>
        <label v-if="answer.needs_input" class="inline-check"><input type="checkbox" :checked="false" @change="answer.needs_input = false">已核对原文和该作答</label>
        <div v-if="awaiting && sources?.length" class="row">
          <label>引用原文区块
            <select value="" @change="(answer.source_ids ??= []).push(($event.target as HTMLSelectElement).value); ($event.target as HTMLSelectElement).value = ''">
              <option disabled value="">选择区块</option>
              <option v-for="source in sources.filter(s => s.role === 'ANSWER' && s.submission_id === answer.submission_id && !answer.source_ids?.includes(s.id))" :value="source.id" :key="source.id">
                {{ source.id }} · {{ source.text.slice(0, 40) }}
              </option>
            </select>
          </label>
        </div>
        <div v-if="answer.source_ids?.length" class="chips">
          <span v-for="(id, n) in answer.source_ids" :key="n" class="chip pair">
            <button type="button" @click="emit('preview', id)">作答原文 {{ id }}</button>
            <button type="button" :aria-label="'解绑原文 ' + id" @click="answer.source_ids?.splice(n, 1)">×</button>
          </span>
        </div>
      </div>
      </template>
    </section>
    <button class="secondary" type="button" @click="emit('add')">＋ 添加题目</button>
  </div>
</template>
