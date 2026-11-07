"""인쇄된 소계·총계 비교. 범위 표현과 누락 경고를 구분하고 원문은 보존한다."""

import re

RANGE_TEXT = re.compile(r"이내|내외|약간\s*명")


def _compare(rows: list[dict], counts: list[dict], scope: str) -> list[dict]:
    checks = []
    expected = {c["전형"]: c for c in counts}
    columns = set(expected) | {c["전형"] for r in rows for c in r["인원"]}
    for column in sorted(columns):
        cells = [
            next((c for c in r["인원"] if c["전형"] == column), None)
            for r in rows
            # 단과대학 병합 행의 다른 열은 학과 행에서 집계한다.
            if r["단위"] != "단과대학" or any(c["전형"] == column for c in r["인원"])
        ]
        ranges = sum(bool(c and RANGE_TEXT.search(c.get("원문", ""))) for c in cells)
        missing = sum(
            c is None
            or (c.get("인원") is None and not RANGE_TEXT.search(c.get("원문", "")))
            for c in cells
        )
        actual = sum(
            c["인원"]
            for c in cells
            if c
            and c.get("인원") is not None
            and not RANGE_TEXT.search(c.get("원문", ""))
        )
        target_cell = expected.get(column, {})
        target = target_cell.get("인원")
        if (
            ranges
            or missing
            or target is None
            or RANGE_TEXT.search(target_cell.get("원문", ""))
            or not rows
        ):
            status = "unverifiable"
        else:
            status = "passed" if actual == target else "failed"
        checks.append(
            {
                "범위": scope,
                "전형": column,
                "합계": actual,
                "기재값": target,
                "범위_텍스트": ranges,
                "칸_누락": missing,
                "비숫자_또는_누락": ranges + missing,
                "status": status,
            }
        )
    return checks


def validate_totals(data: dict) -> dict:
    rows = data["모집인원"]
    checks = _compare(rows, data.get("총계", []), "총계")
    for subtotal in data.get("소계", []):
        group = subtotal["계열"]
        checks.extend(
            _compare(
                [r for r in rows if r["계열"] == group],
                subtotal["인원"],
                f"소계:{group}",
            )
        )
    status = (
        "failed"
        if any(c["status"] == "failed" for c in checks)
        else "passed"
        if checks and all(c["status"] == "passed" for c in checks)
        else "unverifiable"
    )
    return {
        "status": status,
        "passed": status == "passed",
        "checks": checks,
        "warnings": [
            {
                "code": "MISSING_CELL",
                "범위": c["범위"],
                "전형": c["전형"],
                "개수": c["칸_누락"],
            }
            for c in checks
            if c["칸_누락"]
        ],
        "단과대학_행": sum(r["단위"] == "단과대학" for r in rows),
        "비숫자_인원": sum(c.get("인원") is None for r in rows for c in r["인원"]),
    }
