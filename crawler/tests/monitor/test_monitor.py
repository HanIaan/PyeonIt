import asyncio
import hashlib
import json
from datetime import UTC, datetime, timedelta
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock
from urllib.parse import quote

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
    "UPDATE admission_documents SET status = 'RETRY_PENDING', retry_count = 0, "
    "retry_after = NULL WHERE id = %s"
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
        "문서종류": "편입학 모집요강",
        "모집인원": [
            {"계열": "인문", "단위": "학과", "모집단위": "국문", "인원": [cell]}
        ],
        "소계": [],
        "총계": [{**cell, "인원": total}],
        "일정": [],
    }


# 요강 고정 페이지의 대상 학년도 PDF 링크(서강대식: 문구는 'PDF 다운로드', 파일명은 쿼리에)
GUIDE_LINK = Link(
    "PDF 다운로드",
    "https://univ.test/download.asp?f=/up/1.pdf&name=2027%ED%95%99%EB%85%84%EB%8F%84"
    "%20%ED%8E%B8%EC%9E%85%ED%95%99%20%EB%AA%A8%EC%A7%91%EC%9A%94%EA%B0%95.pdf",
)


def fetcher(links=(GUIDE_LINK,), download=None, extract=None, fetch=None):
    return SimpleNamespace(
        links=AsyncMock(return_value=list(links)),
        download=AsyncMock(return_value=pdf(b"%PDF-a"))
        if download is None
        else download,
        fetch=AsyncMock(return_value=pdf(b"%PDF-a")) if fetch is None else fetch,
        extract=MagicMock(return_value=data()) if extract is None else extract,
        model="mock",
    )


def run(conn, f, now, tmp_path, only=None):
    return asyncio.run(
        run_once(conn, f, year=YEAR, storage_dir=tmp_path, now=now, only=only)
    )


def gemini_error(code: int, body=None) -> httpx.HTTPStatusError:
    request = httpx.Request("POST", "https://gemini.test")
    response = httpx.Response(code, request=request, json=body or {})
    return httpx.HTTPStatusError(str(code), request=request, response=response)


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
    g2 = fetcher(fetch=AsyncMock(return_value=pdf(b"%PDF-b")))
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
        fetch=AsyncMock(return_value=pdf(b"%PDF-new")),
        extract=MagicMock(return_value=data(total=4)),
    )
    run(conn, f2, t1, tmp_path)
    # 추출은 확인 뒤에 하므로 이번 확인의 다음 주기는 10분, 그다음부터 하루
    assert source(conn, "GUIDE_PAGE")["next_check_at"] == t1 + timedelta(minutes=10)
    t1 += timedelta(minutes=10)
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
    f = fetcher(fetch=AsyncMock(side_effect=TimeoutError("timeout")))
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


@pytest.mark.parametrize("exc", [gemini_error(503), httpx.ReadTimeout("timeout")])
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


# --- 1) 429 = QUOTA_WAIT ---------------------------------------------------------


def quota_429(delay=None):
    details = [{"retryDelay": delay}] if delay else []
    return gemini_error(429, {"error": {"code": 429, "details": details}})


def two_posts(conn):
    only(conn, "NOTICE_BOARD")
    posts = [
        Link("2027학년도 편입학 모집요강", "https://univ.test/n/1"),
        Link("2027학년도 편입학 모집요강(학사)", "https://univ.test/n/2"),
    ]
    pdfs = {posts[0].url: pdf(b"%PDF-1"), posts[1].url: pdf(b"%PDF-2")}
    return posts, AsyncMock(side_effect=lambda url: pdfs[url])


