import asyncio
import hashlib

import pytest
from playwright.async_api import async_playwright

from pyeonit_crawler.cli import SCHOOLS
from pyeonit_crawler.detection import Link
from pyeonit_crawler.download import download_pdf, pdf_candidates


def test_pdf_candidates_decode_viewer_and_prioritize_year():
    links = [
        Link("2027 시행계획", "https://example.test/plan.pdf"),
        Link("PDF 확대보기", "https://example.test/upload/guide/abc.PDF"),
        Link(
            "PDF 다운로드", "https://example.test/download?fname=2026편입모집요강.pdf"
        ),
        Link(
            "",
            "https://example.test/viewer.html?file=%2Fupload%2Fguide%2Fabc.PDF#page=1",
        ),
        Link("HWP", "https://example.test/guide.hwp"),
    ]
    assert pdf_candidates(links, 2026) == [
        "https://example.test/download?fname=2026편입모집요강.pdf",
        "https://example.test/upload/guide/abc.PDF",
    ]


@pytest.mark.network
@pytest.mark.parametrize("slug", SCHOOLS)
def test_live_pdf_download(slug):
    async def run():
        async with async_playwright() as pw:
            browser = await pw.chromium.launch()
            try:
                context = await browser.new_context()
                pdf = await download_pdf(context, SCHOOLS[slug][1])
                assert pdf.content.startswith(b"%PDF-")
                assert pdf.sha256 == hashlib.sha256(pdf.content).hexdigest()
            finally:
                await browser.close()

    asyncio.run(run())


def test_notice_links_download_and_hash_without_network():
    from unittest.mock import AsyncMock, MagicMock

    pdf_content = b"%PDF-1.7\nfixture"
    frame = MagicMock()
    frame.url = "https://example.test/notice/2026"
    frame.eval_on_selector_all = AsyncMock(
        side_effect=[
            [
                {
                    "title": "2026 편입학 모집요강",
                    "url": "https://example.test/notice/2026",
                    "onclick": "",
                },
                {
                    "title": "2026 편입학 모집요강 예고",
                    "url": "https://example.test/plan",
                    "onclick": "",
                },
            ],
            [
                {
                    "title": "첨부 PDF",
                    "url": "https://example.test/attachment.pdf",
                    "onclick": "",
                }
            ],
        ]
    )
    page = MagicMock()
    page.frames = [frame]
    page.url = "https://example.test/notices"
    page.goto = AsyncMock()
    page.close = AsyncMock()
    response = MagicMock()
    response.ok = True
    response.url = "https://example.test/attachment.pdf"
    response.body = AsyncMock(return_value=pdf_content)
    context = MagicMock()
    context.new_page = AsyncMock(return_value=page)
    context.request.get = AsyncMock(return_value=response)
    result = asyncio.run(download_pdf(context, page.url))
    assert result.sha256 == hashlib.sha256(pdf_content).hexdigest()
    assert result.content == pdf_content
    assert [call.args[0] for call in page.goto.call_args_list] == [
        "https://example.test/notices",
        "https://example.test/notice/2026",
    ]
    page.close.assert_awaited_once()


def test_non_pdf_response_is_rejected_and_page_closed():
    from unittest.mock import AsyncMock, MagicMock

    frame = MagicMock()
    frame.eval_on_selector_all = AsyncMock(
        return_value=[
            {"title": "PDF", "url": "https://example.test/guide.pdf", "onclick": ""},
        ]
    )
    page = MagicMock()
    page.frames = [frame]
    page.goto = AsyncMock()
    page.close = AsyncMock()
    page.url = "https://example.test/guide"
    response = MagicMock()
    response.ok = True
    response.body = AsyncMock(return_value=b"<html>error</html>")
    context = MagicMock()
    context.new_page = AsyncMock(return_value=page)
    context.request.get = AsyncMock(return_value=response)
    with pytest.raises(ValueError, match="PDF"):
        asyncio.run(download_pdf(context, page.url))
    page.close.assert_awaited_once()


def test_extensionless_pdf_attachment():
    assert pdf_candidates(
        [
            Link("2026 편입 모집요강.pdf", "https://example.test/download?id=123"),
            Link("PDF Viewer", "https://adobe.example/reader"),
        ],
        2026,
    ) == ["https://example.test/download?id=123"]


def test_download_tries_next_attachment_after_request_error(monkeypatch):
    from unittest.mock import AsyncMock, MagicMock

    from playwright.async_api import Error

    from pyeonit_crawler import download

    page = MagicMock()
    page.url = "https://example.test/notice"
    page.goto = AsyncMock()
    page.close = AsyncMock()
    monkeypatch.setattr(
        download,
        "page_links",
        AsyncMock(
            return_value=[
                Link("2026 PDF", "https://example.test/broken.pdf"),
                Link("PDF", "https://example.test/working.pdf"),
            ]
        ),
    )
    response = MagicMock()
    response.ok = True
    response.url = "https://example.test/working.pdf"
    response.body = AsyncMock(return_value=b"%PDF-fixture")
    context = MagicMock()
    context.new_page = AsyncMock(return_value=page)
    context.request.get = AsyncMock(side_effect=[Error("connection failed"), response])
    assert asyncio.run(download.download_pdf(context, page.url)).url == response.url
    assert context.request.get.await_count == 2


@pytest.mark.parametrize(
    "url,expected_exception",
    [
        ("https://admission.sogang.ac.kr/guide.pdf", True),
        ("https://admission.skku.edu/guide.pdf", False),
        ("https://admission.yonsei.ac.kr/guide.pdf", False),
        ("https://generativelanguage.googleapis.com/guide.pdf", False),
        ("https://admission.sogang.ac.kr.evil.test/guide.pdf", False),
        ("http://admission.sogang.ac.kr/guide.pdf", False),
    ],
)
def test_tls_exception_only_for_allowlisted_https_host(url, expected_exception):
    from unittest.mock import AsyncMock, MagicMock

    from pyeonit_crawler.download import fetch_attachment

    response = MagicMock(status=200)
    context = MagicMock()
    context.request.get = AsyncMock(return_value=response)
    assert (
        asyncio.run(fetch_attachment(context, url, "https://example.test/")) is response
    )
    kwargs = context.request.get.call_args.kwargs
    assert kwargs["ignore_https_errors"] is expected_exception
    assert kwargs["max_redirects"] == 0


def test_redirect_outside_allowlist_restores_certificate_validation():
    from unittest.mock import AsyncMock, MagicMock

    from pyeonit_crawler.download import fetch_attachment

    redirect = MagicMock(
        status=302, headers={"location": "https://outside.example/guide.pdf"}
    )
    response = MagicMock(status=200)
    context = MagicMock()
    context.request.get = AsyncMock(side_effect=[redirect, response])
    assert (
        asyncio.run(
            fetch_attachment(
                context,
                "https://admission.sogang.ac.kr/guide.pdf",
                "https://example.test/",
            )
        )
        is response
    )
    calls = context.request.get.call_args_list
    assert [call.kwargs["ignore_https_errors"] for call in calls] == [True, False]
    assert calls[1].args[0] == "https://outside.example/guide.pdf"
    assert all(call.kwargs["max_redirects"] == 0 for call in calls)
