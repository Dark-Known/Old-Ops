# Tells the app which processes make up this listener, so that if the connection is lost the app can end exactly these
# (and nothing else) before it arms a new listener. Nothing here ends a process.
# Line: {{PROC}}pid=<n>;start=<n>;ppid=<n>;pname=<name>;pstart=<n>;sshd=<n>
# start/pstart are FILETIME values: together with the pid they identify a process even after the pid number is reused.
# ppid is the cmd.exe (or shell) sshd started this script through; sshd is informational only and is never ended.
try{$me=[Diagnostics.Process]::GetCurrentProcess();$info='pid='+$me.Id+';start='+$me.StartTime.ToFileTimeUtc();if($pp -and $pps){$info+=';ppid='+$pp+';pname='+$pn+';pstart='+$pps};$info+=';sshd='+$g;[Console]::Out.WriteLine('{{PROC}}'+$info);[Console]::Out.Flush()}catch{}
