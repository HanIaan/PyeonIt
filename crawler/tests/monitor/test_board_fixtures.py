"""실제 공지 게시판 HTML(fixtures/) + 가짜 2027 요강 글 1줄로 감지→다운로드→중복 방지 전체 흐름.

로컬 HTTP 서버와 Playwright Chromium만 쓴다. 외부 요청은 모두 차단, Gemini는 mock.
"""

import asyncio
import threading
from datetime import UTC, datetime, timedelta
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import MagicMock
from urllib.parse import urlsplit

import pytest
from playwright.async_api import async_playwright

from pyeonit_crawler.monitor.__main__ import BrowserFetcher
from pyeonit_crawler.monitor.core import run_once

FIXTURES = Path(__file__).with_name("fixtures")
YEAR = 2027
FAKE_TITLE = "2027학년도 편입학 모집요강"
SCHOOLS = [  # (fixture, 대학, 캠퍼스, 가짜 글을 끼울 목록 시작 태그, 한 줄 형식)
    ("sogang_notice.html", "서강대학교", "", '<ul class="boardList">', "<li>{}</li>"),
    ("skku_notice.html", "성균관대학교", "", "<tbody>", "<tr><td>{}</td></tr>"),
    ("yonsei_notice.html", "연세대학교", "서울", "<tbody>", "<tr><td>{}</td></tr>"),
]


class QuietHandler(SimpleHTTPRequestHandler):
    # fixture는 meta 태그를 제거했으므로 charset을 헤더로 준다
    extensions_map = {".html": "text/html; charset=utf-8", ".pdf": "application/pdf"}

    def log_message(self, *args):
        pass


@pytest.fixture
def site(tmp_path, make_pdf):
    root = tmp_path / "site"
    (root / "post").mkdir(parents=True)
    (root / "files").mkdir()
    (root / "files" / "guide2027.pdf").write_bytes(make_pdf("Transfer guide"))
    (root / "post" / "2027.html").write_text(
        f'<html><body><h1>{FAKE_TITLE}</h1><a href="/files/guide2027.pdf">'
        f"{FAKE_TITLE}.pdf</a></body></html>",
        encoding="utf-8",
    )
    server = ThreadingHTTPServer(
        ("127.0.0.1", 0), partial(QuietHandler, directory=root)
    )
    threading.Thread(target=server.serve_forever, daemon=True).start()
    yield root, f"http://127.0.0.1:{server.server_port}"
    server.shutdown()


async def block_external(route):
    if urlsplit(route.request.url).hostname == "127.0.0.1":
        await route.continue_()
    else:
        await route.abort()


@pytest.mark.parametrize("fixture,name,campus,marker,row", SCHOOLS)
def test_real_board_html_detects_new_guide_once(
    conn, tmp_path, site, fixture, name, campus, marker, row
):
    root, base = site
    html = (FIXTURES / fixture).read_text(encoding="utf-8")
    assert marker in html
    fake = row.format(f'<a href="/post/2027.html">{FAKE_TITLE}</a>')
    (root / "board.html").write_text(
        html.replace(marker, marker + fake, 1), encoding="utf-8"
    )

    uid = conn.execute(
        "INSERT INTO universities (name, campus, region) VALUES (%s, %s, 'SEOUL') RETURNING id",
        (name, campus),
    ).fetchone()["id"]
    conn.execute(
        "INSERT INTO crawl_sources (university_id, kind, url) VALUES (%s, 'NOTICE_BOARD', %s)",
        (uid, f"{base}/board.html"),
    )
    cell = {"전형": "일반", "인원": 3, "원문": "3"}
    extracted = {
        "학교": f"{name} {campus}캠퍼스" if campus else name,
        "학년도": YEAR,
        "문서종류": "편입학 모집요강",
        "모집인원": [
            {"계열": "인문", "단위": "학과", "모집단위": "국문", "인원": [cell]}
        ],
        "소계": [],
        "총계": [cell],
        "일정": [],
    }
    extract = MagicMock(return_value=extracted)
    t0 = datetime.now(UTC) + timedelta(minutes=1)

    async def go():
        async with async_playwright() as pw:
            try:
                browser = await pw.chromium.launch()
            except Exception as exc:
                pytest.skip(f"Chromium 필요: playwright install chromium ({exc})")
            try:
                context = await browser.new_context()
                await context.route("**/*", block_external)
                fetcher = BrowserFetcher(context, YEAR, "mock")
                fetcher._extractor = SimpleExtractor(extract)
                for now in (t0, t0 + timedelta(days=2)):
                    await run_once(
                        conn, fetcher, year=YEAR, storage_dir=tmp_path / "pdfs", now=now
                    )
            finally:
                await browser.close()

    asyncio.run(go())
    extract.assert_called_once()  # 2회차는 재분석 없음
    docs = conn.execute("SELECT status, file_url FROM admission_documents").fetchall()
    assert docs == [{"status": "EXTRACTED", "file_url": f"{base}/files/guide2027.pdf"}]
    item = conn.execute(
        "SELECT status, file_sha256 FROM crawl_seen_items WHERE url = %s",
        (f"{base}/post/2027.html",),
    ).fetchone()
    assert item["status"] == "DONE" and item["file_sha256"]
    runs = conn.execute("SELECT status FROM crawl_runs ORDER BY id").fetchall()
    assert [r["status"] for r in runs] == ["SUCCESS", "SUCCESS"]


class SimpleExtractor:
    def __init__(self, extract):
        self.extract = extract
