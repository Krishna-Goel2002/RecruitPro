"""HR assistant planner contract (docs/plan/ASSISTANT_PLAN.md section 3.1).

The planner is stateless: the backend sends the de-identified conversation, the page context, the user's tool
catalogue, this turn's tool observations and the slot-filling draft, and gets back exactly one next step.
"""
from __future__ import annotations

import json
from typing import Annotated, Any, Literal, Union

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

PAGES = ("job", "applicant", "jobs", "admin", "other")
MAX_MESSAGES = 20
MAX_MESSAGE_CHARS = 4000
MAX_TOOLS = 40
MAX_OBSERVATIONS = 12
MAX_OBSERVATION_BYTES = 64 * 1024  # each observation's JSON
MAX_DRAFT_BYTES = 32 * 1024


def _json_size(value: Any) -> int:
    try:
        return len(json.dumps(value, ensure_ascii=False, default=str))
    except (TypeError, ValueError):
        return MAX_OBSERVATION_BYTES + 1


class ChatMessage(BaseModel):
    role: Literal["user", "assistant"]
    text: str = Field(default="", max_length=MAX_MESSAGE_CHARS)


class StepContext(BaseModel):
    page: str = "other"
    jobId: int | None = Field(default=None, ge=1, le=2**53)
    jobTitle: str | None = Field(default=None, max_length=255)
    applicationCode: str | None = Field(default=None, max_length=32)
    applicationId: int | None = Field(default=None, ge=1, le=2**53)  # optional; derived from the code otherwise
    role: str | None = Field(default=None, max_length=40)
    today: str | None = Field(default=None, pattern=r"^\d{4}-\d{2}-\d{2}$")  # the user's local date (optional)

    @field_validator("page", mode="before")
    @classmethod
    def _known_page(cls, value: Any) -> str:
        # Unknown pages are treated as "other" rather than refused: the planner must never break the UI.
        text = str(value or "other").strip().lower()
        return text if text in PAGES else "other"


class ToolSpec(BaseModel):
    name: str = Field(pattern=r"^[a-z][a-z0-9_]{0,63}$")
    description: str = Field(default="", max_length=600)
    args: dict[str, Any] = Field(default_factory=dict)
    writes: bool = False

    @field_validator("args")
    @classmethod
    def _small_args(cls, value: dict[str, Any]) -> dict[str, Any]:
        if len(value) > 20 or _json_size(value) > 4000:
            raise ValueError("args schema too large")
        return value


class Observation(BaseModel):
    tool: str = Field(max_length=64)
    args: dict[str, Any] = Field(default_factory=dict)
    ok: bool = True
    data: Any = None
    error: str | None = Field(default=None, max_length=1000)

    @model_validator(mode="after")
    def _bounded(self) -> "Observation":
        if _json_size(self.data) > MAX_OBSERVATION_BYTES or _json_size(self.args) > 4000:
            raise ValueError("observation too large")
        return self


class Draft(BaseModel):
    model_config = ConfigDict(extra="allow")
    kind: str = Field(max_length=40)
    fields: dict[str, Any] = Field(default_factory=dict)

    @model_validator(mode="after")
    def _bounded(self) -> "Draft":
        if _json_size(self.model_dump()) > MAX_DRAFT_BYTES:
            raise ValueError("draft too large")
        return self


class StepRequest(BaseModel):
    messages: list[ChatMessage] = Field(min_length=1, max_length=MAX_MESSAGES)
    context: StepContext = Field(default_factory=StepContext)
    tools: list[ToolSpec] = Field(default_factory=list, max_length=MAX_TOOLS)
    observations: list[Observation] = Field(default_factory=list, max_length=MAX_OBSERVATIONS)
    draft: Draft | None = None
    allowExternalModel: bool = False


# ------------------------------------------------------------------------------------------- response
class StepMeta(BaseModel):
    planner: str
    model: str | None = None
    fallback: bool = False


class ToolCallStep(BaseModel):
    type: Literal["tool_call"] = "tool_call"
    tool: str
    args: dict[str, Any] = Field(default_factory=dict)
    thought: str = ""
    draft: dict[str, Any] | None = None
    meta: StepMeta


class AskStep(BaseModel):
    type: Literal["ask"] = "ask"
    text: str
    field: str
    options: list[str] = Field(default_factory=list)
    draft: dict[str, Any] | None = None
    meta: StepMeta


class ProposeStep(BaseModel):
    type: Literal["propose"] = "propose"
    action: str
    args: dict[str, Any] = Field(default_factory=dict)
    summary: str
    blocks: list[dict[str, Any]] = Field(default_factory=list)
    draft: dict[str, Any] | None = None
    meta: StepMeta


class AnswerStep(BaseModel):
    type: Literal["answer"] = "answer"
    text: str
    blocks: list[dict[str, Any]] = Field(default_factory=list)
    draft: dict[str, Any] | None = None
    meta: StepMeta


StepResponse = Annotated[Union[ToolCallStep, AskStep, ProposeStep, AnswerStep], Field(discriminator="type")]
