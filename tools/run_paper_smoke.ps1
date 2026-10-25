param(
    [string]$PaperHome = 'A:\Servers Minecraft\Paper 1.21.4',
    [string]$JavaHome = 'C:\Users\bicho\.jdks\temurin-21.0.12.1'
)
$ErrorActionPreference = 'Stop'
$workspaceRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$smokeRoot = [IO.Path]::GetFullPath((Join-Path $workspaceRoot 'build/paper-smoke'))
if (-not $smokeRoot.StartsWith($workspaceRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Smoke server must stay inside this workspace.'
}
New-Item -ItemType Directory -Path $smokeRoot -Force | Out-Null
foreach ($folder in @('cache', 'libraries')) {
    if (-not (Test-Path -LiteralPath (Join-Path $smokeRoot $folder))) {
        Copy-Item -LiteralPath (Join-Path $PaperHome $folder) -Destination $smokeRoot -Recurse
    }
}
Copy-Item -LiteralPath (Join-Path $PaperHome 'paper-1.21.4-232.jar') -Destination (Join-Path $smokeRoot 'paper.jar')
Copy-Item -LiteralPath (Join-Path $PaperHome 'eula.txt') -Destination (Join-Path $smokeRoot 'eula.txt')
$versionLine = Get-Content -LiteralPath (Join-Path $workspaceRoot 'build.gradle.kts') | Select-String '^version = "([^"]+)"$'
$pluginVersion = $versionLine.Matches[0].Groups[1].Value
Copy-Item -LiteralPath (Join-Path $workspaceRoot "build/libs/FlashLight-$pluginVersion-1.21.X.jar") -Destination (Join-Path $smokeRoot 'plugins/FlashLight.jar')
@'
server-ip=127.0.0.1
server-port=25586
online-mode=false
enforce-secure-profile=false
enable-rcon=false
allow-nether=false
level-type=minecraft:flat
generator-settings={"layers":[{"block":"minecraft:bedrock","height":1}],"biome":"minecraft:plains","structure_overrides":[]}
generate-structures=false
view-distance=3
simulation-distance=3
max-players=1
network-compression-threshold=-1
motd=Isolated FlashLight smoke test
'@ | Set-Content -LiteralPath (Join-Path $smokeRoot 'server.properties') -Encoding utf8NoBOM
Push-Location -LiteralPath $smokeRoot
try {
    Set-Content -LiteralPath 'result.txt' -Value 'RUNNING' -Encoding utf8NoBOM
    & (Join-Path $JavaHome 'bin/java.exe') '-Xms512M' '-Xmx1536M' '-Dterminal.jline=false' '-Dterminal.ansi=false' '-Djavax.net.ssl.trustStoreType=Windows-ROOT' '-Djavax.net.ssl.trustStore=NONE' '-jar' 'paper.jar' '--nogui' > 'smoke-console.log' 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Paper exited with code $LASTEXITCODE" }
    $result = Get-Content -LiteralPath (Join-Path $smokeRoot 'result.txt') -Raw
    if (-not $result.StartsWith('PASS:')) { throw $result }
    Write-Output $result
} finally { Pop-Location }
