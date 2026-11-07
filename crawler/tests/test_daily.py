"""일일 요청 예산·우선순위·next-run·429 기록 (T8)."""

import asyncio
import hashlib
import json
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace

import httpx
import pytest
from test_cli import extraction, pipeline  # noqa: F401

from pyeonit_crawler import cli
from pyeonit_crawler.gemini import GeminiExtractor


def write_result(output, slug, entry, *, status, model, data=True, sha=None):
    pdf = output / f"{slug}-2026.pdf"
    if not pdf.exists():
        pdf.write_bytes(b"%PDF-" + slug.encode())
    digest = sha or hashlib.sha256(pdf.read_bytes()).hexdigest()
    body = extraction(entry["name"], total=3 if status == "passed" else 4)
    result = {
        "schema_version": 4,
        "source": {
            **entry,
            "page_url": cli.first_source(entry),
            "sha256": digest,
            "model": model,
            "year": 2026,
        },
        "status": status,
        "data": body if data else None,
        "validation": cli.validate_totals(body) if data else None,
        "model_attempts": [],
    }
    (output / f"{slug}-2026.json").write_text(json.dumps(result, ensure_ascii=False))
    return digest


@pytest.fixture
def schools():
    return cli.load_universities(cli.DEFAULT_UNIVERSITIES)


def test_classification_stages_and_order(tmp_path, schools, capsys):
    out, lite = tmp_path / "out", tmp_path / "lite"
    out.mkdir(), lite.mkdir()
    write_result(
        out, "sogang", schools["sogang"], status="passed", model="gemini-3.8-flash"
    )
    write_result(
        out, "skku", schools["skku"], status="failed", model="gemini-3.6-flash"
    )
    sha = write_result(
        out,
        "경희대학교",
        schools["경희대학교"],
        status="QUOTA_WAIT",
        model="x",
        data=False,
    )
    write_result(
        lite,
        "경희대학교",
        schools["경희대학교"],
        status="passed",
        model="lite",
        sha=sha,
    )
    lite_json = json.loads((lite / "경희대학교-2026.json").read_text())
    lite_json["source"]["model"] = "gemini-3.1-flash-lite"
    (lite / "경희대학교-2026.json").write_text(json.dumps(lite_json))
    gachon = schools["가천대학교"]
    write_result(out, "가천대학교", gachon, status="QUOTA_WAIT", model="x", data=False)
    moved = json.loads((out / "가천대학교-2026.json").read_text())
    moved["source"]["page_url"] = "https://old.example/guide"
    (out / "가천대학교-2026.json").write_text(json.dumps(moved))
    args = SimpleNamespace(output=out, year=2026, lite_results=lite)
    models = ["gemini-3.8-flash"]
    assert cli.classify(args, "sogang", schools["sogang"], models)[0] == "skip"
    assert cli.classify(args, "skku", schools["skku"], models)[0] == 2
    assert cli.classify(args, "경희대학교", schools["경희대학교"], models)[0] == 3
    stage, reason = cli.classify(args, "가천대학교", gachon, models)
    assert stage == 1 and "출처 교체" in reason
    assert cli.classify(args, "yonsei", schools["yonsei"], models)[0] == 1

    # 체인 밖(3.6) 통과는 3.8 단일 체인에서 건너뛰지 않고 ②.
    write_result(
        out, "yonsei", schools["yonsei"], status="passed", model="gemini-3.6-flash"
    )
    assert cli.classify(args, "yonsei", schools["yonsei"], models)[0] == 2
    assert cli.classify(args, "yonsei", schools["yonsei"], cli.MODEL_CHAIN)[0] == "skip"

    dry = SimpleNamespace(
        output=out,
        year=2026,
        lite_results=lite,
        schools=["경희대학교", "skku", "가천대학교", "sogang", "yonsei"],
        model="gemini-3.8-flash",
        model_chain=None,
        quota_state=tmp_path / "q.json",
        daily_request_limit=2,
        dry_run=True,
    )
    assert asyncio.run(cli.backfill(dry)) == 0
    printed = capsys.readouterr().out
    rows = [
        line.split(" | ")[1]
        for line in printed.splitlines()
        if line.startswith("| ") and "단계" not in line and "---" not in line
    ]
    assert rows == [
        "서강대학교",
        "연세대학교 (서울)",  # 기본 체인에서는 3.6 통과도 건너뜀
        "가천대학교",
        "성균관대학교",
        "경희대학교",
    ]
    plan = printed.split("오늘 처리 예정")[1]
    assert "가천대학교" in plan.split("QUOTA_WAIT 예정")[0]
    assert "경희대학교" in plan.split("QUOTA_WAIT 예정")[1]
    assert not (tmp_path / "q.json").exists()


def clock(value):
    return lambda: value


