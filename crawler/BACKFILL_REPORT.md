# T6 수집 범위 확정 및 추출 품질 복구

실행일: 2026-11-08 (KST). 확정 범위 **44개교: 서울 29·경기 13·인천 2**. 한국외대는 하나의 통합 요강으로 수집한다.

## T8 일일 분할 실행 (무료 한도 20요청/일)

실제 backfill은 실행하지 않았다(요청 예산 보존). 아래 결과는 모두 `--dry-run`(Gemini 호출 0)으로 만들었다. 무료 키만 사용하고 유료 키는 쓰지 않는다.

```sh
cd crawler
.venv/bin/pyeonit-crawler backfill --output artifacts/t6-chain --dry-run            # 분류·오늘 계획
.venv/bin/pyeonit-crawler backfill --output artifacts/t6-chain                      # 실제 실행(하루 20요청까지)
.venv/bin/pyeonit-crawler backfill --output artifacts/t6-chain --model-chain gemini-3.8-flash   # 3.8 단일
.venv/bin/pyeonit-crawler backfill --next-run                                       # 다음 실행 권장 시각
.venv/bin/pyeonit-crawler backfill --output artifacts/t6-chain --quota-report       # 한도 독립/공유 근거
```

### 우선순위 분류 (기본 체인, 40곳)

건너뜀 4곳: 가톨릭대학교, 강남대학교, 서강대학교, 성균관대학교. 같은 PDF SHA-256에 체인 모델 통과 결과가 있는 학교다. 나머지 40곳은 단계 순서대로, 단계 안에서는 이름순으로 처리한다. 실행할 때마다 처음에 같은 표를 출력한다.

| 순서 | 학교 | 단계 | 분류 근거 |
| ---: | --- | --- | --- |
| 1 | 가천대학교 | ① 통과·추출 데이터 없음 | 출처 교체: PDF 재다운로드 후 추출 |
| 2 | 경기대학교 | ① 통과·추출 데이터 없음 | QUOTA_WAIT, 추출 데이터 없음 |
| 3 | 고려대학교 (서울) | ① 통과·추출 데이터 없음 | QUOTA_WAIT, 추출 데이터 없음 |
| 4 | 광운대학교 | ① 통과·추출 데이터 없음 | QUOTA_WAIT, 추출 데이터 없음 |
| 5 | 국민대학교 | ① 통과·추출 데이터 없음 | QUOTA_WAIT, 추출 데이터 없음 |
| 6 | 단국대학교 (죽전) | ① 통과·추출 데이터 없음 | QUOTA_WAIT, 추출 데이터 없음 |
| 7 | 동국대학교 | ① 통과·추출 데이터 없음 | QUOTA_WAIT, 추출 데이터 없음 |
| 8 | 동덕여자대학교 | ① 통과·추출 데이터 없음 | QUOTA_WAIT, 추출 데이터 없음 |
| 9 | 명지대학교 | ① 통과·추출 데이터 없음 | QUOTA_WAIT, 추출 데이터 없음 |
| 10 | 상명대학교 (서울) | ① 통과·추출 데이터 없음 | QUOTA_WAIT, 추출 데이터 없음 |
| 11 | 서경대학교 | ① 통과·추출 데이터 없음 | QUOTA_WAIT, 추출 데이터 없음 |
| 12 | 서울과학기술대학교 | ① 통과·추출 데이터 없음 | QUOTA_WAIT, 추출 데이터 없음 |
| 13 | 서울대학교 | ① 통과·추출 데이터 없음 | QUOTA_WAIT, 추출 데이터 없음 |
| 14 | 성신여자대학교 | ① 통과·추출 데이터 없음 | QUOTA_WAIT, 추출 데이터 없음 |
| 15 | 세종대학교 | ① 통과·추출 데이터 없음 | QUOTA_WAIT, 추출 데이터 없음 |
| 16 | 수원대학교 | ① 통과·추출 데이터 없음 | QUOTA_WAIT, 추출 데이터 없음 |
| 17 | 숭실대학교 | ① 통과·추출 데이터 없음 | QUOTA_WAIT, 추출 데이터 없음 |
| 18 | 아주대학교 | ① 통과·추출 데이터 없음 | QUOTA_WAIT, 추출 데이터 없음 |
| 19 | 용인대학교 | ① 통과·추출 데이터 없음 | QUOTA_WAIT, 추출 데이터 없음 |
| 20 | 이화여자대학교 | ① 통과·추출 데이터 없음 | QUOTA_WAIT, 추출 데이터 없음 |
| 21 | 중앙대학교 | ① 통과·추출 데이터 없음 | QUOTA_WAIT, 추출 데이터 없음 |
| 22 | 한국공학대학교 | ① 통과·추출 데이터 없음 | 출처 교체: PDF 재다운로드 후 추출 |
| 23 | 한국외국어대학교 | ① 통과·추출 데이터 없음 | QUOTA_WAIT, 추출 데이터 없음 |
| 24 | 한국항공대학교 | ① 통과·추출 데이터 없음 | QUOTA_WAIT, 추출 데이터 없음 |
| 25 | 한성대학교 | ① 통과·추출 데이터 없음 | QUOTA_WAIT, 추출 데이터 없음 |
| 26 | 한신대학교 | ① 통과·추출 데이터 없음 | QUOTA_WAIT, 추출 데이터 없음 |
| 27 | 한양대학교 (ERICA) | ① 통과·추출 데이터 없음 | QUOTA_WAIT, 추출 데이터 없음 |
| 28 | 한양대학교 (서울) | ① 통과·추출 데이터 없음 | QUOTA_WAIT, 추출 데이터 없음 |
| 29 | 홍익대학교 (서울) | ① 통과·추출 데이터 없음 | QUOTA_WAIT, 추출 데이터 없음 |
| 30 | 건국대학교 (서울) | ② 추출했으나 미통과 | 불일치 (gemini-3.6-flash) |
| 31 | 연세대학교 (서울) | ② 추출했으나 미통과 | 검증불가 (gemini-3.6-flash) |
| 32 | 인천대학교 | ② 추출했으나 미통과 | 불일치 (gemini-3.6-flash) |
| 33 | 경희대학교 | ③ 1차 lite로만 통과 | 1차 lite만 통과(같은 PDF), 체인 모델 통과 없음 |
| 34 | 덕성여자대학교 | ③ 1차 lite로만 통과 | 1차 lite만 통과(같은 PDF), 체인 모델 통과 없음 |
| 35 | 삼육대학교 | ③ 1차 lite로만 통과 | 1차 lite만 통과(같은 PDF), 체인 모델 통과 없음 |
| 36 | 서울시립대학교 | ③ 1차 lite로만 통과 | 1차 lite만 통과(같은 PDF), 체인 모델 통과 없음 |
| 37 | 서울여자대학교 | ③ 1차 lite로만 통과 | 1차 lite만 통과(같은 PDF), 체인 모델 통과 없음 |
| 38 | 숙명여자대학교 | ③ 1차 lite로만 통과 | 1차 lite만 통과(같은 PDF), 체인 모델 통과 없음 |
| 39 | 인하대학교 | ③ 1차 lite로만 통과 | 1차 lite만 통과(같은 PDF), 체인 모델 통과 없음 |
| 40 | 한세대학교 | ③ 1차 lite로만 통과 | 1차 lite만 통과(같은 PDF), 체인 모델 통과 없음 |

분류 규칙과 근거가 불분명한 경우(BLOCKED 없이 보수적으로 처리):

- **건너뜀 기준**: 지시문은 "3.8로 통과"이고 보고 기준 수치는 "통과 4·나머지 40"이다. 그래서 **현재 `--model-chain`에 든 모델의 통과**를 기준으로 삼았다. 기본 체인에서는 3.6 통과인 가톨릭·강남도 건너뛰어 40곳이 된다. `--model-chain gemini-3.8-flash`로 실행하면 두 학교가 ②로 들어가 3.8로 다시 추출된다(42곳).
- **출처 교체**: 가천·한국공학은 이전 JSON의 출처(`page_url`)가 YAML의 첫 출처와 다르다. 기존 추출이 잘못된 PDF에서 나왔으므로 데이터가 없는 것으로 보고 ①로 분류했다. 같은 규칙으로 PDF를 재사용하지 않고 새로 받는다.
- **②**: 지금 결과 JSON에 모집인원 데이터가 있고 검증 상태가 불일치·검증불가(또는 체인 밖 모델 통과)인 경우다. 인천은 최종 상태가 `QUOTA_WAIT`지만 3.6 불일치 데이터가 있어 ②다. 1차 lite 통과와 체인 미통과 데이터가 함께 있으면 데이터가 있는 ②를 우선한다(3.8 단일 체인일 때 강남).
- **③**: 지금 데이터가 없고 `artifacts/artifacts/`(1차)의 lite 통과 결과가 **같은 PDF SHA-256**인 경우다. 해시가 다르면 ③으로 보지 않는다. 결과 파일이 없거나 읽을 수 없으면 ①로 분류한다.

### 오늘 예산으로 처리될 학교 (dry-run)

dry-run 시점은 2026-11-08 02시대 KST(태평양 날짜 2026-11-07)였다. 남은 예산 20 기준 계획(학교당 첫 추출 1요청, 429·재추출이 없다는 가정):

- 처리 예정 20곳: 가천대학교, 경기대학교, 고려대학교 (서울), 광운대학교, 국민대학교, 단국대학교 (죽전), 동국대학교, 동덕여자대학교, 명지대학교, 상명대학교 (서울), 서경대학교, 서울과학기술대학교, 서울대학교, 성신여자대학교, 세종대학교, 수원대학교, 숭실대학교, 아주대학교, 용인대학교, 이화여자대학교
- QUOTA_WAIT 예정 20곳: 중앙대학교, 한국공학대학교, 한국외국어대학교, 한국항공대학교, 한성대학교, 한신대학교, 한양대학교 (ERICA), 한양대학교 (서울), 홍익대학교 (서울), 건국대학교 (서울), 연세대학교 (서울), 인천대학교, 경희대학교, 덕성여자대학교, 삼육대학교, 서울시립대학교, 서울여자대학교, 숙명여자대학교, 인하대학교, 한세대학교

