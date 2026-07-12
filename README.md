# FamilyAlbum

가족만 볼 수 있는 비공개 아기 사진/동영상 앨범 서비스입니다.

사진과 동영상 원본은 서버 디스크에 저장하지 않고 S3 호환 오브젝트 스토리지에 보관합니다. 웹에서는 변환본을 재생하고, 다운로드할 때만 원본 접근 URL을 짧은 시간 동안 발급하는 구조를 목표로 합니다.

## 기술 스택

| 영역 | 기술 |
| --- | --- |
| Backend | Spring Boot 3, Java 21, Spring Web, Spring Data JPA |
| Frontend | Vue 3, Vite |
| Database | PostgreSQL |
| Storage | Amazon Lightsail Object Storage, S3 compatible API |
| Deploy | Docker Compose, Nginx, GitHub Actions, GitHub Container Registry |

## 구조

```text
browser
  -> nginx / Vue frontend
  -> Spring Boot API
  -> PostgreSQL
  -> Lightsail Object Storage
```

업로드 흐름:

```text
1. 브라우저가 Spring API에 업로드 URL 요청
2. Spring이 권한과 메타데이터를 확인하고 presigned PUT URL 발급
3. 브라우저가 Object Storage로 직접 업로드
4. 브라우저가 Spring API에 업로드 완료 알림
5. Spring이 DB의 미디어 상태를 갱신
```

현재 저장 경로 규칙:

```text
originals/yyyy/mm/dd/<uuid>.<ext>
variants/yyyy/mm/dd/<uuid>-1080p.mp4
variants/yyyy/mm/dd/<uuid>-720p.mp4
thumbs/yyyy/mm/dd/<uuid>.jpg
```

## 프로젝트 구성

```text
backend/                 Spring Boot API
frontend/                Vue 3 web app
docker-compose.yml       로컬 PostgreSQL 실행용
docker-compose.prod.yml  Lightsail 운영 배포용
.github/workflows/       GitHub Actions 배포 workflow
docs/                    서버 배포 문서
```


## 앱 타이틀 설정

서비스 이름과 문구는 `frontend/public/app-config.json`에서 런타임으로 주입합니다. 포크해서 사용할 때 이 파일만 바꾸면 빌드 코드 수정 없이 제목을 바꿀 수 있습니다.

```json
{
  "appTitle": "지웅이 성장일기",
  "appSubtitle": "우리 가족이 함께 기록하는 사진과 동영상 앨범",
  "babyName": "지웅이"
}
```
## 로컬 실행

### 1. PostgreSQL 실행

```powershell
cd C:\projects\familyalbum
docker compose -p family-album up -d postgres
```

### 2. 백엔드 실행

```powershell
cd C:\projects\familyalbum\backend
$env:JAVA_HOME='C:\Users\Wansu\.jdks\openjdk-26.0.1-1'
$env:AWS_ACCESS_KEY_ID='your-access-key'
$env:AWS_SECRET_ACCESS_KEY='your-secret-key'
$env:AWS_REGION='ap-northeast-2'
$env:S3_BUCKET='your-bucket-name'
$env:S3_ENDPOINT='https://s3.ap-northeast-2.amazonaws.com'
.\mvnw.cmd spring-boot:run
```

백엔드 기본 주소:

```text
http://localhost:8080
```

헬스 체크:

```text
http://localhost:8080/api/health
```

### 3. 프론트엔드 실행

```powershell
cd C:\projects\familyalbum\frontend
npm install
npm run dev
```

프론트엔드 기본 주소:

```text
http://localhost:5173
```

Vite 개발 서버는 `/api` 요청을 `http://localhost:8080`으로 프록시합니다.

## 환경변수

로컬 참고용 예시는 `.env.example`에 있습니다.

운영 서버에서는 `/opt/familyalbum/.env`를 사용합니다. 이 파일은 GitHub에 올리지 않습니다.

```env
POSTGRES_DB=family_album
POSTGRES_USER=familyalbum
POSTGRES_PASSWORD=replace-with-strong-password

AWS_ACCESS_KEY_ID=replace-me
AWS_SECRET_ACCESS_KEY=replace-me
AWS_REGION=ap-northeast-2
S3_BUCKET=replace-with-your-bucket-name
S3_ENDPOINT=https://s3.ap-northeast-2.amazonaws.com

APP_CORS_ALLOWED_ORIGINS=https://album.joopapa.com
```

## 운영 배포

운영 배포는 Lightsail 서버의 Docker Compose와 GitHub Actions를 기준으로 합니다.

현재 운영 구조:

```text
host nginx: 80/443
FamilyAlbum frontend: 127.0.0.1:8081 -> container 80
FamilyAlbum backend: Docker internal 8080
FamilyAlbum postgres: Docker internal 5432
```

GitHub Actions는 `main` 브랜치에 push되면 다음을 수행합니다.

```text
1. backend Docker image build
2. frontend Docker image build
3. GitHub Container Registry push
4. Lightsail 서버에 docker-compose.prod.yml 업로드
5. 서버에서 docker compose pull/up 실행
```

필요한 GitHub Secrets:

```text
LIGHTSAIL_HOST
LIGHTSAIL_USER
LIGHTSAIL_SSH_KEY
```

자세한 서버 설정은 [docs/lightsail-deploy.md](docs/lightsail-deploy.md)를 참고합니다.

## 주요 API

```text
GET  /api/health
GET  /api/media
POST /api/media/upload-url
POST /api/media/upload-complete
POST /api/media/{assetId}/download-url
```

## 검증 명령어

백엔드 테스트:

```powershell
cd C:\projects\familyalbum\backend
$env:JAVA_HOME='C:\Users\Wansu\.jdks\openjdk-26.0.1-1'
.\mvnw.cmd test
```

프론트엔드 빌드:

```powershell
cd C:\projects\familyalbum\frontend
npm run build
```

운영 Compose 문법 확인:

```powershell
cd C:\projects\familyalbum
$env:POSTGRES_PASSWORD='dummy'
$env:S3_BUCKET='dummy'
$env:AWS_ACCESS_KEY_ID='dummy'
$env:AWS_SECRET_ACCESS_KEY='dummy'
$env:APP_CORS_ALLOWED_ORIGINS='https://album.joopapa.com'
$env:IMAGE_BACKEND='family-backend:test'
$env:IMAGE_FRONTEND='family-frontend:test'
docker compose -f docker-compose.prod.yml config
```

## 다음 개발 과제

- 가족 초대 코드와 로그인
- 앨범/월별 타임라인
- 이미지 썸네일 생성
- 동영상 1080p/720p 변환 작업
- 원본 다운로드 권한 제어
- 댓글과 좋아요
- 모바일 PWA 설치 지원