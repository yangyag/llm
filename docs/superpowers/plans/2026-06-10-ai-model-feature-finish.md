# AI 답변 모델명(aiModel) 기능 마무리 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 미커밋 상태인 "AI 답변 사용 모델명 저장·노출" 기능의 미완성 4건(기본 모델명 불일치, 화면 표시 불일치, null 가드 누락, 테스트 검증 누락)을 정리하고 테스트/빌드 게이트를 통과시킨 뒤 전체를 커밋한다.

**Architecture:** 기능 본체(AiReplyResult record → BoardReply.aiModel → V11 마이그레이션 → BoardReplyDto → 프론트 배지)는 이미 작업 트리에 구현되어 있다. 이 계획은 그 위에 마무리 수정만 얹는다. 백엔드(기본값 + 테스트) → 백엔드 게이트 → 프론트(배지 2곳) → 프론트 게이트 → 일괄 커밋 순서로 진행한다.

**Tech Stack:** Spring Boot (Java 25, Gradle wrapper), JUnit 5 + AssertJ + MockMvc, React/Vite (Node 22 / npm)

---

## 시작 전 컨텍스트 (반드시 읽기)

- **작업 트리에 미커밋 변경이 이미 있다.** 이 기능의 본체(12개 수정 파일 + 신규 `back/src/main/resources/db/migration/V11__add_ai_model_to_replies.sql`)가 미커밋 상태다. 시작 전 `git status --short`로 이 상태가 그대로인지 확인하라. 모르는 변경이 섞여 있으면 중단하고 사용자에게 확인.
- **Task별 커밋 금지, 마지막에 일괄 커밋.** 이 계획의 수정들은 미커밋 기능 본체에 의존한다(예: 테스트가 미커밋 `AiReplyResult` record를 참조). 부분 커밋하면 컴파일 안 되는 커밋이 생기므로, 모든 게이트 통과 후 Task 7에서 한 번에 커밋한다.
- **테스트는 H2(create-drop, Flyway off)** 라서 V11 SQL 자체는 테스트로 검증되지 않는다. 실제 PostgreSQL 적용은 통합 기동(`docker compose up -d --wait` 후 8083 health) 시점에 확인된다 — 이 계획의 범위 밖.
- 명령은 모두 저장소 루트 `/home/yangyag/llm` 기준.

### 범위 제외 (이번 계획에서 하지 않는 것)

- docs 갱신 (docs/05 구 모델명, docs/07·09의 `aiModel` 필드 문서화) — 별도 작업
- WelcomePage 라디오 라벨에 하드코딩된 모델명을 API로 받아오는 개선 — 별도 작업
- SYSTEM_PROMPT 변경을 별도 커밋으로 분리하는 것 — 커밋 메시지 본문에 명시하는 것으로 갈음

---

### Task 1: @Value fallback 기본 모델명 갱신 (테스트 먼저)

`ExternalAiReplyGenerator`의 `@Value` fallback이 구버전 모델명(gpt-5.4, claude-sonnet-4-6, grok-4.20-0309-reasoning)인데 `.env.example`·기존 테스트는 신버전이다. 기존 테스트는 생성자 인자만 검증해서 이 불일치를 못 잡으므로, annotation fallback을 직접 검증하는 테스트를 추가해 red → green으로 간다.

**Files:**
- Modify: `back/src/test/java/com/llm/app/board/ai/ExternalAiReplyGeneratorDefaultsTest.java`
- Modify: `back/src/main/java/com/llm/app/board/ai/ExternalAiReplyGenerator.java:38,41,44`

- [ ] **Step 1: fallback 검증 테스트 추가 (파일 전체를 아래 내용으로 교체)**

`back/src/test/java/com/llm/app/board/ai/ExternalAiReplyGeneratorDefaultsTest.java` 전체:

```java
package com.llm.app.board.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

class ExternalAiReplyGeneratorDefaultsTest {

	@Test
	void constructorShouldPreserveConfiguredLatestDefaultModelNames() throws Exception {
		ExternalAiReplyGenerator generator = new ExternalAiReplyGenerator(
			"",
			"gpt-5.5",
			"https://api.openai.com/v1",
			"",
			"claude-opus-4-7",
			"https://api.anthropic.com/v1",
			"",
			"grok-4.3",
			"https://api.x.ai/v1"
		);

		assertThat(readField(generator, "openAiModel")).isEqualTo("gpt-5.5");
		assertThat(readField(generator, "anthropicModel")).isEqualTo("claude-opus-4-7");
		assertThat(readField(generator, "xAiModel")).isEqualTo("grok-4.3");
	}

	@Test
	void valueAnnotationFallbacksShouldMatchLatestDefaultModelNames() {
		Annotation[][] parameterAnnotations =
			ExternalAiReplyGenerator.class.getDeclaredConstructors()[0].getParameterAnnotations();

		assertThat(valueFallback(parameterAnnotations[1])).isEqualTo("gpt-5.5");
		assertThat(valueFallback(parameterAnnotations[4])).isEqualTo("claude-opus-4-7");
		assertThat(valueFallback(parameterAnnotations[7])).isEqualTo("grok-4.3");
	}

	private String valueFallback(Annotation[] annotations) {
		for (Annotation annotation : annotations) {
			if (annotation instanceof Value value) {
				String expression = value.value();
				int colon = expression.indexOf(':');
				return expression.substring(colon + 1, expression.length() - 1);
			}
		}
		throw new IllegalStateException("@Value 어노테이션을 찾지 못했습니다");
	}

	private Object readField(Object target, String fieldName) throws Exception {
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		return field.get(target);
	}
}
```

