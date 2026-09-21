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

const utils = () => loadModule('utils/postDocument.ts', {
  'js-base64': require('js-base64')
});

const clone = value => JSON.parse(JSON.stringify(value));

const IMAGE_KEY = '11111111-1111-4111-8111-111111111111';

const runtimeDoc = {
  type: 'doc',
  runtime: true,
  content: [
    {
      type: 'paragraph',
      attrs: { align: 'center' },
      style: 'color:red',
      content: [
        {
          type: 'text',
          text: '본문',
          marks: [
            { type: 'italic', attrs: { level: 1 } },
            { type: 'bold' }
          ],
          runtime: 'x'
        }
      ]
    },
    {
      type: 'inlineAttachmentImage',
      src: 'https://example.invalid/node-src',
      attrs: {
        imageKey: IMAGE_KEY,
        alt: '캡처',
        src: 'blob:local',
        runtimeSrc: 'https://example.invalid/image'
      }
    }
  ]
};

const canonicalDoc = {
  type: 'doc',
  content: [
    {
      type: 'paragraph',
      content: [
        { type: 'text', text: '본문', marks: [{ type: 'bold' }, { type: 'italic' }] }
      ]
    },
    {
      type: 'inlineAttachmentImage',
      attrs: { imageKey: IMAGE_KEY, alt: '캡처' }
    }
  ]
};

const attachment = (id, attachmentKind, inlineKey, contentUrl) => ({
  id,
  originalFilename: `file-${id}`,
  size: 128,
  contentType: 'application/octet-stream',
  attachmentKind,
  inlineKey,
  downloadUrl: `/api/v1/posts/1/attachments/${id}`,
  contentUrl
});

test('plain text conversion preserves every line break', () => {
  const { emptyPostDocument, plainTextToPostDocument } = utils();
  assert.deepEqual(clone(emptyPostDocument()), { type: 'doc', content: [{ type: 'paragraph' }] });
  assert.deepEqual(clone(plainTextToPostDocument('첫 줄\n\n셋째\r\n넷째\n')), {
    type: 'doc',
    content: [
      { type: 'paragraph', content: [{ type: 'text', text: '첫 줄' }] },
      { type: 'paragraph' },
      { type: 'paragraph', content: [{ type: 'text', text: '셋째' }] },
      { type: 'paragraph', content: [{ type: 'text', text: '넷째' }] },
      { type: 'paragraph' }
    ]
  });
});

test('canonical serializer removes runtime-only image data and normalizes marks', () => {
  const { toCanonicalPostDocument } = utils();
  assert.deepEqual(clone(toCanonicalPostDocument(runtimeDoc)), canonicalDoc);
  assert.equal(toCanonicalPostDocument({ type: 'doc', content: [{ type: 'video' }] }), null);
  assert.equal(toCanonicalPostDocument(null), null);
  assert.equal(toCanonicalPostDocument('본문'), null);
});

test('type guard and rich resolver fall back safely to plain text', () => {
  const { isPostDocument, resolvePostDocument, plainTextToPostDocument } = utils();
  assert.equal(isPostDocument(canonicalDoc), true);
  assert.equal(isPostDocument([]), false);
  assert.equal(isPostDocument(null), false);
  assert.equal(isPostDocument({ type: 'video' }), false);
  assert.deepEqual(clone(resolvePostDocument('TIPTAP_JSON', runtimeDoc, 'fallback')), canonicalDoc);
  const fallback = clone(plainTextToPostDocument('대체\n본문'));
  assert.deepEqual(clone(resolvePostDocument('TIPTAP_JSON', { type: 'video' }, '대체\n본문')), fallback);
  assert.deepEqual(clone(resolvePostDocument('PLAIN_TEXT', runtimeDoc, '대체\n본문')), fallback);
});

test('inline source resolver and download filter separate attachment roles', () => {
  const { buildInlineImageSources, filterDownloadAttachments } = utils();
  const attachments = [
    attachment(1, 'DOWNLOAD', null, null),
    attachment(2, 'INLINE_IMAGE', IMAGE_KEY, '/api/v1/posts/1/attachments/2/content'),
    attachment(3, 'INLINE_IMAGE', null, '/api/v1/posts/1/attachments/3/content'),
    attachment(4, 'INLINE_IMAGE', '22222222-2222-4222-8222-222222222222', null)
  ];
  const original = [...attachments];
  assert.deepEqual(clone(buildInlineImageSources(attachments)), {
    [IMAGE_KEY]: '/api/v1/posts/1/attachments/2/content'
  });
  assert.deepEqual(clone(filterDownloadAttachments(attachments)), [attachments[0]]);
  assert.deepEqual(attachments, original);
});

test('inline source resolver only trusts the server content endpoint shape', () => {
  const { buildInlineImageSources } = utils();
  const key = suffix => `33333333-3333-4333-8333-3333333333${suffix}`;
  const validKey = key('00');
  const attachments = [
    attachment(10, 'INLINE_IMAGE', key('01'), 'https://evil.example/x.png'),
    attachment(11, 'INLINE_IMAGE', key('02'), 'data:image/png;base64,AAAA'),
    attachment(12, 'INLINE_IMAGE', key('03'), 'blob:https://app.local/abc'),
    attachment(13, 'INLINE_IMAGE', key('04'), 'javascript:alert(1)'),
    attachment(14, 'INLINE_IMAGE', key('05'), '/api/v1/posts/0/attachments/6/content'),
    attachment(15, 'INLINE_IMAGE', key('06'), '/api/v1/posts/3/attachments/0/content'),
    attachment(16, 'INLINE_IMAGE', key('07'), '/api/v1/posts/3/attachments/6/content/extra'),
    attachment(17, 'INLINE_IMAGE', key('08'), 'api/v1/posts/3/attachments/6/content'),
    attachment(18, 'INLINE_IMAGE', key('09'), '//api/v1/posts/3/attachments/6/content'),
    attachment(19, 'INLINE_IMAGE', key('10'), ' /api/v1/posts/3/attachments/6/content'),
    attachment(20, 'INLINE_IMAGE', validKey, '/api/v1/posts/3/attachments/6/content')
  ];
  assert.deepEqual(clone(buildInlineImageSources(attachments)), {
    [validKey]: '/api/v1/posts/3/attachments/6/content'
  });
});

test('rich body payload encodes canonical UTF-8 JSON without plain body fields', () => {
  const { buildRichPostBodyPayload } = utils();
  const payload = buildRichPostBodyPayload(runtimeDoc);
  assert.equal(payload.bodyFormat, 'TIPTAP_JSON');
  assert.deepEqual(
    JSON.parse(Buffer.from(payload.bodyDocumentBase64, 'base64').toString('utf8')),
    canonicalDoc
  );
  assert.deepEqual(Object.keys(payload).sort(), ['bodyDocumentBase64', 'bodyFormat']);
  assert.throws(() => buildRichPostBodyPayload({ type: 'video' }));
});
