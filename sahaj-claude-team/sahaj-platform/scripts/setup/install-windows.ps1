# Sahaj developer setup for Windows 10/11. Run in PowerShell as Administrator:
#   Set-ExecutionPolicy -Scope Process Bypass; .\scripts\setup\install-windows.ps1
$ErrorActionPreference = 'Stop'
function Install($id) { Write-Host "Installing $id ..."; winget install --id $id -e --accept-source-agreements --accept-package-agreements --silent }

Install 'Git.Git'                    # also gives Git Bash, which Claude Code uses on Windows
Install 'Microsoft.OpenJDK.17'       # Java 17 LTS
Install 'OpenJS.NodeJS.LTS'          # Node LTS + npm (Angular, hooks, scripts)
Install 'PostgreSQL.PostgreSQL.16'   # PostgreSQL 16 server, psql, pgAdmin. Default superuser password: postgres

# Maven 3.9.9 (not in winget): download to %USERPROFILE%\tools
$tools = Join-Path $env:USERPROFILE 'tools'; New-Item -ItemType Directory -Force $tools | Out-Null
$mvnZip = Join-Path $tools 'maven.zip'
Invoke-WebRequest 'https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.zip' -OutFile $mvnZip
Expand-Archive $mvnZip -DestinationPath $tools -Force; Remove-Item $mvnZip

# PATH and JAVA_HOME for the current user
$jdk = Get-ChildItem 'C:\Program Files\Microsoft' -Directory -Filter 'jdk-17*' | Select-Object -First 1
if ($jdk) { [Environment]::SetEnvironmentVariable('JAVA_HOME', $jdk.FullName, 'User') }
$add = @("$tools\apache-maven-3.9.9\bin", 'C:\Program Files\PostgreSQL\16\bin')
$path = [Environment]::GetEnvironmentVariable('Path', 'User')
foreach ($p in $add) { if ($path -notlike "*$p*") { $path = "$path;$p" } }
[Environment]::SetEnvironmentVariable('Path', $path, 'User')

Write-Host "`nDone. Close this window, open a NEW terminal, then run: node scripts/setup/check-env.mjs"
Write-Host "Change the default postgres password: psql -U postgres -c ""ALTER USER postgres PASSWORD 'your-strong-password';"""
