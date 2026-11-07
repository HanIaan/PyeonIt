"""감시 1회분 로직. 외부 I/O는 fetcher(links/download/extract/model)로 주입한다."""

import asyncio
import logging
import re
from datetime import UTC, datetime, timedelta
from pathlib import Path
from urllib.parse import urldefrag

from psycopg.types.json import Jsonb

from ..detection import is_admission_guide
from ..validation import validate_totals

log = logging.getLogger(__name__)

PENDING_INTERVAL = timedelta(minutes=10)  # 해당 학년도 요강 미수집 대학
COLLECTED_INTERVAL = timedelta(days=1)  # 수집 완료 대학: 정정 공지·PDF 교체만 확인
MAX_RETRIES = 3
COLLECTED_STATUSES = ["EXTRACTED", "NEEDS_REVIEW", "APPROVED"]


def title_matches(title: str, year: int) -> bool:
    # 제목에 다른 학년도만 적힌 글(지난 요강)은 PDF·Gemini 비용을 쓰지 않는다.
    years = re.findall(r"20\d\d", title)
    return is_admission_guide(title) and (not years or str(year) in years)


def due_sources(conn, now: datetime) -> list[dict]:
    return conn.execute(
        """SELECT s.id, s.kind, s.url, s.content_hash, s.consecutive_failures,
                  u.id AS university_id, u.name
           FROM crawl_sources s JOIN universities u ON u.id = s.university_id
           WHERE s.enabled AND s.next_check_at <= %s ORDER BY s.next_check_at""",
        (now,),
    ).fetchall()


def interval(conn, university_id: int, year: int) -> timedelta:
    collected = conn.execute(
        """SELECT EXISTS (SELECT 1 FROM admission_documents
           WHERE university_id = %s AND academic_year = %s AND status = ANY(%s))""",
        (university_id, year, COLLECTED_STATUSES),
    ).fetchone()["exists"]
    return COLLECTED_INTERVAL if collected else PENDING_INTERVAL


async def run_once(conn, fetcher, *, year: int, storage_dir: Path, now=None) -> int:
    now = now or datetime.now(UTC)
    sources = due_sources(conn, now)
    for source in sources:
        await check_source(conn, fetcher, source, year, storage_dir, now)
    return len(sources)


async def check_source(conn, fetcher, src, year, storage_dir, now) -> None:
    attempt = src["consecutive_failures"] + 1
    run_id = conn.execute(
        """INSERT INTO crawl_runs (source_id, started_at, status, attempt)
           VALUES (%s, %s, 'RUNNING', %s) RETURNING id""",
        (src["id"], now, attempt),
    ).fetchone()["id"]
    try:
        if src["kind"] == "GUIDE_PAGE":
            await _check_guide_page(conn, fetcher, src, year, storage_dir, attempt)
        else:
            await _check_notice_board(conn, fetcher, src, year, storage_dir, attempt)
    except Exception as exc:
        error = f"{type(exc).__name__}: {exc}"[:1000]
        log.warning(
            "%s %s 확인 실패 (시도 %d): %s", src["name"], src["kind"], attempt, error
        )
        # 1·2·4분 뒤 최대 3회 재시도. 이후에는 정상 주기로 돌아가고 연속 실패만 누적한다.
        if attempt <= MAX_RETRIES:
            delay = timedelta(minutes=2 ** (attempt - 1))
        else:
            delay = interval(conn, src["university_id"], year)
        conn.execute(
            """UPDATE crawl_sources SET last_checked_at = %s, consecutive_failures = %s,
               last_error = %s, next_check_at = %s WHERE id = %s""",
            (now, attempt, error, now + delay, src["id"]),
        )
        status = "FAILED"
    else:
        error = None
        conn.execute(
            """UPDATE crawl_sources SET last_checked_at = %s, last_success_at = %s,
               consecutive_failures = 0, last_error = NULL, next_check_at = %s
               WHERE id = %s""",
            (now, now, now + interval(conn, src["university_id"], year), src["id"]),
        )
        status = "SUCCESS"
    conn.execute(
        "UPDATE crawl_runs SET finished_at = now(), status = %s, error = %s WHERE id = %s",
        (status, error, run_id),
    )


