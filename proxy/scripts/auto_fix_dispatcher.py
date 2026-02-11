#!/usr/bin/env python3
"""
ClawPhones Auto-Fix Dispatcher — S2-5 P2
Cron job: every 30 minutes via launchd (15 min offset from crash_analyzer)

Flow:
1. Scan ~/Downloads/specs/auto-bug-*.md for specs with crash status=spec
2. Parse spec → extract platform, crash_id
3. Dispatch Codex to fix (codex exec --full-auto)
4. Verify build passes
5. Success → git commit (NO push) + PATCH status=fixed
6. Failure → PATCH status=wontfix + log for Opus review

Security:
- Only passes structured data (file, line, exception) to Codex — NOT raw stacktrace
- Directory sandboxing: only android/ and ios/ source files
- Diff validation before commit: reject changes to forbidden files
- Rate limit: max 20 fixes/day
- No auto-push — human review required
"""

import os
import re
import json
import time
import logging
import subprocess
import urllib.request
import urllib.error
from datetime import datetime, timezone
from pathlib import Path

# ── Config ──────────────────────────────────────────────
API_BASE = "http://3.142.69.6:8080"
ADMIN_KEY = "clawphones2026"

SPECS_DIR = Path.home() / "Downloads" / "specs"
LOG_DIR = Path.home() / "Downloads" / "scripts" / "logs"
STATE_FILE = Path.home() / "Downloads" / "scripts" / ".auto_fix_state.json"

ANDROID_DIR = Path.home() / ".openclaw" / "workspace" / "android" / "clawphones-android"
IOS_DIR = Path.home() / ".openclaw" / "workspace" / "ios"
WORKSPACE_ROOT = Path.home() / ".openclaw" / "workspace"

MAX_FIXES_PER_DAY = 20
MAX_RETRIES = 3
CODEX_TIMEOUT = 600  # 10 min per fix attempt

# Forbidden files — auto-fix must NOT modify these
FORBIDDEN_FILES = {
    "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
    "AndroidManifest.xml", "Info.plist", "Podfile", "Podfile.lock",
    "server.py", "requirements.txt", "package.json",
    "project.pbxproj",  # Xcode project file
}

ANDROID_BUILD_CMD = (
    "JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home "
    "./gradlew assembleDebug"
)
IOS_BUILD_CMD = (
    "xcodebuild -project ClawPhones.xcodeproj -scheme ClawPhones "
    "-sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 16' "
    "build 2>&1 | tail -50"
)

# ── Logging ─────────────────────────────────────────────
LOG_DIR.mkdir(parents=True, exist_ok=True)
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    handlers=[
        logging.FileHandler(LOG_DIR / "auto_fix_dispatcher.log"),
        logging.StreamHandler(),
    ],
)
log = logging.getLogger("auto_fix_dispatcher")

# ── State ───────────────────────────────────────────────
def load_state():
    if STATE_FILE.exists():
        try:
            with open(STATE_FILE) as f:
                return json.load(f)
        except Exception:
            pass
    return {"daily_count": 0, "daily_date": "", "dispatched": {}}


def save_state(state):
    STATE_FILE.parent.mkdir(parents=True, exist_ok=True)
    with open(STATE_FILE, "w") as f:
        json.dump(state, f, indent=2)


# ── API Helpers ─────────────────────────────────────────
def api_get(path, params=None):
    url = f"{API_BASE}{path}"
    if params:
        qs = "&".join(f"{k}={v}" for k, v in params.items())
        url += f"?{qs}"
    req = urllib.request.Request(url)
    req.add_header("x-admin-key", ADMIN_KEY)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read().decode())
    except Exception as e:
        log.error(f"API GET {path} error: {e}")
        return None


def api_patch(path, body):
    url = f"{API_BASE}{path}"
    data = json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method="PATCH")
    req.add_header("x-admin-key", ADMIN_KEY)
    req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read().decode())
    except Exception as e:
        log.error(f"API PATCH {path} error: {e}")
        return None


