# 给出的每个测试类都要真的跑过、没有跳过的用例，否则失败（windows-server 任务 12）。
# 没装浏览器、不是 Windows 时会自己跳过的类，跳过了构建照样是绿的，等于没测。读 surefire 和 failsafe 的 JUnit XML 报告。
param([Parameter(Mandatory, ValueFromRemainingArguments)][string[]]$Classes)

$ErrorActionPreference = 'Stop'
$reports = Get-ChildItem -Path tantalum-*/target -Recurse -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue |
  Where-Object { $_.FullName -match '[\\/](surefire|failsafe)-reports[\\/]' }
$problems = @()
foreach ($class in $Classes) {
  # 嵌套的测试类有自己的报告（Outer$Inner），算在外面的类里
  $cases = @($reports | ForEach-Object { ([xml](Get-Content -Raw $_.FullName)).testsuite.testcase } |
    Where-Object { $_ -and (($_.classname -split '\.')[-1] -split '\$')[0] -eq $class })
  if ($cases.Count -eq 0) {
    $problems += "${class}: no test cases ran"
    continue
  }
  $skipped = @($cases | Where-Object { $_.SelectSingleNode('skipped') })
  if ($skipped.Count -gt 0) {
    $problems += "${class}: $($skipped.Count) of $($cases.Count) cases were skipped: " + (($skipped | ForEach-Object { $_.name }) -join ', ')
  } else {
    "${class}: $($cases.Count) cases ran, none skipped"
  }
}
if ($problems.Count -gt 0) {
  $problems
  exit 1
}
