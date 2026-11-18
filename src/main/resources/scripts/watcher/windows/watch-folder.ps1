# Start watching the folder for Created / Changed / Renamed. Anything else is reported as "unavailable".
$p='{{PATH}}'
if(-not(Test-Path -LiteralPath $p -PathType Container)){Write-Output('{{UNAVAILABLE}}:path not found or not a directory: '+$p);exit}
$w=New-Object IO.FileSystemWatcher $p
$w.EnableRaisingEvents=$true
'Created','Changed','Renamed'|%{[void](Register-ObjectEvent $w $_)}