# ── Spec Parser ─────────────────────────────────────────
def parse_spec(spec_path: Path) -> dict:
    """
    Parse auto-bug spec file to extract structured data.
    Returns dict with: crash_id, platform, fatal, analysis, stacktrace
    """
    content = spec_path.read_text()

    crash_id_match = re.search(r"Auto-Generated Bug Spec\s*[-—]\s*(\S+)", content)
    crash_id = crash_id_match.group(1) if crash_id_match else spec_path.stem.replace("auto-bug-", "")

    platform_match = re.search(r"Platform:\s*(\w+)", content)
    platform = platform_match.group(1).lower() if platform_match else "unknown"

    fatal_match = re.search(r"Fatal:\s*(True|False|true|false)", content)
    is_fatal = fatal_match.group(1).lower() == "true" if fatal_match else True

    # Extract LLM analysis section (between ### LLM Analysis and ### Fix Requirements)
    analysis_match = re.search(
        r"### LLM Analysis\n(.*?)(?=\n### )", content, re.DOTALL
    )
    analysis = analysis_match.group(1).strip() if analysis_match else ""

    return {
        "crash_id": crash_id,
        "platform": platform,
        "fatal": is_fatal,
        "analysis": analysis,
        "spec_path": str(spec_path),
    }


# ── Build Verification ─────────────────────────────────
def verify_build(platform: str) -> bool:
    """Run build and return True if successful."""
    if platform == "android":
        cwd = str(ANDROID_DIR)
        cmd = ANDROID_BUILD_CMD
    elif platform == "ios":
        cwd = str(IOS_DIR)
        cmd = IOS_BUILD_CMD
    else:
        log.error(f"Unknown platform: {platform}")
        return False

    log.info(f"Building {platform}...")
    try:
        result = subprocess.run(
            cmd, shell=True, cwd=cwd,
            capture_output=True, text=True, timeout=300,
        )
        if result.returncode == 0:
            log.info(f"{platform} build PASSED")
            return True
        else:
            log.error(f"{platform} build FAILED:\n{result.stderr[-500:]}")
            return False
    except subprocess.TimeoutExpired:
        log.error(f"{platform} build timed out (300s)")
        return False
    except Exception as e:
        log.error(f"Build error: {e}")
        return False


# ── Diff Validation ─────────────────────────────────────
def validate_diff() -> bool:
    """
    Check git diff to ensure no forbidden files were modified.
    Returns True if diff is safe.
    """
    try:
        result = subprocess.run(
            ["git", "diff", "--name-only", "HEAD"],
            cwd=str(WORKSPACE_ROOT),
            capture_output=True, text=True, timeout=30,
        )
        changed_files = result.stdout.strip().split("\n")
        for f in changed_files:
            basename = os.path.basename(f)
            if basename in FORBIDDEN_FILES:
                log.error(f"FORBIDDEN file modified: {f} — rejecting auto-fix")
                return False
            # Also check path: only allow android/ and ios/ source dirs
            if not (f.startswith("android/") or f.startswith("ios/")):
                if f.strip():  # non-empty
                    log.error(f"File outside allowed dirs: {f} — rejecting auto-fix")
                    return False
        return True
    except Exception as e:
        log.error(f"Diff validation error: {e}")
        return False


# ── Git Commit (no push) ───────────────────────────────
def git_commit(crash_id: str, description: str) -> bool:
    """Commit auto-fix changes. NO push."""
    try:
        subprocess.run(
            ["git", "add", "-A"],
            cwd=str(WORKSPACE_ROOT),
            capture_output=True, timeout=30,
        )
        msg = f"auto-fix: {crash_id} — {description}\n\nAuto-generated fix. Review before pushing."
        result = subprocess.run(
            ["git", "commit", "-m", msg],
            cwd=str(WORKSPACE_ROOT),
            capture_output=True, text=True, timeout=30,
        )
        if result.returncode == 0:
            log.info(f"Committed: {msg.split(chr(10))[0]}")
            return True
        else:
            log.error(f"Git commit failed: {result.stderr[:300]}")
            return False
    except Exception as e:
        log.error(f"Git commit error: {e}")
        return False


