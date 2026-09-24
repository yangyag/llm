# 테스트와 품질 게이트

변경 유형별로 필요한 검증 범위를 정합니다.

## 기본 검증 명령

백엔드:

```powershell
cd back
.\gradlew.bat '-Porg.gradle.java.installations.paths=<JAVA_25_HOME>' clean test
```

`<JAVA_25_HOME>`은 실행 환경의 Java 25 설치 경로로 바꿉니다. 경로·secret 값은 저장소에 기록하지 않습니다.

프론트:

```bash
cd /home/yangyag/llm/front
npm ci
npm test
npm run typecheck
npm run build
```

통합 명령은 compose 스택과 외부 PostgreSQL이 준비된 환경에서 실행합니다. 최종 통합 게이트는 다음 순서로 실행하며, health는 항상 front proxy 8083 경유로 확인합니다(정상 응답 `{"status":"UP"}`). 백엔드 8080은 host에 publish되지 않아 8080 health 실패는 정상입니다.

```powershell
# 저장소 루트
git status --short
git diff --check
docker compose up -d --wait
curl.exe -fsS http://127.0.0.1:8083/api/v1/health
```

2026-09-08에는 최신 `llm-back:1.0` 이미지로 compose를 기동해 `llm-back`·`llm-front` health, 8083 health, 공개 게시글 목록 조회를 확인했고, 별도 격리 환경에서 실제 인증 ZIP HTTP upload/download smoke도 성공했습니다. smoke는 `m2-llm-back:smoke` sha256 `8572eaceed6e2df0b9211d3bfdb47e67a7bfbe9ea644d570a2152086ac2d68ef` 및 `m2-llm-front:smoke` sha256 `55f49492d323754df8972a4a6177771a16290154c75d68cc4f8e1e521ad61582`를 사용했습니다. PostgreSQL 17, database `llm_m2_smoke`, schema `llm`, Flyway V1~V18이 모두 성공했고, 검증 후 격리 자원을 제거하고 원본 front 네트워크를 복원했습니다.

## 백엔드 테스트 구성

테스트 설정 파일: `back/src/test/resources/application.properties`

- H2 in-memory DB 사용
- PostgreSQL mode 사용
- Flyway 비활성화
- Hibernate `create-drop`
- 테스트용 JWT secret과 업로드 세션 secret 사용
- 첨부파일과 업로드 세션 root는 temp 디렉터리 사용
- 테스트 multipart 제한은 `2MB`

현재 테스트 범위(테스트 수는 각 파일의 `@Test` 선언을 정적으로 센 값):

