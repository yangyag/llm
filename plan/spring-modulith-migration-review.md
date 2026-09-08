# Spring Modulith 전환 구현 검토 및 후속 작업

- 검토일: 2026-09-08
- 기준: `682094a` 이후 현재 작업 트리의 구현·테스트·문서 전체. 미추적 신규 Java 파일도 포함했다.
- 대상 계획서: [spring-modulith-migration-plan.md](spring-modulith-migration-plan.md)
- 결론: 확인한 범위에서 기능 회귀나 현재 모듈 경계 위반은 발견하지 못했다. 후속 검증으로 PostgreSQL finalize 시나리오 (b)와 인증 ZIP HTTP smoke를 완료했고, 문서 정정도 반영되어 전체 계획을 완료로 갱신한다.
- 이번 보고서는 후속 작업 결과까지 반영했다. M1의 PostgreSQL 테스트와 M2의 HTTP smoke 결과를 기존 검토 결론에 반영하고, 계획서의 완료 상태를 갱신했다.

## 1. PostgreSQL 삭제 flush 후 본문 실패 테스트 보완 — P2

**기존 근거:** [PostgresUploadFinalizeTest.java](../back/src/test/java/com/llm/app/review/PostgresUploadFinalizeTest.java)의 기존 시나리오 (a)/(c)와 계획서의 PostgreSQL finalize 완료 체크리스트는 세션·part 삭제 flush 뒤 본문 실패 경로를 미검증으로 남겼다.

**완료 결과:** M1에서 테스트 전용 `UploadSessionRepository` decorator를 추가했다. decorator는 실제 세션 삭제를 호출하고 `EntityManager.flush()` 뒤 같은 트랜잭션의 SQL 조회로 세션·part 행이 삭제되었음을 확인한 다음 본문 예외를 주입한다. 운영 코드에는 테스트 전용 flush hook을 추가하지 않았다.

실제 disposable PostgreSQL focused 테스트에서 원래 예외 보존, 제한 시간 내 종료, 게시글·첨부 metadata와 신규 영구 파일 롤백, 세션·part 삭제 롤백 및 `FAILED` 별도 커밋, 원본 chunk 보존, 조립 파일 정리, 후속 세션 접근의 잠금 없는 종료를 모두 확인했다. `PostgresUploadFinalizeTest`는 (a)/(b)/(c) **3/3 통과**했고 `PostgresMigrationTest`는 **1/1 통과**했다.

완료 기준은 다음과 같았고 모두 확인했다.

- 예외 주입 직전 같은 트랜잭션의 SQL 조회로 세션·part 삭제가 flush되었음을 확인했다.
- 원래 예외가 보존되고 제한 시간 안에 finalize 호출이 종료됐다.
- 게시글·첨부 메타데이터와 신규 영구 파일이 남지 않았다.
- 세션·part 삭제가 롤백되고, 이후 세션의 `FAILED` 상태가 별도 커밋됐다.
- 원본 청크 바이트가 유지되고 조립 파일이 정리됐다.
- 후속 세션 접근이 잠금 대기 없이 가능했다.

이는 확인된 운영 결함에 대한 수정 요청이 아니라, 변경한 트랜잭션 경계에 대한 누락된 회귀 검증이었다. M1 후속 구현과 focused 실행으로 이 항목은 완료되었다.

## 2. 인증 ZIP 업로드·다운로드의 실제 HTTP 검증 완료 — P2

**완료 결과:** M2에서 격리 네트워크(back `18080`, front proxy `18083`)와 disposable PostgreSQL을 준비해 실제 로그인 및 `upload_zip_post.py` 실행을 완료했다. AES-GCM alias 세션 생성, 암호화 청크 1건, finalize, 생성 게시글 조회, 첨부 다운로드가 모두 성공했다.

생성 결과는 post id `1`, `FILE_CONVERSION_REQUEST`, `conversionReady=true`, `authorUserId=2`, 첨부 `/api/v1/posts/1/attachments/1`이었다. 원본과 다운로드 ZIP의 SHA-256 및 바이트가 일치했고, 다운로드는 HTTP 200/application/zip이었다. `upload_sessions=0`, `upload_session_parts=0`, 임시 세션 volume empty, 영구 첨부 volume의 ZIP 보존을 확인했다. 검증에 사용한 이미지 식별자와 PostgreSQL 17/database `llm_m2_smoke`/schema `llm`, Flyway V1~V18 결과는 계획서에 기록했으며 인증값은 기록하지 않았다.

이 결과로 Phase 8 및 계획서 완료 체크리스트의 실제 HTTP 검증 항목을 완료 처리했다. 기존 앱과 운영 DB를 오염시키지 않도록 격리 자원은 검증 후 제거했다.

## 3. 개발 지침과 트랜잭션 설명을 현재 구현에 맞게 정정 — P2

