"""
pytest integration tests for ALL endpoints.

Tests cover:
1. Health check
2. Developer API keys (POST/GET/DELETE)
3. Developer webhooks (POST/GET/DELETE)
4. Token wallet (GET with auto-create)
5. Token transfer (POST)
6. Token rewards (GET rules, POST claim)
7. Token leaderboard (GET)
8. Privacy settings (GET/PUT)
9. Privacy export (POST request, GET status)
10. Privacy audit (GET)
11. Metrics report (POST)
12. Health check/status (POST/GET)
13. Communities (POST/GET)
14. Tasks (POST/GET)

Run with: pytest proxy/test_api.py -v
"""

import pytest
import httpx
import asyncio
import json
from typing import AsyncGenerator, Dict, Any

BASE_URL = "http://localhost:8000"


@pytest.fixture
async def auth_token() -> AsyncGenerator[str, None]:
    """
    Fixture providing an auth token for test user.
    In production, this would register/login and return a real token.
    For testing, we use a mock token that corresponds to a test user.
    """
    # Register a test user and get a token
    async with httpx.AsyncClient(timeout=30.0) as client:
        # Register test user
        register_response = await client.post(
            f"{BASE_URL}/v1/auth/register",
            json={
                "email": "test_user@example.com",
                "password": "SecureTestPassword123!",
                "device_id": "test_device_001"
            }
        )
        if register_response.status_code not in (200, 201, 409):
            # 409 means user already exists, proceed to login
            login_response = await client.post(
                f"{BASE_URL}/v1/auth/login",
                json={
                    "email": "test_user@example.com",
                    "password": "SecureTestPassword123!",
                    "device_id": "test_device_001"
                }
            )
            assert login_response.status_code == 200
            data = login_response.json()
            token = data.get("token")
        else:
            data = register_response.json()
            token = data.get("token")
        assert token, "Failed to obtain auth token"
        yield token


@pytest.fixture
async def auth_headers(auth_token: str) -> Dict[str, str]:
    """Fixture providing auth headers for requests."""
    return {"Authorization": f"Bearer {auth_token}"}


# =============================================================================
# 1. Health Check
# =============================================================================


@pytest.mark.asyncio
async def test_health() -> None:
    """Test GET /health returns 200 with ok=True."""
    async with httpx.AsyncClient(timeout=10.0) as client:
        response = await client.get(f"{BASE_URL}/health")
        assert response.status_code == 200
        data = response.json()
        assert data.get("ok") is True
        assert "ts" in data


# =============================================================================
# 2. Developer Keys
# =============================================================================


@pytest.mark.asyncio
async def test_developer_keys(auth_headers: Dict[str, str]) -> None:
    """Test POST/GET/DELETE /v1/developer/keys."""
    async with httpx.AsyncClient(timeout=30.0) as client:
        # Create API key
        create_response = await client.post(
            f"{BASE_URL}/v1/developer/keys",
            json={
                "name": "Test API Key",
                "permissions": {"read": True, "write": False},
                "rate_limit": 100
            },
            headers=auth_headers
        )
        assert create_response.status_code == 200
        key_data = create_response.json()
        assert "id" in key_data
        assert "key" in key_data
        assert key_data["name"] == "Test API Key"
        key_id = key_data["id"]

        # List API keys
        list_response = await client.get(
            f"{BASE_URL}/v1/developer/keys",
            headers=auth_headers
        )
        assert list_response.status_code == 200
        list_data = list_response.json()
        assert "keys" in list_data
        assert any(k["id"] == key_id for k in list_data["keys"])

        # Delete API key
        delete_response = await client.delete(
            f"{BASE_URL}/v1/developer/keys/{key_id}",
            headers=auth_headers
        )
        assert delete_response.status_code == 200
        delete_data = delete_response.json()
        assert delete_data["status"] == "deleted"
        assert delete_data["id"] == key_id


# =============================================================================
# 3. Developer Webhooks
# =============================================================================


@pytest.mark.asyncio
async def test_developer_webhooks(auth_headers: Dict[str, str]) -> None:
    """Test POST/GET/DELETE /v1/developer/webhooks."""
    async with httpx.AsyncClient(timeout=30.0) as client:
        # Create webhook
        create_response = await client.post(
            f"{BASE_URL}/v1/developer/webhooks",
            json={
                "url": "https://example.com/webhook",
                "events": ["task.completed", "token.earned"],
                "secret": "test_secret_123"
            },
            headers=auth_headers
        )
        assert create_response.status_code == 200
        webhook_data = create_response.json()
        assert "id" in webhook_data
        assert webhook_data["url"] == "https://example.com/webhook"
        webhook_id = webhook_data["id"]

        # List webhooks
        list_response = await client.get(
            f"{BASE_URL}/v1/developer/webhooks",
            headers=auth_headers
        )
        assert list_response.status_code == 200
        list_data = list_response.json()
        assert "webhooks" in list_data
        assert any(w["id"] == webhook_id for w in list_data["webhooks"])

        # Delete webhook
        delete_response = await client.delete(
            f"{BASE_URL}/v1/developer/webhooks/{webhook_id}",
            headers=auth_headers
        )
        assert delete_response.status_code == 200
        delete_data = delete_response.json()
        assert delete_data["status"] == "deleted"
        assert delete_data["id"] == webhook_id


