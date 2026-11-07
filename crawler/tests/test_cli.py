import asyncio
import hashlib
import json
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock

import httpx
import pytest

from pyeonit_crawler import cli
from pyeonit_crawler.download import DownloadedPDF, NoGuideError


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


def test_reextract_same_pdf_until_pass():
    payloads = []
    results = iter([extraction(total=4), extraction(total=5), extraction()])

    def extract(content):
        payloads.append(content)
        return next(results)

    data, validation, attempts = asyncio.run(
        cli.extract_validated(
            SimpleNamespace(extract=extract), b"same-pdf", "서강대학교", 2026
        )
    )
    assert validation["status"] == "passed"
    assert data["총계"][0]["인원"] == 3
    assert payloads == [b"same-pdf"] * 3
    assert [a["validation"]["status"] for a in attempts] == [
        "failed",
        "failed",
        "passed",
    ]


def test_reextract_stops_on_first_pass():
    extract = MagicMock(side_effect=[extraction(total=4), extraction()])
    _, validation, attempts = asyncio.run(
        cli.extract_validated(
            SimpleNamespace(extract=extract), b"pdf", "서강대학교", 2026
        )
    )
    assert validation["passed"]
    assert len(attempts) == extract.call_count == 2


@pytest.mark.parametrize(
    "total,status,count",
    [(4, "failed", 3), (None, "unverifiable", 1), (3, "passed", 1)],
)
def test_reextract_budget_and_unverifiable(total, status, count):
    extract = MagicMock(return_value=extraction(total=total))
    _, validation, attempts = asyncio.run(
        cli.extract_validated(
            SimpleNamespace(extract=extract), b"pdf", "서강대학교", 2026
        )
    )
    assert validation["status"] == status
    assert len(attempts) == extract.call_count == count


def test_reextract_error_preserves_initial_result():
    extract = MagicMock(
        side_effect=[extraction(total=4), RuntimeError("secret"), extraction(year=2027)]
    )
    _, validation, attempts = asyncio.run(
        cli.extract_validated(
            SimpleNamespace(extract=extract), b"pdf", "서강대학교", 2026
        )
    )
    assert validation["status"] == "failed"
    assert len(attempts) == 3
    assert attempts[1:] == [
        {"error_type": "RuntimeError"},
        {"error_type": "ValueError"},
    ]


def test_yaml_fixed_format_and_campuses(tmp_path):
    path = tmp_path / "universities.yaml"
    path.write_text("""# 출처 주석
- name: 학교
  campus: "서울"
  region: SEOUL
  sources:
    - kind: NOTICE_BOARD
      url: https://example.test/notice?a=1&b=2
    - kind: GUIDE_PAGE
      url: https://example.test/guide#pdf
- name: 학교
  campus: "경기" # 캠퍼스 구분
  region: GYEONGGI
  sources:
    - kind: GUIDE_PAGE
      url: https://example.test/guide2
""")
    schools = cli.load_universities(path)
    assert list(schools) == ["학교-서울", "학교-경기"]
    assert schools["학교-서울"]["sources"][1]["url"].endswith("#pdf")


@pytest.mark.parametrize(
    "replacement",
    ["  region: OTHER", "  region: SEOUL\n  region: SEOUL", "  unknown: value"],
)
def test_yaml_rejects_invalid_format(tmp_path, replacement):
    path = tmp_path / "universities.yaml"
    path.write_text(f"""- name: 학교
  campus: ""
{replacement}
  sources:
    - kind: GUIDE_PAGE
      url: https://example.test/guide
""")
    with pytest.raises(ValueError):
        cli.load_universities(path)


def test_no_guide_status_and_source_fallback(monkeypatch, pipeline):
    args, _ = pipeline
    extract = MagicMock(return_value=extraction())
    monkeypatch.setattr(
        cli, "GeminiExtractor", lambda *_: SimpleNamespace(extract=extract)
    )
    monkeypatch.setattr(
        cli, "download_pdf", AsyncMock(side_effect=NoGuideError("PDF 없음"))
    )
    assert asyncio.run(cli.backfill(args)) == 1
    record = json.loads((args.output / "summary.json").read_text())["sogang"]
    assert record["status"] == "no_guide"
    assert len(record["source_attempts"]) == 2
    extract.assert_not_called()


