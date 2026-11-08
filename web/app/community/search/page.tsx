import { Suspense } from "react";
import SearchView from "@/components/search/search-view";
export default function Page() {
  return (
    <Suspense
      fallback={
        <p role="status" className="text-label text-ink-3">
          불러오는 중…
        </p>
      }
    >
      <SearchView />
    </Suspense>
  );
}
