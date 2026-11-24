param(
    [Parameter(Mandatory)]
    [uri]$JudgeUrl,
    [string]$LanguageMap = $env:SMARTEVAL_JUDGE_LANGUAGES
)

$ErrorActionPreference = 'Stop'
if ($JudgeUrl.Scheme -notin @('http','https') -or $JudgeUrl.UserInfo -or $JudgeUrl.Query -or $JudgeUrl.Fragment) {
    throw '请输入 Judge0 API 根地址，可以包含部署路径。'
}
$base = $JudgeUrl.AbsoluteUri.TrimEnd('/')
$headers = @{}
if ($env:SMARTEVAL_JUDGE_TOKEN) { $headers['X-Auth-Token'] = $env:SMARTEVAL_JUDGE_TOKEN }
$runtimeNames = [ordered]@{
    CPP = '^C\+\+(?:\s|$)'
    JAVA = '^Java\s'
    PYTHON = '^Python\s+\(?(?:3|CPython)'
    C = '^C\s'
    JAVASCRIPT = '^JavaScript\s.*Node'
}
$snippets = @{
    CPP = '#include <iostream>' + [Environment]::NewLine + 'int main(){int n; std::cin>>n; std::cout<<n+1<<std::endl;}'
    JAVA = 'import java.util.Scanner; public class Main { public static void main(String[] args){System.out.println(new Scanner(System.in).nextInt()+1);} }'
    PYTHON = 'print(int(input())+1)'
    C = '#include <stdio.h>' + [Environment]::NewLine + 'int main(void){int n; if(scanf("%d",&n)==1) printf("%d\n",n+1); return 0;}'
    JAVASCRIPT = 'console.log(Number(require("fs").readFileSync(0,"utf8").trim())+1)'
}
$mapping = @{}
if ($LanguageMap) {
    foreach ($item in $LanguageMap.Split(',')) {
        $parts = $item.Trim().Split('=', 2)
        if ($parts.Count -ne 2 -or !$runtimeNames.Contains($parts[0]) -or $parts[1] -notmatch '^[1-9][0-9]*$' -or $mapping.ContainsKey($parts[0])) {
            throw "语言映射格式无效：$item"
        }
        $mapping[$parts[0]] = [int]$parts[1]
    }
}
$runtimes = Invoke-RestMethod "$base/languages" -Headers $headers -TimeoutSec 8
foreach ($language in $runtimeNames.Keys) {
    $runtime = if ($mapping.ContainsKey($language)) {
        $runtimes | Where-Object id -eq $mapping[$language] | Select-Object -First 1
    } else {
        $runtimes | Where-Object name -Match $runtimeNames[$language] | Sort-Object {[int]$_.id} -Descending | Select-Object -First 1
    }
    if (!$runtime -or $runtime.name -notmatch $runtimeNames[$language]) { throw "Judge0 未返回可用的 $language 运行时。请通过 LanguageMap 指定该实例的语言 ID。" }
    $mapping[$language] = [int]$runtime.id
    $source = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($snippets[$language]))
    $stdin = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("41" + [char]10))
    $request = @{
        source_code = $source
        stdin = $stdin
        language_id = $runtime.id
        enable_network = $false
        cpu_time_limit = 3
        wall_time_limit = 5
        memory_limit = 262144
    } | ConvertTo-Json -Compress
    $submission = Invoke-RestMethod "$base/submissions?base64_encoded=true&wait=false" -Method Post -Headers $headers -ContentType 'application/json' -Body $request -TimeoutSec 8
    if ($submission.token -notmatch '^[A-Za-z0-9-]{8,100}$') { throw 'Judge0 未返回有效任务编号。' }
    $limit = [DateTimeOffset]::UtcNow.AddSeconds(20)
    do {
        Start-Sleep -Milliseconds 350
        $result = Invoke-RestMethod "$base/submissions/$($submission.token)?base64_encoded=true&fields=status,stdout" -Headers $headers -TimeoutSec 8
        if ($result.status.id -gt 2) { break }
    } while ([DateTimeOffset]::UtcNow -lt $limit)
    $output = if ($result.stdout) { [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($result.stdout)).Trim() } else { '' }
    if ($result.status.id -ne 3 -or $output -ne '42') { throw "$language 样例运行失败，Judge0 状态：$($result.status.description)。" }
    Write-Host "$language 通过，$($runtime.name)"
}
'SMARTEVAL_JUDGE_LANGUAGES=' + (($runtimeNames.Keys | ForEach-Object { "$_=$($mapping[$_])" }) -join ',')

