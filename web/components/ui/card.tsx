// 사용: <Card className="space-y-2">…</Card>
import type { HTMLAttributes } from "react";

export function Card({ className = "", ...props }: HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={`rounded-card border border-line bg-surface p-4 ${className}`}
      {...props}
    />
  );
}
