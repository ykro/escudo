"""check_coverage — is this kind of incident covered? Automatic: read-only."""

from __future__ import annotations

import unicodedata
from typing import Any

from pydantic import BaseModel, Field

from ._base import Capability, Context, usd

# incident keyword -> coverage that pays for it
_INCIDENTS: dict[str, str] = {
    "collision": "own_damage", "crash": "own_damage", "minor_crash": "own_damage", "accident": "own_damage",
    "choque": "own_damage", "colision": "own_damage", "accidente": "own_damage", "own_damage": "own_damage",
    "golpe": "own_damage", "vidrio": "own_damage", "glass": "own_damage",
    "towing": "towing", "tow": "towing", "grua": "towing",
    "rental": "rental_car", "rental_car": "rental_car", "renta": "rental_car", "auto de reemplazo": "rental_car",
    "theft": "theft", "robo": "theft",
    "third_party": "third_party", "terceros": "third_party", "liability": "third_party",
}

_LABELS = {
    "own_damage": "daños propios",
    "third_party": "daños a terceros",
    "towing": "servicio de grúa",
    "rental_car": "auto de reemplazo",
    "theft": "robo total",
}


class Input(BaseModel):
    incident_type: str = Field(description="Kind of incident, e.g. 'collision', 'theft', 'towing', 'rental_car', 'third_party' (Spanish words work too)")


def _normalize(text: str) -> str:
    text = unicodedata.normalize("NFKD", text.lower().strip())
    return "".join(c for c in text if not unicodedata.combining(c))


async def run(inp: Input, ctx: Context) -> dict[str, Any]:
    policy = await ctx.repo.get_policy_for_customer(ctx.caller.customer_id)
    key = _normalize(inp.incident_type)
    coverage = _INCIDENTS.get(key) or next((c for k, c in _INCIDENTS.items() if k in key), None)
    if coverage is None:
        return {
            "incident_type": inp.incident_type,
            "covered": False,
            "coverage": None,
            "notes": "No reconozco ese tipo de incidente. Prueba con choque, robo, grúa, auto de reemplazo o daños a terceros.",
        }
    covered = coverage in policy.coverages and policy.status == "active"
    label = _LABELS[coverage]
    if not covered:
        notes = f"Tu póliza no incluye {label}."
    elif coverage == "own_damage":
        notes = f"Cubierto por {label}. Pagas un deducible de {usd(policy.deductible_usd)}; Escudo cubre el resto."
    elif coverage == "rental_car":
        notes = f"Cubierto: auto de reemplazo por {policy.rental_days} días sin costo."
    else:
        notes = f"Cubierto por {label}."
    return {"incident_type": inp.incident_type, "covered": covered, "coverage": coverage, "notes": notes}


def summarize(result: dict[str, Any]) -> str:
    label = _LABELS.get(result["coverage"] or "", result["incident_type"])
    return f"Cubierto por {label}" if result["covered"] else f"No cubierto: {label}"


CAPABILITY = Capability(
    name="check_coverage",
    description="Check whether an incident type is covered by the customer's policy. Returns covered (bool) and notes in Spanish.",
    Input=Input,
    run=run,
    summarize=summarize,
)
