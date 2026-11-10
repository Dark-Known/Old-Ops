# Windows DPAPI (machine scope) helper. Reads one base64 line from stdin and writes one base64 line to stdout.
# {{OPERATION}} is Protect or Unprotect. Run through "powershell -Command" (see util.KeyProtector).
Add-Type -AssemblyName System.Security
$in = [Console]::In.ReadLine()
$b = [Convert]::FromBase64String($in)
[Console]::Out.Write([Convert]::ToBase64String([System.Security.Cryptography.ProtectedData]::{ { OPERATION } }($b, $null, [System.Security.Cryptography.DataProtectionScope]::LocalMachine)))
