const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const vue = require('vue');

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

const postDocument = () => loadModule('utils/postDocument.ts', {
  'js-base64': require('js-base64')
});

const draftModule = (globals = {}) => loadModule('composables/useInlineImageDraft.ts', {
  'vue': vue,
  '~/utils/postDocument': postDocument()
}, globals);

const clone = value => JSON.parse(JSON.stringify(value));

const K_A = 'aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa';
const K_B = 'bbbbbbbb-2222-4222-8222-bbbbbbbbbbbb';
const K_C = 'cccccccc-3333-4333-8333-cccccccccccc';
const K_D = 'dddddddd-4444-4444-8444-dddddddddddd';

const fakeFile = (name, size, type, lastModified = 1000) => ({ name, size, type, lastModified });
const imageNode = (imageKey, alt = '캡처') => ({ type: 'inlineAttachmentImage', attrs: { imageKey, alt } });
const clipboardItem = (kind, type, file) => ({ kind, type, getAsFile: () => file ?? null });

const sequentialCrypto = (uuids) => {
  let index = 0;
  return { randomUUID: () => uuids[Math.min(index++, uuids.length - 1)] };
};

function makeDraft(mod, options = {}) {
  const urls = [];
  const revoked = [];
  let urlSeq = 0;
  let uuidSeq = 0;
  const draft = mod.useInlineImageDraft({
    confirmUpload: () => true,
    crypto: { randomUUID: () => `${String(++uuidSeq).padStart(8, '0')}-0000-4000-8000-000000000000` },
    now: () => 1700000000123,
    createObjectURL: () => { const url = `blob:draft-${urlSeq++}`; urls.push(url); return url; },
    revokeObjectURL: url => { revoked.push(url); },
    renameFile: (file, name) => ({ ...file, name }),
    ...options
  });
  return { draft, urls, revoked };
}

test('clipboard scan extracts PNG/JPEG files, keeps text paste untouched, marks unsupported image types', () => {
  const { readClipboardImageFiles } = draftModule();
  const png = fakeFile('shot.png', 10, 'image/png');
  const jpg = fakeFile('photo.jpg', 20, 'image/jpeg');
  const mixed = readClipboardImageFiles({ items: [
    clipboardItem('string', 'text/plain'),
    clipboardItem('file', 'image/png', png),
    clipboardItem('file', 'image/jpeg', jpg)
  ] });
  assert.equal(mixed.hasImage, true);
  assert.deepEqual(clone(mixed.files), clone([png, jpg]));
  assert.deepEqual(clone(mixed.unsupportedTypes), []);

  const textOnly = readClipboardImageFiles({ items: [
    clipboardItem('string', 'text/plain'),
    clipboardItem('file', 'text/plain', fakeFile('note.txt', 5, 'text/plain'))
  ] });
  assert.equal(textOnly.hasImage, false);
  assert.equal(textOnly.files.length, 0);
  assert.equal(readClipboardImageFiles(null).hasImage, false);

  const gif = readClipboardImageFiles({ items: [
    clipboardItem('file', 'image/gif', fakeFile('anim.gif', 5, 'image/gif'))
  ] });
  assert.equal(gif.hasImage, true);
  assert.equal(gif.files.length, 0);
  assert.deepEqual(clone(gif.unsupportedTypes), ['image/gif']);

  const fallback = readClipboardImageFiles({ items: [
    { kind: 'file', type: '', getAsFile: () => fakeFile('f.png', 1, 'image/png') }
  ] });
  assert.equal(fallback.files.length, 1);
});

