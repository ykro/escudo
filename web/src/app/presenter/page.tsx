import type { Metadata } from "next";
import { PresenterView } from "@/components/presenter/PresenterView";

export const metadata: Metadata = { title: "Presentador" };
export const dynamic = "force-dynamic";

export default async function PresenterPage({ searchParams }: { searchParams: Promise<{ demo?: string }> }) {
  const { demo } = await searchParams;
  return <PresenterView demo={demo === "1"} />;
}
