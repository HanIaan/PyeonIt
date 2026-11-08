# T8 일일 backfill 및 통과분 DB 적재

실행일: 2026-11-08 KST. 대상 44개교(서울 29·경기 13·인천 2).

## 코드·검증

- WIP `fd53eb4`에서 `feat/backfill-daily`를 만들고 `origin/main` 위로 rebase했다.
- 같은 PDF SHA-256의 **gemini-3.8-flash 합계 검증 통과**만 건너뛴다. ① 미추출·실패 ② 추출했으나 3.8 미통과 ③ lite로만 통과 순이다.
- 첫 추출은 학교당 generateContent **1요청**만 보낸다(503·타임아웃·토큰 잘림도 첫 단계 재시도 없음). 불일치 재추출 최대 2회는 ①② 첫 추출 뒤 남은 예산으로 수행하고, ③은 그 뒤다.
- 예산은 `artifacts/quota-state.json`에 태평양 날짜별 누적한다. 재시도·체인 이동 포함 전송 직전에 집계한다. **모든 429는 즉시 당일 전체 중단**, 체인 이동도 하지 않으며 같은 날 재실행도 차단한다. 남은 학교는 QUOTA_WAIT, 종료 코드 0.
- `load`는 `adopted=true`·`validation.passed=true`인 2026 결과만 적재한다. 검증 재계산, 학교·학년도·PDF SHA-256을 확인한다. 학교가 없으면 `monitor.seed.load_seed`를 import해 시드한다. 문서는 EXTRACTED, 추출 이력에는 실제 모델·결과·검증을 저장하며 해시 UNIQUE로 멱등 처리한다.
- 전체 pytest 및 Postgres 임시 스키마 테스트: **145 passed, 3 deselected**. monitor·backend·web 등 금지 범위는 변경하지 않았다.
- `SCHEDULE_PROPOSAL.md`와 `scripts/daily-backfill.sh`만 준비했다. launchctl·crontab 등록·실행 없음.

## 출처·품질 정정

| 학교 | 문제와 조치 |
| --- | --- |
| 가천대학교 | 기존 `guide1.asp`는 2027 자료. 공식 2026 공지 `noticeView.asp?BOARD_IDX=27525`를 첫 출처로 반영. 35쪽 PDF 표지·모집인원 확인, SHA-256 `6ab6c93deeff21181b138cdab36ce13d0cb365fb8c4dc617b66bdc02b3abc364`. 출처 확인에는 Gemini 미사용. |
| 한국공학대학교 | 면접 유의사항 PDF 대신 공식 2026 모집요강 상세 글 `guide.htm?...mode=view&bltn_seq=13025`를 첫 출처로 반영. 44쪽 요강 SHA-256 `f370126d9335dc860195e9f25c2ef2e7b9d64d964f3ef80fd30d3398b1a27359`. 출처 확인에는 Gemini 미사용. |
| 성균관대학교 | 같은 SHA-256 `91e5561ee528a519eecf9f2b849238cfeb7202c534a5e0c2b10b68352296fd96`에서 1차 세 번 모두 학사 **56/73**. 원인은 비결정성이 아니라 **lite 모델(`gemini-3.1-flash-lite`)+프롬프트 회귀**였다. 복원한 프롬프트와 3.8로 **73/73 통과**했으며 수동 숫자 보정 없음. |

## 오늘 실행