| 테스트 파일 | 테스트 수 | 범위 |
| --- | --- | --- |
| `AuthControllerTest` | 6 | 로그인, JWT 검증, 인증 실패 |
| `HealthControllerTest` | 1 | health endpoint |
| `UserManagementControllerTest` | 28 | 사용자 추가/수정/삭제, ADMIN 전용, 마지막 ADMIN/자기 자신 보호 |
| `BoardPostControllerTest` | 65 | 게시글, 댓글, 첨부파일, rich 본문·inline 이미지 계약(manifest·파일·문서 key 집합, content endpoint header, 합계 5개 한도), AI 답변 제약, 작성자 소유권(본인/ADMIN/레거시), 일괄 삭제 권한, 댓글 소유권 |
| `UploadSessionControllerTest` | 21 | 업로드 세션 생성, chunk, finalize, 오류 조건, finalize 게시글 작성자 기록, 크기 제한 경계, 실패 시 파일 정리, 타인 접근/만료/완료 상태 |
| `JwtProviderTest` | 9 | 토큰 생성/검증, 만료, 위조, Bearer 형식 |
| `SecretKeyDerivationTest` | 5 | 키 파생(32바이트 미만 확장/이상 절단) |
| `BoardContentCodecTest` | 7 | bodyBase64 디코딩 경계(blank/100만자/오류) |
| `BoardRichDocumentCodecTest` | 13 | rich 문서 canonical JSON·평문 추출, node/mark/attribute whitelist, decoded 5MiB·평문 100만자·node 20,000·depth 20 경계, NUL·단독 surrogate 거부 |
| `InlineImageValidatorTest` | 8 | PNG/JPEG 실제 형식·dimensions 검증(클라이언트 MIME·확장자 불신), 10MB·8192px·25MP 경계, 손상·markup·빈 파일 거부 |
| `UploadSessionWireCodecTest` | 7 | 암호화 라운드트립, AAD alias 바인딩, 변조/타 secret 거부 |
| `ExternalAiReplyGeneratorDefaultsTest` | 1 | legacy AI provider 기본값 및 종료된 기능의 잔재 |
| `ApplicationModulesDiagnosticTest` | 1 | Spring Modulith 모듈 경계·순환·내부 접근·허용 의존 strict verification |
| `GeneratedAttachmentPolicyTest` | 3 | board 공개 생성 첨부 크기 정책과 제한 경계 |
| `HttpRequestLimitsTest` | 5 | 실제 내장 Tomcat(RANDOM_PORT)에서 405 `METHOD_NOT_ALLOWED`+`Allow`, 415 `UNSUPPORTED_MEDIA_TYPE`, 깨진 multipart 400, Tomcat 기본 2MB를 넘는 rich 본문 201, 텍스트 필드 8MB 초과 413 |
| `UploadSessionChunkWireLimitTest` | 3 | 암호화 청크 JSON 필드의 Jackson 문자열 한도에서 계산한 최대 청크(11,249,976바이트) 왕복 성공과 다음 크기 거부, 설정값 상한 적용 |
| `SecurityAndStorageRegressionTest` | 10 | 삭제 계정 쓰기·업로드 차단, username 재사용 시 토큰/소유권 분리, 일반·inline 첨부 rollback 정리와 커밋 후 삭제 재시도, 긴 ZIP 제목 |
| `PostgresMigrationTest` | 1 | V1~V16 적용 후 V17~V19 3개 upgrade, 소유권 백필, 삭제 FK, V19 기본값·check·partial unique, Hibernate validate |
| `PostgresUploadFinalizeTest` | 3 | disposable PostgreSQL finalize transaction rollback·commit failure와 파일 정리 |
| `PostgresInlineImageLifecycleTest` | 4 | disposable PostgreSQL에서 레거시 plain fixture 조회, rich 글 생성·수정·삭제, rollback 파일 정리, 커밋 후 삭제 실패·재시도 |

정적 합계는 201건이며, 조건부 PostgreSQL 메서드 8건(`PostgresMigrationTest` 1건, `PostgresUploadFinalizeTest` 3건, `PostgresInlineImageLifecycleTest` 4건)은 `LLM_TEST_POSTGRES_URL`이 있을 때만 실행됩니다. 최신 실행(2026-09-25): 표준 `clean test` 201건 발견·193건 통과·조건부 8건 skip·실패 0·오류 0, 같은 날 disposable `postgres:18`로 실행한 조건부 8건 모두 통과. 이전 기록(2026-09-22): 193건 발견·193건 통과(disposable `postgres:18` PostgreSQL 18.6).

## 프론트 테스트 구성

`cd front && npm test`는 `node --test tests/*.test.cjs`를 실행합니다. 각 테스트는 `typescript`로 TS 소스를 transpile해 `vm` sandbox에서 로드하므로 Nuxt dev/build 없이 store·composable·utility 계약을 검증합니다. 테스트 수는 각 파일의 `test(` 선언을 정적으로 센 값입니다.

| 테스트 파일 | 테스트 수 | 범위 |
| --- | --- | --- |
| `postDetail.test.cjs` | 16 | Pinia store의 응답 순서 역전·조회 실패·저장 중 이동·ID 불일치·계정 ID 권한 표시, rich 글 생성·수정의 inline pending 업로드 집합·일반 첨부와 합산한 5개 한도·실패 시 초안 유지·업로드 확인 1회 |
| `postDocument.test.cjs` | 6 | plain 본문의 줄바꿈 보존 변환, canonical serializer의 runtime 속성 제거와 marks 정규화, `bodyFormat` type guard와 plain fallback, inline content URL allowlist와 DOWNLOAD 분리, rich payload Base64 |
| `inlineImageDraft.test.cjs` | 15 | clipboard PNG/JPEG 추출·텍스트 paste 유지·미지원 형식 표시, UUID v4 fallback, 파일명·10MB 경계, MIME 별칭·확장자 fallback, pending registry 20개·100MB, 문서 순서 업로드·기존/예약 key·manifest index, object URL 해제 멱등 |
| `postDocumentNormalizer.test.cjs` | 9 | 실제 Tiptap 스키마에서 번호 목록 `start`/`type`·코드 언어·긴 alt 정규화, 중복 이미지(기존 이미지 유지)·키 없는 이미지 제외, 선택된 사본 제외 후 커서 위치(원본 이미지 보존), 유일한 자식 제외 시 문서 구조 유지, 정상 입력은 추가 트랜잭션 없음 |
| `post.test.cjs` | 2 | 일반 첨부 0/1/5 병합 경계와 6번째 절단, 업로드 공개 안내 문구 |

