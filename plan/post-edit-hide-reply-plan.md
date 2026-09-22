# 게시글 수정 모드에서 답글 영역 숨김 계획서

- 상태: 완료 — 구현·게이트·Playwright E2E 검증 통과(2026-09-22)
- 작성일: 2026-09-22
- 대상: `front/pages/index.vue` (단일 파일 템플릿 조건부 렌더링)
- 목표: 게시글 수정 화면에서 하단 답글 영역을 숨겨 편집에 집중하게 한다.
- 범위 원칙: 프론트 UI 렌더링 조건만 변경한다. API 계약, 스토어 상태 전이, 백엔드, Flyway는 건드리지 않는다.

이 문서는 구현 전 설계를 확정하기 위한 계획서다. 구현 도중 설계 변경이 필요하면 먼저 이 문서의 결정·Phase 범위를 수정하고, 변경 이유를 재개 기록에 남긴다.

## 1. 최종 사용자 동작

1. 사용자가 게시글 상세 화면에서 `수정` 버튼을 누른다.
2. `postActionMode`가 `edit`로 바뀌고 `PostEditPanel`이 본문 아래에 인라인으로 열린다.
3. 이 동안 화면 하단의 답변 영역(`답변` 제목 + 답변 입력 폼 + 답변 목록)은 표시하지 않는다.
4. `취소`를 누르거나 수정을 저장하면 `postActionMode`가 `none`으로 돌아오고 답변 영역이 다시 표시된다.
5. 수정 모드가 아닐 때의 답변 등록·수정·삭제 동작은 지금과 동일하다.

## 2. 현재 구조와 변경 필요점

게시글 상세는 라우트 이동이 아니라 `front/pages/index.vue`의 `detail.view === 'detail'` 분기 안에서 처리한다. 이 분기는 `PostDetail`과 `reply-thread`를 항상 함께 렌더링한다.

- `front/pages/index.vue:128-140` — `<PostDetail />` 바로 아래에 `.reply-thread` 섹션(`ReplyForm` + `ReplyList`)이 고정으로 붙어 있다.
- `front/stores/postDetail.ts:205-223` — `openPostEditPanel()`가 `postActionMode = "edit"`로 설정한다. 라우트가 바뀌지 않고 상태 플래그만 전환된다.
- `front/components/post/PostDetail.vue:112` — `v-if="detail.postActionMode === 'edit' && manageable"`로 `PostEditPanel`을 조건부 렌더링한다. 이미 같은 플래그를 렌더 조건으로 쓰는 기존 패턴이다.
- `front/pages/posts/[id].vue:128` — 공개 읽기 전용 상세 페이지에도 `reply-thread`가 있지만 수정 모드가 없으므로 변경하지 않는다.

변경 필요점은 `front/pages/index.vue`의 `.reply-thread` 섹션을 `postActionMode`에 따라 조건부 렌더링하는 것 하나다.

## 3. 확정 설계 결정

### 3.1 숨김 범위

**결정 A(권장): `.reply-thread` 섹션 전체를 숨긴다.**

`답변` 제목 + `ReplyForm` + `ReplyList`를 하나의 섹션이므로 함께 숨긴다. 수정 폼 아래에 답변 목록만 남으면 화면이 두 영역으로 나뉘어 편집 흐름이 끊긴다.

**대안 B: `ReplyForm`만 숨기고 `ReplyList`는 유지한다.**

요청 문구를 "답글을 입력하는 폼"으로 좁게 해석한 경우다. 이 경우 `.reply-thread` 안의 `<ReplyForm />`에만 조건을 건다. 사용자 확인 후 이쪽으로 바꿀 수 있다.

### 3.2 조건 플래그

기존 상태 플래그 `detail.postActionMode`를 그대로 쓴다. 새 computed·새 스토어 getter·새 prop을 만들지 않는다.

- 조건식: `detail.postActionMode !== 'edit'`
- 적용 위치: `front/pages/index.vue`의 `<section class="reply-thread">`에 `v-if`로 부착
- 근거: `PostDetail.vue:112`가 이미 `postActionMode === 'edit'`를 `PostEditPanel` 렌더 조건으로 쓰고 있어, 같은 플래그를 반대 방향으로 쓰면 두 영역이 항상 배타적으로 유지된다.

### 3.3 변경하지 않는 범위

