# 검증서: 프론트엔드 React→Nuxt/Vue/TS 개편

> **검증 에이전트용 문서.** 이 문서는 구현 완료 후 별도 에이전트가 독립적으로 검증할 때 사용한다. 배경을 풀어 적었으므로, 이 문서와 계획서(`docs/superpowers/plans/2026-07-30-front-react-to-nuxt-vue-ts.md`)·실제 코드만으로 검증이 가능해야 한다.

## 1. 배경 (왜 이 개편을 하는가)

### 1.1 개편 목적
`front/`를 React/Vite(순수 JSX)에서 Nuxt 3(Vue 3 + TypeScript + Pinia) 기반 **SPA/SSG**로 빅뱅 재작성한다. 목적은 (1) TypeScript 도입으로 API 타입 안전성 확보, (2) Nuxt 파일 라우팅으로 정규식 기반 라우팅 단순화, (3) Pinia 스토어 도입으로 거대 단일 컴포넌트(`WelcomePage.jsx` 1268줄, `useState` 30+개) 분해, (4) Vue 3 전환.

### 1.2 개편 전 상태 (React/Vite)
- **스택:** React 18.3 + Vite 5.4, 순수 JSX. 의존성 3개(react, react-dom, js-base64). 라우터·상태라이브러리 없음.
- **파일 구성:**
  - `src/App.jsx`(130줄) — 인증 상태 + 유휴 타임아웃 + `window.location.pathname` 정규식(`/^\/posts\/(\d+)$/`)으로 3-way 라우팅 분기.
  - `src/api.js`(166줄) — fetch 래퍼. `bodyBase64`(UTF-8→Base64), `multipart/form-data`, `VITE_API_BASE_URL`(빈값→상대경로 `/api`), 인증 요청 401 시 `auth:unauthorized` 이벤트 디스패치.
  - `src/pages/LoginPage.jsx` — 관리자 로그인. 아이디 영문숫자 제한, 조합입력(`isComposing`) 처리.
  - `src/pages/WelcomePage.jsx`(1268줄) — 인증 후 메인 보드. 목록/작성/상세/댓글/AI답변/첨부/일괄삭제/링크복사. `view` 상태(list/write/detail)로 SPA 내 화면 전환.
  - `src/pages/PublicPostPage.jsx` — `/posts/:id` 비인증 공개 상세.
  - `src/styles.css`(684줄).
- **인증:** localStorage JWT(`auth_token`/`auth_username`). 부팅 시 `getMe` 검증. 유휴 타임아웃 1시간(`IDLE_TIMEOUT_MS`, localStorage `auth_last_activity` 시드, throttle 5초, `visibilitychange`/`focus` 재평가). 백엔드 JWT 만료(`APP_JWT_EXPIRATION_MS`, 기본 1시간)와 프론트 유휴 타임아웃은 **독립**. 갱신/슬라이딩 세션 없음.
- **배포:** multi-stage Dockerfile(`node:22` 빌드 → `nginx:1.27-alpine`이 `dist/` 서빙). compose `llm-front` 컨테이너, host 8083→컨테이너 80, healthcheck `wget http://127.0.0.1/`. nginx `/api/`→`llm-back:8080` 역프록시 + SPA fallback(`try_files $uri $uri/ /index.html`) + `client_max_body_size 500M`.

### 1.3 핵심 결정 사항 (사용자 확정)
1. **렌더링: SPA/SSG** — `ssr: false` + `nuxi generate` 정적 산출물(`.output/public`). SSR은 Node 서버 추가·localStorage 인증 충돌로 제외.
2. **서빙: nginx 정적 유지** — 기존 `llm-front` 컨테이너(8083 진입점, `/api` 프록시, SPA fallback) 유지. **Node 서버 추가 없음.** 산출물 경로만 `dist/` → `.output/public`.
3. **전략: 빅뱅** — `front/` 통째로 재작성.
4. **스택:** Vue 3(`<script setup lang="ts">`) + Nuxt 3 + TypeScript + Pinia(`@pinia/nuxt`).

> **nginx 정적 유지의 정확한 의미:** nginx는 정적 자산 직접 서빙 + `/api` 프록시 + SPA fallback. SSR이 아니므로 Node 서버 불필요. SSR을 원했으면 nginx만으로는 불가능하다는 점이 이 결정의 핵심 배경.

### 1.4 백엔드 영향
**없음.** API 계약(`/api/v1` 경로, `bodyBase64`, `multipart/form-data`, JWT Bearer 헤더, 에러 코드 `INVALID_ATTACHMENT_REQUEST`/`FILE_CONVERSION_LOCKED` 등) 그대로. `back/` 미변경.

