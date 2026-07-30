# 프론트엔드 대규모 개편: React/Vite → Nuxt/Vue/TS (SPA/SSG) 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `front/` 전체를 React/Vite(순수 JSX)에서 Nuxt 3(Vue 3 + TypeScript + Pinia) 기반 SPA/SSG로 빅뱅 재작성한다. 백엔드 API 계약은 그대로 두고, 기존 기능·인증 동작을 동등하게 보존하며, 거대 단일 컴포넌트(`WelcomePage.jsx` 1268줄)를 스토어·컴포넌트 단위로 분해한다.

## Background (현재 상태)

- **스택:** React 18.3 + Vite 5.4, 순수 JSX(TS 아님). 의존성 3개(react, react-dom, js-base64). 라우터/상태라이브러리 없음.
- **파일:** `App.jsx`(인증+유휴타임아웃+라우팅 분기), `api.js`(fetch 래퍼), `pages/{LoginPage,WelcomePage,PublicPostPage}.jsx`, `styles.css`(684줄).
- **라우팅:** `window.location.pathname` 정규식(`/^\/posts\/(\d+)$/`)으로 3-way 분기(공개 상세 / 비인증 로그인 / 인증 보드).
- **인증:** localStorage JWT(`auth_token`/`auth_username`) + 부팅 시 `getMe` + 유휴 타임아웃 1시간(`IDLE_TIMEOUT_MS`) + 인증 요청 401 시 `auth:unauthorized` 이벤트 강제 로그아웃.
- **배포:** multi-stage Dockerfile(node:22 빌드 → nginx:1.27-alpine이 `dist/` 서빙). compose `llm-front` 컨테이너, host 8083→컨테이너 80, healthcheck `wget http://127.0.0.1/`. nginx가 `/api/`→`llm-back:8080` 역프록시 + SPA fallback.
- **부채:** `WelcomePage.jsx`에 `useState` 30+개 집중, AI 모델명 하드코딩(`gpt-5.5` 등), 100MB 제한은 UI 표시만.

## 결정 사항 (사용자 확정)

1. **렌더링:** SPA/SSG (`ssr: false` + `nuxi generate` 정적 산출물). SSR은 Node 서버 추가·localStorage 인증 충돌로 인해 제외.
2. **서빙:** nginx 정적 유지. 기존 `llm-front` 컨테이너(8083 진입점, `/api` 프록시, SPA fallback) 구조를 그대로 가져가되, 빌드 산출물 경로만 `dist/` → `.output/public`로 변경.
3. **전략:** 빅뱅. `front/` 통째로 재작성, 기존 React 코드는 교체.
4. **스택:** Vue 3(`<script setup lang="ts">`) + Nuxt 3 + TypeScript + Pinia(`@pinia/nuxt`).

## 전제 (nginx 정적 유지의 정확한 의미)

- nginx는 **정적 자산(`.output/public`의 `_nuxt/*` 등)을 직접 서빙**하고, `/api/`→백엔드 프록시, SPA fallback(`try_files ... /index.html`)을 담당. **Node 서버는 추가하지 않는다**(SSR이 아니므로 불필요).
- 따라서 컨테이너 구조·포트(8083)·healthcheck·compose는 거의 동일. Dockerfile만 빌드 단계(nuxt generate)와 복사 경로가 바뀐다.

## Architecture (새 구조)

