"""Chromium으로 공지/요강 페이지와 iframe의 PDF 첨부를 찾는다."""

import hashlib
import re
from dataclasses import dataclass
from urllib.parse import parse_qs, unquote, urljoin, urlsplit

from playwright.async_api import APIResponse, BrowserContext, Error, Page

from .detection import Link, select_guide_links

# 2026-11-08 확인: 해당 PDF 서버의 인증서 체인 검증 실패로 이 호스트만 예외 허용.
TLS_EXCEPTION_HOSTS = frozenset({"admission.sogang.ac.kr"})


async def fetch_attachment(
    context: BrowserContext, url: str, referer: str
) -> APIResponse:
    # 자동 리다이렉트를 끄고 매 hop의 호스트를 판정해 예외가 외부 호스트로 전파되지 않게 한다.
    for _ in range(11):
        parsed = urlsplit(url)
        if parsed.scheme not in {"http", "https"}:
            raise ValueError("HTTP(S) 첨부 주소만 지원합니다")
        response = await context.request.get(
            url,
            headers={"Referer": referer},
            timeout=60000,
            max_redirects=0,
            ignore_https_errors=(
                parsed.scheme == "https" and parsed.hostname in TLS_EXCEPTION_HOSTS
            ),
        )
        if response.status not in {301, 302, 303, 307, 308}:
            return response
        location = response.headers.get("location")
        if not location:
            raise ValueError("PDF 리다이렉트에 Location이 없습니다")
        url = urljoin(url, location)
    raise ValueError("PDF 리다이렉트 횟수 초과")


@dataclass(frozen=True)
class DownloadedPDF:
    url: str
    content: bytes
    sha256: str


def pdf_candidates(links: list[Link], year: int) -> list[str]:
    candidates: dict[str, int] = {}
    for link in links:
        decoded = unquote(link.url)
        if any(
            word in re.sub(r"\s+", "", link.title)
            for word in ("기본계획", "예고", "시행계획")
        ):
            continue
        pdf_hint = "pdf" in link.title.lower() or ".pdf" in decoded.lower()
        download_hint = re.search(r"download|filedown|attach", decoded, re.IGNORECASE)
        if not re.search(r"\.pdf(?:$|[?#&])", decoded, re.IGNORECASE) and not (
            pdf_hint and download_hint
        ):
            continue
        parsed = urlsplit(link.url)
        query = parse_qs(parsed.query)
        # PDF.js viewer URL은 실제 PDF 주소로 변환한다.
        url = urljoin(link.url, query["file"][0]) if query.get("file") else link.url
        score = 10 * (str(year) in decoded + link.title)
        score += 5 * ("모집" in link.title or "요강" in link.title)
        score += 3 * ("/guide" in decoded.lower())
        score += 1 * ("download" in decoded.lower())
        candidates[url] = max(candidates.get(url, -1), score)
    return sorted(candidates, key=lambda url: candidates[url], reverse=True)


async def page_links(page: Page) -> list[Link]:
    links = []
    for frame in page.frames:
        entries = await frame.eval_on_selector_all(
            "a, iframe, embed, object",
            """els => els.map(e => ({title: (e.innerText || e.title || e.getAttribute('aria-label') || '').trim(),
              url: e.href || e.src || e.data || '', onclick: e.getAttribute('onclick') || ''}))""",
        )
        for entry in entries:
            url = entry["url"]
            if url.startswith(("http://", "https://")):
                links.append(Link(entry["title"], url))
            # location.href/window.open 등에 쓰인 URL 리터럴만 수집, 임의 JS 실행 없음.
            for target in re.findall(
                r"['\"]([^'\"]+\.pdf(?:\?[^'\"]*)?)['\"]", entry["onclick"], re.I
            ):
                links.append(Link(entry["title"], urljoin(frame.url, target)))
    return list(dict.fromkeys(links))


async def download_pdf(
    context: BrowserContext, page_url: str, *, year: int = 2026
) -> DownloadedPDF:
    page = await context.new_page()
    try:
        await page.goto(page_url, wait_until="networkidle", timeout=60000)
        links = await page_links(page)
        candidates = pdf_candidates(links, year)
        if not candidates:
            # 공지 목록에서는 엄격한 제목 필터를 통과한 글만 따라간다.
            for link in select_guide_links(links):
                if str(year) not in link.title or link.url == page.url:
                    continue
                await page.goto(link.url, wait_until="networkidle", timeout=60000)
                candidates.extend(pdf_candidates(await page_links(page), year))
        last_error = None
        for url in dict.fromkeys(candidates):
            try:
                response = await fetch_attachment(context, url, page.url)
                content = await response.body()
            except Error as exc:
                last_error = exc
                continue
            if response.ok and content.lstrip().startswith(b"%PDF-"):
                return DownloadedPDF(
                    response.url, content, hashlib.sha256(content).hexdigest()
                )
        if last_error is not None:
            raise last_error
        raise ValueError("유효한 PDF 첨부를 찾지 못했습니다")
    finally:
        await page.close()
