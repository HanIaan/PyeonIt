"""초기 수집 CLI. DB에 쓰지 않고 PDF 및 JSON을 저장한다."""

import argparse
import asyncio
import hashlib
import json
import re
import threading
from datetime import UTC, datetime, time, timedelta
from pathlib import Path
from urllib.parse import urlsplit
from zoneinfo import ZoneInfo

import httpx
from playwright.async_api import async_playwright

from .download import DownloadedPDF, download_pdf
from .gemini import (
    DEFAULT_MODEL,
    MODEL_CHAIN,
    GeminiExtractor,
    load_api_key,
    quota_metadata,
)
from .validation import validate_totals

DEFAULT_UNIVERSITIES = Path(__file__).resolve().parents[2] / "data/universities.yaml"
ARTIFACTS = Path(__file__).resolve().parents[2] / "artifacts"
# Gemini 무료 일일 한도는 미국 태평양시 자정에 초기화된다(429 retryDelay로 검증).
PACIFIC = ZoneInfo("America/Los_Angeles")
KST = ZoneInfo("Asia/Seoul")
STAGES = {
    1: "① 통과·추출 데이터 없음",
    2: "② 추출했으나 미통과",
    3: "③ 1차 lite로만 통과",
}
# 기존 CLI 별칭 및 네트워크 smoke test 호환.
SCHOOLS = {
    "sogang": (
        "서강대학교",
        "https://admission.sogang.ac.kr/enter/html/transfer/guide.asp",
    ),
    "skku": (
        "성균관대학교",
        "https://admission.skku.edu/admission/html/transfer/guide.html",
    ),
    "yonsei": (
        "연세대학교",
        "https://admission.yonsei.ac.kr/seoul/admission/html/transfer/guide.asp",
    ),
}


def load_universities(path: Path) -> dict[str, dict]:
    """외부 YAML 의존성 없이 지정된 고정 형식만 읽고 모호한 입력은 거부한다.

    앵커, 태그, 중첩 객체 등 일반 YAML 기능은 지원하지 않는다.
    """
    schools = []
    school = source = None
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.split(" #", 1)[0].rstrip()
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        match = re.fullmatch(
            r"(- name: |  campus: |  region: |  sources:|    - kind: |      url: )(.*)",
            line,
        )
        if not match:
            raise ValueError(f"고정 YAML 형식 오류: {number}행")
        field, value = match.groups()
        if value.startswith('"'):
            value = json.loads(value)
        elif value.startswith("'") and value.endswith("'"):
            value = value[1:-1].replace("''", "'")
        if field == "- name: ":
            school = {"name": value, "sources": []}
            schools.append(school)
            source = None
        elif school is None:
            raise ValueError(f"학교 이름 누락: {number}행")
        elif field == "    - kind: ":
            source = {"kind": value}
            school["sources"].append(source)
        elif field == "      url: ":
            if source is None or "url" in source:
                raise ValueError(f"출처 kind 누락 또는 URL 중복: {number}행")
            source["url"] = value
        elif field == "  sources:":
            if value or school["sources"]:
                raise ValueError(f"출처 형식 오류: {number}행")
        else:
            key = field.strip().rstrip(": ")
            if key in school:
                raise ValueError(f"학교 필드 중복: {number}행")
            school[key] = value
    result = {}
    for entry in schools:
        if (
            not entry["name"]
            or "campus" not in entry
            or entry.get("region") not in {"SEOUL", "GYEONGGI", "INCHEON"}
            or not entry["sources"]
        ):
            raise ValueError("학교의 이름·캠퍼스·지역·출처를 확인하세요")
        for item in entry["sources"]:
            parsed = urlsplit(item.get("url", ""))
            if (
                item["kind"] not in {"NOTICE_BOARD", "GUIDE_PAGE"}
                or parsed.scheme not in {"http", "https"}
                or not parsed.hostname
            ):
                raise ValueError("출처 kind 또는 URL 오류")
        key = entry["name"] + (f"-{entry['campus']}" if entry["campus"] else "")
        key = next(
            (slug for slug, (name, _) in SCHOOLS.items() if name == entry["name"]), key
        )
        if key in result or re.search(r"[/\\\x00]", key) or key in {".", ".."}:
            raise ValueError("중복 또는 잘못된 학교 식별자")
        result[key] = entry
    if not result:
        raise ValueError("학교 목록이 비어 있습니다")
    return result


