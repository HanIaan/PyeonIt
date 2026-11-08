# Q2 배포 준비 (실제 배포 금지)

이 문서는 로컬 빌드·테스트와 승인 전 검토용이다. 실제 서버 접속·실제 키·실제 도메인 요청은 수행하지 않는다. **실제 배포 단계: 승인 후 진행.** 아래 명령은 가짜 값으로 작성한 로컬 검증 예시이며 배포 명령이 아니다.

## 백엔드 환경변수

`application-prod.yml`은 `SPRING_PROFILES_ACTIVE=prod`로 적용한다. dev/test를 함께 활성화하지 않는다(dev 로그인 컨트롤러는 dev 프로필에만 존재한다). 기본값 없는 필수 설정이 누락되면 기동 실패한다. 빈 비밀 값도 사용하지 않는다. DB 연결과 Flyway 검증도 기동 조건이다.

| 변수 | 필수/선택 | 설명·가짜 예시 |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | 필수 | `prod` |
| `DB_URL` | 필수 | `jdbc:postgresql://db.example.invalid:5432/pyeonit` (승인 후 TLS 연결 정책 확인) |
| `DB_USERNAME` | 필수 | `fake_app_user` |
| `DB_PASSWORD` | 필수·비밀 | `fake-password-do-not-use` |
| `PYEONIT_BLOCKLIST_HMAC_KEY` | 필수·비밀 | `fake-hmac-key-do-not-use`; 운영에는 충분한 엔트로피의 전용 랜덤 키 |
| `PYEONIT_COOKIE_DOMAIN` | 필수 | `.example.invalid`; 운영 구조에서는 `.pyeonit.com` |
| `PYEONIT_CORS_ALLOWED_ORIGINS` | 필수 | `https://web.example.invalid`; 쉼표로 구분, 와일드카드 금지 |
| `PYEONIT_WEB_BASE_URL` | 필수 | `https://web.example.invalid`; OAuth 성공·실패 후 웹 복귀 주소 |
| `PYEONIT_R2_ENDPOINT` | 필수 | `https://r2.example.invalid`; S3 API 엔드포인트, 공개 이미지 URL과 별개 |
| `PYEONIT_R2_BUCKET` | 필수 | `fake-post-images` |
| `PYEONIT_R2_ACCESS_KEY` | 필수·비밀 | `fake-access-key`; 해당 버킷에 필요한 읽기·쓰기·삭제 권한만 |
| `PYEONIT_R2_SECRET_KEY` | 필수·비밀 | `fake-secret-key` |
| `PYEONIT_R2_PUBLIC_URL_BASE` | 필수 | `https://images.example.invalid/posts`; 공개 URL은 이 값 + `/` + 키 |
| `KAKAO_CLIENT_ID` | 제공자 사용 시 필수 | `fake-kakao-client-id`; REST API 키 |
| `KAKAO_CLIENT_SECRET` | 제공자 사용 시 필수·비밀 | `fake-kakao-secret`; 현재 인증 방식 `client_secret_post` |
| `PYEONIT_ADMIN_COMMUNITY_WITHDRAWN_CLEANUP_ENABLED` | 선택 | 기본 `true`; `false`이면 탈퇴 회원 신고·제재 정리 비활성화. 기존 `pyeonit.admin.community.scheduling-enabled=false`도 적용 |
| `PYEONIT_ADMIN_COMMUNITY_WITHDRAWN_CLEANUP_CRON` | 선택 | 기본 `0 0 4 * * *`; UTC 매일 04:00, `-`이면 예약 비활성화 |
| `PYEONIT_ADMIN_COMMUNITY_WITHDRAWN_RETENTION_DAYS` | 선택 | 기본 `365`일, 양수; 탈퇴 시각이 현재−보관기간보다 이전인 회원만 대상 |
| `PYEONIT_ADMIN_COMMUNITY_WITHDRAWN_CLEANUP_BATCH_SIZE` | 선택 | 기본 `500`, 양수; 주기당 신고·제재 합계 행 상한, 신고 먼저 처리하고 잔여는 다음 주기 |
| `SERVER_PORT` | 선택 | 기본 `8081`; Docker healthcheck도 같은 값을 사용 |
| `SERVER_FORWARD_HEADERS_STRATEGY` | 선택 | 기본 `framework`; 승인 후 신뢰 프록시가 외부 전달 헤더를 제거·재작성하도록 구성 |
| `PYEONIT_COMMUNITY_STORAGE` | 선택 | 기본 `r2`; prod에서는 값과 무관하게 R2가 우선 선택됨. dev/test는 기본 LocalImageStorage, 명시적 `r2`일 때만 R2 |
| `PYEONIT_NICKNAME_FORBIDDEN_WORDS` | 선택 | 기본은 application.yml의 금지어 목록; 쉼표 구분 |
| `PYEONIT_COMMUNITY_MAX_IMAGE_BYTES` | 선택 | 기본 `5242880`(5 MiB); 기존 CommunityService의 검증 한도 |
| `PYEONIT_COMMUNITY_IMAGE_DIRECTORY` | 로컬 저장 시 선택 | 기본 JVM 임시 디렉터리 아래 `pyeonit-post-images`; prod의 R2에서는 사용하지 않음 |
| `PYEONIT_PUSH_VAPID_PUBLIC_KEY` | 푸시 사용 시 필수 | 기본 빈 값; 가짜 예시 `fake-vapid-public-key-do-not-use` |
| `PYEONIT_PUSH_VAPID_PRIVATE_KEY` | 푸시 사용 시 필수·비밀 | 기본 빈 값; 가짜 예시 `fake-vapid-private-key-do-not-use`; 로그·저장소에 기록 금지 |
| `PYEONIT_PUSH_VAPID_SUBJECT` | 푸시 사용 시 필수 | 기본 빈 값; 가짜 예시 `mailto:push@example.invalid` |
| `PYEONIT_PUSH_SCHEDULING_ENABLED` | 선택 | 기본 `true`; `false`이면 새 공고·D-1 알림 예약 작업 비활성화 |
| `PYEONIT_PUSH_NOTICE_INTERVAL_MS` | 선택 | 기본 `60000`(밀리초); 새 공고 발송 작업 종료 후 다음 실행까지의 간격 |
| `PYEONIT_PUSH_REMINDER_CRON` | 선택 | 기본 `0 0 9 * * *`; `Asia/Seoul` 기준 매일 09:00에 다음 날 일정의 D-1 알림 발송 |
| `PYEONIT_PUSH_MAX_INDIVIDUAL_NOTICES` | 선택 | 기본 `3`; 회원별 한 주기 새 공고가 상한을 넘으면 목록 링크 알림 1건으로 묶음, 1 이상 |
| `PYEONIT_PUSH_MAX_SUBSCRIPTIONS_PER_USER` | 선택 | 기본 `10`; 사용자당 구독 수 상한, 1 이상 |
| `PYEONIT_PUSH_ALLOWED_ENDPOINT_HOST_SUFFIXES` | 선택 | 기본 `fcm.googleapis.com,updates.push.services.mozilla.com,push.services.mozilla.com,notify.windows.com,push.apple.com`; 쉼표 구분 허용 호스트 접미사 목록 |
| `PYEONIT_SEARCH_RATE_LIMIT_WINDOW` | 선택 | 기본 `60s`; 검색 레이트리밋 창 크기, 양의 시간 값 |
| `PYEONIT_SEARCH_RATE_LIMIT_IP_REQUESTS` | 선택 | 기본 `60`; IP당 창 내 요청 한도(기본 분당 60회), 1 이상 |
| `PYEONIT_SEARCH_RATE_LIMIT_SESSION_REQUESTS` | 선택 | 기본 `30`; 세션당 창 내 요청 한도(기본 분당 30회), 1 이상 |