def test_second_source_used_after_missing_guide(monkeypatch, pipeline):
    args, _ = pipeline
    original_pdf = cli.download_pdf.return_value
    download = AsyncMock(side_effect=[NoGuideError("없음"), original_pdf])
    monkeypatch.setattr(cli, "download_pdf", download)
    monkeypatch.setattr(
        cli,
        "GeminiExtractor",
        lambda *_: SimpleNamespace(extract=lambda _: extraction()),
    )
    assert asyncio.run(cli.backfill(args)) == 0
    result = json.loads((args.output / "sogang-2026.json").read_text())
    assert result["source"]["page_url"].endswith("notice.asp")
    assert download.await_count == 2


def test_backfill_persists_retry_history_and_adopts_pass(monkeypatch, pipeline):
    args, _ = pipeline
    extract = MagicMock(side_effect=[extraction(total=4), extraction()])
    monkeypatch.setattr(
        cli, "GeminiExtractor", lambda *_: SimpleNamespace(extract=extract)
    )
    assert asyncio.run(cli.backfill(args)) == 0
    result = json.loads((args.output / "sogang-2026.json").read_text())
    assert result["data"]["총계"][0]["인원"] == 3
    assert [a["validation"]["status"] for a in result["extraction_attempts"]] == [
        "failed",
        "passed",
    ]
    assert result["validation"]["passed"]
    summary = json.loads((args.output / "summary.json").read_text())
    assert summary["sogang"]["extraction_attempts"] == 2


def test_retry_unverifiable_does_not_hide_later_pass():
    extract = MagicMock(
        side_effect=[extraction(total=4), extraction(total=None), extraction()]
    )
    _, validation, attempts = asyncio.run(
        cli.extract_validated(
            SimpleNamespace(extract=extract), b"pdf", "서강대학교", 2026
        )
    )
    assert validation["passed"]
    assert len(attempts) == 3


def test_resume_reuses_matching_extraction_and_preserves_failure(monkeypatch, pipeline):
    args, _ = pipeline
    extract = MagicMock(return_value=extraction(total=4))
    monkeypatch.setattr(
        cli, "GeminiExtractor", lambda *_: SimpleNamespace(extract=extract)
    )
    assert asyncio.run(cli.backfill(args)) == 1
    args.resume = True
    extract.reset_mock()
    cli.download_pdf.reset_mock()
    assert asyncio.run(cli.backfill(args)) == 1
    extract.assert_not_called()
    cli.download_pdf.assert_not_called()
    summary = json.loads((args.output / "summary.json").read_text())
    assert summary["sogang"]["reused"]
    assert summary["sogang"]["extraction_attempts"] == 3


@pytest.mark.parametrize("change", ["pdf", "year", "model", "source", "validation"])
def test_resume_rejects_changed_input(monkeypatch, pipeline, change):
    args, _ = pipeline
    extract = MagicMock(return_value=extraction())
    monkeypatch.setattr(
        cli, "GeminiExtractor", lambda *_: SimpleNamespace(extract=extract)
    )
    assert asyncio.run(cli.backfill(args)) == 0
    path = args.output / "sogang-2026.json"
    result = json.loads(path.read_text())
    if change == "pdf":
        (args.output / "sogang-2026.pdf").write_bytes(b"%PDF-changed")
    elif change == "validation":
        result["validation"]["status"] = "failed"
    elif change == "source":
        result["source"]["sources"] = []
    else:
        result["source"][change] = "different"
    path.write_text(json.dumps(result))
    args.resume = True
    extract.reset_mock()
    assert asyncio.run(cli.backfill(args)) == 0
    extract.assert_called_once()


@pytest.mark.parametrize(
    "message,expected",
    [
        ("secret-placeholder", "RuntimeError"),
        ("net::ERR_CERT_DATE_INVALID secret-placeholder", "ERR_CERT_DATE_INVALID"),
        (
            "this._engines.set is not a function secret-placeholder",
            "PAGE_SCRIPT_CONFLICT",
        ),
    ],
)
def test_safe_error_code_does_not_return_exception_body(message, expected):
    assert cli.safe_error_code(RuntimeError(message)) == expected


def test_school_whitespace_is_normalized_only_for_identity_check():
    original = extraction(school="차 의과학대학교")
    data, validation, _ = asyncio.run(
        cli.extract_validated(
            SimpleNamespace(extract=lambda _: original),
            b"pdf",
            "차의과학대학교",
            2026,
        )
    )
    assert validation["passed"]
    assert data["학교"] == "차 의과학대학교"
    assert not cli.matches_request(original, "서울대학교", 2026)
    assert not cli.matches_request(original, "차의과학대학교", 2027)
