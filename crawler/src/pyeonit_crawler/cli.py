"""초기 수집 CLI. DB에 쓰지 않고 PDF 및 JSON을 저장한다."""

import argparse
import asyncio
import json
from datetime import UTC, datetime
from pathlib import Path

import httpx
from playwright.async_api import async_playwright

from .download import download_pdf
from .gemini import GeminiExtractor, load_api_key
from .validation import validate_totals

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


def write_json(path: Path, data: dict) -> None:
    temporary = path.with_suffix(".json.tmp")
    temporary.write_text(
        json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    temporary.replace(path)


async def backfill(args: argparse.Namespace) -> int:
    extractor = GeminiExtractor(load_api_key(args.env_file), args.model)
    args.output.mkdir(parents=True, exist_ok=True)
    summary = {}
    async with async_playwright() as pw:
        browser = await pw.chromium.launch()
        try:
            context = await browser.new_context()
            for slug in args.schools or SCHOOLS:
                school, source = SCHOOLS[slug]
                print(f"{school}: PDF 수집 중", flush=True)
                try:
                    pdf = await download_pdf(context, source, year=args.year)
                    pdf_path = args.output / f"{slug}-{args.year}.pdf"
                    pdf_path.write_bytes(pdf.content)
                    print(f"{school}: Gemini 추출 중 (sha256={pdf.sha256})", flush=True)
                    data = await asyncio.to_thread(extractor.extract, pdf.content)
                    # 현재 페이지가 다음 학년도 요강으로 바뀐 경우 잘못 저장하지 않는다.
                    if data.get("학년도") != args.year or school not in data.get(
                        "학교", ""
                    ):
                        raise ValueError("추출된 학교 또는 학년도가 요청과 다릅니다")
                    validation = validate_totals(data)
                    result = {
                        "schema_version": 2,
                        "source": {
                            "school": school,
                            "page_url": source,
                            "pdf_url": pdf.url,
                            "sha256": pdf.sha256,
                            "pdf_file": pdf_path.name,
                            "collected_at": datetime.now(UTC).isoformat(),
                            "model": args.model,
                        },
                        "data": data,
                        "validation": validation,
                    }
                    write_json(args.output / f"{slug}-{args.year}.json", result)
                    summary[slug] = {
                        "status": validation["status"],
                        "sha256": pdf.sha256,
                        "json_file": f"{slug}-{args.year}.json",
                    }
                    print(f"{school}: 검증 {validation['status']}", flush=True)
                except Exception as exc:
                    # HTTP 오류 본문/요청 헤더 등 비밀을 노출할 수 있는 상세는 출력하지 않는다.
                    summary[slug] = {
                        "status": "error",
                        "error_type": type(exc).__name__,
                    }
                    if isinstance(exc, httpx.HTTPStatusError):
                        summary[slug]["http_status"] = exc.response.status_code
                    print(f"{school}: 수집 실패 ({type(exc).__name__})", flush=True)
        finally:
            await browser.close()
    write_json(args.output / "summary.json", summary)
    # 불일치/검증 불가 데이터도 검토할 수 있도록 보존하고 실패 코드를 반환한다.
    return 0 if all(r["status"] == "passed" for r in summary.values()) else 1


def main() -> None:
    parser = argparse.ArgumentParser(description="편입 모집요강 수집기")
    sub = parser.add_subparsers(dest="command", required=True)
    command = sub.add_parser("backfill", help="현재 게시된 요강 PDF 및 추출 JSON 저장")
    command.add_argument("--year", type=int, default=2026)
    command.add_argument("--schools", nargs="+", choices=list(SCHOOLS))
    command.add_argument(
        "--output", type=Path, default=Path(__file__).resolve().parents[2] / "artifacts"
    )
    command.add_argument("--model", default="gemini-3.1-flash-lite")
    command.add_argument("--env-file", type=Path)
    args = parser.parse_args()
    raise SystemExit(asyncio.run(backfill(args)))
