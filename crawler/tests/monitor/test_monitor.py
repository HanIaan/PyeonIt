import asyncio
import hashlib
from datetime import UTC, datetime, timedelta
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock

from pyeonit_crawler.detection import Link
from pyeonit_crawler.download import DownloadedPDF
from pyeonit_crawler.monitor.core import run_once, title_matches
from pyeonit_crawler.monitor.seed import load_seed

FIXTURE = Path(__file__).with_name("fixture_universities.yaml")
YEAR = 2027
T0 = datetime.now(UTC) + timedelta(minutes=1)


def pdf(content: bytes, url="https://univ.test/a.pdf") -> DownloadedPDF:
    return DownloadedPDF(url, content, hashlib.sha256(content).hexdigest())


def data(total=3, year=YEAR):
    cell = {"전형": "일반", "인원": 3, "원문": "3"}
    return {
        "학교": "테스트대학교",
        "학년도": year,
        "모집인원": [
            {"계열": "인문", "단위": "학과", "모집단위": "국문", "인원": [cell]}
        ],
        "소계": [],
        "총계": [{**cell, "인원": total}],
        "일정": [],
    }


def fetcher(links=(), download=None, extract=None):
    return SimpleNamespace(
        links=AsyncMock(return_value=list(links)),
        download=AsyncMock(return_value=pdf(b"%PDF-a"))
        if download is None
        else download,
        extract=MagicMock(return_value=data()) if extract is None else extract,
        model="mock",
    )


def run(conn, f, now, tmp_path):
    return asyncio.run(run_once(conn, f, year=YEAR, storage_dir=tmp_path, now=now))


def source(conn, kind):
    return conn.execute(
        "SELECT * FROM crawl_sources WHERE kind = %s AND url LIKE 'https://univ.test/%%'",
        (kind,),
    ).fetchone()


def only(conn, kind):
    conn.execute(
        "UPDATE crawl_sources SET enabled = (kind = %s AND url LIKE 'https://univ.test/%%')",
        (kind,),
    )


def test_title_matches():
    assert title_matches("2027학년도 편입학 모집요강 안내", YEAR)
    assert title_matches("편입학 모집요강 정정 공지", YEAR)
    assert not title_matches("2026학년도 편입학 모집요강", YEAR)
    assert not title_matches("2027학년도 편입학 시행계획 모집요강", YEAR)
    assert not title_matches("2027학년도 편입 일정", YEAR)


def test_seed_is_idempotent(conn):
    assert load_seed(conn, FIXTURE) == 2
    load_seed(conn, FIXTURE)
    assert conn.execute("SELECT count(*) FROM universities").fetchone()["count"] == 2
    rows = conn.execute("SELECT kind, url FROM crawl_sources ORDER BY url").fetchall()
    assert [r["url"] for r in rows] == [
        "https://example.test/board",
        "https://univ.test/guide",
        "https://univ.test/notice",
    ]
    assert (
        conn.execute(
            "SELECT campus FROM universities WHERE name = '예시대학교'"
        ).fetchone()["campus"]
        == "글로벌"
    )


def test_only_new_posts_are_processed(conn, tmp_path):
    load_seed(conn, FIXTURE)
    only(conn, "NOTICE_BOARD")
    a = Link("2027학년도 편입학 모집요강", "https://univ.test/notice/1")
    b = Link("학사 일정 안내", "https://univ.test/notice/2")
    f = fetcher([a, b])
    run(conn, f, T0, tmp_path)
    f.download.assert_awaited_once_with(a.url)
    seen = conn.execute(
        "SELECT url, matched FROM crawl_seen_items ORDER BY url"
    ).fetchall()
    assert [(r["url"], r["matched"]) for r in seen] == [(a.url, True), (b.url, False)]

    c = Link("2027학년도 편입학 모집요강 정정", "https://univ.test/notice/3#top")
    f2 = fetcher([a, b, c], download=AsyncMock(return_value=pdf(b"%PDF-c")))
    run(conn, f2, T0 + timedelta(days=2), tmp_path)
    f2.download.assert_awaited_once_with(c.url)
    assert (
        conn.execute("SELECT count(*) FROM admission_documents").fetchone()["count"]
        == 2
    )


