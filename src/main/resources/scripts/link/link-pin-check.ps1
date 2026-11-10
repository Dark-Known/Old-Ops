$tcp = New-Object Net.Sockets.TcpClient('{{HOST}}', {{PORT}})
$ssl = New-Object Net.Security.SslStream($tcp.GetStream(), $false, ({$true}))
$ssl.AuthenticateAsClient('{{HOST}}')
$c = New-Object Security.Cryptography.X509Certificates.X509Certificate2($ssl.RemoteCertificate)
[BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash($c.RawData)).Replace('-','') -eq '{{FINGERPRINT}}'
