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

from .download import download_pdf
from .gemini import GeminiExtractor, load_api_key
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


async def extract_validated(extractor, content: bytes, school: str, year: int):
    """합계 불일치만 동일 PDF로 최대 2회 재추출한다. 최초 통과 즉시 채택."""
    attempts = []
    selected = None
    for _ in range(3):
        try:
            data = await asyncio.to_thread(extractor.extract, content)
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
            attempts.append({"error_type": type(exc).__name__})
    return *selected, attempts


def reusable_result(args, slug: str, entry: dict) -> dict | None:
    """같은 설정·원본 해시의 검증 완료 JSON만 재사용한다(미통과 판정도 유지)."""
    if not getattr(args, "resume", False):
        return None
    try:
        result = json.loads((args.output / f"{slug}-{args.year}.json").read_text())
        source = result["source"]
        data = result["data"]
        if (
            result["schema_version"] != 2
            or source.get("year") != args.year
            or source["model"] != args.model
            or any(
                source.get(key) != entry[key]
                for key in ("name", "campus", "region", "sources")
            )
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
    if "this._engines.set" in str(exc):
        return "PAGE_SCRIPT_CONFLICT"
    return type(exc).__name__


async def backfill(args: argparse.Namespace) -> int:
    schools = load_universities(getattr(args, "universities", DEFAULT_UNIVERSITIES))
    selected = args.schools or list(schools)
    if unknown := set(selected) - schools.keys():
        raise ValueError(f"목록에 없는 학교: {', '.join(sorted(unknown))}")
    extractor = GeminiExtractor(load_api_key(args.env_file), args.model)
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
                }
                cached = reusable_result(args, slug, entry)
                if cached is not None:
                    summary[slug] = {
                        **record,
                        "status": cached["validation"]["status"],
                        "sha256": cached["source"]["sha256"],
                        "json_file": f"{slug}-{args.year}.json",
                        "extraction_attempts": len(
                            cached.get("extraction_attempts", [])
                        ),
                        "reused": True,
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
                        for source in sources:
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
                            data, validation, attempts = await extract_validated(
                                extractor, pdf.content, school, args.year
                            )
                            result = {
                                "schema_version": 2,
                                "source": {
                                    **entry,
                                    "school": school,
                                    "page_url": source["url"],
                                    "pdf_url": pdf.url,
                                    "sha256": pdf.sha256,
                                    "pdf_file": pdf_path.name,
                                    "collected_at": datetime.now(UTC).isoformat(),
                                    "model": args.model,
                                    "year": args.year,
                                },
                                "data": data,
                                "validation": validation,
                                "extraction_attempts": attempts,
                            }
                            write_json(args.output / f"{slug}-{args.year}.json", result)
                            record.update(
                                status=validation["status"],
                                sha256=pdf.sha256,
                                json_file=f"{slug}-{args.year}.json",
                                extraction_attempts=len(attempts),
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
                    print(f"{school}: {record['status']}", flush=True)

            await asyncio.gather(*(collect(slug) for slug in selected))
        finally:
            await browser.close()
    write_json(args.output / "summary.json", {slug: summary[slug] for slug in selected})
    return 0 if all(r["status"] == "passed" for r in summary.values()) else 1


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
    command.add_argument("--model", default="gemini-3.1-flash-lite")
    command.add_argument("--env-file", type=Path)
    command.add_argument("--concurrency", type=int, default=3)
    command.add_argument(
        "--resume",
        action="store_true",
        help="동일 설정·원본 해시의 기존 추출 JSON 재사용(미통과도 보존)",
    )
    args = parser.parse_args()
    if args.concurrency < 1:
        parser.error("--concurrency는 1 이상이어야 합니다")
    raise SystemExit(asyncio.run(backfill(args)))