def test_budget_accumulates_across_runs_and_resets_at_pacific_midnight(tmp_path):
    path = tmp_path / "quota-state.json"
    before = datetime(2026, 11, 8, 7, 59, tzinfo=UTC)  # PST 23:59
    first = cli.RequestBudget(path, 3, clock(before))
    first.spend("m")
    first.spend("m")
    second = cli.RequestBudget(path, 3, clock(before))
    assert second.remaining() == 1
    second.spend("m")
    with pytest.raises(cli.BudgetExhausted):
        second.spend("m")
    assert second.exhausted
    after = cli.RequestBudget(path, 3, clock(before + timedelta(minutes=1)))
    assert after.remaining() == 3
    assert after.state["reset_at"] == "2026-11-09T08:00:00+00:00"
    # 서머타임(PDT)에는 07:00 UTC가 경계.
    assert cli.next_reset(datetime(2026, 7, 1, 6, 59, tzinfo=UTC)).hour == 7
    assert cli.quota_day(datetime(2026, 7, 1, 7, 0, tzinfo=UTC)) == "2026-07-01"


def test_corrupt_budget_state_is_not_reset(tmp_path):
    path = tmp_path / "quota-state.json"
    path.write_text("{broken")
    with pytest.raises(ValueError):
        cli.RequestBudget(path, 20)


def test_next_run_from_last_429_retry_delay(tmp_path, capsys):
    path = tmp_path / "quota-state.json"
    at = datetime(2026, 11, 7, 16, 47, 48, tzinfo=UTC)
    budget = cli.RequestBudget(path, 20, clock(at))
    budget.record_429(
        "gemini-3.8-flash",
        [
            {"quotaId": "GenerateRequestsPerDayPerProjectPerModel-FreeTier"},
            {"retryDelay": "54732s"},
        ],
    )
    state = json.loads(path.read_text())
    assert state["last_429"]["retry_at"] == "2026-11-08T08:00:00+00:00"
    assert state["last_429"]["reset_gap_s"] == 0
    assert cli.next_run_at(state) == datetime(2026, 11, 8, 8, 5, tzinfo=UTC)
    cli.print_next_run(path)
    out = capsys.readouterr().out
    assert "2026-11-08 17:05 KST" in out and "2026-11-08 08:05 UTC" in out
    assert cli.next_run_at({}) is None
    assert cli.next_run_at(
        {"last_429": {"at": "x"}, "reset_at": "2026-11-09T08:00:00+00:00"}
    ) == datetime(2026, 11, 9, 8, 5, tzinfo=UTC)
    cli.print_next_run(tmp_path / "missing.json")
    assert "기록 없음" in capsys.readouterr().out


def test_reset_assumption_mismatch_is_reported(tmp_path, capsys):
    budget = cli.RequestBudget(
        tmp_path / "q.json", 20, clock(datetime(2026, 11, 7, 12, tzinfo=UTC))
    )
    budget.record_429("m", [{"quotaId": "PerDay"}, {"retryDelay": "3600s"}])
    assert "리셋" in capsys.readouterr().out


def test_model_chain_option_single_and_lite_rejected(monkeypatch):
    import argparse
    import sys

    assert cli.parse_model_chain("gemini-3.8-flash") == ["gemini-3.8-flash"]
    for bad in (
        "gemini-3.1-flash-lite",
        "gemini-3.8-flash,gemini-3.8-flash-lite",
        "gpt",
        "",
    ):
        with pytest.raises(argparse.ArgumentTypeError):
            cli.parse_model_chain(bad)
    with pytest.raises(ValueError):
        cli.chain_models(SimpleNamespace(model_chain=["x-lite"], model="x"))
    received = []

    async def backfill(args):
        received.append(cli.chain_models(args))
        return 0

    monkeypatch.setattr(cli, "backfill", backfill)
    monkeypatch.setattr(
        sys, "argv", ["p", "backfill", "--model-chain", "gemini-3.8-flash"]
    )
    with pytest.raises(SystemExit):
        cli.main()
    monkeypatch.setattr(sys, "argv", ["p", "backfill"])
    with pytest.raises(SystemExit):
        cli.main()
    assert received == [["gemini-3.8-flash"], cli.MODEL_CHAIN]
    monkeypatch.setattr(
        sys, "argv", ["p", "backfill", "--model-chain", "gemini-3.1-flash-lite"]
    )
    with pytest.raises(SystemExit) as exc:
        cli.main()
    assert exc.value.code == 2


class Counting:
    """실제 전송처럼 extract 1회마다 예산을 쓴다."""

    def __init__(self, calls, totals):
        self.calls, self.totals = calls, totals

    def extract(self, pdf):
        self.budget.spend("gemini-3.8-flash")
        school = "서강대학교" if pdf.endswith(b"a") else "성균관대학교"
        self.calls.append(school)
        return extraction(school, total=self.totals[school])


