# Status of the daemon service (Running, Stopped, ...); prints nothing when it is not installed. Needs no elevation.
Get-Service -Name '{{SERVICE}}' -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Status