def test_quota_429_waits_without_counting_and_alerts_once_a_day(
    conn, tmp_path, monkeypatch, webhook
):
    monkeypatch.setenv("ALERT_WEBHOOK_URL", FAKE_WEBHOOK)
    load_seed(conn, FIXTURE)
    posts, download = two_posts(conn)
    f = fetcher(
        posts, download=download, extract=MagicMock(side_effect=quota_429("1800s"))
    )
    run(conn, f, T0, tmp_path)
    assert (
        f.extract.call_count == 1
    )  # 할당량 소진 뒤 같은 주기의 남은 문서는 호출 안 함
    docs = conn.execute(
        "SELECT status, retry_count, retry_after FROM admission_documents ORDER BY id"
    ).fetchall()
    assert [(d["status"], d["retry_count"]) for d in docs] == [("QUOTA_WAIT", 0)] * 2
    assert {d["retry_after"] for d in docs} == {T0 + timedelta(seconds=1800)}
    assert [a["kind"] for a in alerts(conn)] == ["QUOTA_WAIT"] and len(
        webhook.sent
    ) == 1

    run(conn, f, T0 + timedelta(minutes=10), tmp_path)  # 재개 시각 전: 호출 없음
    assert f.extract.call_count == 1
    run(
        conn, f, T0 + timedelta(minutes=30), tmp_path
    )  # 재개 후 다시 429: 알림은 하루 1회
    assert f.extract.call_count == 2 and len(alerts(conn)) == 1
    for _ in range(10):  # 429는 몇 번이 와도 FAILED로 가지 않는다
        now = conn.execute(
            "SELECT max(retry_after) AS t FROM admission_documents"
        ).fetchone()["t"]
        run(conn, f, now + timedelta(minutes=10), tmp_path)
    assert {
        d["status"] for d in conn.execute("SELECT status FROM admission_documents")
    } == {"QUOTA_WAIT"}
    assert (
        conn.execute(
            "SELECT max(retry_count) AS m FROM admission_documents"
        ).fetchone()["m"]
        == 0
    )
    run(conn, f, T0 + timedelta(days=2), tmp_path)
    assert len(alerts(conn)) == 2  # 하루가 지나면 다시 알림

    ok = fetcher(posts, download=download)
    run(conn, ok, T0 + timedelta(days=3), tmp_path)
    assert {
        d["status"] for d in conn.execute("SELECT status FROM admission_documents")
    } == {"EXTRACTED"}


def test_quota_without_retry_delay_waits_default(conn, tmp_path):
    load_seed(conn, FIXTURE)
    only(conn, "GUIDE_PAGE")
    run(conn, fetcher(extract=MagicMock(side_effect=quota_429())), T0, tmp_path)
    assert (
        conn.execute("SELECT retry_after FROM admission_documents").fetchone()[
            "retry_after"
        ]
        == T0 + core.QUOTA_DEFAULT_WAIT
    )


# --- 2) 게시글 단위 예외 격리 ----------------------------------------------------


def test_one_link_failure_does_not_fail_the_board(conn, tmp_path):
    load_seed(conn, FIXTURE)
    posts, ok_download = two_posts(conn)

    async def download(url):
        if url == posts[0].url:
            raise httpx.ReadTimeout("timeout")
        return await ok_download(url)

    f = fetcher(posts, download=AsyncMock(side_effect=download))
    run(conn, f, T0, tmp_path)
    src = source(conn, "NOTICE_BOARD")
    assert src["consecutive_failures"] == 0 and src["last_error"] is None
    assert doc(conn)["status"] == "EXTRACTED"  # 나머지 링크는 계속 처리

    def item():
        return conn.execute(
            "SELECT status, failures FROM crawl_seen_items WHERE url = %s",
            (posts[0].url,),
        ).fetchone()

    assert item() == {"status": "RETRY", "failures": 1}
    run(conn, f, T0 + timedelta(days=1), tmp_path)
    assert item() == {"status": "RETRY", "failures": 2} and not alerts(conn)
    run(conn, f, T0 + timedelta(days=2), tmp_path)
    assert item() == {"status": "NEEDS_REVIEW", "failures": 3}
    assert [a["kind"] for a in alerts(conn)] == ["LINK_FAILED"]
    calls = f.download.await_count
    run(conn, f, T0 + timedelta(days=3), tmp_path)
    assert f.download.await_count == calls  # 운영자에게 넘긴 글은 다시 받지 않는다


def test_board_access_failure_keeps_source_backoff(conn, tmp_path):
    load_seed(conn, FIXTURE)
    only(conn, "NOTICE_BOARD")
    f = fetcher(links=())
    f.links = AsyncMock(side_effect=httpx.ConnectError("down"))
    run(conn, f, T0, tmp_path)
    src = source(conn, "NOTICE_BOARD")
    assert src["consecutive_failures"] == 1
    assert src["next_check_at"] == T0 + timedelta(minutes=1)


# --- 3) 학년도 사전 필터 ---------------------------------------------------------


def test_wants_gemini_rules():
    assert core.wants_gemini(YEAR, "2027학년도 편입학 모집요강", [], None)
    assert not core.wants_gemini(
        YEAR, "PDF 다운로드", ["2026학년도 편입학 모집요강.pdf"], None
    )
    assert not core.wants_gemini(
        YEAR, "편입학 모집요강", ["a.pdf"], "2026학년도 편입학 모집요강"
    )
    assert core.wants_gemini(
        YEAR, "편입학 모집요강", ["a.pdf"], "2027학년도 (2026. 12.)"
    )
    assert core.wants_gemini(
        YEAR, "편입학 모집요강", ["20251209144846ZQ25EU.pdf"], None
    )
    # 첫 페이지의 날짜는 학년도가 아니다(대상 학년도 요강을 놓치지 않도록)
    assert core.wants_gemini(
        YEAR, "편입학 모집요강", ["a.pdf"], "원서접수 2026. 12. 1."
    )


