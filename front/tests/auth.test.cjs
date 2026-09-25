const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const pinia = require('pinia');

function createStorage(initial = {}) {
  const values = new Map(Object.entries(initial));
  return {
    getItem: key => (values.has(key) ? values.get(key) : null),
    setItem: (key, value) => values.set(key, String(value)),
    removeItem: key => values.delete(key),
    values
  };
}

// stores/auth.ts는 import.meta.client로 브라우저 여부를 본다. CommonJS sandbox에서는 true로 바꿔 불러온다.
function loadAuthStore(api, localStorage) {
  const source = fs.readFileSync(path.join(__dirname, '..', 'stores/auth.ts'), 'utf8')
    .replaceAll('import.meta.client', 'true');
  const code = ts.transpileModule(source, { compilerOptions: {
    module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022
  } }).outputText;
  const modules = { pinia, '~/services/api': api };
  const sandbox = { exports: {}, require: name => {
    assert.ok(name in modules, `Unexpected dependency: ${name}`);
    return modules[name];
  }, localStorage, console };
  vm.runInNewContext(code, sandbox, { filename: 'stores/auth.ts' });
  return sandbox.exports;
}

function setup(getMe) {
  pinia.setActivePinia(pinia.createPinia());
  const localStorage = createStorage({
    auth_token: 'stored-token', auth_username: 'claude', auth_user_id: '4', auth_role: 'USER'
  });
  const { useAuthStore } = loadAuthStore({ getMe, login: async () => { throw new Error('unused'); } }, localStorage);
  const auth = useAuthStore();
  auth.hydrate();
  return { auth, localStorage };
}

test('fetchMe keeps the stored session when the check fails for a non-auth reason', async () => {
  for (const failure of [
    { code: null, status: 0, message: 'Request failed: 0' },
    { code: 'INTERNAL_ERROR', status: 500, message: 'unexpected server error' },
    { code: null, status: 502, message: 'Request failed: 502' },
    new TypeError('fetch failed')
  ]) {
    const { auth, localStorage } = setup(async () => { throw failure; });
    await auth.fetchMe();

    assert.equal(auth.checked, true);
    assert.equal(auth.token, 'stored-token');
    assert.equal(auth.username, 'claude');
    assert.equal(auth.userId, 4);
    assert.equal(auth.role, 'USER');
    assert.equal(localStorage.getItem('auth_token'), 'stored-token');
  }
});

test('fetchMe logs out when the token is rejected with 401', async () => {
  const { auth, localStorage } = setup(async () => {
    throw { code: 'INVALID_CREDENTIALS', status: 401, message: 'User no longer exists' };
  });
  await auth.fetchMe();

  assert.equal(auth.checked, true);
  assert.equal(auth.token, null);
  assert.equal(auth.role, null);
  assert.equal(localStorage.getItem('auth_token'), null);
  assert.equal(localStorage.getItem('auth_role'), null);
});

test('fetchMe refreshes the account from the server on success', async () => {
  const { auth, localStorage } = setup(async token => {
    assert.equal(token, 'stored-token');
    return { userId: 4, username: 'claude', role: 'ADMIN' };
  });
  await auth.fetchMe();

  assert.equal(auth.checked, true);
  assert.equal(auth.role, 'ADMIN');
  assert.equal(localStorage.getItem('auth_user_id'), '4');
});