③은 ①②의 첫 추출과 재추출이 모두 끝난 뒤 예산이 남을 때만 처리한다. 이 계획대로면 ①의 중앙대부터 다음 날로 넘어간다. 주의: `quota-state.json`이 아직 없어서 T6가 이미 소진한 태평양 날짜 11-07의 한도를 모른다. **반드시 리셋(2026-11-08 17:00 KST) 뒤에 첫 실행**해야 한다. 그 전에 실행하면 429만 받으며, 모델마다 1요청씩 예산이 줄어든다.

### 일일 요청 예산

- `--daily-request-limit`(기본 20)은 실제 generateContent 전송 수다. `GeminiExtractor._request`가 전송 직전에 세므로 503·타임아웃 재시도, MAX_TOKENS 확장, 429, 체인 이동이 모두 포함된다.
- 누적값은 `crawler/artifacts/quota-state.json`(gitignore)에 `{day, used, reset_at, last_429}` 형태로 태평양시 날짜별로 저장한다. 같은 날 여러 번 실행하면 합산되고, 태평양 자정이 지나면 0으로 돌아간다. 파일이 손상되면 0으로 초기화하지 않고 실패한다(한도 초과 방지).
- **예산 절약 방침**: 한 번의 실행에서 먼저 모든 대상 학교를 1회씩(1요청) 추출한다. 합계 불일치 학교의 같은 PDF 재추출(최대 2회)은 그 뒤 남은 예산으로 한다. `cli.py`에도 주석 한 줄로 적었다.
- 이 모드에서는 체인 이동이 **429일 때만** 일어난다. 이전처럼 불일치·검증불가일 때 다음 모델로 넘어가지 않는다. 남은 예산을 다른 학교에 먼저 쓰기 위해서다.
- 예산이 바닥나면 남은 학교는 `summary.json`에 `QUOTA_WAIT`/`reason=DAILY_BUDGET`으로 남는다. **기존 학교 JSON(진단 데이터 포함)은 덮어쓰지 않으며** 종료 코드는 0이다.

### 리셋 시각 검증과 다음 실행

T6의 429 `retryDelay`를 결과 기록 시각에 더하면 모두 2026-11-08 07:59:59~08:00:05 UTC였다. 즉 태평양 자정(PST 00:00 = 17:00 KST)이고, 가정과 같다. 연세·인천은 08:03~08:08로 나오는데, 이는 429 뒤 다른 모델 추출을 마친 시각으로 기록됐기 때문이다(상한값). 이제 429를 받을 때마다 시각, `retryDelay`, 태평양 자정과의 차이(`reset_gap_s`)를 quota-state에 저장한다. 일일 한도에서 차이가 15분을 넘으면 경고를 출력한다. `--next-run`은 마지막 429 시각 + retryDelay + 여유 5분을 KST/UTC로 출력한다(retryDelay가 없으면 저장된 리셋 시각을 쓴다). 429 기록이 없으면 `기록 없음`을 출력한다. 지금은 quota-state가 없으므로 `기록 없음`이다. 자동 실행 제안은 `crawler/SCHEDULE_PROPOSAL.md`(등록 안 함)에 있다.

### 체인 한도 공유 확인 근거 (`--quota-report`, T6 결과)

model_attempts에는 모든 429의 quotaId·quotaValue·quotaDimensions(model)·retryDelay와, 체인 이동 뒤 다음 모델의 응답(`http_status` 200/429)이 남는다. 재시도로 회복된 단기 429도 `retried_429`에 남는다. 판정은 조율자가 한다.

| 요청 모델 | 200 | 429 | 기타 | 429 quotaDimensions.model | quotaId=quotaValue | retryDelay | 직전 모델 429 후 이 모델 응답 |
| --- | ---: | ---: | ---: | --- | --- | --- | --- |
| gemini-3.8-flash | 2 | 3 | 0 | gemini-3.8-flash | GenerateRequestsPerDayPerProjectPerModel-FreeTier=20 | 54457s, 54792s | — |
| gemini-3.7-flash | 0 | 3 | 0 | gemini-3.8-flash | GenerateRequestsPerDayPerProjectPerModel-FreeTier=20 | 54457s, 54791s | gemini-3.8-flash 429 후 429×3 |
| gemini-3.6-flash | 5 | 3 | 0 | gemini-3.6-flash | GenerateRequestsPerDayPerProjectPerModel-FreeTier=20 | 54283s, 54357s, 54456s | gemini-3.7-flash 429 후 200×5, gemini-3.7-flash 429 후 429×3 |
| gemini-3.5-flash | 3 | 1 | 0 | gemini-3.6-flash | GenerateRequestsPerDayPerProjectPerModel-FreeTier=20 | 54358s | gemini-3.6-flash 429 후 200×1 |

근거 읽기: 앞 모델 429 직후 이 모델이 200이면 독립 한도 근거, 이 모델의 429 quotaDimensions.model이 다른 모델이면 공유 가능성. 판정은 조율자.

### 출처 수정 2곳 (Gemini 호출 0, Playwright로 확인)

| 학교 | 문제 | 조치 | 확인한 PDF |
| --- | --- | --- | --- |
| 가천대학교 | 고정 페이지 `guide1.asp`가 2027학년도 예정 자료 | 공지 글 `noticeView.asp?BOARD_IDX=27525`("2026학년도 편입학 모집요강")를 첫 GUIDE_PAGE로 추가했다. 기존 출처 2개는 감시용으로 유지한다. | 첨부 `2026학년도 가천대학교 편입학 모집요강.pdf`(35쪽, 표지 "2026 가천대학교 편입학 모집요강", 3쪽 전형별 모집인원), SHA-256 `6ab6c93deeff21181b138cdab36ce13d0cb365fb8c4dc617b66bdc02b3abc364` |
| 한국공학대학교 | 게시판 첫 PDF가 면접 유의사항(2쪽) | 모집요강 메뉴의 "2026학년도 편입생 모집요강" 상세 글(`guide.htm?...mode=view&bltn_seq=13025`)을 첫 GUIDE_PAGE로 추가했다. | `guide_20251202150653_5152.pdf`(44쪽, "2026학년도 한국공학대학교 편입학 모집요강", 목차에 전형별 모집인원), SHA-256 `f370126d9335dc860195e9f25c2ef2e7b9d64d964f3ef80fd30d3398b1a27359` |

두 PDF 모두 기존 `download_pdf`가 새 URL에서 받아 낸다는 것을 확인했다(내려받은 파일은 작업 임시 폴더에만 있고 artifacts에는 넣지 않았다). 다음 실행에서 새로 받는다. **위험**: 한국공학대 PDF는 18.8MB이고 base64로 바꾸면 약 25MB다. Gemini 인라인 요청 한도를 넘으면 HTTP 4xx `error`로 1요청을 쓰고 끝날 수 있다. 그 경우 File API 업로드 또는 페이지 분할이 필요하며 별도 작업으로 넘긴다.

## 실행 결과

전체 처리가 완료됐다. 2차 상태 집계: **통과 4·불일치 1·검증불가 1·요강없음 0·실패 1·QUOTA_WAIT 37**.

1차 전체 결과는 **통과 11·불일치 25·검증불가 17·요강없음 4·실패 3**(60개 항목)이었고 홍익대는 별도 출처 미확인이었다. 이번 확정 44개교의 1차 상태만 집계하면 검증불가 13, 불일치 17, 실패 1, 실패 (출처 미확인) 1, 요강없음 2, 통과 10이다.

조율자의 최신 지시에 따라 `gemini-3.8-flash → gemini-3.7-flash → gemini-3.6-flash → gemini-3.5-flash` 체인을 사용했다. lite 결과는 채택하지 않았다. 일일 한도 429는 실패가 아닌 `QUOTA_WAIT`이며 다음 모델로 넘어간다. 모델 체인의 한도가 모두 소진되면 나머지도 `QUOTA_WAIT`로 보존하고 종료 코드 0으로 끝난다. **통과한 결과만 `adopted=true`**로 채택한다. 불일치·검증불가의 JSON 데이터는 진단 자료이며 채택 데이터가 아니다.

전체 대상·설정이 같은 실행 명령이다. 실제 실행에서는 `--schools`로 성균관·서강·인천을 우선 지정하고 이어서 나머지 41곳을 처리했다:

```sh
cd crawler
.venv/bin/pyeonit-crawler backfill --year 2026 --model gemini-3.8-flash --output artifacts/t6-chain --concurrency 3
```

최초 실행 전 기존 PDF·출처 메타데이터를 `artifacts/t6-chain/`에 복사했고 성균관·서강의 이번 3.8 통과 JSON을 함께 보존했다. 같은 PDF SHA-256의 통과 결과는 자동으로 건너뛰므로 `--resume` 없이도 이어진다. 1차 3.1-flash-lite의 통과 결과는 건너뛰지 않고 재추출했다.

## 학교별 비교

모델은 실제 응답 `modelVersion`을 우선 기록했다. 응답 버전이 없으면 요청 모델을 기록한다. `QUOTA_WAIT`에서 데이터가 없으면 사용 모델은 `—`, 요청 체인/각 결과는 JSON의 `model_attempts`로 확인한다. 이전에 통과한 성균관·서강은 해당 3.8 요청 기록을 그대로 재사용한다.