(생성자 파라미터 인덱스 1·4·7 = openAiModel·anthropicModel·xAiModel. `valueFallback`은 `${OPENAI_MODEL:gpt-5.5}` 형식에서 `:` 뒤 ~ 닫는 `}` 앞을 추출한다.)

- [ ] **Step 2: 테스트 실행 — 실패(red) 확인**

```bash
cd back && ./gradlew test --tests "com.llm.app.board.ai.ExternalAiReplyGeneratorDefaultsTest"
```

Expected: FAIL — `valueAnnotationFallbacksShouldMatchLatestDefaultModelNames`에서 `expected: "gpt-5.5" but was: "gpt-5.4"` 류의 AssertJ 실패. (기존 `constructorShould...` 테스트는 PASS여야 함.)

- [ ] **Step 3: @Value fallback 3곳 갱신**

`back/src/main/java/com/llm/app/board/ai/ExternalAiReplyGenerator.java`의 생성자(36~46행)에서 아래 3줄만 변경:

```java
// 38행
@Value("${OPENAI_MODEL:gpt-5.4}") String openAiModel,
// → 변경 후
@Value("${OPENAI_MODEL:gpt-5.5}") String openAiModel,

// 41행
@Value("${ANTHROPIC_MODEL:claude-sonnet-4-6}") String anthropicModel,
// → 변경 후
@Value("${ANTHROPIC_MODEL:claude-opus-4-7}") String anthropicModel,

// 44행
@Value("${XAI_MODEL:grok-4.20-0309-reasoning}") String xAiModel,
// → 변경 후
@Value("${XAI_MODEL:grok-4.3}") String xAiModel,
```

- [ ] **Step 4: 테스트 실행 — 통과(green) 확인**

```bash
cd back && ./gradlew test --tests "com.llm.app.board.ai.ExternalAiReplyGeneratorDefaultsTest"
```

Expected: PASS (테스트 2개 모두), `BUILD SUCCESSFUL`

---

### Task 2: 컨트롤러 테스트에 aiModel 응답 검증 추가

mock이 `AiReplyResult("AI 생성 답변", "gpt-5.5")`를 반환하도록 이미 갱신됐지만, 응답 JSON의 `aiModel` 필드 검증이 없다. 저장→DTO 노출 체인(BoardService → BoardReply.aiModel → BoardMapper → BoardReplyDto)을 응답 단에서 고정하는 검증을 추가한다.

**Files:**
- Modify: `back/src/test/java/com/llm/app/board/controller/BoardPostControllerTest.java:667`

- [ ] **Step 1: jsonPath 검증 1줄 추가**

`aiReplyShouldBeStoredAndLocked` 테스트(643행~)의 검증 체인에서:

```java
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.replies", hasSize(1)))
			.andExpect(jsonPath("$.replies[0].body").value("AI 생성 답변"))
			.andExpect(jsonPath("$.replies[0].ai").value(true))
			.andExpect(jsonPath("$.replies[0].aiProvider").value("GPT"))
			.andReturn();
```

를 아래로 변경 (`aiModel` 검증 1줄 삽입):

```java
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.replies", hasSize(1)))
			.andExpect(jsonPath("$.replies[0].body").value("AI 생성 답변"))
			.andExpect(jsonPath("$.replies[0].ai").value(true))
			.andExpect(jsonPath("$.replies[0].aiProvider").value("GPT"))
			.andExpect(jsonPath("$.replies[0].aiModel").value("gpt-5.5"))
			.andReturn();
```

- [ ] **Step 2: 테스트 실행 — 통과 확인**

```bash
cd back && ./gradlew test --tests "com.llm.app.board.controller.BoardPostControllerTest"
```

Expected: PASS, `BUILD SUCCESSFUL`. 기능 본체가 이미 구현돼 있으므로 바로 통과해야 정상. 만약 `aiModel` 검증에서 FAIL하면 BoardService → BoardReply → BoardMapper → BoardReplyDto 체인 어딘가가 끊긴 것이니 해당 diff를 다시 확인하라 (이 계획의 가정이 깨진 것이므로 임의로 본체를 고치지 말고 보고).

---

### Task 3: 백엔드 전체 테스트 게이트

**Files:** 없음 (검증만)

- [ ] **Step 1: 전체 테스트 실행 (CLAUDE.md 게이트)**

```bash
cd back && ./gradlew clean test
```

Expected: `BUILD SUCCESSFUL`, 실패 0건. 실패 시 실패한 테스트 출력 전체를 보고하고 중단.

---

### Task 4: PublicPostPage AI 배지 null 가드

