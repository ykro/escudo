"use client";
import { useEffect, useState } from "react";
import { api, WEBMCP } from "@/lib/api";
import { escudoTools, findModelContext, registerTools } from "@/lib/webmcp";
import styles from "./portal.module.css";

type Status = "checking" | "ready" | "unsupported" | "failed";

// Once per page: tool names are unique per document, and every tool reads the signed-in user at
// call time (they answer "Inicia sesión…" when nobody is), so there is nothing to re-register.
let registration: Promise<{ status: Status; count: number; error?: string }> | null = null;

function registerOnce() {
  registration ??= (async () => {
    const mc = findModelContext(window);
    if (!mc) return { status: "unsupported" as const, count: 0 };
    const tools = escudoTools((path) => api(path, WEBMCP));
    try {
      await registerTools(mc, tools);
      return { status: "ready" as const, count: tools.length };
    } catch (e) {
      console.error("WebMCP registration failed", e);
      return { status: "failed" as const, count: 0, error: String(e) };
    }
  })();
  return registration;
}

/** Registers Escudo's WebMCP tools and says so in the footer. */
export function WebMcpTools() {
  const [status, setStatus] = useState<Status>("checking");
  const [count, setCount] = useState(0);

  useEffect(() => {
    let alive = true;
    registerOnce().then((r) => {
      if (!alive) return;
      setStatus(r.status);
      setCount(r.count);
    });
    return () => {
      alive = false;
    };
  }, []);

  return (
    <p className={styles.webmcp} data-webmcp={status}>
      <span className={styles.webmcpDot} aria-hidden />
      {status === "ready" && `WebMCP · ${count} herramientas disponibles para el agente de tu navegador`}
      {status === "checking" && "WebMCP · preparando herramientas…"}
      {status === "unsupported" && "WebMCP no está activo en este navegador"}
      {status === "failed" && "WebMCP · no se pudieron registrar las herramientas"}
    </p>
  );
}
