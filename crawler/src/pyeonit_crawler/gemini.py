"""Gemini REST 추출. API 키는 로그 또는 URL에 포함하지 않는다."""

import base64
import json
import os
import time
from pathlib import Path

import httpx
from dotenv import dotenv_values

from .schema import PROMPT, SCHEMA


def load_api_key(env_file: Path | None = None) -> str:
    if key := os.environ.get("GEMINI_API_KEY"):
        return key
    if env_file is None:
        # cwd 또는 소스 checkout에서 가장 가까운 Git workspace 루트만 사용.
        for origin in (Path.cwd(), Path(__file__).resolve().parent):
            for parent in (origin, *origin.parents):
                if (parent / ".git").exists():
                    candidate = parent / ".env"
                    if candidate.is_file():
                        key = dotenv_values(candidate).get("GEMINI_API_KEY")
                        if key:
                            return key
                    break
    elif key := dotenv_values(env_file).get("GEMINI_API_KEY"):
        return key
    raise ValueError("GEMINI_API_KEY 환경변수 또는 워크스페이스 루트 .env가 필요합니다")


class GeminiExtractor:
    def __init__(
        self,
        api_key: str,
        model: str = "gemini-3.1-flash-lite",
        *,
        client: httpx.Client | None = None,
        sleep=time.sleep,
    ):
        self.api_key = api_key
        self.model = model
        self.client = client
        self.sleep = sleep

    def extract(self, pdf: bytes) -> dict:
        body = {
            "contents": [
                {
                    "parts": [
                        {
                            "inline_data": {
                                "mime_type": "application/pdf",
                                "data": base64.b64encode(pdf).decode(),
                            }
                        },
                        {"text": PROMPT},
                    ]
                }
            ],
            "generationConfig": {
                "responseMimeType": "application/json",
                "responseSchema": SCHEMA,
                "temperature": 0,
            },
        }
        if self.client is not None:
            return self._request(self.client, body)
        with httpx.Client(timeout=580) as client:
            return self._request(client, body)

    def _request(self, client: httpx.Client, body: dict) -> dict:
        # 최초 요청 + 최대 3회 재시도, 1/2/4초 지수 백오프.
        for attempt in range(4):
            try:
                response = client.post(
                    f"https://generativelanguage.googleapis.com/v1beta/models/{self.model}:generateContent",
                    headers={"x-goog-api-key": self.api_key},
                    json=body,
                )
                response.raise_for_status()
                parts = response.json()["candidates"][0]["content"]["parts"]
                return json.loads(
                    "".join(p.get("text", "") for p in parts if not p.get("thought"))
                )
            except (httpx.TimeoutException, httpx.HTTPStatusError) as exc:
                retryable = (
                    isinstance(exc, httpx.TimeoutException)
                    or exc.response.status_code == 503
                )
                if not retryable or attempt == 3:
                    raise
                self.sleep(2**attempt)
        raise RuntimeError("unreachable")
