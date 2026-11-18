# Linux change listener: one inotifywait per watched folder, run through "sh -c" over SSH (see ListenerScripts).
# Every line ends with ; or & because the lines are joined with spaces into one command.
# Filled in by the app: {{DIR}} (already shell-quoted) {{HB}} {{UNAVAILABLE}}
# Fail closed with the marker when inotifywait is missing, so the shell's own "command not found" never looks like a filename.
command -v inotifywait >/dev/null 2>&1 || { echo {{UNAVAILABLE}}; exit 0; };
# inotifywait runs in the background and the shell waits on stdin: when the SSH channel ends (clean close = end-of-input,
# or sshd noticing a dead connection) the shell kills it. A plain `exec inotifywait -m` would keep running forever.
inotifywait -m -q -e create -e close_write -e moved_to --format '%f' {{DIR}} & p=$!;
# a heartbeat line every 5 s (as on Windows) so a quiet folder is distinguishable from a dead link
( while :; do sleep 5; echo {{HB}}; done ) & h=$!;
trap 'kill $p $h 2>/dev/null' EXIT HUP TERM;
cat >/dev/null; kill $p $h 2>/dev/null; wait $p 2>/dev/null
