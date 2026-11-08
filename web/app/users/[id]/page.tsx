import { Suspense } from "react";
import { notFound } from "next/navigation";
import ProfileView from "@/components/profile/profile-view";
export function generateStaticParams() {
  return [{ id: "1" }];
}
async function Content({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  if (!/^\d+$/.test(id) || !Number.isSafeInteger(Number(id)) || Number(id) < 1)
    notFound();
  return <ProfileView key={id} id={Number(id)} />;
}
export default function Page(props: { params: Promise<{ id: string }> }) {
  return (
    <Suspense
      fallback={
        <p role="status" className="text-label text-ink-3">
          불러오는 중…
        </p>
      }
    >
      <Content {...props} />
    </Suspense>
  );
}
