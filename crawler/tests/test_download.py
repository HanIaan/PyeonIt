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
        ("https://admission.gachon.ac.kr/guide.pdf", True),
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


def test_http_error_page_is_failure_not_no_guide():
    from unittest.mock import AsyncMock, MagicMock

    from pyeonit_crawler.download import NoGuideError

    page = MagicMock()
    page.goto = AsyncMock(return_value=MagicMock(status=404))
    page.close = AsyncMock()
    context = MagicMock()
    context.new_page = AsyncMock(return_value=page)
    with pytest.raises(ValueError, match="HTTP 404") as error:
        asyncio.run(download_pdf(context, "https://example.test/missing"))
    assert not isinstance(error.value, NoGuideError)
    page.close.assert_awaited_once()


def test_extensionless_pdfjs_resource_and_protocol_relative_url():
    assert pdf_candidates(
        [
            Link(
                "",
                "https://example.test/pdfJS/web/viewer.html?file=%2F%2Fexample.test%2Fajax%2FFileView.do%3Fid%3D1",
            ),
            Link("PDF 다운받기", "https://example.test/cms/etcResourceDown.do?key=123"),
        ],
        2026,
    ) == [
        "https://example.test/ajax/FileView.do?id=1",
        "https://example.test/cms/etcResourceDown.do?key=123",
    ]


def test_guide_viewer_beats_unrelated_upload_timestamp():
    assert (
        pdf_candidates(
            [
                Link("PDF", "https://example.test/upload/info/20260917113840.PDF"),
                Link(
                    "",
                    "https://example.test/pdfViewer/web/viewer.html?file=/upload/20251128084620.PDF",
                ),
                Link("2027학년도 정시 PDF", "https://example.test/2027jungsi.pdf"),
            ],
            2026,
        )[0]
        == "https://example.test/upload/20251128084620.PDF"
    )


def test_detached_child_frame_preserves_main_document_links():
    from unittest.mock import AsyncMock, MagicMock

    from playwright.async_api import Error

    from pyeonit_crawler.download import page_links

    main, child = MagicMock(), MagicMock()
    main.eval_on_selector_all = AsyncMock(
        return_value=[
            {"title": "PDF", "url": "https://example.test/guide.pdf", "onclick": ""}
        ]
    )
    child.eval_on_selector_all = AsyncMock(side_effect=Error("detached"))
    page = MagicMock(frames=[main, child], main_frame=main)
    assert asyncio.run(page_links(page)) == [
        Link("PDF", "https://example.test/guide.pdf")
    ]


def test_js_only_pdf_download_button(tmp_path):
    from unittest.mock import AsyncMock, MagicMock

    from pyeonit_crawler.download import click_pdf_download

    content = b"%PDF-js-download"
    path = tmp_path / "download.pdf"
    path.write_bytes(content)
    download = MagicMock(url="https://example.test/file?id=123")
    download.path = AsyncMock(return_value=path)
    pending = MagicMock()

    async def downloaded():
        return download

    async def run():
        pending.value = downloaded()
        manager = MagicMock()
        manager.__aenter__ = AsyncMock(return_value=pending)
        manager.__aexit__ = AsyncMock(return_value=False)
        button = MagicMock()
        button.inner_text = AsyncMock(return_value="PDF 다운로드")
        button.click = AsyncMock()
        buttons = MagicMock()
        buttons.count = AsyncMock(return_value=1)
        buttons.nth.return_value = button
        page = MagicMock()
        page.locator.return_value.filter.return_value = buttons
        page.expect_download.return_value = manager
        result = await click_pdf_download(page, 2026)
        assert result.content == content
        assert result.sha256 == hashlib.sha256(content).hexdigest()
        button.click.assert_awaited_once()

    asyncio.run(run())