웹 푸시(B6)는 VAPID 공개키·개인키·subject 중 하나라도 비면 비활성화된다. 이 누락만으로 서버 기동이 실패하지 않으며(다른 필수 설정·DB 조건은 충족해야 함), `GET /api/push/vapid-public-key`는 404를 반환한다. VAPID 키 쌍은 운영자가 별도로 생성·보관하며 위 가짜 예시를 실제 키로 사용하지 않는다. **VAPID 키를 교체하면 기존 구독이 무효가 되므로 사용자 재구독이 필요하다.** 개인키는 비밀 관리 저장소에서 주입하고 로그·저장소에 남기지 않는다.

푸시 endpoint는 HTTPS 및 허용 호스트 검증을 거친다. 호스트가 `PYEONIT_PUSH_ALLOWED_ENDPOINT_HOST_SUFFIXES`의 항목과 정확히 일치하거나 `'.' + 접미사`로 끝날 때만 허용한다. SSRF 방어 목적이므로 허용 범위를 임의로 넓히지 않는다.

검색(B5)은 IP와 세션 한도를 함께 적용하며, 창 크기를 바꾸면 위 요청 한도도 변경된 창 기준으로 적용된다. 프록시 뒤에서는 `SERVER_FORWARD_HEADERS_STRATEGY`(prod 기본 `framework`) 설정이 `request.getRemoteAddr()`에 따른 클라이언트 IP 판정에 영향을 주므로 신뢰 프록시의 전달 헤더 처리와 함께 확인한다.

