# 모집요강 감시 루프 서버 배포 구조

> 실제 배포는 사용자 승인 후 진행한다. 이 문서는 구조와 절차만 설명한다.

Mac이 꺼져 있어도 감시가 계속되도록, 상시 켜진 Linux 서버(작은 VM 1대면 충분)에서
`crawler` 컨테이너를 `restart: unless-stopped`로 실행한다.

```
[서버]
  crawler 컨테이너 (python -m pyeonit_crawler.monitor, 1분 주기)
    ├─ Playwright Chromium → 대학 공지 게시판 / 모집요강 페이지
    ├─ Gemini REST (새 요강·변경 PDF일 때만)
    ├─ PDF 원본 → pdfs 볼륨 (/data/pdfs, storage_key = <sha256>.pdf)
    └─ Postgres (crawl_sources·crawl_seen_items·crawl_runs·admission_documents·extraction_runs)
  backend (Spring Boot) → 기동 시 Flyway로 V1·V2 적용
```

## 동작

- 1분마다 `enabled AND next_check_at <= now()` 인 소스만 확인한다. 확인할 소스가 없으면 브라우저도 띄우지 않는다.
- 주기: `TARGET_ACADEMIC_YEAR` 요강이 EXTRACTED·NEEDS_REVIEW·APPROVED로 저장된 대학은 1일, 아니면 10분.
- NOTICE_BOARD: 게시글 URL을 `crawl_seen_items`와 비교해 신규만 본다. 제목에 편입·모집·요강이
  모두 있고 다른 학년도만 적힌 글이 아니면 PDF를 받는다.
- GUIDE_PAGE: PDF sha256을 `crawl_sources.content_hash`와 비교해 바뀐 경우만 분석한다.
- 같은 sha256이 `admission_documents`에 있으면 Gemini를 호출하지 않는다.
- 합계 검증 통과 시 EXTRACTED, 아니면 NEEDS_REVIEW. 학교·학년도가 다르면 FAILED로 남긴다.
- 실패 시 `crawl_runs`에 기록하고 1·2·4분 뒤 최대 3회 재시도한 뒤 정상 주기로 돌아간다.
  `consecutive_failures`는 성공할 때까지 누적된다. Gemini 추출이 재시도까지 모두 실패하면
  해당 PDF를 FAILED로 기록해 반복 호출을 멈춘다(행을 지우면 재분석).

## 절차

1. 서버에 Docker와 이 저장소를 준비한다.
2. 루트 `.env`(커밋 금지)에 `GEMINI_API_KEY`, 필요 시 `DATABASE_URL`(관리형 DB 사용 시), `TARGET_ACADEMIC_YEAR`를 둔다.
3. DB 스키마는 backend 기동 시 Flyway가 적용한다. backend 없이 적용하려면:
   `docker run --rm --network <compose 네트워크> -v $PWD/backend/src/main/resources/db/migration:/flyway/sql:ro flyway/flyway:11 -url=jdbc:postgresql://postgres:5432/pyeonit -user=pyeonit -password=<비밀번호> migrate`
4. 대학 목록 시드(1회, 목록 변경 시 재실행해도 upsert):
   `docker compose --profile monitor run --rm crawler python -m pyeonit_crawler.monitor --seed /app/data/universities.yaml --once`
5. 상시 실행: `docker compose --profile monitor up -d crawler`, 로그: `docker compose logs -f crawler`.

## 운영 확인 SQL

```sql
SELECT u.name, s.kind, s.next_check_at, s.consecutive_failures, s.last_error
FROM crawl_sources s JOIN universities u ON u.id = s.university_id ORDER BY s.consecutive_failures DESC;
SELECT * FROM crawl_runs ORDER BY id DESC LIMIT 50;
```

## 제한

- 단일 인스턴스 전제(소스 잠금 없음). 여러 개를 띄우면 같은 소스를 중복 확인한다.
- 게시글 식별은 링크 URL(fragment 제외) 기준. JS onclick으로만 여는 게시판은 글을 찾지 못한다.
- PDF 원본은 로컬 볼륨에 보관한다. R2 연동 시 업로드 후 `storage_key`를 교체한다.
- 운영 Postgres 비밀번호는 compose 기본값(pyeonit) 대신 별도 값으로 바꾼다.

## 로컬 실행·테스트

```sh
cd crawler && .venv/bin/python -m pip install -e '.[test]'
docker compose up -d postgres   # tests/monitor는 Postgres 임시 스키마에 V1·V2를 적용해 실행 (없으면 skip)
.venv/bin/pytest                # 다른 주소는 TEST_DATABASE_URL
DATABASE_URL=postgresql://pyeonit:pyeonit@localhost:5434/pyeonit .venv/bin/python -m pyeonit_crawler.monitor --once
```