async def _check_notice_board(conn, fetcher, src, year, storage_dir, attempt):
    # shortcut: 게시판 구조를 모르므로 제목 있는 모든 링크를 URL(fragment 제외)로 식별한다.
    # 세션성 쿼리가 붙는 게시판은 매번 신규로 보이므로 그때 학교별 item_key 규칙을 추가.
    for link in await fetcher.links(src["url"]):
        title = link.title.strip()
        key = urldefrag(link.url).url
        seen = conn.execute(
            "SELECT 1 FROM crawl_seen_items WHERE source_id = %s AND item_key = %s",
            (src["id"], key),
        ).fetchone()
        if not title or seen:
            continue
        matched = title_matches(title, year)
        if matched:
            try:
                pdf = await fetcher.download(link.url)
            except ValueError as exc:
                # 첨부 PDF가 없는 글은 다시 볼 필요가 없다. 네트워크 오류는 재시도한다.
                log.warning("%s 게시글 PDF 없음: %s (%s)", src["name"], title, exc)
            else:
                await _analyze(
                    conn, fetcher, src, pdf, title, link.url, year, storage_dir, attempt
                )
        # 처리 후에만 기록해서 실패한 게시글은 재시도 때 다시 처리된다.
        conn.execute(
            """INSERT INTO crawl_seen_items (source_id, item_key, url, title, matched)
               VALUES (%s, %s, %s, %s, %s) ON CONFLICT DO NOTHING""",
            (src["id"], key, link.url, title, matched),
        )


async def _check_guide_page(conn, fetcher, src, year, storage_dir, attempt):
    pdf = await fetcher.download(src["url"])
    if pdf.sha256 == src["content_hash"]:
        return
    title = f"{src['name']} {year}학년도 편입학 모집요강"
    await _analyze(
        conn, fetcher, src, pdf, title, src["url"], year, storage_dir, attempt
    )
    conn.execute(
        "UPDATE crawl_sources SET content_hash = %s WHERE id = %s",
        (pdf.sha256, src["id"]),
    )


async def _analyze(
    conn, fetcher, src, pdf, title, page_url, year, storage_dir, attempt
):
    exists = conn.execute(
        "SELECT 1 FROM admission_documents WHERE file_sha256 = %s", (pdf.sha256,)
    ).fetchone()
    if exists:
        return  # 같은 파일은 다시 분석하지 않는다
    # shortcut: 원본은 로컬 디렉터리에 보관한다. R2 연동 시 업로드 후 storage_key를 R2 키로 교체.
    storage_key = f"{pdf.sha256}.pdf"
    storage_dir.mkdir(parents=True, exist_ok=True)
    (storage_dir / storage_key).write_bytes(pdf.content)

    def save(status, data=None, validation=None, error=None, academic_year=year):
        with conn.transaction():
            doc_id = conn.execute(
                """INSERT INTO admission_documents (university_id, source_id, academic_year,
                   title, page_url, file_url, file_sha256, storage_key, status)
                   VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s) RETURNING id""",
                (
                    src["university_id"],
                    src["id"],
                    academic_year,
                    title[:300],
                    page_url,
                    pdf.url,
                    pdf.sha256,
                    storage_key,
                    status,
                ),
            ).fetchone()["id"]
            conn.execute(
                """INSERT INTO extraction_runs (document_id, model, result, validation, error)
                   VALUES (%s, %s, %s, %s, %s)""",
                (
                    doc_id,
                    fetcher.model,
                    None if data is None else Jsonb(data),
                    None if validation is None else Jsonb(validation),
                    error,
                ),
            )

    log.info("%s Gemini 추출 시작 (sha256=%s)", src["name"], pdf.sha256)
    try:
        data = await asyncio.to_thread(fetcher.extract, pdf.content)
    except Exception as exc:
        # 재시도 소진 후에는 FAILED로 남겨 같은 PDF를 더 호출하지 않는다(운영자가 행 삭제 시 재분석).
        if attempt > MAX_RETRIES:
            save("FAILED", error=type(exc).__name__)
        raise
    extracted_year = data.get("학년도")
    if extracted_year != year or src["name"] not in data.get("학교", ""):
        valid_year = isinstance(extracted_year, int) and 2000 <= extracted_year <= 2100
        save(
            "FAILED",
            data,
            error="학교 또는 학년도 불일치",
            academic_year=extracted_year if valid_year else year,
        )
        return
    validation = validate_totals(data)
    save("EXTRACTED" if validation["passed"] else "NEEDS_REVIEW", data, validation)
    log.info("%s 요강 저장 (검증 %s)", src["name"], validation["status"])