정적 합계는 48건입니다. 2026-09-25 실행에서 `npm test` 48/48 통과(inlineImageDraft 15·postDetail 16·postDocument 6·postDocumentNormalizer 9·post 2), `npm run typecheck`·`npm run build` 성공을 확인했습니다. 이전 기록: 2026-09-22 `npm test` 36/36 통과, build client module 284개·SSR 1개·route 5개 prerender.

## 추가 회귀 검증

`SecurityAndStorageRegressionTest`(10건)는 삭제 계정의 모든 쓰기·업로드 차단, username 재사용 시 토큰/소유권 분리, 일반·inline 첨부의 rollback 정리와 커밋 후 삭제 재시도, 긴 ZIP 제목을 검증합니다. `front/tests/postDetail.test.cjs`는 실제 Pinia store에서 응답 순서 역전·조회 실패·저장 중 이동·ID 불일치·계정 ID 권한 표시와 rich 글 생성·수정의 inline 업로드 집합·합계 한도·실패 시 초안 유지 계약을 검증합니다.

`PostgresMigrationTest`, `PostgresUploadFinalizeTest`, `PostgresInlineImageLifecycleTest`는 `LLM_TEST_POSTGRES_URL=jdbc:postgresql://127.0.0.1:<임시포트>/postgres`가 있을 때만 실행하고, 변수가 없으면 JUnit 조건에 따라 각각 1개·3개·4개 테스트를 건너뜁니다. 별도 disposable PostgreSQL의 postgres 사용자와 격리된 무작위 schema·임시 파일 저장소를 사용하며 운영 DB를 지정하지 않습니다. Migration 테스트는 V1~V16 적용 후 V17~V19 3개 migration upgrade·소유권 백필·삭제 FK·V19 `body_format`/`attachment_kind` 기본값과 check·partial unique·Hibernate validate를 확인하고, finalize 테스트는 (a) 본문 flush 이후 실패, (b) 명시적 session/part 삭제 flush 뒤 본문 실패, (c) 실제 commit 단계 지연 삭제 실패를 확인합니다. (b)는 테스트 전용 repository decorator가 실제 삭제와 `EntityManager.flush()` 뒤 SQL 조회로 삭제 상태를 확인하고 본문 예외를 주입하며, 운영 코드에는 테스트 hook을 추가하지 않습니다. 신규 `PostgresInlineImageLifecycleTest`는 레거시 plain fixture 조회, rich 글 생성·수정·삭제, rollback 파일 정리, 커밋 후 파일 삭제 실패·재시도를 확인합니다.

2026-09-08 확인은 Docker 이미지 `postgres:1.0`(PostgreSQL 17.10)을 localhost port `55432`에 둔 환경에서 수행했습니다. PostgreSQL 17.10 disposable test image는 stated production PostgreSQL 18을 대체하지 않습니다. focused 결과는 `PostgresMigrationTest` 1/1 통과와 `PostgresUploadFinalizeTest` 3/3 통과입니다. 환경변수 없이 실행한 전체 `clean test`는 **147개 발견, 143개 통과, 4개 skipped, 실패 0개·오류 0개**였고, skipped 4개는 두 PostgreSQL 조건부 테스트입니다. 실제 PostgreSQL focused 실행에서는 해당 4개가 모두 통과했습니다.

최신 전체 `clean test`(2026-09-22, disposable `postgres:18` PostgreSQL 18.6, host port 55505)는 193건 발견·193건 통과·PostgreSQL 조건부 skip 0·실패 0·오류 0입니다. 같은 URL의 focused PostgreSQL JUnit은 기존 4건(`PostgresMigrationTest` 1건, `PostgresUploadFinalizeTest` 3건)과 신규 4건(`PostgresInlineImageLifecycleTest`)을 합쳐 8건 모두 통과했고, disposable DB에는 V1~V19(schema-creation 포함 20행)가 적용됐으며 Hibernate validate도 확인했습니다.

