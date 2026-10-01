"use client";
import { config } from "./config";
import { firebaseAuth } from "./firebase";

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) {
    super(message);
  }
}

/** Who is calling, for the presenter timeline (X-Escudo-* headers, see contracts/tools.md). */
export type Caller = { surface: "web" | "webmcp"; actor: "customer" | "gemini" };
export const PORTAL: Caller = { surface: "web", actor: "customer" };
export const WEBMCP: Caller = { surface: "webmcp", actor: "gemini" };

export async function api<T>(path: string, caller: Caller = PORTAL, init: RequestInit = {}): Promise<T> {
  const user = firebaseAuth().currentUser;
  if (!user) throw new ApiError(401, "unauthenticated", "Inicia sesión en Escudo para continuar.");
  const token = await user.getIdToken();
  let response: Response;
  try {
    response = await fetch(`${config.apiUrl}${path}`, {
      ...init,
      headers: {
        Authorization: `Bearer ${token}`,
        "X-Escudo-Surface": caller.surface,
        "X-Escudo-Actor": caller.actor,
        ...(init.body ? { "Content-Type": "application/json" } : {}),
        ...init.headers,
      },
    });
  } catch {
    throw new ApiError(0, "network", "No pudimos conectar con Escudo. Revisa tu conexión e intenta de nuevo.");
  }
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new ApiError(response.status, body.error ?? "error", body.message ?? "Algo salió mal. Intenta de nuevo.");
  }
  return (await response.json()) as T;
}
