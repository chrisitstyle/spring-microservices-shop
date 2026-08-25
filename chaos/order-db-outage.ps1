param(
    [string]$Namespace = "spring-shop",
    [string]$StatefulSet = "order-db",
    [string]$OrderDeployment = "order-service",
    [string]$GatewayUrl = "http://localhost:8085",
    [int]$RecoveryTimeoutSeconds = 180
)

$ErrorActionPreference = "Stop"


function Write-Step {
    param([string]$Message)

    Write-Host ""
    Write-Host "==> $Message"
}


function Get-PodCount {
    param(
        [string]$Namespace,
        [string]$Label
    )

    $data =
        kubectl get pods `
            -n $Namespace `
            -l $Label `
            -o json |
        ConvertFrom-Json

    if ($LASTEXITCODE -ne 0) {
        throw "Could not list Pods for label '$Label'."
    }

    return @($data.items).Count
}


function Test-OrderDbAvailable {

    $previous =
        $ErrorActionPreference

    $ErrorActionPreference = "Continue"

    try {

        $null =
            kubectl exec `
                -n $Namespace `
                order-db-0 `
                -- `
                pg_isready `
                -U order_user `
                -d order_db `
                2>$null

        return $LASTEXITCODE -eq 0
    }
    finally {
        $ErrorActionPreference = $previous
    }
}


# ============================================================
# BASELINE
# ============================================================

Write-Step "Checking baseline"

$baseline =
    Invoke-WebRequest `
        -Uri "$GatewayUrl/products" `
        -Method Get `
        -TimeoutSec 5 `
        -UseBasicParsing

if ($baseline.StatusCode -ne 200) {
    throw "Baseline failed."
}

Write-Host "Gateway baseline OK."


Write-Step "Reading existing Order DB state"

$orderCountBefore =
    kubectl exec `
        -n $Namespace `
        order-db-0 `
        -- `
        psql `
        -U order_user `
        -d order_db `
        -tA `
        -c "SELECT COUNT(*) FROM orders;"

if ($LASTEXITCODE -ne 0) {
    throw "Could not read Order DB."
}

$orderCountBefore =
    [int](($orderCountBefore | Out-String).Trim())

Write-Host "Orders before outage: $orderCountBefore"


Write-Step "Reading original database replica count"

$statefulSetData =
    kubectl get statefulset `
        $StatefulSet `
        -n $Namespace `
        -o json |
    ConvertFrom-Json

$originalReplicas =
    [int]$statefulSetData.spec.replicas

if ($originalReplicas -lt 1) {
    throw "Order DB must be running before the test."
}


try {

    # ========================================================
    # FAILURE
    # ========================================================

    Write-Step "Injecting Order DB outage"

    kubectl scale statefulset `
        $StatefulSet `
        -n $Namespace `
        --replicas=0

    if ($LASTEXITCODE -ne 0) {
        throw "Could not stop Order DB."
    }


    Write-Step "Waiting until Order DB Pod disappears"

    $deadline =
        (Get-Date).AddSeconds(60)

    $podCount = -1

    while ((Get-Date) -lt $deadline) {

        $podCount =
            Get-PodCount `
                -Namespace $Namespace `
                -Label "app=order-db"

        Write-Host "Order DB Pods: $podCount"

        if ($podCount -eq 0) {
            break
        }

        Start-Sleep -Seconds 2
    }

    if ($podCount -ne 0) {
        throw "Order DB Pod did not terminate."
    }


    Write-Step "Database outage confirmed"

    Write-Host "Order DB is unavailable."

    # Give connection pools / health checks a moment
    # to observe the dependency failure.
    Start-Sleep -Seconds 8


    Write-Step "Inspecting Order Service during database outage"

    kubectl get pods `
        -n $Namespace `
        -l "app=$OrderDeployment"

    Write-Host ""
    Write-Host "Order Service remains managed by Kubernetes."
    Write-Host "Database dependency is unavailable."

}
finally {

    # ========================================================
    # RESTORE
    # ========================================================

    Write-Step "Restoring Order DB"

    kubectl scale statefulset `
        $StatefulSet `
        -n $Namespace `
        --replicas=$originalReplicas

    if ($LASTEXITCODE -ne 0) {
        Write-Host "WARNING: Failed to restore Order DB."
    }
    else {

        kubectl rollout status `
            "statefulset/$StatefulSet" `
            -n $Namespace `
            --timeout="${RecoveryTimeoutSeconds}s"

        if ($LASTEXITCODE -ne 0) {
            Write-Host "WARNING: Order DB rollout timed out."
        }
    }
}


# ============================================================
# DATABASE RECOVERY
# ============================================================

Write-Step "Waiting for PostgreSQL recovery"

$deadline =
    (Get-Date).AddSeconds(
        $RecoveryTimeoutSeconds
    )

$dbAvailable = $false

while ((Get-Date) -lt $deadline) {

    $dbAvailable =
        Test-OrderDbAvailable

    Write-Host "Order DB available: $dbAvailable"

    if ($dbAvailable) {
        break
    }

    Start-Sleep -Seconds 2
}

if (-not $dbAvailable) {
    throw "Order DB did not recover."
}


# ============================================================
# DATA DURABILITY
# ============================================================

Write-Step "Checking durable data"

$orderCountAfter =
    kubectl exec `
        -n $Namespace `
        order-db-0 `
        -- `
        psql `
        -U order_user `
        -d order_db `
        -tA `
        -c "SELECT COUNT(*) FROM orders;"

if ($LASTEXITCODE -ne 0) {
    throw "Could not query Order DB after recovery."
}

$orderCountAfter =
    [int](($orderCountAfter | Out-String).Trim())

Write-Host "Orders before outage: $orderCountBefore"
Write-Host "Orders after outage:  $orderCountAfter"

if ($orderCountAfter -ne $orderCountBefore) {
    throw "Order count changed across database outage."
}


# ============================================================
# ORDER SERVICE RECOVERY
# ============================================================

Write-Step "Waiting for Order Service readiness"

kubectl rollout status `
    "deployment/$OrderDeployment" `
    -n $Namespace `
    --timeout="${RecoveryTimeoutSeconds}s"

if ($LASTEXITCODE -ne 0) {
    throw "Order Service did not recover."
}


# ============================================================
# RESULT
# ============================================================

Write-Host ""
Write-Host "========================================"
Write-Host "CHAOS TEST PASSED"
Write-Host "========================================"
Write-Host "Failure:       Order DB outage"
Write-Host "DB replicas:   $originalReplicas -> 0 -> $originalReplicas"
Write-Host "Orders before: $orderCountBefore"
Write-Host "Orders after:  $orderCountAfter"
Write-Host "PVC data:      preserved"
Write-Host "Recovery:      confirmed"
Write-Host "========================================"