실제 전체 실행 예시:

```powershell
cd back
.\gradlew.bat '-Porg.gradle.java.installations.paths=<JAVA_25_HOME>' clean test
```

focused PostgreSQL 실행 예시(환경변수 값 자체는 기록하거나 공유하지 않음):

```powershell
cd back
$env:LLM_TEST_POSTGRES_URL = 'jdbc:postgresql://127.0.0.1:<임시포트>/postgres'
.\gradlew.bat '-Porg.gradle.java.installations.paths=<JAVA_25_HOME>' test --tests com.llm.app.review.PostgresMigrationTest --tests com.llm.app.review.PostgresUploadFinalizeTest --tests com.llm.app.review.PostgresInlineImageLifecycleTest
Remove-Item Env:LLM_TEST_POSTGRES_URL
```
| 변경 유형 | 필수 검증 |
| --- | --- |
| 백엔드 controller/service/domain | `cd back && .\gradlew.bat '-Porg.gradle.java.installations.paths=<JAVA_25_HOME>' clean test` |
| DB migration | 백엔드 테스트와 실제 PostgreSQL 연결 검증. 루트 compose에는 PostgreSQL 서비스가 없으므로 로컬/EC2의 외부 DB 또는 별도 PostgreSQL을 준비 |
| 프론트 UI/API client | `cd front && npm test && npm run typecheck && npm run build` |
| rich 본문·inline 이미지 backend 계약 | `cd back && .\gradlew.bat '-Porg.gradle.java.installations.paths=<JAVA_25_HOME>' clean test`와 focused PostgreSQL(`PostgresMigrationTest`, `PostgresUploadFinalizeTest`, `PostgresInlineImageLifecycleTest`), content endpoint header·bytes 확인 |
| rich 본문·inline 이미지 frontend UI | `cd front && npm test && npm run typecheck && npm run build` 후 붙여넣기·파일 선택을 포함한 paste/create/edit/delete 브라우저 자동화 smoke |
| Dockerfile/compose | 프론트는 `cd front && npm run build` 후 `docker compose --profile build build front-build`. 백엔드는 `build back-build`. 이어서 `docker compose up -d --wait` |
| EC2 배포 절차 | `auto_default` 존재와 `docker compose --project-name ubuntu --env-file .env -f docker-compose.yml config --quiet`를 확인하고 Compose pull/up/ps 및 8083 health 경로 검증 |
| 운영 env 변경 | 컨테이너 재기동, `docker inspect`, health, 기능 smoke test |
| AI provider 변경 | provider별 성공/오류 smoke test |
| 업로드 도구 변경 | 작은 ZIP과 큰 ZIP 업로드, 중단 후 재개 테스트 |

## 수동 smoke test

아래 절차는 compose 스택이 실행 중이고 외부 PostgreSQL이 준비된 환경에서 수행합니다. 2026-09-08에는 최신 백엔드 이미지로 compose 기동, 컨테이너 health, 8083 health와 공개 게시글 목록 조회를 확인했습니다. 이어 격리 네트워크(back 18080, front proxy 18083)에서 실제 HTTP 로그인과 `upload_zip_post.py`의 AES-GCM alias 세션 생성, 암호화 청크 1건, finalize, 다운로드를 성공했습니다. `upload_zip_post.py`는 exit 0이었고, 결과 게시글은 `FILE_CONVERSION_REQUEST`, `conversionReady=true`, `authorUserId=2`, 첨부 `/api/v1/posts/1/attachments/1`이었습니다. 원본·다운로드 ZIP SHA-256 `a7a184d93123d7f52442f8bd6b4897f3665cc8f00cbc7aa647699b48279f2904`가 일치하고 바이트 비교 및 다운로드 HTTP 200/application/zip 길이 비교가 통과했습니다. 정리 후 `upload_sessions=0`, `upload_session_parts=0`, 세션 임시 volume empty, 영구 첨부 volume에 ZIP이 남았습니다.

