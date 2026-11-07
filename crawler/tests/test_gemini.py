import json

import httpx
import pytest

from pyeonit_crawler.gemini import SCHEMA, GeminiExtractor, load_api_key


@pytest.mark.parametrize("failure", ["503", "timeout"])
def test_retry_with_exponential_backoff(failure):
    attempts, delays = [], []

    def handler(request):
        attempts.append(request)
        assert "/models/gemini-3.8-flash:generateContent" in str(request.url)
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


@pytest.mark.parametrize("status,expected", [(503, 4), (400, 1), (429, 4)])
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


def test_default_model_and_document_schema():
    from pyeonit_crawler.gemini import DEFAULT_MODEL
    from pyeonit_crawler.schema import SCHEMA as original

    assert GeminiExtractor("fake").model == DEFAULT_MODEL == "gemini-3.8-flash"
    assert "문서종류" in SCHEMA["required"]
    assert "잘못된 문서" in SCHEMA["properties"]["문서종류"]["enum"]
    assert "문서종류" not in original["properties"]


@pytest.mark.parametrize("always_truncated", [False, True])
def test_max_tokens_retries_with_higher_bounded_budget(always_truncated):
    from pyeonit_crawler.gemini import MAX_OUTPUT_TOKENS, OutputTokenLimitError

    budgets = []

    def handler(request):
        body = json.loads(request.content)
        budgets.append(body["generationConfig"]["maxOutputTokens"])
        assert "2028학년도" in body["contents"][0]["parts"][1]["text"]
        if always_truncated or len(budgets) < 2:
            # 부분 JSON이 유효해 보여도 MAX_TOKENS는 채택하면 안 된다.
            return httpx.Response(
                200,
                json={
                    "candidates": [
                        {
                            "finishReason": "MAX_TOKENS",
                            "content": {"parts": [{"text": "{}"}]},
                        }
                    ]
                },
            )
        return httpx.Response(
            200,
            json={
                "candidates": [
                    {
                        "finishReason": "STOP",
                        "content": {"parts": [{"text": '{"complete": true}'}]},
                    }
                ]
            },
        )

    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        extractor = GeminiExtractor("fake", client=client)
        extractor.year = 2028
        if always_truncated:
            with pytest.raises(OutputTokenLimitError):
                extractor.extract(b"pdf")
        else:
            assert extractor.extract(b"pdf") == {"complete": True}
    assert budgets == [32768, MAX_OUTPUT_TOKENS]


@pytest.mark.parametrize("status", [429, 503])
@pytest.mark.parametrize("source", ["header", "details"])
def test_server_retry_delay_on_transient_failures(status, source):
    calls, delays = [], []

    def handler(request):
        calls.append(1)
        if len(calls) == 1:
            return httpx.Response(
                status,
                headers={"Retry-After": "7"} if source == "header" else {},
                json={"error": {"details": [{"retryDelay": "7s"}]}}
                if source == "details"
                else {},
            )
        return httpx.Response(
            200, json={"candidates": [{"content": {"parts": [{"text": "{}"}]}}]}
        )

    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        assert (
            GeminiExtractor("fake", client=client, sleep=delays.append).extract(b"pdf")
            == {}
        )
    assert delays == [7]


def test_blocked_finish_reason_is_not_parsed_as_success():
    with httpx.Client(
        transport=httpx.MockTransport(
            lambda _: httpx.Response(
                200,
                json={
                    "candidates": [
                        {
                            "finishReason": "SAFETY",
                            "content": {"parts": [{"text": "{}"}]},
                        }
                    ]
                },
            )
        )
    ) as client:
        with pytest.raises(ValueError):
            GeminiExtractor("fake", client=client).extract(b"pdf")


@pytest.mark.parametrize("version", ["gemini-3.8-flash-001", None])
def test_actual_model_version_belongs_to_each_result(version):
    def handler(request):
        assert "/gemini-3.8-flash:" in str(request.url)
        return httpx.Response(
            200,
            json={
                "modelVersion": version,
                "candidates": [
                    {"finishReason": "STOP", "content": {"parts": [{"text": "{}"}]}}
                ],
            },
        )

    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        result = GeminiExtractor("fake", client=client).extract(b"pdf")
    assert result.model_version == (version or "gemini-3.8-flash")


def test_503_honors_server_delay_over_one_minute():
    calls, delays = [], []

    def handler(request):
        calls.append(1)
        if len(calls) == 1:
            return httpx.Response(
                503, json={"error": {"details": [{"retryDelay": "120s"}]}}
            )
        return httpx.Response(
            200, json={"candidates": [{"content": {"parts": [{"text": "{}"}]}}]}
        )

    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        assert (
            GeminiExtractor("fake", client=client, sleep=delays.append).extract(b"pdf")
            == {}
        )
    assert delays == [120]


def test_model_chain_order_and_no_lite():
    from pyeonit_crawler.gemini import MODEL_CHAIN

    assert MODEL_CHAIN == [
        "gemini-3.8-flash",
        "gemini-3.7-flash",
        "gemini-3.6-flash",
        "gemini-3.5-flash",
    ]
    assert all("lite" not in model for model in MODEL_CHAIN)
