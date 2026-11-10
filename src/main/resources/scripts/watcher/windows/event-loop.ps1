# Print the name of each changed file, a heartbeat line every 5 s of silence, and leave when the SSH session is gone.
$why='stopped'
while($true){
if($g){$x=Get-Process -Id $g -EA 0;$d=-not $x;if($x -and $gs){try{if($x.StartTime -ne $gs){$d=$true}}catch{}};if($d){$why='the SSH session that started it (process '+$g+') is gone';break}}
$e=Wait-Event -Timeout 5
if($e -eq $null){[Console]::Out.WriteLine('{{HB}}');[Console]::Out.Flush();continue}
Write-Output $e.SourceEventArgs.Name
Remove-Event -EventIdentifier $e.EventIdentifier}