test('createUuidV4 prefers randomUUID and falls back to RFC 4122 getRandomValues bytes', () => {
  const mod = draftModule({
    Math: Object.create(Math, { random: { value: () => { throw new Error('Math.random must not be used'); } } })
  });
  assert.equal(mod.createUuidV4({ randomUUID: () => 'fixed-uuid' }), 'fixed-uuid');
  const deterministic = mod.createUuidV4({ getRandomValues: array => {
    for (let index = 0; index < array.length; index += 1) array[index] = index * 17;
    return array;
  } });
  assert.equal(deterministic, '00112233-4455-4677-8899-aabbccddeeff');
  assert.match(deterministic, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
});

test('declined confirmation creates nothing; accepted files get inline-image names', () => {
  const mod = draftModule();
  const { draft, urls } = makeDraft(mod, { confirmUpload: () => false });
  const declined = draft.register([fakeFile('a.png', 10, 'image/png')]);
  assert.equal(declined.entries.length, 0);
  assert.equal(declined.error, null);
  assert.equal(urls.length, 0);
  assert.equal(draft.entries.value.length, 0);

  const accepted = makeDraft(mod, { crypto: sequentialCrypto([K_A, K_B]) });
  const result = accepted.draft.register([
    fakeFile('shot.png', 10, 'image/png', 777),
    fakeFile('photo.jpeg', 20, 'image/jpeg', 888)
  ]);
  assert.equal(result.error, null);
  assert.equal(result.entries.length, 2);
  assert.equal(result.entries[0].imageKey, K_A);
  assert.equal(result.entries[0].file.name, 'inline-image-1700000000123-aaaaaaaa.png');
  assert.equal(result.entries[0].file.size, 10);
  assert.equal(result.entries[0].file.type, 'image/png');
  assert.equal(result.entries[0].file.lastModified, 777);
  assert.equal(result.entries[1].file.name, 'inline-image-1700000000123-bbbbbbbb.jpg');
  assert.equal(result.entries[1].file.type, 'image/jpeg');
  assert.deepEqual(accepted.urls, [result.entries[0].objectUrl, result.entries[1].objectUrl]);
});

test('generated key collisions retry until every key is distinct', () => {
  const mod = draftModule();
  const { draft } = makeDraft(mod, { crypto: sequentialCrypto([K_A, K_A, K_B, K_C, K_D]) });
  const result = draft.register([
    fakeFile('a.png', 1, 'image/png'),
    fakeFile('b.png', 1, 'image/png'),
    fakeFile('c.png', 1, 'image/png')
  ]);
  assert.equal(result.error, null);
  assert.deepEqual(clone(result.entries).map(entry => entry.imageKey), [K_A, K_B, K_C]);
  const second = draft.register([fakeFile('d.png', 1, 'image/png')]);
  assert.equal(second.error, null);
  assert.equal(second.entries[0].imageKey, K_D);
  assert.equal(new Set(draft.entries.value.map(entry => entry.imageKey)).size, 4);
});

test('per-file size boundary accepts 10MiB and rejects anything above', () => {
  const mod = draftModule();
  const { draft } = makeDraft(mod);
  const max = 10 * 1024 * 1024;
  const accepted = draft.register([
    fakeFile('under.png', max - 1, 'image/png'),
    fakeFile('exact.png', max, 'image/jpeg')
  ]);
  assert.equal(accepted.error, null);
  assert.equal(accepted.entries.length, 2);
  const rejected = draft.register([fakeFile('over.png', max + 1, 'image/png')]);
  assert.equal(rejected.error, '본문 이미지는 파일당 최대 10MB까지 추가할 수 있습니다.');
  assert.equal(rejected.entries.length, 0);
  assert.equal(draft.entries.value.length, 2);
});

test('registry limits enforce 20 entries and 100MiB total without real allocation', () => {
  const mod = draftModule();
  const countDraft = makeDraft(mod);
  const twenty = Array.from({ length: 20 }, (_, index) => fakeFile(`f${index}.png`, 1, 'image/png'));
  assert.equal(countDraft.draft.register(twenty).error, null);
  assert.equal(countDraft.draft.entries.value.length, 20);
  const overflow = countDraft.draft.register([fakeFile('x.png', 1, 'image/png')]);
  assert.equal(overflow.error, '본문 이미지는 편집 세션당 최대 20개까지 보관할 수 있습니다. 글을 저장하거나 편집을 취소한 뒤 다시 시도해 주세요.');
  assert.equal(overflow.entries.length, 0);

  const bytesDraft = makeDraft(mod);
  const mib = 1024 * 1024;
  const hundred = Array.from({ length: 10 }, (_, index) => fakeFile(`big${index}.png`, 10 * mib, 'image/png'));
  assert.equal(bytesDraft.draft.register(hundred).error, null);
  assert.equal(bytesDraft.draft.totalBytes.value, 100 * mib);
  const tooLarge = bytesDraft.draft.register([fakeFile('tiny.png', 1, 'image/png')]);
  assert.equal(tooLarge.error, '본문 이미지 임시 보관 용량은 최대 100MB입니다. 글을 저장하거나 편집을 취소한 뒤 다시 시도해 주세요.');
  assert.equal(tooLarge.entries.length, 0);
});

test('buildUploads follows document order, keeps deleted pending entries, and reports missing keys', () => {
  const mod = draftModule();
  const { draft } = makeDraft(mod, { crypto: sequentialCrypto([K_A, K_B]) });
  draft.register([fakeFile('a.png', 1, 'image/png'), fakeFile('b.png', 1, 'image/png')]);

  const onlyB = { type: 'doc', content: [imageNode(K_B)] };
  const built = draft.buildUploads(onlyB);
  assert.equal(built.error, null);
  assert.deepEqual(clone(built.inlineImages).map(upload => upload.imageKey), [K_B]);
  assert.equal(draft.entries.value.length, 2);
  assert.equal(draft.inlineSources.value[K_A], draft.entries.value[0].objectUrl);

  const ordered = { type: 'doc', content: [
    imageNode(K_B),
    { type: 'paragraph', content: [{ type: 'text', text: 'between' }] },
    imageNode(K_A)
  ] };
  assert.deepEqual(clone(draft.buildUploads(ordered).inlineImages).map(upload => upload.imageKey), [K_B, K_A]);

  const missing = draft.buildUploads({ type: 'doc', content: [imageNode(K_C)] });
  assert.equal(missing.error, '본문 이미지 임시 파일을 찾을 수 없습니다. 이미지를 다시 추가해 주세요.');
  assert.equal(missing.inlineImages.length, 0);
});

test('buildUploads retains existing keys without uploads and still rejects unknown keys', () => {
  const mod = draftModule();
  const { draft } = makeDraft(mod, { crypto: sequentialCrypto([K_A, K_B]) });
  draft.register([fakeFile('a.png', 1, 'image/png'), fakeFile('b.png', 1, 'image/png')]);

  const mixed = { type: 'doc', content: [imageNode(K_C), imageNode(K_B)] };
  const built = draft.buildUploads(mixed, [K_C]);
  assert.equal(built.error, null);
  assert.deepEqual(clone(built.inlineImages).map(upload => upload.imageKey), [K_B]);
  assert.equal(draft.entries.value.length, 2);

  const retainedOnly = draft.buildUploads({ type: 'doc', content: [imageNode(K_C)] }, [K_C]);
  assert.equal(retainedOnly.error, null);
  assert.equal(retainedOnly.inlineImages.length, 0);

  const deletedPending = draft.buildUploads({ type: 'doc', content: [imageNode(K_C), imageNode(K_A)] }, [K_C]);
  assert.equal(deletedPending.error, null);
  assert.deepEqual(clone(deletedPending.inlineImages).map(upload => upload.imageKey), [K_A]);
  assert.equal(draft.entries.value.length, 2);

  const unknown = draft.buildUploads({ type: 'doc', content: [imageNode(K_D)] }, [K_C]);
  assert.equal(unknown.error, '본문 이미지 임시 파일을 찾을 수 없습니다. 이미지를 다시 추가해 주세요.');
  assert.equal(unknown.inlineImages.length, 0);
});

test('reserved existing keys are excluded from generated pending keys', () => {
  const mod = draftModule();
  const { draft } = makeDraft(mod, {
    crypto: sequentialCrypto([K_A, K_B]),
    reservedImageKeys: () => [K_A]
  });
  const result = draft.register([fakeFile('a.png', 1, 'image/png')]);
  assert.equal(result.error, null);
  assert.equal(result.entries[0].imageKey, K_B);
});

test('clear revokes every URL exactly once and stays idempotent', () => {
  const mod = draftModule();
  const { draft, urls, revoked } = makeDraft(mod);
  draft.register([fakeFile('a.png', 1, 'image/png'), fakeFile('b.png', 1, 'image/png')]);
  assert.equal(urls.length, 2);
  draft.clear();
  draft.clear();
  assert.deepEqual([...revoked].sort(), [...urls].sort());
  assert.equal(revoked.length, 2);
  assert.equal(draft.entries.value.length, 0);
  assert.equal(draft.totalBytes.value, 0);
});

test('manifest maps uploads to their file indices and rejects duplicate keys', () => {
  const { buildInlineImageManifest } = draftModule();
  assert.deepEqual(clone(buildInlineImageManifest([
    { imageKey: K_A, file: fakeFile('a.png', 1, 'image/png') },
    { imageKey: K_B, file: fakeFile('b.png', 1, 'image/png') }
  ])), [
    { imageKey: K_A, fileIndex: 0 },
    { imageKey: K_B, fileIndex: 1 }
  ]);
  assert.throws(() => buildInlineImageManifest([
    { imageKey: K_A, file: fakeFile('a.png', 1, 'image/png') },
    { imageKey: K_A, file: fakeFile('dup.png', 1, 'image/png') }
  ]));
});

test('collectInlineImageKeys walks nested content in document order and rejects invalid documents', () => {
  const { collectInlineImageKeys } = postDocument();
  const nested = { type: 'doc', content: [
    { type: 'paragraph', content: [{ type: 'text', text: 'start' }] },
    imageNode(K_A),
    { type: 'blockquote', content: [imageNode(K_B)] },
    { type: 'bulletList', content: [
      { type: 'listItem', content: [
        { type: 'paragraph', content: [{ type: 'text', text: 'item' }] },
        imageNode(K_C)
      ] }
    ] },
    imageNode(K_D)
  ] };
  assert.deepEqual(clone(collectInlineImageKeys(nested)), [K_A, K_B, K_C, K_D]);
  assert.equal(collectInlineImageKeys({ type: 'doc', content: [imageNode(K_A), imageNode(K_A)] }).length, 0);
  assert.equal(collectInlineImageKeys({ type: 'doc', content: [{ type: 'video' }] }).length, 0);
  assert.equal(collectInlineImageKeys(null).length, 0);
  assert.equal(collectInlineImageKeys('text').length, 0);
});

test('resolveInlineImageType normalizes MIME aliases and falls back to PNG/JPEG extensions', () => {
  const { resolveInlineImageType } = draftModule();
  assert.equal(resolveInlineImageType(fakeFile('a.png', 1, 'image/png')), 'image/png');
  assert.equal(resolveInlineImageType(fakeFile('a.jpg', 1, 'image/jpeg')), 'image/jpeg');
  assert.equal(resolveInlineImageType(fakeFile('a.jpg', 1, 'image/jpg')), 'image/jpeg');
  assert.equal(resolveInlineImageType(fakeFile('a.jpg', 1, 'image/pjpeg')), 'image/jpeg');
  assert.equal(resolveInlineImageType(fakeFile('a.png', 1, 'image/x-png')), 'image/png');
  assert.equal(resolveInlineImageType(fakeFile('shot.PNG', 1, '')), 'image/png');
  assert.equal(resolveInlineImageType(fakeFile('photo.JPG', 1, '')), 'image/jpeg');
  assert.equal(resolveInlineImageType(fakeFile('photo.jpeg', 1, 'application/octet-stream')), 'image/jpeg');
  assert.equal(resolveInlineImageType(fakeFile('anim.gif', 1, 'image/gif')), null);
  assert.equal(resolveInlineImageType(fakeFile('anim.gif', 1, '')), null);
  assert.equal(resolveInlineImageType(fakeFile('noext', 1, '')), null);
  assert.equal(resolveInlineImageType(fakeFile('g.png.exe', 1, '')), null);
});

test('register accepts MIME alias and extension fallback files with normalized names', () => {
  const mod = draftModule();
  const alias = makeDraft(mod).draft.register([fakeFile('photo.JPG', 20, 'image/jpg')]);
  assert.equal(alias.error, null);
  assert.ok(alias.entries[0].file.name.endsWith('.jpg'));

  const fallback = makeDraft(mod).draft.register([fakeFile('shot.PNG', 10, '')]);
  assert.equal(fallback.error, null);
  assert.ok(fallback.entries[0].file.name.endsWith('.png'));
});

test('register rejects unsupported image types with the shared PNG/JPEG message', () => {
  const mod = draftModule();
  const { draft } = makeDraft(mod);
  const rejected = draft.register([fakeFile('anim.gif', 5, 'image/gif')]);
  assert.equal(rejected.error, '본문 이미지는 PNG 또는 JPEG 파일만 추가할 수 있습니다.');
  assert.equal(rejected.entries.length, 0);
  assert.equal(draft.entries.value.length, 0);
});