```
front/
├── nuxt.config.ts          # ssr:false, runtimeConfig.public.apiBase, nitro devProxy
├── package.json            # nuxt, vue, pinia, @pinia/nuxt, js-base64, typescript
├── tsconfig.json
├── Dockerfile              # node:22(nuxi generate) → nginx:1.27-alpine(.output/public)
├── nginx.conf              # 기존 유지(정적 + /api 프록시 + SPA fallback + 500M)
├── .dockerignore
├── app.vue                 # Nuxt 루트(<NuxtPage/>)
├── assets/css/main.css     # styles.css 이관
├── public/upload_zip_post.zip  # 정적 파일 유지(업로드 도구)
├── pages/
│   ├── index.vue           # / (인증 보드: auth middleware)
│   ├── login.vue           # /login (비인증 로그인)
│   └── posts/[id].vue      # /posts/:id (공개 상세, middleware 없음)
├── middleware/
│   └── auth.global.ts      # 비인증 시 /login 리다이렉트, /login·/posts/* 제외
├── components/
│   ├── board/{PostList,Pagination,SearchBar,BatchActionBar}.vue
│   ├── post/{PostDetail,PostForm,PostEditPanel,AttachmentSelect,AttachmentPanel,ConversionSummary}.vue
│   ├── reply/{ReplyList,ReplyForm,ReplyItem,ReplyEditPanel}.vue
│   └── ai/{AiReplyPanel}.vue
├── composables/
│   ├── useIdleTimeout.ts   # 유휴 타임아웃(1h, throttle, visibilitychange/focus, localStorage)
│   └── useListUrlState.ts  # page/query URL 동기화(pushState/popstate)
├── stores/
│   ├── auth.ts             # token, username, checked, login/logout/fetchMe
│   ├── posts.ts            # 목록, 페이지네이션, 검색
│   └── postDetail.ts       # 상세, 댓글, 첨부, 수정 모드, AI provider
├── plugins/
│   └── auth.client.ts      # 부팅 fetchMe + auth:unauthorized 리스너→logout
├── services/
│   └── api.ts              # $fetch 래퍼, withApiBase, bodyBase64, 에러 정규화, 401 디스패치
└── types/
    └── api.ts              # Post, Reply, Attachment, Pagination, ApiError, ...
```

**Tech Stack:** Nuxt 3 / Vue 3(`<script setup lang="ts">`) / Pinia(`@pinia/nuxt`) / TypeScript / Node 22 / nginx 1.27-alpine

## 시작 전 컨텍스트 (반드시 읽기)

- **백엔드 변경 금지.** 모든 API(`/api/v1`, `bodyBase64`, `multipart/form-data`, JWT Bearer 헤더) 계약은 그대로. `back/`은 건드리지 않는다.
- **작업 전 `git status --short`로 범위 확인.** 이 개편은 `front/` 전체 교체. 백엔드·docs 변경이 섞여 있으면 중단하고 확인.
- **게이트:** UI/API client 변경 시 `cd front && npm run build` 필수. 통합 검증은 `docker compose up -d --wait` 후 `curl -fsS http://127.0.0.1:8083/api/v1/health` (정상 `{"status":"UP"}`).
- **CLAUDE.md 제약 준수:** health는 항상 **8083(front proxy)** 경유. 외부 네트워크 `auto_default` 선행. `VITE_API_BASE_URL`(운영 빈값→상대경로)은 `NUXT_PUBLIC_API_BASE`로 대응.
- 명령은 모두 저장소 루트 `/home/yangyag/llm` 기준.

### 범위 제외 (이번 계획에서 하지 않는 것)

- 백엔드 springdoc/OpenAPI 추가(TS 타입 자동생성) — 별도 작업. 타입은 `docs/07` 기반 수동 정의.
- AI 모델명을 API로 받아오는 개선 — 하드코딩 그대로 이관(별도 작업).
- 100MB 파일 검증을 프론트에서 실제 수행 — UI 표시만 유지(백엔드 검증 위임).
- docs(05/07/11/12 등) 갱신 — 개편 완료 후 별도 작업.
- EC2 실배포 — 로컬 통합 검증까지만.

---

### Task 1: Nuxt 프로젝트 스캐폴드 + 설정

기존 `front/` React 파일은 Task 12 일괄 커밋 전까지 임시 보존(`front/.legacy/`로 이동 권장)하거나, 빅뱅이므로 직접 덮어쓰되 git 히스토리로 복구 가능함을 전제.

**Files:**
- Replace: `front/package.json`, `front/nuxt.config.ts`, `front/tsconfig.json`, `front/Dockerfile`, `front/nginx.conf`, `front/.dockerignore`
- New: `front/app.vue`, `front/assets/css/main.css`(기존 `styles.css` 이관)