def test_first_page_text(make_pdf):
    assert "Transfer 2027" in core.first_page_text(make_pdf("Transfer 2027"))
    assert core.first_page_text(b"%PDF-broken") is None  # 스캔본·깨진 파일 = 판단 불가


def test_other_year_pdf_skips_gemini_and_is_remembered(conn, tmp_path, monkeypatch):
    load_seed(conn, FIXTURE)
    only(conn, "NOTICE_BOARD")
    monkeypatch.setattr(
        core, "first_page_text", lambda content: "2026학년도 편입학 모집요강"
    )
    posts = [
        Link("편입학 모집요강 안내", "https://univ.test/n/1"),
        Link("편입학 모집요강 (재게시)", "https://univ.test/n/2"),
    ]
    f = fetcher(posts)
    run(conn, f, T0, tmp_path)
    f.extract.assert_not_called()
    assert not conn.execute("SELECT 1 FROM admission_documents").fetchone()
    rows = conn.execute("SELECT status, file_sha256 FROM crawl_seen_items").fetchall()
    sha = hashlib.sha256(b"%PDF-a").hexdigest()
    assert [(r["status"], r["file_sha256"]) for r in rows] == [("DONE", sha)] * 2
    run(conn, f, T0 + timedelta(days=1), tmp_path)
    assert f.download.await_count == 2  # 본 글은 다시 받지 않는다

    # 요강 고정 페이지: 학년도 없는 링크의 2026 PDF는 content_hash만 기록, Gemini 없음
    only(conn, "GUIDE_PAGE")
    g = fetcher([Link("편입학 모집요강.pdf", "https://univ.test/up/x.pdf")])
    g.fetch = AsyncMock(return_value=pdf(b"%PDF-g"))
    run(conn, g, T0, tmp_path)
    run(conn, g, T0 + timedelta(minutes=10), tmp_path)
    g.extract.assert_not_called()
    assert (
        source(conn, "GUIDE_PAGE")["content_hash"]
        == hashlib.sha256(b"%PDF-g").hexdigest()
    )


def test_unreadable_pdf_calls_gemini(conn, tmp_path, monkeypatch):
    load_seed(conn, FIXTURE)
    only(conn, "GUIDE_PAGE")
    monkeypatch.setattr(core, "first_page_text", lambda content: None)
    f = fetcher([Link("편입학 모집요강.pdf", "https://univ.test/up/x.pdf")])
    run(conn, f, T0, tmp_path)
    f.extract.assert_called_once()


# --- 4) 확인 먼저, 추출은 나중에 -------------------------------------------------


def test_all_sources_checked_before_extraction(conn, tmp_path):
    load_seed(conn, FIXTURE)
    conn.execute("UPDATE crawl_sources SET enabled = url LIKE 'https://univ.test/%%'")
    order = []
    post = Link("2027학년도 편입학 모집요강", "https://univ.test/n/1")

    async def links(url):
        order.append(f"links {url}")
        return [post] if url.endswith("notice") else [GUIDE_LINK]

    async def download(url):
        order.append("download")
        return pdf(b"%PDF-n")

    async def fetch(url, referer):
        order.append("fetch")
        return pdf(b"%PDF-g")

    def extract(content):
        order.append("extract")
        return data()

    f = SimpleNamespace(
        links=links, download=download, fetch=fetch, extract=extract, model="mock"
    )
    run(conn, f, T0, tmp_path)
    assert order.count("extract") == 2
    assert order.index("extract") > max(
        i for i, step in enumerate(order) if step != "extract"
    )


def test_requests_today_logged(conn, tmp_path, caplog):
    load_seed(conn, FIXTURE)
    only(conn, "GUIDE_PAGE")
    with caplog.at_level("INFO"):
        run(conn, fetcher(), datetime.now(UTC) + timedelta(seconds=1), tmp_path)
    assert "오늘 Gemini 요청 1건" in caplog.text


# --- 5) 학교 대조 ----------------------------------------------------------------


