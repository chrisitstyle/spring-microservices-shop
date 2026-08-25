param(
    [string]$Namespace = "spring-shop",
    [string]$Deployment = "order-service",
    [int]$RecoveryTimeoutSeconds = 180
)

$ErrorActionPreference = "Stop"


function Write-Step {
    param([string]$Message)

    Write-Host ""
    Write-Host "==> $Message"
}


function Invoke-OrderDbScalar {
    param([string]$Sql)

    $result =
        kubectl exec `
            -n $Namespace `
            order-db-0 `
            -- `
            psql `
            -U order_user `
            -d order_db `
            -tA `
            -c $Sql

    if ($LASTEXITCODE -ne 0) {
        throw "Order DB query failed."
    }

    return ($result | Out-String).Trim()
}


function Invoke-OrderDbCommand {
    param([string]$Sql)

    kubectl exec `
        -n $Namespace `
        order-db-0 `
        -- `
        psql `
        -U order_user `
        -d order_db `
        -v ON_ERROR_STOP=1 `
        -c $Sql

    if ($LASTEXITCODE -ne 0) {
        throw "Order DB command failed."
    }
}


# ============================================================
# BASELINE
# ============================================================

Write-Step "Checking Order Service baseline"

kubectl rollout status `
    "deployment/$Deployment" `
    -n $Namespace `
    --timeout=60s

if ($LASTEXITCODE -ne 0) {
    throw "Order Service is not healthy before the test."
}


$oldPod =
    kubectl get pods `
        -n $Namespace `
        -l "app=$Deployment" `
        -o jsonpath="{.items[0].metadata.name}"

if (-not $oldPod) {
    throw "Could not find Order Service Pod."
}

Write-Host "Current Order Service Pod: $oldPod"


# ============================================================
# CREATE STALE SAGA
# ============================================================

Write-Step "Creating stale interrupted Saga"

$sagaId =
    [Guid]::NewGuid().ToString()


Invoke-OrderDbCommand `
    -Sql @"
INSERT INTO order_creation_sagas (
    id,
    user_id,
    status,
    created_at,
    updated_at
)
VALUES (
    '$sagaId',
    1,
    'STARTED',
    CURRENT_TIMESTAMP - INTERVAL '10 minutes',
    CURRENT_TIMESTAMP - INTERVAL '10 minutes'
);
"@


$initialStatus =
    Invoke-OrderDbScalar `
        -Sql @"
SELECT status
FROM order_creation_sagas
WHERE id = '$sagaId';
"@


if ($initialStatus -ne "STARTED") {
    throw "Expected initial Saga status STARTED, got '$initialStatus'."
}


Write-Host "Saga ID:     $sagaId"
Write-Host "Saga status: $initialStatus"
Write-Host "Saga is stale and recoverable."


