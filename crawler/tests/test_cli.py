import asyncio
import hashlib
import json
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock

import httpx
import pytest

from pyeonit_crawler import cli
from pyeonit_crawler.download import DownloadedPDF


@pytest.fixture
def pipeline(monkeypatch, tmp_path):
    content = b"%PDF-fixture"
    pdf = DownloadedPDF(
        "https://example.test/guide.pdf", content, hashlib.sha256(content).hexdigest()
    )
    browser = MagicMock()
    browser.new_context = AsyncMock()
    browser.close = AsyncMock()
    pw = MagicMock()
    pw.chromium.launch = AsyncMock(return_value=browser)
    manager = MagicMock()
    manager.__aenter__ = AsyncMock(return_value=pw)
    manager.__aexit__ = AsyncMock(return_value=False)
    monkeypatch.setattr(cli, "async_playwright", lambda: manager)
    monkeypatch.setattr(cli, "download_pdf", AsyncMock(return_value=pdf))
    monkeypatch.setattr(cli, "load_api_key", lambda _: "secret-placeholder")
    args = SimpleNamespace(
        output=tmp_path,
        env_file=None,
        model="mock",
        schools=["sogang"],
        year=2026,
    )
    return args, browser


def extraction(school="서강대학교", year=2026, total=3):
    return {
        "학교": school,
        "학년도": year,
        "모집인원": [
            {
                "계열": "인문",
                "단위": "학과",
                "모집단위": "국문",
                "인원": [{"전형": "일반", "인원": 3, "원문": "3"}],
            }
        ],
        "총계": [{"전형": "일반", "인원": total, "원문": str(total)}],
        "일정": [],
    }


@pytest.mark.parametrize(
    "total,expected_exit,expected_status", [(3, 0, "passed"), (4, 1, "failed")]
)
def test_backfill_persists_extraction_and_validation(
    monkeypatch, pipeline, total, expected_exit, expected_status
):
    args, browser = pipeline
    monkeypatch.setattr(
        cli,
        "GeminiExtractor",
        lambda *_: SimpleNamespace(extract=lambda _: extraction(total=total)),
    )
    assert asyncio.run(cli.backfill(args)) == expected_exit
    data = json.loads((args.output / "sogang-2026.json").read_text())
    assert data["validation"]["status"] == expected_status
    assert data["schema_version"] == 2
    assert (
        data["source"]["sha256"]
        == hashlib.sha256((args.output / "sogang-2026.pdf").read_bytes()).hexdigest()
    )
    assert (
        json.loads((args.output / "summary.json").read_text())["sogang"]["status"]
        == expected_status
    )
    browser.close.assert_awaited_once()
    browser.new_context.assert_awaited_once_with()


def test_wrong_year_is_rejected(monkeypatch, pipeline):
    args, _ = pipeline
    monkeypatch.setattr(
        cli,
        "GeminiExtractor",
        lambda *_: SimpleNamespace(extract=lambda _: extraction(year=2027)),
    )
    assert asyncio.run(cli.backfill(args)) == 1
    assert not (args.output / "sogang-2026.json").exists()
    assert (
        json.loads((args.output / "summary.json").read_text())["sogang"]["status"]
        == "error"
    )


def test_school_error_isolated_and_secrets_not_logged(monkeypatch, pipeline, capsys):
    args, _ = pipeline
    args.schools = ["sogang", "skku"]
    calls = []

    def extract(_):
        calls.append(1)
        if len(calls) == 1:
            response = httpx.Response(
                403, request=httpx.Request("POST", "https://example.test/api")
            )
            raise httpx.HTTPStatusError(
                "secret-placeholder", request=response.request, response=response
            )
        return extraction(school="성균관대학교")

    monkeypatch.setattr(
        cli, "GeminiExtractor", lambda *_: SimpleNamespace(extract=extract)
    )
    assert asyncio.run(cli.backfill(args)) == 1
    summary = json.loads((args.output / "summary.json").read_text())
    assert summary["sogang"]["http_status"] == 403
    assert summary["skku"]["status"] == "passed"
    assert "secret-placeholder" not in capsys.readouterr().out
    assert "secret-placeholder" not in (args.output / "summary.json").read_text()