def test_same_pdf_never_reanalyzed(conn, tmp_path):
    load_seed(conn, FIXTURE)
    only(conn, "NOTICE_BOARD")
    posts = [
        Link("2027학년도 편입학 모집요강", "https://univ.test/n/1"),
        Link("2027 편입 모집요강 (재게시)", "https://univ.test/n/2"),
    ]
    f = fetcher(posts)
    run(conn, f, T0, tmp_path)
    assert f.download.await_count == 2
    f.extract.assert_called_once()
    assert (
        tmp_path / f"{hashlib.sha256(b'%PDF-a').hexdigest()}.pdf"
    ).read_bytes() == b"%PDF-a"

    # 요강 고정 페이지: 같은 PDF는 Gemini 미호출, 교체된 PDF만 분석
    only(conn, "GUIDE_PAGE")
    g = fetcher()
    run(conn, g, T0, tmp_path)
    g.extract.assert_not_called()
    assert (
        source(conn, "GUIDE_PAGE")["content_hash"]
        == hashlib.sha256(b"%PDF-a").hexdigest()
    )
    g2 = fetcher(download=AsyncMock(return_value=pdf(b"%PDF-b")))
    run(conn, g2, T0 + timedelta(days=2), tmp_path)
    g2.extract.assert_called_once()


def test_interval_switches_after_collection(conn, tmp_path):
    load_seed(conn, FIXTURE)
    only(conn, "GUIDE_PAGE")
    f = fetcher(
        extract=MagicMock(return_value=data(year=2026))
    )  # 아직 지난 학년도 요강
    run(conn, f, T0, tmp_path)
    assert source(conn, "GUIDE_PAGE")["next_check_at"] == T0 + timedelta(minutes=10)
    assert conn.execute(
        "SELECT status, academic_year FROM admission_documents"
    ).fetchone() == {"status": "FAILED", "academic_year": 2026}

    t1 = T0 + timedelta(minutes=10)
    f2 = fetcher(
        download=AsyncMock(return_value=pdf(b"%PDF-new")),
        extract=MagicMock(return_value=data(total=4)),
    )
    run(conn, f2, t1, tmp_path)
    assert source(conn, "GUIDE_PAGE")["next_check_at"] == t1 + timedelta(days=1)
    doc = conn.execute(
        "SELECT id, status FROM admission_documents WHERE academic_year = 2027"
    ).fetchone()
    assert doc["status"] == "NEEDS_REVIEW"  # 총계 불일치
    ext = conn.execute(
        "SELECT model, result, validation FROM extraction_runs WHERE document_id = %s",
        (doc["id"],),
    ).fetchone()
    assert (
        ext["model"] == "mock"
        and ext["validation"]["status"] == "failed"
        and ext["result"]["학년도"] == 2027
    )

    # 같은 대학의 다른 소스도 수집 완료 후엔 하루 주기
    only(conn, "NOTICE_BOARD")
    run(conn, fetcher(), t1, tmp_path)
    assert source(conn, "NOTICE_BOARD")["next_check_at"] == t1 + timedelta(days=1)


def test_passed_validation_is_extracted(conn, tmp_path):
    load_seed(conn, FIXTURE)
    only(conn, "GUIDE_PAGE")
    run(conn, fetcher(), T0, tmp_path)
    assert (
        conn.execute("SELECT status FROM admission_documents").fetchone()["status"]
        == "EXTRACTED"
    )


def test_retry_backoff_is_limited(conn, tmp_path):
    load_seed(conn, FIXTURE)
    only(conn, "GUIDE_PAGE")
    f = fetcher(download=AsyncMock(side_effect=TimeoutError("timeout")))
    now, delays = T0, []
    for _ in range(5):
        run(conn, f, now, tmp_path)
        nxt = source(conn, "GUIDE_PAGE")["next_check_at"]
        delays.append(nxt - now)
        now = nxt
    m = timedelta(minutes=1)
    assert delays == [1 * m, 2 * m, 4 * m, 10 * m, 10 * m]
    src = source(conn, "GUIDE_PAGE")
    assert src["consecutive_failures"] == 5 and src["last_error"].startswith(
        "TimeoutError"
    )
    runs = conn.execute("SELECT status, attempt FROM crawl_runs ORDER BY id").fetchall()
    assert [(r["status"], r["attempt"]) for r in runs] == [
        ("FAILED", i) for i in range(1, 6)
    ]

    # 성공하면 연속 실패 초기화
    run(conn, fetcher(), now, tmp_path)
    assert source(conn, "GUIDE_PAGE")["consecutive_failures"] == 0


def test_gemini_failure_gives_up_after_retries(conn, tmp_path):
    load_seed(conn, FIXTURE)
    only(conn, "GUIDE_PAGE")
    f = fetcher(extract=MagicMock(side_effect=RuntimeError("quota")))
    now = T0
    for _ in range(6):
        run(conn, f, now, tmp_path)
        now = source(conn, "GUIDE_PAGE")["next_check_at"]
    assert f.extract.call_count == 4  # 최초 + 재시도 3회, 이후 FAILED 기록으로 중단
    doc = conn.execute("SELECT id, status FROM admission_documents").fetchone()
    assert doc["status"] == "FAILED"
    assert (
        conn.execute("SELECT error FROM extraction_runs").fetchone()["error"]
        == "RuntimeError"
    )