try {

    # ========================================================
    # CRASH ORDER SERVICE
    # ========================================================

    Write-Step "Crashing Order Service Pod"

    kubectl delete pod `
        $oldPod `
        -n $Namespace

    if ($LASTEXITCODE -ne 0) {
        throw "Could not delete Order Service Pod."
    }


    # ========================================================
    # KUBERNETES RECOVERY
    # ========================================================

    Write-Step "Waiting for Kubernetes to replace Order Service"

    kubectl rollout status `
        "deployment/$Deployment" `
        -n $Namespace `
        --timeout="${RecoveryTimeoutSeconds}s"

    if ($LASTEXITCODE -ne 0) {
        throw "Order Service Deployment did not recover."
    }


    $newPod =
        kubectl get pods `
            -n $Namespace `
            -l "app=$Deployment" `
            -o jsonpath="{.items[0].metadata.name}"


    if (-not $newPod) {
        throw "Replacement Order Service Pod was not found."
    }


    Write-Host "Old Pod: $oldPod"
    Write-Host "New Pod: $newPod"


    if ($newPod -eq $oldPod) {
        throw "Expected a new Order Service Pod."
    }


    # ========================================================
    # WAIT FOR SAGA RECOVERY WORKER
    # ========================================================

    Write-Step "Waiting for Saga recovery worker"

    Write-Host "The worker has a startup delay, so this can take around 30-60 seconds."

    $deadline =
        (Get-Date).AddSeconds(
            $RecoveryTimeoutSeconds
        )

    $status = ""
    $fence = 0


    while ((Get-Date) -lt $deadline) {

        $state =
            Invoke-OrderDbScalar `
                -Sql @"
SELECT
    status
    || '|'
    || recovery_fence
FROM order_creation_sagas
WHERE id = '$sagaId';
"@


        if ($state) {

            $parts =
                $state.Split("|")

            $status =
                $parts[0]

            $fence =
                [long]$parts[1]
        }


        Write-Host "Saga status: $status | fence: $fence"


        if ($status -eq "COMPENSATED") {
            break
        }


        Start-Sleep -Seconds 2
    }


    if ($status -ne "COMPENSATED") {
        throw "Saga was not recovered to COMPENSATED state."
    }


    # ========================================================
    # FENCING ASSERTION
    # ========================================================

    Write-Step "Checking fencing"

    if ($fence -lt 1) {
        throw "Expected recovery_fence to be incremented."
    }

    Write-Host "Recovery fence: $fence"


    # ========================================================
    # LEASE ASSERTION
    # ========================================================

    Write-Step "Checking recovery lease cleanup"

    $leaseState =
        Invoke-OrderDbScalar `
            -Sql @"
SELECT
    CASE
        WHEN recovery_owner IS NULL
         AND recovery_lease_until IS NULL
            THEN 'RELEASED'
        ELSE 'HELD'
    END
FROM order_creation_sagas
WHERE id = '$sagaId';
"@


    Write-Host "Recovery lease: $leaseState"


    if ($leaseState -ne "RELEASED") {
        throw "Recovery claim was not released."
    }


    # ========================================================
    # FAILURE REASON
    # ========================================================

    Write-Step "Checking recovery reason"

    $failureReason =
        Invoke-OrderDbScalar `
            -Sql @"
SELECT COALESCE(failure_reason, '')
FROM order_creation_sagas
WHERE id = '$sagaId';
"@


    Write-Host "Failure reason: $failureReason"


    # ========================================================
    # LOG EVIDENCE
    # ========================================================

    Write-Step "Showing Saga recovery logs"

    $logs =
        kubectl logs `
            $newPod `
            -n $Namespace `
            --since=5m


    $logs |
        Select-String `
            -Pattern "Recovering interrupted order creation saga|Interrupted order creation saga compensated" |
        ForEach-Object {
            Write-Host $_
        }


    # ========================================================
    # RESULT
    # ========================================================

    Write-Host ""
    Write-Host "========================================"
    Write-Host "CHAOS TEST PASSED"
    Write-Host "========================================"
    Write-Host "Failure:       Order Service Pod crash"
    Write-Host "Saga ID:       $sagaId"
    Write-Host "Initial state: STARTED"
    Write-Host "Final state:   COMPENSATED"
    Write-Host "Fence:         $fence"
    Write-Host "Lease:         RELEASED"
    Write-Host "Old Pod:       $oldPod"
    Write-Host "New Pod:       $newPod"
    Write-Host "Recovery:      confirmed"
    Write-Host "========================================"

}
finally {

    # ========================================================
    # ENVIRONMENT SAFETY
    # ========================================================

    Write-Step "Ensuring Order Service is healthy"

    kubectl rollout status `
        "deployment/$Deployment" `
        -n $Namespace `
        --timeout="${RecoveryTimeoutSeconds}s"


    # ========================================================
    # CLEAN TEST FIXTURE
    # ========================================================

    Write-Step "Cleaning synthetic Saga"

    Invoke-OrderDbCommand `
        -Sql @"
DELETE FROM order_creation_sagas
WHERE id = '$sagaId';
"@

    Write-Host "Synthetic Saga removed."
}