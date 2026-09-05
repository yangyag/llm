const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const pinia = require('pinia');

function loadModule(relativePath, modules) {
  const source = fs.readFileSync(path.join(__dirname, '..', relativePath), 'utf8');
  const code = ts.transpileModule(source, { compilerOptions: {
    module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022
  } }).outputText;
  const sandbox = { exports: {}, require: name => {
    assert.ok(name in modules, `Unexpected dependency: ${name}`);
    return modules[name];
  }, window: { clearTimeout, setTimeout }, console };
  vm.runInNewContext(code, sandbox, { filename: relativePath });
  return sandbox.exports;
}

const post = id => ({ id, title: `Post ${id}`, body: `Body ${id}`, mode: 'NORMAL',
  authorUsername: 'member', authorUserId: 1, attachments: [], replies: [] });
const tick = () => new Promise(resolve => setImmediate(resolve));

function setup(overrides = {}) {
  pinia.setActivePinia(pinia.createPinia());
  const pending = new Map();
  const mutations = [];
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
    './auth': { useAuthStore: () => ({ token: 'synthetic-test-token', userId: 1 }) },
    './posts': { usePostsStore: () => ({ currentPage: 1, loadPosts: async () => {} }) }
  });
  return { store: exports.usePostDetailStore(), pending, mutations };
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