# ── Git Reset (undo failed fix) ────────────────────────
def git_reset():
    """Reset working tree to HEAD (undo failed fix attempt)."""
    try:
        subprocess.run(
            ["git", "checkout", "."],
            cwd=str(WORKSPACE_ROOT),
            capture_output=True, timeout=30,
        )
        subprocess.run(
            ["git", "clean", "-fd"],
            cwd=str(WORKSPACE_ROOT),
            capture_output=True, timeout=30,
        )
        log.info("Git reset: working tree cleaned")
    except Exception as e:
        log.error(f"Git reset error: {e}")


# ── Codex Dispatch ──────────────────────────────────────
def dispatch_codex(spec_info: dict) -> bool:
    """
    Dispatch Codex to fix the bug.
    Only passes structured data — NOT raw stacktrace.
    Returns True if fix succeeded.
    """
    platform = spec_info["platform"]
    analysis = spec_info["analysis"]
    spec_path = spec_info["spec_path"]

    if platform == "android":
        work_dir = str(ANDROID_DIR)
        build_cmd = ANDROID_BUILD_CMD
    elif platform == "ios":
        work_dir = str(IOS_DIR)
        build_cmd = IOS_BUILD_CMD
    else:
        log.error(f"Unknown platform: {platform}")
        return False

    # Build Codex prompt with ONLY structured data from LLM analysis
    # Do NOT include raw stacktrace to prevent injection
    codex_prompt = (
        f"Fix a crash in the ClawPhones {platform} app.\n\n"
        f"LLM analysis of the crash:\n{analysis}\n\n"
        f"Requirements:\n"
        f"1. Only modify source files under the current directory\n"
        f"2. Do NOT modify build.gradle, AndroidManifest.xml, Info.plist, Podfile, or any config files\n"
        f"3. Do NOT add new network requests, permissions, or change API URLs\n"
        f"4. After fixing, verify build passes: {build_cmd}\n"
        f"5. If build fails, fix and retry (max 5 attempts)\n"
    )

    log.info(f"Dispatching Codex for {platform} fix...")

    try:
        result = subprocess.run(
            [
                "codex", "exec",
                "--skip-git-repo-check", "--full-auto",
                "-C", work_dir,
                codex_prompt,
            ],
            capture_output=True, text=True, timeout=CODEX_TIMEOUT,
        )
        if result.returncode == 0:
            log.info("Codex dispatch completed")
            return True
        else:
            log.error(f"Codex dispatch failed (rc={result.returncode}): {result.stderr[:300]}")
            return False
    except subprocess.TimeoutExpired:
        log.error(f"Codex dispatch timed out ({CODEX_TIMEOUT}s)")
        return False
    except FileNotFoundError:
        log.error("codex CLI not found — is it installed?")
        return False
    except Exception as e:
        log.error(f"Codex dispatch error: {e}")
        return False


