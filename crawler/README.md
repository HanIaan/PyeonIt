# 모집요강 수집기

Python 3.12 이상에서 실행하는 독립 패키지다. DB 저장은 하지 않는다.

```sh
cd crawler
python3 -m venv .venv
.venv/bin/python -m pip install -e '.[test]'
.venv/bin/python -m playwright install chromium
.venv/bin/python -m pyeonit_crawler backfill
```

`GEMINI_API_KEY` 환경변수를 우선 사용한다. 없으면 현재 작업 디렉터리 또는
패키지 소스에서 찾은 Git 워크스페이스 루트의 `.env`를 읽는다.
명시적인 파일은 `--env-file ../.env`로 지정한다. 키는 URL·로그·결과 JSON에 기록하지 않는다.

기본 대상은 서강·성균관·연세의 현재 게시된 2026학년도 편입 모집요강이다.
기본 Gemini 모델은 `gemini-3.1-flash-lite`이며 `--model`로 바꿀 수 있다.
실행 당시 `gemini-2.5-flash`는 신규 사용자 이용 불가(404)를 반환했다.

```sh
.venv/bin/python -m pyeonit_crawler backfill --schools sogang skku yonsei --year 2026
```

TLS 예외는 `download.py`의 `TLS_EXCEPTION_HOSTS`에 명시한
`admission.sogang.ac.kr`의 PDF 요청에만 적용한다(2026-11-08 인증서 체인 검증 실패 확인).
Chromium 컨텍스트·목록 밖 호스트·Gemini REST는 항상 정상 검증한다.
다운로드 리다이렉트는 자동 추적하지 않고 각 hop의 호스트를 다시 확인해 예외가 전파되지 않는다.
현재 요강의 학년도·학교가 요청과 다르면 오류로 남긴다.

## 결과 및 다음 작업 인터페이스

기본 출력 디렉터리는 `crawler/artifacts/`이며 Git에서 제외된다.
`--output <디렉터리>`로 변경할 수 있다.

- `<school>-2026.pdf`: 실제 내려받은 원본.
- `<school>-2026.json`: `schema_version`, `source`, `data`, `validation`.
- `summary.json`: 마지막 실행의 학교별 검증 상태 또는 오류 종류·HTTP 상태.

`source`는 학교·공지 URL·최종 PDF URL·sha256·로컬 PDF 파일명·UTC 수집시각·Gemini 모델을 담는다.
`data`는 프로토타입 v2 스키마의 한국어 필드(`학교`, `학년도`, `모집인원`, `소계`, `총계`, `일정`)를 유지한다.
인원 셀은 `전형`, `인원`(정수 또는 null), `원문`을 보존한다.
병합된 모집인원은 `단위=단과대학` 행으로 두고 학과에 임의 배분하지 않는다.

`validate_totals(data)`는 dict를 반환하며 원문을 변경하지 않는다.
학과 및 별도로 추출된 단과대학 행을 전형별로 합산하여 인쇄된 소계·총계와 비교한다.
단과대학 병합 행에 없는 전형 열은 학과 행에서 집계하므로 누락으로 간주하지 않는다.
검사마다 `범위`, `전형`, `합계`, `기재값`, `비숫자_또는_누락`, `status`를 기록한다.
`status`는 `passed`(모두 일치), `failed`(비교 가능한 항목 중 불일치 있음),
`unverifiable`(미확정 인원·누락 셀·기재 합계 없음) 중 하나다.
소계가 없는 문서는 총계만 검사하며, 알 수 없는 값을 0으로 간주하지 않는다.
`validation.passed`는 모든 검사가 통과했을 때만 true다.
DB 적재 작업은 이 상태를 보고 원문과 함께 검토할 수 있다.

CLI는 학교별 오류를 격리해 나머지 학교를 계속 처리한다.
불일치·검증 불가인 추출 결과도 저장한다. 모든 학교가 `passed`이면 종료 코드 0,
오류·불일치·검증 불가가 하나라도 있으면 1을 반환한다.
학교별 JSON은 임시 파일을 거쳐 교체하며, 실패한 학교의 이전 JSON은 덮어쓰지 않는다.
현재 실행 성공 여부는 `summary.json`을 기준으로 판단한다.

## 모듈 및 테스트

- `detection.py`: 제목에 편입·모집·요강을 모두 포함하고 기본계획·예고·시행계획은 제외.
- `download.py`: 공지 글/요강 페이지 및 iframe의 PDF 링크·PDF.js·다운로드 엔드포인트 탐색, PDF 시그니처 확인, sha256 계산.
- `gemini.py`, `schema.py`: REST JSON 추출. 503·타임아웃만 최초 요청 이후 최대 3회(1/2/4초) 재시도.
- `validation.py`: 인쇄된 소계·총계 비교.
- `cli.py`: 학교별 초기 수집 및 파일 저장.

```sh
.venv/bin/pytest                       # 외부 네트워크 없이 실행
.venv/bin/pytest -m network            # 실제 대학 사이트 PDF 다운로드
```

실제 Gemini 호출은 backfill로 검증한다. 단위 테스트는 mock REST로 성공·503·타임아웃·재시도 상한·즉시 실패를 확인한다.
