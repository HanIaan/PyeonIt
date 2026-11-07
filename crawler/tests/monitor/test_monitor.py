import asyncio
import hashlib
import json
from datetime import UTC, datetime, timedelta
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock

import httpx
import pytest

from pyeonit_crawler.detection import Link
from pyeonit_crawler.download import DownloadedPDF, NoGuideError
from pyeonit_crawler.monitor import core
from pyeonit_crawler.monitor.core import alert, run_once, title_matches
from pyeonit_crawler.monitor.seed import load_seed

FIXTURE = Path(__file__).with_name("fixture_universities.yaml")
YEAR = 2027
T0 = datetime.now(UTC) + timedelta(minutes=1)
FAKE_WEBHOOK = "https://discord.example/api/webhooks/test"
RECOVER_SQL = (  # DEPLOY.md의 복구 SQL과 같게 유지
    "UPDATE admission_documents SET status = 'RETRY_PENDING', retry_count = 0 "
    "WHERE id = %s"
)


@pytest.fixture(autouse=True)
def webhook(monkeypatch):
    """실제 전송 금지: httpx.post를 MockTransport로 돌리고 요청을 모은다."""
    monkeypatch.delenv("ALERT_WEBHOOK_URL", raising=False)
    sent, state = [], {"respond": lambda req: httpx.Response(204)}

    def handler(request):
        sent.append(request)
        return state["respond"](request)

    def fake_post(url, **kwargs):
        with httpx.Client(transport=httpx.MockTransport(handler)) as client:
            return client.post(url, **kwargs)

    monkeypatch.setattr(core.httpx, "post", fake_post)
    return SimpleNamespace(sent=sent, state=state)


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


def run(conn, f, now, tmp_path, only=None):
    return asyncio.run(
        run_once(conn, f, year=YEAR, storage_dir=tmp_path, now=now, only=only)
    )


def gemini_error(code: int) -> httpx.HTTPStatusError:
    request = httpx.Request("POST", "https://gemini.test")
    return httpx.HTTPStatusError(
        str(code), request=request, response=httpx.Response(code, request=request)
    )


def doc(conn):
    return conn.execute(
        "SELECT id, status, retry_count FROM admission_documents"
    ).fetchone()


def alerts(conn):
    return conn.execute(
        "SELECT kind, message FROM operator_alerts ORDER BY id"
    ).fetchall()


def cycles(conn, f, tmp_path, n, kind="GUIDE_PAGE", now=T0):
    for _ in range(n):
        run(conn, f, now, tmp_path)
        now = source(conn, kind)["next_check_at"]
    return now


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


@pytest.mark.parametrize(
    "exc", [gemini_error(429), gemini_error(503), httpx.ReadTimeout("timeout")]
)
def test_gemini_transient_error_retries_then_fails_with_alert(
    conn, tmp_path, monkeypatch, webhook, exc
):
    monkeypatch.setenv("ALERT_WEBHOOK_URL", FAKE_WEBHOOK)
    load_seed(conn, FIXTURE)
    only(conn, "GUIDE_PAGE")
    f = fetcher(extract=MagicMock(side_effect=exc))
    now = cycles(conn, f, tmp_path, 4)
    assert doc(conn)["status"] == "RETRY_PENDING" and doc(conn)["retry_count"] == 4
    assert f.extract.call_count == 4 and not alerts(conn) and not webhook.sent
    # 소스 확인 자체는 성공: 정상 10분 주기로 다음 주기에 재시도
    src = source(conn, "GUIDE_PAGE")
    assert src["consecutive_failures"] == 0
    assert src["next_check_at"] - src["last_checked_at"] == timedelta(minutes=10)

    cycles(conn, f, tmp_path, 3, now=now)  # 5주기째 FAILED, 이후 호출 없음
    assert f.extract.call_count == 5
    assert doc(conn)["status"] == "FAILED" and doc(conn)["retry_count"] == 5
    assert [a["kind"] for a in alerts(conn)] == ["GEMINI_FAILED"]
    assert conn.execute("SELECT count(*) FROM extraction_runs").fetchone()["count"] == 5

    [req] = webhook.sent
    assert req.method == "POST" and str(req.url) == FAKE_WEBHOOK
    assert req.headers["content-type"] == "application/json"
    body = json.loads(req.content)
    assert list(body) == ["content"] and "GEMINI_FAILED" in body["content"]


@pytest.mark.parametrize(
    "exc", [gemini_error(400), json.JSONDecodeError("bad", "x", 0)]
)
def test_gemini_permanent_error_fails_immediately(conn, tmp_path, exc):
    load_seed(conn, FIXTURE)
    only(conn, "GUIDE_PAGE")
    f = fetcher(extract=MagicMock(side_effect=exc))
    cycles(conn, f, tmp_path, 3)
    assert f.extract.call_count == 1
    assert doc(conn)["status"] == "FAILED" and doc(conn)["retry_count"] == 0
    assert not alerts(conn)


