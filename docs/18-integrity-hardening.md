# 계정 ID 전환과 첨부파일 일관성

2026-09-05 구현. 소스 변경과 로컬 검증 결과를 정리한 문서입니다.

## 변경되는 동작

- 공통 인증은 서명·만료·토큰 버전과 현재 계정 존재 여부를 확인합니다. 삭제 계정은 게시글·댓글·업로드를 포함한 모든 보호 API에서 401로 거부됩니다.
- JWT subject는 고유 계정 ID이며 `tokenVersion=2`를 사용합니다. **이전 username 토큰은 거부하므로 배포 후 재로그인이 필요합니다.** 업로드 도구의 저장 JWT도 새로 발급받아야 합니다.
- 로그인/me 응답에 `userId`, 게시글 목록·상세·댓글에 `authorUserId`가 추가됩니다. username은 표시용으로 보존합니다. 프론트 권한 표시도 ID로 비교합니다.
- V17은 기존 표시 이름과 계정 생성 시점을 대조하여 소유권을 연결합니다. 이름이 같아도 계정이 콘텐츠보다 나중에 생성됐다면 연결하지 않습니다. 연결되지 않은 글·댓글은 관리자만 관리할 수 있고 업로드 세션은 만료 정리됩니다. AI 댓글은 소유자를 연결하지 않습니다.
- V18의 삭제 대기열은 DB 커밋 후 파일을 삭제하고 실패하면 재시도합니다. 신규 파일은 트랜잭션 롤백 시 정리하며 파일 삭제 실패 시 대기열을 남깁니다. DB 장애로 대기열 등록까지 실패하면 오류 로그를 확인하고 첨부 metadata와 실제 파일을 대조해야 합니다.
- 상세 화면은 최신 조회만 반영하며 실패하면 이전 데이터를 비웁니다. 수정·삭제·댓글 요청은 화면의 글 ID가 선택된 ID와 일치할 때만 보냅니다. 저장 도중 다른 글로 이동한 경우 이전 응답이 새 화면을 덮어쓰지 않습니다.
- ZIP 원본 이름은 보존하며 자동 제목만 200자 이내로 제한합니다.

## 검증

Windows의 Gradle 실행 JVM은 그대로 두고 컴파일·테스트 toolchain 경로를 지정할 수 있습니다.

```powershell
cd C:\dev\llm\back
.\gradlew.bat clean test '-Porg.gradle.java.installations.paths=C:/jdk/jdk-25.0.4.1+1'
cd ..\front
npm test
npm run typecheck
npm run build
```

PostgreSQL 검증은 일회용 컨테이너에서만 수행합니다. 다음 컨테이너는 localhost의 임시 포트에만 노출되며 애플리케이션 볼륨을 연결하지 않습니다. 이름이 이미 사용 중이면 새 이름을 선택합니다.

```powershell
docker run --rm -d --name llm-validation-postgres -e POSTGRES_HOST_AUTH_METHOD=trust -p 127.0.0.1::5432 postgres:17-alpine
docker port llm-validation-postgres 5432/tcp
docker exec llm-validation-postgres pg_isready -U postgres
# 위 출력의 포트를 사용. 환경 변수는 이 PowerShell 프로세스에만 적용합니다.
$env:LLM_TEST_POSTGRES_URL = 'jdbc:postgresql://127.0.0.1:<임시포트>/postgres'
cd C:\dev\llm\back
.\gradlew.bat clean test '-Porg.gradle.java.installations.paths=C:/jdk/jdk-25.0.4.1+1'
Remove-Item Env:\LLM_TEST_POSTGRES_URL
# 위에서 만든 일회용 컨테이너만 종료합니다.
docker stop llm-validation-postgres
```

2026-09-05 당시 검증 결과: 백엔드 135개(실제 PostgreSQL 마이그레이션·Hibernate validate 포함) 통과, 프론트 회귀 테스트 7개·typecheck·build 통과. 최신 Spring Modulith 후속 검증은 아래 별도 기록을 참조합니다.

## Spring Modulith 후속 검증 — 2026-09-08

- `ApplicationModulesDiagnosticTest`의 strict verification과 Java 25 전체 backend `clean test`가 통과했습니다: 147개 발견, 143개 통과, 4개 조건부 건너뜀, 실패 0개·오류 0개.
- disposable PostgreSQL `postgres:1.0`(PostgreSQL 17.10, localhost `55432`)에서 `PostgresMigrationTest` 1/1, `PostgresUploadFinalizeTest` 3/3이 통과했습니다. 운영 기준 PostgreSQL 18과는 별도 테스트 환경입니다.
- finalize 본문 flush 이후 실패, 테스트 전용 repository decorator를 통한 명시적 session/part 삭제 flush 뒤 본문 실패, commit 단계 지연 삭제 실패를 모두 검증했습니다. 시나리오 (b)는 운영 코드에 flush hook을 추가하지 않고 실제 삭제·flush·SQL 조회 주입으로 확인했습니다.
- 최신 `llm-back:1.0` 이미지 재빌드 후 compose를 기동해 `llm-back`·`llm-front` health, front proxy 8083 health(`status=UP`), `GET /api/v1/posts?page=1` 목록 조회를 확인했습니다. 추가 격리 환경에서 실제 인증 ZIP upload/download smoke도 성공했으며, 상세 결과는 docs/10 및 계획서에 기록했습니다.
- 이번 구조 전환은 Flyway V1~V18, 운영 schema, API 경로, 환경변수를 변경하지 않았습니다.

## inline 이미지 파일·metadata 일관성 — 2026-09-21

