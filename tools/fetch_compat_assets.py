"""Cache SHA-1 verified vanilla shader assets from Mojang for compatibility checks.

Only build/ is written. These vanilla files are reference inputs, not shipped assets.
"""
import concurrent.futures
import hashlib
import json
from pathlib import Path
import sys
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / "build/minecraft-assets"
MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
VERSIONS = ["1.21.4", "1.21.5", "1.21.6", "1.21.7", "1.21.8", "1.21.9",
            "1.21.10", "1.21.11", "26.1", "26.1.1", "26.1.2", "26.2", "26.3"]


def download(url):
    request = urllib.request.Request(url, headers={"User-Agent": "FlashLight-compatibility/1.8.0"})
    with urllib.request.urlopen(request, timeout=120) as response:
        return response.read()


def fetch(version, manifest):
    entry = next(item for item in manifest["versions"] if item["id"] == version)
    raw = download(entry["url"])
    if hashlib.sha1(raw).hexdigest() != entry["sha1"]:
        raise ValueError(f"Manifest SHA-1 mismatch: {version}")
    metadata = json.loads(raw)
    target = CACHE / version
    target.mkdir(parents=True, exist_ok=True)
    (target / "manifest.json").write_bytes(raw)
    client = metadata["downloads"]["client"]
    jar = target / "client.jar"
    if not jar.is_file() or hashlib.sha1(jar.read_bytes()).hexdigest() != client["sha1"]:
        content = download(client["url"])
        if hashlib.sha1(content).hexdigest() != client["sha1"]:
            raise ValueError(f"Client SHA-1 mismatch: {version}")
        jar.write_bytes(content)
    with zipfile.ZipFile(jar) as source:
        # Newer client JARs include explicit directory entries.
        for folder in ("post_effect", "shaders"):
            stale = target / "assets/minecraft" / folder
            if stale.is_file() and stale.stat().st_size == 0:
                stale.unlink()
        for name in source.namelist():
            if name.endswith("/"):
                continue
            if name.startswith(("assets/minecraft/shaders/", "assets/minecraft/post_effect/")) or name == "version.json":
                path = target / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(source.read(name))
    version_data = json.loads((target / "version.json").read_text())
    print(version, "pack", version_data["pack_version"], "Java", metadata["javaVersion"]["majorVersion"], flush=True)


if __name__ == "__main__":
    versions = sys.argv[1:] or VERSIONS
    manifest = json.loads(download(MANIFEST))
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(lambda version: fetch(version, manifest), versions))
