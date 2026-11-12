"""Shared by configure.py and user.py: creds.env and calls to the test server's API."""
import json
import os
import sys
import time
import urllib.error
import urllib.request

ROOT = os.path.expanduser(os.environ.get("EPHYRA_TEST_SERVER_DIR", "~/ephyra-test-server"))
CREDS_PATH = os.path.join(ROOT, "creds.env")
# Its own DeviceId: logging in with a DeviceId an emulator uses would sign that emulator out.
AUTH = 'MediaBrowser Client="ephyra-test-setup", Device="script", DeviceId="ephyra-test-setup", Version="1"'

token = None
attempts = 5  # per call, two seconds apart; configure.py raises it while a new server starts


def read_creds():
    if not os.path.exists(CREDS_PATH):
        sys.exit(f"no {CREDS_PATH}: run tools/test-server/setup.sh first")
    with open(CREDS_PATH) as f:
        return dict(
            line.split("#")[0].strip().split("=", 1)
            for line in f
            if "=" in line and not line.startswith("#")
        )


CREDS = read_creds()
URL = CREDS["JF_URL_HOST"]


def request(method, path, body=None):
    """One call; raises urllib errors."""
    headers = {"Authorization": AUTH + (f', Token="{token}"' if token else "")}
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(URL + path, data=data, method=method, headers=headers)
    with urllib.request.urlopen(req, timeout=60) as r:
        raw = r.read()
    return json.loads(raw) if raw.strip() else None


def call(method, path, body=None):
    """One call; exits with the status on an HTTP error (never prints the request body)."""
    # A fresh server answers 503 with a "server is starting" page for a while, and drops its
    # connections once when it swaps the startup web server for the real one: wait through both.
    last = attempts - 1
    for attempt in range(attempts):
        try:
            return request(method, path, body)
        except urllib.error.HTTPError as e:
            if e.code == 503 and attempt < last:
                time.sleep(2)
                continue
            sys.exit(f"{method} {path.split('?')[0]} -> HTTP {e.code}: {e.read()[:120]!r}")
        except (urllib.error.URLError, OSError) as e:
            if attempt < last:
                time.sleep(2)
                continue
            sys.exit(f"{URL} is not reachable ({e}): is the container up? tools/test-server/setup.sh")


def login_admin():
    global token
    token = call("POST", "/Users/AuthenticateByName",
                 {"Username": CREDS["JF_ADMIN_USER"], "Pw": CREDS["JF_ADMIN_PASS"]})["AccessToken"]