@pytest.mark.parametrize(
    "limit,expected",
    [(1, ["서강대학교"]), (3, ["서강대학교", "성균관대학교", "서강대학교"])],
)
def test_first_pass_all_then_retries_with_leftover_budget(
    monkeypatch,
    pipeline,  # noqa: F811
    limit,
    expected,
):
    from pyeonit_crawler.download import DownloadedPDF

    args, _ = pipeline
    args.schools = ["sogang", "skku"]
    args.concurrency = 1
    args.daily_request_limit = limit
    pdfs = {
        "sogang": DownloadedPDF(
            "https://e/a.pdf", b"%PDF-a", hashlib.sha256(b"%PDF-a").hexdigest()
        ),
        "skku": DownloadedPDF(
            "https://e/b.pdf", b"%PDF-b", hashlib.sha256(b"%PDF-b").hexdigest()
        ),
    }

    async def download(_, url, year):
        return pdfs["sogang" if "sogang" in url else "skku"]

    monkeypatch.setattr(cli, "download_pdf", download)
    calls = []
    fake = Counting(calls, {"서강대학교": 4, "성균관대학교": 3})
    monkeypatch.setattr(cli, "GeminiExtractor", lambda *_: fake)
    assert asyncio.run(cli.backfill(args)) == 0
    assert calls == expected
    summary = json.loads((args.output / "summary.json").read_text())
    state = json.loads((args.output / "quota-state.json").read_text())
    assert state["used"] == limit
    if limit == 1:
        assert summary["skku"]["status"] == "QUOTA_WAIT"
        assert summary["skku"]["reason"] == "DAILY_BUDGET"
        assert not (args.output / "skku-2026.json").exists()
        assert summary["sogang"]["retry"] == "DAILY_BUDGET"
    else:
        saved = json.loads((args.output / "sogang-2026.json").read_text())
        assert saved["status"] == "failed"
        assert [a.get("error_code") for a in saved["extraction_attempts"]] == [
            None,
            None,
            "DAILY_BUDGET",
        ]
        assert summary["skku"]["status"] == "passed"
    # 같은 날 재실행: 누적 예산이 이미 소진되어 호출 0.
    calls.clear()
    assert asyncio.run(cli.backfill(args)) == 0
    assert calls == []


def quota_response(quota_id, delay):
    return httpx.Response(
        429,
        json={
            "error": {
                "details": [
                    {
                        "violations": [
                            {
                                "quotaId": quota_id,
                                "quotaValue": "20",
                                "quotaDimensions": {"model": "gemini-3.8-flash"},
                            }
                        ]
                    },
                    {"retryDelay": delay},
                ]
            }
        },
    )


def test_every_429_is_counted_and_recorded(tmp_path):
    responses = [
        quota_response("GenerateRequestsPerMinute", "2s"),
        httpx.Response(
            200,
            json={
                "candidates": [
                    {"content": {"parts": [{"text": json.dumps(extraction())}]}}
                ]
            },
        ),
        quota_response("GenerateRequestsPerDayPerProjectPerModel-FreeTier", "54000s"),
    ]
    with httpx.Client(
        transport=httpx.MockTransport(lambda _: responses.pop(0))
    ) as client:
        budget = cli.RequestBudget(tmp_path / "q.json", 20)
        extractors = {}
        for model in ("gemini-3.8-flash", "gemini-3.7-flash"):
            extractors[model] = GeminiExtractor(
                "fake", model, client=client, sleep=lambda _: None
            )
            extractors[model].budget = budget
        first = asyncio.run(
            cli.extract_with_models(
                extractors,
                b"%PDF",
                "서강대학교",
                2026,
                {},
                tries=1,
                chain_on_fail=False,
            )
        )
        responses[:] = [
            quota_response(
                "GenerateRequestsPerDayPerProjectPerModel-FreeTier", "54000s"
            ),
            httpx.Response(
                200,
                json={
                    "candidates": [
                        {"content": {"parts": [{"text": json.dumps(extraction())}]}}
                    ]
                },
            ),
        ]
        second = asyncio.run(
            cli.extract_with_models(
                extractors,
                b"%PDF",
                "서강대학교",
                2026,
                {},
                tries=1,
                chain_on_fail=False,
            )
        )
    assert budget.state["used"] == 4
    retried = first["model_attempts"][0]["extraction_attempts"][0]["retried_429"]
    assert retried[0]["quota"][0]["quotaId"] == "GenerateRequestsPerMinute"
    moved = second["model_attempts"]
    assert (
        moved[0]["http_status"] == 429
        and moved[0]["quota"][1]["retryDelay"] == "54000s"
    )
    assert (
        moved[1]["requested_model"] == "gemini-3.7-flash"
        and moved[1]["http_status"] == 200
    )
    assert budget.state["last_429"]["model"] == "gemini-3.8-flash"

    (tmp_path / "a-2026.json").write_text(
        json.dumps({"model_attempts": first["model_attempts"]})
    )
    (tmp_path / "b-2026.json").write_text(json.dumps({"model_attempts": moved}))
    rows = cli.quota_evidence(tmp_path)
    assert rows["gemini-3.8-flash"]["429"] == 2 and rows["gemini-3.8-flash"]["200"] == 1
    assert rows["gemini-3.7-flash"]["after"] == {"gemini-3.8-flash 429 후 200": 1}
    assert "gemini-3.8-flash 429 후 200×1" in cli.quota_report(tmp_path)
