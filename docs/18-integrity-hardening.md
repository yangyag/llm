# 계정 ID 전환과 첨부파일 일관성

2026-09-05 구현. 이 문서는 소스 변경 및 로컬 검증 결과이며 운영 배포 완료 기록은 아닙니다.

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

로컬 검증 결과: 백엔드 135개(실제 PostgreSQL 마이그레이션·Hibernate validate 포함) 통과, 프론트 회귀 테스트 7개·typecheck·build 통과.

## 배포 시 확인

프론트와 백엔드를 함께 갱신해야 새 ID 응답과 권한 표시가 일치합니다. 기존 배포 절차대로 이미지는 Windows에서 빌드하여 전달하며, 운영 DB 이력과 V17/V18을 대조하고 DB·첨부 볼륨 백업을 확보합니다. 과거 migration은 수정하지 않습니다.

배포 후에는 front proxy 8083 health, 재로그인, 본인/타인 글 권한, ZIP 업로드와 다운로드를 확인합니다. `attachment_file_deletions`에 작업이 계속 남으면 저장 경로·볼륨·파일 권한과 백엔드 로그를 확인합니다.
