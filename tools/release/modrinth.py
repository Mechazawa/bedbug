#!/usr/bin/env python3
"""Publishes built jars as Modrinth versions. The token is read from MODRINTH_TOKEN."""
import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
import uuid
from pathlib import Path

API = "https://api.modrinth.com"
SLUG = "bedbug"
FABRIC_API = "P7dR8mSH"
USER_AGENT = "Mechazawa/bedbug release"
JAR_PATH = re.compile(r"versions/([^/]+)/build/libs/[^/]+\.jar$")


def call(method, url, token, body=None, content_type=None):
    headers = {"User-Agent": USER_AGENT}
    if token:
        headers["Authorization"] = token
    if content_type:
        headers["Content-Type"] = content_type
    request = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request) as response:
            raw = response.read()
    except urllib.error.HTTPError as error:
        sys.exit(f"{method} {url} failed: {error.code} {error.read().decode(errors='replace')}")
    return json.loads(raw) if raw else None


def multipart(data, jar):
    boundary = uuid.uuid4().hex
    head = (
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"data\"\r\n"
        f"Content-Type: application/json\r\n\r\n{json.dumps(data)}\r\n"
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{jar.name}\"\r\n"
        "Content-Type: application/java-archive\r\n\r\n"
    )
    body = head.encode() + jar.read_bytes() + f"\r\n--{boundary}--\r\n".encode()
    return body, f"multipart/form-data; boundary={boundary}"


def release_type(version):
    for kind in ("alpha", "beta"):
        if f"-{kind}" in version:
            return kind
    return "release"


def minecraft_of(jar):
    match = JAR_PATH.search(jar.as_posix())
    return match.group(1) if match else None


def sort_key(minecraft):
    return [int(part) for part in re.findall(r"\d+", minecraft)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True, help="mod version without the leading v")
    parser.add_argument("--changelog", help="changelog text; defaults to a link to the GitHub release")
    parser.add_argument("--dry-run", action="store_true", help="print what would be sent, send nothing")
    parser.add_argument("jars", nargs="+", type=Path)
    args = parser.parse_args()

    jars = {}
    for jar in args.jars:
        minecraft = minecraft_of(jar)
        if jar.name.endswith(("-sources.jar", "-dev.jar", "-javadoc.jar")) or minecraft is None:
            continue
        jars[minecraft] = jar
    if not jars:
        sys.exit("no mod jars among the arguments")

    token = os.environ.get("MODRINTH_TOKEN")
    if not token and not args.dry_run:
        sys.exit("MODRINTH_TOKEN is not set")

    changelog = args.changelog or f"https://github.com/Mechazawa/bedbug/releases/tag/v{args.version}"
    newest = max(jars, key=sort_key)

    if args.dry_run:
        project_id, existing = SLUG, set()
    else:
        project_id = call("GET", f"{API}/v2/project/{SLUG}", token)["id"]
        existing = {v["version_number"] for v in call("GET", f"{API}/v2/project/{project_id}/version", token)}

    for minecraft in sorted(jars, key=sort_key):
        jar = jars[minecraft]
        number = f"{args.version}+{minecraft}"
        if number in existing:
            print(f"{number}: already on Modrinth, skipped")
            continue
        data = {
            "name": number,
            "version_number": number,
            "changelog": changelog,
            "dependencies": [{"project_id": FABRIC_API, "dependency_type": "required"}],
            "game_versions": [minecraft],
            "version_type": release_type(args.version),
            "loaders": ["fabric"],
            "featured": minecraft == newest,
            "project_id": project_id,
            "file_parts": ["file"],
            "primary_file": "file",
        }
        if args.dry_run:
            print(f"POST {API}/v2/version file={jar} ({jar.name})")
            print(json.dumps(data, indent=2))
            print(f"PATCH {API}/v3/version/<new id> " + json.dumps({"environment": "client_only_server_optional"}))
            continue
        body, content_type = multipart(data, jar)
        created = call("POST", f"{API}/v2/version", token, body, content_type)
        patch = json.dumps({"environment": "client_only_server_optional"}).encode()
        call("PATCH", f"{API}/v3/version/{created['id']}", token, patch, "application/json")
        print(f"{number}: published as {created['id']}")


if __name__ == "__main__":
    main()
