from fastapi import APIRouter

from app.matching.engine import match_batch, match_requirements
from app.models.matching import BatchMatchRequest, BatchMatchResponse, MatchRequest, MatchResponse

router = APIRouter()


# Plain ``def`` endpoints run in the thread pool, off the event loop.
@router.post("/match", response_model=MatchResponse)
def match_endpoint(request: MatchRequest) -> MatchResponse:
    return match_requirements(request)


@router.post("/match/batch", response_model=BatchMatchResponse)
def batch_match_endpoint(request: BatchMatchRequest) -> BatchMatchResponse:
    return match_batch(request)