| 학교 | 지역 | 1차 → 2차 최종 | 모집인원 행 수 | 사용 모델 | 근거 |
| --- | --- | --- | ---: | --- | --- |
| 서강대학교 | SEOUL | 통과 → 통과 | 26 | 3.8-flash | 합계·소계 일치; 3.8-flash:통과; 동일 PDF 통과 결과 재사용 |
| 성균관대학교 | SEOUL | 불일치 → 통과 | 51 | 3.8-flash | 합계·소계 일치; 3.8-flash:통과; 동일 PDF 통과 결과 재사용; 학사 73/73 일치 |
| 연세대학교 (서울) | SEOUL | 검증불가 → 검증불가 | 79 | 3.6-flash | 기재 총계 없음/범위 표현/누락으로 검증불가; 3.8-flash:QUOTA_WAIT → 3.7-flash:QUOTA_WAIT → 3.6-flash:검증불가 → 3.5-flash:검증불가 |
| 가천대학교 | GYEONGGI | 실패 → 실패 | 0 | 3.6-flash | WRONG_DOCUMENT: 잘못된 문서; 3.8-flash:QUOTA_WAIT → 3.7-flash:QUOTA_WAIT → 3.6-flash:QUOTA_WAIT → 3.5-flash:실패; WRONG_DOCUMENT |
| 가톨릭대학교 | GYEONGGI | 불일치 → 통과 | 50 | 3.6-flash | 합계·소계 일치; 3.8-flash:QUOTA_WAIT → 3.7-flash:QUOTA_WAIT → 3.6-flash:통과 |
| 강남대학교 | GYEONGGI | 통과 → 통과 | 18 | 3.6-flash | 합계·소계 일치; 3.8-flash:QUOTA_WAIT → 3.7-flash:QUOTA_WAIT → 3.6-flash:검증불가 → 3.5-flash:통과 |
| 건국대학교 (서울) | SEOUL | 불일치 → 불일치 | 51 | 3.6-flash | 총계 일반편입 431/429 (불일치); 3.8-flash:QUOTA_WAIT → 3.7-flash:QUOTA_WAIT → 3.6-flash:불일치 → 3.5-flash:QUOTA_WAIT |
| 경기대학교 | GYEONGGI | 검증불가 → QUOTA_WAIT | — | — | 3.8-flash:QUOTA_WAIT → 3.7-flash:QUOTA_WAIT → 3.6-flash:QUOTA_WAIT → 3.5-flash:QUOTA_WAIT; 한도 복구 후 3.8부터 재실행 |
| 경희대학교 | SEOUL | 통과 → QUOTA_WAIT | — | — | 3.8-flash:QUOTA_WAIT → 3.7-flash:QUOTA_WAIT → 3.6-flash:QUOTA_WAIT → 3.5-flash:QUOTA_WAIT; 한도 복구 후 3.8부터 재실행 |
| 고려대학교 (서울) | SEOUL | 검증불가 → QUOTA_WAIT | — | — | 3.8-flash:QUOTA_WAIT → 3.7-flash:QUOTA_WAIT → 3.6-flash:QUOTA_WAIT → 3.5-flash:QUOTA_WAIT; 한도 복구 후 3.8부터 재실행 |
| 광운대학교 | SEOUL | 불일치 → QUOTA_WAIT | — | — | 3.8-flash:QUOTA_WAIT → 3.7-flash:QUOTA_WAIT → 3.6-flash:QUOTA_WAIT → 3.5-flash:QUOTA_WAIT; 한도 복구 후 3.8부터 재실행 |
| 국민대학교 | SEOUL | 검증불가 → QUOTA_WAIT | — | — | 3.8-flash:QUOTA_WAIT → 3.7-flash:QUOTA_WAIT → 3.6-flash:QUOTA_WAIT → 3.5-flash:QUOTA_WAIT; 한도 복구 후 3.8부터 재실행 |
| 단국대학교 (죽전) | GYEONGGI | 불일치 → QUOTA_WAIT | — | — | 3.8-flash:QUOTA_WAIT → 3.7-flash:QUOTA_WAIT → 3.6-flash:QUOTA_WAIT → 3.5-flash:QUOTA_WAIT; 한도 복구 후 3.8부터 재실행 |
| 덕성여자대학교 | SEOUL | 통과 → QUOTA_WAIT | — | — | 3.8-flash:QUOTA_WAIT → 3.7-flash:QUOTA_WAIT → 3.6-flash:QUOTA_WAIT → 3.5-flash:QUOTA_WAIT; 한도 복구 후 3.8부터 재실행 |
| 동국대학교 | SEOUL | 불일치 → QUOTA_WAIT | — | — | 3.8-flash:QUOTA_WAIT → 3.7-flash:QUOTA_WAIT → 3.6-flash:QUOTA_WAIT → 3.5-flash:QUOTA_WAIT; 한도 복구 후 3.8부터 재실행 |
| 동덕여자대학교 | SEOUL | 검증불가 → QUOTA_WAIT | — | — | 3.8-flash:QUOTA_WAIT → 3.7-flash:QUOTA_WAIT → 3.6-flash:QUOTA_WAIT → 3.5-flash:QUOTA_WAIT; 한도 복구 후 3.8부터 재실행 |
| 명지대학교 | SEOUL | 검증불가 → QUOTA_WAIT | — | — | 3.8-flash:QUOTA_WAIT → 3.7-flash:QUOTA_WAIT → 3.6-flash:QUOTA_WAIT → 3.5-flash:QUOTA_WAIT; 한도 복구 후 3.8부터 재실행 |
| 삼육대학교 | SEOUL | 통과 → QUOTA_WAIT | — | — | 3.8-flash:QUOTA_WAIT → 3.7-flash:QUOTA_WAIT → 3.6-flash:QUOTA_WAIT → 3.5-flash:QUOTA_WAIT; 한도 복구 후 3.8부터 재실행 |
| 상명대학교 (서울) | SEOUL | 불일치 → QUOTA_WAIT | — | — | 3.8-flash:QUOTA_WAIT → 3.7-flash:QUOTA_WAIT → 3.6-flash:QUOTA_WAIT → 3.5-flash:QUOTA_WAIT; 한도 복구 후 3.8부터 재실행 |
| 서경대학교 | SEOUL | 불일치 → QUOTA_WAIT | — | — | 3.8-flash:QUOTA_WAIT → 3.7-flash:QUOTA_WAIT → 3.6-flash:QUOTA_WAIT → 3.5-flash:QUOTA_WAIT; 한도 복구 후 3.8부터 재실행 |
| 서울과학기술대학교 | SEOUL | 불일치 → QUOTA_WAIT | — | — | 3.8-flash:QUOTA_WAIT → 3.7-flash:QUOTA_WAIT → 3.6-flash:QUOTA_WAIT → 3.5-flash:QUOTA_WAIT; 한도 복구 후 3.8부터 재실행 |
| 서울대학교 | SEOUL | 검증불가 → QUOTA_WAIT | — | — | 3.8-flash:QUOTA_WAIT → 3.7-flash:QUOTA_WAIT → 3.6-flash:QUOTA_WAIT → 3.5-flash:QUOTA_WAIT; 한도 복구 후 3.8부터 재실행 |
| 서울시립대학교 | SEOUL | 통과 → QUOTA_WAIT | — | — | 3.8-flash:QUOTA_WAIT → 3.7-flash:QUOTA_WAIT → 3.6-flash:QUOTA_WAIT → 3.5-flash:QUOTA_WAIT; 한도 복구 후 3.8부터 재실행 |
| 서울여자대학교 | SEOUL | 통과 → QUOTA_WAIT | — | — | 3.8-flash:QUOTA_WAIT → 3.7-flash:QUOTA_WAIT → 3.6-flash:QUOTA_WAIT → 3.5-flash:QUOTA_WAIT; 한도 복구 후 3.8부터 재실행 |
| 성신여자대학교 | SEOUL | 불일치 → QUOTA_WAIT | — | — | 3.8-flash:QUOTA_WAIT → 3.7-flash:QUOTA_WAIT → 3.6-flash:QUOTA_WAIT → 3.5-flash:QUOTA_WAIT; 한도 복구 후 3.8부터 재실행 |
| 세종대학교 | SEOUL | 불일치 → QUOTA_WAIT | — | — | 3.8-flash:QUOTA_WAIT → 3.7-flash:QUOTA_WAIT → 3.6-flash:QUOTA_WAIT → 3.5-flash:QUOTA_WAIT; 한도 복구 후 3.8부터 재실행 |
| 수원대학교 | GYEONGGI | 불일치 → QUOTA_WAIT | — | — | 3.8-flash:QUOTA_WAIT → 3.7-flash:QUOTA_WAIT → 3.6-flash:QUOTA_WAIT → 3.5-flash:QUOTA_WAIT; 한도 복구 후 3.8부터 재실행 |
| 숙명여자대학교 | SEOUL | 통과 → QUOTA_WAIT | — | — | 3.8-flash:QUOTA_WAIT → 3.7-flash:QUOTA_WAIT → 3.6-flash:QUOTA_WAIT → 3.5-flash:QUOTA_WAIT; 한도 복구 후 3.8부터 재실행 |
| 숭실대학교 | SEOUL | 검증불가 → QUOTA_WAIT | — | — | 3.8-flash:QUOTA_WAIT → 3.7-flash:QUOTA_WAIT → 3.6-flash:QUOTA_WAIT → 3.5-flash:QUOTA_WAIT; 한도 복구 후 3.8부터 재실행 |
| 아주대학교 | GYEONGGI | 검증불가 → QUOTA_WAIT | — | — | 3.8-flash:QUOTA_WAIT → 3.7-flash:QUOTA_WAIT → 3.6-flash:QUOTA_WAIT → 3.5-flash:QUOTA_WAIT; 한도 복구 후 3.8부터 재실행 |
| 용인대학교 | GYEONGGI | 불일치 → QUOTA_WAIT | — | — | 3.8-flash:QUOTA_WAIT → 3.7-flash:QUOTA_WAIT → 3.6-flash:QUOTA_WAIT → 3.5-flash:QUOTA_WAIT; 한도 복구 후 3.8부터 재실행 |
| 이화여자대학교 | SEOUL | 불일치 → QUOTA_WAIT | — | — | 3.8-flash:QUOTA_WAIT → 3.7-flash:QUOTA_WAIT → 3.6-flash:QUOTA_WAIT → 3.5-flash:QUOTA_WAIT; 한도 복구 후 3.8부터 재실행 |
| 인천대학교 | INCHEON | 불일치 → QUOTA_WAIT | 65 | 3.6-flash | 총계 농어촌학생 17/13 (불일치); 3.8-flash:QUOTA_WAIT → 3.7-flash:QUOTA_WAIT → 3.6-flash:불일치 → 3.5-flash:QUOTA_WAIT; 한도 복구 후 3.8부터 재실행; 실제 일반 추출 **299 / 기재 299** |
| 인하대학교 | INCHEON | 통과 → QUOTA_WAIT | — | — | 3.8-flash:QUOTA_WAIT → 3.7-flash:QUOTA_WAIT → 3.6-flash:QUOTA_WAIT → 3.5-flash:QUOTA_WAIT; 한도 복구 후 3.8부터 재실행 |
| 중앙대학교 | SEOUL | 요강없음 → QUOTA_WAIT | — | — | 3.8-flash:QUOTA_WAIT → 3.7-flash:QUOTA_WAIT → 3.6-flash:QUOTA_WAIT → 3.5-flash:QUOTA_WAIT; 한도 복구 후 3.8부터 재실행 |
| 한국공학대학교 | GYEONGGI | 요강없음 → QUOTA_WAIT | — | — | 3.8-flash:QUOTA_WAIT → 3.7-flash:QUOTA_WAIT → 3.6-flash:QUOTA_WAIT → 3.5-flash:QUOTA_WAIT; 한도 복구 후 3.8부터 재실행; 최초 게시판 PDF는 면접 유의사항(2쪽); 모집요강으로 채택 금지 |
| 한국외국어대학교 | SEOUL | 불일치 → QUOTA_WAIT | — | — | 3.8-flash:QUOTA_WAIT → 3.7-flash:QUOTA_WAIT → 3.6-flash:QUOTA_WAIT → 3.5-flash:QUOTA_WAIT; 한도 복구 후 3.8부터 재실행 |
| 한국항공대학교 | GYEONGGI | 검증불가 → QUOTA_WAIT | — | — | 3.8-flash:QUOTA_WAIT → 3.7-flash:QUOTA_WAIT → 3.6-flash:QUOTA_WAIT → 3.5-flash:QUOTA_WAIT; 한도 복구 후 3.8부터 재실행 |
| 한성대학교 | SEOUL | 불일치 → QUOTA_WAIT | — | — | 3.8-flash:QUOTA_WAIT → 3.7-flash:QUOTA_WAIT → 3.6-flash:QUOTA_WAIT → 3.5-flash:QUOTA_WAIT; 한도 복구 후 3.8부터 재실행 |
| 한세대학교 | GYEONGGI | 통과 → QUOTA_WAIT | — | — | 3.8-flash:QUOTA_WAIT → 3.7-flash:QUOTA_WAIT → 3.6-flash:QUOTA_WAIT → 3.5-flash:QUOTA_WAIT; 한도 복구 후 3.8부터 재실행 |
| 한신대학교 | GYEONGGI | 검증불가 → QUOTA_WAIT | — | — | 3.8-flash:QUOTA_WAIT → 3.7-flash:QUOTA_WAIT → 3.6-flash:QUOTA_WAIT → 3.5-flash:QUOTA_WAIT; 한도 복구 후 3.8부터 재실행 |
| 한양대학교 (서울) | SEOUL | 검증불가 → QUOTA_WAIT | — | — | 3.8-flash:QUOTA_WAIT → 3.7-flash:QUOTA_WAIT → 3.6-flash:QUOTA_WAIT → 3.5-flash:QUOTA_WAIT; 한도 복구 후 3.8부터 재실행 |
| 한양대학교 (ERICA) | GYEONGGI | 검증불가 → QUOTA_WAIT | — | — | 3.8-flash:QUOTA_WAIT → 3.7-flash:QUOTA_WAIT → 3.6-flash:QUOTA_WAIT → 3.5-flash:QUOTA_WAIT; 한도 복구 후 3.8부터 재실행 |
| 홍익대학교 (서울) | SEOUL | 실패 (출처 미확인) → QUOTA_WAIT | — | — | 3.8-flash:QUOTA_WAIT → 3.7-flash:QUOTA_WAIT → 3.6-flash:QUOTA_WAIT → 3.5-flash:QUOTA_WAIT; 한도 복구 후 3.8부터 재실행; 새 PDF: articleNo=145569, attachNo=84807 |

