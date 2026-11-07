"""Gemini REST 추출. API 키는 로그 또는 URL에 포함하지 않는다."""

import base64
import json
import os
import re
import time
from copy import deepcopy
from pathlib import Path

import httpx
from dotenv import dotenv_values

from .schema import SCHEMA as BASE_SCHEMA

MODEL_CHAIN = [
    "gemini-3.8-flash",
    "gemini-3.7-flash",
    "gemini-3.6-flash",
    "gemini-3.5-flash",
]
DEFAULT_MODEL = MODEL_CHAIN[0]
INITIAL_OUTPUT_TOKENS = 32768
MAX_OUTPUT_TOKENS = 65536
SCHEMA = deepcopy(BASE_SCHEMA)
SCHEMA["properties"]["문서종류"] = {
    "type": "STRING",
    "enum": ["편입학 모집요강", "잘못된 문서"],
}
SCHEMA["required"].append("문서종류")

PROMPT = """먼저 PDF의 문서종류를 판별하라. 요청 학년도의 대학 편입학 모집요강이면
문서종류='편입학 모집요강', 다른 학년도·수시·정시·신입학·대학원·기본계획·공지·결과 자료이면
문서종류='잘못된 문서'로 기록하고 모집인원/소계/총계/일정은 빈 배열로 반환하라.
학교와 학년도는 PDF에 적힌 실제 값을 기록하라.
해당 모집요강에서는 다음을 추출하라.
- 모집인원: 표의 모든 전형 열(일반편입, 학사편입, 정원외 특별전형 등 열 이름 그대로)을 빠짐없이.
입학정원·여석·전년도 열은 제외. 원문에는 셀에 적힌 그대로('3', '1명 이내', '8명 내외', '약간명', '미선발', '-').
인원은 확정 숫자일 때만 정수, '이내/내외/약간명'이면 null, '미선발'·'-'·실제 빈칸이면 0.
판독 불가 또는 추출에서 누락된 칸은 null로 두고 원문에 '판독불가' 또는 '누락'을 적어 실제 빈칸과 구분하라.
셀이 여러 학과에 걸친 병합 셀(단과대 단위 인원)이면 학과에 나누지 말고 단위='단과대학' 행으로 따로 기록.
그 병합 전형의 해당 학과 셀은 0으로 기록하고 원문은 '단과대학 병합 셀 참조'로 표시하여 중복 집계하지 말 것.
단과대학 행에는 병합된 전형 열만 기록한다. 학과마다 모든 전형 열을 기록할 것.
첫 모집인원 표가 여러 페이지에 걸치면 마지막 페이지까지 모든 모집단위를 빠짐없이 추출하라.
같은 표가 전형별 안내 등에서 반복되면 첫 모집인원 표만 사용하고 중복 행을 만들지 말 것.
소계·합계 행은 학과 행에 넣지 말 것.
- 소계/총계: 표에 적힌 계열별 소계와 총계를 그대로(직접 계산하지 말 것). 없으면 빈 배열.
- 일정: 원서접수, 필기시험, 면접, 합격자 발표, 등록 등. 날짜는 YYYY-MM-DD 또는 YYYY-MM-DD HH:MM.
문서에 없는 값은 지어내지 말 것."""


class ExtractionResult(dict):
    """응답별 모델 버전. 동시 추출 간 메타데이터가 섞이지 않게 결과에 결합한다."""

    def __init__(self, data: dict, model_version: str):
        super().__init__(data)
        self.model_version = model_version


