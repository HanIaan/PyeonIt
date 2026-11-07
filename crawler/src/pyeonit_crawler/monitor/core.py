"""감시 1회분 로직. 외부 I/O는 fetcher(links/download/extract/model)로 주입한다."""

import asyncio
import json
import logging
import os
import re
from datetime import UTC, datetime, timedelta
from pathlib import Path
from urllib.parse import urldefrag

import httpx
from psycopg.types.json import Jsonb

from ..detection import is_admission_guide
from ..validation import validate_totals

log = logging.getLogger(__name__)

PENDING_INTERVAL = timedelta(minutes=10)  # 해당 학년도 요강 미수집 대학
COLLECTED_INTERVAL = timedelta(days=1)  # 수집 완료 대학: 정정 공지·PDF 교체만 확인
MAX_RETRIES = 3  # 소스 확인(페이지·다운로드) 실패 백오프 횟수
GEMINI_MAX_CYCLES = 5  # Gemini 일시 오류는 이 주기 수만큼 연속 실패하면 FAILED
PDF_WAIT = timedelta(days=7)  # 매칭 공지에 PDF가 이 기간 없으면 운영자 확인
COLLECTED_STATUSES = ["EXTRACTED", "NEEDS_REVIEW", "APPROVED"]


def title_matches(title: str, year: int) -> bool:
    # 제목에 다른 학년도만 적힌 글(지난 요강)은 PDF·Gemini 비용을 쓰지 않는다.
    years = re.findall(r"20\d\d", title)
    return is_admission_guide(title) and (not years or str(year) in years)


LABEL = "u.name || CASE WHEN u.campus = '' THEN '' ELSE '(' || u.campus || ')' END"


def due_sources(conn, now: datetime, only: list[str] | None = None) -> list[dict]:
    # only: "서강대학교", "연세대학교(서울)" 형식의 대학 이름 목록. None이면 전체
    return conn.execute(
        f"""SELECT s.id, s.kind, s.url, s.content_hash, s.consecutive_failures,
                  u.id AS university_id, u.name
           FROM crawl_sources s JOIN universities u ON u.id = s.university_id
           WHERE s.enabled AND s.next_check_at <= %s
             AND (%s::text[] IS NULL OR {LABEL} = ANY(%s))
           ORDER BY s.next_check_at""",
        (now, only, only),
    ).fetchall()


def unknown_universities(conn, names: list[str]) -> list[str]:
    known = {
        r["label"] for r in conn.execute(f"SELECT {LABEL} AS label FROM universities u")
    }
    return [n for n in names if n not in known]


def alert(conn, kind: str, message: str, *, source_id=None, document_id=None) -> None:
    """운영자 알림: ERROR 로그 + operator_alerts 행 + (설정 시) ALERT_WEBHOOK_URL POST."""
    log.error("운영자 알림 [%s] %s", kind, message)
    conn.execute(
        """INSERT INTO operator_alerts (kind, source_id, document_id, message)
           VALUES (%s, %s, %s, %s)""",
        (kind, source_id, document_id, message),
    )
    if url := os.environ.get("ALERT_WEBHOOK_URL"):
        # 디스코드 웹훅 형식. content 한도 2000자. URL은 비밀이라 로그에 남기지 않는다.
        try:
            httpx.post(
                url, json={"content": f"[{kind}] {message}"[:2000]}, timeout=10
            ).raise_for_status()
        except httpx.HTTPError as exc:
            log.error("알림 웹훅 전송 실패: %s", type(exc).__name__)


def is_transient(exc: Exception) -> bool:
    # 영구 오류: Gemini 4xx(깨진 PDF 등, 429 제외)와 해석 불가 응답. 나머지는 다음 주기 재시도
    if isinstance(exc, httpx.HTTPStatusError):
        code = exc.response.status_code
        return code == 429 or code >= 500
    return not isinstance(exc, json.JSONDecodeError)