- [ ] **Step 1: `package.json`** — deps: `nuxt`(3.x), `vue`, `pinia`, `@pinia/nuxt`, `js-base64`; devDeps: `typescript`, `vue-tsc`. scripts: `dev: nuxt dev`, `build: nuxi generate`, `preview: nuxt preview`. (Nuxt 3는 `vue`를 peer로 자동 포함하나 명시 권장)
- [ ] **Step 2: `nuxt.config.ts`** — `ssr: false`, `modules: ['@pinia/nuxt']`, `runtimeConfig.public.apiBase: ''`(빌드 인자 `NUXT_PUBLIC_API_BASE`로 주입, 빈값→상대경로 `/api`), `nitro.devProxy['/api']: { target: 'http://localhost:8082', changeOrigin: true }`(로컬 bootRun/Vite 대체용), `css: ['~/assets/css/main.css']`, `app.head.title: 'LLM Starter'`.
- [ ] **Step 3: `tsconfig.json`** — Nuxt 생성 기본(`extends: ./.nuxt/tsconfig.json`).
- [ ] **Step 4: `app.vue`** — `<NuxtPage/>` 단순 래퍼 + 전역 메시지 배너 영역(선택).
- [ ] **Step 5: `styles.css` → `assets/css/main.css` 이관** — 내용은 그대로, 경로만 이동. CSS 클래스명은 기존 그대로 유지해 컴포넌트 마이그레이션 부담 최소화.
- [ ] **Step 6: 빌드 게이트** — `cd front && npm install && npm run build` 통과(`.output/public` 생성 확인).

### Task 2: TypeScript 타입 정의 (API)

`docs/07-api-reference.md` 기반으로 응답/요청 타입 수동 정의. 백엔드 스키마가 없으므로(springdoc 없음) 문서에서 도출.

**Files:**
- New: `front/types/api.ts`

- [ ] **Step 1: 타입 정의** — `Post`(id, title, body, mode, conversionReady, hasAttachment, replyCount, createdAt, attachments?, replies?), `Reply`(id, body, ai, aiProvider?, aiModel?, createdAt), `Attachment`(id, originalFilename, size, contentType, downloadUrl), `Pagination`(page, pageSize, totalItems, totalPages, hasPrevious, hasNext), `PostListResponse`(items, ...Pagination), `ApiError`(code, status, message), `AiProvider = 'GPT'|'CLAUDE'|'GROK'`, `PostMode = 'NORMAL'|'FILE_CONVERSION_REQUEST'`.
- [ ] **Step 2: 게이트** — `npm run build` 타입 에러 없음.

### Task 3: API 클라이언트 ($fetch 래퍼)

`api.js`를 `services/api.ts`로 포팅. Nuxt `$fetch` 사용, 에러 정규화·401 디스패치 보존.

**Files:**
- New: `front/services/api.ts`

- [ ] **Step 1: `withApiBase`/`encodeBodyBase64`** — `useRuntimeConfig().public.apiBase` 기반. 빈값→상대경로. `encodeBodyBase64`는 `js-base64` `fromUint8Array(new TextEncoder().encode(value))` 유지.
- [ ] **Step 2: `requestJson`** — `$fetch` 래퍼. FormData면 `Content-Type` 생략, JSON이면 명시. 응답 204→null. 에러 시 `ApiError` 객체(code/status/message) 생성. **401 + Authorization 헤더**면 `window.dispatchEvent(new CustomEvent('auth:unauthorized'))` 디스패치(로그인 요청 자체 401은 제외).
- [ ] **Step 3: 엔드포인트 함수** — `getPosts`, `getPost`, `createPost`, `updatePost`, `deletePost`, `createReply`, `createAiReply`, `updateReply`, `deleteReply`, `batchDeletePosts`, `login`, `getMe`, `getApiUrl`. 시그니처는 `types/api.ts` 타입 사용.
- [ ] **Step 4: 게이트** — `npm run build`.

### Task 4: Pinia 스토어

`WelcomePage`의 `useState` 30+개를 3개 스토어로 분리. 인증 로직은 Task 5에서 plugin/composable로 보강.

**Files:**
- New: `front/stores/auth.ts`, `front/stores/posts.ts`, `front/stores/postDetail.ts`

