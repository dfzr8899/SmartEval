# SmartEval API v2 接入示例

## 一份结构化作业

在工作台的「API 接入」创建密钥。下例从环境变量读取，使用 PowerShell 7 和当前部署的地址。创建任务没有依赖题库。将 `kind` 改为 `TEXT` 并省略 `rubric` 时，草案由已配置的评分标准 Agent 拟定。

```powershell
$base = 'http://localhost:8088/api/v2'
$headers = @{ 'X-API-Key' = $env:SMARTEVAL_TEST_API_KEY }
$submitHeaders = $headers + @{ 'Idempotency-Key' = [guid]::NewGuid().ToString() }
$body = @{
    title = '样例作业'
    questions = @(@{
        id = '1'
        prompt = '2 + 3 等于多少？'
        kind = 'EXACT'
        max_score = 5
        accepted = @('5')
        answers = @(@{ submission_id = 'demo-01'; text = '5' })
    })
} | ConvertTo-Json -Depth 10
$job = Invoke-RestMethod "$base/grading-jobs" -Method Post -Headers $submitHeaders -ContentType 'application/json; charset=utf-8' -Body $body
$jobUrl = "$base/grading-jobs/$($job.id)"
Invoke-RestMethod $jobUrl -Headers $headers
Invoke-RestMethod "$jobUrl/draft" -Headers $headers
```

草案准备完成后任务进入 `AWAITING_CONFIRMATION`，此时教师可以从「待处理」中核对并确认。自动集成可将任务 ID 提供给教师，在当前工作空间使用教师会话的短期访问令牌代为发出明确的确认请求。确认前读取最新的 ETag，如需修改调用 `PATCH /grading-jobs/{id}/draft` 后使用返回的新 ETag。提交密钥本身无法确认细则。

```powershell
$teacherHeaders = @{ Authorization = "Bearer $env:SMARTEVAL_TEACHER_ACCESS_TOKEN" }
$draftResponse = Invoke-WebRequest "$jobUrl/draft" -Headers $teacherHeaders
$teacherHeaders['If-Match'] = [string]$draftResponse.Headers['ETag'][0]
Invoke-RestMethod "$jobUrl/confirm" -Method Post -Headers $teacherHeaders
```

轮询任务状态并按需获取报告。排队期间报告会列出已完成和未完成项。重复提交同一个业务请求时复用原请求标识，重复标识与不同请求体会返回 409。处理 412 时重新读取草案或单题的当前版本。

```powershell
do {
    $status = Invoke-RestMethod $jobUrl -Headers $headers
    if ($status.status -in @('QUEUED', 'GRADING')) { Start-Sleep -Seconds 2 }
} while ($status.status -in @('QUEUED', 'GRADING'))
Invoke-RestMethod "$jobUrl/report" -Headers $headers | ConvertTo-Json -Depth 20
```

## 上传一份 PDF 或手写答卷

```powershell
$upload = Invoke-RestMethod "$base/assets" -Method Post -Headers $headers -Form @{ file = Get-Item 'answer.pdf' }
$fileBody = @{
    title = '待批 PDF'
    materials = @(@{ asset_id = $upload.asset_id; role = 'ANSWER'; submission_id = 'demo-02' })
} | ConvertTo-Json -Depth 6
$submitHeaders['Idempotency-Key'] = [guid]::NewGuid().ToString()
Invoke-RestMethod "$base/grading-jobs" -Method Post -Headers $submitHeaders -ContentType 'application/json; charset=utf-8' -Body $fileBody
```

文件可指定 `QUESTION`、`REFERENCE` 或 `ANSWER`，其中作答需指定答卷编号。代码文件或内联系统使用 `kind: CODE`，在草案里提供语言和测试用例。评分分值为整数单位。如果业务采用小数分，可按十分之一分或百分之一分约定统一单位。
