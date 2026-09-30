"""ADK agents that talk to each other over A2A.

- Escudo agent (`/a2a/escudo`): the insurer's agent. When a capability needs a
  tow truck or repair quotes it delegates to partner agents it discovers from
  their Agent Cards (`A2A_PEERS` holds base URLs only).
- Tow agent (`/a2a/tow`) and Repair agent (`/a2a/repair`): partners. They live
  in the same service for simplicity but are reached only over A2A, exactly
  as if they belonged to other companies.
"""
