const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const { getSchema, Node } = require('@tiptap/core');
const StarterKit = require('@tiptap/starter-kit').default;
const { EditorState, NodeSelection, TextSelection } = require('@tiptap/pm/state');

function loadModule(relativePath, modules) {
  const source = fs.readFileSync(path.join(__dirname, '..', relativePath), 'utf8');
  const code = ts.transpileModule(source, { compilerOptions: {
    module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022
  } }).outputText;
  const sandbox = { exports: {}, require: name => {
    assert.ok(name in modules, `Unexpected dependency: ${name}`);
    return modules[name];
  }, console };
  vm.runInNewContext(code, sandbox, { filename: relativePath });
  return sandbox.exports;
}

const utils = loadModule('utils/postDocument.ts', { 'js-base64': require('js-base64') });
const normalizer = loadModule('components/post/postDocumentNormalizer.ts', {
  '@tiptap/core': require('@tiptap/core'),
  '@tiptap/pm/state': require('@tiptap/pm/state'),
  '@tiptap/pm/transform': require('@tiptap/pm/transform'),
  '~/utils/postDocument': utils
});

// components/post/inlineAttachmentImage.ts와 같은 스키마(NodeView 제외).
const InlineAttachmentImage = Node.create({
  name: 'inlineAttachmentImage',
  group: 'block',
  atom: true,
  addAttributes() {
    return { imageKey: { default: null }, alt: { default: '' } };
  }
});
const schema = getSchema([StarterKit.configure({ link: false }), InlineAttachmentImage]);

const KEY_A = '11111111-1111-4111-8111-111111111111';
const paragraph = text => text ? { type: 'paragraph', content: [{ type: 'text', text }] } : { type: 'paragraph' };
const image = (imageKey, alt = '') => ({ type: 'inlineAttachmentImage', attrs: { imageKey, alt } });
const orderedList = attrs => ({ type: 'orderedList', attrs, content: [{ type: 'listItem', content: [paragraph('item')] }] });
const node = json => schema.nodeFromJSON(json);

function editorState(content) {
  return EditorState.create({
    schema,
    doc: node({ type: 'doc', content }),
    plugins: [normalizer.createPostDocumentNormalizerPlugin()]
  });
}

function apply(state, mutate) {
  const tr = state.tr;
  mutate(tr);
  const { state: next, transactions } = state.applyTransaction(tr);
  next.doc.check();
  return { next, appended: transactions.slice(1) };
}

const canonical = state => utils.toCanonicalPostDocument(state.doc.toJSON());
const images = state => {
  const found = [];
  state.doc.descendants(child => {
    if (child.type.name === 'inlineAttachmentImage') found.push({ ...child.attrs });
  });
  return found;
};

test('"0. " 입력으로 만든 시작 번호 0 목록을 1로 고쳐 저장 형식을 유지한다', () => {
  const state = editorState([paragraph('x')]);
  const { next } = apply(state, tr => tr.replaceWith(0, tr.doc.content.size, node(orderedList({ start: 0, type: null }))));
  assert.deepEqual({ ...next.doc.firstChild.attrs }, { start: 1, type: null });
  assert.notEqual(canonical(next), null);
});

test('붙여넣은 번호 목록의 type 속성과 범위 밖 시작 번호를 고친다', () => {
  const cases = [
    [{ start: 1, type: 'a' }, { start: 1, type: null }],
    [{ start: 2_000_000, type: null }, { start: 1_000_000, type: null }],
    [{ start: Number.NaN, type: 'i' }, { start: 1, type: null }]
  ];
  for (const [input, expected] of cases) {
    const { next } = apply(editorState([paragraph('x')]), tr => tr.insert(tr.doc.content.size, node(orderedList(input))));
    assert.deepEqual({ ...next.doc.lastChild.attrs }, expected);
    assert.notEqual(canonical(next), null);
  }
});

