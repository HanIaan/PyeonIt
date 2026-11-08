// 사용: <EmptyState action={<Button>글쓰기</Button>}>아직 글이 없어요</EmptyState>
export function EmptyState({
  children,
  action,
}: {
  children: React.ReactNode;
  action?: React.ReactNode;
}) {
  return (
    <div className="flex flex-col items-center gap-4 py-12 text-center">
      <p className="text-body text-ink-2">{children}</p>
      {action}
    </div>
  );
}
