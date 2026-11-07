"""제목 기반 모집요강 링크 감지."""

import re
from dataclasses import dataclass


@dataclass(frozen=True)
class Link:
    title: str
    url: str


def is_admission_guide(title: str) -> bool:
    compact = re.sub(r"\s+", "", title)
    return all(word in compact for word in ("편입", "모집", "요강")) and not any(
        word in compact for word in ("기본계획", "예고", "시행계획")
    )


def select_guide_links(links: list[Link]) -> list[Link]:
    return [link for link in links if is_admission_guide(link.title)]
