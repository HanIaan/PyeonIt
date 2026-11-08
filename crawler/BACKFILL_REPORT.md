# T8 일일 backfill 및 통과분 DB 적재

실행일: 2026-11-08 KST. 대상 44개교(서울 29·경기 13·인천 2).

## 코드·검증

- WIP `fd53eb4`에서 `feat/backfill-daily`를 만들고 `origin/main` 위로 rebase했다.
- 같은 PDF SHA-256의 **gemini-3.8-flash 합계 검증 통과**만 건너뛴다. ① 미추출·실패 ② 추출했으나 3.8 미통과 ③ lite로만 통과 순이다.
- 첫 추출은 학교당 generateContent **1요청**만 보낸다(503·타임아웃·토큰 잘림도 첫 단계 재시도 없음). 불일치 재추출 최대 2회는 ①② 첫 추출 뒤 남은 예산으로 수행하고, ③은 그 뒤다.
- 예산은 `artifacts/quota-state.json`에 태평양 날짜별 누적한다. 재시도·체인 이동 포함 전송 직전에 집계한다. **모든 429는 즉시 당일 전체 중단**, 체인 이동도 하지 않으며 같은 날 재실행도 차단한다. 남은 학교는 QUOTA_WAIT, 종료 코드 0.
- `load`는 `adopted=true`·`validation.passed=true`인 2026 결과만 적재한다. 검증 재계산, 학교·학년도·PDF SHA-256을 확인한다. 학교가 없으면 `monitor.seed.load_seed`를 import해 시드한다. 문서는 EXTRACTED, 추출 이력에는 실제 모델·결과·검증을 저장하며 해시 UNIQUE로 멱등 처리한다.
- 전체 pytest 및 Postgres 임시 스키마 테스트: **143 passed, 3 deselected**. monitor·backend·web 등 금지 범위는 변경하지 않았다.
- `SCHEDULE_PROPOSAL.md`와 `scripts/daily-backfill.sh`만 준비했다. launchctl·crontab 등록·실행 없음.

## 출처·품질 정정

| 학교 | 문제와 조치 |
| --- | --- |
| 가천대학교 | 기존 `guide1.asp`는 2027 자료. 공식 2026 공지 `noticeView.asp?BOARD_IDX=27525`를 첫 출처로 반영. 35쪽 PDF 표지·모집인원 확인, SHA-256 `6ab6c93deeff21181b138cdab36ce13d0cb365fb8c4dc617b66bdc02b3abc364`. 출처 확인에는 Gemini 미사용. |
| 한국공학대학교 | 면접 유의사항 PDF 대신 공식 2026 모집요강 상세 글 `guide.htm?...mode=view&bltn_seq=13025`를 첫 출처로 반영. 44쪽 요강 SHA-256 `f370126d9335dc860195e9f25c2ef2e7b9d64d964f3ef80fd30d3398b1a27359`. 출처 확인에는 Gemini 미사용. |
| 성균관대학교 | 같은 SHA-256 `91e5561ee528a519eecf9f2b849238cfeb7202c534a5e0c2b10b68352296fd96`에서 1차 세 번 모두 학사 **56/73**. 원인은 비결정성이 아니라 **lite 모델(`gemini-3.1-flash-lite`)+프롬프트 회귀**였다. 복원한 프롬프트와 3.8로 **73/73 통과**했으며 수동 숫자 보정 없음. |

## 오늘 실행

CODE_PR_PENDING

## 학교별 상태

3.8 완료 **2개교**, 남음 **42개교**. 상태 집계: passed 4, unverifiable 1, error 1, failed 1, QUOTA_WAIT 37.

