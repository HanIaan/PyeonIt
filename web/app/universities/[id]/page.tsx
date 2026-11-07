import { Suspense } from "react";
import { notFound } from "next/navigation";
import UniversityDetail from "@/components/universities/university-detail";

// 공통 AuthProvider의 usePathname이 사전 렌더링할 대표 경로다. 다른 ID도 런타임에 처리한다.
export function generateStaticParams() {
  return [{ id: "1" }];
}

type Props = { params: Promise<{ id: string }> };

async function Detail({ params }: Props) {
  const { id } = await params;
  if (!/^\d+$/.test(id) || !Number.isSafeInteger(Number(id)) || Number(id) < 1)
    notFound();
  return <UniversityDetail id={Number(id)} />;
}

export default function Page(props: Props) {
  return (
    <Suspense fallback={<p role="status">학교를 불러오는 중…</p>}>
      <Detail {...props} />
    </Suspense>
  );
}
