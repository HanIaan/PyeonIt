import type { AdminDocument } from "@/lib/admin-api";
import { table } from "./admin-shell";
type Row = Record<string, unknown>;
function object(value: unknown): Row {
  return value && typeof value === "object" && !Array.isArray(value)
    ? (value as Row)
    : {};
}
function rows(value: unknown): Row[] {
  return Array.isArray(value) ? value.map(object) : [];
}
function text(value: unknown) {
  return typeof value === "string" || typeof value === "number"
    ? String(value)
    : "—";
}
function counts(value: unknown) {
  return rows(value).map((c, i) => (
    <div key={i}>
      {text(c["전형"])}: {text(c["원문"] ?? c["인원"])}
      {c["인원"] === null && " (확정 인원 없음)"}
    </div>
  ));
}
const validationLabel = (status: unknown) =>
  status === "passed" ? "통과" : status === "failed" ? "불일치" : "검증불가";
export function Extraction({ document }: { document: AdminDocument }) {
  const run = document.latestExtraction;
  if (!run) return <p className="p-4">추출 결과가 없습니다.</p>;
  const result = object(run.result);
  const validation = object(run.validation);
  const quotas = rows(result["모집인원"]);
  const groups = [...new Set(quotas.map((row) => text(row["계열"])))];
  return (
    <div className="space-y-5">
      <p className="text-xs text-slate-500">
        추출 #{run.id} · {run.model}
      </p>
      {run.error && (
        <p role="alert" className="text-red-700">
          추출 오류: {run.error}
        </p>
      )}
      <section>
        <h4 className="font-bold">
          합계 검증 ·{" "}
          <span
            className={
              validation.status === "passed"
                ? "text-teal-800"
                : "text-amber-800"
            }
          >
            {validationLabel(validation.status)}
          </span>
        </h4>
        <div className="overflow-x-auto">
          <table className={table}>
            <thead>
              <tr>
                <th>범위 / 전형</th>
                <th>계산 합계</th>
                <th>원문 기재값</th>
                <th>결과</th>
              </tr>
            </thead>
            <tbody>
              {rows(validation.checks).map((check, i) => (
                <tr key={i}>
                  <td>
                    {text(check["범위"])} / {text(check["전형"])}
                  </td>
                  <td>{text(check["합계"])}</td>
                  <td>{text(check["기재값"])}</td>
                  <td>
                    {validationLabel(check.status)}
                    {Number(check["칸_누락"]) > 0 && " · 칸 누락"}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {!rows(validation.checks).length && (
          <p className="py-2 text-sm">검증 항목이 없습니다.</p>
        )}
      </section>
      <section>
        <h4 className="mb-2 font-bold">모집인원 · 단과대/계열 → 학과</h4>
        {groups.map((group) => (
          <div
            key={group}
            className="mb-3 overflow-x-auto rounded-lg border border-slate-200"
          >
            <h5 className="bg-slate-100 px-3 py-2 text-sm font-semibold">
              {group}
            </h5>
            <table className={table}>
              <thead>
                <tr>
                  <th>모집단위</th>
                  <th>단위</th>
                  <th>전형별 인원 (원문)</th>
                </tr>
              </thead>
              <tbody>
                {quotas
                  .filter((q) => text(q["계열"]) === group)
                  .map((q, i) => (
                    <tr key={i}>
                      <td>{text(q["모집단위"])}</td>
                      <td>{text(q["단위"])}</td>
                      <td>{counts(q["인원"])}</td>
                    </tr>
                  ))}
              </tbody>
            </table>
          </div>
        ))}
        {!quotas.length && (
          <p className="text-sm">모집인원 추출 결과가 없습니다.</p>
        )}
        <div className="text-sm">원문 총계: {counts(result["총계"])}</div>
      </section>
      <section>
        <h4 className="mb-2 font-bold">전형 일정</h4>
        <ul className="space-y-2">
          {rows(result["일정"]).map((s, i) => (
            <li key={i} className="rounded-lg bg-slate-50 p-3 text-sm">
              <strong>{text(s["항목"])}</strong>
              <p>
                {text(s["시작"])}
                {s["종료"] ? ` ~ ${text(s["종료"])}` : ""}
              </p>
              <p className="text-slate-600">{text(s["비고"])}</p>
            </li>
          ))}
        </ul>
        {!rows(result["일정"]).length && (
          <p className="text-sm">일정 추출 결과가 없습니다.</p>
        )}
      </section>
      <details className="text-xs">
        <summary className="cursor-pointer">추출 원본 JSON / 검증 상세</summary>
        <pre className="mt-2 max-h-80 overflow-auto whitespace-pre-wrap break-all rounded-lg bg-slate-100 p-3">
          {JSON.stringify(
            { result: run.result, validation: run.validation },
            null,
            2,
          )}
        </pre>
      </details>
    </div>
  );
}
export function safeUrl(value: string | null) {
  if (!value) return null;
  try {
    const url = new URL(value);
    return ["https:", "http:"].includes(url.protocol) ? url.href : null;
  } catch {
    return null;
  }
}
