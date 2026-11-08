"use client";
import { useRouter, useSearchParams } from "next/navigation";
import { useState } from "react";
import { input, primary, Message } from "@/components/community/shared";

export default function SearchForm({ schools = false }: { schools?: boolean }) {
  const params = useSearchParams();
  const router = useRouter();
  const [error, setError] = useState("");
  return (
    <form
      key={`${params.get("q")}:${params.get("scope")}`}
      className="mb-5 space-y-3"
      onSubmit={(e) => {
        e.preventDefault();
        const form = new FormData(e.currentTarget);
        const q = String(form.get("q") ?? "").trim();
        if (
          (q.length < (schools ? 1 : 2) && !(schools && !q)) ||
          q.length > 50
        ) {
          setError(`검색어는 ${schools ? "1" : "2"}~50자로 입력해 주세요.`);
          return;
        }
        setError("");
        const next = new URLSearchParams(params);
        if (q) next.set("q", q);
        else next.delete("q");
        next.delete("page");
        if (!schools) next.set("scope", String(form.get("scope")));
        router.push(
          `${schools ? "/universities" : "/community/search"}?${next}`,
        );
      }}
    >
      {!schools && (
        <fieldset className="flex gap-4 text-sm">
          <legend className="sr-only">검색 대상</legend>
          {[
            ["posts", "게시물 검색"],
            ["users", "작성자 검색"],
          ].map(([value, label]) => (
            <label key={value} className="flex min-h-11 items-center gap-2">
              <input
                type="radio"
                name="scope"
                value={value}
                defaultChecked={
                  (params.get("scope") === "users" ? "users" : "posts") ===
                  value
                }
              />
              {label}
            </label>
          ))}
        </fieldset>
      )}
      <div className="flex gap-2">
        <label className="min-w-0 flex-1">
          <span className="sr-only">{schools ? "학교명 검색" : "검색어"}</span>
          <input
            name="q"
            className={input}
            defaultValue={params.get("q") ?? ""}
            placeholder={schools ? "학교명 또는 별칭" : "검색어 2~50자"}
          />
        </label>
        <button className={primary}>검색</button>
      </div>
      <Message>{error}</Message>
    </form>
  );
}
