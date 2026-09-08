# Spring Modulith 전환 구현 검토 및 후속 작업

- 검토일: 2026-09-08
- 기준: `682094a` 이후 현재 작업 트리의 구현·테스트·문서 전체. 미추적 신규 Java 파일도 포함했다.
- 대상 계획서: [spring-modulith-migration-plan.md](spring-modulith-migration-plan.md)
- 결론: 확인한 범위에서 기능 회귀나 현재 모듈 경계 위반은 발견하지 못했다. 다만 **검증 보완 2건과 문서 정정 1건**이 남아 있어 전체 계획의 완료 판정은 보류하는 것이 맞다.
- 이번 검토에서는 이 보고서만 추가했다. 애플리케이션·테스트 소스와 기존 계획서는 수정하지 않았다.

## 1. PostgreSQL 삭제 flush 후 본문 실패 테스트 보완 — P2

**근거:** [PostgresUploadFinalizeTest.java](../back/src/test/java/com/llm/app/review/PostgresUploadFinalizeTest.java) 56–61행과 계획서 465행은 시나리오 (b)를 명시적으로 미검증으로 남겼다. 현재 두 테스트는 게시판 생성 직후의 flush 실패와 메서드 반환 이후의 commit 실패를 다룬다.

`UploadSessionService.finalizeSession()`은 세션·part 삭제를 요청한 다음 반환하며, 본문에서 발생한 예외만 `registerFailureMarking()`으로 연결한다. 삭제 SQL이 실제 실행된 뒤 본문에서 예외가 발생했을 때, 삭제 롤백과 별도 `FAILED` 기록이 함께 작동하는 경로는 현재 테스트로 직접 확인되지 않는다. 특히 commit 실패 테스트는 `catch`를 거치지 않고 `PENDING`으로 돌아오는 경로이므로 이를 대신하지 못한다.

**필요한 작업:** 기존 PostgreSQL 테스트에 시나리오 (b)를 추가한다. 운영 코드에 테스트 전용 flush 메서드를 넣기 전에, 테스트 전용 repository spy/decorator에서 실제 삭제와 `EntityManager.flush()`를 수행한 뒤 예외를 주입하는 방식을 검토한다. 이 주입 방식 자체는 이번 검토에서 구현·실행하지 않았다.

**완료 기준:** 실제 PostgreSQL 트랜잭션에서 아래 결과를 확인한다. 테스트 메서드 전체를 자동 롤백하는 방식은 사용하지 않는다.

- 예외 주입 직전 같은 트랜잭션의 SQL 조회로 세션·part 삭제가 flush되었음을 확인한다.
- 원래 예외가 보존되고 제한 시간 안에 finalize 호출이 종료된다.
- 게시글·첨부 메타데이터와 신규 영구 파일은 남지 않는다.
- 세션·part 삭제는 롤백되고, 이후 세션의 `FAILED` 상태가 별도 커밋된다.
- 원본 청크 바이트는 유지되고 조립 파일은 정리된다.
- 후속 세션 접근이 잠금 대기 없이 가능하다.

이는 확인된 운영 결함에 대한 수정 요청이 아니라, 변경한 트랜잭션 경계에 대한 누락된 회귀 검증이다.

## 2. 인증 ZIP 업로드·다운로드의 실제 HTTP 검증 완료 — P2

**근거:** 계획서의 Phase 8 및 468행, [docs/10-testing-quality.md](../docs/10-testing-quality.md)의 수동 smoke 기록에는 인증 ZIP HTTP 검증이 미실행으로 남아 있다. 기존 `UploadSessionControllerTest`는 MockMvc에서 정상 finalize와 다운로드 바이트를 검증하지만, 실제 Nginx proxy·컨테이너 저장 경로·업로드 스크립트까지 검증하지는 않는다.

**필요한 작업:** 격리된 앱 DB·첨부 저장소·테스트 계정을 준비하고, 검토 대상 소스로 만든 이미지와 실제 업로드 도구를 사용해 HTTP 검증을 수행한다. 다른 서비스의 8083 사용을 방해하지 않도록 별도 검증 환경에서 front proxy를 8083으로 구성한다.

**완료 기준:**

1. front proxy `8083` 경유 로그인과 `upload_zip_post.py`의 암호화 세션 생성·청크 전송·finalize가 성공한다.
2. 생성된 게시글의 작성자 계정 ID, `FILE_CONVERSION_REQUEST`, 첨부 응답과 다운로드 URL을 확인한다.
3. 해당 URL로 받은 ZIP의 SHA-256이 원본과 일치한다.
4. 성공 후 세션·part 행과 임시 세션 디렉터리가 정리되고 영구 첨부는 다운로드 가능하다.
5. 이미지 식별자, DB 버전, 성공 여부와 정리 결과를 기록하고 Phase 8·완료 체크리스트를 갱신한다. 인증값은 기록하지 않는다.

이번 검토에서는 기존 앱의 인증 쓰기 요청, 이미지 재빌드·교체 및 EC2 배포를 수행하지 않았다. 아래 health 결과만으로 이 항목을 완료 처리하면 안 된다.

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
| Java 25.0.2, `back/gradlew.bat clean test` | 146건 발견, 143건 통과, PostgreSQL 조건부 3건 skipped, 실패·오류 0건 |
| `ApplicationModulesDiagnosticTest` | 통과. 현재 의존 방향·내부 접근·순환 검증에서 위반 없음 |
| `GeneratedAttachmentPolicyTest` | 3건 통과. HTTP 세션 크기 경계 검증은 별도 `UploadSessionControllerTest`에 존재 |
| 별도 PostgreSQL `PostgresMigrationTest` | 1건 통과 |
| 별도 PostgreSQL `PostgresUploadFinalizeTest` | 기존 (a)/(c) 2건 통과, skipped 0건 |
| PostgreSQL 검증 환경 | 로컬 이미지 `postgres:1.0`, PostgreSQL 17.10, 임시 컨테이너·무작위 테스트 schema 사용. 검증 후 이번에 만든 컨테이너 제거 |
| 기존 실행 중인 front proxy `8083` health | `status=UP` 확인. 해당 컨테이너 이미지와 현재 소스의 동일성은 이번 검토에서 검증하지 않음 |
| 변경 범위·정적 비교 | auth/upload 이동 전후 구현, 공개 생성 계약, 예외 매핑, 응답 매퍼, 트랜잭션 콜백 확인. Flyway·프론트·compose 변경 없음 |
| `git diff --check` | 공백 오류 없음 |

구조 검증은 `verify()`를 그대로 실행하며 위반 필터나 open module로 우회하지 않는다. 검증 범위는 [Spring Modulith 1.4 공식 문서](https://docs.spring.io/spring-modulith/reference/1.4/verification.html)와 대조했다.

PostgreSQL 17.10 결과를 운영 문서의 PostgreSQL 18 검증 결과로 간주하지 않는다. 위 테스트 통과와 별개로, 1·2번 검증 및 3번 문서 정정을 완료한 뒤 계획서의 최종 상태를 갱신한다.
