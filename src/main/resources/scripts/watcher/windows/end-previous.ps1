# Sent as its own command (not part of the listener) when the app arms a listener again. {{TARGETS}} is the list of
# processes the app recorded from earlier listeners: "pid:start" pairs (start = FILETIME), comma separated.
# A process is ended only if it still has that pid AND that start time AND is PowerShell or cmd.exe, so a pid number that
# has been reused by something else is left alone. One result line per entry: {{GONE}}<pid> already exited,
# {{KILLED}}<pid> ended now, {{KEPT}}<pid> not ours any more (left alone), {{FAILED}}<pid> still running after the attempt.
$ErrorActionPreference='Continue';$ProgressPreference='SilentlyContinue'
foreach($t in '{{TARGETS}}'.Split(',')){
$x=$t.Split(':');$id=[int]$x[0];$st=[long]$x[1]
$q=Get-Process -Id $id -EA 0
if(-not $q){Write-Output('{{GONE}}'+$id);continue}
$qs=0;try{$qs=$q.StartTime.ToFileTimeUtc()}catch{}
if($qs -ne $st -or $q.ProcessName -notmatch '^(powershell|pwsh|cmd)$'){Write-Output('{{KEPT}}'+$id);continue}
Stop-Process -Id $id -Force -EA 0
Start-Sleep -Milliseconds 150
if(Get-Process -Id $id -EA 0){Write-Output('{{FAILED}}'+$id)}else{Write-Output('{{KILLED}}'+$id)}}