- [ ] **Step 1: `auth`** — state: `token, username, checked`. actions: `login(u,p)`(localStorage 쓰기), `logout()`(localStorage 정리), `fetchMe()`(getMe 호출). `setAuth` 헬퍼.
- [ ] **Step 2: `posts`** — state: `items, pagination, currentPage, searchQuery, searchInput, loading, selectedPostIds`. actions: `loadPosts`, `navigateToList`(URL 동기화), `toggleSelection`, `toggleSelectAll`, `batchDelete`. 게터: `pageNumbers`.
- [ ] **Step 3: `postDetail`** — state: `selectedPost, detailLoading, postActionMode, postForm, postEditForm, postAttachmentFiles, postEditAttachmentFiles, removeAttachmentIds, replyForm, replyEditState, selectedAiProvider, submitting, aiSubmitting, error/message 류`. actions: `loadDetail`, `createPost`, `updatePost`, `deletePost`, `createReply`, `updateReply`, `deleteReply`, `createAiReply`, 첨부 관련(`handleCreateAttachmentChange`, `mergeAttachmentFiles`, `toggleRemoveExistingAttachment` 등). — 이 스토어가 가장 크다. 필요 시 `attachment` 별도 스토어로 추가 분리 가능.
- [ ] **Step 4: 게이트** — `npm run build`.

### Task 5: 인증 (plugin + composable)

인증 동작을 동등하게 보존: 부팅 검증, 유휴 타임아웃 1시간, 401 강제 로그아웃.

**Files:**
- New: `front/plugins/auth.client.ts`, `front/composables/useIdleTimeout.ts`, `front/middleware/auth.global.ts`

- [ ] **Step 1: `plugins/auth.client.ts`** — 부팅 시 `auth.token` 있으면 `fetchMe()`, 실패 시 `logout()`. `window.addEventListener('auth:unauthorized', logout)` 등록(언마운트 시 정리). SPA 모드라 `.client` 한정이 자연스럽지만 전역 동작 보장.
- [ ] **Step 2: `useIdleTimeout.ts`** — `IDLE_TIMEOUT_MS=3600000`, `ACTIVITY_EVENTS=['mousedown','keydown','scroll','touchstart']`(capture 단계), `LAST_ACTIVITY_KEY`. throttle 5초, `setTimeout` 재예약(남은 시간만큼), `visibilitychange`/`focus` 시 즉시 재평가, localStorage 시드 보존(리로드 시 리셋 방지). `App.jsx` 로직 1:1 포팅.
- [ ] **Step 3: `middleware/auth.global.ts`** — `to.path`가 `/login` 또는 `/posts/:id`가 아니고 `auth.token` 없으면 `/login` 리다이렉트. 토큰 있고 `/login` 접근 시 `/` 리다이렉트.
- [ ] **Step 4: 게이트** — `npm run build`.

### Task 6: 라우팅 + 로그인 페이지

기존 `App.jsx` 정규식 라우팅 → Nuxt 파일 라우팅으로 자연 대체. **동작 차이 주의:** 기존엔 `/` 고정에서 인증 여부로 컴포넌트 교체였으나, Nuxt에선 `/`(보드)와 `/login` 분리. 공개 `/posts/:id`는 그대로.

**Files:**
- New: `front/pages/login.vue`, `front/pages/index.vue`(껍데기, Task 7에서 보드 컴포넌트 조립), `front/pages/posts/[id].vue`(Task 8)

- [ ] **Step 1: `pages/login.vue`** — `LoginPage.jsx` 포팅. `sanitizeUsername`/`isValidUsername`/`handleUsernamePaste`/조합입력(`isComposing`) 처리 보존. 성공 시 `auth.login()` 호출 후 `/` 이동(`navigateTo`).
- [ ] **Step 2: `pages/index.vue`** — 보드 셸. `useIdleTimeout()` 활성화, 헤더(사용자명·로그아웃·목록·글쓰기), `view`(list/write/detail) 상태로 하위 컴포넌트 교체. 기존 `view` 기반 SPA 내 전환은 Nuxt에서도 동일 컴포넌트 내 `view` 상태로 유지(라우트 분리 아님 — 기존 동작 보존).
- [ ] **Step 3: 게이트** — `npm run build`.