아래 정책 변수는 환경변수로 외부화되어 있지만 운영에서 표의 기본 정책을 유지한다. 변경은 별도 보안 검토 대상이다.

| 변수 | 기본 정책 |
|---|---|
| `SERVER_SERVLET_SESSION_COOKIE_SECURE` | `true` |
| `SERVER_SERVLET_SESSION_COOKIE_HTTP_ONLY` | `true` |
| `SERVER_SERVLET_SESSION_COOKIE_SAME_SITE` | `lax` |
| `SERVER_ERROR_INCLUDE_STACKTRACE` | `never` |
| `SERVER_ERROR_INCLUDE_MESSAGE` | `never` |
| `SERVER_ERROR_INCLUDE_BINDING_ERRORS` | `never` |
| `SPRING_JPA_HIBERNATE_DDL_AUTO` | `validate` (스키마 자동 변경 금지) |
| `SPRING_FLYWAY_LOCATIONS` | `classpath:db/migration`만, `db/dev` 포함 금지 |
| `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE` | `health`만 |
| `MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS` | `never` |

이 정책 변수는 모두 선택 값이다. R2 속성은 `pyeonit.community.r2.{endpoint,bucket,access-key,secret-key,public-url-base}`로도 주입할 수 있다. R2 서명 리전은 `auto`, path-style 주소를 사용한다. AWS SDK v2 S3와 동기 HTTP 전송을 사용하며 비동기 Netty 의존성은 제외했다. [AWS SDK 엔드포인트 설정 참고](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/endpoint-config.html).

## 웹 환경변수

| 변수 | 필수/선택 | 설명·가짜 예시 |
|---|---|---|
| `NEXT_PUBLIC_API_BASE_URL` | Docker 빌드 필수 | `https://api.example.invalid`; 끝 `/` 없이 지정 |
| `NEXT_PUBLIC_API_MOCK` | 빌드 선택 | 기본 `false`; 운영은 반드시 `false` |
| `NEXT_PUBLIC_ENABLE_DEV_LOGIN` | 빌드 선택 | 기본 `false`; 운영은 반드시 `false` |
| `PORT` | 실행 선택 | 기본 `3000` |
| `HOSTNAME` | 실행 선택 | 기본 `0.0.0.0` (컨테이너 바인딩 주소) |
| `NODE_ENV` | 실행 선택 | 이미지 기본 `production` 유지 |
| `NEXT_TELEMETRY_DISABLED` | 선택 | 이미지 기본 `1` |

**`NEXT_PUBLIC_*`는 Docker `--build-arg`로 전달하며 빌드 시 클라이언트 번들에 고정된다.** 실행 시 환경변수만 바꿔도 주소·모의 데이터·dev 로그인 표시가 바뀌지 않는다. 변경하려면 이미지를 다시 빌드해야 한다. 이 값들은 공개되므로 비밀을 넣지 않는다. Next standalone의 `server.js`로 실행하며 static/public 파일도 이미지에 포함한다. 두 런타임 모두 UID/GID 10001 비루트 사용자다.

