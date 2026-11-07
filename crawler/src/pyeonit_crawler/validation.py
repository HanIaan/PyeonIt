"""인쇄된 소계·총계를 모집인원 합과 비교한다. 원문은 수정하지 않는다."""


def _compare(rows: list[dict], counts: list[dict], scope: str) -> list[dict]:
    checks = []
    expected = {c["전형"]: c.get("인원") for c in counts}
    columns = set(expected) | {c["전형"] for r in rows for c in r["인원"]}
    for column in sorted(columns):
        cells = [
            next((c for c in r["인원"] if c["전형"] == column), None)
            for r in rows
            # 단과대학 병합 행은 해당 전형만 보유한다. 없는 열은 학과 행에서 이미 집계한다.
            if r["단위"] != "단과대학" or any(c["전형"] == column for c in r["인원"])
        ]
        unknown = sum(c is None or c.get("인원") is None for c in cells)
        actual = sum(c["인원"] for c in cells if c and c.get("인원") is not None)
        target = expected.get(column)
        if unknown or target is None or not rows:
            status = "unverifiable"
        else:
            status = "passed" if actual == target else "failed"
        checks.append(
            {
                "범위": scope,
                "전형": column,
                "합계": actual,
                "기재값": target,
                "비숫자_또는_누락": unknown,
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
        "단과대학_행": sum(r["단위"] == "단과대학" for r in rows),
        "비숫자_인원": sum(c.get("인원") is None for r in rows for c in r["인원"]),
    }
