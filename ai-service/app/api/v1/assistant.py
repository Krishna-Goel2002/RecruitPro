from fastapi import APIRouter
from pydantic import TypeAdapter

from app.assistant.planner import plan_step
from app.models.assistant import StepRequest, StepResponse

router = APIRouter()
_RESPONSE = TypeAdapter(StepResponse)


# Plain ``def``: an LLM call blocks, so the endpoint runs in the thread pool.
@router.post("/assistant/step", response_model=StepResponse)
def assistant_step(request: StepRequest) -> StepResponse:
    """One planning step for the HR assistant (stateless; the backend runs the loop and executes tools)."""
    return _RESPONSE.validate_python(plan_step(request))