푸시 서비스 워커 `/sw.js`는 배포 시 `Cache-Control: no-cache, no-store, must-revalidate` 응답 헤더를 권장한다. iOS에서는 16.4 이상에서 홈 화면에 추가한 PWA에서만 웹 푸시가 동작한다. [WebKit의 iOS 웹 푸시 지원 안내](https://webkit.org/blog/13878/).

## 비밀 값 보관과 키 유지

운영 비밀은 승인된 비밀 관리 저장소/CI 비밀 저장소에 보관하고, 실행 시 환경변수로만 주입한다. 로컬 확인용 파일이 필요하면 저장소 밖의 접근 제한 파일에 둔다. `.env` 커밋·이미지 COPY·Docker ARG/ENV에 비밀 저장·로그/에러/PR/셸 이력에 비밀 출력 모두 금지한다. Docker build context는 각 backend/web 디렉터리이며 `.dockerignore`가 `.env*`와 키 파일을 제외한다. 예시 값은 모두 가짜이며 사용 가능한 자격 증명이 아니다.

`PYEONIT_BLOCKLIST_HMAC_KEY`는 다른 OAuth/R2 키와 분리하고 DB 백업과 함께 복구 가능한 별도 비밀 백업을 유지한다. **분실·교체하면 기존 제재 회피 방지 해시와 더 이상 매칭할 수 없다.** 탈퇴 후 원본 소셜 ID는 삭제되므로 새 키로 일괄 재계산할 수도 없다. 임의 교체를 금지하며 필요 시 조율자의 별도 전환 정책 검토를 받는다. dev/test 전용 키는 운영에서 거부된다.

## 도메인·쿠키·OAuth 검토

운영 전제는 웹 `https://pyeonit.com`, API `https://api.pyeonit.com`이다(설계 표기이며 접속하지 않는다). 세션 쿠키는 Secure=true, HttpOnly=true, SameSite=Lax, 도메인 `.pyeonit.com`; CORS 허용 오리진은 `https://pyeonit.com`, 웹 복귀 주소도 `https://pyeonit.com`으로 승인 후 주입한다. 두 호스트는 HTTPS의 같은 사이트여야 SameSite=Lax 세션을 fetch에 보낼 수 있다. 서로 다른 등록 도메인을 사용하는 구조는 이 설정의 전제가 아니다. API는 credentials 포함 요청과 명시된 오리진만 허용한다. `XSRF-TOKEN`은 기존 보안 설정상 JS가 읽는 CSRF 쿠키이며 HttpOnly 세션 쿠키와 구분한다. 서브도메인 분리 시 웹 API 모듈은 `/api/csrf` 응답 본문의 토큰을 사용할 수 있다.

승인 후 프록시/TLS 설정에서 API의 원래 HTTPS 호스트가 보존되는지 확인해야 한다. OAuth 콜백은 `{API}/login/oauth2/code/kakao`, `{API}/login/oauth2/code/google`이며 코드의 `{baseUrl}`은 전달된 요청 주소에서 만들어진다. 예시는 `https://api.example.invalid/login/oauth2/code/kakao`와 `https://api.example.invalid/login/oauth2/code/google`이다.

- 카카오 개발자 콘솔: 앱/웹 플랫폼 사이트 도메인(웹), 카카오 로그인 활성화, REST API 키와 Client Secret, API의 정확한 Redirect URI(`/login/oauth2/code/kakao`), 동의 항목을 등록·검토한다. 현재 사용자 ID만 저장하며 이메일을 수집하지 않는다. 추가 동의 항목은 임의로 활성화하지 않는다.
- 구글 개발자 콘솔: OAuth 동의 화면(앱 이름, 지원 이메일·문의처, 승인 도메인, 출시 전 검토된 약관/개인정보 URL), 웹 애플리케이션 OAuth 클라이언트, 웹의 승인된 JavaScript 원본, API의 정확한 승인된 리디렉션 URI(`/login/oauth2/code/google`)를 등록·검토한다. 현재 클라이언트 ID가 비면 해당 제공자는 비활성화된다. 실제 키 등록·콘솔 변경은 승인 후 진행한다.

## 로컬 검증 예시

```sh
(cd backend && PYEONIT_BLOCKLIST_HMAC_KEY=q2-fake-test-key-never-use-in-production ./gradlew test)
(cd web && npm ci && npm run lint && NEXT_PUBLIC_API_BASE_URL=https://api.example.invalid NEXT_PUBLIC_API_MOCK=false NEXT_PUBLIC_ENABLE_DEV_LOGIN=false npm run build)
docker build -t pyeonit-backend:q2 backend
docker build --build-arg NEXT_PUBLIC_API_BASE_URL=https://api.example.invalid --build-arg NEXT_PUBLIC_API_MOCK=false --build-arg NEXT_PUBLIC_ENABLE_DEV_LOGIN=false -t pyeonit-web:q2 web
docker image inspect pyeonit-backend:q2 pyeonit-web:q2 --format '{{.RepoTags}} {{.Size}}'
```

전체 백엔드 테스트는 Testcontainers 임시 PostgreSQL을 사용하고 수동 실행용 공유 DB를 지우지 않는다. R2 테스트는 S3Client mock 및 네트워크 호출 없는 빈 선택 검증이다. prod 테스트는 가짜 값과 임시 DB로 dev 로그인 404·R2 선택·정책·CORS·dev seed 미포함을 확인하며, 기존 BlocklistKeyTests가 HMAC 키 누락 기동 실패를 확인한다. 실제 R2 접근은 수행하지 않는다.

## DB 백업·마이그레이션·헬스체크·롤백

승인 후 진행: 변경 직전 PostgreSQL 일관 백업(필요하면 WAL/PITR)과 스키마 이력, 이미지 버전, HMAC 키 복구 가능 여부를 확인하고 별도 환경에서 복원 검증한다. 백업에는 개인정보가 있으므로 암호화·접근 제한·보관/폐기 정책을 적용하고, R2 객체도 DB의 키와 함께 복원할 수 있게 별도 백업/복구 정책을 정한다. 보관 기간과 RPO/RTO는 운영자가 승인해야 한다.

Flyway는 `db/migration`만 적용하며 JPA는 `validate`다. `V<number>__<description>.sql`은 기존 적용 파일을 수정하지 않고 새 번호로만 추가한다. 현재 번호는 V1·V2·V4·V7·V8·V9·V10이며 V3·V5·V6는 미사용 번호다. 새 마이그레이션은 V11부터 추가하고, 머지 순서와 번호가 어긋나면 머지 전에 번호를 조정한다. 중복 번호·빠진 선행 마이그레이션·순서가 뒤집힌 병합은 검증 오류 또는 누락을 만들 수 있으므로 배포 후보 전체의 번호와 Flyway 이력을 먼저 대조한다. 임의 `repair`, out-of-order 활성화, `clean`으로 우회하지 않는다.

검색(B5)에는 PostgreSQL `pg_trgm` 확장이 필요하다. `V10__search_indexes_and_university_aliases.sql`이 `CREATE EXTENSION IF NOT EXISTS pg_trgm`을 실행하므로 DB 사용자에게 확장 생성 권한이 있거나 확장이 사전 설치되어 있어야 한다. 대학 별칭 데이터도 이 마이그레이션에 포함된다.

백엔드 Docker HEALTHCHECK는 내부 `http://127.0.0.1:${SERVER_PORT}/actuator/health`를 30초 간격·5초 제한·60초 시작 유예·3회 실패 기준으로 확인한다. health는 공개되지만 세부 정보는 숨기며 DB 상태도 반영한다. 웹은 standalone 서버의 응답을 승인 후 별도 모니터링한다. 실제 외부 도메인 점검·라우팅 변경은 승인 후 진행한다.

롤백은 승인 후 진행한다. 직전 검증된 불변 이미지 식별자와 해당 웹 빌드 인자/비밀 버전을 보관하고, 장애 시 이전 이미지로 되돌릴 수 있는지 먼저 DB 스키마의 하위 호환성을 검토한다. Flyway 적용 내역은 이미지 롤백으로 취소되지 않는다. 비호환 DB 변경이면 승인된 백업 복원 또는 추가 보정 마이그레이션을 별도로 결정하며, 복원 시점 이후 쓰기 손실·R2 객체와 DB 참조 불일치·HMAC 키 불일치를 함께 검토한다. 정상 health와 핵심 로그인/읽기/쓰기 검증을 마친 후에만 복구 완료로 판단한다.

## Q2 검증 결과

로컬 linux/arm64에서 백엔드 전체 76개 테스트(실패·건너뜀 0), 웹 lint/build, 두 Docker build가 통과했다. `docker image inspect .Size` 기준 backend `168564761`바이트(약 168.6 MB), web `74957706`바이트(약 75.0 MB)다. 아키텍처·베이스 이미지 갱신에 따라 크기는 달라질 수 있다. 두 이미지의 사용자 설정은 `10001:10001`이다. 서버 접속·배포·실제 자격 증명·실제 도메인 요청은 수행하지 않았다.
