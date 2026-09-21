const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

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

const utils = () => loadModule('utils/post.ts', {});

const file = name => new File([`content-${name}`], name, { type: 'text/plain' });

test('mergeAttachmentFiles applies 0/1/max boundary and truncates the sixth', () => {
  const { mergeAttachmentFiles } = utils();
  const empty = mergeAttachmentFiles([], []);
  assert.equal(empty.files.length, 0);
  assert.equal(empty.truncated, false);

  const single = mergeAttachmentFiles([], [file('a.txt')]);
  assert.equal(single.files.length, 1);
  assert.equal(single.truncated, false);

  const atLimit = mergeAttachmentFiles(
    [file('a.txt'), file('b.txt'), file('c.txt'), file('d.txt')],
    [file('e.txt')]
  );
  assert.equal(atLimit.files.length, 5);
  assert.equal(atLimit.truncated, false);

  const overLimit = mergeAttachmentFiles(
    [file('a.txt'), file('b.txt'), file('c.txt'), file('d.txt'), file('e.txt')],
    [file('f.txt')]
  );
  assert.equal(overLimit.files.length, 5);
  assert.equal(overLimit.truncated, true);
  assert.deepEqual([...overLimit.files.map(f => f.name)], ['a.txt', 'b.txt', 'c.txt', 'd.txt', 'e.txt']);
});

test('attachment upload confirmation states public exposure and asks a question', () => {
  const { ATTACHMENT_ENVIRONMENT_CONFIRM_MESSAGE } = utils();
  assert.ok(ATTACHMENT_ENVIRONMENT_CONFIRM_MESSAGE.includes('게시글과 함께 공개'));
  assert.ok(ATTACHMENT_ENVIRONMENT_CONFIRM_MESSAGE.includes('?'));
});