## 모델별 무료 할당량 응답

다음은 실제 429 응답의 `QuotaFailure.violations` 값이다. 성공 응답만 있거나 429에 값이 없으면 추정하지 않고 `응답에 없음`으로 기록한다. 요청 모델과 응답의 `quotaDimensions.model`이 다르면 그대로 표시한다. 이 경우 요청 모델 자체의 독립 한도로 단정하지 않는다.

| 요청 모델 | 429 응답의 한도 | 응답 대상 모델 | 서버 대기시간 |
| --- | --- | --- | --- |
| gemini-3.8-flash | GenerateRequestsPerDayPerProjectPerModel-FreeTier = 20 | gemini-3.8-flash | 54792s |
| gemini-3.7-flash | GenerateRequestsPerDayPerProjectPerModel-FreeTier = 20 | gemini-3.8-flash | 54791s |
| gemini-3.6-flash | GenerateRequestsPerDayPerProjectPerModel-FreeTier = 20 | gemini-3.6-flash | 54283s |
| gemini-3.5-flash | GenerateRequestsPerDayPerProjectPerModel-FreeTier = 20 | gemini-3.6-flash | 54358s |

모델별 원시 허용 필드 증거: `crawler/artifacts/t6-chain/model_quotas.json`. API 키·인증 헤더·오류 본문은 저장하지 않는다.

## 내일 3.8 한도 복구 후 재실행 대상

통과하지 못한 학교는 아래 전부 재실행 대상이다. 실행 중인 학교는 최종 상태가 정해지면 이 목록을 갱신한다. 가천대의 잘못된 학년도 PDF와 한국공학대 면접 유의사항은 올바른 2026 편입 모집요강을 먼저 확보해야 한다. 학교별 PDF를 교체하면 출처·SHA-256 메타데이터도 함께 갱신해야 한다.

| 학교 | 재실행 사유 |
| --- | --- |
| 연세대학교 (서울) | 검증불가 |
| 가천대학교 | 실패 / WRONG_DOCUMENT |
| 건국대학교 (서울) | 불일치 |
| 경기대학교 | QUOTA_WAIT |
| 경희대학교 | QUOTA_WAIT |
| 고려대학교 (서울) | QUOTA_WAIT |
| 광운대학교 | QUOTA_WAIT |
| 국민대학교 | QUOTA_WAIT |
| 단국대학교 (죽전) | QUOTA_WAIT |
| 덕성여자대학교 | QUOTA_WAIT |
| 동국대학교 | QUOTA_WAIT |
| 동덕여자대학교 | QUOTA_WAIT |
| 명지대학교 | QUOTA_WAIT |
| 삼육대학교 | QUOTA_WAIT |
| 상명대학교 (서울) | QUOTA_WAIT |
| 서경대학교 | QUOTA_WAIT |
| 서울과학기술대학교 | QUOTA_WAIT |
| 서울대학교 | QUOTA_WAIT |
| 서울시립대학교 | QUOTA_WAIT |
| 서울여자대학교 | QUOTA_WAIT |
| 성신여자대학교 | QUOTA_WAIT |
| 세종대학교 | QUOTA_WAIT |
| 수원대학교 | QUOTA_WAIT |
| 숙명여자대학교 | QUOTA_WAIT |
| 숭실대학교 | QUOTA_WAIT |
| 아주대학교 | QUOTA_WAIT |
| 용인대학교 | QUOTA_WAIT |
| 이화여자대학교 | QUOTA_WAIT |
| 인천대학교 | QUOTA_WAIT |
| 인하대학교 | QUOTA_WAIT |
| 중앙대학교 | QUOTA_WAIT |
| 한국공학대학교 | QUOTA_WAIT |
| 한국외국어대학교 | QUOTA_WAIT |
| 한국항공대학교 | QUOTA_WAIT |
| 한성대학교 | QUOTA_WAIT |
| 한세대학교 | QUOTA_WAIT |
| 한신대학교 | QUOTA_WAIT |
| 한양대학교 (서울) | QUOTA_WAIT |
| 한양대학교 (ERICA) | QUOTA_WAIT |
| 홍익대학교 (서울) | QUOTA_WAIT |

재실행 대상 **40곳**. T8부터는 위 「T8 일일 분할 실행」 우선순위·일일 예산으로 나눠 처리한다. 아래 명령은 통과 학교를 자동 건너뛰고 나머지를 3.8부터 다시 처리한다. 새로운 실행에서는 한도 소진 상태를 초기화한다.

```sh
cd crawler
.venv/bin/pyeonit-crawler backfill --year 2026 --model gemini-3.8-flash --output artifacts/t6-chain --concurrency 3
```

## 성균관·인천대·서강 직접 검토

- 성균관: `crawler/artifacts/t6-chain/skku-2026.json`, 학사 **73/73 일치**. 최초 3.8 추출 모집인원 51행. 원본 SHA-256 `91e5561ee528a519eecf9f2b849238cfeb7202c534a5e0c2b10b68352296fd96`.
- 서강: `crawler/artifacts/t6-chain/sogang-2026.json`, 합계 검증 통과. 해당 JSON과 PDF 해시를 확인해 자동 재사용했다.
- 인천: `crawler/artifacts/t6-chain/인천대학교-2026.json`, 실제 값 **일반편입 299 / 기재 299**.
- 원문 시각 검토: 성균관 PDF 9쪽, 인천 PDF 5쪽을 PNG로 렌더링해 병합 셀과 총계를 확인했다(`crawler/artifacts/t6-review/`). merge 전에 조율자가 세 학교 JSON을 직접 확인한다.

## 구현 및 출처 보완

