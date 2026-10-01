"use client";
import { useCallback, useEffect, useState } from "react";
import { api, ApiError } from "./api";

export type Loadable<T> =
  | { status: "loading" }
  | { status: "error"; error: ApiError }
  | { status: "ready"; data: T };

/** GET a path from the Escudo API; `null` path means "not yet" (stays loading). */
export function useApi<T>(path: string | null, pollMs?: number): Loadable<T> & { reload: () => void } {
  const [state, setState] = useState<Loadable<T>>({ status: "loading" });
  const [tick, setTick] = useState(0);
  const reload = useCallback(() => setTick((t) => t + 1), []);

  useEffect(() => {
    if (!path) return;
    let alive = true;
    api<T>(path)
      .then((data) => alive && setState({ status: "ready", data }))
      .catch((error: ApiError) => alive && setState({ status: "error", error }));
    const timer = pollMs ? setInterval(() => setTick((t) => t + 1), pollMs) : undefined;
    return () => {
      alive = false;
      if (timer) clearInterval(timer);
    };
  }, [path, tick, pollMs]);

  return { ...state, reload };
}