게시글 rich 본문의 inline 이미지(`INLINE_IMAGE`)는 일반 첨부와 같은 파일 저장소·삭제 생명주기를 사용합니다. 아래 계약은 `BoardService`, `AttachmentFileLifecycle`, `AttachmentDeletionWorker`와 V19 migration에 구현되어 있습니다.

- 수정 시 문서의 `imageKey` 집합이 유지·삭제 계산의 기준입니다. 이 글의 기존 inline key와 이번 요청의 신규 manifest key로만 문서 참조를 해석하고, 새 문서가 참조하지 않게 된 기존 `INLINE_IMAGE` 행은 삭제 대상으로 계산합니다.
- 문서가 참조하는 key가 이 글의 기존 key도 신규 manifest key도 아니면(다른 글의 key, stale·존재하지 않는 key) 400 `INVALID_ATTACHMENT_REQUEST`로 거부합니다. 신규 manifest가 기존 key를 재사용하거나 manifest key가 문서에서 참조되지 않아도 같은 오류로 거부합니다.
- `removeAttachmentIds`는 `DOWNLOAD` 첨부 전용입니다. 이 글에 속하지 않는 ID나 inline 이미지 ID를 넣으면 400 `INVALID_ATTACHMENT_REQUEST`로 거부하며 metadata·파일은 변하지 않습니다.
- 일반 첨부 삭제, 문서에서 빠진 inline 이미지 자동 삭제, 신규 일반 첨부, 신규 inline 이미지를 합친 최종 개수 `기존 - 삭제 + 신규`가 총 5개(`APP_ATTACHMENTS_MAX_COUNT`, 기본 5)를 넘는지 디스크·DB 변경 전에 검증합니다. 초과하면 400 `INVALID_ATTACHMENT_REQUEST`이고 게시글·첨부·파일이 변하지 않습니다.
- 신규 일반 첨부·inline 이미지 파일은 저장 직후 `AttachmentFileLifecycle.trackCreated`에 등록됩니다. 트랜잭션 롤백 시 파일을 삭제하고, rollback 삭제도 실패하면 별도 트랜잭션으로 삭제 대기열에 등록합니다. 트랜잭션 결과를 알 수 없으면(`UNKNOWN`) 파일을 남기고 오류 로그만 기록합니다.
- 기존 metadata 삭제와 `attachment_file_deletions`(V18) 대기열 등록은 같은 트랜잭션에서 처리하고, 커밋 후 실파일 삭제를 즉시 시도합니다. 삭제가 실패해도 대기열이 남고 커밋된 응답은 실패로 바뀌지 않으며, `AttachmentDeletionWorker`가 1분 주기(`@Scheduled(fixedDelay = 60000, initialDelay = 60000)`)로 최대 100건씩 재시도합니다.
- 삭제가 포함된 트랜잭션이 rollback되면 metadata 삭제와 파일 삭제 예약도 함께 취소되어 기존 행·파일이 그대로 유지됩니다. key·한도·권한 검증 실패는 디스크·DB 변경 전에 끝나므로 어느 경로에서도 부분 반영이 없습니다.
- V19의 `attachment_kind`(`DOWNLOAD`/`INLINE_IMAGE`)·`inline_key` check와 `(post_id, inline_key)` partial unique index가 DB 레벨에서 같은 글의 중복 key와 kind-key 불일치를 막습니다.

검증 테스트:

- `BoardPostControllerTest`: `inlineManifestDocumentAndFilesMustMatchExactly`(manifest·파일 누락, key 불일치, 중복 index·key, 손상 manifest), `downloadAndInlineImagesShouldShareFiveAttachmentLimit`(5개 허용, 6개 400과 게시글·행·파일 무변경), `updatingInlineImagePostWithSameKeyShouldPreserveFileAndRejectDownloadRemoval`(같은 key 수정 시 id·content URL·bytes 유지, inline ID를 `removeAttachmentIds`로 보내면 400), `inlineImageUpdateShouldKeepRetainedKeysAndReplaceRemovedOnes`(유지 key의 id·저장 경로·content 보존, 빠진 key의 행·content 정리), `inlineImageUpdateShouldRejectKeysOwnedByOtherPosts`(다른 글 key 거부와 무변경), `inlineImageUpdateByNonAuthorShouldBeForbiddenAndLeavePostUntouched`(403·무변경), `inlineImageUpdateShouldBeRejectedForFileConversionRequestPost`(`FILE_CONVERSION_LOCKED`·무변경)
- `SecurityAndStorageRegressionTest`: `newInlineFileIsRemovedWhenTransactionRollsBackAfterSavingItsRow`, `inlineUpdateRollbackRemovesNewFileAndPreservesExistingFile`(신규 파일 삭제, 기존 행·파일·대기열 무변경), `committedInlineRemovalIsRetriedAfterFilesystemFailure`(커밋 후 삭제 실패 → 대기열 유지 → `retryPending` 후 행·파일 삭제)

PostgreSQL에서는 `PostgresMigrationTest`가 V17·V18·V19 적용, 기존 행 default, check·partial unique index 위반의 `SQLException`, Hibernate `ddl-auto=validate`를 확인합니다.

## 배포 시 확인

프론트와 백엔드를 함께 갱신해야 새 ID 응답과 권한 표시가 일치합니다. 기존 배포 절차대로 이미지는 Windows에서 빌드하여 전달하며, 운영 DB 이력과 V17/V18을 대조하고 DB·첨부 볼륨 백업을 확보합니다. 과거 migration은 수정하지 않습니다.

배포 후에는 front proxy 8083 health, 재로그인, 본인/타인 글 권한, ZIP 업로드와 다운로드를 확인합니다. `attachment_file_deletions`에 작업이 계속 남으면 저장 경로·볼륨·파일 권한과 백엔드 로그를 확인합니다.
