# One listener per folder: end a stale one that a lost connection left behind. Only a PowerShell that was started
# with -EncodedCommand (i.e. ours) is touched, so a reused PID is never killed by mistake.
$f=Join-Path $env:TEMP('opstool-w-'+($p.ToLower() -replace '[^a-z0-9]','_')+'.pid')
$o=0;if(Test-Path -LiteralPath $f){[void][int]::TryParse((Get-Content -LiteralPath $f -EA 0|Out-String).Trim(),[ref]$o)}
if($o -gt 0 -and $o -ne $PID){$q=Get-Process -Id $o -EA 0;if($q -and $q.ProcessName -like 'powershell*' -and [string](Get-CimInstance Win32_Process -Filter('ProcessId='+$o) -EA 0).CommandLine -like '*-EncodedCommand*'){Stop-Process -Id $o -Force -EA 0}}
Set-Content -LiteralPath $f -Value $PID