### Task 7: 보드 컴포넌트 분해 (WelcomePage → 컴포넌트군)

가장 큰 작업. `WelcomePage.jsx` 1268줄을 컴포넌트로 분해하며 `postDetail`/`posts` 스토어에 연결.

**Files:**
- New: `front/components/board/{PostList,Pagination,SearchBar,BatchActionBar}.vue`, `front/components/post/{PostDetail,PostForm,PostEditPanel,AttachmentSelect,AttachmentPanel,ConversionSummary}.vue`, `front/components/reply/{ReplyList,ReplyForm,ReplyItem,ReplyEditPanel}.vue`, `front/components/ai/AiReplyPanel.vue`

- [ ] **Step 1: 목록군** — `SearchBar`(검색/초기화, URL 동기화), `PostList`(아이템·체크박스·모드 배지·첨부 배지), `Pagination`(처음/이전/번호/다음/끝), `BatchActionBar`(전체선택/선택삭제). `posts` 스토어 사용.
- [ ] **Step 2: 작성** — `PostForm`(제목/본문/첨부), `AttachmentSelect`(파일 선택·누적·중복제거·최대5개·환경확인 confirm·개별 제거). `handleCreatePost` 로직 보존.
- [ ] **Step 3: 상세** — `PostDetail`(제목·메타·링크복사·수정/삭제), `ConversionSummary`(FILE_CONVERSION 모드 본문 숨김 카드), `AttachmentPanel`(다운로드 링크). 모드별 분기 보존(`isFileConversionMode`).
- [ ] **Step 4: 수정** — `PostEditPanel`(제목/본문/현재 첨부 삭제 토글/새 첨부 추가/한도 재검증). `FILE_CONVERSION_LOCKED`/`INVALID_ATTACHMENT_REQUEST` 에러 분기 보존.
- [ ] **Step 5: 댓글** — `ReplyList`, `ReplyItem`(AI 배지·수정/삭제·AI 답변은 수정삭제 불가), `ReplyForm`(작성), `ReplyEditPanel`(수정). `reply.ai` 분기 보존.
- [ ] **Step 6: AI** — `AiReplyPanel`(GPT/Claude/Grok 라디오, 라벨에 하드코딩 모델명 유지, `FILE_CONVERSION` 모드엔 미노출). `handleCreateAiReply` 보존.
- [ ] **Step 7: `pages/index.vue` 조립** — `view` 상태(list/write/detail)로 위 컴포넌트 교체. `message`/`error` 배너, 다운로드 링크(`/upload_zip_post.zip`) 배치.
- [ ] **Step 8: 게이트** — `npm run build`.

### Task 8: 공개 상세 페이지

`PublicPostPage.jsx` → `pages/posts/[id].vue`.

**Files:**
- New: `front/pages/posts/[id].vue`

- [ ] **Step 1:** `useRoute().params.id`로 postId 취득. `getPost` 로드(`cancelled` 패턴 → `onUnmounted`/`AbortController`로 정리). 로딩/에러/재시도 UI 보존.
- [ ] **Step 2:** 상세 본문(모드별 분기), 첨부 다운로드, 댓글 목록(AI 배지), "관리자 전용 기능" 안내 카드 + `/` 이동 버튼. 미인증 접근 허용(middleware 제외).
- [ ] **Step 3: 게이트** — `npm run build`.

### Task 9: 정적 자산 + 다운로드 링크

- [ ] **Step 1:** `public/upload_zip_post.zip` 유지(이미 존재). `getApiUrl('/upload_zip_post.zip')` 링크가 정적 경로로 동작하는지 확인(apiBase 빈값 시 `/upload_zip_post.zip` → nginx 정적 서빙).
- [ ] **Step 2:** 게이트 — `npm run build` 후 `.output/public/upload_zip_post.zip` 존재 확인.

### Task 10: 빌드/배포 파이프라인

Dockerfile·nginx·compose를 Nuxt 정적 산출물에 맞게 수정. nginx 구조는 유지.

