// 사용: <Badge tone="lime">마감 임박</Badge>
type Tone = "neutral" | "lime" | "danger" | "success" | "warning";
const tones: Record<Tone, string> = {
  neutral: "border-line text-ink-2",
  lime: "border-lime bg-lime text-ink",
  danger: "border-danger/30 text-danger",
  success: "border-success/30 text-success",
  warning: "border-warning/30 text-warning",
};

export function Badge({
  tone = "neutral",
  children,
}: {
  tone?: Tone;
  children: React.ReactNode;
}) {
  return (
    <span
      className={`inline-flex items-center rounded-pill border px-2 py-0.5 text-caption font-medium ${tones[tone]}`}
    >
      {children}
    </span>
  );
}
