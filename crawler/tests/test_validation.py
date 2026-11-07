from copy import deepcopy

import pytest

from pyeonit_crawler.validation import validate_totals


def counts(n):
    return [{"전형": "일반편입", "인원": n, "원문": str(n)}]


@pytest.fixture
def data():
    return {
        "모집인원": [
            {"계열": "인문", "단위": "학과", "모집단위": "국문", "인원": counts(3)},
            {"계열": "인문", "단위": "학과", "모집단위": "영문", "인원": counts(2)},
            {
                "계열": "자연",
                "단위": "단과대학",
                "모집단위": "공과대학",
                "인원": counts(4),
            },
        ],
        "소계": [{"계열": "인문", "인원": counts(5)}],
        "총계": counts(9),
    }


def test_matching_totals_and_subtotals(data):
    result = validate_totals(data)
    assert result["passed"]
    assert result["단과대학_행"] == 1
    assert [c["합계"] for c in result["checks"]] == [9, 5]


def test_mismatching_total(data):
    data["총계"] = counts(10)
    result = validate_totals(data)
    assert result["status"] == "failed"
    assert result["checks"][0]["합계"] == 9


def test_mismatching_subtotal_with_matching_total(data):
    data["소계"][0]["인원"] = counts(6)
    assert validate_totals(data)["status"] == "failed"


def test_unknown_cells_are_not_silently_zero(data):
    data["모집인원"][0]["인원"] = [
        {"전형": "일반편입", "인원": None, "원문": "3명 이내"}
    ]
    result = validate_totals(data)
    assert result["status"] == "unverifiable"
    assert not result["passed"]
    assert result["비숫자_인원"] == 1


def test_missing_totals_are_not_passed(data):
    data["총계"] = []
    assert validate_totals(data)["status"] == "unverifiable"


def test_missing_cell_is_unverifiable(data):
    data["모집인원"][0]["인원"] = []
    assert validate_totals(data)["status"] == "unverifiable"


def test_empty_data_is_unverifiable():
    assert validate_totals({"모집인원": [], "총계": []})["status"] == "unverifiable"


def test_does_not_modify_extraction(data):
    before = deepcopy(data)
    validate_totals(data)
    assert data == before


def test_sparse_college_row_applies_only_to_its_columns(data):
    # 학사 인원이 단과대학으로 병합되어도 일반 인원 합에는 영향이 없다.
    for row in data["모집인원"]:
        row["인원"].append({"전형": "학사편입", "인원": 0, "원문": "-"})
    data["모집인원"].append(
        {
            "계열": "인문",
            "단위": "단과대학",
            "모집단위": "인문대학",
            "인원": [{"전형": "학사편입", "인원": 2, "원문": "2"}],
        }
    )
    data["총계"].append({"전형": "학사편입", "인원": 2, "원문": "2"})
    data["소계"][0]["인원"].append({"전형": "학사편입", "인원": 2, "원문": "2"})
    assert validate_totals(data)["passed"]


@pytest.mark.parametrize("text", ["1명 이내", "8명 내외", "약간명", "약간 명"])
def test_range_text_has_separate_unverifiable_reason(data, text):
    data["모집인원"][0]["인원"][0].update(인원=None, 원문=text)
    result = validate_totals(data)
    assert result["status"] == "unverifiable"
    assert result["checks"][0]["범위_텍스트"] == 1
    assert result["checks"][0]["칸_누락"] == 0
    assert result["warnings"] == []


@pytest.mark.parametrize(
    "cell", [[], [{"전형": "일반편입", "인원": None, "원문": "판독불가"}]]
)
def test_missing_cells_warn_without_failing_totals(data, cell):
    data["모집인원"][0]["인원"] = cell
    result = validate_totals(data)
    assert result["status"] == "unverifiable"
    assert not any(c["status"] == "failed" for c in result["checks"])
    assert result["checks"][0]["칸_누락"] == 1
    assert result["checks"][0]["범위_텍스트"] == 0
    assert result["warnings"][0]["code"] == "MISSING_CELL"


def test_range_text_is_not_counted_as_fixed_even_if_model_emits_number(data):
    data["모집인원"][0]["인원"][0]["원문"] = "3명 이내"
    result = validate_totals(data)
    assert result["status"] == "unverifiable"
    assert result["checks"][0]["합계"] == 6