**Files:**
- Replace: `front/Dockerfile`, `front/nginx.conf`, `docker-compose.yml`(front-build args)

- [ ] **Step 1: `front/Dockerfile`** — 1단계 `node:22-bookworm-slim`: `npm ci` → `ARG NUXT_PUBLIC_API_BASE=`/`ENV` → `npm run build`(`nuxi generate`). 2단계 `nginx:1.27-alpine`: `COPY nginx.conf`, `COPY --from=builder /app/.output/public /usr/share/nginx/html`. `EXPOSE 80`, `CMD nginx -g 'daemon off;'`.
- [ ] **Step 2: `front/nginx.conf`** — 기존 유지(listen 80, `client_max_body_size 500M`, `/api/`→`llm-back:8080` 프록시, `/` `try_files $uri $uri/ /index.html`). 변경점 없음, 검증만.
- [ ] **Step 3: `docker-compose.yml`** — `front-build` 서비스의 `args`를 `VITE_API_BASE_URL` → `NUXT_PUBLIC_API_BASE: ${NUXT_PUBLIC_API_BASE:-}`로 변경. `llm-front` 서비스(포트 8083→80, healthcheck `wget http://127.0.0.1/`)는 유지.
- [ ] **Step 4: 로컬 통합 기동** — `docker network inspect auto_default >/dev/null 2>&1 || docker network create auto_default` 후 `docker compose up -d --wait` → `curl -fsS http://127.0.0.1:8083/api/v1/health` `{"status":"UP"}` 확인.

### Task 11: 통합 시나리오 검증

- [ ] **Step 1:** 로그인(admin/admin) → `/` 보드 진입.
- [ ] **Step 2:** 글 작성(본문 선택 + 첨부 1~2개) → 상세 진입 → 첨부 다운로드.
- [ ] **Step 3:** 댓글 작성/수정/삭제, AI 답변 생성(GPT).
- [ ] **Step 4:** 글 수정(첨부 추가/삭제 토글), 일괄 삭제(체크박스).
- [ ] **Step 5:** `/posts/:id` 미인증 공개 열람.
- [ ] **Step 6:** 유휴 타임아웃 로직 검증(localStorage `auth_last_activity` 조작으로 1시간 경과 시뮬레이션 → 로그아웃). 401 강제 로그아웃 검증(토큰 변조 후 요청).
- [ ] **Step 7:** `cd back && ./gradlew clean test` 백엔드 게이트(변경 없음 확인 차).

### Task 12: 일괄 커밋

- [ ] **Step 1:** `git status --short`로 `front/` 외 변경 없는지 확인.
- [ ] **Step 2:** 한글 커밋 메시지로 일괄 커밋. (예: `프론트 대규모 개편: React/Vite → Nuxt/Vue/TS(SPA/SSG) 재작성`)

---

## 리스크 / 롤백

- **라우팅 동작 차이:** `/`(보드)·`/login` 분리로 기존 `/` 고정 동작과 달라짐. 미인증 시 자동 `/login` 이동. 공개 `/posts/:id`는 영향 없음. 사용자에게 사전 공지 권장.
- **Nuxt SSR 훅 오용 주의:** `ssr:false`라 서버 전용 훅(`useRequestFetch` SSR 동작)은 제외. localStorage 접근은 반드시 클라이언트 한정(`import.meta.client` 또는 `.client` plugin/middleware).
- **runtimeConfig 빌드 vs 런타임:** `NUXT_PUBLIC_API_BASE`는 빌드 인자 주입(운영 빈값). 런타임 오버라이드 불필요. 빈값→상대경로 동작 반드시 검증.
- **CSS 클래스 의존:** 기존 클래스명 그대로 쓰므로 `main.css` 이관 누락 시 스타일 깨짐. Task 1 Step 5에서 경로/내용 정합성 확인.
- **롤백:** 빅뱅이나 git 히스토리로 `git revert`/이전 커밋 체크아웃으로 복구. Docker Hub `yangyag2/llm-front:latest`는 교체 전 이미지를 보존하지 않으므로, 롤백 시 이전 SHA 이미지 필요(배포 시 시각/SHA/digest 기록 권장 — CLAUDE.md).