def test_school_matches_exact_and_campus():
    assert core.school_matches("서강 대학교", "서강대학교", "", None)
    assert not core.school_matches("서울대학교", "서울대학교병원", "", None)
    assert not core.school_matches("한양대학교 ERICA", "한양대학교", "", None)
    assert not core.school_matches("연세대학교", "연세대학교", "서울", None)
    assert core.school_matches("연세대학교 서울캠퍼스", "연세대학교", "서울", None)
    assert core.school_matches(
        "연세대학교", "연세대학교", "서울", "연세대학교 서울캠퍼스 편입학"
    )
    assert not core.school_matches("연세대학교 미래캠퍼스", "연세대학교", "서울", None)
    assert not core.school_matches(None, "연세대학교", "", None)


def test_school_mismatch_needs_review(conn, tmp_path):
    load_seed(conn, FIXTURE)
    only(conn, "GUIDE_PAGE")
    wrong = {
        **data(),
        "학교": "테스트대학교 제2캠퍼스",
    }  # 부분 일치는 더 이상 통과하지 않는다
    run(conn, fetcher(extract=MagicMock(return_value=wrong)), T0, tmp_path)
    assert doc(conn)["status"] == "NEEDS_REVIEW"
    assert conn.execute("SELECT error FROM extraction_runs").fetchone()["error"] == (
        "학교 또는 캠퍼스 불일치"
    )


def test_wrong_document_is_failed(conn, tmp_path):
    load_seed(conn, FIXTURE)
    only(conn, "GUIDE_PAGE")
    wrong = {**data(), "문서종류": "잘못된 문서"}
    run(conn, fetcher(extract=MagicMock(return_value=wrong)), T0, tmp_path)
    assert doc(conn)["status"] == "FAILED"


# --- 6) 요강 고정 페이지 후보 PDF -------------------------------------------------


def q(text: str, encoding="utf-8") -> str:
    return quote(text.encode(encoding))


SOGANG_LIKE = [  # 2026-11-08 서강·연세 요강 페이지 링크 구조
    Link(
        "Embedded PDF",
        "https://u.test/viewer.html?file=%2Fup%2F20251209144846ZQ25EU.pdf",
    ),
    Link("PDF 확대보기", "https://u.test/up/20251209144846ZQ25EU.pdf"),
    Link(
        "PDF 다운로드",
        "https://u.test/dl.asp?real=/up/1.pdf&name="
        + q("2026학년도 편입학 모집요강.pdf"),
    ),
    Link(
        "",
        "https://u.test/dl.asp?furl=g/2.HWP&fname="
        + q("2026학년도 편입학전형 모집요강.hwp", "cp949"),
    ),
]


def test_guide_candidates():
    assert core.guide_candidates(SOGANG_LIKE, YEAR) == ([], 1)  # 지난 요강만 있음
    new = Link(
        "",
        "https://u.test/dl.asp?furl=g/3.PDF&fname="
        + q("2027학년도 편입학전형 모집요강.pdf", "cp949"),
    )
    plan = Link("2027학년도 편입학 모집 기본계획.pdf", "https://u.test/plan.pdf")
    assert core.guide_candidates([*SOGANG_LIKE, new, plan], YEAR) == ([new], 1)
    no_year = Link("편입학 모집요강 다운로드", "https://u.test/guide.pdf")
    assert core.guide_candidates([no_year], YEAR) == ([no_year], 0)
    assert core.guide_candidates([Link("PDF", "https://u.test/x.pdf")], YEAR) == ([], 0)


def test_guide_page_review_on_zero_or_many_candidates(conn, tmp_path):
    load_seed(conn, FIXTURE)
    only(conn, "GUIDE_PAGE")
    stale = fetcher(SOGANG_LIKE)
    run(conn, stale, T0, tmp_path)  # 지난 요강만: 정상 대기, 다운로드·알림 없음
    stale.fetch.assert_not_awaited()
    assert source(conn, "GUIDE_PAGE")["review_reason"] is None and not alerts(conn)

    none = fetcher([Link("PDF", "https://u.test/x.pdf")])
    run(conn, none, T0 + timedelta(minutes=10), tmp_path)
    run(conn, none, T0 + timedelta(minutes=20), tmp_path)
    assert source(conn, "GUIDE_PAGE")["review_reason"].startswith("요강 후보 PDF 0개")
    assert [a["kind"] for a in alerts(conn)] == ["GUIDE_REVIEW"]  # 같은 사유는 한 번만

    two = fetcher(
        [GUIDE_LINK, Link("2027 편입학 모집요강.pdf", "https://u.test/b.pdf")]
    )
    run(conn, two, T0 + timedelta(minutes=30), tmp_path)
    two.fetch.assert_not_awaited()
    assert source(conn, "GUIDE_PAGE")["review_reason"].startswith("요강 후보 PDF 2개")
    assert len(alerts(conn)) == 2

    one = fetcher()
    run(conn, one, T0 + timedelta(minutes=40), tmp_path)
    one.fetch.assert_awaited_once_with(GUIDE_LINK.url, "https://univ.test/guide")
    assert source(conn, "GUIDE_PAGE")["review_reason"] is None
    assert doc(conn)["status"] == "EXTRACTED"


