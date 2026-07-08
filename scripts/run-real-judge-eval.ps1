$ErrorActionPreference = "Stop"

param(
    [switch]$AssertExpectation
)

$assertFlag = "false"
if ($AssertExpectation) {
    $assertFlag = "true"
}

Write-Host "Running optional real LLM-as-Judge evaluation..."
Write-Host "Set OPENAI_API_KEY / OPENAI_BASE_URL / OPENAI_MODEL_NAME before running this script."
Write-Host "Assert expectation: $assertFlag"

mvn `
    "-Dmaven.repo.local=.m2\repository" `
    "-Dsmartcs.eval.real-judge.enabled=true" `
    "-Dsmartcs.eval.real-judge.assert-expectation=$assertFlag" `
    "-Dtest=LlmAsJudgeEvaluationTest" `
    test
