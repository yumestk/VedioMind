import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { parse, compileScript } from '@vue/compiler-sfc'
import { createRenderer, h, nextTick } from 'vue'

test('saved note preserves edits on conflict, advances version on success, and disables withdrawn sources', async () => {
  const { descriptor } = parse(await readFile(new URL('../src/components/StudyNoteArtifactCard.vue', import.meta.url), 'utf8'))
  const script = compileScript(descriptor, { id: 'note-card-test', inlineTemplate: true })
  const artifact = { id: 'note', runId: 'r', version: 1, edited: false, content: { title: '笔记', coreConclusion: '结论', knowledgePoints: [{ title: '知识点', explanation: '解释', evidenceRefs: ['r'] }], gaps: [] }, coverageMaterialIds: [10], citations: [{ evidence: { ref: 'r', materialId: 10, startMs: 3000, text: '' }, available: false }] }
  let conflict = true, requests = []
  globalThis.__noteApi = {
    editStudyArtifact: async (id, edit) => { requests.push({ id, edit: JSON.parse(JSON.stringify(edit)) }); if (conflict) throw new Error('笔记已被修改，请刷新后再保存'); return { ...artifact, version: 2, edited: true, content: edit.content } },
    errorMessage: error => error.message
  }
  const code = script.content.replace(/import \{ editStudyArtifact \} from ['"]\.\.\/api\/media['"]/, 'const { editStudyArtifact } = globalThis.__noteApi')
    .replace(/import \{ errorMessage \} from ['"]\.\.\/api\/http['"]/, 'const { errorMessage } = globalThis.__noteApi')
    .replace(/from ["']vue["']/g, `from '${import.meta.resolve('vue')}'`)
  const component = (await import(`data:text/javascript,${encodeURIComponent(code)}`)).default
  const element = type => ({ type, tagName: type.toUpperCase(), children: [], props: {}, text: '', addEventListener() {}, getRootNode() { return globalThis.document } })
  const previousDocument = globalThis.document, previousDocumentType = globalThis.Document
  globalThis.Document = class Document { activeElement = null }; globalThis.document = new globalThis.Document()
  const renderer = createRenderer({ createElement: element, createText: text => ({ ...element('text'), text }), createComment: () => element('comment'),
    insert: (child, parent, anchor) => { if (child.parent) child.parent.children = child.parent.children.filter(x => x !== child); const index = anchor ? parent.children.indexOf(anchor) : -1; parent.children.splice(index < 0 ? parent.children.length : index, 0, child); child.parent = parent }, remove: child => { child.parent.children = child.parent.children.filter(x => x !== child) },
    setText: (node, text) => { node.text = text }, setElementText: (node, text) => { node.text = text; node.children = [] }, patchProp: (node, key, old, value) => { node.props[key] = value }, parentNode: node => node.parent, nextSibling: node => node.parent?.children[node.parent.children.indexOf(node) + 1] || null })
  const root = element('root'), app = renderer.createApp({ render: () => h(component, { artifact }) })
  const find = (node, predicate) => predicate(node) ? node : node.children.map(c => find(c, predicate)).find(Boolean)
  const button = text => find(root, n => n.type === 'button' && n.text === text)
  const flush = async () => { for (let i = 0; i < 6; i++) { await Promise.resolve(); await nextTick() } }
  try {
    app.mount(root); await flush()
    assert.equal(find(root, n => n.type === 'button' && n.text.includes('来源失效')).props.disabled, true)
    await button('编辑笔记').props.onClick(); await flush()
    const title = find(root, n => n.type === 'input')
    title.props['onUpdate:modelValue']('我的修改'); await flush()
    await button('保存修改').props.onClick(); await flush()
    assert.equal(requests[0].edit.baseVersion, 1); assert.equal(requests[0].edit.content.title, '我的修改')
    assert.ok(button('保存修改'), 'conflict keeps the editor and draft open')
    assert.ok(find(root, n => n.props.role === 'alert' && n.text.includes('刷新')))
    conflict = false
    await button('保存修改').props.onClick(); await flush()
    assert.ok(button('编辑笔记'))
    assert.ok(find(root, n => n.type === 'strong' && n.text.includes('版本 2')))
    assert.ok(find(root, n => n.type === 'h2' && n.text === '我的修改'))
  } finally { app.unmount(); globalThis.document = previousDocument; globalThis.Document = previousDocumentType; delete globalThis.__noteApi }
})
