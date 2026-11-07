"""초기 수집 CLI. DB에 쓰지 않고 PDF 및 JSON을 저장한다."""

import argparse
import asyncio
import hashlib
import json
import re
from datetime import UTC, datetime
from pathlib import Path
from urllib.parse import urlsplit

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


async def extract_validated(extractor, content: bytes, school: str, year: int):
    """합계 불일치만 동일 PDF로 최대 2회 재추출한다. 최초 통과 즉시 채택."""
    attempts = []
    selected = None
    for _ in range(3):
        try:
            data = await asyncio.to_thread(extractor.extract, content)
            if data.get("문서종류") != "편입학 모집요강":
                raise WrongDocumentError(data)
            if not matches_request(data, school, year):
                raise ValueError("추출된 학교 또는 학년도가 요청과 다릅니다")
            validation = validate_totals(data)
            attempts.append({"data": data, "validation": validation})
            if selected is None or validation["status"] == "passed":
                selected = (data, validation)
            if validation["status"] == "passed" or (
                len(attempts) == 1 and validation["status"] == "unverifiable"
            ):
                break
        except Exception as exc:
            if selected is None:
                raise
            # 이미 얻은 결과는 후속 재추출 오류로 버리지 않는다.
            error = {"error_type": type(exc).__name__}
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


def reusable_result(args, slug: str, entry: dict) -> dict | None:
    """같은 PDF의 통과 결과만 자동 재사용한다. lite 결과는 항상 재추출한다."""
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
            or not any(
                source.get("model", "").startswith(model) for model in MODEL_CHAIN
            )
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
    extractors: dict, content: bytes, school: str, year: int, exhausted: dict
) -> dict:
    """429 또는 검증 미통과만 다음 모델로 넘긴다. 통과 결과만 채택한다."""
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
                extractor, content, school, year
            )
            actual_model = getattr(data, "model_version", model)
            trial = {
                "requested_model": model,
                "model": actual_model,
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
            if diagnostic is None:
                diagnostic = result
        except Exception as exc:
            trial = {
                "requested_model": model,
                "status": "error",
                "error_type": type(exc).__name__,
                "error_code": safe_error_code(exc),
            }
            if isinstance(exc, httpx.HTTPStatusError):
                trial["http_status"] = exc.response.status_code
                if exc.response.status_code == 429:
                    exhausted[model] = quota_metadata(exc.response)
                    trial.update(status="QUOTA_WAIT", quota=exhausted[model])
                    model_attempts.append(trial)
                    continue
            if isinstance(exc, WrongDocumentError):
                trial["model"] = getattr(exc.data, "model_version", model)
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


async def backfill(args: argparse.Namespace) -> int:
    schools = load_universities(getattr(args, "universities", DEFAULT_UNIVERSITIES))
    selected = args.schools or list(schools)
    if unknown := set(selected) - schools.keys():
        raise ValueError(f"목록에 없는 학교: {', '.join(sorted(unknown))}")
    api_key = load_api_key(args.env_file)
    models = (
        MODEL_CHAIN[MODEL_CHAIN.index(args.model) :]
        if args.model in MODEL_CHAIN
        else [args.model]
    )
    extractors = {model: GeminiExtractor(api_key, model) for model in models}
    for extractor in extractors.values():
        extractor.year = args.year
    exhausted = {}
    args.output.mkdir(parents=True, exist_ok=True)
    summary = {}
    async with async_playwright() as pw:
        browser = await pw.chromium.launch()
        try:
            context = await browser.new_context()
            semaphore = asyncio.Semaphore(getattr(args, "concurrency", 3))

            async def collect(slug):
                entry = schools[slug]
                school = entry["name"]
                record = {
                    "school": school,
                    "campus": entry["campus"],
                    "region": entry["region"],
                    "requested_model": args.model,
                }
                cached = reusable_result(args, slug, entry)
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
                    print(f"{school}: PDF 수집 중", flush=True)
                    stage = "download"
                    try:
                        errors = []
                        pdf = None
                        # 고정 모집요강 페이지를 먼저 시도하고 공지 게시판으로 보완한다.
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
                                    metadata["sha256"] != digest
                                    or metadata.get("name") != school
                                    or metadata.get("campus") != entry["campus"]
                                    or metadata.get("year") != args.year
                                ):
                                    raise ValueError(
                                        "재사용 PDF의 해시 또는 학교/학년도 불일치"
                                    )
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
                            outcome = await extract_with_models(
                                extractors, pdf.content, school, args.year, exhausted
                            )
                            data = outcome.get("data")
                            validation = outcome.get("validation")
                            attempts = outcome.get("extraction_attempts", [])
                            result = {
                                "schema_version": 4,
                                "source": {
                                    **entry,
                                    "school": school,
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
                                "validation": validation,
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
                                        for key in (
                                            "error_type",
                                            "error_code",
                                            "http_status",
                                        )
                                        if key in outcome
                                    }
                                )
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

            await asyncio.gather(*(collect(slug) for slug in selected))
        finally:
            await browser.close()
    write_json(args.output / "summary.json", {slug: summary[slug] for slug in selected})
    write_json(args.output / "model_quotas.json", exhausted)
    return (
        0
        if all(model in exhausted for model in models)
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
    command.add_argument(
        "--output", type=Path, default=Path(__file__).resolve().parents[2] / "artifacts"
    )
    command.add_argument("--model", choices=MODEL_CHAIN, default=DEFAULT_MODEL)
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
    args = parser.parse_args()
    if args.concurrency < 1:
        parser.error("--concurrency는 1 이상이어야 합니다")
    raise SystemExit(asyncio.run(backfill(args)))
