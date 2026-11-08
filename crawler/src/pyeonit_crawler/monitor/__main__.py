"""감시 루프: python -m pyeonit_crawler.monitor [--once] [--seed universities.yaml] [--only 대학,...]"""

import argparse
import asyncio
import hashlib
import logging
import os
import time
from datetime import UTC, datetime
from pathlib import Path

import httpx
import psycopg
from playwright.async_api import async_playwright
from psycopg.rows import dict_row

from ..download import DownloadedPDF, discover_links, download_pdf, fetch_attachment
from ..gemini import DEFAULT_MODEL, GeminiExtractor, load_api_key
from .core import due_sources, run_once, stop_on_quota, unknown_universities
from .seed import load_seed

log = logging.getLogger("pyeonit_crawler.monitor")
DEFAULT_DB = "postgresql://pyeonit:pyeonit@localhost:5434/pyeonit"
DEFAULT_STORAGE = Path(__file__).resolve().parents[3] / "artifacts" / "pdfs"


class BrowserFetcher:
    def __init__(self, context, year: int, model: str = DEFAULT_MODEL):
        self.context, self.year, self.model = context, year, model
        self._extractor = None

    async def links(self, url):
        page = await self.context.new_page()
        try:
            await page.goto(url, wait_until="networkidle", timeout=60000)
            return await discover_links(self.context, page, url)
        finally:
            await page.close()

    async def download(self, url):
        return await download_pdf(self.context, url, year=self.year)

    async def fetch(self, url, referer):
        response = await fetch_attachment(self.context, url, referer)
        content = await response.body()
        if not response.ok or not content.lstrip().startswith(b"%PDF-"):
            raise ValueError(f"PDF 아님 (HTTP {response.status})")
        return DownloadedPDF(response.url, content, hashlib.sha256(content).hexdigest())

    def extract(self, pdf: bytes) -> dict:
        # 분석할 PDF가 생겼을 때만 키를 읽는다. 키 없음은 설정 문제라 일시 오류로 다룬다.
        if self._extractor is None:
            try:
                key = load_api_key()
            except ValueError as exc:
                raise RuntimeError(str(exc)) from None
            # 감시는 체인 없이 단일 모델(backfill 전용 MODEL_CHAIN은 쓰지 않는다)
            self._extractor = GeminiExtractor(key, self.model)
            self._extractor.year = self.year
        with httpx.Client(
            timeout=580, event_hooks={"response": [stop_on_quota]}
        ) as client:
            self._extractor.client = client
            try:
                return self._extractor.extract(pdf)
            finally:
                self._extractor.client = None


def connect(url: str):
    return psycopg.connect(url, autocommit=True, row_factory=dict_row)


async def tick(args) -> int:
    with connect(args.database_url) as conn:
        now = datetime.now(UTC)
        if not due_sources(conn, now, args.only):
            return 0
        async with async_playwright() as pw:
            browser = await pw.chromium.launch()
            try:
                fetcher = BrowserFetcher(
                    await browser.new_context(), args.year, args.model
                )
                return await run_once(
                    conn,
                    fetcher,
                    year=args.year,
                    storage_dir=args.storage_dir,
                    now=now,
                    only=args.only,
                )
            finally:
                await browser.close()


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="모집요강 감시 루프 (1분 주기)")
    parser.add_argument("--once", action="store_true", help="1회만 실행하고 종료")
    parser.add_argument(
        "--seed", type=Path, help="universities.yaml 경로. 시작 전 upsert"
    )
    parser.add_argument(
        "--only",
        type=lambda v: [n.strip() for n in v.split(",") if n.strip()],
        help="확인할 대학만 쉼표로 지정. 예: 서강대학교,연세대학교(서울)",
    )
    parser.add_argument("--model", default=DEFAULT_MODEL)
    parser.add_argument(
        "--storage-dir",
        type=Path,
        default=Path(os.environ.get("PDF_STORAGE_DIR", DEFAULT_STORAGE)),
    )
    return parser


def main() -> None:
    parser = build_parser()
    args = parser.parse_args()
    args.year = int(os.environ.get("TARGET_ACADEMIC_YEAR", "2027"))
    args.database_url = os.environ.get("DATABASE_URL", DEFAULT_DB)
    logging.basicConfig(
        level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s"
    )

    if args.seed:
        with connect(args.database_url) as conn:
            log.info("시드 %d개 대학 반영", load_seed(conn, args.seed))
    if args.only:
        with connect(args.database_url) as conn:
            if unknown := unknown_universities(conn, args.only):
                parser.error(f"--only: DB에 없는 대학 {unknown}")
    log.info("감시 시작: 대상 %d학년도", args.year)
    while True:
        try:
            log.info("확인한 소스 %d개", asyncio.run(tick(args)))
        except Exception:
            if args.once:
                raise
            # DB 끊김 등에도 루프는 유지한다.
            log.exception("감시 루프 오류, 1분 뒤 다시 시도")
        if args.once:
            break
        time.sleep(60)


if __name__ == "__main__":
    main()