def write_json(path: Path, data: dict) -> None:
    temporary = path.with_suffix(".json.tmp")
    temporary.write_text(
        json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    temporary.replace(path)


class BudgetExhausted(Exception):
    """오늘 요청 예산 소진. 남은 학교는 QUOTA_WAIT."""


def quota_day(now: datetime) -> str:
    return now.astimezone(PACIFIC).date().isoformat()


def next_reset(now: datetime) -> datetime:
    day = now.astimezone(PACIFIC).date() + timedelta(days=1)
    return datetime.combine(day, time(), PACIFIC).astimezone(UTC)


def retry_delay_seconds(quota: list[dict]) -> float | None:
    for item in quota:
        if match := re.fullmatch(r"(\d+(?:\.\d+)?)s", item.get("retryDelay", "")):
            return float(match[1])
    return None


class RequestBudget:
    """태평양시 날짜별 generateContent 전송 수를 파일에 누적한다(여러 번 실행해도 합산)."""

    def __init__(self, path: Path, limit: int, now=lambda: datetime.now(UTC)):
        self.path, self.limit, self.now = path, limit, now
        self.lock = threading.Lock()
        self.exhausted = False
        # 손상된 파일을 0으로 초기화하면 한도를 넘길 수 있으므로 그대로 실패한다.
        self.state = json.loads(path.read_text()) if path.is_file() else {}

    def _roll(self) -> None:
        now = self.now()
        if self.state.get("day") != quota_day(now):
            self.state.update(day=quota_day(now), used=0)
        self.state["reset_at"] = next_reset(now).isoformat()

    def remaining(self) -> int:
        with self.lock:
            self._roll()
            left = (
                0
                if self.state.get("blocked_day") == self.state["day"]
                else max(self.limit - self.state["used"], 0)
            )
            self.exhausted |= left == 0
            return left

    def spend(self, model: str) -> None:
        with self.lock:
            self._roll()
            if (
                self.state["used"] >= self.limit
                or self.state.get("blocked_day") == self.state["day"]
            ):
                self.exhausted = True
                raise BudgetExhausted("DAILY_BUDGET")
            self.state["used"] += 1
            self.state.setdefault("days", {})[self.state["day"]] = self.state["used"]
            self._save()

    def record_429(self, model: str, quota: list[dict]) -> None:
        with self.lock:
            self._roll()
            self.state["blocked_day"] = self.state["day"]
            self.exhausted = True
            at = self.now()
            entry = {"at": at.isoformat(), "model": model, "quota": quota}
            delay = retry_delay_seconds(quota)
            if delay is not None:
                retry_at = at + timedelta(seconds=delay)
                entry["retry_at"] = retry_at.isoformat()
                # 일일 한도라면 0에 가까워야 한다. 크면 리셋 시각 가정이 틀린 것.
                entry["reset_gap_s"] = round(
                    (retry_at - next_reset(at)).total_seconds()
                )
                if (
                    any("PerDay" in q.get("quotaId", "") for q in quota)
                    and abs(entry["reset_gap_s"]) > 900
                ):
                    print(
                        f"경고: {model} 429 retryDelay 기준 리셋이 태평양 자정과 "
                        f"{entry['reset_gap_s']}초 다릅니다",
                        flush=True,
                    )
            self.state["last_429"] = entry
            self._save()

    def _save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        write_json(self.path, self.state)


def next_run_at(state: dict, margin: timedelta = timedelta(minutes=5)):
    """마지막 429의 retryDelay(없으면 저장된 리셋 시각) + 여유 5분. 429 기록 없으면 None."""
    last = state.get("last_429")
    if not last:
        target = state.get("reset_at")
        return datetime.fromisoformat(target) + margin if target else None
    targets = [
        datetime.fromisoformat(value)
        for value in (last.get("retry_at"), state.get("reset_at"))
        if value
    ]
    return max(targets) + margin if targets else None


def matches_request(data: dict, school: str, year: int) -> bool:
    label = data.get("학교")
    return (
        data.get("학년도") == year
        and isinstance(label, str)
        and re.sub(r"\s+", "", school) in re.sub(r"\s+", "", label)
    )


class WrongDocumentError(ValueError):
    def __init__(self, data: dict):
        super().__init__("잘못된 문서")
        self.data = data


async def extract_validated(
    extractor, content: bytes, school: str, year: int, tries: int = 3, retry=False
):
    """합계 불일치만 동일 PDF로 최대 2회 재추출한다. 최초 통과 즉시 채택.

    retry=True는 앞선 1회 추출 뒤 이어지는 재추출 단계라 검증불가에서도 멈추지 않는다.
    """
    attempts = []
    selected = None
    for _ in range(tries):
        try:
            data = await asyncio.to_thread(extractor.extract, content)
            events = getattr(data, "quota_events", None)
            if data.get("문서종류") != "편입학 모집요강":
                raise WrongDocumentError(data)
            if not matches_request(data, school, year):
                raise ValueError("추출된 학교 또는 학년도가 요청과 다릅니다")
            validation = validate_totals(data)
            attempts.append({"data": data, "validation": validation})
            if events:
                attempts[-1]["retried_429"] = events
            if selected is None or validation["status"] == "passed":
                selected = (data, validation)
            if validation["status"] == "passed" or (
                not retry
                and len(attempts) == 1
                and validation["status"] == "unverifiable"
            ):
                break
        except Exception as exc:
            if selected is None:
                raise
            # 이미 얻은 결과는 후속 재추출 오류로 버리지 않는다.
            error = {"error_type": type(exc).__name__}
            if getattr(exc, "quota_events", None):
                error["retried_429"] = exc.quota_events
            if isinstance(exc, BudgetExhausted):
                attempts.append({**error, "error_code": "DAILY_BUDGET"})
                break
            if isinstance(exc, httpx.HTTPStatusError):
                error.update(
                    error_code=safe_error_code(exc),
                    http_status=exc.response.status_code,
                )
            if (
                isinstance(exc, httpx.HTTPStatusError)
                and exc.response.status_code == 429
            ):
                if metadata := quota_metadata(exc.response):
                    error["quota"] = metadata
            attempts.append(error)
            if (
                isinstance(exc, httpx.HTTPStatusError)
                and exc.response.status_code == 429
            ):
                break
    return *selected, attempts


def reusable_result(args, slug: str, entry: dict, models=None) -> dict | None:
    """같은 PDF의 통과 결과만 자동 재사용한다. lite 결과는 항상 재추출한다."""
    models = ["gemini-3.8-flash"]
    try:
        result = json.loads((args.output / f"{slug}-{args.year}.json").read_text())
        source = result["source"]
        data = result["data"]
        if not isinstance(data, dict) or not isinstance(result.get("validation"), dict):
            return None
        if (
            result["schema_version"] not in {3, 4}
            or source.get("year") != args.year
            or result["validation"]["status"] != "passed"
            or not any(source.get("model", "").startswith(model) for model in models)
            or "lite" in source.get("model", "").lower()
            or any(
                source.get(key) != entry[key] for key in ("name", "campus", "region")
            )
            or data.get("문서종류") != "편입학 모집요강"
            or not matches_request(data, entry["name"], args.year)
            or source["sha256"]
            != hashlib.sha256(
                (args.output / f"{slug}-{args.year}.pdf").read_bytes()
            ).hexdigest()
            or result["validation"] != validate_totals(data)
        ):
            return None
        return result
    except (OSError, ValueError, KeyError, TypeError):
        return None


def safe_error_code(exc: Exception) -> str:
    """예외 본문을 남기지 않고 허용된 원인 분류만 보존한다."""
    if isinstance(exc, httpx.HTTPStatusError):
        return f"HTTP_{exc.response.status_code}"
    if match := re.search(r"net::(ERR_[A-Z_]+)", str(exc)):
        return match[1]
    if "추출된 학교 또는 학년도가 요청과 다릅니다" == str(exc):
        return "IDENTITY_MISMATCH"
    if str(exc) == "잘못된 문서":
        return "WRONG_DOCUMENT"
    if "this._engines.set" in str(exc):
        return "PAGE_SCRIPT_CONFLICT"
    return type(exc).__name__


async def extract_with_models(
    extractors: dict,
    content: bytes,
    school: str,
    year: int,
    exhausted: dict,
    *,
    tries: int = 3,
    retry: bool = False,
    chain_on_fail: bool = True,
) -> dict:
    """429 또는 검증 미통과만 다음 모델로 넘긴다. 통과 결과만 채택한다.

    chain_on_fail=False(일일 예산 실행)는 429만 다음 모델로 넘긴다.
    """
    model_attempts = []
    diagnostic = None
    for model, extractor in extractors.items():
        if model in exhausted:
            model_attempts.append(
                {
                    "requested_model": model,
                    "status": "QUOTA_WAIT",
                    "quota": exhausted[model],
                    "skipped": True,
                }
            )
            continue
        try:
            data, validation, attempts = await extract_validated(
                extractor, content, school, year, tries, retry
            )
            actual_model = getattr(data, "model_version", model)
            trial = {
                "requested_model": model,
                "model": actual_model,
                "http_status": 200,
                "status": validation["status"],
                "extraction_attempts": attempts,
            }
            model_attempts.append(trial)
            for attempt in attempts:
                if attempt.get("http_status") == 429:
                    exhausted[model] = attempt.get("quota", [])
            result = {
                "data": data,
                "validation": validation,
                "extraction_attempts": attempts,
                "model": actual_model,
                "requested_model": model,
            }
            if validation["status"] == "passed":
                return {
                    **result,
                    "status": "passed",
                    "adopted": True,
                    "model_attempts": model_attempts,
                }
            terminal = next(
                (
                    a
                    for a in reversed(attempts)
                    if (a.get("http_status") is not None and a["http_status"] != 429)
                    or a.get("error_type")
                    in {
                        "TimeoutException",
                        "ReadTimeout",
                        "ConnectTimeout",
                        "WriteTimeout",
                        "PoolTimeout",
                    }
                ),
                None,
            )
            if terminal is not None:
                return {
                    **result,
                    **terminal,
                    "status": "error",
                    "adopted": False,
                    "model_attempts": model_attempts,
                }
            if not chain_on_fail:
                return {
                    **result,
                    "status": validation["status"],
                    "adopted": False,
                    "model_attempts": model_attempts,
                }
            if diagnostic is None:
                diagnostic = result
        except BudgetExhausted:
            model_attempts.append(
                {
                    "requested_model": model,
                    "status": "QUOTA_WAIT",
                    "reason": "DAILY_BUDGET",
                }
            )
            if diagnostic is not None:
                break
            return {
                "status": "QUOTA_WAIT",
                "budget_exhausted": True,
                "adopted": False,
                "data": None,
                "validation": None,
                "model_attempts": model_attempts,
                "model": model,
                "requested_model": model,
            }
        except Exception as exc:
            trial = {
                "requested_model": model,
                "status": "error",
                "error_type": type(exc).__name__,
                "error_code": safe_error_code(exc),
            }
            if getattr(exc, "quota_events", None):
                trial["retried_429"] = exc.quota_events
            if isinstance(exc, httpx.HTTPStatusError):
                trial["http_status"] = exc.response.status_code
                if exc.response.status_code == 429:
                    exhausted[model] = quota_metadata(exc.response)
                    trial.update(status="QUOTA_WAIT", quota=exhausted[model])
                    model_attempts.append(trial)
                    if getattr(extractor, "budget", None) is not None:
                        if not extractor.budget.exhausted:
                            extractor.budget.record_429(model, exhausted[model])
                        return {
                            "status": "QUOTA_WAIT",
                            "adopted": False,
                            "model_attempts": model_attempts,
                            "model": model,
                            "requested_model": model,
                        }
                    continue
            if isinstance(exc, WrongDocumentError):
                trial["model"] = getattr(exc.data, "model_version", model)
                trial["http_status"] = 200
            model_attempts.append(trial)
            # 503/타임아웃/잘못된 문서는 체인 전환하지 않는다.
            return {
                **trial,
                "status": "error",
                "adopted": False,
                "model_attempts": model_attempts,
                "model": trial.get("model", model),
                "requested_model": model,
                **(
                    {
                        "data": exc.data,
                        "validation": {
                            "status": "failed",
                            "passed": False,
                            "reason": "WRONG_DOCUMENT",
                        },
                    }
                    if isinstance(exc, WrongDocumentError)
                    else {}
                ),
            }
    status = (
        "QUOTA_WAIT"
        if all(model in exhausted for model in extractors)
        else diagnostic["validation"]["status"]
    )
    return {
        **(diagnostic or {}),
        "status": status,
        "adopted": False,
        "model_attempts": model_attempts,
        "model": (diagnostic or {}).get("model", list(extractors)[-1]),
        "requested_model": (diagnostic or {}).get(
            "requested_model", list(extractors)[-1]
        ),
    }


def first_source(entry: dict) -> str:
    """고정 모집요강 페이지를 먼저 시도하고 공지 게시판으로 보완하는 순서의 첫 URL."""
    return sorted(entry["sources"], key=lambda s: s["kind"] != "GUIDE_PAGE")[0]["url"]


def label(entry: dict) -> str:
    return entry["name"] + (f" ({entry['campus']})" if entry["campus"] else "")


def lite_passed(args, slug: str, sha256: str | None) -> bool:
    directory = getattr(args, "lite_results", None)
    if directory is None or not sha256:
        return False
    try:
        result = json.loads((directory / f"{slug}-{args.year}.json").read_text())
        return (
            result["validation"]["status"] == "passed"
            and "lite" in result["source"].get("model", "").lower()
            and result["source"]["sha256"] == sha256
        )
    except (OSError, ValueError, KeyError, TypeError):
        return False


def classify(args, slug: str, entry: dict, models: list[str]) -> tuple:
    """(단계, 사유). 단계는 'skip' 또는 1·2·3. 근거가 불분명하면 보수적으로 ①."""
    if reusable_result(args, slug, entry, models) is not None:
        return "skip", "같은 PDF의 gemini-3.8-flash 통과 결과"
    try:
        result = json.loads((args.output / f"{slug}-{args.year}.json").read_text())
        source = result["source"]
        data = result.get("data")
        validation = result.get("validation") or {}
    except (OSError, ValueError, KeyError, TypeError, AttributeError):
        return 1, "이전 결과 없음 또는 판독 불가(보수적으로 ①)"
    if source.get("page_url") != first_source(entry):
        return 1, "출처 교체: PDF 재다운로드 후 추출"
    if validation.get("passed") and "lite" in source.get("model", "").lower():
        return 3, "현재 PDF의 lite 모델만 통과"
    if (
        isinstance(data, dict)
        and data.get("모집인원")
        and validation.get("status") in {"passed", "failed", "unverifiable"}
        and validation.get("reason") != "WRONG_DOCUMENT"
    ):
        status = {"passed": "체인 밖 모델 통과", "failed": "불일치"}.get(
            validation["status"], "검증불가"
        )
        if lite_passed(args, slug, source.get("sha256")):
            status += "; 1차 lite 통과도 있으나 미통과 추출 데이터 우선(②)"
        return 2, f"{status} ({source.get('model', '모델 미상')})"
    if lite_passed(args, slug, source.get("sha256")):
        return 3, "1차 lite만 통과(같은 PDF), 체인 모델 통과 없음"
    reason = result.get("status") or "상태 없음"
    if validation.get("reason") == "WRONG_DOCUMENT":
        reason = "WRONG_DOCUMENT"
    return 1, f"{reason}, 추출 데이터 없음"


def chain_models(args) -> list[str]:
    if getattr(args, "model_chain", None):
        models = list(args.model_chain)
    else:
        models = (
            MODEL_CHAIN[MODEL_CHAIN.index(args.model) :]
            if args.model in MODEL_CHAIN
            else [args.model]
        )
    if any("lite" in model.lower() for model in models):
        raise ValueError("lite 모델은 허용하지 않습니다")
    return models


def parse_model_chain(value: str) -> list[str]:
    models = [m.strip() for m in value.split(",") if m.strip()]
    if (
        not models
        or len(set(models)) != len(models)
        or any("lite" in m.lower() or m not in MODEL_CHAIN for m in models)
    ):
        raise argparse.ArgumentTypeError(
            f"허용 모델(lite 제외)만 쉼표로: {','.join(MODEL_CHAIN)}"
        )
    return models


def quota_evidence(output: Path) -> dict[str, dict]:
    """model_attempts에서 모델별 응답·429 쿼터 차원을 모은다. 판정은 하지 않는다."""
    rows: dict[str, dict] = {}
    for path in sorted(output.glob("*.json")):
        if path.name in {"summary.json", "model_quotas.json", "quota-state.json"}:
            continue
        try:
            attempts = json.loads(path.read_text()).get("model_attempts") or []
        except (OSError, ValueError, AttributeError):
            continue
        previous = None
        for trial in attempts:
            if trial.get("skipped") or trial.get("reason") == "DAILY_BUDGET":
                previous = trial
                continue
            row = rows.setdefault(
                trial["requested_model"],
                {"200": 0, "429": 0, "기타": 0, "dims": set(), "ids": set()}
                | {"delays": set(), "after": {}},
            )
            nested = trial.get("extraction_attempts") or []
            quotas = (
                [trial.get("quota") or []] if trial.get("http_status") == 429 else []
            )
            quotas += [e["quota"] for e in trial.get("retried_429", [])]
            quotas += [
                q
                for a in nested
                for q in [a.get("quota") or []] * (a.get("http_status") == 429)
                + [e["quota"] for e in a.get("retried_429", [])]
            ]
            ok = (
                trial.get("http_status") == 200
                or trial.get("error_code") == ("WRONG_DOCUMENT")
                or trial.get("status")
                in {
                    "passed",
                    "failed",
                    "unverifiable",
                }
            )
            code = "200" if ok else "429" if trial.get("http_status") == 429 else "기타"
            row[code] += 1
            row["429"] += len(quotas) - (code == "429")
            for quota in quotas:
                for item in quota:
                    if model := item.get("quotaDimensions", {}).get("model"):
                        row["dims"].add(model)
                    if "quotaId" in item:
                        row["ids"].add(f"{item['quotaId']}={item.get('quotaValue')}")
                    if "retryDelay" in item:
                        row["delays"].add(item["retryDelay"])
            if previous is not None and previous.get("status") == "QUOTA_WAIT":
                key = f"{previous['requested_model']} 429 후 {code}"
                row["after"][key] = row["after"].get(key, 0) + 1
            previous = trial
    return rows


def quota_report(output: Path) -> str:
    lines = [
        "| 요청 모델 | 200 | 429 | 기타 | 429 quotaDimensions.model | quotaId=quotaValue"
        " | retryDelay | 직전 모델 429 후 이 모델 응답 |",
        "| --- | ---: | ---: | ---: | --- | --- | --- | --- |",
    ]
    for model, row in quota_evidence(output).items():
        after = ", ".join(f"{k}×{v}" for k, v in sorted(row["after"].items()))
        lines.append(
            f"| {model} | {row['200']} | {row['429']} | {row['기타']} | "
            f"{', '.join(sorted(row['dims'])) or '—'} | "
            f"{', '.join(sorted(row['ids'])) or '—'} | "
            f"{', '.join(sorted(row['delays'])) or '—'} | {after or '—'} |"
        )
    lines.append(
        "\n근거 읽기: 앞 모델 429 직후 이 모델이 200이면 독립 한도 근거, "
        "이 모델의 429 quotaDimensions.model이 다른 모델이면 공유 가능성. 판정은 조율자."
    )
    return "\n".join(lines)


def print_next_run(path: Path) -> None:
    state = json.loads(path.read_text()) if path.is_file() else {}
    at = next_run_at(state)
    if at is None:
        print("다음 실행 권장 시각: 기록 없음 (quota-state에 429 기록이 없습니다)")
        return
    last = state.get("last_429") or {"model": "예산", "at": "일일 상한"}
    print(
        f"다음 실행 권장 시각: {at.astimezone(KST):%Y-%m-%d %H:%M} KST / "
        f"{at.astimezone(UTC):%Y-%m-%d %H:%M} UTC "
        f"(마지막 429 {last['model']} {last['at']} + retryDelay + 여유 5분)"
    )
    if "reset_gap_s" in last:
        print(f"태평양 자정 리셋 가정과의 차이: {last['reset_gap_s']}초")
    if at <= datetime.now(UTC):
        print("이미 지난 시각입니다. 지금 실행할 수 있습니다.")


async def backfill(args: argparse.Namespace) -> int:
    state_path = getattr(args, "quota_state", None) or args.output / "quota-state.json"
    if getattr(args, "next_run", False):
        print_next_run(state_path)
        return 0
    if getattr(args, "quota_report", False):
        print(quota_report(args.output))
        return 0
    schools = load_universities(getattr(args, "universities", DEFAULT_UNIVERSITIES))
    selected = args.schools or list(schools)
    if unknown := set(selected) - schools.keys():
        raise ValueError(f"목록에 없는 학교: {', '.join(sorted(unknown))}")
    models = chain_models(args)
    budget = RequestBudget(state_path, getattr(args, "daily_request_limit", 20))
    plan = {slug: classify(args, slug, schools[slug], models) for slug in selected}
    order = sorted(
        selected,
        key=lambda s: (
            0 if plan[s][0] == "skip" else plan[s][0],
            schools[s]["name"],
            schools[s]["campus"],
        ),
    )
    print("| 순서 | 학교 | 단계 | 근거 |\n| ---: | --- | --- | --- |", flush=True)
    for number, slug in enumerate(order, 1):
        stage, reason = plan[slug]
        print(
            f"| {number} | {label(schools[slug])} | "
            f"{STAGES.get(stage, '건너뜀')} | {reason} |",
            flush=True,
        )
    remaining = budget.remaining()
    print(
        f"요청 예산: 태평양 날짜 {budget.state['day']} 사용 {budget.state['used']}"
        f"/{budget.limit}, 남음 {remaining}; 리셋 "
        f"{datetime.fromisoformat(budget.state['reset_at']).astimezone(KST):%m-%d %H:%M} KST;"
        f" 모델 체인 {','.join(models)}",
        flush=True,
    )
    if getattr(args, "dry_run", False):
        queue = [s for s in order if plan[s][0] != "skip"]
        print(
            f"오늘 처리 예정(학교당 1차 추출 1요청 가정, {min(remaining, len(queue))}곳):"
        )
        for slug in queue[:remaining]:
            print(f"- {label(schools[slug])} [{STAGES[plan[slug][0]]}]")
        print(f"QUOTA_WAIT 예정 {len(queue[remaining:])}곳: ", end="")
        print(", ".join(label(schools[s]) for s in queue[remaining:]) or "없음")
        print("재추출(최대 2회)은 1차 추출 뒤 남은 예산으로만 한다. Gemini 호출 0회.")
        return 0
    api_key = load_api_key(args.env_file)
    extractors = {model: GeminiExtractor(api_key, model) for model in models}
    for extractor in extractors.values():
        extractor.year = args.year
        extractor.budget = budget
        extractor.first_pass = True
    exhausted = {}
    args.output.mkdir(parents=True, exist_ok=True)
    summary = {}
    retries = {}

    def save(slug, entry, source, pdf, pdf_path, outcome, record):
        data = outcome.get("data")
        attempts = outcome.get("extraction_attempts", [])
        result = {
            "schema_version": 4,
            "source": {
                **entry,
                "school": entry["name"],
                "page_url": source["url"],
                "pdf_url": pdf.url,
                "sha256": pdf.sha256,
                "pdf_file": pdf_path.name,
                "collected_at": datetime.now(UTC).isoformat(),
                "model": outcome["model"],
                "requested_model": outcome["requested_model"],
                "year": args.year,
            },
            "status": outcome["status"],
            "adopted": outcome["adopted"],
            "model_attempts": outcome["model_attempts"],
            "data": data,
            "validation": outcome.get("validation"),
            "extraction_attempts": attempts,
        }
        write_json(args.output / f"{slug}-{args.year}.json", result)
        record.update(
            status=outcome["status"],
            model=outcome["model"],
            model_attempts=outcome["model_attempts"],
            sha256=pdf.sha256,
            json_file=f"{slug}-{args.year}.json",
            extraction_attempts=len(attempts),
        )
        if outcome["status"] == "error":
            record.update(
                {
                    key: outcome[key]
                    for key in ("error_type", "error_code", "http_status")
                    if key in outcome
                }
            )

    def budget_wait(record, school):
        # 예산 소진: 기존 JSON(진단 데이터 포함)은 덮어쓰지 않는다.
        record.update(status="QUOTA_WAIT", reason="DAILY_BUDGET")
        print(f"{school}: QUOTA_WAIT (오늘 요청 예산 소진)", flush=True)

    async with async_playwright() as pw:
        browser = await pw.chromium.launch()
        try:
            context = await browser.new_context()
            semaphore = asyncio.Semaphore(
                1
            )  # 429 이후 새 요청이 나가지 않도록 순차 실행

            async def collect(slug):
                entry = schools[slug]
                school = entry["name"]
                record = {
                    "school": school,
                    "campus": entry["campus"],
                    "region": entry["region"],
                    "requested_model": models[0],
                    "priority": plan[slug][0],
                }
                cached = reusable_result(args, slug, entry, models)
                if cached is not None:
                    cached["schema_version"] = 4
                    cached["source"].setdefault(
                        "requested_model", cached["source"]["model"]
                    )
                    cached.update(status="passed", adopted=True)
                    cached.setdefault(
                        "model_attempts",
                        [
                            {
                                "requested_model": cached["source"].get(
                                    "requested_model", cached["source"]["model"]
                                ),
                                "model": cached["source"]["model"],
                                "status": "passed",
                                "reused": True,
                            }
                        ],
                    )
                    write_json(args.output / f"{slug}-{args.year}.json", cached)
                    summary[slug] = {
                        **record,
                        "status": cached["validation"]["status"],
                        "sha256": cached["source"]["sha256"],
                        "json_file": f"{slug}-{args.year}.json",
                        "extraction_attempts": len(
                            cached.get("extraction_attempts", [])
                        ),
                        "reused": True,
                        "model": cached["source"]["model"],
                        "model_attempts": cached.get("model_attempts", []),
                    }
                    print(
                        f"{school}: 기존 결과 재사용 ({summary[slug]['status']})",
                        flush=True,
                    )
                    return
                async with semaphore:
                    if budget.remaining() == 0:
                        summary[slug] = record
                        budget_wait(record, school)
                        return
                    print(f"{school}: PDF 수집 중", flush=True)
                    stage = "download"
                    try:
                        errors = []
                        pdf = None
                        sources = sorted(
                            entry["sources"], key=lambda s: s["kind"] != "GUIDE_PAGE"
                        )
                        # PDF만 재사용하고 JSON은 현재 모델/프롬프트로 다시 추출한다.
                        reuse_dir = (
                            args.output
                            if (args.output / f"{slug}-{args.year}.pdf").is_file()
                            else getattr(args, "reuse_pdfs", None)
                        )
                        if reuse_dir is not None:
                            path = reuse_dir / f"{slug}-{args.year}.pdf"
                            if path.is_file():
                                content = path.read_bytes()
                                if not content.lstrip().startswith(b"%PDF-"):
                                    raise ValueError("재사용 파일이 PDF가 아닙니다")
                                if path.with_suffix(".json").is_file():
                                    previous = json.loads(
                                        path.with_suffix(".json").read_text()
                                    )
                                    metadata = previous["source"]
                                else:
                                    # 1차 추출 실패로 JSON이 없으면 수집 summary의 원본 증거 사용.
                                    prior = json.loads(
                                        (reuse_dir / "summary.json").read_text()
                                    )[slug]
                                    if prior.get("pdf_file") != path.name:
                                        raise ValueError("재사용 PDF 파일명 불일치")
                                    metadata = {
                                        **prior,
                                        "name": prior["school"],
                                        "year": args.year,
                                        "page_url": prior.get("page_url"),
                                    }
                                    record["pdf_reuse_metadata"] = "summary"
                                digest = hashlib.sha256(content).hexdigest()
                                if (
                                    "pdf_reuse_metadata" not in record
                                    and metadata.get("page_url") != sources[0]["url"]
                                ):
                                    # YAML 첫 출처가 바뀌면(잘못된 PDF 교체) 새로 받는다.
                                    record["pdf_reuse_skipped"] = "SOURCE_CHANGED"
                                elif (
                                    metadata["sha256"] != digest
                                    or metadata.get("name") != school
                                    or metadata.get("campus") != entry["campus"]
                                    or metadata.get("year") != args.year
                                ):
                                    raise ValueError(
                                        "재사용 PDF의 해시 또는 학교/학년도 불일치"
                                    )
                                else:
                                    source = {"url": metadata["page_url"]}
                                    pdf = DownloadedPDF(
                                        metadata["pdf_url"], content, digest
                                    )
                                    record["pdf_reused"] = True
                        for candidate_source in sources:
                            if pdf is not None:
                                break
                            source = candidate_source
                            try:
                                pdf = await download_pdf(
                                    context, source["url"], year=args.year
                                )
                                break
                            except Exception as exc:
                                errors.append(
                                    {
                                        "url": source["url"],
                                        "error_type": type(exc).__name__,
                                        "error_code": safe_error_code(exc),
                                    }
                                )
                        record["source_attempts"] = errors
                        if pdf is None:
                            record["status"] = (
                                "no_guide"
                                if all(
                                    e["error_type"] == "NoGuideError" for e in errors
                                )
                                else "error"
                            )
                            record["stage"] = stage
                        else:
                            pdf_path = args.output / f"{slug}-{args.year}.pdf"
                            pdf_path.write_bytes(pdf.content)
                            record.update(
                                sha256=pdf.sha256,
                                pdf_file=pdf_path.name,
                                pdf_url=pdf.url,
                            )
                            stage = "extract"
                            print(f"{school}: Gemini 추출 중", flush=True)
                            # 예산 절약: 모든 대상 학교를 먼저 1회씩 추출하고, 불일치 재추출은 남은 예산으로 뒤에 한다.
                            outcome = await extract_with_models(
                                extractors,
                                pdf.content,
                                school,
                                args.year,
                                exhausted,
                                tries=1,
                                chain_on_fail=False,
                            )
                            if outcome.get("budget_exhausted"):
                                budget_wait(record, school)
                            else:
                                save(
                                    slug, entry, source, pdf, pdf_path, outcome, record
                                )
                                if outcome["status"] == "failed":
                                    retries[slug] = (source, pdf, pdf_path, outcome)
                    except Exception as exc:
                        record.update(
                            status="error",
                            stage=stage,
                            error_type=type(exc).__name__,
                            error_code=safe_error_code(exc),
                        )
                        if isinstance(exc, httpx.HTTPStatusError):
                            record["http_status"] = exc.response.status_code
                    summary[slug] = record
                    # 중간 결과도 보존한다. 한 학교 오류가 다른 학교를 중단시키지 않는다.
                    write_json(args.output / "summary.json", summary)
                    write_json(args.output / "model_quotas.json", exhausted)
                    print(f"{school}: {record['status']}", flush=True)

            async def retry_failed(slug):
                source, pdf, pdf_path, first = retries.pop(slug)
                entry, record = schools[slug], summary[slug]
                if budget.remaining() == 0:
                    record["retry"] = "DAILY_BUDGET"
                    return
                for extractor in extractors.values():
                    extractor.first_pass = False
                start = models.index(first["requested_model"])
                second = await extract_with_models(
                    {m: extractors[m] for m in models[start:]},
                    pdf.content,
                    entry["name"],
                    args.year,
                    exhausted,
                    tries=2,
                    retry=True,
                    chain_on_fail=False,
                )
                merged = second if second["status"] == "passed" else first
                merged = {
                    **merged,
                    "model_attempts": first["model_attempts"]
                    + second["model_attempts"],
                    "extraction_attempts": first.get("extraction_attempts", [])
                    + second.get("extraction_attempts", []),
                }
                save(slug, entry, source, pdf, pdf_path, merged, record)
                write_json(args.output / "summary.json", summary)
                write_json(args.output / "model_quotas.json", exhausted)
                print(f"{entry['name']}: 재추출 후 {record['status']}", flush=True)

            # ③(1차 lite만 통과)은 ①②의 1회 추출·재추출을 모두 마친 뒤 남은 예산으로만.
            for stages in ({"skip", 1, 2}, {3}):
                batch = [s for s in order if plan[s][0] in stages]
                await asyncio.gather(*(collect(slug) for slug in batch))
                for slug in [s for s in batch if s in retries]:
                    await retry_failed(slug)
        finally:
            await browser.close()
    write_json(args.output / "summary.json", {slug: summary[slug] for slug in selected})
    write_json(args.output / "model_quotas.json", exhausted)
    return (
        0
        if budget.exhausted
        or all(model in exhausted for model in models)
        or all(r["status"] in {"passed", "QUOTA_WAIT"} for r in summary.values())
        else 1
    )


def main() -> None:
    parser = argparse.ArgumentParser(description="편입 모집요강 수집기")
    sub = parser.add_subparsers(dest="command", required=True)
    command = sub.add_parser("backfill", help="게시된 요강 PDF 및 추출 JSON 저장")
    command.add_argument("--year", type=int, default=2026)
    command.add_argument(
        "--schools",
        nargs="+",
        help="YAML 학교 이름(캠퍼스는 이름-캠퍼스), 기존 sogang/skku/yonsei 별칭",
    )
    command.add_argument("--universities", type=Path, default=DEFAULT_UNIVERSITIES)
    command.add_argument("--output", type=Path, default=ARTIFACTS)
    command.add_argument("--model", choices=MODEL_CHAIN, default=DEFAULT_MODEL)
    command.add_argument(
        "--model-chain",
        type=parse_model_chain,
        help=f"쉼표 목록(기본 {','.join(MODEL_CHAIN)}); lite 불가",
    )
    command.add_argument(
        "--daily-request-limit",
        type=int,
        default=20,
        help="태평양시 날짜별 generateContent 전송 상한(재시도·체인 이동 포함)",
    )
    command.add_argument(
        "--quota-state", type=Path, default=ARTIFACTS / "quota-state.json"
    )
    command.add_argument(
        "--lite-results",
        type=Path,
        default=ARTIFACTS / "artifacts",
        help="③ 분류용 1차 lite 결과 폴더",
    )
    command.add_argument(
        "--dry-run", action="store_true", help="분류·예산 계획만 출력(Gemini 호출 0)"
    )
    command.add_argument(
        "--next-run", action="store_true", help="마지막 429 기준 다음 실행 권장 시각"
    )
    command.add_argument(
        "--quota-report", action="store_true", help="모델별 한도 독립/공유 근거 표"
    )
    command.add_argument(
        "--reuse-pdfs",
        type=Path,
        help="원본 해시와 출처가 있는 기존 PDF 폴더; JSON은 재추출",
    )
    command.add_argument("--env-file", type=Path)
    command.add_argument("--concurrency", type=int, default=3)
    command.add_argument(
        "--resume",
        action="store_true",
        help="호환 옵션: 같은 PDF의 통과 결과는 항상 자동 재사용",
    )
    load_command = sub.add_parser("load", help="채택·합계 검증 통과 결과만 DB 적재")
    load_command.add_argument("--input", type=Path, default=ARTIFACTS / "t6-chain")
    load_command.add_argument("--universities", type=Path, default=DEFAULT_UNIVERSITIES)
    load_command.add_argument("--storage-dir", type=Path, default=ARTIFACTS / "pdfs")
    load_command.add_argument(
        "--database-url", default=None, help="기본 DATABASE_URL 환경변수"
    )
    args = parser.parse_args()
    if args.command == "load":
        from .load import run_load

        raise SystemExit(run_load(args))
    if args.concurrency < 1:
        parser.error("--concurrency는 1 이상이어야 합니다")
    if args.daily_request_limit < 0:
        parser.error("--daily-request-limit은 0 이상이어야 합니다")
    raise SystemExit(asyncio.run(backfill(args)))
