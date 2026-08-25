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
            -TimeoutSec 5 `
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

function Get-PodCount {
    param(
        [string]$Namespace,
        [string]$Deployment
    )

    $podList = kubectl get pods `
        -n $Namespace `
        -l "app=$Deployment" `
        -o json |
        ConvertFrom-Json

    if ($LASTEXITCODE -ne 0) {
        throw "Could not list Pods for '$Deployment'."
    }

    return @($podList.items).Count
}

function Get-ReadyEndpointCount {
    param(
        [string]$Namespace,
        [string]$Service
    )

    $sliceList = kubectl get endpointslices.discovery.k8s.io `
        -n $Namespace `
        -l "kubernetes.io/service-name=$Service" `
        -o json |
        ConvertFrom-Json

    if ($LASTEXITCODE -ne 0) {
        throw "Could not read EndpointSlices for '$Service'."
    }

    $count = 0

    foreach ($slice in @($sliceList.items)) {

        foreach ($endpoint in @($slice.endpoints)) {

            if ($endpoint.conditions.ready -eq $true) {

                foreach ($address in @($endpoint.addresses)) {
                    $count++
                }
            }
        }
    }

    return $count
}


Write-Step "Checking baseline"

$baselineStatus = Get-HttpStatus `
    -Url "$GatewayUrl/products"

if ($baselineStatus -ne 200) {
    throw "Baseline failed. Expected HTTP 200, got $baselineStatus."
}

Write-Host "Baseline OK: HTTP 200"


Write-Step "Reading current replica count"

$deploymentData = kubectl get deployment `
    $Deployment `
    -n $Namespace `
    -o json |
    ConvertFrom-Json

if ($LASTEXITCODE -ne 0) {
    throw "Could not read Deployment '$Deployment'."
}

$originalReplicas = [int]$deploymentData.spec.replicas

if ($originalReplicas -lt 1) {
    throw "Expected at least one original replica."
}

Write-Host "Original replicas: $originalReplicas"

$outageStatus = $null


try {

    Write-Step "Injecting complete Product Service outage"

    kubectl scale deployment `
        $Deployment `
        -n $Namespace `
        --replicas=0

    if ($LASTEXITCODE -ne 0) {
        throw "Failed to scale '$Deployment' to zero."
    }


    Write-Step "Waiting until Product Service Pods disappear"

    $deadline = (Get-Date).AddSeconds(60)
    $podCount = -1

    while ((Get-Date) -lt $deadline) {

        $podCount = Get-PodCount `
            -Namespace $Namespace `
            -Deployment $Deployment

        Write-Host "Product Service Pods: $podCount"

        if ($podCount -eq 0) {
            break
        }

        Start-Sleep -Seconds 2
    }

    if ($podCount -ne 0) {
        throw "Product Service Pods did not terminate."
    }

    Write-Host "Product Service Pods are gone."


    Write-Step "Waiting until Service has no ready endpoints"

$deadline = (Get-Date).AddSeconds(60)
$readyEndpointCount = -1

while ((Get-Date) -lt $deadline) {

    $readyEndpointCount = Get-ReadyEndpointCount `
        -Namespace $Namespace `
        -Service $Deployment

    Write-Host "Product Service ready endpoints: $readyEndpointCount"

    if ($readyEndpointCount -eq 0) {
        break
    }

    Start-Sleep -Seconds 2
}

if ($readyEndpointCount -ne 0) {
    throw "Expected no ready Product Service endpoints."
}

Write-Host "Product Service has no ready endpoints."


    Write-Step "Calling Gateway during outage"

    $outageStatus = Get-HttpStatus `
        -Url "$GatewayUrl/products"

    Write-Host "HTTP status during outage: $outageStatus"

    if ($outageStatus -eq 200) {
        throw "Expected request to fail during Product Service outage, but received HTTP 200."
    }

    Write-Host "Failure was exposed correctly."

}
finally {

    Write-Step "Restoring Product Service"

    kubectl scale deployment `
        $Deployment `
        -n $Namespace `
        --replicas=$originalReplicas

    if ($LASTEXITCODE -ne 0) {

        Write-Host "WARNING: Failed to restore replica count."

    }
    else {

        kubectl rollout status `
            "deployment/$Deployment" `
            -n $Namespace `
            --timeout="${RecoveryTimeoutSeconds}s"

        if ($LASTEXITCODE -ne 0) {
            Write-Host "WARNING: Deployment recovery timed out."
        }
    }
}


Write-Step "Checking recovery"

Wait-ForHttp200 `
    -Url "$GatewayUrl/products" `
    -TimeoutSeconds $RecoveryTimeoutSeconds


Write-Host ""
Write-Host "========================================"
Write-Host "CHAOS TEST PASSED"
Write-Host "========================================"
Write-Host "Failure:       Product Service outage"
Write-Host "Replicas:      $originalReplicas -> 0 -> $originalReplicas"
Write-Host "Outage status: $outageStatus"
Write-Host "Recovery:      HTTP 200"
Write-Host "========================================"