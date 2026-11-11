"""Validate local XML/artifact evidence and emit a compact, reproducible manifest (no model calls)."""
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
version = next(line.split("=", 1)[1].strip() for line in (root / "gradle.properties").read_text().splitlines()
               if line.startswith("mod_version"))
source_roots = ("api/src", "engine/src", "tools/src", "fabric/src", "neoforge/src", "tests/gametest")
sources = sorted(path for directory in source_roots for path in (root / directory).rglob("*") if path.is_file())
sources += [root / name for name in ("build.gradle", "fabric/build.gradle", "neoforge/build.gradle", "gradle.properties",
                                    "gradle/wrapper/gradle-wrapper.properties")]
digest = hashlib.sha256()
for path in sorted(sources):
    data = path.read_bytes()
    if path.suffix in {".java", ".json", ".toml", ".cfg", ".gradle", ".properties", ".md"}:
        data = data.replace(b"\r\n", b"\n")
    digest.update(path.relative_to(root).as_posix().encode() + b"\0" + data + b"\0")

def read_cases(path):
    tree = ET.parse(path)
    cases = tree.findall(".//testcase")
    if not cases or any(case.find(tag) is not None for case in cases for tag in ("failure", "error", "skipped")):
        raise ValueError(f"Missing or unsuccessful test evidence: {path}")
    return [case.attrib.get("name", "") for case in cases]

unit_paths = sorted((root / "fabric/build/test-results/test").glob("TEST-*.xml"))
if not unit_paths:
    raise ValueError("Run :fabric:test first")
units = sum(len(read_cases(path)) for path in unit_paths)
lifecycle = {
    "playerlifecyclegametests.createsremovesandrecreatesthesameprofile",
    "playerlifecyclegametests.duplicatecreationpreservestheexistingplayer",
    "playerlifecyclegametests.failedentityregistrationrollsbackonlythenewplayer",
}
base = lifecycle | {
    "learninggametests.nativedatareloadinvalidateslearnedrules",
    "learninggametests.learnstransfersandstopsafterrulechange",
    "learninggametests.observesnewitemwithoutdumpingprivatecomponents",
    "learninggametests.observespublicactorandrejectswronguuid",
    "learninggametests.runsobservationsviatheagentloopandreadspublicstorage",
}
storage = {"storagemodgametests.learnscompensatesandtransfersarealdrawer"}
industry = {"industrymodgametests.reportsactualpublicelectricstorageinitsownunit",
            "industrymodgametests.fallsbackfromunconfiguredportsandsmeltsthroughtherealmenu"}
cooking = {"cookingmodgametests.cookstherealnewingredientwithheatandacontainer",
           "cookingmodgametests.observesactualcookingpotandnewingredient"}
cross = {"crossmodgametests.movesarealmodingredientthroughanothermodsport",
         "crossmodgametests.twoagentscoordinatedependentjobsononerealport"}
reports = {}
expected = {"base": base, "storage": base | storage, "industry": base | industry,
            "combined": base | storage | industry | cooking | cross, "neoforge": lifecycle}
latest_source = max(path.stat().st_mtime for path in sources)
for profile, required in expected.items():
    path = root / ("neoforge/build/gametest-results.xml" if profile == "neoforge" else
                   "fabric/build/gametest-results.xml" if profile == "base" else f"fabric/build/modpack-{profile}-results.xml")
    names = set(read_cases(path))
    if not required <= names:
        raise ValueError(f"Missing {profile} cases: {sorted(required - names)}")
    if path.stat().st_mtime < latest_source:
        raise ValueError(f"Evidence predates the current source: {path}; rerun the native scenario")
    log = root / ("neoforge/build/gametest/logs/latest.log" if profile == "neoforge" else
                  "fabric/build/gametest/logs/latest.log" if profile == "base" else f"fabric/build/modpack-{profile}/logs/latest.log")
    if "LLM call attempt" in log.read_text(encoding="utf-8", errors="replace"):
        raise ValueError(f"Unexpected model transport attempt in native evidence: {log}")
    reports[profile] = {"executions": len(names), "cases": sorted(names), "xml_sha256": hashlib.sha256(path.read_bytes()).hexdigest()}

artifacts = {}
manifest = root / "build/release-artifacts.sha256"
for line in manifest.read_text().splitlines():
    checksum, name = line.split()
    loader = "fabric" if "fabric" in name else "neoforge"
    path = root / loader / "build/libs" / name
    if hashlib.sha256(path.read_bytes()).hexdigest() != checksum:
        raise ValueError("Artifact hash mismatch: " + name)
    if f"-{version}.jar" not in name:
        raise ValueError("Wrong artifact version: " + name)
    artifacts[name] = {"sha256": checksum, "bytes": path.stat().st_size}
if len(artifacts) != 2:
    raise ValueError("Expected exactly two loader artifacts")

result = {"version": version, "source_sha256_normalized": digest.hexdigest(), "source_files": len(sources),
          "component_tests": units, "component_suites": len(unit_paths), "native": reports,
          "unique_native_cases": len(set().union(*(set(r["cases"]) for r in reports.values()))),
          "native_executions": sum(r["executions"] for r in reports.values()),
          "artifacts": artifacts, "modpack_lock_sha256": hashlib.sha256((root / "fabric/src/modtest/resources/modpack-lock.json").read_bytes()).hexdigest(),
          "model_evaluation": "No paid or real model capability evaluation; paused native harness and local protocol fixtures only",
          "scope": "Fabric native learning and real-mod matrix; native lifecycle on both loaders; NeoForge remains experimental"}
output = root / f"build/validation-{version}.json"
output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({"output": str(output), "tests": units, "suites": len(unit_paths),
                  "native_executions": result["native_executions"], "unique_native_cases": result["unique_native_cases"]}, ensure_ascii=False))