def interval(conn, university_id: int, year: int) -> timedelta:
    collected = conn.execute(
        """SELECT EXISTS (SELECT 1 FROM admission_documents
           WHERE university_id = %s AND academic_year = %s AND status = ANY(%s))""",
        (university_id, year, COLLECTED_STATUSES),
    ).fetchone()["exists"]
    return COLLECTED_INTERVAL if collected else PENDING_INTERVAL


async def run_once(
    conn, fetcher, *, year: int, storage_dir: Path, now=None, only=None
) -> int:
    now = now or datetime.now(UTC)
    sources = due_sources(conn, now, only)
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
        # Gemini 일시 오류로 미뤄 둔 문서를 먼저 재분석한다(소스 확인 1회 = 1주기).
        for doc in conn.execute(
            """SELECT id, retry_count, storage_key FROM admission_documents
               WHERE source_id = %s AND status = 'RETRY_PENDING' ORDER BY id""",
            (src["id"],),
        ).fetchall():
            content = (storage_dir / doc["storage_key"]).read_bytes()
            await _extract(conn, fetcher, src, doc, content, year)
        if src["kind"] == "GUIDE_PAGE":
            await _check_guide_page(conn, fetcher, src, year, storage_dir)
        else:
            await _check_notice_board(conn, fetcher, src, year, storage_dir, now)
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


async def _check_notice_board(conn, fetcher, src, year, storage_dir, now):
    # shortcut: 게시판 구조를 모르므로 제목 있는 모든 링크를 URL(fragment 제외)로 식별한다.
    # 세션성 쿼리가 붙는 게시판은 매번 신규로 보이므로 그때 학교별 item_key 규칙을 추가.
    for link in await fetcher.links(src["url"]):
        title = link.title.strip()
        key = urldefrag(link.url).url
        seen = conn.execute(
            """SELECT status, first_seen_at FROM crawl_seen_items
               WHERE source_id = %s AND item_key = %s""",
            (src["id"], key),
        ).fetchone()
        if not title or (seen and seen["status"] != "AWAITING_PDF"):
            continue
        matched = title_matches(title, year)
        status = "DONE"
        if matched:
            try:
                pdf = await fetcher.download(link.url)
            except ValueError as exc:
                # PDF 없는 매칭 공지는 다음 주기에 다시 본다. 네트워크 오류는 소스 재시도.
                # shortcut: 7일 전에 목록에서 사라진 글은 판정하지 않는다. 누락 사례가 나오면 별도 점검 추가.
                first_seen = seen["first_seen_at"] if seen else now
                if now - first_seen >= PDF_WAIT:
                    status = "NEEDS_REVIEW"
                    alert(
                        conn,
                        "PDF_MISSING",
                        f"{src['name']} 매칭 공지에 7일간 PDF 없음(HWP·JS 뷰어 확인): "
                        f"{title} {link.url}",
                        source_id=src["id"],
                    )
                else:
                    status = "AWAITING_PDF"
                    log.warning("%s 게시글 PDF 없음: %s (%s)", src["name"], title, exc)
            else:
                await _analyze(
                    conn, fetcher, src, pdf, title, link.url, year, storage_dir
                )
        # 처리 후에만 기록해서 실패한 게시글은 재시도 때 다시 처리된다.
        conn.execute(
            """INSERT INTO crawl_seen_items
               (source_id, item_key, url, title, matched, status, first_seen_at)
               VALUES (%s, %s, %s, %s, %s, %s, %s)
               ON CONFLICT (source_id, item_key) DO UPDATE SET status = EXCLUDED.status""",
            (src["id"], key, link.url, title, matched, status, now),
        )


async def _check_guide_page(conn, fetcher, src, year, storage_dir):
    pdf = await fetcher.download(src["url"])
    if pdf.sha256 == src["content_hash"]:
        return
    title = f"{src['name']} {year}학년도 편입학 모집요강"
    await _analyze(conn, fetcher, src, pdf, title, src["url"], year, storage_dir)
    conn.execute(
        "UPDATE crawl_sources SET content_hash = %s WHERE id = %s",
        (pdf.sha256, src["id"]),
    )


