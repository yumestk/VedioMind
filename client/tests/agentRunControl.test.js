import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { parse, compileScript } from '@vue/compiler-sfc'
import { createRenderer, h, ref, nextTick } from 'vue'

// Mount the real SFC with Vue's native renderer; only HTTP and the polling clock are doubles.
test('run card polls once, accepts parent updates, cancels, and stops polling at terminal status', async () => {
  const source = await readFile(new URL('../src/components/AgentRunMessageCard.vue', import.meta.url), 'utf8')
  const { descriptor } = parse(source)
  const script = compileScript(descriptor, { id: 'run-card-test', inlineTemplate: true })
  const initial = { id: 'r', goal: '学习目标', status: 'RUNNING', phase: 'DRAFTING', terminal: false, sourceAvailable: true }
  let gets = 0, cancels = 0, timers = new Map(), timerId = 0
  const cursors = []
  globalThis.__runCardApi = {
    getVideoAgentRun: async () => { gets++; return { ...initial } },
    cancelVideoAgentRun: async () => { cancels++; initial.status = 'CANCELLED'; initial.terminal = true; return { ...initial } },
    getVideoAgentRunEvents: async (id, after) => { cursors.push(after); return after ? [] : [{ sequence: 1, type: 'EXECUTION_STARTED', data: {} }] },
    getStudyArtifacts: async () => [],
    errorMessage: error => error.message
  }
  const code = script.content
    .replace(/import \{[^}]+\} from ['"]\.\.\/api\/media['"]/, 'const { getVideoAgentRun, cancelVideoAgentRun, getVideoAgentRunEvents, getStudyArtifacts } = globalThis.__runCardApi')
    .replace(/import StudyNoteArtifactCard from [^\n]+/, 'const StudyNoteArtifactCard = { render: () => null }')
    .replace(/import \{ errorMessage \} from ['"]\.\.\/api\/http['"]/, 'const { errorMessage } = globalThis.__runCardApi')
  const component = (await import(`data:text/javascript,${encodeURIComponent(code.replace(/from ["']vue["']/g, `from '${import.meta.resolve('vue')}'`))}`)).default
  const element = type => ({ type, children: [], props: {}, text: '' })
  const renderer = createRenderer({
    createElement: element, createText: text => ({ ...element('text'), text }), createComment: () => element('comment'),
    insert: (child, parent) => { parent.children.push(child); child.parent = parent },
    remove: child => { child.parent.children = child.parent.children.filter(x => x !== child) },
    setText: (node, text) => { node.text = text }, setElementText: (node, text) => { node.text = text; node.children = [] },
    patchProp: (node, key, previous, value) => { node.props[key] = value },
    parentNode: node => node.parent, nextSibling: () => null
  })
  const run = ref({ ...initial }), root = element('root')
  const app = renderer.createApp({ setup: () => () => h(component, { run: run.value, userId: 2, onRunChange: value => { run.value = value } }) })
  const previousSet = globalThis.setTimeout, previousClear = globalThis.clearTimeout
  globalThis.setTimeout = callback => { const id = ++timerId; timers.set(id, callback); return id }
  globalThis.clearTimeout = id => timers.delete(id)
  const flush = async () => { for (let i = 0; i < 8; i++) { await Promise.resolve(); await nextTick() } }
  const find = (node, predicate) => predicate(node) ? node : node.children.map(child => find(child, predicate)).find(Boolean)
  try {
    app.mount(root); await flush()
    assert.equal(gets, 1, 'parent run-change must not trigger immediate recursive refresh')
    assert.equal(timers.size, 1)
    const poll = [...timers.values()][0]; timers.clear(); await poll(); await flush()
    assert.equal(gets, 2); assert.deepEqual(cursors, [0, 1])
    const cancel = find(root, node => node.type === 'button' && node.text === '取消任务')
    assert.ok(cancel); await cancel.props.onClick(); await flush()
    assert.equal(cancels, 1); assert.equal(run.value.terminal, true); assert.equal(timers.size, 0)
    assert.equal(find(root, node => node.type === 'button' && node.text === '取消任务'), undefined)
  } finally {
    app.unmount(); globalThis.setTimeout = previousSet; globalThis.clearTimeout = previousClear; delete globalThis.__runCardApi
  }
})