### 1.5 준수해야 할 CLAUDE.md 제약 (검증 시 필수 확인)
- health는 항상 **8083(front proxy)** 경유. back 8080은 host에 publish 안 함(8080 health 실패는 정상).
- 외부 네트워크 `auto_default` 선행(없으면 `up -d --wait` health 단계 실패).
- `VITE_API_BASE_URL`(운영 빈값→상대경로 `/api`) → `NUXT_PUBLIC_API_BASE`로 대응. 운영에선 반드시 빈값.
- `docker compose down -v`/무분별 volume prune 금지(첨부 데이터 손실).
- 빌드: Docker Hub namespace `yangyag2`, 태그 `latest`. compose 이미지 빌드는 `docker compose --profile build build`.
- `FILE_CONVERSION_REQUEST` 게시글: 업로드 세션 finalize로만 생성, 첨부 있으면 수정 불가, AI 답변 불가. AI 답변(`is_ai=true`)은 수정·삭제 불가.
- 게시글/댓글 본문은 `bodyBase64`(보안 아님, 인코딩만).

---

## 2. 검증 범위

구현이 계획서(12 Task)를 충실히 이행했는지, 그리고 **기존 React 기능이 동등하게 보존**되었는지 검증. 백엔드는 검증 대상이 아님(미변경 전제).

## 3. 검증 체크리스트

### 3.1 기능 동등성 (React→Vue 이관 누락 점검)
- [ ] 목록: 페이지네이션(처음/이전/번호/다음/끝), 검색(URL `page`/`query` 동기화, `popstate` 뒤로가기), 새로고침.
- [ ] 작성: 제목(maxLength 200, 필수), 본문(선택), 첨부(최대 5개, 파일당 100MB 표시). 같은 파일 재선택 가능(`input key` 초기화).
- [ ] 상세: 모드 배지, `conversionReady` 배지, 첨부 배지, 링크 복사(clipboard, 2초 "복사됨").
- [ ] 수정: `FILE_CONVERSION_READY` 글 수정 버튼 미노출. 첨부 삭제 토글 + 새 첨부 추가 + 한도 재검증(제출 직전).
- [ ] 삭제: 단건 confirm, 일괄 삭제(체크박스, 전체선택, confirm).
- [ ] 댓글: 작성/수정/삭제. AI 답변은 수정·삭제 버튼 미노출.
- [ ] AI 답변: GPT/Claude/Grok 라디오. `FILE_CONVERSION` 모드엔 AI 패널 미노출.
- [ ] 공개 상세(`/posts/:id`): 미인증 열람, 본문(모드별 숨김), 첨부 다운로드, "관리자 전용 기능" 안내.

### 3.2 인증/세션 보존 (가장 중요)
- [ ] localStorage 키 동일: `auth_token`, `auth_username`, `auth_last_activity`.
- [ ] 부팅 시 `getMe` 검증 → 실패 시 저장값 정리.
- [ ] 유휴 타임아웃 1시간 로직 1:1 포팅: `IDLE_TIMEOUT_MS`, 활동 이벤트 capture 단계 등록, throttle 5초, `setTimeout` 남은시간 재예약, `visibilitychange`/`focus` 즉시 재평가, localStorage 시드 보존(리로드 시 리셋 방지), 새 로그인 시 시드 갱신.
- [ ] 인증 요청(Authorization 헤더 포함) 401 → `auth:unauthorized` 이벤트 → 강제 로그아웃. **로그인 요청 자체 401(잘못된 자격증명)은 제외.**
- [ ] 로그아웃 시 localStorage 3개 키 정리.
- [ ] SSR 아님(`ssr:false`) 확인 — localStorage 접근이 서버 컨텍스트에서 실행되지 않는지.

### 3.3 API 계약 유지
- [ ] 모든 요청 `/api/v1` 경로. `NUXT_PUBLIC_API_BASE` 빈값 시 상대경로 `/api` 동작.
- [ ] 본문 `bodyBase64`(UTF-8→Base64, `js-base64` `fromUint8Array`).
- [ ] 게시글 생성/수정: `multipart/form-data`(title, bodyBase64, attachments, removeAttachmentIds).
- [ ] 댓글/AI: `application/json`.
- [ ] 에러 정규화: `code`/`status`/`message` 추출. `INVALID_ATTACHMENT_REQUEST`/`FILE_CONVERSION_LOCKED` 코드별 UI 메시지 분기.
- [ ] 다운로드 링크 `getApiUrl` 동작(첨부 `downloadUrl`, `/upload_zip_post.zip`).

