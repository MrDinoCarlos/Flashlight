"""Run the native Paper smoke probe in isolated build/ worlds, with verified JARs.

Requires an existing accepted eula.txt; never changes a real server or its worlds.
Build with gradlew assemble smokeProbe first. Use --versions to select a subset.
"""
import argparse
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
VERSIONS = [f"1.21.{n}" for n in range(4, 12)] + ["26.1.1", "26.1.2", "26.2", "26.3"]


def download(url):
    request = urllib.request.Request(url, headers={"User-Agent": "FlashLight-compatibility/1.8.0 (local plugin smoke tests)"})
    with urllib.request.urlopen(request, timeout=180) as response:
        return response.read()


def run(version, index, args, plugin_version):
    cache = ROOT / "build/paper-downloads" / version
    cache.mkdir(parents=True, exist_ok=True)
    metadata = cache / "build.json"
    if not metadata.is_file():
        metadata.write_bytes(download(f"https://fill.papermc.io/v3/projects/paper/versions/{version}/builds/latest"))
    build = json.loads(metadata.read_text())
    artifact = build["downloads"]["server:default"]
    jar = cache / artifact["name"]
    if not jar.is_file() or hashlib.sha256(jar.read_bytes()).hexdigest() != artifact["checksums"]["sha256"]:
        content = download(artifact["url"])
        if hashlib.sha256(content).hexdigest() != artifact["checksums"]["sha256"]:
            raise ValueError(f"Paper SHA-256 mismatch: {version}")
        jar.write_bytes(content)
    directory = (ROOT / "build/compat-smoke" / version).resolve()
    if not directory.is_relative_to(ROOT / "build/compat-smoke"):
        raise ValueError("Smoke server must stay inside build/compat-smoke")
    plugins = directory / "plugins"
    plugins.mkdir(parents=True, exist_ok=True)
    branch = "1.21.X" if version.startswith("1.21.") else ".".join(version.split(".")[:2]) + ".X"
    shutil.copyfile(ROOT / f"build/libs/FlashLight-{plugin_version}-{branch}.jar", plugins / "FlashLight.jar")
    shutil.copyfile(ROOT / "build/paper-smoke/plugins/FlashLight-SmokeProbe.jar", plugins / "FlashLight-SmokeProbe.jar")
    shutil.copyfile(args.eula, directory / "eula.txt")
    (directory / "result.txt").write_text("RUNNING\n")
    (directory / "server.properties").write_text(f"""server-ip=127.0.0.1
server-port={25620+index}
online-mode=false
enforce-secure-profile=false
enable-rcon=false
allow-nether=false
level-type=minecraft:flat
generator-settings={{"layers":[{{"block":"minecraft:bedrock","height":1}}],"biome":"minecraft:plains","structure_overrides":[]}}
generate-structures=false
view-distance=3
simulation-distance=3
max-players=1
network-compression-threshold=-1
motd=Isolated FlashLight compatibility test
""", encoding="utf-8")
    java = args.java21 if version.startswith("1.") else args.java25
    command = [str(java), "-Xms256M", "-Xmx1536M", "-Dterminal.jline=false", "-Dterminal.ansi=false"]
    if os.name == "nt":
        command += ["-Djavax.net.ssl.trustStoreType=Windows-ROOT", "-Djavax.net.ssl.trustStore=NONE"]
    command += ["-jar", str(jar.resolve()), "--nogui"]
    print("Starting Paper", version, "build", build["id"], flush=True)
    with (directory / "console.log").open("w", encoding="utf-8") as log:
        completed = subprocess.run(command, cwd=directory, stdout=log, stderr=subprocess.STDOUT,
                                   timeout=300, creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
    result = (directory / "result.txt").read_text()
    passed = completed.returncode == 0 and result.startswith("PASS:")
    print(version, "PASS" if passed else "FAIL", result.strip(), flush=True)
    return {"minecraft": version, "build": build["id"], "passed": passed, "result": result.strip(),
            "plugin_sha256": hashlib.sha256((plugins / "FlashLight.jar").read_bytes()).hexdigest(),
            "log": str((directory / "console.log").relative_to(ROOT))}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--versions", nargs="+", default=VERSIONS)
    parser.add_argument("--java21", type=Path, default=Path.home() / ".jdks/temurin-21.0.12.1/bin/java.exe")
    parser.add_argument("--java25", type=Path, default=Path.home() / ".jdks/temurin-25.0.4.1/bin/java.exe")
    parser.add_argument("--eula", type=Path, default=ROOT / "build/paper-smoke/eula.txt")
    parser.add_argument("--workers", type=int, choices=(1, 2), default=2)
    args = parser.parse_args()
    if not args.eula.is_file() or not re.search(r"(?m)^eula=true\s*$", args.eula.read_text()):
        parser.error("Supply an existing accepted eula.txt using --eula")
    plugin_version = re.search(r'^version = "([^"]+)"', (ROOT / "build.gradle.kts").read_text(), re.M)[1]
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = [pool.submit(run, version, index, args, plugin_version) for index, version in enumerate(args.versions)]
        results = [future.result() for future in futures]
    report = ROOT / "build/compat-smoke/report.json"
    report.write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")
    raise SystemExit(0 if all(result["passed"] for result in results) else 1)
