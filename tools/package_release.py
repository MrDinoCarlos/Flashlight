"""Package the four verified branches with English documentation and checksums."""
from datetime import datetime, timezone
import hashlib
from io import BytesIO
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED

ROOT = Path(__file__).resolve().parents[1]
BRANCHES = ("1.21.X", "26.1.X", "26.2.X", "26.3.X")
EXPECTED_PAPER = {f"1.21.{n}" for n in range(4, 12)} | {"26.1.1", "26.1.2", "26.2", "26.3"}


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def branch_for(version):
    return "1.21.X" if version.startswith("1.21.") else ".".join(version.split(".")[:2]) + ".X"


def compatible_pack(pack):
    """Select the same compact pair as RendererProfiles, including native overlays."""
    with ZipFile(BytesIO(pack)) as archive:
        entries = {name: archive.read(name) for name in archive.namelist() if not name.endswith("/")}
    prefix = "assets/flashlight/shaders/post/"
    for stage in ("vsh", "fsh"):
        if prefix + "spotlight_compat." + stage not in entries:
            raise ValueError("Missing compatible shader")
    for path in list(entries):
        for stage in ("vsh", "fsh"):
            suffix = prefix + "spotlight_compat." + stage
            if path == suffix or path.endswith("/" + suffix):
                entries[path.removesuffix(suffix) + prefix + "spotlight." + stage] = entries[path]
    output = BytesIO()
    with ZipFile(output, "w", compression=ZIP_DEFLATED, compresslevel=9) as archive:
        for name, data in sorted(entries.items()):
            info = ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, data)
    return output.getvalue()