- `gemini.py`에 T7이 import할 순서 있는 단일 `MODEL_CHAIN` 리스트를 공개했다. 기본값은 3.8이다. 429만 다음 모델로 넘기고 503·타임아웃은 같은 모델에서 서버 지정 대기시간 우선 또는 1/2/4초 백오프로 재시도한다. 재시도 소진 후 해당 학교만 실패로 보존한다.
- 각 모델에서 합계 불일치 시 같은 PDF로 최대 2회 재추출 후 다음 모델을 시도한다. 검증불가는 해당 모델에서 1회 후 다음 모델로 넘긴다. 최초 통과 즉시 채택한다. 모델별 시도 순서·결과·실제 모델·재추출 이력은 v4 JSON의 `model_attempts`, `source.model`, `source.requested_model`에 남긴다.
- `ed5984e:crawler/prototype/extract.py`의 PROMPT를 복원·강화했다. 병합 인원은 단과대학 행으로 분리하고 학과의 전형 열은 모두 기록한다. 입학정원·여석·전년도 열과 소계·합계 행을 학과에 섞지 않으며 반복 표는 첫 모집인원 표만 사용한다.
- 허용 범위 밖 `schema.py`는 수정하지 않고 `gemini.py`에서 복사한 스키마에 필수 `문서종류`를 추가했다. 해당 학년도 편입 모집요강이 아니면 `잘못된 문서`/`WRONG_DOCUMENT` 실패로 구분한다. `IDENTITY_MISMATCH`와 별도 사유이다.
- `finishReason=MAX_TOKENS`의 부분 JSON은 채택하지 않는다. 출력 토큰 **32768 → 65536**, **65536 상한**에서도 잘리면 실패한다.
- 범위 표현(이내·내외·약간명)과 칸 누락을 별도 집계한다. 누락은 `MISSING_CELL` 경고 및 검증불가로 표시하며 숫자 불일치로 세지 않는다. 원문은 보존한다.
- 광운·삼육·서울시립·성신·수원·중앙·한국공학·한신·홍익의 누락 메뉴를 Playwright로 다시 찾아 공지·요강 두 종류를 모두 확보했다. 중앙대는 공지 랜딩을 가리키던 요강 URL을 실제 요강 메뉴로 교체했다. 한 종류만 남은 학교가 없어 미확인 사유 주석 대상은 없다. 탐색 증거: `artifacts/t6-discovery.json`, `t6-menu-verification.log`.
- 홍익대 목록은 접근 가능했으나 상세 페이지 스크립트가 외부 화면으로 이동했다. 공식 서버가 반환한 공개 HTML 앵커를 보완 파싱해 `articleNo=145569`, `attachNo=84807` PDF를 새로 다운로드했다. TLS 예외는 기존 두 호스트(`admission.sogang.ac.kr`, `admission.gachon.ac.kr`)에만 유지했다.

## 범위 제외

| 학교 | 처리 |
| --- | --- |
| 강서대학교 | 범위 제외 (T6 확정 44개교 외) |
| 경동대학교 (메트로폴) | 범위 제외 (T6 확정 44개교 외) |
| 대진대학교 | 범위 제외 (T6 확정 44개교 외) |
| 동양대학교 (동두천) | 범위 제외 (T6 확정 44개교 외) |
| 성결대학교 | 범위 제외 (T6 확정 44개교 외) |
| 성공회대학교 | 범위 제외 (T6 확정 44개교 외) |
| 신한대학교 | 범위 제외 (T6 확정 44개교 외) |
| 안양대학교 | 범위 제외 (T6 확정 44개교 외) |
| 을지대학교 | 범위 제외 (T6 확정 44개교 외) |
| 인천가톨릭대학교 | 범위 제외 (T6 확정 44개교 외) |
| 차의과학대학교 | 범위 제외 (T6 확정 44개교 외) |
| 청운대학교 (인천) | 범위 제외 (T6 확정 44개교 외) |
| 평택대학교 | 범위 제외 (T6 확정 44개교 외) |
| 한경국립대학교 | 범위 제외 (T6 확정 44개교 외) |
| 협성대학교 | 범위 제외 (T6 확정 44개교 외) |
| 화성의과학대학교 | 범위 제외 (T6 확정 44개교 외) |
| 국립한국교통대학교 (의왕) | 범위 제외 (T6 확정 44개교 외) |

## 검증 및 인계

```sh
cd crawler
.venv/bin/pytest -q -m ''
.venv/bin/ruff check src/pyeonit_crawler/{cli,download,gemini,validation}.py tests --exclude tests/monitor
.venv/bin/ruff format --check src/pyeonit_crawler/{cli,download,gemini,validation}.py tests/test_{cli,download,gemini,validation}.py
git diff --check
git check-ignore artifacts/t6-chain/summary.json .venv/bin/python
```

- 네트워크 다운로드 3건을 포함한 pytest **128개 통과**. 모델 체인 순서/lite 미포함, 429 전환, 503·타임아웃 전환 금지, 모델별 재추출, 실제 모델 버전 저장, 통과 학교 자동 skip, lite 통과 재추출, QUOTA_WAIT 및 정상 종료, MAX_TOKENS 상한, 누락/범위 표현 구분, PDF 해시 무결성, 홍익 HTML 첨부 추출을 테스트했다.
- Ruff·diff 공백 검사 통과. artifacts·.env·가상환경은 Git 제외 대상이며 커밋하지 않는다. 로컬 환경에 기존 의존성 psycopg[binary]·PyYAML과 PDF 검토용 PyMuPDF를 설치했으며 의존성 선언 파일은 변경하지 않았다.
- T7 전달: `crawler/src/pyeonit_crawler/monitor/__main__.py`의 별도 기본값은 아직 `gemini-3.1-flash-lite`이다. 소유권 제한으로 수정하지 않았다. T7/조율자가 `MODEL_CHAIN`을 import하여 최신 체인/QUOTA_WAIT/통과 전용 채택 정책을 반영해야 한다. DB·monitor 구현 파일은 변경하지 않았다.

---

## 1차 실행 기록 (보존)

# 2026학년도 수도권 일반대학 초기 수집 결과

실행일: 2026-11-08. Python 3.15.2 / Playwright Chromium / Gemini REST `gemini-3.1-flash-lite`. DB 저장 없음.

사용자의 최종 범위는 서울·경기·인천의 일반 종합대학이다. 전문대·원격대·교육대·신학/종교·예술/체육 특화 대학은 제외했다. 통합 모집요강은 학교당 한 번 수집하고, 한양대 서울/ERICA처럼 별도 요강을 제공하는 캠퍼스는 구분했다. 통합 PDF에 들어 있는 타지역 캠퍼스 행은 원문 검증을 위해 보존하므로, JSON 전체를 수도권 모집인원 합계로 곧바로 집계하면 안 된다.

최종 YAML: **60개 수집 항목(59개 대학)**. 통과 11개, 불일치 25개, 검증불가 17개, 요강없음 4개, 실패 3개. 이와 별도로 홍익대 서울은 공식 사이트 봇 차단으로 출처 검증에 실패해 YAML 끝 주석에 기록했다.

## 실행 및 재현

```sh
cd crawler
.venv/bin/python -m pyeonit_crawler backfill --year 2026 --concurrency 2 --resume
.venv/bin/python -m pyeonit_crawler backfill --year 2026 --schools 가천대학교 --concurrency 1
.venv/bin/python -m pyeonit_crawler backfill --year 2026 --schools 차의과학대학교 --concurrency 1
```

전체 실행 종료 코드 **1**, 가천대 개별 확인 **1**, 차의과학대 개별 확인 **0**. 전체 항목의 처리가 완료됐으며, 불일치·검증불가·요강없음·실패가 포함되어 비정상 종료 코드를 반환한 것이다. 초회 전체 수집 도중 범위가 일반대학으로 좁혀졌고, 의왕 캠퍼스를 보완한 최종 60개 목록으로 전체 재실행했다. 이후 가천대 인증서 예외와 차의과학대 학교명 공백 정규화를 개별 재확인했으며, `summary.json`은 전체 실행 상태에 이 두 학교의 마지막 상태를 합쳐 보존했다. `--schools` 단독 실행은 summary를 선택한 학교로 교체하므로 운영 시 전체 summary를 별도로 보존해야 한다. `--resume`은 출처 목록·캠퍼스·지역·학년도·모델·원본 PDF 해시와 재계산 검증 결과가 일치하는 기존 JSON만 재사용한다. 미통과 판정도 그대로 유지하며 통과로 바꾸지 않는다. 새 폴더에서 전부 재추출하려면 `--resume`을 빼거나 `--output artifacts/fresh`를 지정한다.

최종 실행에서 기존 결과 42개를 재사용했다. 초기 동시 요청 6개 실행에서 API 429가 발생했고, 응답의 분당 입력 토큰 제한(250,000)과 `RetryInfo` 55초를 확인했다. 단기 제한이 명시된 429만 최대 3회 전송 재시도하며 서버 대기 시간(최대 60초)을 따른다. 일일 제한·긴 대기·불명확한 429는 즉시 실패로 남긴다.

## 학교별 최종 상태

상태 의미: 통과=추출한 합계·소계 모두 일치, 불일치=숫자 합계 불일치, 검증불가=기재 총계 없음·비숫자/누락 셀 등. **요강없음은 지정된 출처에서 현재 크롤러가 유효 PDF를 찾지 못했다는 뜻이며 실제 미모집을 의미하지 않는다.** 실패는 접속·사이트 스크립트·API·학교/학년도 또는 응답 형식 오류 등이다. JavaScript 전용 첨부나 목록 페이지 밖의 과거 공지는 추가 대응이 필요할 수 있다.

