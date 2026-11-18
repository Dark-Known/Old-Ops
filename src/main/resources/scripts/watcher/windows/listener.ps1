# Windows change listener, started by the app with "powershell -EncodedCommand" over SSH (see ListenerScripts).
# This file is only the skeleton; each concern lives in its own file next to it and is pulled in by {{include:...}}.
# Comment lines, blank lines and leading spaces are removed before sending: sshd runs the command through cmd.exe,
# whose command line may not exceed 8191 characters, and the UTF-16 base64 form is 2.7x the script.
# Filled in by the app: {{PATH}} {{HB}} {{END}} {{UNAVAILABLE}}
$ErrorActionPreference='Stop';$ProgressPreference='SilentlyContinue'
try{
{{include:watch-folder.ps1}}
{{include:takeover.ps1}}
{{include:session-guard.ps1}}
{{include:event-loop.ps1}}
}catch{$why='error: '+$_.Exception.Message;Write-Output('{{UNAVAILABLE}}:'+$_.Exception.Message)}
finally{
{{include:cleanup.ps1}}
}
exit
