"use client";
import { useEffect, useRef } from "react";
import { button, secondary } from "./admin-shell";
export function Confirm({
  title,
  children,
  busy,
  onCancel,
  onConfirm,
}: {
  title: string;
  children: React.ReactNode;
  busy: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const dialog = ref.current!;
    dialog.showModal();
    return () => dialog.close();
  }, []);
  return (
    <dialog
      ref={ref}
      aria-labelledby="admin-confirm-title"
      onCancel={(e) => {
        e.preventDefault();
        if (!busy) onCancel();
      }}
      className="m-auto w-[calc(100%-2rem)] max-w-lg rounded-card bg-surface p-6 text-ink backdrop:bg-ink/50"
    >
      <h3 id="admin-confirm-title" className="text-lg font-bold">
        {title}
      </h3>
      <div className="my-4 text-sm leading-6">{children}</div>
      <div className="flex justify-end gap-2">
        <button
          autoFocus
          className={secondary}
          disabled={busy}
          onClick={onCancel}
        >
          취소
        </button>
        <button className={button} disabled={busy} onClick={onConfirm}>
          {busy ? "처리 중…" : "확인 후 처리"}
        </button>
      </div>
    </dialog>
  );
}
