const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const pinia = require('pinia');

function loadModule(relativePath, modules, globals = {}) {
  const source = fs.readFileSync(path.join(__dirname, '..', relativePath), 'utf8');
  const code = ts.transpileModule(source, { compilerOptions: {
    module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022
  } }).outputText;
  const sandbox = { exports: {}, require: name => {
    assert.ok(name in modules, `Unexpected dependency: ${name}`);
    return modules[name];
  }, window: { clearTimeout, setTimeout }, console, ...globals };
  vm.runInNewContext(code, sandbox, { filename: relativePath });
  return sandbox.exports;
}

const post = id => ({ id, title: `Post ${id}`, body: `Body ${id}`, mode: 'NORMAL',
  bodyFormat: 'PLAIN_TEXT', bodyDocument: null, conversionReady: false,
  authorUsername: 'member', authorUserId: 1, attachments: [], replies: [],
  createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' });
const tick = () => new Promise(resolve => setImmediate(resolve));

const fakeFile = index => ({ name: `file-${index}.bin`, size: index * 10, lastModified: index });

function setup(overrides = {}) {
  pinia.setActivePinia(pinia.createPinia());
  const pending = new Map();
  const mutations = [];
  const env = { confirm: () => true };
  const api = {
    getPost: id => new Promise((resolve, reject) => pending.set(id, { resolve, reject })),
    deletePost: async id => mutations.push(['delete', id]),
    updatePost: async id => { mutations.push(['update', id]); return post(id); },
    createReply: async id => { mutations.push(['reply', id]); return post(id); },
    ...overrides
  };
  const exports = loadModule('stores/postDetail.ts', {
    'pinia': pinia,
    '~/services/api': api,
    '~/utils/clipboard': {},
    '~/utils/post': { MAX_ATTACHMENTS: 5 },
    '~/utils/postDocument': {
      emptyPostDocument: () => ({ type: 'doc', content: [{ type: 'paragraph' }] }),
      resolvePostDocument: (bodyFormat, bodyDocument, plainBody) => (
        bodyFormat === 'TIPTAP_JSON' && bodyDocument ? bodyDocument : {
          type: 'doc',
          content: String(plainBody ?? '').split('\n').map(line => line
            ? { type: 'paragraph', content: [{ type: 'text', text: line }] }
            : { type: 'paragraph' })
        }
      ),
      collectInlineImageKeys: () => []
    },
    '~/composables/useInlineImageDraft': {
      INLINE_IMAGE_ATTACHMENT_COUNT_MESSAGE: 'combined-count-message'
    },
    './auth': { useAuthStore: () => ({ token: 'synthetic-test-token', userId: 1 }) },
    './posts': { usePostsStore: () => ({
      currentPage: 1, searchQuery: '', navigateToList: () => {}, loadPosts: async () => {}
    }) }
  }, { window: { clearTimeout, setTimeout, confirm: () => env.confirm() } });
  return { store: exports.usePostDetailStore(), pending, mutations, env };
}

test('a late response cannot replace the currently selected post or deletion target', async () => {
  const { store, pending, mutations } = setup();
  store.openDetail(1);
  store.resetListViewState();
  store.openDetail(2);
  pending.get(2).resolve(post(2));
  await tick();
  pending.get(1).resolve(post(1));
  await tick();
  assert.equal(store.selectedPostId, 2);
  assert.equal(store.selectedPost.id, 2);
  await store.handleDeletePost();
  assert.deepEqual(mutations, [['delete', 2]]);
});

test('failed detail loading clears stale content and blocks all post mutations', async () => {
  const { store, pending, mutations } = setup();
  store.openDetail(1);
  pending.get(1).resolve(post(1));
  await tick();
  store.openDetail(2);
  assert.equal(store.selectedPost, null);
  pending.get(2).reject(new Error('network failure'));
  await tick();
  assert.equal(store.selectedPost, null);
  assert.equal(store.detailLoading, false);
  await store.handleDeletePost();
  await store.handleUpdatePost();
  await store.handleCreateReply();
  assert.deepEqual(mutations, []);
});

test('a late failure cannot stop the newest request loading state', async () => {
  const { store, pending } = setup();
  store.openDetail(1);
  store.openDetail(2);
  pending.get(1).reject(new Error('stale error'));
  await tick();
  assert.equal(store.detailLoading, true);
  assert.equal(store.error, '');
  pending.get(2).resolve(post(2));
  await tick();
  assert.equal(store.selectedPost.id, 2);
});

test('returning to the list invalidates an in-flight request', async () => {
  const { store, pending } = setup();
  store.openDetail(1);
  store.resetListViewState();
  pending.get(1).resolve(post(1));
  await tick();
  assert.equal(store.view, 'list');
  assert.equal(store.selectedPost, null);
});

test('an old save response cannot replace another post opened during the save', async () => {
  let finishSave;
  const { store, pending } = setup({ updatePost: () => new Promise(resolve => { finishSave = resolve; }) });
  store.openDetail(1);
  pending.get(1).resolve(post(1));
  await tick();
  const saving = store.handleUpdatePost();
  store.openDetail(2);
  pending.get(2).resolve(post(2));
  await tick();
  finishSave(post(1));
  await saving;
  assert.equal(store.selectedPost.id, 2);
  assert.equal(store.selectedPostId, 2);
});

test('a displayed ID mismatch is blocked even if stale state is introduced', async () => {
  const { store, mutations } = setup();
  store.view = 'detail';
  store.selectedPostId = 2;
  store.selectedPost = post(1);
  await store.handleDeletePost();
  await store.handleUpdatePost();
  await store.handleCreateReply();
  assert.deepEqual(mutations, []);
});

test('ownership controls use account IDs and keep unresolved owners admin-only', () => {
  const { canManagePost } = loadModule('utils/post.ts', {});
  assert.equal(canManagePost(1, 1, 'USER'), true);
  assert.equal(canManagePost(1, 2, 'USER'), false);
  assert.equal(canManagePost(null, 2, 'USER'), false);
  assert.equal(canManagePost(null, 2, 'ADMIN'), true);
  assert.equal(canManagePost(1, null, 'ADMIN'), false);
});

test('create post blocks combined attachment and inline image overflow before the API call', async () => {
  const calls = [];
  const { store } = setup({ createPost: async input => { calls.push(input); return post(42); } });
  store.openWrite();
  store.postAttachmentConfirmed = true;
  store.postAttachmentFiles = [fakeFile(1), fakeFile(2), fakeFile(3), fakeFile(4)];
  const result = await store.handleCreatePost([
    { imageKey: 'key-1', file: fakeFile(5) },
    { imageKey: 'key-2', file: fakeFile(6) }
  ]);
  assert.equal(result, false);
  assert.deepEqual(calls, []);
  assert.equal(store.error, 'combined-count-message');
  assert.equal(store.postAttachmentFiles.length, 4);
});

test('create post forwards inline images and returns true after the success flow', async () => {
  const calls = [];
  const { store } = setup({ createPost: async input => { calls.push(input); return post(42); } });
  store.openWrite();
  store.postAttachmentConfirmed = true;
  store.postForm.title = 'inline title';
  const inlineImages = [
    { imageKey: 'key-1', file: fakeFile(5) },
    { imageKey: 'key-2', file: fakeFile(6) }
  ];
  const result = await store.handleCreatePost(inlineImages);
  assert.equal(result, true);
  assert.equal(calls.length, 1);
  assert.deepEqual(calls[0].inlineImages, inlineImages);
  assert.equal(calls[0].attachments.length, 0);
  assert.equal(store.postForm.title, '');
  assert.equal(store.message, '게시글을 등록했습니다.');
});

test('create post API failure returns false and keeps the draft untouched', async () => {
  const { store } = setup({ createPost: async () => {
    throw { code: 'SERVER_ERROR', status: 500, message: 'boom' };
  } });
  store.openWrite();
  store.postAttachmentConfirmed = true;
  store.postForm.title = 'draft title';
  store.postAttachmentFiles = [fakeFile(1)];
  const result = await store.handleCreatePost([{ imageKey: 'key-1', file: fakeFile(5) }]);
  assert.equal(result, false);
  assert.equal(store.error, 'boom');
  assert.equal(store.postForm.title, 'draft title');
  assert.equal(store.postAttachmentFiles.length, 1);
});

test('create post returns false when the upload confirmation is declined', async () => {
  const calls = [];
  const { store, env } = setup({ createPost: async input => { calls.push(input); return post(42); } });
  store.openWrite();
  env.confirm = () => false;
  const result = await store.handleCreatePost([{ imageKey: 'key-1', file: fakeFile(5) }]);
  assert.equal(result, false);
  assert.deepEqual(calls, []);
});
