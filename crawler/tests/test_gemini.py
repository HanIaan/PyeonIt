import json

import httpx
import pytest

from pyeonit_crawler.gemini import GeminiExtractor, load_api_key
from pyeonit_crawler.schema import SCHEMA


@pytest.mark.parametrize("failure", ["503", "timeout"])
def test_retry_with_exponential_backoff(failure):
    attempts, delays = [], []

    def handler(request):
        attempts.append(request)
        assert request.headers["x-goog-api-key"] == "fake"
        assert "fake" not in str(request.url)
        body = json.loads(request.content)
        assert body["generationConfig"]["responseSchema"] == SCHEMA
        if len(attempts) < 4:
            if failure == "timeout":
                raise httpx.ReadTimeout("timeout", request=request)
            return httpx.Response(503)
        return httpx.Response(
            200,
            json={
                "candidates": [{"content": {"parts": [{"text": '{"학교":"테스트"}'}]}}]
            },
        )

    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        result = GeminiExtractor("fake", client=client, sleep=delays.append).extract(
            b"%PDF-test"
        )
    assert result["학교"] == "테스트"
    assert len(attempts) == 4
    assert delays == [1, 2, 4]


@pytest.mark.parametrize("status,expected", [(503, 4), (400, 1), (429, 1)])
def test_retry_limit_and_non_retryable_status(status, expected):
    calls = []

    def handler(request):
        calls.append(request)
        return httpx.Response(status)

    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        with pytest.raises(httpx.HTTPStatusError):
            GeminiExtractor("fake", client=client, sleep=lambda _: None).extract(
                b"%PDF-test"
            )
    assert len(calls) == expected


def test_env_key_precedes_file(monkeypatch, tmp_path):
    monkeypatch.setenv("GEMINI_API_KEY", "environment-placeholder")
    env = tmp_path / ".env"
    env.write_text("GEMINI_API_KEY=file-placeholder\n")
    assert load_api_key(env) == "environment-placeholder"


def test_dotenv_key(monkeypatch, tmp_path):
    monkeypatch.delenv("GEMINI_API_KEY", raising=False)
    env = tmp_path / ".env"
    env.write_text('GEMINI_API_KEY="file-placeholder"\n')
    assert load_api_key(env) == "file-placeholder"


def test_timeout_exhausts_after_three_retries():
    calls, delays = [], []

    def handler(request):
        calls.append(request)
        raise httpx.ReadTimeout("timeout", request=request)

    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        with pytest.raises(httpx.ReadTimeout):
            GeminiExtractor("fake", client=client, sleep=delays.append).extract(
                b"%PDF-test"
            )
    assert len(calls) == 4
    assert delays == [1, 2, 4]


def test_short_quota_limit_honors_retry_info():
    calls, delays = [], []

    def handler(request):
        calls.append(request)
        if len(calls) == 1:
            return httpx.Response(
                429,
                json={
                    "error": {
                        "details": [
                            {
                                "violations": [
                                    {"quotaId": "InputTokensPerModelPerMinute"}
                                ]
                            },
                            {"retryDelay": "55s"},
                        ]
                    }
                },
            )
        return httpx.Response(
            200, json={"candidates": [{"content": {"parts": [{"text": "{}"}]}}]}
        )

    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        assert (
            GeminiExtractor("fake", client=client, sleep=delays.append).extract(b"pdf")
            == {}
        )
    assert len(calls) == 2
    assert delays == [55]


@pytest.mark.parametrize(
    "quota_id,delay", [("RequestsPerDay", "55s"), ("TokensPerMinute", "3600s")]
)
def test_daily_or_long_quota_limit_is_not_retried(quota_id, delay):
    calls = []

    def handler(request):
        calls.append(request)
        return httpx.Response(
            429,
            json={
                "error": {
                    "details": [
                        {"violations": [{"quotaId": quota_id}]},
                        {"retryDelay": delay},
                    ]
                }
            },
        )

    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        with pytest.raises(httpx.HTTPStatusError):
            GeminiExtractor("fake", client=client, sleep=lambda _: None).extract(b"pdf")
    assert len(calls) == 1
