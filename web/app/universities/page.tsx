import { Suspense } from "react";
import UniversitiesView from "@/components/universities/universities-view";

export default function Page() {
  return (
    <Suspense fallback={<p>학교를 불러오는 중입니다…</p>}>
      <UniversitiesView />
    </Suspense>
  );
}