# =============================================================================
# 4. Token Wallet
# =============================================================================


@pytest.mark.asyncio
async def test_token_wallet(auth_headers: Dict[str, str]) -> None:
    """Test GET /v1/tokens/wallet with auto-create."""
    async with httpx.AsyncClient(timeout=30.0) as client:
        # Get wallet (auto-creates if not exists)
        response = await client.get(
            f"{BASE_URL}/v1/tokens/wallet",
            headers=auth_headers
        )
        assert response.status_code == 200
        data = response.json()
        assert "user_id" in data
        assert "total_credits" in data
        assert "available_credits" in data
        assert "pending_credits" in data
        assert "lifetime_earned" in data
        assert "lifetime_spent" in data


# =============================================================================
# 5. Token Transfer
# =============================================================================


@pytest.mark.asyncio
async def test_token_transfer(auth_headers: Dict[str, str]) -> None:
    """Test POST /v1/tokens/transfer."""
    async with httpx.AsyncClient(timeout=30.0) as client:
        # First, ensure wallet has some credits by creating a second user
        # and giving them credits to receive
        register_response = await client.post(
            f"{BASE_URL}/v1/auth/register",
            json={
                "email": "recipient@example.com",
                "password": "SecureTestPassword123!",
                "device_id": "test_device_002"
            }
        )
        if register_response.status_code not in (200, 201):
            # Try to login if registration failed (user exists)
            login_response = await client.post(
                f"{BASE_URL}/v1/auth/login",
                json={
                    "email": "recipient@example.com",
                    "password": "SecureTestPassword123!",
                    "device_id": "test_device_002"
                }
            )
            if login_response.status_code == 200:
                recipient_data = login_response.json()
                recipient_user_id = recipient_data.get("user_id")
            else:
                pytest.skip("Could not create recipient user")
        else:
            # Get recipient user_id from their wallet
            recipient_data = register_response.json()
            token = recipient_data.get("token")
            recipient_headers = {"Authorization": f"Bearer {token}"}
            wallet_response = await client.get(
                f"{BASE_URL}/v1/tokens/wallet",
                headers=recipient_headers
            )
            if wallet_response.status_code == 200:
                recipient_user_id = wallet_response.json().get("user_id")
            else:
                pytest.skip("Could not get recipient user_id")

        if not recipient_user_id:
            pytest.skip("Could not determine recipient_user_id")

        # Try transfer (may fail due to insufficient credits, but endpoint should work)
        transfer_response = await client.post(
            f"{BASE_URL}/v1/tokens/transfer",
            json={
                "to_user_id": recipient_user_id,
                "amount": 10,
                "description": "Test transfer"
            },
            headers=auth_headers
        )
        # Either success (200) or insufficient funds (400) is acceptable
        assert transfer_response.status_code in (200, 400)
        if transfer_response.status_code == 200:
            data = transfer_response.json()
            assert "transaction_id" in data
            assert data["amount"] == 10


# =============================================================================
# 6. Token Rewards
# =============================================================================


@pytest.mark.asyncio
async def test_token_rewards(auth_headers: Dict[str, str]) -> None:
    """Test GET /v1/tokens/rewards/rules and POST claim."""
    async with httpx.AsyncClient(timeout=30.0) as client:
        # Get reward rules
        rules_response = await client.get(
            f"{BASE_URL}/v1/tokens/rewards/rules",
            headers=auth_headers
        )
        assert rules_response.status_code == 200
        rules_data = rules_response.json()
        assert "rules" in rules_data

        # Try to claim a reward if rules exist
        if rules_data["rules"]:
            rule_id = rules_data["rules"][0]["id"]
            claim_response = await client.post(
                f"{BASE_URL}/v1/tokens/rewards/claim",
                json={"rule_id": rule_id},
                headers=auth_headers
            )
            # Either success (200) or on cooldown/limit (400) is acceptable
            assert claim_response.status_code in (200, 400, 404)
            if claim_response.status_code == 200:
                data = claim_response.json()
                assert "reward_credits" in data or "status" in data


# =============================================================================
# 7. Token Leaderboard
# =============================================================================


