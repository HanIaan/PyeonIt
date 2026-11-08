"""감시 1회분 로직. 외부 I/O는 fetcher(links/download/fetch/extract/model)로 주입한다.

한 주기 = 대상 소스 전부 확인(목록·PDF 다운로드) → 그 소스들의 대기 문서를 모아 Gemini 추출.
"""

import asyncio
import io
import logging
import os
import re
from datetime import UTC, datetime, timedelta
from pathlib import Path
from urllib.parse import unquote_to_bytes, urldefrag, urlsplit

import httpx
from psycopg.types.json import Jsonb
from pypdf import PdfReader

from ..detection import is_admission_guide
from ..download import NoGuideError
from ..validation import validate_totals

log = logging.getLogger(__name__)
logging.getLogger("pypdf").setLevel(logging.ERROR)  # 깨진 PDF 경고는 판단 불가로만 처리

PENDING_INTERVAL = timedelta(minutes=10)  # 해당 학년도 요강 미수집 대학
COLLECTED_INTERVAL = timedelta(days=1)  # 수집 완료 대학: 정정 공지·PDF 교체만 확인
MAX_RETRIES = 3  # 소스 확인(게시판·요강 페이지 접속) 실패 백오프 횟수
LINK_MAX_FAILURES = 3  # 게시글 하나의 다운로드가 이만큼 실패하면 NEEDS_REVIEW
GEMINI_MAX_CYCLES = (
    5  # Gemini 일시 오류(503·타임아웃)는 이 주기 수만큼 연속 실패하면 FAILED
)
QUOTA_DEFAULT_WAIT = timedelta(hours=1)  # 429 응답에 retryDelay가 없을 때
PDF_WAIT = timedelta(days=7)  # 매칭 공지에 PDF가 이 기간 없으면 운영자 확인
COLLECTED_STATUSES = ["EXTRACTED", "NEEDS_REVIEW", "APPROVED"]
QUEUED_STATUSES = ["DETECTED", "RETRY_PENDING", "QUOTA_WAIT"]
RECHECK_ITEM_STATUSES = ("AWAITING_PDF", "RETRY")
YEAR = r"(?<!\d)(20\d\d)(?!\d)"


def title_matches(title: str, year: int) -> bool:
    # 제목에 다른 학년도만 적힌 글(지난 요강)은 PDF·Gemini 비용을 쓰지 않는다.
    years = re.findall(r"20\d\d", title)
    return is_admission_guide(title) and (not years or str(year) in years)


LABEL = "u.name || CASE WHEN u.campus = '' THEN '' ELSE '(' || u.campus || ')' END"


