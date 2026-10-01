/** Shapes from contracts/openapi.yaml (only the fields the web uses). */
export type Coverage = "own_damage" | "third_party" | "towing" | "rental_car" | "theft";

export type Policy = {
  policy_id: string;
  holder: { name: string; customer_id: string };
  vehicle: { make: string; model: string; year: number; plate: string };
  coverages: Coverage[];
  deductible_usd: number;
  rental_days: number;
  status: "active" | "suspended";
};

export type DamageAssessment = {
  parts: { name: string; damage: string }[];
  severity: "minor" | "moderate" | "severe";
  cost_range_usd: [number, number];
  drivable: boolean;
  summary?: string;
};

export type RepairOption = {
  shop_id: string;
  name: string;
  address: string;
  rating: number;
  slots: string[];
  quote_usd: number;
};

export type TimelineItem = { ts: string; actor: string; surface: string; kind: string; tool: string | null; summary: string };

export type Claim = {
  claim_id: string;
  status: "open" | "assessed" | "tow_requested" | "repair_selected" | "deductible_accepted" | "signed";
  status_label?: string;
  description: string;
  location?: { lat?: number; lng?: number; label?: string } | null;
  created_at: string;
  photos?: string[];
  assessment?: DamageAssessment | null;
  tow?: { provider: string; eta_minutes: number; phone?: string } | null;
  repair?: { shop_id: string; shop_name: string; slot: string; quote_usd: number; address?: string } | null;
  rental?: { days: number; pickup: string; covered: boolean; cost_usd: number } | null;
  deductible_accepted?: boolean;
  signed_at?: string | null;
  repair_options?: RepairOption[];
  timeline?: TimelineItem[];
};

export type Approval = {
  approval_id: string;
  claim_id: string | null;
  tool: string;
  reason: string;
  summary: string;
  amount_usd: number | null;
  status: "pending" | "approved" | "rejected" | "used";
  created_at: string;
};