test('허용하지 않는 코드 언어는 지우고 허용 언어는 유지한다', () => {
  const code = language => ({ type: 'codeBlock', attrs: { language }, content: [{ type: 'text', text: 'x' }] });
  const invalid = apply(editorState([paragraph('x')]), tr => tr.insert(tr.doc.content.size, node(code('c/c++'))));
  assert.equal(invalid.next.doc.lastChild.attrs.language, null);
  assert.notEqual(canonical(invalid.next), null);
  const valid = apply(editorState([paragraph('x')]), tr => tr.insert(tr.doc.content.size, node(code('typescript'))));
  assert.equal(valid.next.doc.lastChild.attrs.language, 'typescript');
  assert.equal(valid.appended.length, 0);
});

test('편집기 안에서 복사한 이미지는 기존 이미지를 남기고 사본을 제외한다', () => {
  const state = editorState([image(KEY_A, 'original'), paragraph('after')]);
  // 기존 이미지 앞에 같은 키의 사본을 붙여넣은 상황.
  const { next, appended } = apply(state, tr => tr.insert(0, node(image(KEY_A, 'copy'))));
  assert.deepEqual(images(next), [{ imageKey: KEY_A, alt: 'original' }]);
  assert.deepEqual([...normalizer.readNormalizeIssues(appended)], ['duplicate-image']);
  assert.notEqual(canonical(next), null);
});

test('선택된 채 제외된 사본 자리에 커서를 두어 다음 입력이 원본 이미지를 지우지 않는다', () => {
  const state = editorState([paragraph('before'), image(KEY_A, 'original')]);
  // 문서 끝에 붙여넣은 사본이 NodeSelection으로 선택된 상황.
  const { next } = apply(state, tr => {
    const end = tr.doc.content.size;
    tr.insert(end, node(image(KEY_A, 'copy')));
    tr.setSelection(NodeSelection.create(tr.doc, end));
  });
  assert.ok(next.selection instanceof TextSelection);
  assert.equal(next.selection.$from.parent.type.name, 'paragraph');
  assert.equal(next.selection.$from.parent.content.size, 0);
  const { next: typed } = apply(next, tr => tr.insertText('x'));
  assert.deepEqual(images(typed), [{ imageKey: KEY_A, alt: 'original' }]);
  assert.notEqual(canonical(typed), null);
});

test('인용문의 유일한 내용이던 중복 이미지는 빈 문단으로 바꿔 문서 구조를 유지한다', () => {
  const state = editorState([image(KEY_A), paragraph('x')]);
  const { next } = apply(state, tr => tr.insert(tr.doc.content.size, node({ type: 'blockquote', content: [image(KEY_A)] })));
  assert.equal(images(next).length, 1);
  assert.deepEqual(next.doc.lastChild.toJSON(), { type: 'blockquote', content: [{ type: 'paragraph' }] });
  assert.notEqual(canonical(next), null);
});

test('키가 없거나 UUID가 아닌 이미지는 제외한다', () => {
  for (const imageKey of [null, 'not-a-uuid']) {
    const { next, appended } = apply(editorState([paragraph('x')]), tr => tr.insert(tr.doc.content.size, node(image(imageKey))));
    assert.deepEqual(images(next), []);
    assert.deepEqual([...normalizer.readNormalizeIssues(appended)], ['invalid-image']);
    assert.notEqual(canonical(next), null);
  }
});

test('200자를 넘는 alt는 서로게이트 쌍을 나누지 않고 자른다', () => {
  const alt = 'a'.repeat(199) + '😀' + 'b';
  const { next } = apply(editorState([paragraph('x')]), tr => tr.insert(tr.doc.content.size, node(image(KEY_A, alt))));
  assert.equal(images(next)[0].alt, 'a'.repeat(199));
  assert.notEqual(canonical(next), null);
});

test('저장 형식에 맞는 입력은 추가 트랜잭션을 만들지 않는다', () => {
  const state = editorState([orderedList({ start: 3, type: null }), image(KEY_A, 'alt'), paragraph('x')]);
  const { appended } = apply(state, tr => tr.insertText('y', tr.doc.content.size - 1));
  assert.equal(appended.length, 0);
  assert.equal(normalizer.normalizePostDocumentTransaction(state), null);
});