# ── Main ────────────────────────────────────────────────
def main():
    log.info("=" * 60)
    log.info("Auto-Fix Dispatcher starting")

    state = load_state()

    # Reset daily counter
    today = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    if state.get("daily_date") != today:
        state["daily_count"] = 0
        state["daily_date"] = today

    if state["daily_count"] >= MAX_FIXES_PER_DAY:
        log.info(f"Daily fix limit reached ({MAX_FIXES_PER_DAY}), skipping")
        return

    # 1. Find auto-bug specs
    spec_files = sorted(SPECS_DIR.glob("auto-bug-*.md"))
    if not spec_files:
        log.info("No auto-bug specs found")
        return

    log.info(f"Found {len(spec_files)} auto-bug spec(s)")

    # 2. Filter: only process specs where crash status is "spec" (not already fixing/fixed)
    pending_specs = []
    for sf in spec_files:
        info = parse_spec(sf)
        crash_id = info["crash_id"]

        # Skip already dispatched
        if crash_id in state.get("dispatched", {}):
            continue

        # Skip non-fatal (only log, don't auto-fix)
        if not info["fatal"]:
            log.info(f"Skipping non-fatal crash {crash_id} (spec only, no auto-fix)")
            continue

        # Verify crash status is still "spec" on backend
        crash_data = api_get(f"/v1/crash-reports/{crash_id}")
        if not crash_data:
            continue
        status = crash_data.get("status", "")
        if status != "spec":
            log.info(f"Crash {crash_id} status={status}, skipping (expected 'spec')")
            continue

        pending_specs.append(info)

    if not pending_specs:
        log.info("No pending specs to fix")
        save_state(state)
        return

    log.info(f"{len(pending_specs)} spec(s) ready for auto-fix")

    # 3. Process each spec
    fixed = 0
    failed = 0

    for spec_info in pending_specs:
        if state["daily_count"] >= MAX_FIXES_PER_DAY:
            log.info("Daily limit reached mid-run")
            break

        crash_id = spec_info["crash_id"]
        log.info(f"Processing crash {crash_id} ({spec_info['platform']})")

        # Mark as fixing
        api_patch(f"/v1/crash-reports/{crash_id}", {"status": "fixing"})

        success = False
        for attempt in range(1, MAX_RETRIES + 1):
            log.info(f"Attempt {attempt}/{MAX_RETRIES}")

            # Dispatch Codex
            codex_ok = dispatch_codex(spec_info)
            if not codex_ok:
                log.warning(f"Codex dispatch failed on attempt {attempt}")
                git_reset()
                continue

            # Validate diff
            if not validate_diff():
                log.error("Diff validation failed — forbidden files modified")
                git_reset()
                continue

            # Verify build
            if verify_build(spec_info["platform"]):
                success = True
                break
            else:
                log.warning(f"Build failed on attempt {attempt}")
                git_reset()

        if success:
            # Commit (NO push)
            analysis_first_line = spec_info["analysis"].split("\n")[0][:80] if spec_info["analysis"] else "crash fix"
            committed = git_commit(crash_id, analysis_first_line)
            if committed:
                api_patch(f"/v1/crash-reports/{crash_id}", {"status": "fixed"})
                fixed += 1
                log.info(f"Crash {crash_id} FIXED and committed (not pushed)")
            else:
                api_patch(f"/v1/crash-reports/{crash_id}", {"status": "wontfix"})
                failed += 1
        else:
            api_patch(f"/v1/crash-reports/{crash_id}", {"status": "wontfix"})
            failed += 1
            log.error(f"Crash {crash_id} FAILED after {MAX_RETRIES} attempts")

        state["dispatched"][crash_id] = {
            "status": "fixed" if success else "wontfix",
            "timestamp": int(time.time()),
        }
        state["daily_count"] += 1

    # Clean old dispatched entries (>30 days)
    cutoff = int(time.time()) - 30 * 24 * 3600
    state["dispatched"] = {
        k: v for k, v in state.get("dispatched", {}).items()
        if v.get("timestamp", 0) > cutoff
    }

    save_state(state)

    # Summary
    log.info(f"Done: {fixed} fixed, {failed} failed, daily total: {state['daily_count']}")

    # Write claude-mem (best effort)
    if fixed > 0 or failed > 0:
        try:
            msg = (
                f"AUTO-FIX-DISPATCH: {fixed + failed} bugs processed, "
                f"{fixed} fixed, {failed} needs review ({today})"
            )
            subprocess.run(
                ["claude", "-p", f"Remember this: {msg}"],
                timeout=30, capture_output=True,
            )
        except Exception:
            pass


if __name__ == "__main__":
    main()