def due_sources(conn, now: datetime, only: list[str] | None = None) -> list[dict]:
    # only: "서강대학교", "연세대학교(서울)" 형식의 대학 이름 목록. None이면 전체
    return conn.execute(
        f"""SELECT s.id, s.kind, s.url, s.content_hash, s.consecutive_failures,
                  s.review_reason, u.id AS university_id, u.name, u.campus
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


def alert(
    conn, kind: str, message: str, *, source_id=None, document_id=None, now=None
) -> None:
    """운영자 알림: ERROR 로그 + operator_alerts 행 + (설정 시) ALERT_WEBHOOK_URL POST."""
    log.error("운영자 알림 [%s] %s", kind, message)
    conn.execute(
        """INSERT INTO operator_alerts (kind, source_id, document_id, message, created_at)
           VALUES (%s, %s, %s, %s, COALESCE(%s, now()))""",
        (kind, source_id, document_id, message, now),
    )
    if url := os.environ.get("ALERT_WEBHOOK_URL"):
        # 디스코드 웹훅 형식. content 한도 2000자. URL은 비밀이라 로그에 남기지 않는다.
        try:
            httpx.post(
                url, json={"content": f"[{kind}] {message}"[:2000]}, timeout=10
            ).raise_for_status()
        except httpx.HTTPError as exc:
            log.error("알림 웹훅 전송 실패: %s", type(exc).__name__)


class QuotaWait(Exception):
    """공유 추출기의 즉시 재시도 전에 감시의 429를 루프로 돌려보낸다."""

    def __init__(self, response: httpx.Response):
        self.response = response
        super().__init__("Gemini 429")


def stop_on_quota(response: httpx.Response) -> None:
    if response.status_code == 429:
        response.read()
        raise QuotaWait(response)


def quota_wait_seconds(response: httpx.Response) -> float | None:
    # 일일 한도 응답에도 retryDelay가 있으면 그대로 존중한다.
    try:
        details = response.json().get("error", {}).get("details", [])
    except (ValueError, AttributeError):
        details = []
    values = [d.get("retryDelay", "") for d in details]
    values.append(response.headers.get("Retry-After", ""))
    for value in values:
        if match := re.fullmatch(r"(\d+(?:\.\d+)?)s?", value):
            if float(match[1]) > 0:
                return float(match[1])
    return None


def gemini_error_kind(exc: Exception) -> str:
    """QUOTA(429) / PERMANENT(깨진 PDF·해석 불가 응답) / TRANSIENT(503·타임아웃 등)."""
    if isinstance(exc, (httpx.HTTPStatusError, QuotaWait)):
        code = exc.response.status_code
        if code == 429:
            return "QUOTA"
        return "TRANSIENT" if code >= 500 else "PERMANENT"
    # JSONDecodeError·출력 토큰 한도·비정상 종료 응답은 gemini.py에서 ValueError로 온다.
    return "PERMANENT" if isinstance(exc, ValueError) else "TRANSIENT"


def link_names(url: str) -> list[str]:
    """URL 경로와 쿼리 값의 파일명. 학교 서버는 파일명을 쿼리에 UTF-8 또는 EUC-KR로 넣는다."""
    parts = urlsplit(url)
    raws = [parts.path] + [p.partition("=")[2] for p in parts.query.split("&")]
    names = []
    for raw in filter(None, raws):
        data = unquote_to_bytes(raw.replace("+", " "))
        try:
            text = data.decode("utf-8")
        except UnicodeDecodeError:
            text = data.decode("cp949", "replace")
        names.append(text.rsplit("/", 1)[-1])
    return names


def first_page_text(content: bytes) -> str | None:
    try:
        return PdfReader(io.BytesIO(content)).pages[0].extract_text() or None
    except Exception:  # 깨진 PDF·암호화·스캔본은 판단 불가로 본다
        return None


def wants_gemini(
    year: int, title: str, names: list[str], first_page: str | None
) -> bool:
    """제목·파일명·첫 페이지에 다른 학년도만 있으면 False. 학년도가 없거나 대상 학년도면 True."""
    years = set(re.findall(YEAR, " ".join([title, *names])))
    # 첫 페이지는 날짜(2026. 12.)가 섞이므로 'N학년도' 표기만 학년도로 본다.
    years |= set(re.findall(r"(?<!\d)(20\d\d)\s*학년도", first_page or ""))
    return not years or str(year) in years


def guide_candidates(links, year: int) -> tuple[list, int]:
    """요강 고정 페이지의 대상 학년도 요강 PDF 후보와, 다른 학년도 요강 PDF 수."""
    candidates, stale = {}, 0
    for link in links:
        names = link_names(link.url)
        text = re.sub(r"\s+", "", " ".join([link.title, *names]))
        is_guide = "편입" in text and ("요강" in text or "모집" in text)
        if not is_guide or not any(n.lower().endswith(".pdf") for n in names):
            continue
        if any(word in text for word in ("기본계획", "예고", "시행계획")):
            continue
        years = set(re.findall(YEAR, text))
        if years and str(year) not in years:
            stale += 1
        else:
            candidates.setdefault(link.url, link)
    return list(candidates.values()), stale


def school_matches(label, name: str, campus: str, first_page: str | None) -> bool:
    """공백 무시 정확 비교. 캠퍼스가 있으면 학교 표기 나머지나 첫 페이지에 캠퍼스명이 있어야 한다."""
    compact = re.sub(r"\s+", "", label) if isinstance(label, str) else ""
    name = re.sub(r"\s+", "", name)
    if not compact.startswith(name):
        return False
    rest = compact[len(name) :]
    if not campus:
        return rest == ""
    campus = re.sub(r"\s+", "", campus)
    allowed = {campus, f"{campus}캠퍼스", f"({campus})", f"({campus}캠퍼스)"}
    if rest:
        return rest in allowed
    return campus in re.sub(r"\s+", "", first_page or "")


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
    # 추출(문서당 수 분)이 다른 학교 확인을 막지 않도록 확인을 먼저 모두 끝낸다.
    for source in sources:
        await check_source(conn, fetcher, source, year, storage_dir, now)
    await extract_queued(conn, fetcher, sources, year, storage_dir, now)
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
            await _check_guide_page(conn, fetcher, src, year, storage_dir, now)
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
            """SELECT status, first_seen_at, failures FROM crawl_seen_items
               WHERE source_id = %s AND item_key = %s""",
            (src["id"], key),
        ).fetchone()
        if not title or (seen and seen["status"] not in RECHECK_ITEM_STATUSES):
            continue
        matched = title_matches(title, year)
        status, failures, sha = "DONE", 0, None
        if matched:
            try:
                pdf = await fetcher.download(link.url)
                await _queue(conn, src, pdf, title, title, link.url, year, storage_dir)
                sha = pdf.sha256
            except NoGuideError as exc:
                # PDF 없는 매칭 공지는 다음 주기에 다시 본다.
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
                        now=now,
                    )
                else:
                    status = "AWAITING_PDF"
                    log.warning("%s 게시글 PDF 없음: %s (%s)", src["name"], title, exc)
            except Exception as exc:
                # 게시글 하나의 타임아웃·5xx가 게시판 전체 확인을 실패시키지 않게 격리한다.
                failures = (seen["failures"] if seen else 0) + 1
                error = f"{type(exc).__name__}: {exc}"[:300]
                if failures >= LINK_MAX_FAILURES:
                    status = "NEEDS_REVIEW"
                    alert(
                        conn,
                        "LINK_FAILED",
                        f"{src['name']} 게시글 다운로드 {failures}회 실패: "
                        f"{title} {link.url} ({error})",
                        source_id=src["id"],
                        now=now,
                    )
                else:
                    status = "RETRY"
                    log.warning(
                        "%s 게시글 다운로드 실패 (%d/%d): %s (%s)",
                        src["name"],
                        failures,
                        LINK_MAX_FAILURES,
                        title,
                        error,
                    )
        # 처리 후에만 기록해서 실패한 게시글은 재시도 때 다시 처리된다.
        conn.execute(
            """INSERT INTO crawl_seen_items (source_id, item_key, url, title, matched,
                   status, failures, file_sha256, first_seen_at)
               VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)
               ON CONFLICT (source_id, item_key) DO UPDATE SET status = EXCLUDED.status,
                   failures = EXCLUDED.failures, file_sha256 = EXCLUDED.file_sha256""",
            (src["id"], key, link.url, title, matched, status, failures, sha, now),
        )


async def _check_guide_page(conn, fetcher, src, year, storage_dir, now):
    candidates, stale = guide_candidates(await fetcher.links(src["url"]), year)
    if len(candidates) == 1:
        reason = None
    elif not candidates and stale:
        reason = None  # 지난 학년도 요강만 게시된 정상 대기 상태
        log.info("%s %d학년도 요강 미게시 (지난 요강 %d개)", src["name"], year, stale)
    else:
        urls = " ".join(c.url for c in candidates[:5])
        reason = f"요강 후보 PDF {len(candidates)}개 {urls}".strip()
    if reason != src["review_reason"]:
        conn.execute(
            "UPDATE crawl_sources SET review_reason = %s WHERE id = %s",
            (reason, src["id"]),
        )
        if reason:  # 상태가 바뀔 때만 알린다(같은 사유로 주기마다 알리지 않음)
            alert(
                conn,
                "GUIDE_REVIEW",
                f"{src['name']} 요강 페이지 확인 필요: {reason} ({src['url']})",
                source_id=src["id"],
                now=now,
            )
    if len(candidates) != 1:
        return
    # shortcut: 고정 페이지는 PDF를 매 주기 받아 sha256으로 비교한다. URL 비교로 줄이면 같은 주소 교체를 놓친다.
    link = candidates[0]
    pdf = await fetcher.fetch(link.url, src["url"])
    if pdf.sha256 == src["content_hash"]:
        return
    title = f"{src['name']} {year}학년도 편입학 모집요강"
    await _queue(conn, src, pdf, title, link.title, src["url"], year, storage_dir)
    conn.execute(
        "UPDATE crawl_sources SET content_hash = %s WHERE id = %s",
        (pdf.sha256, src["id"]),
    )


async def _queue(conn, src, pdf, title, label, page_url, year, storage_dir) -> None:
    """새 PDF를 저장하고 추출 대기(DETECTED)로 등록. 다른 학년도 PDF는 Gemini 없이 넘긴다."""
    known = conn.execute(
        """SELECT 1 FROM admission_documents WHERE file_sha256 = %s
           UNION ALL SELECT 1 FROM crawl_seen_items WHERE file_sha256 = %s""",
        (pdf.sha256, pdf.sha256),
    ).fetchone()
    if known:
        return  # 같은 파일은 다시 분석하지 않는다
    names = link_names(pdf.url)
    if not wants_gemini(year, label, names, first_page_text(pdf.content)):
        log.info("%s 다른 학년도 PDF, 분석 생략: %s %s", src["name"], label, names[-1:])
        return
    # shortcut: 원본은 로컬 디렉터리에 보관한다. R2 연동 시 업로드 후 storage_key를 R2 키로 교체.
    storage_key = f"{pdf.sha256}.pdf"
    storage_dir.mkdir(parents=True, exist_ok=True)
    (storage_dir / storage_key).write_bytes(pdf.content)
    conn.execute(
        """INSERT INTO admission_documents (university_id, source_id, academic_year,
           title, page_url, file_url, file_sha256, storage_key, status)
           VALUES (%s, %s, %s, %s, %s, %s, %s, %s, 'DETECTED')""",
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
    )


async def extract_queued(conn, fetcher, sources, year, storage_dir, now) -> None:
    by_id = {s["id"]: s for s in sources}
    docs = conn.execute(
        """SELECT id, source_id, retry_count, storage_key FROM admission_documents
           WHERE source_id = ANY(%s) AND status = ANY(%s)
             AND (retry_after IS NULL OR retry_after <= %s) ORDER BY id""",
        (list(by_id), QUEUED_STATUSES, now),
    ).fetchall()
    quota_until = None
    for doc in docs:
        if quota_until:  # 할당량 소진 후에는 남은 문서도 호출하지 않고 함께 미룬다
            conn.execute(
                """UPDATE admission_documents SET status = 'QUOTA_WAIT', retry_after = %s
                   WHERE id = %s""",
                (quota_until, doc["id"]),
            )
            continue
        content = (storage_dir / doc["storage_key"]).read_bytes()
        quota_until = await _extract(
            conn, fetcher, by_id[doc["source_id"]], doc, content, year, now
        )
    if docs:
        log.info(
            "오늘 Gemini 요청 %d건 (태평양 시간 자정 기준)", requests_today(conn, now)
        )


def requests_today(conn, now: datetime) -> int:
    # Gemini 일일 무료 한도는 태평양 시간 자정에 초기화된다. extraction_runs 1행 = 추출 1회
    # (gemini.py 내부의 짧은 재시도 요청은 세지 않는다).
    return conn.execute(
        """SELECT count(*) FROM extraction_runs WHERE created_at >=
           date_trunc('day', %s AT TIME ZONE 'America/Los_Angeles')
           AT TIME ZONE 'America/Los_Angeles'""",
        (now,),
    ).fetchone()["count"]


async def _extract(conn, fetcher, src, doc, content, year, now) -> datetime | None:
    """문서 1건 추출. 429면 재개 시각을 반환한다."""

    def finish(status, data=None, validation=None, error=None, **cols):
        with conn.transaction():
            conn.execute(
                """UPDATE admission_documents SET status = %s, retry_after = %s,
                   academic_year = COALESCE(%s, academic_year),
                   retry_count = COALESCE(%s, retry_count) WHERE id = %s""",
                (
                    status,
                    cols.get("retry_after"),
                    cols.get("academic_year"),
                    cols.get("retry_count"),
                    doc["id"],
                ),
            )
            conn.execute(
                """INSERT INTO extraction_runs (document_id, model, result, validation, error)
                   VALUES (%s, %s, %s, %s, %s)""",
                (
                    doc["id"],
                    getattr(data, "model_version", None) or fetcher.model,
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
        kind = gemini_error_kind(exc)
        if kind == "QUOTA":
            # 할당량 대기는 retry_count(5주기 규칙)에 넣지 않는다.
            seconds = quota_wait_seconds(exc.response)
            until = now + (
                timedelta(seconds=seconds) if seconds else QUOTA_DEFAULT_WAIT
            )
            finish("QUOTA_WAIT", error=error, retry_after=until)
            log.warning("Gemini 할당량 소진, %s 이후 재개", until.isoformat())
            recent = conn.execute(
                """SELECT 1 FROM operator_alerts
                   WHERE kind = 'QUOTA_WAIT' AND created_at > %s""",
                (now - timedelta(days=1),),
            ).fetchone()
            if not recent:  # 하루 1회만 알린다
                alert(
                    conn,
                    "QUOTA_WAIT",
                    f"Gemini 할당량 소진({fetcher.model}). {until.isoformat()} 이후 재개",
                    source_id=src["id"],
                    document_id=doc["id"],
                    now=now,
                )
            return until
        if kind == "PERMANENT":
            log.warning(
                "%s 문서 %d 영구 오류로 FAILED: %s", src["name"], doc["id"], error
            )
            finish("FAILED", error=error)
            return None
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
            return None
        finish("FAILED", error=error, retry_count=count)
        alert(
            conn,
            "GEMINI_FAILED",
            f"{src['name']} 문서 {doc['id']} Gemini {count}주기 연속 실패: {error}",
            source_id=src["id"],
            document_id=doc["id"],
            now=now,
        )
        return None
    extracted_year = data.get("학년도")
    if data.get("문서종류") == "잘못된 문서" or extracted_year != year:
        valid_year = isinstance(extracted_year, int) and 2000 <= extracted_year <= 2100
        finish(
            "FAILED",
            data,
            error="편입 요강 아님 또는 학년도 불일치",
            academic_year=extracted_year if valid_year else None,
        )
        return None
    if not school_matches(
        data.get("학교"), src["name"], src["campus"], first_page_text(content)
    ):
        finish("NEEDS_REVIEW", data, error="학교 또는 캠퍼스 불일치")
        return None
    validation = validate_totals(data)
    finish("EXTRACTED" if validation["passed"] else "NEEDS_REVIEW", data, validation)
    log.info("%s 요강 저장 (검증 %s)", src["name"], validation["status"])
    return None