### 3.4 빌드/배포/인프라
- [ ] `cd front && npm run build`(`nuxi generate`) 성공. `.output/public` 생성(index.html + `_nuxt/*` + `upload_zip_post.zip`).
- [ ] Dockerfile: 1단계 `node:22` `npm ci` + `NUXT_PUBLIC_API_BASE` ARG + `nuxi generate`. 2단계 `nginx:1.27-alpine`이 `.output/public` → `/usr/share/nginx/html` 복사.
- [ ] `nginx.conf`: `listen 80`, `client_max_body_size 500M`, `/api/`→`llm-back:8080` 프록시(헤더 설정), `/` `try_files $uri $uri/ /index.html`. 기존과 동일.
- [ ] `docker-compose.yml`: `front-build` args `NUXT_PUBLIC_API_BASE`(기본 빈값). `llm-front` 포트 8083→80, healthcheck `wget http://127.0.0.1/` 유지.
- [ ] 통합 기동: `docker network inspect auto_default` 선행 → `docker compose up -d --wait` → `curl -fsS http://127.0.0.1:8083/api/v1/health` = `{"status":"UP"}`.

### 3.5 TypeScript/구조 품질
- [ ] `types/api.ts`에 핵심 타입(Post/Reply/Attachment/Pagination/ApiError/AiProvider/PostMode) 정의.
- [ ] `<script setup lang="ts">` 사용, `any` 남용 없음.
- [ ] Pinia 스토어 3개(auth/posts/postDetail)로 상태 분리. `WelcomePage` 단일 거대 컴포넌트 해체 확인.
- [ ] 컴포넌트 분해: board/post/reply/ai 디렉토리 구성.
- [ ] `nuxt.config.ts`: `ssr:false`, `@pinia/nuxt`, `runtimeConfig.public.apiBase`, `nitro.devProxy['/api']`(localhost:8082).

### 3.6 라우팅 차이 명시적 확인 (기존과 다른 점)
- [ ] `/`(보드)·`/login` 분리. 미인증 `/` 접근 시 `/login` 리다이렉트(middleware).
- [ ] `/posts/:id`는 미인증 허용(middleware 제외).
- [ ] 보드 내 `view`(list/write/detail) 전환은 라우트가 아닌 컴포넌트 내 상태(기존 동작 보존).

---

## 4. 반드시 통과해야 할 게이트 (명령)

```
# 1. 프론트 빌드
cd front && npm install && npm run build

# 2. 백엔드 게이트 (변경 없음 확인)
cd ../back && ./gradlew clean test

# 3. 외부 네트워크 + 통합 기동 + health
cd ..
docker network inspect auto_default >/dev/null 2>&1 || docker network create auto_default
docker compose up -d --wait
curl -fsS http://127.0.0.1:8083/api/v1/health   # {"status":"UP"}
```

## 5. 검증 시나리오 (수행 순서)

1. **빌드** — 위 게이트 1 통과.
2. **통합 기동** — 게이트 3, health `UP`.
3. **인증 흐름** — `/` 접근 → `/login` 리다이렉트 → admin/admin 로그인 → `/` 보드 진입. 새로고침 후 세션 유지.
4. **게시글 생애주기** — 작성(본문+첨부) → 상세(첨부 다운로드) → 수정(첨부 추가/삭제) → 일괄삭제.
5. **댓글/AI** — 댓글 작성/수정/삭제, AI 답변(GPT) 생성, AI 답변 수정·삭제 불가 확인.
6. **공개 열람** — 시크릿/미인증 `/posts/:id` 열람.
7. **유휴 타임아웃** — localStorage `auth_last_activity`를 1시간+1초 전으로 조작 후 활동 트리거 → 로그아웃.
8. **401 강제 로그아웃** — DevTools에서 `auth_token` 변조 후 보드 조작 → 401 → 로그아웃.
9. **백엔드 무변경** — `git status`로 `back/` 변경 없음 확인.

## 6. 실패 시 처리

- 빌드 실패 → TS 타입 에러/의존성 누락 점검. `vue-tsc` 통과 여부.
- health 실패 → 8083(nginx) 기동 확인, `auto_default` 네트워크, `llm-back` healthy 여부. 8080 직접 health 시도 금지(정상 실패).
- 기능 누락 → 계획서 해당 Task 재확인, React 원본 로직(`front/.legacy/` 또는 git 히스토리)과 대조.
- 인증 로직 상이 → `App.jsx` 원본(유휴 타임아웃·401 디스패치)과 `useIdleTimeout.ts`/`plugins/auth.client.ts` 1:1 대조.

## 7. 검증 결과 보고 형식

검증 완료 후 각 체크리스트 항목별 PASS/FAIL과, FAIL 시 재현 단계·증상을 보고. 게이트(4절)는 전부 PASS여야 개편 완료로 인정.