1. `docker compose up -d --wait`
2. `http://localhost:8083` 접속
3. 기본 관리자 계정으로 로그인
4. 게시글 작성
5. 게시글 상세 공개 URL 확인
6. 첨부파일 업로드/다운로드 확인
7. 댓글 작성/수정/삭제 확인
8. 검색 확인
9. AI 답변 endpoint가 `410 Gone` / `AI_REPLY_DISABLED`를 반환하는지 확인(외부 provider 호출 없음)
10. `front/public/upload_zip_post.zip`에서 스크립트를 추출해 작은 ZIP 업로드 확인
11. 일반 글 상세(로그인·공개)에서 본문 복사 → 붙여넣기 = 화면 본문; 변환글에서는 버튼 없음

이미지 본문 기능의 paste/create/edit/delete 절차와 자동화·수동 구분은 아래 '브라우저 자동화 smoke (Playwright)' 절을 따릅니다.

## 브라우저 자동화 smoke (Playwright)

UI·브라우저 동작과 end-to-end 흐름은 Playwright 1.62.1 실행 환경(임시 설치, 저장소 dependency 아님)을 재사용해 자동화합니다. 자동화 가능한 시나리오는 assertion으로 검증하며 수동 눈대중 확인만으로 완료 처리하지 않습니다.

- DOM 상태와 API 응답을 assertion하고, 실행 중 `console.error`, page error, request failure, 예상하지 않은 API 4xx·5xx를 수집합니다.
- 오류 응답 자체가 계약인 음수 시나리오는 기대 status와 무변경 상태를 명시적으로 assertion하고 실패 건수에서 제외합니다.
- browser smoke를 위해 Playwright를 `front/package.json`이나 lockfile에 새 dependency로 추가하지 않습니다.
- native OS 한글 IME 조합, OS clipboard 권한 UI, 실제 모바일 기기처럼 자동화할 수 없는 항목만 수동 확인하고, 자동화 한계와 실제 결과를 구분해 기록합니다.
- 기존 Playwright 실행 환경을 사용할 수 없으면 성공을 추정하지 않고 `차단`으로 기록합니다.

paste/create/edit/delete 최종 smoke 절차:

1. 로그인
2. 텍스트-이미지-텍스트 글 생성
3. 로그인 상세·공개 상세 조회
4. content endpoint headers·bytes 확인
5. 기존 이미지 1개 삭제, 신규 이미지 2개 추가 수정(붙여넣기 1건 + "본문 이미지 추가" 버튼 파일 선택 1건)
6. 새로고침 후 순서·이미지 유지 확인
7. 일반 첨부 다운로드 유지 확인
8. `FILE_CONVERSION_REQUEST` ZIP 조회·다운로드 회귀 확인
9. 삭제 후 metadata와 삭제 대기열·실파일 상태 확인

등록 전 create 요청 0건, 붙여넣기 직후 `blob:` 표시, 저장 실패 시 편집 상태·blob 유지 후 재시도, 저장 성공·정상 이탈·unmount 시 object URL 정확히 1회 해제가 함께 assertion 대상입니다. content endpoint headers·bytes와 DB metadata, 삭제 대기열·실파일 상태처럼 브라우저 DOM 밖의 항목은 JUnit이나 직접 HTTP·PostgreSQL 검증으로 보완하고 Playwright 결과와 구분해 기록합니다. 이 기능의 완료 조건은 8083 경유 health와 이 paste/create/edit/delete smoke 통과입니다. 파일 선택 버튼으로 추가하는 경로는 같은 등록·삽입 로직을 공유하며, 2026-09-25 사용자 수동 확인에서 정상 동작했습니다. 이 경로의 Playwright 자동화 smoke는 아직 재실행하지 않았습니다.

2026-09-25 편집기 정규화 확인: disposable `postgres:18`과 로컬 8082/5174에서 내장 브라우저로 `0. ` 입력, `<ol type="a">` 붙여넣기, 편집기 안 이미지 복사·붙여넣기(중복 제외 안내, 원본 유지) 후 새 글 등록과 수정 저장을 수행하고, API로 저장된 본문(번호 목록 `start=1`/`type=null`, 이후 입력 문장, inline 이미지 1개)을 확인했습니다. console error 0건. 붙여넣기는 합성 `ClipboardEvent`로 수행했고 OS 클립보드는 거치지 않았습니다.

