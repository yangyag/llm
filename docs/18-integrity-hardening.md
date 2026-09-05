# 계정 ID 전환과 첨부파일 일관성

2026-09-05 구현. 소스 변경·로컬 검증 결과와 같은 날 수행한 운영 배포 기록입니다.

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

## 운영 배포 기록 — 2026-09-05 KST

- 배포 소스: `0f6a2577d8c14417cc345df08d908d9e89210a7e` (`main`, GitHub push 완료).
- 전환 구간: 09:50:54~09:52:05. front/back을 중지하여 일관된 백업을 확보한 뒤 함께 교체했습니다.
- Windows에서 빌드한 이미지를 `docker save` → scp → EC2 `docker load`로 전달했습니다. EC2 배포 디렉터리는 Git checkout이 없는 이미지 실행 환경이며 소스 빌드는 하지 않았습니다.
- 기존 운영 compose와 로컬 파일의 SHA-256이 일치하여 compose와 운영 `.env`는 변경하지 않았습니다. 프로젝트 `ubuntu`, 외부 네트워크 `auto_default`, 기존 첨부·업로드 볼륨을 유지했습니다.

| 서비스 | EC2 실행 이미지 ID |
| --- | --- |
| `llm-front:1.0` | `sha256:31d86731ffc5cf6d7f7523a026d1df034be7f3691d915c7e646ac93ab3a93a23` |
| `llm-back:1.0` | `sha256:6ec3611c35a0e1e64be5c6129a1206d75e4d9d96e2fa692f4b41764df35c8354` |

### 백업·복구 자료

- 운영 백업: `/home/ubuntu/llm/backups/20260905-0f6a257/` (약 177 MiB). DB custom-format dump, 첨부·업로드 볼륨 archive, 기존 설정과 이미지 ID를 보관했습니다.
- 백업 디렉터리는 700, DB dump와 환경 설정 사본은 600 권한입니다. dump 목록 읽기, tar 목록 읽기 및 archive SHA-256 검증을 통과했습니다.
- 배포 파일: `/home/ubuntu/llm/releases/20260905-0f6a257/llm-release.tar`. SHA-256: `709f429f1c9b77a8b65ff8e5125a10d08b0b51a74be4f0ed63bcedeefcb46643`.
- 이전 이미지는 `llm-front:rollback-20260905-0f6a257`, `llm-back:rollback-20260905-0f6a257`로 보존했습니다. 자동 기동 실패 복구는 이전 이미지 재기동이며 DB restore는 자동 수행하지 않습니다. 이번 배포에서는 복구 절차를 실행하지 않았습니다.

### 확인 결과

- 운영 V1~V16 description/checksum 일치 확인 후 V17·V18 적용 성공, 전체 18개 migration validate 성공.
- 게시글 39개·댓글 9개·첨부 metadata 31개 유지. 게시글 39개와 일반 댓글 5개 모두 계정 ID 연결 완료. 첨부 파일 31개의 전후 SHA-256 일치, 삭제 대기열 0건.
- `llm-front`, `llm-back`, `yangyag-postgres` 모두 healthy. 배포 직후 백엔드 ERROR 로그와 컨테이너 재시작 횟수 0.
- EC2 내부 `http://127.0.0.1:8083/api/v1/health`와 외부 `https://yangyag.duckdns.org/api/v1/health` 모두 UP.
- HTTPS 메인·로그인 페이지 200, 제공 HTML과 로컬 빌드의 SHA-256 일치. 게시글 목록·상세의 계정 ID 응답과 기존 첨부 다운로드(17,347,227 bytes) 확인.
- 무인증 `/auth/me`, 업로드 세션 조회는 401. 실제 사용자 로그인·쓰기·ZIP 업로드 E2E는 운영에서 실행하지 않았습니다.
- 배포 직전 PostgreSQL 18을 포함한 백엔드 135개 테스트 통과. 프론트 7개 테스트, typecheck, build 통과.
- 점검 PC에서 공인 IP의 8083 직접 연결은 timeout이었고 HTTPS 도메인은 정상입니다. 네트워크 노출 설정은 변경하지 않았습니다.

기존 웹 로그인과 업로드 도구 JWT는 다시 발급받아야 합니다.