async def _analyze(conn, fetcher, src, pdf, title, page_url, year, storage_dir):
    exists = conn.execute(
        "SELECT 1 FROM admission_documents WHERE file_sha256 = %s", (pdf.sha256,)
    ).fetchone()
    if exists:
        return  # 같은 파일은 다시 분석하지 않는다
    # shortcut: 원본은 로컬 디렉터리에 보관한다. R2 연동 시 업로드 후 storage_key를 R2 키로 교체.
    storage_key = f"{pdf.sha256}.pdf"
    storage_dir.mkdir(parents=True, exist_ok=True)
    (storage_dir / storage_key).write_bytes(pdf.content)
    # RETRY_PENDING으로 먼저 저장해서 추출 도중 프로세스가 죽어도 다음 주기에 재분석된다.
    doc = conn.execute(
        """INSERT INTO admission_documents (university_id, source_id, academic_year,
           title, page_url, file_url, file_sha256, storage_key, status)
           VALUES (%s, %s, %s, %s, %s, %s, %s, %s, 'RETRY_PENDING')
           RETURNING id, retry_count""",
        (
            src["university_id"],
            src["id"],
            year,
            title[:300],
            page_url,
            pdf.url,
            pdf.sha256,
            storage_key,
        ),
    ).fetchone()
    await _extract(conn, fetcher, src, doc, pdf.content, year)


async def _extract(conn, fetcher, src, doc, content, year):
    def finish(status, data=None, validation=None, error=None, **cols):
        with conn.transaction():
            conn.execute(
                """UPDATE admission_documents SET status = %s,
                   academic_year = COALESCE(%s, academic_year),
                   retry_count = COALESCE(%s, retry_count) WHERE id = %s""",
                (status, cols.get("academic_year"), cols.get("retry_count"), doc["id"]),
            )
            conn.execute(
                """INSERT INTO extraction_runs (document_id, model, result, validation, error)
                   VALUES (%s, %s, %s, %s, %s)""",
                (
                    doc["id"],
                    fetcher.model,
                    None if data is None else Jsonb(data),
                    None if validation is None else Jsonb(validation),
                    error,
                ),
            )

    log.info("%s Gemini 추출 시작 (문서 %d)", src["name"], doc["id"])
    try:
        data = await asyncio.to_thread(fetcher.extract, content)
    except Exception as exc:
        error = f"{type(exc).__name__}: {exc}"[:500]
        if not is_transient(exc):
            log.warning(
                "%s 문서 %d 영구 오류로 FAILED: %s", src["name"], doc["id"], error
            )
            finish("FAILED", error=error)
            return
        count = doc["retry_count"] + 1
        if count < GEMINI_MAX_CYCLES:
            log.warning(
                "%s 문서 %d Gemini 일시 오류 (%d/%d), 다음 주기 재시도: %s",
                src["name"],
                doc["id"],
                count,
                GEMINI_MAX_CYCLES,
                error,
            )
            finish("RETRY_PENDING", error=error, retry_count=count)
            return
        finish("FAILED", error=error, retry_count=count)
        alert(
            conn,
            "GEMINI_FAILED",
            f"{src['name']} 문서 {doc['id']} Gemini {count}주기 연속 실패: {error}",
            source_id=src["id"],
            document_id=doc["id"],
        )
        return
    extracted_year = data.get("학년도")
    if extracted_year != year or src["name"] not in data.get("학교", ""):
        valid_year = isinstance(extracted_year, int) and 2000 <= extracted_year <= 2100
        finish(
            "FAILED",
            data,
            error="학교 또는 학년도 불일치",
            academic_year=extracted_year if valid_year else None,
        )
        return
    validation = validate_totals(data)
    finish("EXTRACTED" if validation["passed"] else "NEEDS_REVIEW", data, validation)
    log.info("%s 요강 저장 (검증 %s)", src["name"], validation["status"])
