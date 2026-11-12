#!/usr/bin/env python3
"""Configure the Ephyra test Jellyfin server through its API. Safe to run again.

configure.py                 wizard, libraries, users, then a report
configure.py --report-only   only the report
"""
import sys
import time
import urllib.error
import urllib.parse

import jf
from jf import CREDS, call

report_only = "--report-only" in sys.argv
if not report_only:
    jf.attempts = 90  # a server that was just started needs a while

for _ in range(90):
    try:
        info = jf.request("GET", "/System/Info/Public")
        break
    except (urllib.error.URLError, ConnectionError, OSError, ValueError):  # ValueError: a page, not JSON
        if report_only:
            sys.exit(f"{jf.URL} does not answer")
        time.sleep(2)
else:
    sys.exit("server did not come up")

if not report_only and not info.get("StartupWizardCompleted"):
    call("POST", "/Startup/Configuration",
         {"UICulture": "en-US", "MetadataCountryCode": "US", "PreferredMetadataLanguage": "en"})
    call("GET", "/Startup/User")
    call("POST", "/Startup/User", {"Name": CREDS["JF_ADMIN_USER"], "Password": CREDS["JF_ADMIN_PASS"]})
    call("POST", "/Startup/RemoteAccess", {"EnableRemoteAccess": True, "EnableAutomaticPortMapping": False})
    call("POST", "/Startup/Complete")

jf.login_admin()


def add_library(existing, name, collection_type, path, item_types, internet=False):
    if name in existing:
        return
    query = urllib.parse.urlencode({"name": name, "collectionType": collection_type, "refreshLibrary": "false"})
    if internet:  # the server's default fetchers (TMDb and friends)
        call("POST", "/Library/VirtualFolders?" + query, {"LibraryOptions": {
            "Enabled": True, "EnableRealtimeMonitor": True, "PathInfos": [{"Path": path}],
            "EnableInternetProviders": True, "PreferredMetadataLanguage": "en", "MetadataCountryCode": "US",
        }})
        return
    call("POST", "/Library/VirtualFolders?" + query, {"LibraryOptions": {
        "Enabled": True,
        "EnableRealtimeMonitor": True,
        "PathInfos": [{"Path": path}],
        "MetadataSavers": [],
        "SaveLocalMetadata": False,
        "EnableInternetProviders": False,
        "TypeOptions": [{"Type": t, "MetadataFetchers": [], "ImageFetchers": []} for t in item_types],
    }})


def make_user(users, name, password, may_download):
    uid = users.get(name) or call("POST", "/Users/New", {"Name": name, "Password": password})["Id"]
    policy = call("GET", f"/Users/{uid}")["Policy"]
    policy["EnableContentDownloading"] = may_download
    policy["EnableAllFolders"] = True
    call("POST", f"/Users/{uid}/Policy", policy)


if not report_only:
    cfg = call("GET", "/System/Configuration")
    cfg["ServerName"] = "Ephyra Test"
    cfg["QuickConnectAvailable"] = True
    call("POST", "/System/Configuration", cfg)

    existing = {f["Name"] for f in call("GET", "/Library/VirtualFolders")}
    add_library(existing, "Movies", "movies", "/media/movies", ["Movie"])
    add_library(existing, "Shows", "tvshows", "/media/shows", ["Series", "Season", "Episode"])
    add_library(existing, "Real Movies", "movies", "/media/movies-meta", [], internet=True)
    add_library(existing, "Real Shows", "tvshows", "/media/shows-meta", [], internet=True)
    # trickplay on the edge-case libraries (small files, quick to extract)
    for folder in call("GET", "/Library/VirtualFolders"):
        opts = folder["LibraryOptions"]
        if folder["Name"] in ("Movies", "Shows") and not opts.get("EnableTrickplayImageExtraction"):
            opts["EnableTrickplayImageExtraction"] = True
            opts["ExtractTrickplayImagesDuringLibraryScan"] = True
            call("POST", "/Library/VirtualFolders/LibraryOptions", {"Id": folder["ItemId"], "LibraryOptions": opts})
    call("POST", "/Library/Refresh")

    users = {u["Name"]: u["Id"] for u in call("GET", "/Users")}
    make_user(users, CREDS["JF_TESTER_USER"], CREDS["JF_TESTER_PASS"], True)
    make_user(users, CREDS["JF_NODL_USER"], CREDS["JF_NODL_PASS"], False)
    make_user(users, CREDS["JF_SECOND_USER"], CREDS["JF_SECOND_PASS"], True)
    make_user(users, CREDS["JF_NOPASS_USER"], "", True)

    # wait for the scan
    for _ in range(120):
        tasks = call("GET", "/ScheduledTasks")
        if not any(t["State"] != "Idle" and t["Key"] == "RefreshLibrary" for t in tasks):
            break
        time.sleep(2)

# what the server made of the edge cases
admin = next(u["Id"] for u in call("GET", "/Users") if u["Name"] == CREDS["JF_ADMIN_USER"])


def q(**kw):
    return call("GET", "/Items?" + urllib.parse.urlencode({"userId": admin, "Recursive": "true", **kw}))


info = call("GET", "/System/Info/Public")  # the first answer of a fresh server has no name yet
print("server:", info.get("ServerName"), info.get("Version"), "at", jf.URL)
for kind in ("Movie", "Series", "Season", "Episode"):
    print(f"{kind}: {q(IncludeItemTypes=kind, Limit=0)['TotalRecordCount']}")
for s in q(IncludeItemTypes="Season", Fields="ChildCount", SortBy="SeriesSortName,SortName")["Items"]:
    print(f"  {s.get('SeriesName')} / {s['Name']}: {s.get('ChildCount')} episodes")
for m in q(IncludeItemTypes="Movie", Fields="MediaSources")["Items"]:
    src = m.get("MediaSources") or []
    streams = [f"{x['Type'][0]}:{x.get('Codec')}" for x in (src[0].get("MediaStreams") if src else [])]
    print(f"  {m['Name'][:40]}: {len(src)} version(s) {' '.join(streams)}")
withimg = q(IncludeItemTypes="Movie,Series", ImageTypes="Primary", Limit=0)["TotalRecordCount"]
withcast = [i["Name"] for i in q(IncludeItemTypes="Movie,Series", Fields="People")["Items"] if i.get("People")]
print(f"with a poster: {withimg}; with cast: {len(withcast)}")
trick = [i["Name"] for i in q(IncludeItemTypes="Movie,Episode", Fields="Trickplay")["Items"] if i.get("Trickplay")]
print(f"with trickplay: {len(trick)}")
chapters = [i["Name"] for i in q(IncludeItemTypes="Movie,Episode", Fields="Chapters")["Items"] if i.get("Chapters")]
print(f"with chapters: {len(chapters)}")
print("users:", ", ".join(sorted(u["Name"] for u in call("GET", "/Users"))))
