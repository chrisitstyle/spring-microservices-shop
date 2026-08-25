param(
    [string]$Namespace = "spring-shop",
    [string]$GatewayUrl = "http://localhost:8085",
    [string]$KeycloakUrl = "http://localhost:8080",
    [string]$KafkaStatefulSet = "kafka",
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


function Get-UserToken {
    param(
        [string]$Username,
        [string]$Password
    )

    $response = Invoke-RestMethod `
        -Method Post `
        -Uri "$KeycloakUrl/realms/spring-shop/protocol/openid-connect/token" `
        -ContentType "application/x-www-form-urlencoded" `
        -Body @{
            grant_type = "password"
            client_id  = "e2e-test-client"
            username   = $Username
            password   = $Password
        }

    if (-not $response.access_token) {
        throw "Could not obtain access token for '$Username'."
    }

    return $response.access_token
}


function Invoke-OrderDbScalar {
    param([string]$Sql)

    $result = kubectl exec `
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


function Invoke-NotificationDbScalar {
    param([string]$Sql)

    $result = kubectl exec `
        -n $Namespace `
        notification-db-0 `
        -- `
        psql `
        -U notification_user `
        -d notification_db `
        -tA `
        -c $Sql

    if ($LASTEXITCODE -ne 0) {
        throw "Notification DB query failed."
    }

    return ($result | Out-String).Trim()
}


function Get-KafkaPodCount {

    $podList = kubectl get pods `
        -n $Namespace `
        -l "app=kafka" `
        -o json |
        ConvertFrom-Json

    if ($LASTEXITCODE -ne 0) {
        throw "Could not list Kafka Pods."
    }

    return @($podList.items).Count
}


function Test-KafkaAvailable {

    $previousErrorActionPreference =
        $ErrorActionPreference

    $ErrorActionPreference = "Continue"

    try {
        $null = kubectl exec `
            -n $Namespace `
            kafka-0 `
            -- `
            /opt/kafka/bin/kafka-topics.sh `
            --bootstrap-server kafka:19092 `
            --list `
            2>$null

        return $LASTEXITCODE -eq 0
    }
    finally {
        $ErrorActionPreference =
            $previousErrorActionPreference
    }
}


# ============================================================
# BASELINE
# ============================================================

Write-Step "Checking baseline"

$baselineStatus =
    Get-HttpStatus `
        -Url "$GatewayUrl/products"

if ($baselineStatus -ne 200) {
    throw "Baseline failed. Expected HTTP 200, got $baselineStatus."
}

Write-Host "Baseline OK: HTTP 200"


# ============================================================
# AUTH
# ============================================================

Write-Step "Getting E2E access tokens"

$adminToken =
    Get-UserToken `
        -Username "e2e-admin" `
        -Password "e2e-password"

$customerToken =
    Get-UserToken `
        -Username "e2e-customer" `
        -Password "e2e-password"

Write-Host "Access tokens obtained."


# ============================================================
# PRODUCT
# ============================================================

Write-Step "Creating product before Kafka outage"

$productSuffix =
    [Guid]::NewGuid().ToString("N").Substring(0, 8)

$productBody = @{
    name =
        "Chaos Kafka Product $productSuffix"

    description =
        "Product created by Kafka chaos test"

    price = 19.99

    stockQuantity = 10
} |
ConvertTo-Json


$productResponse =
    Invoke-WebRequest `
        -Method Post `
        -Uri "$GatewayUrl/products" `
        -Headers @{
            Authorization =
                "Bearer $adminToken"
        } `
        -ContentType "application/json" `
        -Body $productBody `
        -UseBasicParsing


if ($productResponse.StatusCode -ne 201) {
    throw "Product creation failed. HTTP $($productResponse.StatusCode)."
}


$product =
    $productResponse.Content |
    ConvertFrom-Json


$productId =
    [long]$product.id


Write-Host "Created product ID: $productId"


# ============================================================
# KAFKA ORIGINAL STATE
# ============================================================

Write-Step "Reading Kafka replica count"

$kafkaData =
    kubectl get statefulset `
        $KafkaStatefulSet `
        -n $Namespace `
        -o json |
        ConvertFrom-Json


if ($LASTEXITCODE -ne 0) {
    throw "Could not read Kafka StatefulSet."
}


$originalKafkaReplicas =
    [int]$kafkaData.spec.replicas


if ($originalKafkaReplicas -lt 1) {
    throw "Kafka must be running before the test."
}


Write-Host "Kafka replicas: $originalKafkaReplicas"


$orderId = $null
$outboxDuringOutage = $null


try {

    # ========================================================
    # INJECT FAILURE
    # ========================================================

    Write-Step "Injecting Kafka outage"

    kubectl scale statefulset `
        $KafkaStatefulSet `
        -n $Namespace `
        --replicas=0

    if ($LASTEXITCODE -ne 0) {
        throw "Failed to scale Kafka to zero."
    }


    Write-Step "Waiting until Kafka Pod disappears"

    $deadline =
        (Get-Date).AddSeconds(60)

    $kafkaPodCount = -1

    while ((Get-Date) -lt $deadline) {

        $kafkaPodCount =
            Get-KafkaPodCount

        Write-Host "Kafka Pods: $kafkaPodCount"

        if ($kafkaPodCount -eq 0) {
            break
        }

        Start-Sleep -Seconds 2
    }


    if ($kafkaPodCount -ne 0) {
        throw "Kafka Pod did not terminate."
    }


    Write-Host "Kafka is unavailable."


    # ========================================================
    # CREATE ORDER WHILE KAFKA IS DOWN
    # ========================================================

    Write-Step "Creating order while Kafka is unavailable"

    $orderBody = @{
        items = @(
            @{
                productId = $productId
                quantity  = 2
            }
        )
    } |
    ConvertTo-Json -Depth 5


    $orderResponse =
        Invoke-WebRequest `
            -Method Post `
            -Uri "$GatewayUrl/orders" `
            -Headers @{
                Authorization =
                    "Bearer $customerToken"
            } `
            -ContentType "application/json" `
            -Body $orderBody `
            -UseBasicParsing


    if ($orderResponse.StatusCode -ne 201) {

        throw @"
Order creation failed while Kafka was unavailable.
HTTP status: $($orderResponse.StatusCode)
Body: $($orderResponse.Content)
"@
    }


    $order =
        $orderResponse.Content |
        ConvertFrom-Json


    $orderId =
        [long]$order.id


    Write-Host "Created order ID: $orderId"


    # ========================================================
    # SAGA MUST COMPLETE WITHOUT KAFKA
    # ========================================================

    Write-Step "Waiting for Saga completion"

    $deadline =
        (Get-Date).AddSeconds(30)

    $sagaStatus = ""

    while ((Get-Date) -lt $deadline) {

        $sagaStatus =
            Invoke-OrderDbScalar `
                -Sql @"
SELECT status
FROM order_creation_sagas
WHERE order_id = $orderId
ORDER BY created_at DESC
LIMIT 1;
"@

        Write-Host "Saga status: $sagaStatus"

        if ($sagaStatus -eq "COMPLETED") {
            break
        }

        Start-Sleep -Seconds 1
    }


    if ($sagaStatus -ne "COMPLETED") {
        throw "Saga did not reach COMPLETED state."
    }


    # ========================================================
    # OUTBOX MUST EXIST BUT REMAIN UNPUBLISHED
    # ========================================================

    Write-Step "Checking durable outbox event"

    $deadline =
        (Get-Date).AddSeconds(30)

    $outboxDuringOutage = ""

    while ((Get-Date) -lt $deadline) {

        $outboxDuringOutage =
            Invoke-OrderDbScalar `
                -Sql @"
SELECT
    CASE
        WHEN published_at IS NULL
            THEN 'UNPUBLISHED'
        ELSE 'PUBLISHED'
    END
FROM outbox_events
WHERE aggregate_id = $orderId
  AND event_type = 'OrderCreated'
ORDER BY created_at DESC
LIMIT 1;
"@

        Write-Host "Outbox state: $outboxDuringOutage"

        if ($outboxDuringOutage) {
            break
        }

        Start-Sleep -Seconds 1
    }


    if (-not $outboxDuringOutage) {
        throw "OrderCreated outbox event was not created."
    }


    if ($outboxDuringOutage -ne "UNPUBLISHED") {
        throw "Outbox event was unexpectedly published while Kafka was down."
    }


    # Give the publisher time to attempt delivery.
    Start-Sleep -Seconds 5


    $outboxAfterPublishAttempt =
        Invoke-OrderDbScalar `
            -Sql @"
SELECT
    CASE
        WHEN published_at IS NULL
            THEN 'UNPUBLISHED'
        ELSE 'PUBLISHED'
    END
FROM outbox_events
WHERE aggregate_id = $orderId
  AND event_type = 'OrderCreated'
ORDER BY created_at DESC
LIMIT 1;
"@


    Write-Host "Outbox after failed publish attempts: $outboxAfterPublishAttempt"


    if ($outboxAfterPublishAttempt -ne "UNPUBLISHED") {
        throw "Expected outbox event to remain unpublished."
    }


    # ========================================================
    # NOTIFICATION MUST NOT EXIST YET
    # ========================================================

    Write-Step "Checking Notification Service during outage"

    $notificationCount =
        Invoke-NotificationDbScalar `
            -Sql @"
SELECT COUNT(*)
FROM processed_order_events
WHERE order_id = $orderId;
"@


    Write-Host "Processed notification events: $notificationCount"


    if ([int]$notificationCount -ne 0) {
        throw "Notification was processed even though Kafka was unavailable."
    }


    Write-Host ""
    Write-Host "Durability during outage confirmed:"
    Write-Host "Order:        persisted"
    Write-Host "Saga:         COMPLETED"
    Write-Host "Outbox:       UNPUBLISHED"
    Write-Host "Notification: absent"

}
finally {

    # ========================================================
    # ALWAYS RESTORE KAFKA
    # ========================================================

    Write-Step "Restoring Kafka"

    kubectl scale statefulset `
        $KafkaStatefulSet `
        -n $Namespace `
        --replicas=$originalKafkaReplicas


    if ($LASTEXITCODE -ne 0) {

        Write-Host "WARNING: Failed to restore Kafka."

    }
    else {

        kubectl rollout status `
            "statefulset/$KafkaStatefulSet" `
            -n $Namespace `
            --timeout="${RecoveryTimeoutSeconds}s"

        if ($LASTEXITCODE -ne 0) {
            Write-Host "WARNING: Kafka StatefulSet recovery timed out."
        }
    }
}


# ============================================================
# WAIT FOR KAFKA TO ACTUALLY ACCEPT REQUESTS
# ============================================================

Write-Step "Waiting for Kafka broker availability"

$deadline =
    (Get-Date).AddSeconds(
        $RecoveryTimeoutSeconds
    )

$kafkaAvailable = $false


while ((Get-Date) -lt $deadline) {

    $kafkaAvailable =
        Test-KafkaAvailable

    Write-Host "Kafka available: $kafkaAvailable"

    if ($kafkaAvailable) {
        break
    }

    Start-Sleep -Seconds 3
}


if (-not $kafkaAvailable) {
    throw "Kafka did not become available."
}


# ============================================================
# OUTBOX RECOVERY
# ============================================================

Write-Step "Waiting for outbox recovery"

$deadline =
    (Get-Date).AddSeconds(
        $RecoveryTimeoutSeconds
    )

$outboxAfterRecovery = ""


while ((Get-Date) -lt $deadline) {

    $outboxAfterRecovery =
        Invoke-OrderDbScalar `
            -Sql @"
SELECT
    CASE
        WHEN published_at IS NULL
            THEN 'UNPUBLISHED'
        ELSE 'PUBLISHED'
    END
FROM outbox_events
WHERE aggregate_id = $orderId
  AND event_type = 'OrderCreated'
ORDER BY created_at DESC
LIMIT 1;
"@


    Write-Host "Outbox state: $outboxAfterRecovery"


    if ($outboxAfterRecovery -eq "PUBLISHED") {
        break
    }


    Start-Sleep -Seconds 2
}


if ($outboxAfterRecovery -ne "PUBLISHED") {
    throw "Outbox event was not published after Kafka recovery."
}


# ============================================================
# NOTIFICATION RECOVERY
# ============================================================

Write-Step "Waiting for Notification Service"

$deadline =
    (Get-Date).AddSeconds(
        $RecoveryTimeoutSeconds
    )

$notificationCount = 0


while ((Get-Date) -lt $deadline) {

    $notificationCount =
        [int](
            Invoke-NotificationDbScalar `
                -Sql @"
SELECT COUNT(*)
FROM processed_order_events
WHERE order_id = $orderId;
"@
        )


    Write-Host "Processed notification events: $notificationCount"


    if ($notificationCount -gt 0) {
        break
    }


    Start-Sleep -Seconds 2
}


if ($notificationCount -ne 1) {
    throw "Expected exactly one processed OrderCreated event."
}


# ============================================================
# RESULT
# ============================================================

Write-Host ""
Write-Host "========================================"
Write-Host "CHAOS TEST PASSED"
Write-Host "========================================"
Write-Host "Failure:       Kafka outage"
Write-Host "Order ID:      $orderId"
Write-Host "Saga:          COMPLETED"
Write-Host "During outage: UNPUBLISHED"
Write-Host "After recovery:PUBLISHED"
Write-Host "Notification:  processed exactly once"
Write-Host "Outbox:        recovery confirmed"
Write-Host "========================================"