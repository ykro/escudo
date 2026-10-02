/** Spanish labels for contract enums (contracts/events.schema.json, openapi.yaml). */

export const SURFACE_LABEL: Record<string, string> = {
  android: "Android",
  web: "Web",
  mcp: "MCP",
  a2a: "A2A",
  rest: "API",
  chat: "Chat",
  appfunctions: "AppFunctions",
  webmcp: "WebMCP",
};

export const ACTOR_LABEL: Record<string, string> = {
  customer: "Andrea",
  escudo_assistant: "Asistente Escudo",
  escudo_agent: "Agente Escudo",
  tow_agent: "Agente de grúa",
  repair_agent: "Agente de talleres",
  damage_assessor: "Perito IA",
  gemini: "Gemini",
  external_agent: "Agente externo",
  backend: "Escudo",
};

export const COVERAGE_LABEL: Record<string, string> = {
  own_damage: "Daños propios",
  third_party: "Daños a terceros",
  towing: "Servicio de grúa",
  rental_car: "Auto de reemplazo",
  theft: "Robo total",
};

export const ALL_COVERAGES = ["own_damage", "third_party", "towing", "rental_car", "theft"] as const;

export const CLAIM_STEPS = [
  { status: "open", label: "Abierto" },
  { status: "assessed", label: "Evaluado" },
  { status: "tow_requested", label: "Grúa" },
  { status: "repair_selected", label: "Taller" },
  { status: "deductible_accepted", label: "Deducible" },
  { status: "signed", label: "Firmado" },
] as const;

export const SEVERITY_LABEL: Record<string, string> = { minor: "leve", moderate: "moderado", severe: "severo" };

const DAYS = ["domingo", "lunes", "martes", "miércoles", "jueves", "viernes", "sábado"];
const MONTHS = ["enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre"];

/** Slot as the shop's local wall-clock time: "jueves 1 de octubre, 8:00". */
export function slotLabel(iso: string): string {
  const m = iso.match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/);
  if (!m) return iso;
  const [, y, mo, d, h, mi] = m;
  const weekday = new Date(Date.UTC(+y, +mo - 1, +d)).getUTCDay();
  return `${DAYS[weekday]} ${+d} de ${MONTHS[+mo - 1]}, ${+h}:${mi}`;
}

/** One currency format everywhere (backend, app and web): "USD 500". */
export function usd(n: number): string {
  return `USD ${n.toLocaleString("es-GT", { maximumFractionDigits: 0 })}`;
}
