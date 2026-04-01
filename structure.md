# 프로젝트 구조 정리

## 개요
- 이 저장소에는 프로젝트 구조만 전용으로 정리한 문서는 없었습니다.
- 구조 설명의 주요 근거는 루트 `README.md`의 `디렉터리 구조`, 루트 `AGENTS.md`의 `디렉터리 규칙`, 그리고 `front/README.md`, `back/README.md`입니다.
- 아래 내용은 위 문서와 실제 파일 배치를 함께 확인해서 정리한 현재 기준 구조입니다.

## 루트 구조
```text
llm/
  front/                    # React + Vite 프론트엔드
  back/                     # Spring Boot 백엔드
  aws/                      # AWS/EC2 배포용 compose 파일
  db/                       # PostgreSQL 이미지 구성
  etc/                      # 보조 스크립트/샘플 파일
  docker-compose.yml        # 로컬 통합 실행
  docker-compose.ec2.yml    # EC2 배포용 compose
  llm.env.example           # 환경 변수 예시
  llm.env                   # 로컬/배포 실행용 환경 변수
  README.md                 # 저장소 개요 및 실행 방법
  AGENTS.md                 # 작업 규약 및 역할 정의
  structure.md              # 현재 문서
```

## 디렉터리별 역할

### `front/`
- 역할: UI, 라우팅에 가까운 화면 전환, API 연동, 정적 빌드 산출물 관리
- 기술 스택: React 18, Vite 5, Nginx
- 현재 확인된 핵심 파일 구조:

```text
front/
  Dockerfile
  README.md
  index.html
  nginx.conf
  package.json
  vite.config.js
  public/
    encode_zip_to_base64.zip
  src/
    main.jsx                # 앱 진입점
    App.jsx                 # 인증 상태 분기 및 앱 셸
    api.js                  # 백엔드 API 호출 모듈
    styles.css              # 전역 스타일
    pages/
      LoginPage.jsx         # 로그인 화면
      WelcomePage.jsx       # 로그인 후 메인 화면
```

### `back/`
- 역할: REST API, 인증, 게시글/답변 도메인 처리, 첨부파일 처리, AI 연동, 테스트
- 기술 스택: Spring Boot 3.5.11, Java 25, JUnit/MockMvc, Flyway
- 현재 확인된 핵심 구조:

```text
back/
  Dockerfile
  README.md
  build.gradle
  settings.gradle
  gradlew
  gradle/
    wrapper/
  src/
    main/
      java/com/llm/app/
        LlmApplication.java
        auth/              # 관리자 인증, JWT, 로그인 API
        board/
          ai/              # AI 답변 생성 인터페이스/구현
          controller/      # 게시글/답변 API 엔드포인트
          dto/             # 요청/응답 DTO
          exception/       # 도메인 예외
          model/           # 엔티티/도메인 모델
          repository/      # JPA 저장소/프로젝션
          service/         # 게시판 비즈니스 로직
        common/
          config/          # 공통 설정
          web/             # 헬스 체크, 예외 응답, 웹 설정
      resources/
        application.properties
        db/migration/      # Flyway 마이그레이션
        templates/
    test/
      java/com/llm/app/
        auth/              # 인증 API 테스트
        board/controller/  # 게시판 API 테스트
        common/web/        # 헬스 체크 테스트
      resources/
        application.properties
```

### `aws/`
- 역할: AWS/EC2 환경에서 사용할 배포용 compose 설정 보관
- 현재 파일:

```text
aws/
  docker-compose.ec2.yml
```

### `db/`
- 역할: PostgreSQL 컨테이너 이미지 구성
- 현재 파일:

```text
db/
  Dockerfile
```

### `etc/`
- 역할: 보조 유틸리티와 샘플 파일 보관
- 현재 파일:

```text
etc/
  encode_zip_to_base64.py
  encode_zip_to_base64.zip
```

## 실행/배포 기준 파일
- `docker-compose.yml`: 로컬 통합 실행용
- `docker-compose.ec2.yml`: EC2 배포용
- `llm.env.example`: 환경 변수 템플릿
- `llm.env`: 실제 실행 환경 변수
- `front/Dockerfile`: 프론트 이미지 빌드
- `back/Dockerfile`: 백엔드 이미지 빌드
- `db/Dockerfile`: DB 이미지 빌드

## 생성 산출물과 작업 시 유의점
- `front/dist/`: 프론트 빌드 결과물
- `front/node_modules/`: 프론트 의존성 디렉터리
- `back/build/`: 백엔드 빌드 결과물
- `back/.gradle/`: Gradle 캐시/작업 디렉터리
- 위 디렉터리들은 소스 구조 설명의 중심이 아니라 생성 산출물로 분리해서 보는 것이 적절합니다.

## 문서 기준 요약
- 루트 관점 구조 설명: `README.md`
- 작업 규약과 디렉터리 역할: `AGENTS.md`
- 프론트 개별 설명: `front/README.md`
- 백엔드 개별 설명: `back/README.md`
- 현재 문서는 위 문서들과 실제 파일 배치를 합쳐 한 번에 볼 수 있도록 정리한 구조 요약본입니다.