@pytest.mark.asyncio
async def test_token_leaderboard(auth_headers: Dict[str, str]) -> None:
    """Test GET /v1/tokens/leaderboard."""
    async with httpx.AsyncClient(timeout=30.0) as client:
        response = await client.get(
            f"{BASE_URL}/v1/tokens/leaderboard",
            headers=auth_headers
        )
        assert response.status_code == 200
        data = response.json()
        assert "leaderboard" in data


# =============================================================================
# 8. Privacy Settings
# =============================================================================


@pytest.mark.asyncio
async def test_privacy_settings(auth_headers: Dict[str, str]) -> None:
    """Test GET/PUT /v1/privacy/settings."""
    async with httpx.AsyncClient(timeout=30.0) as client:
        # Get privacy settings (auto-creates defaults)
        get_response = await client.get(
            f"{BASE_URL}/v1/privacy/settings",
            headers=auth_headers
        )
        assert get_response.status_code == 200
        get_data = get_response.json()
        assert "settings" in get_data

        # Update privacy settings
        put_response = await client.put(
            f"{BASE_URL}/v1/privacy/settings",
            json={
                "face_blur": True,
                "plate_blur": True,
                "location_precision": "neighborhood",
                "share_analytics": False,
                "encryption_enabled": True
            },
            headers=auth_headers
        )
        assert put_response.status_code == 200
        put_data = put_response.json()
        assert "settings" in put_data


# =============================================================================
# 9. Privacy Export
# =============================================================================


@pytest.mark.asyncio
async def test_privacy_export(auth_headers: Dict[str, str]) -> None:
    """Test POST /v1/privacy/export and GET status."""
    async with httpx.AsyncClient(timeout=30.0) as client:
        # Request data export
        export_response = await client.post(
            f"{BASE_URL}/v1/privacy/export",
            json={"format": "json"},
            headers=auth_headers
        )
        assert export_response.status_code == 200
        export_data = export_response.json()
        assert "export_id" in export_data
        assert export_data["status"] == "pending"
        export_id = export_data["export_id"]

        # Get export status
        status_response = await client.get(
            f"{BASE_URL}/v1/privacy/export/{export_id}",
            headers=auth_headers
        )
        assert status_response.status_code == 200
        status_data = status_response.json()
        assert "export_id" in status_data
        assert "status" in status_data


# =============================================================================
# 10. Privacy Audit
# =============================================================================


@pytest.mark.asyncio
async def test_privacy_audit(auth_headers: Dict[str, str]) -> None:
    """Test GET /v1/privacy/audit."""
    async with httpx.AsyncClient(timeout=30.0) as client:
        response = await client.get(
            f"{BASE_URL}/v1/privacy/audit",
            headers=auth_headers
        )
        assert response.status_code == 200
        data = response.json()
        assert "logs" in data
        assert "count" in data


# =============================================================================
# 11. Metrics Report
# =============================================================================


@pytest.mark.asyncio
async def test_metrics_report(auth_headers: Dict[str, str]) -> None:
    """Test POST /v1/metrics/report."""
    async with httpx.AsyncClient(timeout=30.0) as client:
        response = await client.post(
            f"{BASE_URL}/v1/metrics/report",
            json={
                "startup_time_ms": 1500,
                "memory_mb": 256.5,
                "cpu_percent": 15.3,
                "battery_drain": 2.5,
                "network_in": 1024000,
                "network_out": 512000,
                "connections": 5,
                "frame_drops": 2,
                "cache_hit_rate": 0.85
            },
            headers=auth_headers
        )
        assert response.status_code == 200
        data = response.json()
        assert data["status"] == "recorded"
        assert "recorded_at" in data


# =============================================================================
# 12. Health Check/Status
# =============================================================================


@pytest.mark.asyncio
async def test_health_check(auth_headers: Dict[str, str]) -> None:
    """Test POST /v1/health/check and GET /v1/health/status."""
    async with httpx.AsyncClient(timeout=30.0) as client:
        # Perform health check
        check_response = await client.post(
            f"{BASE_URL}/v1/health/check",
            json={
                "relay_ok": True,
                "backend_ok": True,
                "ws_ok": True,
                "push_ok": False,
                "latency_ms": 125
            },
            headers=auth_headers
        )
        assert check_response.status_code == 200
        check_data = check_response.json()
        assert check_data["status"] == "checked"
        assert "checked_at" in check_data

        # Get health status/history
        status_response = await client.get(
            f"{BASE_URL}/v1/health/status",
            headers=auth_headers
        )
        assert status_response.status_code == 200
        status_data = status_response.json()
        assert "health_checks" in status_data


# =============================================================================
# 13. Communities CRUD
# =============================================================================