# --- 모델 기본값 ------------------------------------------------------------------


def test_monitor_uses_single_default_model():
    from pyeonit_crawler.gemini import DEFAULT_MODEL
    from pyeonit_crawler.monitor.__main__ import BrowserFetcher, build_parser

    model = build_parser().parse_args([]).model
    assert model == DEFAULT_MODEL == "gemini-3.8-flash"
    assert BrowserFetcher(None, YEAR).model == DEFAULT_MODEL


@pytest.mark.parametrize(
    "label", ["연세대학교병원", "연세대학교병원 서울", "연세대학교 미래캠퍼스"]
)
def test_campus_does_not_allow_school_name_suffix(label):
    assert not core.school_matches(label, "연세대학교", "서울", "서울캠퍼스")


@pytest.mark.parametrize("violations", [[], [{"quotaId": "RequestsPerDay"}]])
def test_monitor_429_never_retries_inside_extractor(
    conn, tmp_path, monkeypatch, violations
):
    from pyeonit_crawler.monitor.__main__ import BrowserFetcher

    calls = []
    real_client = httpx.Client

    def handler(request):
        calls.append(request)
        return httpx.Response(
            429,
            json={
                "error": {
                    "details": [
                        {"violations": violations},
                        {"retryDelay": "30s"},
                    ]
                }
            },
        )

    def client(**kwargs):
        return real_client(transport=httpx.MockTransport(handler), **kwargs)

    monkeypatch.setenv("GEMINI_API_KEY", "test-key")
    monkeypatch.setattr(httpx, "Client", client)
    load_seed(conn, FIXTURE)
    only(conn, "GUIDE_PAGE")
    f = fetcher()
    browser_fetcher = BrowserFetcher(None, YEAR)
    f.extract = browser_fetcher.extract
    run(conn, f, T0, tmp_path)
    assert len(calls) == 1
    assert calls[0].url.path.endswith("/models/gemini-3.8-flash:generateContent")
    row = conn.execute(
        "SELECT status, retry_count, retry_after FROM admission_documents"
    ).fetchone()
    assert row == {
        "status": "QUOTA_WAIT",
        "retry_count": 0,
        "retry_after": T0 + timedelta(seconds=30),
    }


def test_queue_failure_is_isolated_per_item(conn, tmp_path, monkeypatch):
    load_seed(conn, FIXTURE)
    posts, download = two_posts(conn)
    queue = core._queue

    async def failing_queue(*args):
        if args[5] == posts[0].url:
            raise OSError("storage unavailable")
        return await queue(*args)

    monkeypatch.setattr(core, "_queue", failing_queue)
    f = fetcher(posts, download=download)
    for day in range(3):
        run(conn, f, T0 + timedelta(days=day), tmp_path)
    row = conn.execute(
        "SELECT status, failures FROM crawl_seen_items WHERE url = %s", (posts[0].url,)
    ).fetchone()
    assert row == {"status": "NEEDS_REVIEW", "failures": 3}
    assert doc(conn)["status"] == "EXTRACTED"
    assert source(conn, "NOTICE_BOARD")["consecutive_failures"] == 0


def test_queue_failure_can_recover_without_hash_dedup_skip(conn, tmp_path, monkeypatch):
    load_seed(conn, FIXTURE)
    posts, download = two_posts(conn)
    queue = core._queue

    async def fail(*args):
        raise OSError("temporary storage failure")

    monkeypatch.setattr(core, "_queue", fail)
    f = fetcher(posts, download=download)
    run(conn, f, T0, tmp_path)
    rows = conn.execute("SELECT status, file_sha256 FROM crawl_seen_items").fetchall()
    assert rows == [{"status": "RETRY", "file_sha256": None}] * 2
    monkeypatch.setattr(core, "_queue", queue)
    run(conn, f, T0 + timedelta(minutes=10), tmp_path)
    assert f.extract.call_count == 2
    assert {
        r["status"] for r in conn.execute("SELECT status FROM admission_documents")
    } == {"EXTRACTED"}