class OutputTokenLimitError(ValueError):
    """출력 토큰 상한에서도 응답이 잘려 추출 실패."""


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
        model: str = DEFAULT_MODEL,
        *,
        client: httpx.Client | None = None,
        sleep=time.sleep,
    ):
        self.api_key = api_key
        self.model = model
        self.year = 2026
        self.client = client
        self.sleep = sleep
        # 일일 요청 예산(cli.RequestBudget). 실제 generateContent 전송마다 spend 한다.
        self.budget = None

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
                        {"text": PROMPT + f"\n요청 학년도: {self.year}학년도."},
                    ]
                }
            ],
            "generationConfig": {
                "responseMimeType": "application/json",
                "responseSchema": SCHEMA,
                "temperature": 0,
                "maxOutputTokens": INITIAL_OUTPUT_TOKENS,
            },
        }
        if self.client is not None:
            return self._extract_complete(self.client, body)
        with httpx.Client(timeout=580) as client:
            return self._extract_complete(client, body)

    def _extract_complete(self, client: httpx.Client, body: dict) -> dict:
        # 재시도로 회복된 429도 한도 공유 판정 근거라 결과/예외에 붙여 보존한다.
        events = []
        try:
            return self._extract_events(client, body, events)
        except Exception as exc:
            exc.quota_events = events
            raise

    def _extract_events(self, client: httpx.Client, body: dict, events: list) -> dict:
        while True:
            candidate = self._request(client, body, events)
            if candidate.get("finishReason") == "MAX_TOKENS":
                limit = body["generationConfig"]["maxOutputTokens"]
                if limit >= MAX_OUTPUT_TOKENS:
                    raise OutputTokenLimitError("MAX_TOKENS")
                body["generationConfig"]["maxOutputTokens"] = min(
                    limit * 2, MAX_OUTPUT_TOKENS
                )
                continue
            if candidate.get("finishReason", "STOP") != "STOP":
                raise ValueError("Gemini 응답이 정상 완료되지 않았습니다")
            parts = candidate["content"]["parts"]
            result = ExtractionResult(
                json.loads(
                    "".join(p.get("text", "") for p in parts if not p.get("thought"))
                ),
                candidate.get("modelVersion") or self.model,
            )
            result.quota_events = events
            return result

    def _request(self, client: httpx.Client, body: dict, events=None) -> dict:
        # 최초 요청 + 최대 3회 재시도, 1/2/4초 지수 백오프.
        for attempt in range(4):
            try:
                if self.budget is not None:
                    self.budget.spend(self.model)
                response = client.post(
                    f"https://generativelanguage.googleapis.com/v1beta/models/{self.model}:generateContent",
                    headers={"x-goog-api-key": self.api_key},
                    json=body,
                )
                response.raise_for_status()
                payload = response.json()
                return {
                    **payload["candidates"][0],
                    "modelVersion": payload.get("modelVersion"),
                }
            except (httpx.TimeoutException, httpx.HTTPStatusError) as exc:
                delay = 2**attempt
                retryable = isinstance(exc, httpx.TimeoutException)
                if isinstance(exc, httpx.HTTPStatusError):
                    retryable = exc.response.status_code == 503
                    if retryable:
                        delay = quota_retry_delay(exc.response, max_delay=None) or delay
                    if exc.response.status_code == 429:
                        metadata = quota_metadata(exc.response)
                        if self.budget is not None:
                            self.budget.record_429(self.model, metadata)
                        server_delay = quota_retry_delay(exc.response)
                        retryable = not quota_exhausted(exc.response)
                        delay = server_delay or delay
                if not retryable or attempt == 3:
                    raise
                if (
                    isinstance(exc, httpx.HTTPStatusError)
                    and exc.response.status_code == 429
                    and events is not None
                ):
                    # 최종 429는 예외로 cli가 기록한다. 재시도로 넘긴 429만 여기 남긴다.
                    events.append({"model": self.model, "quota": metadata})
                self.sleep(delay)
        raise RuntimeError("unreachable")


def quota_exhausted(response: httpx.Response) -> bool:
    """일일 할당량/기존 장기 제한은 즉시 해당 학교 실패로 보존한다."""
    try:
        details = response.json().get("error", {}).get("details", [])
    except (ValueError, AttributeError):
        return False
    for detail in details:
        if any("PerDay" in v.get("quotaId", "") for v in detail.get("violations", [])):
            return True
        if match := re.fullmatch(r"(\d+(?:\.\d+)?)s", detail.get("retryDelay", "")):
            if float(match[1]) > 60:
                return True
    return False


def quota_retry_delay(
    response: httpx.Response, *, max_delay: float | None = 60
) -> float | None:
    """명시된 단기 할당량 대기만 재시도한다. 일일 제한/무제한 대기는 금지."""
    retry_after = response.headers.get("Retry-After", "")
    header_delay = (
        float(retry_after) if re.fullmatch(r"\d+(?:\.\d+)?", retry_after) else None
    )
    try:
        details = response.json().get("error", {}).get("details", [])
    except (ValueError, AttributeError):
        details = []
    violations = [v for d in details for v in d.get("violations", [])]
    if any("PerDay" in v.get("quotaId", "") for v in violations):
        return None
    for detail in details:
        if match := re.fullmatch(r"(\d+(?:\.\d+)?)s", detail.get("retryDelay", "")):
            delay = float(match[1])
            if delay > 0 and (max_delay is None or delay <= max_delay):
                return delay
    return (
        header_delay
        if header_delay
        and header_delay > 0
        and (max_delay is None or header_delay <= max_delay)
        else None
    )


def quota_metadata(response: httpx.Response) -> list[dict]:
    """오류 메시지/URL/헤더 없이 서버가 명시한 할당량 필드만 보존한다."""
    try:
        details = response.json().get("error", {}).get("details", [])
    except (ValueError, AttributeError):
        return []
    result = []
    for detail in details:
        for violation in detail.get("violations", []):
            result.append(
                {
                    k: violation[k]
                    for k in ("quotaId", "quotaMetric", "quotaValue", "quotaDimensions")
                    if k in violation
                }
            )
        if "retryDelay" in detail:
            result.append({"retryDelay": detail["retryDelay"]})
    return result
