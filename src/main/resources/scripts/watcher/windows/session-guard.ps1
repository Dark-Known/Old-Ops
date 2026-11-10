# Orphan guard. Console writes to a closed pipe do not throw, so the heartbeat alone never ends an orphan.
# Find the sshd that started this script (walk up the parents; the direct parent if there is none) and remember
# its PID and start time. The event loop ends the script as soon as that process is gone.
$g = 0; $gs = $null
try
{
    $c = $PID; for($i = 0; $i -lt 6; $i++){
        $r = Get-CimInstance Win32_Process -Filter('ProcessId=' + $c); $a = [int]$r.ParentProcessId; $b = Get-Process -Id $a -EA 0; if (-not $b)
        {
            break
        }; if (-not $g -or $b.ProcessName -like 'sshd*')
        {
            $g = $a; $gs = $null; try
            {
                $gs = $b.StartTime
            }
            catch
            {
            }
        }; if ($b.ProcessName -like 'sshd*')
        {
            break
        }; $c = $a
    }
}
catch
{
}
