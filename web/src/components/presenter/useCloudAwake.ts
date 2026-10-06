"use client";
import { useEffect, useState } from "react";

export type Awake = "waking" | "ready" | "retrying";

/**
 * Wakes the API when the presenter opens (GET /api/presenter/wake) and keeps trying until it answers,
 * so the first step on the phone never meets a cold service. The replay (?demo=1) needs no cloud.
 */
export function useCloudAwake(skip: boolean): Awake {
  const [awake, setAwake] = useState<Awake>(skip ? "ready" : "waking");
  useEffect(() => {
    if (skip) return;
    let stopped = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const attempt = async () => {
      try {
        const r = await fetch("/api/presenter/wake", { cache: "no-store" });
        if (r.ok) {
          if (!stopped) setAwake("ready");
          return;
        }
      } catch {}
      if (stopped) return;
      setAwake("retrying");
      timer = setTimeout(attempt, 3000);
    };
    attempt();
    return () => {
      stopped = true;
      if (timer) clearTimeout(timer);
    };
  }, [skip]);
  return awake;
}
