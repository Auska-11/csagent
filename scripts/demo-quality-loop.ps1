param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$EvalFile = "target/demo-quality-loop/response_quality_eval_cases.json",
    [switch]$SkipMavenTest
)

$ErrorActionPreference = "Stop"

$WorkspaceRoot = Split-Path -Parent $PSScriptRoot
Set-Location $WorkspaceRoot

function Resolve-WorkspacePath {
    param([string]$PathText)

    if ([System.IO.Path]::IsPathRooted($PathText)) {
        return $PathText
    }
    return Join-Path $WorkspaceRoot $PathText
}

function Invoke-JsonRequest {
    param(
        [string]$Method,
        [string]$Url,
        [object]$Body = $null,
        [string]$BearerToken = ""
    )

    $headers = @{ "Content-Type" = "application/json" }
    if (-not [string]::IsNullOrWhiteSpace($BearerToken)) {
        $headers["Authorization"] = "Bearer $BearerToken"
    }
    if ($null -eq $Body) {
        return Invoke-RestMethod -Method $Method -Uri $Url -Headers $headers
    }

    $json = $Body | ConvertTo-Json -Depth 12
    return Invoke-RestMethod -Method $Method -Uri $Url -Headers $headers -Body $json
}

$EvalFilePath = Resolve-WorkspacePath $EvalFile
$EvalDir = Split-Path -Parent $EvalFilePath
$SourceEvalFile = Join-Path $WorkspaceRoot "src/test/resources/eval/response_quality_eval_cases.json"

# Use a temporary eval file for the demo, so source eval cases stay clean.
New-Item -ItemType Directory -Force -Path $EvalDir | Out-Null
Copy-Item -LiteralPath $SourceEvalFile -Destination $EvalFilePath -Force

$HealthUrl = "$BaseUrl/api/health"
$DemoTokensUrl = "$BaseUrl/api/auth/demo/tokens"
$ChatUrl = "$BaseUrl/api/chat/send"
$FeedbackUrl = "$BaseUrl/api/monitor/feedback"
$CandidatesUrl = "$BaseUrl/api/monitor/feedback/candidates"
$MergeUrl = "$BaseUrl/api/monitor/feedback/candidates/merge"

Write-Host ""
Write-Host "Smart CS Agent quality-loop demo"
Write-Host "Temporary eval file: $EvalFilePath"
Write-Host ""
Write-Host "Start the app in another PowerShell window with:"
Write-Host "`$env:SMARTCS_MONITOR_RESPONSE_QUALITY_EVAL_FILE=`"$EvalFilePath`""
Write-Host "`$env:SMARTCS_MONITOR_EVAL_CANDIDATES_FILE=`"target/demo-quality-loop/feedback-eval-candidates.json`""
Write-Host ".\mvnw.cmd spring-boot:run"
Write-Host ""

try {
    Invoke-RestMethod -Method Get -Uri $HealthUrl | Out-Null
} catch {
    Write-Error "Cannot access $HealthUrl. Start the Spring Boot app first, then run this script again."
}

# USER-001 同时拥有 CUSTOMER 和 ADMIN，能够先发起真实对话，再完成候选审核与合并。
$demoTokens = Invoke-JsonRequest -Method "Post" -Url $DemoTokensUrl
$demoIdentity = $demoTokens | Where-Object { $_.userId -eq "USER-001" } | Select-Object -First 1
if ($null -eq $demoIdentity -or [string]::IsNullOrWhiteSpace($demoIdentity.token)) {
    Write-Error "Cannot obtain USER-001 demo JWT. Ensure SMARTCS_SECURITY_DEMO_ISSUER_ENABLED=true."
}
$bearerToken = $demoIdentity.token

# 反馈必须关联当前用户真实存在的会话，并匹配服务端保存的最后一条助手回复。
$requestedConversationId = "demo-quality-loop-" + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$chatBody = @{
    conversationId = $requestedConversationId
    message = "我要退货"
}

Write-Host "1. Create a real conversation turn..."
$chatResponse = Invoke-JsonRequest -Method "Post" -Url $ChatUrl -Body $chatBody -BearerToken $bearerToken
$conversationId = $chatResponse.conversationId
$assistantReply = $chatResponse.reply
if ([string]::IsNullOrWhiteSpace($conversationId) -or [string]::IsNullOrWhiteSpace($assistantReply)) {
    Write-Error "Chat response did not contain conversationId and reply."
}
Write-Host "   conversationId = $conversationId"

$feedbackBody = @{
    conversationId = $conversationId
    rating = "UNHELPFUL"
    reason = "Missing return timeline; good response-quality negative sample"
    assistantReply = $assistantReply
}

Write-Host "2. Submit UNHELPFUL feedback..."
$feedbackResponse = Invoke-JsonRequest -Method "Post" -Url $FeedbackUrl -Body $feedbackBody -BearerToken $bearerToken
$feedbackId = $feedbackResponse.feedback.feedbackId
Write-Host "   feedbackId = $feedbackId"

Write-Host "3. Find the generated Eval candidate..."
$candidates = Invoke-JsonRequest -Method "Get" -Url $CandidatesUrl -BearerToken $bearerToken
$candidate = $candidates | Where-Object { $_.feedbackId -eq $feedbackId } | Select-Object -First 1
if ($null -eq $candidate) {
    Write-Error "No Eval candidate found for feedbackId=$feedbackId."
}
$candidateId = $candidate.candidateId
Write-Host "   candidateId = $candidateId"

Write-Host "4. Review the candidate as APPROVED..."
$reviewBody = @{
    status = "APPROVED"
    reviewNote = "Demo approved after assertions are completed"
}
$ReviewUrl = "$BaseUrl/api/monitor/feedback/candidates/$candidateId/review"
Invoke-JsonRequest -Method "Post" -Url $ReviewUrl -Body $reviewBody -BearerToken $bearerToken | Out-Null

Write-Host "5. Merge the approved draft into the temporary eval file..."
$caseId = "feedback_" + $candidateId.ToLower().Replace("-", "_")
$mergeBody = @{
    cases = @(
        @{
            sourceCandidateId = $candidateId
            id = $caseId
            candidateReply = $assistantReply
            requiredKeywords = @("return timeline")
            forbiddenKeywords = @("Tool", "tool_")
            expectedPass = $false
        }
    )
}
$mergeResponse = Invoke-JsonRequest -Method "Post" -Url $MergeUrl -Body $mergeBody -BearerToken $bearerToken
Write-Host "   mergedCount = $($mergeResponse.result.mergedCount)"
Write-Host "   afterCount  = $($mergeResponse.result.afterCount)"

if (-not $SkipMavenTest) {
    Write-Host "6. Run ResponseQualityEvaluationTest with the temporary eval file..."
    $mavenArgs = @(
        "-Dmaven.repo.local=.m2\repository",
        "-Dsmartcs.eval.response-quality-file=$EvalFilePath",
        "-Dtest=ResponseQualityEvaluationTest",
        "test"
    )
    & mvn @mavenArgs
    if ($LASTEXITCODE -ne 0) {
        Write-Error "ResponseQualityEvaluationTest failed."
    }
}

Write-Host ""
Write-Host "Quality-loop demo completed: feedback -> candidate -> review -> merge -> regression test."
