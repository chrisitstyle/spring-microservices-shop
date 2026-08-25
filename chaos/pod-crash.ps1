param(
    [string]$Namespace = "spring-shop",
    [string]$Deployment = "product-service",
    [string]$GatewayUrl = "http://localhost:8085",
    [int]$RecoveryTimeoutSeconds = 180
)

$ErrorActionPreference = "Stop"

function Write-Step {
    param([string]$Message)

    Write-Host ""
    Write-Host "==> $Message"
}

function Get-HttpStatus {
    param([string]$Url)

    try {
        $response = Invoke-WebRequest `
            -Uri $Url `
            -Method Get `
            -TimeoutSec 5
            -UseBasicParsing

        return $response.StatusCode
    }
    catch {
        if ($_.Exception.Response) {
            return [int]$_.Exception.Response.StatusCode
        }

        return 0
    }
}

function Wait-ForHttp200 {
    param(
        [string]$Url,
        [int]$TimeoutSeconds
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)

    while ((Get-Date) -lt $deadline) {

        $status = Get-HttpStatus -Url $Url

        Write-Host "HTTP status: $status"

        if ($status -eq 200) {
            return
        }

        Start-Sleep -Seconds 2
    }

    throw "Service did not recover within $TimeoutSeconds seconds."
}


Write-Step "Checking baseline"

$baselineStatus = Get-HttpStatus `
    -Url "$GatewayUrl/products"

if ($baselineStatus -ne 200) {
    throw "Baseline failed. Expected HTTP 200, got $baselineStatus."
}

Write-Host "Baseline OK: HTTP 200"


Write-Step "Finding current Pod"

$oldPod = kubectl get pods `
    -n $Namespace `
    -l "app=$Deployment" `
    -o jsonpath="{.items[0].metadata.name}"

if (-not $oldPod) {
    throw "Could not find Pod for deployment '$Deployment'."
}

Write-Host "Current Pod: $oldPod"


Write-Step "Injecting failure"

kubectl delete pod `
    $oldPod `
    -n $Namespace

if ($LASTEXITCODE -ne 0) {
    throw "Failed to delete Pod '$oldPod'."
}


Write-Step "Waiting for Kubernetes recovery"

kubectl rollout status `
    "deployment/$Deployment" `
    -n $Namespace `
    --timeout="${RecoveryTimeoutSeconds}s"

if ($LASTEXITCODE -ne 0) {
    throw "Deployment did not recover."
}


Write-Step "Finding replacement Pod"

$newPod = kubectl get pods `
    -n $Namespace `
    -l "app=$Deployment" `
    -o jsonpath="{.items[0].metadata.name}"

if (-not $newPod) {
    throw "Replacement Pod was not found."
}

Write-Host "Old Pod: $oldPod"
Write-Host "New Pod: $newPod"

if ($newPod -eq $oldPod) {
    throw "Expected a replacement Pod, but Pod name did not change."
}


Write-Step "Checking Pod readiness"

$ready = kubectl get pod `
    $newPod `
    -n $Namespace `
    -o jsonpath="{.status.conditions[?(@.type=='Ready')].status}"

if ($ready -ne "True") {
    throw "Replacement Pod is not Ready."
}

Write-Host "Replacement Pod is Ready."


Write-Step "Checking application recovery"

Wait-ForHttp200 `
    -Url "$GatewayUrl/products" `
    -TimeoutSeconds $RecoveryTimeoutSeconds


Write-Host ""
Write-Host "========================================"
Write-Host "CHAOS TEST PASSED"
Write-Host "========================================"
Write-Host "Failure:       product-service Pod crash"
Write-Host "Old Pod:       $oldPod"
Write-Host "Replacement:   $newPod"
Write-Host "Gateway:       HTTP 200"
Write-Host "Self-healing:  confirmed"
Write-Host "========================================"