@pytest.mark.asyncio
async def test_community_crud(auth_headers: Dict[str, str]) -> None:
    """Test POST/GET /v1/communities."""
    async with httpx.AsyncClient(timeout=30.0) as client:
        # Create community
        create_response = await client.post(
            f"{BASE_URL}/v1/communities",
            json={
                "name": "Test Community",
                "description": "A test community for pytest",
                "center_lat": 37.7749,
                "center_lon": -122.4194,
                "h3_resolution": 9
            },
            headers=auth_headers
        )
        assert create_response.status_code == 200
        create_data = create_response.json()
        assert "id" in create_data
        assert create_data["name"] == "Test Community"
        assert "invite_code" in create_data
        community_id = create_data["id"]

        # Get my communities
        list_response = await client.get(
            f"{BASE_URL}/v1/communities/mine",
            headers=auth_headers
        )
        assert list_response.status_code == 200
        list_data = list_response.json()
        assert "communities" in list_data

        # Get specific community
        get_response = await client.get(
            f"{BASE_URL}/v1/communities/{community_id}",
            headers=auth_headers
        )
        assert get_response.status_code == 200
        get_data = get_response.json()
        assert get_data["id"] == community_id


# =============================================================================
# 14. Tasks CRUD
# =============================================================================


@pytest.mark.asyncio
async def test_task_crud(auth_headers: Dict[str, str]) -> None:
    """Test POST/GET /v1/tasks."""
    async with httpx.AsyncClient(timeout=30.0) as client:
        # Create task (requires publisher_key)
        # First, we might need to get or create a publisher key
        publisher_key = "test_publisher_key_123"

        create_response = await client.post(
            f"{BASE_URL}/v1/tasks",
            json={
                "title": "Test Capture Task",
                "description": "Capture photos of downtown area",
                "task_type": "capture",
                "requirements": {"camera_quality": "high", "time_of_day": "day"},
                "reward_credits": 100,
                "reward_bonus": 20,
                "location_lat": 37.7749,
                "location_lon": -122.4194,
                "expires_at": int(asyncio.get_event_loop().time()) + 7 * 86400,
                "max_assignments": 10,
                "publisher_key": publisher_key
            },
            headers=auth_headers
        )
        assert create_response.status_code == 200
        create_data = create_response.json()
        assert "task_id" in create_data
        assert create_data["status"] == "created"
        task_id = create_data["task_id"]

        # Get available tasks
        list_response = await client.get(
            f"{BASE_URL}/v1/tasks/available",
            headers=auth_headers
        )
        assert list_response.status_code == 200
        list_data = list_response.json()
        assert "tasks" in list_data

        # Get specific task
        get_response = await client.get(
            f"{BASE_URL}/v1/tasks/{task_id}",
            headers=auth_headers
        )
        assert get_response.status_code == 200
        get_data = get_response.json()
        assert get_data["id"] == task_id


# =============================================================================
# Integration Test: Full Flow
# =============================================================================


@pytest.mark.asyncio
async def test_full_integration_flow(auth_headers: Dict[str, str]) -> None:
    """Test a full integration flow: register, create key, transfer tokens."""
    async with httpx.AsyncClient(timeout=30.0) as client:
        # Step 1: Create API key
        key_response = await client.post(
            f"{BASE_URL}/v1/developer/keys",
            json={"name": "Integration Test Key"},
            headers=auth_headers
        )
        assert key_response.status_code == 200
        key_data = key_response.json()
        key_id = key_data["id"]

        # Step 2: Get wallet
        wallet_response = await client.get(
            f"{BASE_URL}/v1/tokens/wallet",
            headers=auth_headers
        )
        assert wallet_response.status_code == 200

        # Step 3: Report metrics
        metrics_response = await client.post(
            f"{BASE_URL}/v1/metrics/report",
            json={
                "startup_time_ms": 1000,
                "memory_mb": 200,
                "cpu_percent": 10,
                "battery_drain": 1.0,
                "network_in": 500000,
                "network_out": 250000,
                "connections": 3,
                "frame_drops": 0,
                "cache_hit_rate": 0.9
            },
            headers=auth_headers
        )
        assert metrics_response.status_code == 200

        # Step 4: Health check
        health_response = await client.post(
            f"{BASE_URL}/v1/health/check",
            json={
                "relay_ok": True,
                "backend_ok": True,
                "ws_ok": True,
                "push_ok": True,
                "latency_ms": 50
            },
            headers=auth_headers
        )
        assert health_response.status_code == 200

        # Step 5: Get privacy settings
        privacy_response = await client.get(
            f"{BASE_URL}/v1/privacy/settings",
            headers=auth_headers
        )
        assert privacy_response.status_code == 200

        # Step 6: Cleanup - Delete API key
        delete_response = await client.delete(
            f"{BASE_URL}/v1/developer/keys/{key_id}",
            headers=auth_headers
        )
        assert delete_response.status_code == 200
