import type { Metadata } from "next";
import { PresenterView } from "@/components/presenter/PresenterView";

export const metadata: Metadata = { title: "Presentador" };
export const dynamic = "force-dynamic";

export default async function PresenterPage({ searchParams }: { searchParams: Promise<{ demo?: string; run?: string }> }) {
  const { demo, run } = await searchParams;
  // ?run=<id> follows one scenario run (printed by make scenario); without it, the latest run.
  return <PresenterView demo={demo === "1"} run={run} />;
}
