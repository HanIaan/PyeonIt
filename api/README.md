# PyeonIt API 계약

`api/openapi.yaml`(OpenAPI 3.1)이 기준이다. 웹 타입은 여기서 생성한다: `cd web && npm run gen:api` → `web/lib/api-types.ts`(커밋함).

검증: `npx -y @redocly/cli@1.34.5 lint api/openapi.yaml`
의도된 경고 4개: servers 가 localhost(로컬 개발 전용), `startOAuth2Login` 은 302만 있음(2xx 없음), `getCsrfToken`·`listBoards` 는 실패 응답이 없음(4xx 없음).

## (a) 인증·CSRF·오리진

| 항목 | 결정 | 이유 |
|---|---|---|
| 세션 | 서버 세션 쿠키 `JSESSIONID` (HttpOnly, SameSite=Lax, 운영은 `SERVER_SERVLET_SESSION_COOKIE_SECURE=true`) | 토큰 저장·갱신 로직 없이 Spring Security 기본 기능만으로 끝난다 |
| CSRF | `CookieCsrfTokenRepository`: `XSRF-TOKEN` 쿠키(JS가 읽을 수 있음) 값을 GET/HEAD/OPTIONS 외 모든 요청에 `X-XSRF-TOKEN` 헤더로 보낸다. 쿠키를 읽을 수 없으면 `GET /api/csrf` 본문의 `token` 을 쓴다 | 쿠키 세션에는 CSRF 방어가 필요하고, 이 방식은 Spring 표준이라 서버 코드가 거의 없다. 헤더로만 받으므로 토큰 마스킹(BREACH 대비)은 끈다 |
| 오리진 | 개발: 웹 `http://localhost:3000` 이 백엔드 `http://localhost:8081` 을 CORS(`credentials: 'include'`)로 직접 호출. 프록시 없음 | 웹 기본 주소가 이미 `API_BASE_URL`(8081)이고, localhost 끼리는 같은 사이트라 Lax 쿠키가 전달된다 |

- 로컬 포트: 백엔드 `8081`(`SERVER_PORT` 로 변경, 이 맥은 8080을 다른 앱이 쓴다), 웹 `3000`.
- 웹 → API 주소: `NEXT_PUBLIC_API_BASE_URL`(기본 `http://localhost:8081`, `web/.env.example`).
- 허용 오리진: `PYEONIT_CORS_ALLOWED_ORIGINS`(쉼표 구분, 로컬 기본 `http://localhost:3000`). 허용 헤더는 `Content-Type`, `X-XSRF-TOKEN`.
- OAuth2 완료 후 웹으로 돌아갈 주소: `PYEONIT_WEB_BASE_URL`(기본 `http://localhost:3000`).
- 운영도 웹과 API는 같은 사이트(예: `pyeonit.com` 과 `api.pyeonit.com`)여야 한다. 다른 사이트면 SameSite=Lax 쿠키가 안 간다.
- 로그인: 브라우저를 `GET /oauth2/authorization/{kakao|google}` 로 이동. 콜백 `/login/oauth2/code/{provider}`.
  성공 → 회원 `{WEB}/`, 가입 대기 `{WEB}/signup`. 실패 → `{WEB}/login?error={code}`.
- 가입 대기: OAuth2 는 됐지만 닉네임 미설정. `users.nickname` 이 NOT NULL 이라 users 행은 `POST /api/auth/signup` 에서 만든다. 이 세션은 `GET /api/me` 와 쓰기에서 403 `SIGNUP_REQUIRED`.
- 탈퇴(`DELETE /api/me`)는 `LAST_SOCIAL_ACCOUNT` 규칙과 무관하게 항상 가능: users.nickname=NULL, status=WITHDRAWN, withdrawn_at 기록, social_accounts 전부 삭제, 글·댓글은 유지(작성자 닉네임 null).
- 권한 계약(B1 구현): 가입 회원 세션의 권한은 `ROLE_USER` 또는 `ROLE_ADMIN`(ADMIN 은 USER 를 포함, RoleHierarchy). 가입 대기 세션은 둘 다 없다.
- 경로 규칙(`SecurityConfig`): `/api/admin/**` ADMIN, `/api/me/**` 회원, `/api/me`·`/api/auth/signup` 인증, 그 외 `GET /api/**` 공개, 나머지는 회원. `POST /api/dev/login` 은 dev 프로필에서만 공개.
- 공개 API는 APPROVED 요강만 노출한다. 학교·학년도별 가장 최근 승인 요강이 게시본이고 그 외 상태와 추출 결과는 관리자 API에서만 보인다.