| 학교 / 캠퍼스 | 지역 | 상태 | 모집인원 행 | 추출 시도 | 근거 / 후속 확인 |
| --- | --- | --- | ---: | ---: | --- |
| 서강대학교 | SEOUL | 통과 | 26 | 1 | 합계·소계 일치 · [공식 출처](https://admission.sogang.ac.kr/enter/html/transfer/guide.asp) |
| 성균관대학교 | SEOUL | 불일치 | 45 | 3 | 총계 학사 편입학: 56/73; 재추출 중 HTTPStatusError · [공식 출처](https://admission.skku.edu/admission/html/transfer/guide.html) |
| 연세대학교 (서울) | SEOUL | 검증불가 | 74 | 1 | 기재 총계 없음 또는 비숫자·누락 셀 보존 · [공식 출처](https://admission.yonsei.ac.kr/seoul/admission/html/transfer/guide.asp) |
| 가천대학교 | GYEONGGI | 실패 | — | — | 추출 IDENTITY_MISMATCH · [공식 출처](https://admission.gachon.ac.kr/admission/html/transfer/guide1.asp) |
| 가톨릭대학교 | GYEONGGI | 불일치 | 36 | 3 | 총계 일반 편입학: 473/77 · [공식 출처](https://ipsi.catholic.ac.kr/submenu.do?menuurl=lktmA6TpgpjUpNE2tkO4bw%3d%3d&) |
| 강남대학교 | GYEONGGI | 통과 | 8 | 1 | 합계·소계 일치 · [공식 출처](https://admission.kangnam.ac.kr/jeonhyung/yogang.htm?ctg_cd=transfer) |
| 강서대학교 | SEOUL | 요강없음 | — | — | 고정 요강/공지에서 지원 방식의 PDF 미발견 · [공식 출처](https://entrance.gangseo.ac.kr/kcui/homeService?menuCode=MC0500) |
| 건국대학교 (서울) | SEOUL | 불일치 | 51 | 3 | 총계 일반편입: 431/429; 재추출 중 HTTPStatusError · [공식 출처](https://admission.konkuk.ac.kr/admission/37889/subview.do) |
| 경기대학교 | GYEONGGI | 검증불가 | 56 | 1 | 기재 총계 없음 또는 비숫자·누락 셀 보존 · [공식 출처](https://enter.kyonggi.ac.kr/cms/FR_CON/index.do?MENU_ID=160) |
| 경동대학교 (메트로폴) | GYEONGGI | 검증불가 | 29 | 1 | 기재 총계 없음 또는 비숫자·누락 셀 보존 · [공식 출처](https://www.kduniv.ac.kr/iphak/CMS/Contents/Contents.do?mCode=MN036) |
| 경희대학교 | SEOUL | 통과 | 76 | 1 | 합계·소계 일치 · [공식 출처](https://iphak.khu.ac.kr/submenu.do?menuurl=pNIJTWxI%2b8B8m%2bUBBAchHA%3d%3d&) |
| 고려대학교 (서울) | SEOUL | 검증불가 | 59 | 1 | 기재 총계 없음 또는 비숫자·누락 셀 보존 · [공식 출처](https://oku.korea.ac.kr/oku/cms/FR_CON/index.do?MENU_ID=660) |
| 광운대학교 | SEOUL | 불일치 | 41 | 3 | 총계 일반: 119/39; 총계 특성화고졸업자: 3/2; 총계 학사: 59/33 외 5건 · [공식 출처](https://iphak.kw.ac.kr/mojib/mojib.php?m_type=PYEONIP) |
| 국민대학교 | SEOUL | 검증불가 | 61 | 1 | 기재 총계 없음 또는 비숫자·누락 셀 보존 · [공식 출처](https://admission.kookmin.ac.kr/transfer/application.php) |
| 단국대학교 (죽전) | GYEONGGI | 불일치 | 47 | 3 | 총계 일반편입: 337/348; 총계 학사편입: 43/45 · [공식 출처](https://ipsi.dankook.ac.kr/jukjeon/doumi/mojip.html?bbsid=juk_paper&ctg_cd=05&kw=03952E) |
| 대진대학교 | GYEONGGI | 불일치 | 35 | 3 | 총계 일반편입(정원내): 186/184; 재추출 중 HTTPStatusError · [공식 출처](https://admission.daejin.ac.kr/submenu.do?menuurl=iR4AzM40LcXsoQSN2OSCIg%3d%3d&) |
| 덕성여자대학교 | SEOUL | 통과 | 38 | 1 | 합계·소계 일치 · [공식 출처](https://enter.duksung.ac.kr/mojib/?m_type=PYEONIP) |
| 동국대학교 | SEOUL | 불일치 | 39 | 3 | 총계 일반편입(일반): 49/41 · [공식 출처](https://ipsi.dongguk.edu/admission/html/transfer/guide.asp) |
| 동덕여자대학교 | SEOUL | 검증불가 | 41 | 1 | 기재 총계 없음 또는 비숫자·누락 셀 보존 · [공식 출처](https://ipsi.dongduk.ac.kr/ipsi/contents/transfer-viewer.do?gotoMenuNo=transfer-viewer) |
| 동양대학교 (동두천) | GYEONGGI | 요강없음 | — | — | 고정 요강/공지에서 지원 방식의 PDF 미발견 · [공식 출처](https://ipsi.dyu.ac.kr/information/information_01/) |
| 명지대학교 | SEOUL | 검증불가 | 47 | 1 | 기재 총계 없음 또는 비숫자·누락 셀 보존 · [공식 출처](https://iphak.mju.ac.kr/pages/?p=26&mj=04) |
| 삼육대학교 | SEOUL | 통과 | 24 | 1 | 합계·소계 일치 · [공식 출처](https://ipsi.syu.ac.kr/2016_syu/pages/index.asp?p=20&mj=04) |
| 상명대학교 (서울) | SEOUL | 불일치 | 32 | 3 | 총계 일반편입: 196/164; 총계 학사편입: 38/26 · [공식 출처](https://admission.smu.ac.kr/_seoul/iphak/mojip.html?bbsid=seoul_mojib&ctg_cd=pyunip) |
| 서경대학교 | SEOUL | 불일치 | 25 | 3 | 총계 농어촌학생편입: 6/7; 총계 일반편입: 119/110; 총계 학사편입: 20/26; 재추출 중 HTTPStatusError · [공식 출처](https://go.skuniv.ac.kr/index.php?mid=transfer_admission_guide) |
| 서울과학기술대학교 | SEOUL | 불일치 | 36 | 3 | 총계 일반: 134/132; 총계 평생학습자: 3/5 · [공식 출처](https://admission.seoultech.ac.kr/cms/FR_CON/index.do?MENU_ID=290) |
| 서울대학교 | SEOUL | 검증불가 | 42 | 1 | 기재 총계 없음 또는 비숫자·누락 셀 보존 · [공식 출처](https://admission.snu.ac.kr/transfer/transfer/guide) |
| 서울시립대학교 | SEOUL | 통과 | 45 | 1 | 합계·소계 일치 · [공식 출처](https://admission.uos.ac.kr/admissionNew/html/pyunib/info.do?menuid=2002003001000000000) |
| 서울여자대학교 | SEOUL | 통과 | 38 | 1 | 합계·소계 일치 · [공식 출처](https://admission.swu.ac.kr/bbs/board.php?bo_table=flashpaper&sca=%ED%8E%B8%EC%9E%85%ED%95%99&sub_ck=1) |
| 성결대학교 | GYEONGGI | 검증불가 | 28 | 1 | 기재 총계 없음 또는 비숫자·누락 셀 보존 · [공식 출처](https://ipsi.sungkyul.ac.kr/10000016) |
| 성공회대학교 | SEOUL | 불일치 | 6 | 3 | 총계 편입학 모집인원 총 계: 0/51 · [공식 출처](https://enter.skhu.ac.kr/enter/3075/subview.do) |
| 성신여자대학교 | SEOUL | 불일치 | 43 | 3 | 총계 기회균형선발: 3/4; 총계 농·어촌학생: 11/10; 총계 일반편입학: 200/142 외 1건 · [공식 출처](https://ipsi.sungshin.ac.kr/guide/recruit.htm?ctg_cd=transfer) |
| 세종대학교 | SEOUL | 불일치 | 42 | 3 | 총계 농어촌학생: 22/18; 총계 특성화고교졸업자: 9/10 · [공식 출처](https://ipsi.sejong.ac.kr/ipsi/transfer/recruitment-guidelines.do) |
| 수원대학교 | GYEONGGI | 불일치 | 50 | 3 | 총계 학사: 43/42; 재추출 중 HTTPStatusError,HTTPStatusError · [공식 출처](https://ipsi.suwon.ac.kr/guide/transfer) |
| 숙명여자대학교 | SEOUL | 통과 | 40 | 1 | 합계·소계 일치 · [공식 출처](https://admission.sookmyung.ac.kr/admission/html/transfer/guide.asp) |
| 숭실대학교 | SEOUL | 검증불가 | 0 | 1 | 기재 총계 없음 또는 비숫자·누락 셀 보존 · [공식 출처](https://iphak.ssu.ac.kr/mojip/req.asp?flag=3&page_no=1_4_2) |
| 신한대학교 | GYEONGGI | 검증불가 | 38 | 1 | 기재 총계 없음 또는 비숫자·누락 셀 보존 · [공식 출처](https://ipsi.shinhan.ac.kr/adm/info.do?cateId=4&bbsId=BBSMSTR_000000000051&relateImagePath=svis_4) |
| 아주대학교 | GYEONGGI | 검증불가 | 37 | 1 | 기재 총계 없음 또는 비숫자·누락 셀 보존 · [공식 출처](https://www.iajou.ac.kr/mojib/?m_type=PYEONIP) |
| 안양대학교 | GYEONGGI | 불일치 | 34 | 3 | 총계 기회균형: 5/6; 총계 농어촌학생: 9/8; 총계 학사편입: 29/22 · [공식 출처](https://enter.anyang.ac.kr/10000022) |
| 용인대학교 | GYEONGGI | 불일치 | 28 | 3 | 총계 기초생활수급자 및 차상위계층: 5/7; 총계 의료인력: 2/0; 총계 일반: 279/280 · [공식 출처](https://ipsi.yongin.ac.kr/pyunip/guide.do) |
| 을지대학교 | GYEONGGI | 실패 | — | — | Error · [공식 출처](https://admission.eulji.ac.kr/?menuno=2735) |
| 이화여자대학교 | SEOUL | 불일치 | 61 | 3 | 총계 학사편입학: 69/61 · [공식 출처](https://admission.ewha.ac.kr/admission/html/transfer/guide.asp) |
| 인천가톨릭대학교 | INCHEON | 검증불가 | 4 | 1 | 기재 총계 없음 또는 비숫자·누락 셀 보존 · [공식 출처](https://admission.iccu.ac.kr/admission/php/transfer_a.php) |
| 인천대학교 | INCHEON | 불일치 | 4 | 3 | 총계 기회균형: 0/3; 총계 유치원교사양성: 0/11; 총계 일반: 2/299 외 2건 · [공식 출처](https://admission.inu.ac.kr/submenu.do?menuurl=hB8GYKGYSI7BBlNJFDXa%2bQ%3d%3d&) |
| 인하대학교 | INCHEON | 통과 | 56 | 1 | 합계·소계 일치 · [공식 출처](https://admission.inha.ac.kr/cms/FR_CON/index.do?MENU_ID=620) |
| 중앙대학교 | SEOUL | 요강없음 | — | — | 고정 요강/공지에서 지원 방식의 PDF 미발견 · [공식 출처](https://admission.cau.ac.kr/submenu.do?menuurl=8Dv5j3LJonuTK%2BIWKKbkOQ%3D%3D) |
| 차의과학대학교 | GYEONGGI | 통과 | 1 | 1 | 합계·소계 일치 · [공식 출처](https://admission.cha.ac.kr/%ed%8e%b8%ec%9e%85%ed%95%99/%eb%aa%a8%ec%a7%91%ec%9a%94%ea%b0%95/) |
| 청운대학교 (인천) | INCHEON | 불일치 | 31 | 3 | 총계 일반전형(학생부): 748/565 · [공식 출처](https://enter.chungwoon.ac.kr/enter/contents.do?key=112) |
| 평택대학교 | GYEONGGI | 불일치 | 26 | 3 | 총계 기회균형: 3/4 · [공식 출처](https://entrance.ptu.ac.kr/entrance/3665/subview.do) |
| 한경국립대학교 | GYEONGGI | 불일치 | 46 | 3 | 총계 농어촌학생: 6/1; 총계 일반전형: 199/157; 총계 재외국민: 2/1 외 4건 · [공식 출처](https://ipsi.hknu.ac.kr/mojib/mojib.php?m_type=PYEONIP) |
| 한국공학대학교 | GYEONGGI | 요강없음 | — | — | 고정 요강/공지에서 지원 방식의 PDF 미발견 · [공식 출처](https://iphak.tukorea.ac.kr/transfer/guide.htm) |
| 한국외국어대학교 | SEOUL | 불일치 | 65 | 3 | 총계 학사편입학: 99/67 · [공식 출처](https://adms.hufs.ac.kr/cms/FrCon/index.do?MENU_ID=230) |
| 한국항공대학교 | GYEONGGI | 검증불가 | 12 | 1 | 기재 총계 없음 또는 비숫자·누락 셀 보존 · [공식 출처](https://ibhak.kau.ac.kr/admission/html/transfer/guide.asp) |
| 한성대학교 | SEOUL | 불일치 | 23 | 3 | 총계 농어촌편입: 4/3; 총계 일반편입: 165/163; 총계 특성화고교졸편입: 3/4 · [공식 출처](https://enter.hansung.ac.kr/?m1=menu03%25&m2=sub00%25&board=application%25&application=8%25) |
| 한세대학교 | GYEONGGI | 통과 | 19 | 1 | 합계·소계 일치 · [공식 출처](https://ipsi.hansei.ac.kr/view.do?no=1083) |
| 한신대학교 | GYEONGGI | 검증불가 | 29 | 1 | 기재 총계 없음 또는 비숫자·누락 셀 보존 · [공식 출처](https://ent.hs.ac.kr/ipsi/pages/?p=12&mj=03) |
| 한양대학교 (서울) | SEOUL | 검증불가 | 49 | 1 | 기재 총계 없음 또는 비숫자·누락 셀 보존 · [공식 출처](https://go.hanyang.ac.kr/web/mojib/mojib.do?m_type=PYEONIP) |
| 한양대학교 (ERICA) | GYEONGGI | 검증불가 | 27 | 1 | 기재 총계 없음 또는 비숫자·누락 셀 보존 · [공식 출처](https://goerica.hanyang.ac.kr/admission/html/transfer/guide.asp) |
| 협성대학교 | GYEONGGI | 불일치 | 28 | 3 | 총계 기초생활수급자/차상위계층/한부모가족: 3/1; 총계 특성화고졸업자: 1/3; 재추출 중 HTTPStatusError · [공식 출처](https://iphak.uhs.ac.kr/pyuniphak/mojip.do) |
| 화성의과학대학교 | GYEONGGI | 실패 | — | — | PAGE_SCRIPT_CONFLICT · [공식 출처](https://www.hsmu.ac.kr/admission/contents/AMS30100000.do) |
| 국립한국교통대학교 (의왕) | GYEONGGI | 불일치 | 48 | 3 | 총계 학사편입(주간): 46/37 · [공식 출처](https://www.ut.ac.kr/prog/rcrit/ipsi/sub03_01/view.do?cntNo=36) |
| 홍익대학교 (서울) | SEOUL | 실패 (출처 미확인) | — | — | 입학처 → STCLab BotManager 차단. [탐색 주소](https://admission.hongik.ac.kr/) |

접속/원문 제한: 가천대는 인증서 예외 후 PDF 다운로드가 가능해졌으나 현재 고정 페이지에 2027학년도 예정 자료가 게시되어 2026 요청과 불일치했다. 을지대 PDF는 Playwright API 요청에서 `Parse Error: Invalid header token`이 발생했다. 화성의과학대는 `this._engines.set is not a function` 사이트 스크립트 충돌로 링크 평가가 실패했다. 이들 오류를 검증 통과로 대체하지 않았다.

## 성균관대 73명 재확인

원본 PDF SHA-256: `91e5561ee528a519eecf9f2b849238cfeb7202c534a5e0c2b10b68352296fd96`. 이전 보고와 동일하다. 1차 실행에서는 같은 PDF를 최초 추출 + 2회 재추출해 **세 번 모두 학사 56명 / 기재 총계 73명**으로 불일치했다(아래 표). 같은 값이 매번 반복됐으므로 원인은 비결정성이 아니라 **기본 모델 `gemini-3.1-flash-lite`와 프롬프트 회귀**(T6에서 `ed5984e:crawler/prototype/extract.py`의 PROMPT를 복원)였다. T6 2차에서 `gemini-3.8-flash`와 복원한 프롬프트로 같은 PDF를 추출해 **학사 73/73 통과**했다(`artifacts/t6-chain/skku-2026.json`). 아래 표는 1차 lite 실행의 실제 수치이며 그대로 보존한다. 인원을 수동 보정하지 않았다.

| 실행 | 시도 | 학사 합 | 기재 총계 | 결과 |
| --- | ---: | ---: | ---: | --- |
| 사전 점검 | 1 | 56 | 73 | 불일치 |
| 사전 점검 | 2 | 56 | 73 | 불일치 |
| 사전 점검 | 3 | 56 | 73 | 불일치 |
| 전체 수집(최종 재사용) | 1 | 56 | 73 | 불일치 |
| 전체 수집(최종 재사용) | 2 | 56 | 73 | 불일치 |
| 전체 수집(최종 재사용) | 3 | — | — | HTTPStatusError (API 오류) |

## 재추출·보존 정책

- 합계 검증 실패 시 같은 PDF 바이트로 최대 2회 재추출한다(최초 포함 3회). 최초 통과 결과를 즉시 채택한다. 중간에 검증불가가 나와도 남은 재시도에서 통과를 확인한다. 최초 결과가 검증불가면 불필요한 재추출은 하지 않는다.
- 통과가 없으면 최초의 유효한 추출 결과를 유지한다. 후속 API 오류·학교/학년도 오류가 기존 불일치 데이터를 덮어쓰지 않는다. 모든 시도의 추출 데이터/검증 또는 오류 종류를 `extraction_attempts`에 저장한다.
- PDF.js 뷰어의 확장자 없는 주소, 학년도별 PDF 우선순위, 동적 프레임 교체와 JS 전용 PDF 다운로드 버튼을 지원한다. TLS 예외 허용 목록은 `admission.sogang.ac.kr`에 더해 가천대 공식 PDF 서버의 인증서 체인 실패를 확인한 `admission.gachon.ac.kr`만 이유 주석과 함께 추가했다. 전체 TLS 비활성화나 외부 리다이렉트로의 예외 전파는 없다.
- 공지 게시판과 고정 요강 메뉴 중 확인된 종류만 YAML에 넣었다. 미확인 메뉴 종류는 YAML 끝 주석에 기록했다.

## 산출물

`crawler/artifacts/summary.json`에는 최종 60개 항목의 상태가 들어 있다. 학교별 PDF와 JSON도 같은 폴더에 저장했다. JSON에는 v2 스키마, 원본 출처·SHA-256·수집 시각·모델·학년도, 추출 데이터, 검증 결과와 재추출 이력이 포함된다. 추출 자체가 실패한 학교는 학교별 JSON 대신 summary 오류와 수집된 PDF(있는 경우)로 확인한다. 모든 artifacts와 로컬 환경·키는 Git 제외 대상이며 PR에 포함하지 않았다. 범위 변경 전 신학/예술계 산출물과 진단 로그가 같은 로컬 폴더에 남아 있으므로 최종 `summary.json`에 나열된 항목만 이번 결과로 취급한다.

| 학교 | JSON (artifacts/ 기준) | 원본 SHA-256 |
| --- | --- | --- |
| 서강대학교 | `sogang-2026.json` | `48ce417d96eba8f67bc96b12524416b2db184d0e2d6c51187229fe2b28d724af` |
| 성균관대학교 | `skku-2026.json` | `91e5561ee528a519eecf9f2b849238cfeb7202c534a5e0c2b10b68352296fd96` |
| 연세대학교 (서울) | `yonsei-2026.json` | `713c65369713fe1b35c94412ebeb02dd28a6abb41e522669699066e408e202ce` |
| 가톨릭대학교 | `가톨릭대학교-2026.json` | `bc91566de7d2c06c4e10dd9207721e7c2b67ca414df1559755d4d474790364ed` |
| 강남대학교 | `강남대학교-2026.json` | `c8de0a782a1109e9af89d28d780ba485507cb8b4a4032ae692b3c3961bc8836e` |
| 건국대학교 (서울) | `건국대학교-서울-2026.json` | `976f49f932f5d1ed780c54947c9cfd65155bc420d14ea756cfb21e8c63f9c12d` |
| 경기대학교 | `경기대학교-2026.json` | `74de277406d666fe014693d3f4779d3fc04cf9e81bd7d3eb654dd275e0e5237f` |
| 경동대학교 (메트로폴) | `경동대학교-메트로폴-2026.json` | `ed92ced55b844c20eb6b1edcb34aff8a90e9088601e3450261333dafc0d1a952` |
| 경희대학교 | `경희대학교-2026.json` | `ea0849638ecd565a56a2486dfa3a2802f548fa6818c58a3f5a150948506f7c57` |
| 고려대학교 (서울) | `고려대학교-서울-2026.json` | `7f5634bd8be0e0274b0306c3dd9acb574901cc890a82e2af5c79c3ec8cf36acd` |
| 광운대학교 | `광운대학교-2026.json` | `4ad6045d4c1a8ab7a4ddbad053ba7bb1c2679b78e3144942575950a70e6cffb9` |
| 국민대학교 | `국민대학교-2026.json` | `366c9731d838215c1841c9d0a27278385c1dc26eff7f9a186efc5c810282db8f` |
| 단국대학교 (죽전) | `단국대학교-죽전-2026.json` | `8583f8a5fe381aa64ec08c1818506c88c8901643c8c9c5e891f561fdd118b9c7` |
| 대진대학교 | `대진대학교-2026.json` | `19d436c09aa13f194d794602db15681182f1153f29fa8861467c61a6f902c297` |
| 덕성여자대학교 | `덕성여자대학교-2026.json` | `ccb5616c2910ae24008df9964d9dae1cc5c4a4e9106e9f8c8f38ce84288b9930` |
| 동국대학교 | `동국대학교-2026.json` | `bee63d238dac9eb7b831c611e8ea7d1eb763f3b41c06e2e55b12c37919da4acf` |
| 동덕여자대학교 | `동덕여자대학교-2026.json` | `e99afca4570269f606298cc6138b34a5cb74fde874fb6f6887981163dedbc3a9` |
| 명지대학교 | `명지대학교-2026.json` | `c101b2e04ccd17a8e31ee6b87ea49c7b2d1d0a0ab7b425a79281895c70f8b144` |
| 삼육대학교 | `삼육대학교-2026.json` | `7efa1d79e82b5dfaec11544de21f0feb82f0cfc65e21978c54437d98145abc55` |
| 상명대학교 (서울) | `상명대학교-서울-2026.json` | `2f5a2921a6c75c555dfb931136e2f2d40314061be129d5eaef0e8e4a6ffe047a` |
| 서경대학교 | `서경대학교-2026.json` | `84569ffc310119118a888c5acf9430d1dae2e5ef866d7f137454e3fd7b1b8e22` |
| 서울과학기술대학교 | `서울과학기술대학교-2026.json` | `2c877ccc018cc58f1ed2e22799fb3994f9a257595f0aff2acee7c5dae7782929` |
| 서울대학교 | `서울대학교-2026.json` | `3d6d58f15b32281513ecd59307e79684dd85ed1291db88090e58d83b02955084` |
| 서울시립대학교 | `서울시립대학교-2026.json` | `26d8953337b60f4ab2013f96fa0f4d6300d19dbf5a1892a9e210ae867ba81dc8` |
| 서울여자대학교 | `서울여자대학교-2026.json` | `a7186b7b0b34590c37f26ce0553637b1c852f7f7fc85121fe09075431c853042` |
| 성결대학교 | `성결대학교-2026.json` | `9915f3a6d2a210fe334c533dfcc0f8044b9bf8d40809639cf6ddebfeb9a9338b` |
| 성공회대학교 | `성공회대학교-2026.json` | `5969dd9548d8f8aa4864c68a00f2a845fbc6e6563c48f1d5a4052d41475e05c2` |
| 성신여자대학교 | `성신여자대학교-2026.json` | `cd7642d434e6b57935c3d6d3fa01655769d185ac412f01ce99a10931573f9ea0` |
| 세종대학교 | `세종대학교-2026.json` | `e8750670630612c0dbb772f4b07ccf1c24ec90150c96deca94f6aafa4a2f02cf` |
| 수원대학교 | `수원대학교-2026.json` | `4a42c0c116cf9a8e7454ea878747fd31c83d686cc0968cfe4e3877ee5f9bf73f` |
| 숙명여자대학교 | `숙명여자대학교-2026.json` | `c6c9e771c1f9f8e996f65b143f5f1bdf2c737d554cf91ea0143715b1552bc391` |
| 숭실대학교 | `숭실대학교-2026.json` | `ef3a5b2519a712477fe4077ab39d5baa3197b3d78d8b06cf17400df4177079d6` |
| 신한대학교 | `신한대학교-2026.json` | `63c7adae5bac60ea8afdc45db4183eb8bda4736250690856a67ffdb70860f23a` |
| 아주대학교 | `아주대학교-2026.json` | `35da2d94c9de6755d7cd2dd553a2fa758ab70f191d7532555bf72e6499bb05e6` |
| 안양대학교 | `안양대학교-2026.json` | `ced649d4d9276759f903f658352a4935950e6912a0c37569a6f2265b725072a0` |
| 용인대학교 | `용인대학교-2026.json` | `470b0e39b8ca7bca8ec80ec81d46258736db94c005d24418f22f6c14bd480278` |
| 이화여자대학교 | `이화여자대학교-2026.json` | `a5a9d443031ed175a97b9bd65632d2e1540741405a5e4391ffc50409489cbb0a` |
| 인천가톨릭대학교 | `인천가톨릭대학교-2026.json` | `69f23b7aea3c6e22eb571771d64ea9e74612d039773fcbad2f86ed24dc3ccb3a` |
| 인천대학교 | `인천대학교-2026.json` | `993de534dde432e52aa083a26bd806516db70db1a6be47c308560a60aeb028ed` |
| 인하대학교 | `인하대학교-2026.json` | `0a4cd5e905763b97bbe3d1ec24f661412d54d4e9736da43ac62d28b07a2d36cd` |
| 차의과학대학교 | `차의과학대학교-2026.json` | `f2a1a5cef3374d0bff06cf8b8cc42c82961989019789c02ecd740bdaa85a27ca` |
| 청운대학교 (인천) | `청운대학교-인천-2026.json` | `99fca04716a6f810b9bb34d42a44c04fa05d789c5f6842d7d1d86841378b5d32` |
| 평택대학교 | `평택대학교-2026.json` | `920bf143bb6a04724b0f1e0371a79ba815632a1e6dfa35d246419152270d7d27` |
| 한경국립대학교 | `한경국립대학교-2026.json` | `f158e8a0f600571760c5929105585f31dfca2ebcb11f99677b23c8a45390b047` |
| 한국외국어대학교 | `한국외국어대학교-2026.json` | `843b2ebe6687a01093852fbe4a6db75a1b8b9715fdcf0150fef3f53a499a92cc` |
| 한국항공대학교 | `한국항공대학교-2026.json` | `64be85db839b2b79fdc2945a91d6ecd586befb4d360ca959e985cc49aa32b511` |
| 한성대학교 | `한성대학교-2026.json` | `900bf5192507b8b90f471a89d655417c22da7e479bf16c5b977b44a0bf17edc4` |
| 한세대학교 | `한세대학교-2026.json` | `3df37afb3df12250cd03fbc14dffa7d0271b9547b5afec4126e859e11b67a5dd` |
| 한신대학교 | `한신대학교-2026.json` | `63f3b8dc78c6172c44afe0233f106a4c49f003c80041c0886ff6ee71bad8f39f` |
| 한양대학교 (서울) | `한양대학교-서울-2026.json` | `ff64ee14eb008ee21dd8671f0cf6972f42def90f2be6d8b5d54bf7ea8477e57b` |
| 한양대학교 (ERICA) | `한양대학교-ERICA-2026.json` | `90c70ad427f4d6462921ec09ea492c51833b85cd7787e105ea9e3b633bcfdbe4` |
| 협성대학교 | `협성대학교-2026.json` | `8b28a4316ac563719f74e73091ef2a02c247761745e7da782eb684effda954a5` |
| 국립한국교통대학교 (의왕) | `국립한국교통대학교-의왕-2026.json` | `fe9aa1a3a21d4a43b2bac6384290aae79a4738d6403be7c05e68084b2eb3591f` |

## 검증

```sh
cd crawler
.venv/bin/pytest -m ''
.venv/bin/ruff check src tests
git diff --check
git check-ignore artifacts/summary.json .venv/bin/python
```

- 실제 대학 다운로드 3건을 포함한 pytest **80개 통과**.
- 최종 summary 60개 항목과 53개 학교 JSON/PDF의 SHA-256·검증 결과 일치를 프로그램으로 재확인했다.
- Ruff, diff 공백 검사 통과. 결과 JSON·가상환경의 ignore 적용 확인.
- 재추출 조기 통과·최대 횟수·검증불가 처리·후속 오류 보존·동일 PDF 사용·JSON 이력 보존, YAML 파싱·잘못된 입력 거부, 출처 fallback, 재사용 무결성, 학교명 공백 정규화(원문은 보존), 429 단기/일일 제한 정책을 테스트했다.
- DB 연결·저장·마이그레이션은 실행하지 않았다. 불일치·검증불가 데이터는 원문 검토 후 다음 작업으로 넘겨야 한다.
