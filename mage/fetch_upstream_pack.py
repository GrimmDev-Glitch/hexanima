#!/usr/bin/env python3
"""Fetch and verify the upstream prepacked Mage files for host/phone port tests."""
from __future__ import annotations

import concurrent.futures
import hashlib
import json
import urllib.request
from pathlib import Path, PurePosixPath


REPO = "ayanoby/hexanima-mage-packs"
REVISION = "1937989fd5327472322d273366aa9ee8d944729d"
ROOT = Path(__file__).resolve().parent / "artifacts" / "upstream-pack"
BASE_URL = f"https://huggingface.co/{REPO}/resolve/{REVISION}/"
MANIFEST_URL = BASE_URL + "manifest.json"


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(4 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def safe_path(relative: str) -> Path:
    name = PurePosixPath(relative)
    if name.is_absolute() or not name.parts or any(part in ("", ".", "..") for part in name.parts):
        raise ValueError(f"unsafe path in Mage manifest: {relative!r}")
    return ROOT.joinpath(*name.parts)


def fetch_one(item: dict[str, object]) -> str:
    relative = str(item["path"])
    expected_size = int(item["size"])
    expected_hash = str(item["sha256"]).lower()
    dest = safe_path(relative)
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.is_file() and dest.stat().st_size == expected_size and sha256(dest) == expected_hash:
        return f"verified {relative} ({expected_size} bytes)"

    part = dest.with_name(dest.name + ".part")
    have = part.stat().st_size if part.exists() else 0
    if have > expected_size:
        part.unlink()
        have = 0
    headers = {"User-Agent": "HexAnima-Mage-V73-test-pack/1"}
    if have:
        headers["Range"] = f"bytes={have}-"
    request = urllib.request.Request(BASE_URL + relative, headers=headers)
    with urllib.request.urlopen(request, timeout=120) as response:
        if have and response.status != 206:
            have = 0
        mode = "ab" if have else "wb"
        with part.open(mode) as stream:
            while chunk := response.read(4 << 20):
                stream.write(chunk)

    if part.stat().st_size != expected_size:
        raise ValueError(f"size mismatch for {relative}: {part.stat().st_size} != {expected_size}")
    actual = sha256(part)
    if actual != expected_hash:
        raise ValueError(f"SHA-256 mismatch for {relative}: {actual} != {expected_hash}")
    part.replace(dest)
    return f"verified {relative} ({expected_size} bytes)"


def main() -> None:
    request = urllib.request.Request(MANIFEST_URL, headers={"User-Agent": "HexAnima-Mage-V73-test-pack/1"})
    with urllib.request.urlopen(request, timeout=60) as response:
        manifest = json.load(response)
    files = manifest["files"]
    expected_total = sum(int(item["size"]) for item in files)
    print(f"{REPO}@{REVISION}: {len(files)} files, {expected_total} bytes", flush=True)
    ROOT.mkdir(parents=True, exist_ok=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        futures = [pool.submit(fetch_one, item) for item in files]
        done = 0
        for future in concurrent.futures.as_completed(futures):
            print(future.result(), flush=True)
            done += 1
            print(f"progress {done}/{len(files)}", flush=True)
    actual_total = sum(path.stat().st_size for path in ROOT.rglob("*") if path.is_file() and not path.name.endswith(".part"))
    if actual_total != expected_total:
        raise ValueError(f"pack total mismatch: {actual_total} != {expected_total}")
    print(f"Mage pack verified: {actual_total} bytes", flush=True)


if __name__ == "__main__":
    main()