2026-09-25 요청 한도·오류 응답 확인: disposable `postgres:18`, 로컬 8082(`APP_UPLOAD_SESSIONS_MAX_DECODED_CHUNK_SIZE=100MB`로 기동해 11,249,976바이트 상한 경고 확인)와 5174에서 수행했습니다. `upload_zip_post.py`로 최대 청크(`--chunk-size-base64-chars 14999968`) 25MB ZIP 3청크 업로드·finalize 성공, 한 단계 큰 청크(14999972)는 세션 생성에서 400, 기본 청크 3MB ZIP 업로드 성공을 확인했습니다. 내장 브라우저에서 두 ZIP 다운로드 SHA-256이 원본과 일치했고, 글쓰기 화면에서 90만 자 본문(Base64 약 3.67MB) 등록이 201로 성공했으며, 405(`Allow: POST, GET`)·415·깨진 multipart 400을 확인했습니다. 백엔드 로그 오류 0건.

2026-09-22 최종 통합 결과: 현재 소스로 재빌드한 이미지로 compose 스택을 기동해 8083 경유 HTTP smoke 38/38 통과, Playwright 최종 browser smoke 112/112 assertion 통과(disposable PostgreSQL·로컬 8082/5174, console error·page error·예상 외 4xx·5xx 0건)를 확인했습니다(파일 선택 버튼 도입 이전 실행).

## 실패 분석 기준

- 백엔드 테스트 실패: 실패 테스트 이름, expected/actual, 관련 controller/service를 먼저 확인합니다.
- 프론트 검사/빌드 실패: `nuxi typecheck`의 TypeScript 오류, Vue 컴포넌트 import, `NUXT_PUBLIC_API_BASE`, 정적 생성 로그를 확인합니다.
- 통합 health 실패: `docker compose ps`, `docker compose logs back`, `docker compose logs front` 순서로 확인합니다.
- DB 연결 실패: `APP_DB_HOST`, `APP_DB_NAME`, `APP_DB_SCHEMA`, 네트워크 `auto_default` 존재 여부를 확인합니다.

## 프론트 의존성 보안 점검

```bash
cd /home/yangyag/llm/front
npm audit --omit=dev
npm ls nuxt nitropack archiver archiver-utils readdir-glob zip-stream minimatch brace-expansion vue-tsc --all
```

2026-07-30 기준 `vue-tsc`를 Nuxt와 호환되는 `3.3.8`로 갱신한 뒤 `npm audit --omit=dev`에 남은 high 11건은 하나의 [`brace-expansion` 메모리 고갈(DoS) 권고](https://github.com/advisories/GHSA-mh99-v99m-4gvg)에서 파생됩니다.

- 빌드 경로: `nuxt` → `@nuxt/nitro-server` → `nitropack` → `archiver` → `glob`/`minimatch`/`brace-expansion`
- 직접 선언 의존성으로 표시되는 것은 `nuxt`이고, 취약 구현은 전이 의존성에 있습니다. 기존 `vue-tsc` 2.x 경로는 3.3.8 갱신으로 제거했습니다.
- Docker 최종 단계는 `.output/public`만 `nginx:1.27-alpine`에 복사하므로 Node/Nuxt 의존성은 운영 이미지에 포함되지 않습니다. 따라서 HTTP 런타임 노출은 없고, 신뢰하지 않는 glob 입력을 빌드에 넣을 때의 빌드 가용성 위험으로 분류합니다.
- 당시 최신 Nuxt 3(`3.21.10`)과 Nuxt 4도 같은 전이 경로가 보고되어, 강제 major override나 Nuxt 3 다운그레이드는 적용하지 않습니다. 패치된 Nuxt 3/Nitropack 계열이 나오면 `npm install` 후 typecheck/build/Docker 회귀검증을 수행합니다.

`npm audit fix --force`는 Nuxt/타입 도구의 호환성을 깨뜨릴 수 있으므로 사용하지 않습니다.

## 문서 변경 검증

문서만 바꾼 경우에도 최소한 다음을 확인합니다.

```bash
find docs -maxdepth 1 -type f -name '*.md' | sort
rg -n 'TO''DO|TB''D|FIX''ME' docs || true
```

문서에 secret 값이 들어가지 않았는지도 확인합니다.

```bash
rg -n 'API_''KEY=.*[A-Za-z0-9_-]{12,}|SEC''RET=.*[A-Za-z0-9_-]{12,}|PASS''WORD=.*[A-Za-z0-9_-]{8,}|TO''KEN=.*[A-Za-z0-9_-]{12,}' docs || true
```
