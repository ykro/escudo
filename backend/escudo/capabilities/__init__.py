"""Escudo's capabilities: the single domain behind REST, MCP, A2A, voice, AppFunctions and WebMCP.

One module per capability. Adapters never call these directly; they go
through `escudo.gateway.call_capability`, which applies the autonomy table.
"""

from ._base import Capability, Caller, Context
from . import (
    accept_deductible,
    attach_claim_photos,
    check_coverage,
    get_claim_status,
    get_policy,
    list_repair_options,
    open_claim,
    request_rental_car,
    request_tow,
    select_repair_shop,
    sign_claim,
)

# Order matches contracts/tools.md.
REGISTRY: dict[str, Capability] = {
    m.CAPABILITY.name: m.CAPABILITY
    for m in (
        get_policy,
        check_coverage,
        open_claim,
        attach_claim_photos,
        request_tow,
        list_repair_options,
        request_rental_car,
        accept_deductible,
        select_repair_shop,
        sign_claim,
        get_claim_status,
    )
}

__all__ = ["REGISTRY", "Capability", "Caller", "Context"]
