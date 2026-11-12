#!/usr/bin/env python3
"""Test users on the Ephyra test server. Passwords go to creds.env and are never printed.

user.py list
user.py add <name> [--no-download] [--no-password]   a new user; KEY in creds.env is JF_<NAME>
user.py rotate <name>                                a new password for an existing user
user.py remove <name>

Give every agent or emulator that tests at the same time its own user, so their watched state
and resume positions do not mix.
"""
import re
import secrets
import sys
import urllib.parse

import jf
from jf import CREDS, call


def key_for(name):
    for k, v in CREDS.items():
        if k.endswith("_USER") and v == name:
            return k[: -len("_USER")]
    return "JF_" + re.sub(r"[^A-Z0-9]", "_", name.upper())


def write_creds(key, name, password):
    """Set (or with name None: drop) KEY_USER and KEY_PASS in creds.env, keeping the other lines."""
    with open(jf.CREDS_PATH) as f:
        lines = [l for l in f.read().splitlines() if not l.startswith((key + "_USER=", key + "_PASS="))]
    if name is not None:
        lines.append(f"{key}_USER={name}")
        if password:
            lines.append(f"{key}_PASS={password}")
    with open(jf.CREDS_PATH, "w") as f:
        f.write("\n".join(lines) + "\n")


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    flags = {a for a in sys.argv[1:] if a.startswith("--")}
    if not args or args[0] not in ("list", "add", "rotate", "remove") or (args[0] != "list" and len(args) != 2):
        sys.exit(__doc__)
    jf.login_admin()
    users = {u["Name"]: u for u in call("GET", "/Users")}
    if args[0] == "list":
        for name, u in sorted(users.items()):
            may = "may download" if u["Policy"].get("EnableContentDownloading") else "no downloads"
            print(f"{name}: {may}, creds key {key_for(name)}")
        return
    name = args[1]
    key = key_for(name)
    if args[0] == "add":
        if name in users:
            sys.exit(f"{name} exists already")
        password = "" if "--no-password" in flags else secrets.token_hex(6)
        uid = call("POST", "/Users/New", {"Name": name, "Password": password})["Id"]
        policy = call("GET", f"/Users/{uid}")["Policy"]
        policy["EnableContentDownloading"] = "--no-download" not in flags
        policy["EnableAllFolders"] = True
        call("POST", f"/Users/{uid}/Policy", policy)
        write_creds(key, name, password)
        print(f"added {name}; its password is {key}_PASS in {jf.CREDS_PATH}")
        return
    if name not in users:
        sys.exit(f"no user {name}")
    uid = users[name]["Id"]
    if args[0] == "rotate":
        password = secrets.token_hex(6)
        call("POST", "/Users/Password?" + urllib.parse.urlencode({"userId": uid}), {"NewPw": password})
        write_creds(key, name, password)
        jf.token = None
        call("POST", "/Users/AuthenticateByName", {"Username": name, "Pw": password})
        print(f"rotated {name} and verified the new login; {key}_PASS in {jf.CREDS_PATH}")
    else:
        if name == CREDS["JF_ADMIN_USER"]:
            sys.exit("not removing the admin")
        call("DELETE", f"/Users/{uid}")
        write_creds(key, None, None)
        print(f"removed {name}")


main()