def test_failed_document_recovery(conn, tmp_path):
    load_seed(conn, FIXTURE)
    only(conn, "GUIDE_PAGE")
    now = cycles(
        conn, fetcher(extract=MagicMock(side_effect=gemini_error(503))), tmp_path, 5
    )
    assert doc(conn)["status"] == "FAILED"
    conn.execute(RECOVER_SQL, (doc(conn)["id"],))
    f = fetcher()
    run(conn, f, now, tmp_path)
    f.extract.assert_called_once()  # 저장된 원본으로 재분석
    assert doc(conn)["status"] == "EXTRACTED"
    assert (
        conn.execute("SELECT count(*) FROM admission_documents").fetchone()["count"]
        == 1
    )


def test_webhook_truncates_and_never_raises(conn, monkeypatch, webhook):
    monkeypatch.setenv("ALERT_WEBHOOK_URL", FAKE_WEBHOOK)
    alert(conn, "TEST", "가" * 5000)
    body = json.loads(webhook.sent[0].content)
    assert len(body["content"]) == 2000

    def boom(request):
        raise httpx.ConnectError("down")

    for respond in (lambda r: httpx.Response(500), boom):
        webhook.state["respond"] = respond
        alert(conn, "TEST", "실패해도 계속")
    assert len(alerts(conn)) == 3  # DB 기록은 전송 실패와 무관


def test_matched_notice_without_pdf_is_rechecked_then_needs_review(conn, tmp_path):
    load_seed(conn, FIXTURE)
    only(conn, "NOTICE_BOARD")
    post = Link("2027학년도 편입학 모집요강", "https://univ.test/notice/9")
    f = fetcher([post], download=AsyncMock(side_effect=NoGuideError("PDF 없음")))

    def seen():
        return conn.execute(
            "SELECT status, first_seen_at FROM crawl_seen_items"
        ).fetchone()

    run(conn, f, T0, tmp_path)
    assert seen() == {"status": "AWAITING_PDF", "first_seen_at": T0}
    run(conn, f, T0 + timedelta(days=6), tmp_path)
    assert f.download.await_count == 2 and seen()["status"] == "AWAITING_PDF"
    assert not alerts(conn)

    run(conn, f, T0 + timedelta(days=7), tmp_path)
    assert seen() == {"status": "NEEDS_REVIEW", "first_seen_at": T0}
    assert [a["kind"] for a in alerts(conn)] == ["PDF_MISSING"]
    run(conn, f, T0 + timedelta(days=8), tmp_path)
    assert f.download.await_count == 3  # 운영자에게 넘긴 뒤에는 다시 보지 않는다
    assert not conn.execute("SELECT 1 FROM admission_documents").fetchone()


def test_notice_pdf_appearing_later_is_analyzed(conn, tmp_path):
    load_seed(conn, FIXTURE)
    only(conn, "NOTICE_BOARD")
    post = Link("2027학년도 편입학 모집요강", "https://univ.test/notice/9")
    run(
        conn,
        fetcher([post], download=AsyncMock(side_effect=NoGuideError("x"))),
        T0,
        tmp_path,
    )
    f = fetcher([post])
    run(conn, f, T0 + timedelta(days=1), tmp_path)
    f.extract.assert_called_once()
    assert doc(conn)["status"] == "EXTRACTED"
    assert (
        conn.execute("SELECT status FROM crawl_seen_items").fetchone()["status"]
        == "DONE"
    )


def test_seed_disables_and_reenables_missing_sources(conn, tmp_path):
    load_seed(conn, FIXTURE)
    partial = tmp_path / "partial.yaml"
    partial.write_text(
        FIXTURE.read_text(encoding="utf-8").split("- name: 예시대학교")[0]
    )
    load_seed(conn, partial)

    def enabled():
        rows = conn.execute("SELECT url, enabled FROM crawl_sources ORDER BY url")
        return {r["url"]: r["enabled"] for r in rows}

    assert enabled() == {
        "https://example.test/board": False,  # 삭제하지 않고 비활성화
        "https://univ.test/guide": True,
        "https://univ.test/notice": True,
    }
    load_seed(conn, FIXTURE)
    assert all(enabled().values())


def test_only_limits_sources(conn, tmp_path):
    load_seed(conn, FIXTURE)
    f = fetcher()
    assert run(conn, f, T0, tmp_path, only=["예시대학교(글로벌)"]) == 1
    f.links.assert_awaited_once_with("https://example.test/board")
    assert core.unknown_universities(conn, ["예시대학교(글로벌)", "없는대학교"]) == [
        "없는대학교"
    ]
