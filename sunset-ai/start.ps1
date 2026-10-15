param(
    [ValidateRange(1, 65535)]
    [int]$Port = 8080
)

$ErrorActionPreference = 'Stop'

# Existing IDEs and terminals retain the environment from when they were opened.
# Load the current Windows setting without placing the key in arguments or logs.
$keySource = 'current process'
foreach ($scope in @('User', 'Machine')) {
    $configuredKey = [Environment]::GetEnvironmentVariable('DASHSCOPE_API_KEY', $scope)
    if (-not [string]::IsNullOrWhiteSpace($configuredKey)) {
        [Environment]::SetEnvironmentVariable('DASHSCOPE_API_KEY', $configuredKey, 'Process')
        $keySource = "Windows $scope environment"
        break
    }
}

if ([string]::IsNullOrWhiteSpace($env:DASHSCOPE_API_KEY)) {
    throw 'DASHSCOPE_API_KEY is missing. Configure it in Windows environment variables before starting.'
}

Write-Host "Loaded DASHSCOPE_API_KEY from $keySource (value hidden)."

Push-Location -LiteralPath $PSScriptRoot
try {
    $maven = Get-Command mvn -ErrorAction SilentlyContinue
    if ($maven) {
        & $maven.Source '-B' 'spring-boot:run' "-Dspring-boot.run.arguments=--server.port=$Port"
    } else {
        & (Join-Path $PSScriptRoot 'mvnw.cmd') '-B' 'spring-boot:run' "-Dspring-boot.run.arguments=--server.port=$Port"
    }
    $backendExitCode = $LASTEXITCODE
} finally {
    Pop-Location
}

exit $backendExitCode