### 공개 인증 진입점

**정정 결과:** 기존 지침은 새 보호 엔드포인트에서 `JwtProvider.authenticate`를 직접 호출하도록 잘못 안내했으나, [AGENTS.md](../AGENTS.md), [docs/14-security.md](../docs/14-security.md), [docs/04-architecture.md](../docs/04-architecture.md)와 계획서의 인증 절을 현재 구현에 맞게 고쳤다.

`JwtProvider`는 `auth.internal`에 있고, 게시판·업로드 컨트롤러는 `auth.api.AuthenticationGateway`를 주입받는다. 따라서 auth 외부 모듈의 보호 컨트롤러는 `AuthenticationGateway.authenticate`를 호출하고, auth 내부 구현인 `JwtProvider`가 JWT와 현재 계정 존재 여부를 검증한다. auth 내부 컨트롤러의 직접 사용은 그대로 허용한다.

인증 방식과 검증 동작은 바꾸지 않았다. 외부 모듈에서 `JwtProvider`를 직접 참조하지 않도록 문서 계약만 정정했다.

### 실패 상태 기록 시점

**정정 결과:** 계획서 160행의 기존 설명은 `catch`가 `UploadSessionFailureService.markFailed`를 직접 `REQUIRES_NEW`로 호출한다고 잘못 기록했다. 실제 [UploadSessionService.java](../back/src/main/java/com/llm/app/upload/service/UploadSessionService.java) 186–188행은 실패 기록 콜백만 등록하고, 463–482행에서 `afterCompletion(STATUS_ROLLED_BACK)` 이후 별도 트랜잭션으로 실패를 기록한다. 계획서의 업로드 흐름도 현재 구현에 맞게 고쳤다.

실제 순서는 다음과 같다.

1. finalize 본문에서 예외 발생 → 실패 기록 콜백 등록 → 원래 예외 재전파.
2. 기존 DB 트랜잭션 롤백 → `afterCompletion` → `REQUIRES_NEW`로 `FAILED` 기록.
3. 메서드 반환 이후 commit 실패는 본문의 `catch`를 지나지 않는다. 현재 검증된 시나리오 (c)는 세션이 `PENDING`으로 복원된다.
4. 조립 경로 생성 직후 정리 콜백을 등록한다. 성공 commit 뒤 세션 디렉터리를 정리하고 롤백 뒤 조립 파일을 정리한다.

롤백 전 직접 호출과 롤백 후 호출은 PostgreSQL 행 잠금 측면에서 중요한 차이다. 코드의 현재 순서를 유지하고 문서를 정정했다.

**완료 기준:** AGENTS·보안·아키텍처·계획서에서 공개 인증 계약과 실제 트랜잭션 순서를 일관되게 설명한다.

## 이번 검토에서 확인한 결과

| 검증 | 결과 |
| --- | --- |
| Java 25.0.2, `back/gradlew.bat clean test` | 147건 발견, 143건 통과, PostgreSQL 조건부 4건 skipped, 실패·오류 0건 |
| `ApplicationModulesDiagnosticTest` | 통과. 현재 의존 방향·내부 접근·순환 검증에서 위반 없음 |
| `GeneratedAttachmentPolicyTest` | 3건 통과. HTTP 세션 크기 경계 검증은 별도 `UploadSessionControllerTest`에 존재 |
| 별도 PostgreSQL `PostgresMigrationTest` | 1건 통과 |
| 별도 PostgreSQL `PostgresUploadFinalizeTest` | 시나리오 (a)/(b)/(c) 3건 통과, skipped 0건 |
| PostgreSQL 검증 환경 | 로컬 이미지 `postgres:1.0`, PostgreSQL 17.10, 임시 컨테이너·무작위 테스트 schema 사용. 검증 후 이번에 만든 컨테이너 제거 |
| 기존 실행 중인 front proxy `8083` health | `status=UP` 확인. 해당 컨테이너 이미지와 현재 소스의 동일성은 이번 검토에서 검증하지 않음 |
| 변경 범위·정적 비교 | auth/upload 이동 전후 구현, 공개 생성 계약, 예외 매핑, 응답 매퍼, 트랜잭션 콜백 확인. Flyway·프론트·compose 변경 없음 |
| `git diff --check` | 공백 오류 없음 |

구조 검증은 `verify()`를 그대로 실행하며 위반 필터나 open module로 우회하지 않는다. 검증 범위는 [Spring Modulith 1.4 공식 문서](https://docs.spring.io/spring-modulith/reference/1.4/verification.html)와 대조했다.

PostgreSQL 17.10 결과를 운영 문서의 PostgreSQL 18 검증 결과로 간주하지 않는다. 1·2번 검증과 3번 문서 정정을 모두 완료했으며, 계획서의 최종 상태와 완료 체크리스트를 갱신했다.
