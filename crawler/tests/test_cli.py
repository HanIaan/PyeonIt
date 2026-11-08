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
        model="gemini-3.8-flash",
        schools=["sogang"],
        year=2026,
    )
    monkeypatch.setattr(cli, "MODEL_CHAIN", ["gemini-3.8-flash"])
    return args, browser


def extraction(school="서강대학교", year=2026, total=3):
    return {
        "문서종류": "편입학 모집요강",
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
    assert data["schema_version"] == 4
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
    assert json.loads((args.output / "sogang-2026.json").read_text())["data"] is None
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


def test_resume_reextracts_failure_with_same_pdf(monkeypatch, pipeline):
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
    assert extract.call_count == 3
    cli.download_pdf.assert_not_called()
    summary = json.loads((args.output / "summary.json").read_text())
    assert summary["sogang"]["pdf_reused"]
    assert summary["sogang"]["extraction_attempts"] == 3


@pytest.mark.parametrize("change", ["pdf", "year", "validation"])
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
    assert asyncio.run(cli.backfill(args)) == (1 if change in {"pdf", "year"} else 0)
    if change in {"pdf", "year"}:
        extract.assert_not_called()
    else:
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


@pytest.mark.parametrize("document", ["잘못된 문서", None])
def test_wrong_document_is_distinct_from_identity_mismatch(
    monkeypatch, pipeline, document
):
    args, _ = pipeline
    data = extraction()
    data["문서종류"] = document
    monkeypatch.setattr(
        cli, "GeminiExtractor", lambda *_: SimpleNamespace(extract=lambda _: data)
    )
    assert asyncio.run(cli.backfill(args)) == 1
    result = json.loads((args.output / "summary.json").read_text())["sogang"]
    assert result["status"] == "error"
    assert result["error_code"] == "WRONG_DOCUMENT"


def test_pdf_reuse_extracts_again_without_downloading(monkeypatch, pipeline, tmp_path):
    args, _ = pipeline
    monkeypatch.setattr(
        cli,
        "GeminiExtractor",
        lambda *_: SimpleNamespace(extract=lambda _: extraction()),
    )
    assert asyncio.run(cli.backfill(args)) == 0
    previous = args.output
    args.output = tmp_path / "second"
    args.reuse_pdfs = previous
    cli.download_pdf.reset_mock()
    extract = MagicMock(return_value=extraction())
    monkeypatch.setattr(
        cli, "GeminiExtractor", lambda *_: SimpleNamespace(extract=extract)
    )
    assert asyncio.run(cli.backfill(args)) == 0
    cli.download_pdf.assert_not_called()
    extract.assert_called_once_with(b"%PDF-fixture")
    summary = json.loads((args.output / "summary.json").read_text())
    assert summary["sogang"]["pdf_reused"]


def test_pdf_reuse_rejects_changed_hash(monkeypatch, pipeline, tmp_path):
    args, _ = pipeline
    monkeypatch.setattr(
        cli,
        "GeminiExtractor",
        lambda *_: SimpleNamespace(extract=lambda _: extraction()),
    )
    assert asyncio.run(cli.backfill(args)) == 0
    previous = args.output
    (previous / "sogang-2026.pdf").write_bytes(b"%PDF-tampered")
    args.output = tmp_path / "second"
    args.reuse_pdfs = previous
    extract = MagicMock()
    monkeypatch.setattr(
        cli, "GeminiExtractor", lambda *_: SimpleNamespace(extract=extract)
    )
    assert asyncio.run(cli.backfill(args)) == 1
    extract.assert_not_called()


def test_scope_is_exactly_44_with_requested_regions_and_hongik_source():
    from collections import Counter

    schools = cli.load_universities(cli.DEFAULT_UNIVERSITIES)
    assert len(schools) == 44
    assert Counter(e["region"] for e in schools.values()) == {
        "SEOUL": 29,
        "GYEONGGI": 13,
        "INCHEON": 2,
    }
    assert sum(e["name"] == "한국외국어대학교" for e in schools.values()) == 1
    assert any(
        s["kind"] == "GUIDE_PAGE"
        and s["url"] == "https://www.hongik.ac.kr/kr/admission/recruitment-transfer.do"
        for s in schools["홍익대학교-서울"]["sources"]
    )


def test_pdf_reuse_uses_collection_summary_when_first_extraction_failed(
    monkeypatch, pipeline, tmp_path
):
    args, _ = pipeline
    monkeypatch.setattr(
        cli,
        "GeminiExtractor",
        lambda *_: SimpleNamespace(extract=lambda _: extraction()),
    )
    assert asyncio.run(cli.backfill(args)) == 0
    previous = args.output
    (previous / "sogang-2026.json").unlink()
    args.output = tmp_path / "second"
    args.reuse_pdfs = previous
    cli.download_pdf.reset_mock()
    assert asyncio.run(cli.backfill(args)) == 0
    cli.download_pdf.assert_not_called()
    result = json.loads((args.output / "summary.json").read_text())["sogang"]
    assert result["pdf_reuse_metadata"] == "summary"


def test_cli_model_default_is_restored(monkeypatch):
    import sys

    received = []

    async def backfill(args):
        received.append(args.model)
        return 0

    monkeypatch.setattr(sys, "argv", ["pyeonit-crawler", "backfill"])
    monkeypatch.setattr(cli, "backfill", backfill)
    with pytest.raises(SystemExit) as exc:
        cli.main()
    assert exc.value.code == 0
    assert received == ["gemini-3.8-flash"]


def test_actual_model_version_saved_with_requested_model(monkeypatch, pipeline):
    from pyeonit_crawler.gemini import ExtractionResult

    args, _ = pipeline
    result = ExtractionResult(extraction(), "gemini-3.8-flash-001")
    monkeypatch.setattr(
        cli, "GeminiExtractor", lambda *_: SimpleNamespace(extract=lambda _: result)
    )
    assert asyncio.run(cli.backfill(args)) == 0
    saved = json.loads((args.output / "sogang-2026.json").read_text())
    assert saved["source"]["model"] == "gemini-3.8-flash-001"
    assert saved["source"]["requested_model"] == "gemini-3.8-flash"
    args.resume = True
    cli.download_pdf.reset_mock()
    assert asyncio.run(cli.backfill(args)) == 0
    cli.download_pdf.assert_not_called()


@pytest.mark.parametrize("status", [503])
def test_exhausted_transient_error_records_school_failure_then_continues(
    monkeypatch, pipeline, status
):
    args, _ = pipeline
    args.schools = ["sogang", "skku"]
    response = httpx.Response(
        status, request=httpx.Request("POST", "https://example.test/api")
    )
    extract = MagicMock(
        side_effect=[
            httpx.HTTPStatusError(
                "secret", request=response.request, response=response
            ),
            extraction(school="성균관대학교"),
        ]
    )
    monkeypatch.setattr(
        cli, "GeminiExtractor", lambda *_: SimpleNamespace(extract=extract)
    )
    assert asyncio.run(cli.backfill(args)) == 1
    summary = json.loads((args.output / "summary.json").read_text())
    assert summary["sogang"]["status"] == "error"
    assert summary["sogang"]["http_status"] == status
    assert summary["sogang"]["error_code"] == f"HTTP_{status}"
    assert summary["skku"]["status"] == "passed"


def test_later_api_error_keeps_http_status_and_original_extraction():
    response = httpx.Response(
        429, request=httpx.Request("POST", "https://example.test/api")
    )
    error = httpx.HTTPStatusError("secret", request=response.request, response=response)
    extract = MagicMock(side_effect=[extraction(total=4), error, error])
    data, validation, attempts = asyncio.run(
        cli.extract_validated(
            SimpleNamespace(extract=extract), b"same-pdf", "서강대학교", 2026
        )
    )
    assert data["총계"][0]["인원"] == 4
    assert validation["status"] == "failed"
    assert attempts[1:] == [
        {
            "error_type": "HTTPStatusError",
            "error_code": "HTTP_429",
            "http_status": 429,
        }
    ]


def test_chain_429_switches_to_next_model_and_records_order():
    from pyeonit_crawler.gemini import MODEL_CHAIN, ExtractionResult

    response = httpx.Response(
        429, request=httpx.Request("POST", "https://example.test/api")
    )
    first = MagicMock(
        side_effect=httpx.HTTPStatusError(
            "quota", request=response.request, response=response
        )
    )
    second = MagicMock(
        return_value=ExtractionResult(extraction(), "gemini-3.7-flash-001")
    )
    result = asyncio.run(
        cli.extract_with_models(
            {
                MODEL_CHAIN[0]: SimpleNamespace(extract=first),
                MODEL_CHAIN[1]: SimpleNamespace(extract=second),
            },
            b"same-pdf",
            "서강대학교",
            2026,
            {},
        )
    )
    assert result["status"] == "passed" and result["adopted"]
    assert result["model"] == "gemini-3.7-flash-001"
    assert [(a["requested_model"], a["status"]) for a in result["model_attempts"]] == [
        (MODEL_CHAIN[0], "QUOTA_WAIT"),
        (MODEL_CHAIN[1], "passed"),
    ]
    first.assert_called_once_with(b"same-pdf")
    second.assert_called_once_with(b"same-pdf")


@pytest.mark.parametrize("failure", ["503", "timeout"])
def test_chain_does_not_switch_after_transport_retries_exhausted(failure):
    response = httpx.Response(
        503, request=httpx.Request("POST", "https://example.test/api")
    )
    error = (
        httpx.ReadTimeout("timeout")
        if failure == "timeout"
        else httpx.HTTPStatusError("busy", request=response.request, response=response)
    )
    first, second = MagicMock(side_effect=error), MagicMock(return_value=extraction())
    result = asyncio.run(
        cli.extract_with_models(
            {
                "gemini-3.8-flash": SimpleNamespace(extract=first),
                "gemini-3.7-flash": SimpleNamespace(extract=second),
            },
            b"pdf",
            "서강대학교",
            2026,
            {},
        )
    )
    assert result["status"] == "error"
    assert len(result["model_attempts"]) == 1
    second.assert_not_called()


@pytest.mark.parametrize("total,tries", [(4, 3), (None, 1)])
def test_chain_only_adopts_pass_and_retries_mismatch_per_model(total, tries):
    first, second = (
        MagicMock(return_value=extraction(total=total)),
        MagicMock(return_value=extraction()),
    )
    result = asyncio.run(
        cli.extract_with_models(
            {
                "gemini-3.8-flash": SimpleNamespace(extract=first),
                "gemini-3.7-flash": SimpleNamespace(extract=second),
            },
            b"pdf",
            "서강대학교",
            2026,
            {},
        )
    )
    assert result["status"] == "passed" and result["adopted"]
    assert result["requested_model"] == "gemini-3.7-flash"
    assert first.call_count == tries
    second.assert_called_once()


def test_quota_wait_all_models_and_remaining_school_exit_zero(monkeypatch, pipeline):
    from pyeonit_crawler.gemini import MODEL_CHAIN

    args, _ = pipeline
    args.schools = ["sogang", "skku"]
    args.concurrency = 1
    monkeypatch.setattr(cli, "MODEL_CHAIN", MODEL_CHAIN)
    response = httpx.Response(
        429,
        request=httpx.Request("POST", "https://example.test/api"),
        json={
            "error": {
                "details": [
                    {"violations": [{"quotaId": "RequestsPerDay", "quotaValue": "20"}]}
                ]
            }
        },
    )
    calls = []

    def factory(_, model):
        def extract(pdf):
            calls.append(model)
            raise httpx.HTTPStatusError(
                "quota secret", request=response.request, response=response
            )

        return SimpleNamespace(extract=extract)

    monkeypatch.setattr(cli, "GeminiExtractor", factory)
    assert asyncio.run(cli.backfill(args)) == 0
    assert calls == MODEL_CHAIN[:1]
    summary = json.loads((args.output / "summary.json").read_text())
    assert {r["status"] for r in summary.values()} == {"QUOTA_WAIT"}
    saved = json.loads((args.output / "sogang-2026.json").read_text())
    assert not saved["adopted"] and saved["data"] is None
    assert len(saved["model_attempts"]) == 1
    assert not (args.output / "skku-2026.json").exists()
    quotas = json.loads((args.output / "model_quotas.json").read_text())
    assert quotas[MODEL_CHAIN[0]][0]["quotaValue"] == "20"


def test_passed_school_automatically_skipped_without_resume(monkeypatch, pipeline):
    args, _ = pipeline
    extract = MagicMock(return_value=extraction())
    monkeypatch.setattr(
        cli, "GeminiExtractor", lambda *_: SimpleNamespace(extract=extract)
    )
    assert asyncio.run(cli.backfill(args)) == 0
    extract.reset_mock()
    cli.download_pdf.reset_mock()
    assert asyncio.run(cli.backfill(args)) == 0
    extract.assert_not_called()
    cli.download_pdf.assert_not_called()


def test_lite_passed_result_is_reextracted(monkeypatch, pipeline):
    args, _ = pipeline
    extract = MagicMock(return_value=extraction())
    monkeypatch.setattr(
        cli, "GeminiExtractor", lambda *_: SimpleNamespace(extract=extract)
    )
    assert asyncio.run(cli.backfill(args)) == 0
    path = args.output / "sogang-2026.json"
    data = json.loads(path.read_text())
    data["source"]["model"] = "gemini-3.1-flash-lite"
    path.write_text(json.dumps(data))
    extract.reset_mock()
    assert asyncio.run(cli.backfill(args)) == 0
    extract.assert_called_once()


def test_chain_does_not_switch_on_503_after_prior_mismatch():
    response = httpx.Response(
        503, request=httpx.Request("POST", "https://example.test/api")
    )
    error = httpx.HTTPStatusError("busy", request=response.request, response=response)
    first = MagicMock(side_effect=[extraction(total=4), error, error])
    second = MagicMock(return_value=extraction())
    result = asyncio.run(
        cli.extract_with_models(
            {
                "gemini-3.8-flash": SimpleNamespace(extract=first),
                "gemini-3.7-flash": SimpleNamespace(extract=second),
            },
            b"pdf",
            "서강대학교",
            2026,
            {},
        )
    )
    assert result["status"] == "error"
    assert result["http_status"] == 503
    second.assert_not_called()


def test_wrong_document_preserves_actual_model_and_document_kind():
    from pyeonit_crawler.gemini import ExtractionResult

    data = ExtractionResult(
        {**extraction(), "문서종류": "잘못된 문서"}, "gemini-3.8-flash-001"
    )
    result = asyncio.run(
        cli.extract_with_models(
            {"gemini-3.8-flash": SimpleNamespace(extract=lambda _: data)},
            b"pdf",
            "서강대학교",
            2026,
            {},
        )
    )
    assert result["status"] == "error"
    assert result["model"] == "gemini-3.8-flash-001"
    assert result["data"]["문서종류"] == "잘못된 문서"
    assert result["validation"]["reason"] == "WRONG_DOCUMENT"


def test_old_non_lite_pass_is_migrated_without_extracting_again(monkeypatch, pipeline):
    args, _ = pipeline
    extract = MagicMock(return_value=extraction())
    monkeypatch.setattr(
        cli, "GeminiExtractor", lambda *_: SimpleNamespace(extract=extract)
    )
    assert asyncio.run(cli.backfill(args)) == 0
    path = args.output / "sogang-2026.json"
    data = json.loads(path.read_text())
    data["schema_version"] = 3
    del data["source"]["requested_model"]
    del data["model_attempts"]
    path.write_text(json.dumps(data))
    extract.reset_mock()
    assert asyncio.run(cli.backfill(args)) == 0
    extract.assert_not_called()
    saved = json.loads(path.read_text())
    assert saved["schema_version"] == 4
    assert saved["source"]["requested_model"] == "gemini-3.8-flash"
    assert saved["model_attempts"][0]["status"] == "passed"