PR: [#21](https://github.com/HanIaan/PyeonIt/pull/21), base=main. 코드 커밋·push·PR 생성 후 실행했다.

```sh
cd crawler
.venv/bin/pyeonit-crawler backfill --year 2026 --daily-request-limit 20 --model-chain gemini-3.8-flash --output artifacts/t6-chain --env-file .env
.venv/bin/pyeonit-crawler backfill --next-run
.venv/bin/pyeonit-crawler backfill --output artifacts/t6-chain --quota-report
```

- 결과: **실제 전송 2회**(가천 200·합계 검증 통과 → 경기 429), **종료 코드 0**. 429 후 재시도·체인 이동·다른 학교 요청 없음. 서강·성균관은 재사용했다.
- 429 수신: 2026-11-08 **11:08:58.601 KST**, `GenerateRequestsPerDayPerProjectPerModel-FreeTier=20`, `quotaDimensions.model=gemini-3.8-flash`, `retryDelay=21061s`.
- 응답 기준 리셋: **16:59:59.601 KST**, 태평양 자정 17:00과 반올림 **0초 차이**. 다음 권장 실행(`--next-run`): **2026-11-08 17:05 KST / 08:05 UTC**. 같은 날 재실행은 저장된 `blocked_day`로 Gemini 전송을 차단한다.
- 남은 학교의 운영 상태는 QUOTA_WAIT 41·불일치 0·검증불가 0. 보존된 이전 진단 데이터에는 **불일치 2(건국·인천), 검증불가 1(연세)**이 있으며 아래 사유에 함께 표시했다(41에 포함, 중복 합산 금지). 가톨릭·강남은 이전 3.6 통과·채택분이며 3.8 완료 수에는 포함하지 않는다.
- 출처 수정 후 받은 가천 PDF 해시는 위 2026 요강 해시와 일치했다. 한국공학은 오늘 예산 중단으로 새 PDF 추출을 수행하지 않았다.

모델 체인 한도 근거는 호출을 추가하지 않고 `--quota-report`로 수집했다. 아래는 이번 실행 전 보존한 T6 기록이다. 3.7의 429 차원이 3.8, 3.5의 차원이 3.6이라는 공유 가능성 근거만 남기며 이번 실행은 3.8 단일이다.

| 요청 모델 | 200 | 429 | 기타 | 429 quotaDimensions.model | quotaId=quotaValue | retryDelay | 직전 모델 429 후 이 모델 응답 |
| --- | ---: | ---: | ---: | --- | --- | --- | --- |
| gemini-3.8-flash | 2 | 3 | 0 | gemini-3.8-flash | GenerateRequestsPerDayPerProjectPerModel-FreeTier=20 | 54457s, 54792s | — |
| gemini-3.7-flash | 0 | 3 | 0 | gemini-3.8-flash | GenerateRequestsPerDayPerProjectPerModel-FreeTier=20 | 54457s, 54791s | gemini-3.8-flash 429 후 429×3 |
| gemini-3.6-flash | 5 | 3 | 0 | gemini-3.6-flash | GenerateRequestsPerDayPerProjectPerModel-FreeTier=20 | 54283s, 54357s, 54456s | gemini-3.7-flash 429 후 200×5, gemini-3.7-flash 429 후 429×3 |
| gemini-3.5-flash | 3 | 1 | 0 | gemini-3.6-flash | GenerateRequestsPerDayPerProjectPerModel-FreeTier=20 | 54358s | gemini-3.6-flash 429 후 200×1 |

## 학교별 상태

3.8 완료 **3개교**, 남음 **41개교**. 상태: 통과 3·QUOTA_WAIT 41·불일치 0·검증불가 0·요강없음 0·실패 0.

| 학교 | 상태 | 사유 |
| --- | --- | --- |
| 서강대학교 | 통과 | gemini-3.8-flash, 동일 PDF 합계 검증 통과 |
| 성균관대학교 | 통과 | gemini-3.8-flash, 동일 PDF 합계 검증 통과 |
| 연세대학교 (서울) | QUOTA_WAIT | 429 이후 당일 중단; 이전 검증불가 |
| 가천대학교 | 통과 | gemini-3.8-flash, 동일 PDF 합계 검증 통과 |
| 가톨릭대학교 | QUOTA_WAIT | 429 이후 당일 중단; 이전 gemini-3.6-flash 통과·채택 |
| 강남대학교 | QUOTA_WAIT | 429 이후 당일 중단; 이전 gemini-3.6-flash 통과·채택 |
| 건국대학교 (서울) | QUOTA_WAIT | 429 이후 당일 중단; 이전 불일치 |
| 경기대학교 | QUOTA_WAIT | Gemini 일일 한도 429 |
| 경희대학교 | QUOTA_WAIT | 429 이후 당일 중단 |
| 고려대학교 (서울) | QUOTA_WAIT | 429 이후 당일 중단 |
| 광운대학교 | QUOTA_WAIT | 429 이후 당일 중단 |
| 국민대학교 | QUOTA_WAIT | 429 이후 당일 중단 |
| 단국대학교 (죽전) | QUOTA_WAIT | 429 이후 당일 중단 |
| 덕성여자대학교 | QUOTA_WAIT | 429 이후 당일 중단 |
| 동국대학교 | QUOTA_WAIT | 429 이후 당일 중단 |
| 동덕여자대학교 | QUOTA_WAIT | 429 이후 당일 중단 |
| 명지대학교 | QUOTA_WAIT | 429 이후 당일 중단 |
| 삼육대학교 | QUOTA_WAIT | 429 이후 당일 중단 |
| 상명대학교 (서울) | QUOTA_WAIT | 429 이후 당일 중단 |
| 서경대학교 | QUOTA_WAIT | 429 이후 당일 중단 |
| 서울과학기술대학교 | QUOTA_WAIT | 429 이후 당일 중단 |
| 서울대학교 | QUOTA_WAIT | 429 이후 당일 중단 |
| 서울시립대학교 | QUOTA_WAIT | 429 이후 당일 중단 |
| 서울여자대학교 | QUOTA_WAIT | 429 이후 당일 중단 |
| 성신여자대학교 | QUOTA_WAIT | 429 이후 당일 중단 |
| 세종대학교 | QUOTA_WAIT | 429 이후 당일 중단 |
| 수원대학교 | QUOTA_WAIT | 429 이후 당일 중단 |
| 숙명여자대학교 | QUOTA_WAIT | 429 이후 당일 중단 |
| 숭실대학교 | QUOTA_WAIT | 429 이후 당일 중단 |
| 아주대학교 | QUOTA_WAIT | 429 이후 당일 중단 |
| 용인대학교 | QUOTA_WAIT | 429 이후 당일 중단 |
| 이화여자대학교 | QUOTA_WAIT | 429 이후 당일 중단 |
| 인천대학교 | QUOTA_WAIT | 429 이후 당일 중단; 이전 불일치 |
| 인하대학교 | QUOTA_WAIT | 429 이후 당일 중단 |
| 중앙대학교 | QUOTA_WAIT | 429 이후 당일 중단 |
| 한국공학대학교 | QUOTA_WAIT | 429 이후 당일 중단; 2026 출처 수정 완료, 새 PDF 추출 대기 |
| 한국외국어대학교 | QUOTA_WAIT | 429 이후 당일 중단 |
| 한국항공대학교 | QUOTA_WAIT | 429 이후 당일 중단 |
| 한성대학교 | QUOTA_WAIT | 429 이후 당일 중단 |
| 한세대학교 | QUOTA_WAIT | 429 이후 당일 중단 |
| 한신대학교 | QUOTA_WAIT | 429 이후 당일 중단 |
| 한양대학교 (서울) | QUOTA_WAIT | 429 이후 당일 중단 |
| 한양대학교 (ERICA) | QUOTA_WAIT | 429 이후 당일 중단 |
| 홍익대학교 (서울) | QUOTA_WAIT | 429 이후 당일 중단 |

## 통합 개발 DB·API

대상: `localhost:5434/pyeonit_dev`, 백엔드 `http://localhost:8081`(dev). 서버 중지·재시작, 스케줄 등록, 브랜치 E2E 없음.

```sh
.venv/bin/pyeonit-crawler load --input artifacts/t6-chain --storage-dir artifacts/pdfs --schedule-corrections scripts/schedule-corrections-2026.json --database-url postgresql://pyeonit:pyeonit@localhost:5434/pyeonit_dev
```

| 항목 | 적재 전 | 적재·승인 후 |
| --- | ---: | ---: |
| universities | 2 | 44 |
| admission_documents | 2(기존 APPROVED) | 5(모두 APPROVED) |
| extraction_runs | 0 | 3(모두 validation.passed=true) |

- 신규 적재 3건: 가천(id=9, 3.8), 가톨릭(id=5, 3.6), 강남(id=6, 3.6). 모두 EXTRACTED로 저장 후 ADMIN API로 승인했다. 서강·성균관은 같은 SHA-256의 기존 APPROVED 문서여서 중복 적재·재승인하지 않았다.
- 일정 종료일에 모델 설명 접미문이 섞여 최초 승인은 422 `EXTRACTION_UNUSABLE`이었다. PDF를 텍스트·렌더링으로 대조해 가천 PDF 5쪽 `2025-12-04 18:00`, 가톨릭 3쪽 `2025-12-09 18:00`, 강남 4쪽 `2026-02-18 11:00`과 일치함을 확인했다. `scripts/schedule-corrections-2026.json`에 **PDF SHA-256·수정 전 문자열·근거 페이지**를 명시했다. 숫자·날짜의 의미를 바꾸지 않고 설명 접미문만 제거했다.
- `load --schedule-corrections`는 명시한 원문이 정확히 일치할 때만 적용하고, `result._schedule_corrections`에 원문과 근거를 보존한다. 기존 EXTRACTED 문서의 같은 모델·검증·원본 결과인 이력만 갱신한다(3건). APPROVED 문서와 원본 artifacts는 변경하지 않는다. 해당 보정 없이도 일반 `load`는 원본 결과를 그대로 저장한다.
- 최종 재실행: **신규 0·갱신 0·건너뜀 44**(해시 중복 5, 미채택/검증 미통과 39). 요강·추출 이력 중복 없음. PDF 5개는 `artifacts/pdfs/<sha256>.pdf`에 저장했다.
- curl로 `GET /api/csrf` → `POST /api/dev/login {nickname,role:ADMIN}`(X-XSRF-TOKEN) → `GET /api/admin/admission-documents` → 해당 신규 2026 ID의 `POST /{id}/approve {extractionRunId}`를 수행했다. 최종 로그인·목록·승인 **전부 HTTP 200**, 승인 결과 APPROVED.

| 공개 API 검증 | 결과 |
| --- | --- |
| GET /api/universities | HTTP 200, 44개교 |
| GET /api/universities/{id}?academicYear=2026 | 아래 5개교 모두 HTTP 200, 2026 요강·TO·일정 노출 |
| GET /api/notices?academicYear=2026 | HTTP 200, 승인된 5개교만 노출, 각 원서접수 시작·종료 시각 존재 |

| 학교 | 문서 ID | 공개 TO 셀 | 일정 수 | 상태 |
| --- | ---: | ---: | ---: | --- |
| 서강대학교 | 1 | 53 | 11 | 기존 APPROVED 유지 |
| 성균관대학교 | 2 | 231 | 12 | 기존 APPROVED 유지 |
| 가천대학교 | 9 | 288 | 1 | 신규 APPROVED |
| 가톨릭대학교 | 5 | 202 | 1 | 신규 APPROVED |
| 강남대학교 | 6 | 36 | 1 | 신규 APPROVED |

기존 환경 참고: 서강 53셀 중 1셀은 기존 `Q1b 범위 검증 전형` 테스트 데이터다(실제 추출 52셀). 이번 적재 이전부터 있던 값으로 변경하지 않았으며 T7/통합 환경 정리 시 제거 여부를 확인해야 한다. 신규 3문서에는 검증 미통과 데이터가 없다.

실행 증거(전부 gitignore): `artifacts/t8-daily.log`, `quota-state.json`, `t8-load*.json`, `t8-api-final.log`, `t8-integration/*.json`, `t8-db-final.json`. 키·쿠키·CSRF 값은 보고서·커밋에 포함하지 않았다.