## 닉네임 규칙 (B1 구현, F1 UI)

가입(`POST /api/auth/signup`)과 변경(`PATCH /api/me/nickname`) 모두 적용.

| 규칙 | 위반 시 |
|---|---|
| 2~12자, 한글·영문·숫자만 `^[가-힣a-zA-Z0-9]{2,12}$` | 400 `VALIDATION_FAILED` (fieldErrors[nickname]) |
| 중복 불가, 대소문자 무시 | 409 `NICKNAME_TAKEN` |
| 금지어 포함 검사(대소문자 무시). 예: 운영자, 관리자, 편잇, pyeonit, admin. 전체 목록은 백엔드 설정 | 422 `NICKNAME_FORBIDDEN` |
| 변경은 30일에 1회. 가입 직후 최초 설정은 제한 대상이 아니고 시계도 시작하지 않는다 | 429 `NICKNAME_CHANGE_TOO_SOON` + `Retry-After` + 본문 `nicknameChangeableAt` |

- `Me.nicknameChangeableAt` = nickname_changed_at + 30일, 변경 이력이 없으면 null(지금 변경 가능).
- 정지(SUSPENDED) 회원도 변경 가능.
- B1 DB 계약(V4): `users.nickname_changed_at TIMESTAMPTZ`(NULL=변경 이력 없음) 추가, `CREATE UNIQUE INDEX ON users (lower(nickname))`(탈퇴 회원 NULL은 유니크 대상 아님). 기존 `nickname` UNIQUE 는 그대로 둬도 된다.
- 429 본문은 ErrorResponse + `nicknameChangeableAt` 이라 `ApiException` 으로는 못 만든다. B1 컨트롤러 안의 `@ExceptionHandler` 로 직접 응답한다(공통 핸들러보다 우선).

## (b) 페이지네이션

page/size 하나로 통일: `page`(0부터, 기본 0), `size`(기본 20, 최대 100, 초과 400).
응답: `{ content: [...], page, size, totalElements, totalPages }` (Spring Data `Page` 를 그대로 옮긴 모양).
이유: 1인 개발이라 Spring Data `Pageable` 을 그대로 쓰고, 게시판의 번호형 페이지 UI에 바로 맞는다.
학교·게시판·관심학교·캘린더(최대 100일)처럼 크기가 작게 정해진 목록은 배열로 전부 준다.

## 공통 형식

- 오류 본문: `{ code, message, fieldErrors: [{ field, message }] }`. `message` 는 사용자에게 보여줄 한국어. 백엔드는 `com.pyeonit.config.ApiException(status, code, message)` 을 던진다.
- 날짜·시각: ISO-8601 UTC 오프셋 포함(`2026-11-08T09:00:00+09:00`). ID: 정수(int64, boards·tracks 는 int32).
- 이름: DB 컬럼 snake_case ↔ JSON 필드 camelCase. enum 은 DB 값 그대로 대문자.
- 탈퇴 회원은 `UserSummary.nickname = null`.

## (c) 상태 코드 규칙과 오류 코드

| 상태 | 언제 | code |
|---|---|---|
| 400 | 요청만 보고 알 수 있는 오류 (형식, 범위, 필수값) | `INVALID_REQUEST`(파싱·타입 오류), `VALIDATION_FAILED`(값 검증, fieldErrors 포함) |
| 401 | 로그인 필요 | `UNAUTHORIZED` |
| 403 | 로그인했지만 할 수 없음 | `FORBIDDEN`(권한·작성자 아님), `CSRF_INVALID`, `SIGNUP_REQUIRED`(닉네임 미설정), `ACCOUNT_SUSPENDED`(정지 회원 쓰기) |
| 404 | 없음, 또는 보이면 안 됨(HIDDEN·DELETED 글, 미승인 요강) | `NOT_FOUND` |
| 409 | 현재 상태와 충돌 (중복, 이미 처리됨) | `NICKNAME_TAKEN`, `ALREADY_REGISTERED`, `PROVIDER_ALREADY_LINKED`, `ALREADY_REPORTED`, `DOCUMENT_ALREADY_REVIEWED`, `REPORT_ALREADY_HANDLED`, `SANCTION_NOT_ACTIVE` |
| 413 | 업로드 크기 초과 | `PAYLOAD_TOO_LARGE` |
| 422 | 형식은 맞지만 다른 데이터 때문에 처리 불가 | `NICKNAME_FORBIDDEN`, `REPLY_DEPTH_EXCEEDED`, `INVALID_REFERENCE`(없는 게시판·학교, 남의/이미 연결된 이미지), `LAST_SOCIAL_ACCOUNT`, `EXTRACTION_UNUSABLE` |
| 429 | 요청이 너무 잦음 (`Retry-After` 헤더) | `RATE_LIMITED`, `NICKNAME_CHANGE_TOO_SOON`(본문에 `nicknameChangeableAt`) |
| 500 | 서버 오류 | `INTERNAL_ERROR` |

