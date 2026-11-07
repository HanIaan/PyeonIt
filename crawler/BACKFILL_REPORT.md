# 2026학년도 실제 backfill 실행 결과

실행일: 2026-11-08 (KST). Python 3.15.2, Chromium, Gemini REST `gemini-3.1-flash-lite`.

```sh
cd crawler
.venv/bin/python -m pyeonit_crawler backfill
```

종료 코드: 1. 불일치·검증 불가를 발견한 정상적인 검증 결과이며, 세 학교의 추출 JSON과 원본 PDF는 모두 저장됐다.

| 학교 | 검증 | 모집인원 행 | 일정 |
| --- | --- | ---: | ---: |
| 서강대학교 | passed | 26 | 11 |
| 성균관대학교 | failed | 45 | 8 |
| 연세대학교 | unverifiable | 74 | 9 |

## 세부 비교

| 학교 | 범위 | 전형 | 숫자 합 | 기재값 | 비숫자/누락 셀 | 결과 |
| --- | --- | --- | ---: | ---: | ---: | --- |
| 서강대학교 | 총계 | 일반 | 96 | 96 | 0 | passed |
| 서강대학교 | 총계 | 학사 | 31 | 31 | 0 | passed |
| 서강대학교 | 소계:인문 | 일반 | 53 | 53 | 0 | passed |
| 서강대학교 | 소계:인문 | 학사 | 19 | 19 | 0 | passed |
| 서강대학교 | 소계:자연 | 일반 | 43 | 43 | 0 | passed |
| 서강대학교 | 소계:자연 | 학사 | 12 | 12 | 0 | passed |
| 성균관대학교 | 총계 | 농어촌학생 | 21 | 21 | 0 | passed |
| 성균관대학교 | 총계 | 일반편입학 유형1 | 255 | 255 | 0 | passed |
| 성균관대학교 | 총계 | 일반편입학 유형2 | 78 | 78 | 0 | passed |
| 성균관대학교 | 총계 | 저소득층 | 5 | 5 | 0 | passed |
| 성균관대학교 | 총계 | 학사 편입학 | 56 | 73 | 0 | failed |
| 연세대학교 | 총계 | 군위탁편입학 | 0 | null | 62 | unverifiable |
| 연세대학교 | 총계 | 일반편입학 | 426 | 426 | 0 | passed |
| 연세대학교 | 총계 | 학사편입학 | 0 | null | 64 | unverifiable |

성균관 학사편입은 추출 합계 56명과 기재 총계 73명이 달라 원문 병합 셀 및 추출 누락 검토가 필요하다.
연세의 이내·약간명 등 미확정 인원은 null로 보존했다. 이 항목을 0으로 간주하거나 검증 통과로 처리하지 않는다.
불일치에 맞춰 원문 인원이나 총계를 임의 수정하지 않았다.

## 산출물 및 출처

아래 파일은 `crawler/artifacts/`에 저장되며 Git에서 제외된다. DB 작업은 JSON의 `data`, `source`, `validation`을 입력으로 사용하고 미통과 데이터를 검토해야 한다.

- `sogang-2026.json`, `sogang-2026.pdf`: https://admission.sogang.ac.kr/enter/html/transfer/guide.asp
  - sha256: `48ce417d96eba8f67bc96b12524416b2db184d0e2d6c51187229fe2b28d724af`
- `skku-2026.json`, `skku-2026.pdf`: https://admission.skku.edu/admission/html/transfer/guide.html
  - sha256: `91e5561ee528a519eecf9f2b849238cfeb7202c534a5e0c2b10b68352296fd96`
- `yonsei-2026.json`, `yonsei-2026.pdf`: https://admission.yonsei.ac.kr/seoul/admission/html/transfer/guide.asp
  - sha256: `713c65369713fe1b35c94412ebeb02dd28a6abb41e522669699066e408e202ce`

`summary.json`에는 마지막 실행의 세 학교 상태가 담겨 있다.

## 검증 및 운영 확인

- `.venv/bin/pytest`: 오프라인 44개 통과, 네트워크 3개 기본 제외.
- `.venv/bin/pytest -m ''`: 전체 47개 통과(실제 대학 사이트 다운로드 3개 포함).
- `.venv/bin/ruff check src tests`: 통과.
- `git diff --cached --check` 및 스테이징 대상 범위·실제 키 미포함 검사: 통과.

서강 PDF 서버의 인증서 체인 검증 실패를 확인했다. 예외는 `admission.sogang.ac.kr`만 허용하며, 외부 리다이렉트는 정상 검증한다.
이 허용 목록으로 실제 세 학교 다운로드 테스트와 backfill을 수행했다.
`gemini-2.5-flash`는 신규 사용자 이용 불가(404), `gemini-3.8-flash`는 503을 반환하여 응답 성공을 확인한 `gemini-3.1-flash-lite`를 사용했다.