V11 이전에 생성된 기존 AI 답변은 `ai_model`이 NULL → API의 `aiModel`이 null로 내려와 현재 코드는 `AI · GPT ()`처럼 빈 괄호를 그린다. 모델명이 있을 때만 괄호를 붙인다.

**Files:**
- Modify: `front/src/pages/PublicPostPage.jsx:166`

- [ ] **Step 1: 배지 렌더링에 null 가드 적용**

166행:

```jsx
{reply.ai ? <span className="ai-badge">AI · {reply.aiProvider} ({reply.aiModel})</span> : null}
```

를 아래로 변경:

```jsx
{reply.ai ? <span className="ai-badge">AI · {reply.aiProvider}{reply.aiModel ? ` (${reply.aiModel})` : ""}</span> : null}
```

(렌더 결과: 모델명 있으면 `AI · GPT (gpt-5.5)`, 없으면 `AI · GPT`)

---

### Task 5: WelcomePage AI 배지에 모델명 표시 (null 가드 포함)

관리자 화면(WelcomePage)의 답변 목록 배지는 provider만 표시해 PublicPostPage와 불일치한다. 같은 형식으로 통일한다.

**Files:**
- Modify: `front/src/pages/WelcomePage.jsx:1103`

- [ ] **Step 1: 배지 렌더링에 모델명 추가**

1103행:

```jsx
{reply.ai ? <span className="ai-badge">AI · {reply.aiProvider}</span> : null}
```

를 아래로 변경 (Task 4와 동일한 패턴):

```jsx
{reply.ai ? <span className="ai-badge">AI · {reply.aiProvider}{reply.aiModel ? ` (${reply.aiModel})` : ""}</span> : null}
```

---

### Task 6: 프론트 빌드 게이트

**Files:** 없음 (검증만)

- [ ] **Step 1: 프론트 빌드 실행 (CLAUDE.md 게이트)**

```bash
cd front && npm run build
```

Expected: `vite build` 성공, `✓ built in ...` 출력으로 종료, 에러 0건. `node_modules` 없다는 에러가 나면 `cd front && npm ci` 후 재시도. 빌드 실패 시 에러 출력 전체를 보고하고 중단.

---

### Task 7: 변경 범위 확인 후 일괄 커밋

**Files:** 없음 (git 작업만)

- [ ] **Step 1: 변경 범위 확인**

```bash
git status --short
```

Expected: 다음 파일들만 나와야 한다 —
수정 12개: `.env.example`, `README.md`, `back/src/main/java/.../ai/AiReplyGenerator.java`, `ai/ExternalAiReplyGenerator.java`, `dto/BoardReplyDto.java`, `model/BoardReply.java`, `service/BoardMapper.java`, `service/BoardService.java`, `back/src/test/java/.../ai/ExternalAiReplyGeneratorDefaultsTest.java`, `controller/BoardPostControllerTest.java`, `front/src/pages/PublicPostPage.jsx`, `front/src/pages/WelcomePage.jsx`
신규(untracked): `back/src/main/resources/db/migration/V11__add_ai_model_to_replies.sql`, `docs/superpowers/plans/2026-06-10-ai-model-feature-finish.md`(이 계획서 — 커밋에서 제외)
그 외 파일이 있으면 중단하고 사용자에게 확인.

- [ ] **Step 2: 기능 전체 + 마무리 수정 일괄 커밋 (계획서 제외)**

```bash
git add .env.example README.md back/src front/src
git commit -m "$(cat <<'EOF'
AI 답변에 사용 모델명(aiModel) 저장·노출 추가

- AiReplyGenerator 반환 타입을 AiReplyResult(content, model)로 확장
- post_replies.ai_model 컬럼 추가 (V11, nullable VARCHAR(64))
- 답변 API 응답(BoardReplyDto)과 화면 AI 배지에 모델명 표시 (기존 NULL 데이터는 모델명 생략)
- AI 시스템 프롬프트를 조언형에서 직접 답변형으로 교체
- 기본/예시 모델명을 gpt-5.5, claude-opus-4-7, grok-4.3으로 갱신

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 3: 커밋 결과 확인**

```bash
git status --short && git log --oneline -1
```

Expected: 새 커밋 1개. 남은 변경은 `?? docs/superpowers/plans/2026-06-10-ai-model-feature-finish.md`(이 계획서)뿐이어야 한다. push는 사용자 지시가 있을 때만.

---

## 배포 시 주의 (계획 범위 밖, 인수인계용)

- JPA `ddl-auto=validate`이므로 **V11 미적용 DB에 새 백엔드가 붙으면 기동 실패**한다. 정상 경로(컨테이너 기동 시 Flyway 선행)에선 자동 적용되므로 코드와 마이그레이션을 반드시 함께 배포할 것.
- 프론트보다 **백엔드 선배포가 안전**: 프론트만 먼저 나가면 `aiModel`이 undefined라 모델명이 안 보일 뿐(무해), 백엔드 먼저는 완전 호환.
- 운영 `.env`의 `OPENAI_MODEL` 등은 이번 변경과 무관하게 그대로다. 운영 모델을 바꾸려면 EC2 `.env` 수정 + 컨테이너 재기동이 별도로 필요.
