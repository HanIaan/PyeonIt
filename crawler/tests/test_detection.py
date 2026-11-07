import pytest

from pyeonit_crawler.detection import Link, is_admission_guide, select_guide_links


@pytest.mark.parametrize(
    "title",
    [
        "2026학년도 편입학 모집요강",
        "2026 일반 및 학사편입 모집 요강 안내",
        "[수정] 편입학 최종 모집요강 공고",
    ],
)
def test_include(title):
    assert is_admission_guide(title)


@pytest.mark.parametrize(
    "title",
    [
        "2026학년도 편입학 기본계획",
        "편입학 모집요강 기본계획 안내",
        "편입학 모집요강 사전 예고",
        "편입학 모집요강 시행 계획",
        "2026학년도 신입학 모집요강",
        "편입학 모집인원",
        "편입학 요강 안내",
    ],
)
def test_exclude(title):
    assert not is_admission_guide(title)


def test_select_links():
    good = Link("2026 편입학 모집요강", "https://example.test/guide")
    assert select_guide_links(
        [Link("편입 기본계획", "https://example.test/plan"), good]
    ) == [good]
