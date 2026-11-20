# Windows change listener, started by the app with "powershell -EncodedCommand" over SSH (see ListenerScripts).
# This file is only the skeleton; each concern lives in its own file next to it and is pulled in by {{include:...}}.
# Comment lines, blank lines and leading spaces are removed before sending: sshd runs the command through cmd.exe,
# whose command line may not exceed 8191 characters, and the UTF-16 base64 form is 2.7x the script.
# Filled in by the app: {{PATH}} {{HB}} {{END}} {{UNAVAILABLE}} {{STEP}} {{PROC}}
# This script never ends another process. It reports its own processes ({{PROC}} line); the app remembers them and,
# when it has to arm a new listener, ends exactly those (end-previous.ps1, a separate command).
# Mark prints a breadcrumb line ({{STEP}}<text>) after each startup phase. The app logs them and ignores them as events, so when
# the process disappears early the log says which phase it reached (and whether it started at all).
$ErrorActionPreference='Stop';$ProgressPreference='SilentlyContinue'
function Mark($m){try{[Console]::Out.WriteLine('{{STEP}}'+$m);[Console]::Out.Flush()}catch{}}
Mark 'started'
try{
{{include:watch-folder.ps1}}
{{include:session-guard.ps1}}
{{include:report-processes.ps1}}
{{include:event-loop.ps1}}
}catch{$why='error: '+$_.Exception.Message;Write-Output('{{UNAVAILABLE}}:'+$_.Exception.Message)}
finally{
{{include:cleanup.ps1}}
}
exit
