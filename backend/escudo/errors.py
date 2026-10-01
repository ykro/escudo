"""Domain errors. Adapters translate them: REST status codes, MCP tool errors, A2A text."""

from __future__ import annotations


class EscudoError(Exception):
    status = 400

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message  # Spanish, safe to show to the customer

    def to_dict(self) -> dict[str, str]:
        return {"error": self.code, "message": self.message}


class NotFound(EscudoError):
    status = 404


class Conflict(EscudoError):
    status = 409


class InvalidInput(EscudoError):
    status = 422


class ApprovalInvalid(EscudoError):
    """A gated action was retried with an approval that is missing, pending, used or bound to other args."""

    status = 403

    def __init__(self, detail: str, message: str) -> None:
        super().__init__("approval_invalid", message)
        self.detail = detail

    def to_dict(self) -> dict[str, str]:
        return {"error": self.code, "detail": self.detail, "message": self.message}


class Unauthorized(EscudoError):
    status = 401


class Forbidden(EscudoError):
    status = 403
