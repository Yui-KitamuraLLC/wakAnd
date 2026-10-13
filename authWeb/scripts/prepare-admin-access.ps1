# Run on the administrator's Windows PC. No server shell or database client required.
# Creates no OAuth token/encryption key and never prints the admin access key.
[CmdletBinding()]
param([string]$OutputParent = $env:USERPROFILE)
$ErrorActionPreference = 'Stop'
$taskDirectory = Join-Path $OutputParent ('.wakand-admin-' + [Guid]::NewGuid().ToString('N'))
try {
    [void][IO.Directory]::CreateDirectory($taskDirectory)
    $taskAcl = Get-Acl -LiteralPath $taskDirectory
    $taskAcl.SetAccessRuleProtection($true, $false)
    $taskUser = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $taskRule = New-Object Security.AccessControl.FileSystemAccessRule($taskUser, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
    $taskAcl.SetAccessRule($taskRule)
    Set-Acl -LiteralPath $taskDirectory -AclObject $taskAcl
    $taskBytes = New-Object byte[] 32
    $taskRandom = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $taskRandom.GetBytes($taskBytes) } finally { $taskRandom.Dispose() }
    $taskKey = [Convert]::ToBase64String($taskBytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
    $taskSha = [Security.Cryptography.SHA256]::Create()
    try { $taskHash = ([BitConverter]::ToString($taskSha.ComputeHash($taskBytes))).Replace('-', '').ToLowerInvariant() }
    finally { $taskSha.Dispose() }
    $taskCreated = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $taskExpiry = $taskCreated + 1800
    $taskPhp = "<?php return ['admin_access' => ['key_sha256' => '$taskHash', 'created_at' => $taskCreated, 'expires_at' => $taskExpiry]];`n"
    $taskEncoding = New-Object Text.UTF8Encoding($false)
    [IO.File]::WriteAllText((Join-Path $taskDirectory 'admin-access.php'), $taskPhp, $taskEncoding)
    [IO.File]::WriteAllText((Join-Path $taskDirectory 'access-key.txt'), $taskKey, $taskEncoding)
    Write-Output "Private admin files created: $taskDirectory"
    Write-Output 'Upload admin-access.php to the private server directory only. Read access-key.txt locally and paste into the HTTPS admin form. It expires in 30 minutes; do not share the key.'
} catch {
    Write-Error 'Admin access preparation failed. Check local private directory permissions; no credentials were printed.'
}