def main():
    version = re.search(r'^version = "([^"]+)"', (ROOT / "build.gradle.kts").read_text(), re.M)[1]
    suites = [ET.parse(path).getroot() for path in sorted((ROOT / "build/test-results/test").glob("TEST-*.xml"))]
    totals = {key: sum(int(suite.attrib.get(key, 0)) for suite in suites)
              for key in ("tests", "failures", "errors", "skipped")}
    if not suites or not totals["tests"] or any(totals[key] for key in ("failures", "errors", "skipped")):
        raise ValueError(f"Require a successful full test run with no skipped tests: {totals}")

    native = json.loads((ROOT / "build/compat-smoke/report.json").read_text(encoding="utf-8"))
    if len(native) != len(EXPECTED_PAPER) or {row["minecraft"] for row in native} != EXPECTED_PAPER:
        raise ValueError("Require the complete native Paper matrix, not a subset report")

    files, artifacts, hashes = {}, [], {}
    base_model_count = 0
    for branch in BRANCHES:
        jar_name = f"FlashLight-{version}-{branch}.jar"
        pack_name = f"FlashLight-ResourcePack-{version}-{branch}.zip"
        jar = (ROOT / "build/libs" / jar_name).read_bytes()
        pack = (ROOT / "build/distributions" / pack_name).read_bytes()
        with ZipFile(BytesIO(jar)) as archive:
            if archive.testzip() or archive.read("pack/FlashLight-ResourcePack.zip") != pack:
                raise ValueError(f"Invalid or mismatched embedded pack: {jar_name}")
            metadata = archive.read("plugin.yml").decode("utf-8")
            compatibility = archive.read("compatibility.properties").decode("utf-8")
            expected_version = r"(?m)^version:\s*['\"]?" + re.escape(version) + r"['\"]?\s*$"
            expected_api = "1.21.4" if branch == "1.21.X" else branch.removesuffix(".X")
            actual_api = re.search(r"(?m)^api-version:\s*['\"]?([^'\"\s]+)", metadata)
            if not re.search(expected_version, metadata) or not actual_api or actual_api[1] != expected_api or f"minecraft.branch={branch}" not in compatibility:
                raise ValueError(f"Incorrect branch metadata: {jar_name}")
        with ZipFile(BytesIO(pack)) as archive:
            if archive.testzip():
                raise ValueError(f"Invalid pack ZIP: {pack_name}")
            if branch == "1.21.X":
                base_model_count = sum(name.startswith("assets/") and "/models/" in name and name.endswith(".json")
                                       for name in archive.namelist())
            for name in archive.namelist():
                if name.endswith(".json") or name.endswith(".mcmeta"):
                    json.loads(archive.read(name))
        hashes[branch] = sha256(jar)
        compatible_name = pack_name.removesuffix(".zip") + "-Compatible.zip"
        compatible = compatible_pack(pack)
        (ROOT / "build/distributions" / compatible_name).write_bytes(compatible)
        for name, data, folder in ((jar_name, jar, "plugins"), (pack_name, pack, "resource-packs"),
                                   (compatible_name, compatible, "resource-packs")):
            path = f"{folder}/{name}"
            files[path] = data
            artifacts.append({"path": path, "bytes": len(data), "sha256": sha256(data)})

    for row in native:
        if not row["passed"] or row.get("plugin_sha256") != hashes[branch_for(row["minecraft"])]:
            raise ValueError(f"Native probe failed or tested a different JAR: {row['minecraft']}")

    now = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC")
    summary = [f"Release: FlashLight {version}", f"Packaged: {now}",
               f"Automated tests: {totals['tests']} passed; 0 failures; 0 errors; 0 skipped.",
               f"Pack validation: {base_model_count:,} base models; valid JSON/UVs/textures; embedded packs match distributed ZIPs.",
               "Rendering profiles checked: 13 native Minecraft client releases.",
               "Native Paper probes: 12 passed; each report matches the final distributed JAR SHA-256.", "",
               "Native Minecraft / Paper build / result / server Java:"]
    for row in native:
        java = 21 if row["minecraft"].startswith("1.") else 25
        summary.append(f"  {row['minecraft']:9} build {row['build']:3}  PASS  Java {java}")
    summary += ["", "Plain Paper 26.1 was not available in the retrieved published server list.",
                "The 26.1 client renderer is checked; native server probes cover 26.1.1 and 26.1.2."]

    gpu_path = ROOT / f"build/gpu-{version}-fullchain-1920x1080.txt"
    gpu = gpu_path.read_text(encoding="utf-8").strip()
    scope = """CHECK SCOPE

Behavioral tests cover commands/selectors/timers, permissions, battery and item
persistence, transfers, configuration migration, provider contracts, geometric
hand placement, forward lens direction, upper switch orientation across all
six item models in both hands, multiplayer visibility, and resource-pack tuning.
Standalone checks exercise automatic join/reload delivery, old-default migration,
IPv4/IPv6/domain URLs, provider ownership, HTTP byte/hash integrity and cleanup.
Renderer checks cover per-player selection, one retry on FAILED_RELOAD, rejection
of unrelated statuses, explicit FAST/COMPATIBLE modes and external fallback URLs.
Modrinth checks verify stable-release selection for the native Paper version,
semantic ordering, HTTP failures, asynchronous lifecycle and notification limits.
Console and per-admin notices are checked at 0, 23, 24 and 48 hours, including
reconnects and reload, without making live API requests in the unit tests.

Graphics checks use actual OpenGL shader compilation and rendering, exact-version
native includes/post schemas, and the shipped five-pass graph. They cover dim/off,
cone boundaries, textures, scene-depth preservation, normal outlines, water,
shared body shadows, both local hands, distant-camera behavior, and face glare.
26.3 also has ShaderC SPIR-V compilation across relevant native transparency
phases and depth definitions, plus zero-to-one OpenGL depth reconstruction checks.
Foreground/distant receiver regressions ensure that sparse scan reach changes
do not blink surface lighting; this is checked across all 13 client profiles.
Stationary first-person air regressions alternate near/far collision scan extents
with fixed scene/camera, checking both hands across all 13 native profiles and
both renderer variants, including 26.3 zero-to-one depth. The 1.8.4 implementation
failed this regression before the local metadata correction. Remote and distant
F5 controls retain their server collision limits. The full-screen spotlight
programs, pass graph, models and textures are unchanged by the 1.8.5 correction.
The optional compact interface fits 16 varying vectors and uses 61 scalar
components. The default fast lighting/shadow kernel and vertex shader retain
the expanded 1.8.2 source; final capped-colour normalization uses continuous opacity.
The fast kernel does not execute the compact path's extra fragment work. Both paths compile
against all 13 native client profiles. Compact A/B checks against 1.8.2 cover
32 lights, sparse slots, all eight body casters, glare and nonstandard projection
matrices. Full float precision and the five-pass graph are retained.
Surface-composition regressions sweep 128 intensity samples with fixed scene and
camera. The old encoder alternated between adjacent RGB values after clipping
overlay alpha; the corrected fast and compatible outputs keep capped RGB stable.
The same property is checked across all 13 native profiles and 26.3 zero-to-one
depth. Scan regressions compare exact final composited RGB rather than invisible
overlay RGB. The capped-colour regression checks the encoded RGB/alpha directly. Static A/B appearance remains
within the existing two-level RGBA8 tolerance. No sample, pass or target is added.

The native probe launches actual isolated Paper servers. It checks startup,
native items, command registration, reload, collision geometry, bounded display
counts, shared/private optical controls, eight shared body controls, intensity,
turning, cleanup, unchanged receiver metadata/block light, and native pack export.
It also requests and downloads the primary pack over the standalone HTTP listener
without Nexo or an explicit public URL, checking the offered SHA-1 and ZIP bytes.
Observers are simulated; these are not twelve interactive multiplayer sessions.

LIMITS OF THIS VERIFICATION

No connected-client multiplayer play session, production-server CPU profile,
complete interactive Vulkan session, or exhaustive rendering-mod/GPU matrix was
performed. Third-person grip is checked geometrically; final animation/camera
appearance should be observed in the native game. External provider contracts
and legacy shader fixtures do not certify every Nexo/TRP/Strobe release on every
server version. Future patches and protocol-translated clients are not implied.
An Intel HD 2500 was not available for physical testing. The reduced interface
targets its reported varying-space link failure, but other hardware/driver
limitations remain possible and must be checked on that device.
Vanilla sends no GPU identity. Some late postprocessing failures never report
FAILED_RELOAD; these require manual compatible selection. Shared Nexo/TRP
delivery retains the configured global profile and cannot retry per player.
Additional 26.3 startup probes encountered intermittent native Windows aborts
before atlas creation, also reproduced with vanilla and the previous release.
These probes do not establish reliable client startup or an interactive session;
the repeatable shader rendering/compilation checks are reported separately above.

Body shadows approximate up to eight selected player ellipsoids; they are not
animated mesh shadows. Local camera tracking reduces steering delay but cannot
restore server TPS or eliminate latency. These limits are documented in the wiki.

GPU MEASUREMENT

The following is synthetic effect cost, not whole-client FPS. The scene has no
active body proxies and is not a worst-case shadow benchmark. Timing varies with
load, resolution, drivers, hardware, and visible lights. Internal target bytes
are estimated allocation, not total Minecraft VRAM.
"""
    connected = []
    connected_path = ROOT / "docs" / f"FlashLight-Native-Lens-Regression-{version}.json"
    if connected_path.is_file():
        evidence = json.loads(connected_path.read_text(encoding="utf-8"))
        if evidence["release"] != version or not all(case["passed"] for case in evidence["cases"]):
            raise ValueError("Native connected-client lens regression did not pass")
        connected = ["NATIVE CONNECTED-CLIENT LENS REGRESSION", "",
                     "Stationary camera, fixed daylight, breathing enabled, random faults disabled.",
                     "A block edge crosses the server lens while the rendered camera stays still.",
                     "The uncorrected 1.8.6 run lost its controllers in 25 of 69 live samples.",
                     "The corrected 26.3 fast run kept all 69 samples active; the measured lit",
                     "patch remained byte-for-byte stable in its 12 image samples.",
                     "26.3 images use temporary diagnostic colour/depth recording passes and a",
                     "texture-readback agent. These diagnostics are absent from shipped packs.",
                     "Legacy client images use the shipped graph and a readback agent.", ""]
        for case in evidence["cases"]:
            connected.append(f"{case['client']}; {case['renderer']}; live samples={case['live_samples']}; "
                             f"missing controls={case['missing_controls']}; image samples={case['image_samples']}; "
                             f"measured patch channel range={case['patch_channel_range']}; PASS")
        connected += ["", "This corrects server-side controller removal, not every possible visual variation.",
                      "OptiFine validation is 1.21.4 HD U J3, external Shaders OFF. No official",
                      "26.3 OptiFine build was listed at https://optifine.net/downloads when checked",
                      "on 2026-11-15. External shaderpacks, other settings and GPUs are not certified.",
                      "All shader files are identical to 1.8.6; no GPU pass, target or sample is added."]
        files["docs/" + connected_path.name] = connected_path.read_bytes()
        figure = ROOT / "docs/native-lens-dropout.png"
        if figure.is_file():
            files["docs/" + figure.name] = figure.read_bytes()
        scope = scope.replace("No connected-client multiplayer play session, production-server CPU profile,\ncomplete interactive Vulkan session, or exhaustive rendering-mod/GPU matrix was\nperformed.",
                              "Isolated connected-client checks are listed above. No production-server CPU\nprofile, long manual multiplayer play session, complete interactive Vulkan\nsession or exhaustive rendering-mod/GPU matrix was performed.")
        scope = scope.replace("These probes do not establish reliable client startup or an interactive session;\nthe repeatable shader rendering/compilation checks are reported separately above.",
                              "Those earlier startup-only probes did not establish an interactive session.\nThe later successful connected-client cases are documented separately above.")
    report_lines = [f"FLASHLIGHT {version} — RELEASE VERIFICATION", "", *summary, "", *connected, "", scope.strip(), "", gpu,
                    "", "NATIVE CPU MEASUREMENTS", "", "Synthetic repeated renderer updates; not a production-server profile."]
    optimization = []
    for level in (150, 330):
        path = ROOT / f"build/gpu-optimization-glsl{level}-1920x1080.txt"
        if path.is_file():
            optimization.append(path.read_text(encoding="utf-8").strip())
            files[f"docs/{path.name}"] = path.read_bytes()
    if optimization:
        report_lines[-4:-4] = ["", "GPU A/B AGAINST THE 1.8.1 SHADERS", "",
                              "31 interleaved timed frames after warmup, identical scene and graph.",
                              "Appearance is compared within two RGBA8 levels. These timings describe",
                              "effect cost, not total Minecraft FPS; background GPU load can affect them.",
                              "The zero-light case forces the outline graph for measurement; Minecraft",
                              "normally skips this graph when no outlined entities need it.", "", *optimization]
    compatibility = []
    fast = []
    for level in (150, 330):
        path = ROOT / f"build/gpu-fast-glsl{level}-1920x1080.txt"
        if path.is_file():
            fast.append(path.read_text(encoding="utf-8").strip())
            files[f"docs/{path.name}"] = path.read_bytes()
    if fast:
        report_lines[-4:-4] = ["", "DEFAULT FAST PATH A/B AGAINST 1.8.2", "",
                              "The cached lighting/shadow kernel is retained; capped colours use continuous opacity.",
                              "No new texture sample, pass or target is added. Timings include measurement noise.",
                              "The compatible profile's extra fragment work is absent from this path.", "", *fast]
    for level in (150, 330):
        path = ROOT / f"build/gpu-compatibility-glsl{level}-1920x1080.txt"
        if path.is_file():
            compatibility.append(path.read_text(encoding="utf-8").strip())
            files[f"docs/{path.name}"] = path.read_bytes()
    if compatibility:
        report_lines[-4:-4] = ["", "COMPACT INTERFACE A/B AGAINST 1.8.2", "",
                              "This change reduces varying storage to support lower interface budgets.",
                              "It does not promise higher FPS: extra shadow records are decoded lazily",
                              "per fragment. Read the measured GPU cost for each scenario, not only its",
                              "interface-size reduction. A/B image differences remain within two RGBA8 levels.",
                              "", *compatibility]
    surface_traces = []
    for variant in ("spotlight", "spotlight_compat"):
        path = ROOT / f"build/surface-stability-{variant}.csv"
        rows = path.read_text(encoding="utf-8").strip().splitlines()[1:]
        colors = {tuple(row.split(",")[1:4]) for row in rows}
        if len(rows) != 128 or len(colors) != 1:
            raise ValueError(f"Require a stable 128-frame surface trace: {path.name}")
        files[f"docs/{path.name}"] = path.read_bytes()
        surface_traces.append(f"{variant}: 128 samples; constant composited RGB {','.join(next(iter(colors)))}")
    report_lines[-4:-4] = ["", "CAPPED SURFACE TEMPORAL REGRESSION", "",
                          "Fixed geometry/camera/scene; intensity swept through the capped range.",
                          "The old 1.8.5 trace alternated adjacent RGB codes. Both final variants",
                          "remain constant; full per-frame traces are included alongside this report.",
                          *surface_traces]
    for row in native:
        cpu = ROOT / "build/compat-smoke" / row["minecraft"] / f"cpu-{version}.txt"
        if cpu.is_file():
            report_lines += ["", f"Paper {row['minecraft']}:", cpu.read_text(encoding="utf-8").strip()]
    report_lines += ["", "DISTRIBUTED ARTIFACTS — SHA-256", ""]
    for artifact in artifacts:
        report_lines += [f"{artifact['sha256']}  {artifact['path']}", f"  {artifact['bytes']} bytes"]
    report = "\n".join(report_lines) + "\n"
    report_name = f"FlashLight-Verification-{version}.txt"
    (ROOT / "docs" / report_name).write_text(report, encoding="utf-8")

    wiki_path = ROOT / "docs/FlashLight-Wiki-EN.txt"
    wiki = wiki_path.read_text(encoding="utf-8")
    wiki = re.sub(r"BEGIN GENERATED VERIFICATION\n.*?\n?END GENERATED VERIFICATION",
                  "BEGIN GENERATED VERIFICATION\n" + "\n".join(summary) + "\nEND GENERATED VERIFICATION", wiki, flags=re.S)
    wiki_path.write_text(wiki, encoding="utf-8")
    for path in (wiki_path, ROOT / "docs" / report_name, ROOT / "docs/model-preview.png",
                 ROOT / "docs/third-person-grip-preview.png"):
        files["docs/" + path.name] = path.read_bytes()
    files["docs/native-paper-results.json"] = (json.dumps(native, indent=2) + "\n").encode("utf-8")
    files["config.yml"] = (ROOT / "src/main/resources/config.yml").read_bytes()
    files["LICENSE"] = (ROOT / "LICENSE").read_bytes()

    readme = (ROOT / "README.md").read_text(encoding="utf-8")
    readme = readme.replace("(build/libs/", "(plugins/").replace("(build/distributions)", "(resource-packs)")
    readme = readme.replace("(src/main/resources/config.yml)", "(config.yml)")
    readme = re.sub(r"Download the \[complete release ZIP\]\([^\n]+?\) for", "This archive contains", readme)
    readme = re.sub(r"## Model sources\n.*?\n## Building and verification", "## Building and verification", readme, flags=re.S)
    files["README.md"] = readme.encode("utf-8")
    files["INSTALL.txt"] = f"""FlashLight {version}

1. Choose ONE JAR from plugins/ for your server branch. Do not install all four.
2. Paper 1.21.4-1.21.11 uses Java 21. Paper 26.x uses Java 25.
3. Stop your server, replace its old FlashLight JAR, then restart.
4. Keep your data directory and distribute the updated resource pack too.
5. The plugin exports its native, tuned pack to plugins/FlashLight/
   FlashLight-ResourcePack.zip. Nexo/TRP manage delivery when available; otherwise
   standalone HTTP hosting and sending on join are enabled by default. Make TCP
   port 8192 reachable, or configure the HTTP port/public-url for your hosting.
6. Accept the pack, run /flashlight give, and right-click the held item.
   Version 1.8.7 fixes powered controls disappearing on near-lens collisions.
   Its shader files retain the 1.8.6 composition and local air fixes; replace
   the client pack too, including packs hosted externally or rebuilt by Nexo/TRP.
7. The fast renderer is the default. A client with varying-space link errors can
   use /flashlight pack compatible with standalone delivery. Automatic retry
   requires a FAILED_RELOAD report; some late shader errors do not report it.
   /flashlight pack fast returns that player to the original renderer. For a
   shared Nexo/TRP pack, explicitly configure resource-pack.renderer: COMPATIBLE
   and rebuild the provider instead. See the wiki for external hosting details.

plugins/ contains four alternative plugin builds, not server plugins to install
together. resource-packs/ contains the corresponding branch pack distributions.
config.yml is the commented default; retain and reload your existing settings.
docs/ contains the complete English wiki and exact release verification report.
README.md is the shorter description and setup guide. Build tools/source models
referenced by development documentation belong to the development workspace.

LICENSE contains the CC-BY-NC-ND-4.0 license for original FlashLight material.

SHA256SUMS.txt lists the integrity hash of every other file in this archive.
""".encode("utf-8")
    files["SHA256SUMS.txt"] = ("\n".join(f"{sha256(data)}  {path}" for path, data in sorted(files.items())) + "\n").encode("utf-8")
    directory = ROOT / "build/releases"
    directory.mkdir(parents=True, exist_ok=True)
    archive_path = directory / f"FlashLight-{version}-complete.zip"
    with ZipFile(archive_path, "w", compression=ZIP_DEFLATED, compresslevel=9) as archive:
        for path, data in sorted(files.items()):
            info = ZipInfo(path, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, data)
    with ZipFile(archive_path) as archive:
        if archive.testzip():
            raise ValueError("Release ZIP integrity check failed")
        for line in archive.read("SHA256SUMS.txt").decode().splitlines():
            expected, path = line.split("  ", 1)
            if sha256(archive.read(path)) != expected:
                raise ValueError(f"Release checksum mismatch: {path}")
    checksum = f"{sha256(archive_path.read_bytes())}  {archive_path.name}\n"
    archive_path.with_suffix(".zip.sha256").write_text(checksum, encoding="utf-8")
    print(f"Packaged {archive_path.name}: {archive_path.stat().st_size:,} bytes; {len(files)} files")
    print(f"Tests: {totals['tests']} passed, none skipped. Native Paper: {len(native)} passed with matching JAR hashes.")
    print(f"Wiki: {len(wiki.split()):,} words. All release entry checksums verified.")


if __name__ == "__main__":
    main()