OAuth2 리다이렉트 쿼리 code: `OAUTH2_FAILED`, 연결 실패 `SOCIAL_ACCOUNT_IN_USE`·`PROVIDER_ALREADY_LINKED`.
정지(SUSPENDED) 회원은 `x-suspended-blocked: true` 인 operation(글 작성·수정, 댓글 작성·수정, 추천·취소, 신고, 이미지 업로드)에서 403 `ACCOUNT_SUSPENDED`. 읽기·삭제·관심학교·로그아웃·탈퇴는 허용.

## (d) 담당별 operation (총 39: B1 10 / B2 10 / B3 14 / B4 5)

| 담당 | 영역 | 패키지 | 마이그레이션 | 개수 |
|---|---|---|---|---|
| B1 | 인증·회원 | `com.pyeonit.auth` | V4 | 10 |
| B2 | 학교·공고·관리자 요강 | `com.pyeonit.university`, `com.pyeonit.notice`, `com.pyeonit.admin.notice` | V5 | 10 |
| B3 | 커뮤니티 | `com.pyeonit.community` | V6 | 14 |
| B4 | 관리자 신고·제재 | `com.pyeonit.admin.community` | V7(예약, 필요할 때만. reports·sanctions 는 V1에 이미 있음) | 5 |

- B4 는 users.status 변경(정지·해제)이 필요하면 auth 패키지를 수정하지 말고 `SUPERSET_WORKER_BLOCKED` 로 보고한다.

C0 가 이미 구현한 것: `getCsrfToken`(`config.CsrfController`), `logout`(SecurityConfig logout 필터), 공통 오류 처리(`config.GlobalExceptionHandler`).

**B1** — `startOAuth2Login` GET /oauth2/authorization/{provider}, `getCsrfToken` GET /api/csrf, `logout` POST /api/auth/logout, `completeSignup` POST /api/auth/signup, `getMe` GET /api/me, `changeNickname` PATCH /api/me/nickname, `withdraw` DELETE /api/me, `startSocialLink` POST /api/me/social-accounts/{provider}, `unlinkSocialAccount` DELETE /api/me/social-accounts/{provider}, `devLogin` POST /api/dev/login (dev 전용)

**B2** — `listUniversities` GET /api/universities, `getUniversity` GET /api/universities/{universityId}, `listNotices` GET /api/notices, `listCalendarEvents` GET /api/calendar, `listFavoriteUniversities` GET /api/me/favorite-universities, `addFavoriteUniversity` PUT ·`removeFavoriteUniversity` DELETE /api/me/favorite-universities/{universityId}, `listAdminAdmissionDocuments` GET /api/admin/admission-documents, `approveAdmissionDocument` POST …/{documentId}/approve, `rejectAdmissionDocument` POST …/{documentId}/reject

**B3** — `listBoards` GET /api/boards, `listPosts` GET·`createPost` POST /api/posts, `getPost` GET·`updatePost` PUT·`deletePost` DELETE /api/posts/{postId}, `listComments` GET·`createComment` POST /api/posts/{postId}/comments, `updateComment` PATCH·`deleteComment` DELETE /api/comments/{commentId}, `likePost` PUT·`unlikePost` DELETE /api/posts/{postId}/like, `createReport` POST /api/reports, `uploadPostImage` POST /api/post-images

**B4** — `listAdminReports` GET /api/admin/reports, `handleReport` POST /api/admin/reports/{reportId}/handle, `listSanctions` GET·`createSanction` POST /api/admin/sanctions, `releaseSanction` POST /api/admin/sanctions/{sanctionId}/release

## (e) 명세 변경 절차

1. 작업자(B·F)는 openapi.yaml 을 직접 고치지 않는다. 필요하면 `SUPERSET_WORKER_BLOCKED` 로 보고한다(무엇을, 왜, 영향받는 operation).
2. 조율자가 C0 에 변경을 맡기고, C0 가 openapi.yaml·README 를 고친 뒤 `npm run gen:api` 로 `web/lib/api-types.ts` 를 다시 만들고 lint 를 통과시킨다.
3. 머지 후 작업자는 main 을 받아 이어서 한다.