| 학교 | 상태 | 사유 |
| --- | --- | --- |
| 서강대학교 | 통과 | gemini-3.8-flash, 동일 PDF 합계 검증 통과 |
| 성균관대학교 | 통과 | gemini-3.8-flash, 동일 PDF 합계 검증 통과 |
| 연세대학교 (서울) | 검증불가 | 이전 결과 유지 |
| 가천대학교 | 실패 | 429(오늘 첫 요청); 올바른 2026 PDF로 교체 완료 |
| 가톨릭대학교 | 통과 | gemini-3.6-flash, 동일 PDF 합계 검증 통과 |
| 강남대학교 | 통과 | gemini-3.6-flash, 동일 PDF 합계 검증 통과 |
| 건국대학교 (서울) | 불일치 | 이전 결과 유지 |
| 경기대학교 | QUOTA_WAIT | Gemini 일일 한도 429 |
| 경희대학교 | QUOTA_WAIT | Gemini 일일 한도 429 |
| 고려대학교 (서울) | QUOTA_WAIT | Gemini 일일 한도 429 |
| 광운대학교 | QUOTA_WAIT | Gemini 일일 한도 429 |
| 국민대학교 | QUOTA_WAIT | Gemini 일일 한도 429 |
| 단국대학교 (죽전) | QUOTA_WAIT | Gemini 일일 한도 429 |
| 덕성여자대학교 | QUOTA_WAIT | Gemini 일일 한도 429 |
| 동국대학교 | QUOTA_WAIT | Gemini 일일 한도 429 |
| 동덕여자대학교 | QUOTA_WAIT | Gemini 일일 한도 429 |
| 명지대학교 | QUOTA_WAIT | Gemini 일일 한도 429 |
| 삼육대학교 | QUOTA_WAIT | Gemini 일일 한도 429 |
| 상명대학교 (서울) | QUOTA_WAIT | Gemini 일일 한도 429 |
| 서경대학교 | QUOTA_WAIT | Gemini 일일 한도 429 |
| 서울과학기술대학교 | QUOTA_WAIT | Gemini 일일 한도 429 |
| 서울대학교 | QUOTA_WAIT | Gemini 일일 한도 429 |
| 서울시립대학교 | QUOTA_WAIT | Gemini 일일 한도 429 |
| 서울여자대학교 | QUOTA_WAIT | Gemini 일일 한도 429 |
| 성신여자대학교 | QUOTA_WAIT | Gemini 일일 한도 429 |
| 세종대학교 | QUOTA_WAIT | Gemini 일일 한도 429 |
| 수원대학교 | QUOTA_WAIT | Gemini 일일 한도 429 |
| 숙명여자대학교 | QUOTA_WAIT | Gemini 일일 한도 429 |
| 숭실대학교 | QUOTA_WAIT | Gemini 일일 한도 429 |
| 아주대학교 | QUOTA_WAIT | Gemini 일일 한도 429 |
| 용인대학교 | QUOTA_WAIT | Gemini 일일 한도 429 |
| 이화여자대학교 | QUOTA_WAIT | Gemini 일일 한도 429 |
| 인천대학교 | QUOTA_WAIT | Gemini 일일 한도 429; 이전 불일치 |
| 인하대학교 | QUOTA_WAIT | Gemini 일일 한도 429 |
| 중앙대학교 | QUOTA_WAIT | Gemini 일일 한도 429 |
| 한국공학대학교 | QUOTA_WAIT | Gemini 일일 한도 429; 2026 출처 수정 완료, 새 PDF 추출 대기 |
| 한국외국어대학교 | QUOTA_WAIT | Gemini 일일 한도 429 |
| 한국항공대학교 | QUOTA_WAIT | Gemini 일일 한도 429 |
| 한성대학교 | QUOTA_WAIT | Gemini 일일 한도 429 |
| 한세대학교 | QUOTA_WAIT | Gemini 일일 한도 429 |
| 한신대학교 | QUOTA_WAIT | Gemini 일일 한도 429 |
| 한양대학교 (서울) | QUOTA_WAIT | Gemini 일일 한도 429 |
| 한양대학교 (ERICA) | QUOTA_WAIT | Gemini 일일 한도 429 |
| 홍익대학교 (서울) | QUOTA_WAIT | Gemini 일일 한도 429 |

## 통합 개발 DB·API

DB_PENDING
