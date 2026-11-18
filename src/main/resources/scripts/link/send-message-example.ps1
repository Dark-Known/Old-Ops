  Invoke-RestMethod -Method Post -Uri {{URL}}/socketLink/events `
    -Headers @{ Authorization = "Bearer <token>" } -ContentType application/json `
    -Body '{"type":"heartbeat","state":"connected"}'