- `postActionMode` 상태 전이(`openPostEditPanel` / `closePostActionPanel` / `handleUpdatePost`) — 이미 원하는 대로 동작한다.
- `PostDetail.vue`의 수정 버튼 노출 조건(`editable`) — 수정 모드 진입 자체를 막는 조건이므로 건드리지 않는다.
- 수정 모드에서 원문 본문·첨부가 함께 보이는 현재 렌더링 — 이번 요청 범위 밖이다.
- 답변 수정 모드(`replyEditState`)일 때의 표시 — 요청에 없음. 후속 논의로 넘긴다.
- 답변 개수 배지(`post-hero-replies`)와 `reply-count` — 수정 모드에서도 개수 정보는 유지한다.
- `front/pages/posts/[id].vue` 공개 상세 — 수정 모드가 없음.
- 백엔드·API·Flyway·문서 — UI 렌더링 조건만 바꾸므로 해당 없음.

## 4. Phase

### Phase 1: 템플릿 조건부 렌더링

1. `front/pages/index.vue`의 `<section class="reply-thread">`에 `v-if="detail.postActionMode !== 'edit'"`를 부착한다. (결정 A 기준)
2. `script setup`이나 스토어에는 손대지 않는다.
3. 같은 파일의 AI 답변 관련 주석(137행)과 그 외 주석은 유지한다. 동작 설명이 달라지는 주석이 있으면 함께 고친다.

### Phase 2: 검증 게이트

1. `cd front && npm test` — `front/tests/postDetail.test.cjs`의 `postActionMode` 전이 검증이 그대로 통과해야 한다. 스토어를 바꾸지 않았으므로 실패하면 범위가 새어 나간 것이다.
2. `cd front && npm run typecheck`
3. `cd front && npm run build`
4. 수동 확인(사용자): 상세 → `수정` 클릭 시 하단 답변 영역 소멸, `취소`/저장 후 답변 영역 복귀, 수정 모드가 아닐 때 답변 등록 정상.

백엔드·테스트·문서 변경은 없다. 프론트 게이트만 통과하면 된다.

## 5. 검증 결과 기록

구현: `front/pages/index.vue`의 `<section class="reply-thread">`에 `v-if="detail.postActionMode !== 'edit'"`를 부착했다. 스토어·computed·주석·테스트 추가 없음.

### 5.1 프론트 게이트 (2026-09-22)

`cd front && npm test && npm run typecheck && npm run build` — 세 게이트 모두 PASS.

- `npm test`(`node --test tests/*.test.cjs`): 36 tests / 36 pass / 0 fail
- `npm run typecheck`(`nuxi typecheck`): 진단 없음
- `npm run build`(`nuxi generate`): 5 routes prerendered, `.output/public` 생성 성공
  - 비치명적 기존 경고 1건(`cache-driver.js` external dependency WARN)과 `ssr: false` 안내는 이번 변경과 무관

### 5.2 Playwright E2E (2026-09-22)

사용자 요청에 따라 Phase 2-4의 수동 확인 대신 Playwright 자동 검증을 수행했다. 하니스는 루트 `e2e/`에 독립 패키지로 구성했다(`e2e/package.json`, `e2e/playwright.config.ts`, `e2e/tests/reply-thread-edit.spec.ts`). 재실행: `cd e2e && npx playwright test`.

- 환경: Node v24.19.0, Playwright 1.63.0, Chromium
- 대상: `docker compose` 스택 (`llm-front:1.0` 재빌드 + `llm-back:1.0`), health `{"status":"UP"}` 확인 후 `http://localhost:8083`에서 실행
- 시나리오: `admin` 로그인 → `글쓰기`로 일반 글 생성(첨부 없음) → 상세 → `수정` → 어서션 → `취소` → 어서션 → `수정` 후 `게시글 수정` 저장 → 어서션
- 결과: `1 passed (6.2s)`
  - 수정 모드에서 `.reply-thread` 미표시 — PASS
  - `취소` 후 `.reply-thread` 복귀 — PASS
  - 저장 후 `.reply-thread` 복귀 — PASS (저장 배너 `게시글을 수정했습니다.`와 제목 변경도 확인)

### 5.3 후속 정리

- E2E 하니스 `e2e/` 디렉터리는 검증 직후 사용자 요청으로 삭제했다. 커밋하지 않는다.
- E2E 실행용 테스트 글 `E2E-답변숨김-*`가 로컬 게시판에 남아 있다. 목록을 깨끗이 하려면 수동 삭제.
- 로컬 docker 스택은 healthy 상태로 실행 중이다.
