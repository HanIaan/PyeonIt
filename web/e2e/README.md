# Q1 / Q1b 통합 E2E

Chromium만 사용하며 목/네트워크 가로채기는 사용하지 않는다. 공유 통합 환경(8081/3000)은 변경하거나 종료하지 않는다.

## Q1b 전용 환경

먼저 `localhost:5434`의 기존 Postgres에 새 전용 DB `pyeonit_q1b`를 `createdb`로 생성한다(기본 개발 계정 사용). 다른 DB는 변경하지 않는다. 새 RANGE 시드가 적용된 이 워크트리의 백엔드가 필요하다.

각 터미널에서 다음을 실행한다. 종료 시 자신이 실행한 두 서버만 종료한다.

```sh
cd backend
SERVER_PORT=8085 SPRING_PROFILES_ACTIVE=dev \
  DB_URL=jdbc:postgresql://localhost:5434/pyeonit_q1b \
  PYEONIT_CORS_ALLOWED_ORIGINS=http://localhost:3005 \
  PYEONIT_WEB_BASE_URL=http://localhost:3005 ./gradlew bootRun
```

```sh
cd web
npm ci
npx playwright install chromium
NEXT_PUBLIC_API_BASE_URL=http://localhost:8085 NEXT_PUBLIC_API_MOCK=false \
  NEXT_PUBLIC_ENABLE_DEV_LOGIN=true npm run dev -- -p 3005
```

```sh
cd web
E2E_WEB_URL=http://localhost:3005 E2E_API_URL=http://localhost:8085 npx playwright test
npm run lint && npm run build
```

`npm run e2e`도 동일하다. 기본 주소는 웹 `http://localhost:3000`, API `http://localhost:8081`이며 `E2E_WEB_URL`, `E2E_API_URL`로 덮어쓴다. 실행 중인 웹의 API 설정/CORS도 해당 주소에 맞아야 한다. 웹 dev 로그인 활성화와 백엔드 dev 프로필이 필요하다.

계정 닉네임·글·댓글에 실행별 접미사를 붙인다. 생성한 계정/글만 변경하며 기존 데이터 개수/고정 ID를 단정하지 않는다. 학교·공고·일정은 실행 시 API에서 탐색한다. RANGE가 한 건도 없으면 실패한다. 읽기 검증용 글과 dev 계정 등 실행 데이터는 전용 DB에 남는다. 작성자는 정지 상태에서 탈퇴한다.

단계 실패를 모아 마지막에 실패 처리한다. 글쓰기 UI 자체가 실패하면 후속 검증용 글만 API로 생성하고 원래 실패 및 fallback 주석을 유지한다. 제품 버그를 통과 처리하거나 예상 실패로 숨기지 않는다.

결과 JSON은 `test-results/results.json`, 실패 스크린샷/trace는 `test-results/` 아래에 저장한다. trace에는 세션 정보가 포함될 수 있으므로 커밋하지 않는다. `npx playwright show-trace <trace.zip 경로>`로 확인한다. 기본 리포터는 list+JSON이다.

## Q1b 검증 결과

- 전용 환경 8085/3005, DB `pyeonit_q1b`: **5 통과 / 0 실패 / 0 건너뜀**.
- Q1-001 해결: `resolveApiUrl()` 하나로 편집 미리보기 `img src`, 상세 `img src`와 원본 `a href`를 API 주소로 해석한다. 이미지 URL 사용처 검색 결과 추가 썸네일 등은 없었다. 기존 `api()`는 변경하지 않았다. 미리보기와 상세 모두 `naturalWidth > 0`을 검증했다.
- Q1-2에서 서강대학교 컴퓨터공학과의 개발 전용 RANGE 사례(`1명 이내`)가 학교 상세 TO 표에 원문으로 표시되고 `count=null`인지 검증했다. 기존 행은 유지하며 멱등 INSERT 1건만 추가했다.
- 기존 5개 시나리오의 닉네임·공고·TO·일정·관심학교·커뮤니티·신고·제재·탈퇴·접근 제한도 모두 통과했다.
- `cd backend && ./gradlew test`: **77 통과 / 0 실패 / 0 오류 / 0 건너뜀**(Testcontainers).
- `cd web && npm run lint && npm run build`: 통과.

## 검증 공백 3건 확인 근거

모두 Testcontainers 통합 테스트로 검증한다. 기존 테스트는 수정하지 않았다.

- **최초 가입 pending 흐름: 기존 커버.** `backend/src/test/java/com/pyeonit/auth/AuthFlowTests.java`의 `firstLoginCreatesPendingSession`, `pendingSessionGetsSignupRequired`, `signupReplacesPrincipalWithUserId`: OAuth 성공 핸들러 → `pending:<provider>:<id>`·권한 없음 → GET `/api/me`의 403 `SIGNUP_REQUIRED` → signup 201 → 숫자 principal·회원 세션 교체. 추가 테스트 없음.
- **RANGE 응답: 기존 부분 커버, Q1b 보완.** `backend/src/test/java/com/pyeonit/notice/NoticeApiTests.java`의 `approvalMaterializesHierarchyTracksQuotasSchedulesAndReviewMetadata`는 승인 후 `kind=RANGE`만 단정했다. 새 `RangeQuotaApiTests.universityDetailReturnsRangeWithNullCountAndOriginalText`가 실제 GET 응답의 `kind=RANGE`, `count=null`, `rawText=1명 이내`를 모두 단정한다. E2E에서도 실제 TO 원문 표시를 검증했다.
- **탈퇴 후 동일 소셜 재가입 차단: 기존 커버.** `backend/src/test/java/com/pyeonit/admin/community/AdminCommunityTests.java`의 `suspendWithdrawalStoresOnlyHmacAndBlocksBothOldAndNewPendingSessions`는 정지 중 탈퇴, 원문 식별자 제거·HMAC 저장, 신규/기존 pending signup 403 `ACCOUNT_BLOCKED`를 검증한다. `warningAndExpiredSuspensionAllowSignupAfterWithdrawal`는 경고만/만료 정지의 탈퇴 후 signup 201을 검증한다. 추가 테스트 없음.

## 남은 브라우저 검증 공백

- 최초 가입 화면: dev 로그인은 이미 가입한 회원 세션만 생성하므로 pending 세션에 도달할 수 없다. 위 백엔드 통합 테스트로 세션/API 계약을 검증한다. 실제 외부 OAuth 인가·토큰 교환은 수행하지 않는다.
- 동일 소셜 계정 재가입 차단 UI: dev 계정에는 소셜 식별자가 없고 dev 로그인은 닉네임 조회/생성 및 역할·정지 상태 덮어쓰기 방식이다. 위 백엔드 통합 테스트로 HMAC 차단과 예외 조건을 검증한다.
