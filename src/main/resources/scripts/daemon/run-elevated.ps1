# Runs another PowerShell script with a UAC prompt and waits for it. {{ENCODED}} is that script as base64 (UTF-16LE),
# so no quoting layer between here and the elevated process can misread it.
Start-Process powershell.exe -ArgumentList '-NoProfile','-NonInteractive','-EncodedCommand','{{ENCODED}}' -Verb RunAs -Wait
