from fastapi import APIRouter

from app.compile.requirements import compile_requirements
from app.generate.interview_kits import generate_interview_kit
from app.generate.work_samples import generate_work_sample
from app.models.authoring import (
    CompileRequest, CompileResult, InterviewKitRequest, InterviewKitResponse, WorkSampleRequest, WorkSampleResponse,
)

router = APIRouter()


@router.post("/requirements/compile", response_model=CompileResult)
def compile_endpoint(request: CompileRequest) -> CompileResult:
    return compile_requirements(request)


@router.post("/work-samples/generate", response_model=WorkSampleResponse)
def work_sample_endpoint(request: WorkSampleRequest) -> WorkSampleResponse:
    return generate_work_sample(request)


@router.post("/interview-kits/generate", response_model=InterviewKitResponse)
def interview_kit_endpoint(request: InterviewKitRequest) -> InterviewKitResponse:
    return generate_interview_kit(request)
