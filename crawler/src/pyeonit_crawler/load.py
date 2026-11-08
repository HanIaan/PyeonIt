"""검증·채택된 로컬 backfill 결과의 멱등 DB 적재."""

import hashlib
import json
import os
from copy import deepcopy
from datetime import datetime
from pathlib import Path

import psycopg
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb

from .monitor.seed import load_seed


def corrected_result(data: dict, digest: str, corrections: dict) -> dict:
    """원문 PDF 대조 후 명시한 날짜 보정만 적용하고 수정 전 값을 이력으로 남긴다."""
    result = deepcopy(data)
    evidence = corrections.get(digest, [])
    for correction in evidence:
        field = correction["field"]
        if field not in {"시작", "종료"} or not correction.get("pdf_page"):
            raise ValueError("날짜 필드·PDF 근거 페이지 필요")
        row = result["일정"][correction["index"]]
        if row["항목"] != correction["label"] or row[field] != correction["before"]:
            raise ValueError("보정 대상 원문 불일치")
        value = correction["after"]
        fmt = "%Y-%m-%d" if len(value) == 10 else "%Y-%m-%d %H:%M"
        if datetime.strptime(value, fmt).strftime(fmt) != value:
            raise ValueError("보정 날짜 형식 오류")
        row[field] = value
    if evidence:
        result["_schedule_corrections"] = {"pdf_sha256": digest, "changes": evidence}
    return result


def load_artifacts(
    conn,
    directory: Path,
    storage_dir: Path,
    universities: Path,
    corrections_path: Path | None = None,
) -> dict:
    from .cli import load_universities, matches_request
    from .validation import validate_totals

    schools = load_universities(universities)
    corrections = json.loads(corrections_path.read_text()) if corrections_path else {}
    report = {"loaded": 0, "updated": 0, "skipped": 0, "documents": [], "reasons": []}
    for path in sorted(directory.glob("*-2026.json")):
        reason = None
        try:
            artifact = json.loads(path.read_text(encoding="utf-8"))
            source = artifact["source"]
            data = artifact.get("data")
            validation = artifact.get("validation") or {}
            if (
                artifact.get("adopted") is not True
                or validation.get("passed") is not True
            ):
                reason = "NOT_ADOPTED_OR_NOT_PASSED"
            elif source.get("year") != 2026 or not isinstance(data, dict):
                reason = "INVALID_YEAR_OR_DATA"
            else:
                entry = schools.get(path.name.removesuffix("-2026.json"))
                if (
                    entry is None
                    or any(
                        source.get(k) != entry[k] for k in ("name", "campus", "region")
                    )
                    or not matches_request(data, entry["name"], 2026)
                    or data.get("문서종류") != "편입학 모집요강"
                ):
                    reason = "IDENTITY_MISMATCH"
                elif (
                    validation != validate_totals(data)
                    or not validate_totals(data)["passed"]
                ):
                    reason = "VALIDATION_MISMATCH"
                else:
                    pdf = path.with_suffix(".pdf")
                    content = pdf.read_bytes()
                    digest = hashlib.sha256(content).hexdigest()
                    if not content.lstrip().startswith(
                        b"%PDF-"
                    ) or digest != source.get("sha256"):
                        reason = "PDF_HASH_MISMATCH"
                    elif (
                        not source.get("model")
                        or not source.get("page_url")
                        or not source.get("pdf_url")
                    ):
                        reason = "MISSING_PROVENANCE"
        except (OSError, ValueError, KeyError, TypeError, AttributeError):
            reason = "INVALID_ARTIFACT_OR_MISSING_PDF"
        if reason:
            report["skipped"] += 1
            report["reasons"].append({"file": path.name, "reason": reason})
            continue
        try:
            prepared = corrected_result(data, digest, corrections)
        except (ValueError, KeyError, TypeError, IndexError):
            report["skipped"] += 1
            report["reasons"].append(
                {"file": path.name, "reason": "CORRECTION_MISMATCH"}
            )
            continue
        with conn.transaction():
            university = conn.execute(
                "SELECT id FROM universities WHERE name=%s AND campus=%s",
                (entry["name"], entry["campus"]),
            ).fetchone()
            if university is None:
                load_seed(conn, universities)
                university = conn.execute(
                    "SELECT id FROM universities WHERE name=%s AND campus=%s",
                    (entry["name"], entry["campus"]),
                ).fetchone()
            storage_key = f"{digest}.pdf"
            storage_dir.mkdir(parents=True, exist_ok=True)
            target = storage_dir / storage_key
            if (
                not target.exists()
                or hashlib.sha256(target.read_bytes()).hexdigest() != digest
            ):
                temporary = target.with_suffix(".pdf.tmp")
                temporary.write_bytes(content)
                temporary.replace(target)
            document = conn.execute(
                """INSERT INTO admission_documents
                   (university_id, academic_year, title, page_url, file_url, file_sha256, storage_key, status)
                   VALUES (%s, 2026, %s, %s, %s, %s, %s, 'EXTRACTED')
                   ON CONFLICT (file_sha256) DO NOTHING RETURNING id""",
                (
                    university["id"],
                    f"2026학년도 {entry['name']} 편입학 모집요강",
                    source["page_url"],
                    source["pdf_url"],
                    digest,
                    storage_key,
                ),
            ).fetchone()
            if document is None and prepared != data:
                existing = conn.execute(
                    "SELECT id, status FROM admission_documents WHERE file_sha256=%s FOR UPDATE",
                    (digest,),
                ).fetchone()
                run = conn.execute(
                    "SELECT * FROM extraction_runs WHERE document_id=%s ORDER BY id DESC LIMIT 1 FOR UPDATE",
                    (existing["id"],),
                ).fetchone()
                if (
                    existing["status"] == "EXTRACTED"
                    and run is not None
                    and run["result"] == data
                    and run["validation"] == validation
                    and run["model"] == source["model"]
                ):
                    conn.execute(
                        "UPDATE extraction_runs SET result=%s WHERE id=%s",
                        (Jsonb(prepared), run["id"]),
                    )
                    report["updated"] += 1
                    report["documents"].append(
                        {
                            "id": existing["id"],
                            "school": entry["name"],
                            "sha256": digest,
                            "updated": True,
                        }
                    )
                    continue
            if document is None:
                report["skipped"] += 1
                report["reasons"].append(
                    {"file": path.name, "reason": "DUPLICATE_SHA256"}
                )
                continue
            conn.execute(
                """INSERT INTO extraction_runs (document_id, model, result, validation)
                   VALUES (%s, %s, %s, %s)""",
                (document["id"], source["model"], Jsonb(prepared), Jsonb(validation)),
            )
            report["loaded"] += 1
            report["documents"].append(
                {"id": document["id"], "school": entry["name"], "sha256": digest}
            )
    return report


def run_load(args) -> int:
    url = args.database_url or os.environ.get("DATABASE_URL")
    if not url:
        print("DATABASE_URL 또는 --database-url이 필요합니다")
        return 2
    try:
        with psycopg.connect(url, autocommit=True, row_factory=dict_row) as conn:
            report = load_artifacts(
                conn,
                args.input,
                args.storage_dir,
                args.universities,
                args.schedule_corrections,
            )
    except psycopg.Error:
        print("DB 적재 실패: 연결·스키마·제약 조건을 확인하세요")
        return 1
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0
