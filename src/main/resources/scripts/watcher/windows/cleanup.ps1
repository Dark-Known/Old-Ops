# Last words (why the listener ended), then release the watcher.
try{[Console]::Out.WriteLine('{{END}}'+$why);[Console]::Out.Flush()}catch{}
Get-EventSubscriber -EA 0|Unregister-Event -EA 0
if($w){$w.Dispose()}
