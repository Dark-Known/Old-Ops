# Last words (why the listener ended), then release the watcher and remove this listener's PID file.
try{[Console]::Out.WriteLine('{{END}}'+$why);[Console]::Out.Flush()}catch{}
Get-EventSubscriber -EA 0|Unregister-Event -EA 0
if($w){$w.Dispose()}
if($f -and(Test-Path -LiteralPath $f)){if((Get-Content -LiteralPath $f -EA 0|Out-String).Trim() -eq [string]$PID){Remove-Item -LiteralPath $f -Force -EA 0}}
