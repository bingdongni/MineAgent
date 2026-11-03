"""Fetch only pinned public test dependencies; validate bytes before exposing them to the loader."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import urllib.request
import io
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument("--profile", choices=("storage", "industry", "combined"), default="combined")
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
lock_path = root / "fabric/src/modtest/resources/modpack-lock.json"
lock = json.loads(lock_path.read_text(encoding="utf-8"))
destination = root / "fabric/build/real-mods" / args.profile
destination.mkdir(parents=True, exist_ok=True)
selected = set(lock["profiles"][args.profile])
for mod in lock["mods"]:
    if mod["id"] not in selected:
        continue
    filename = mod["filename"]
    if Path(filename).name != filename or not mod["url"].startswith("https://cdn.modrinth.com/"):
        raise ValueError("Unsafe test dependency location")
    path = destination / filename
    if not path.exists() or hashlib.sha512(path.read_bytes()).hexdigest() != mod["sha512"]:
        request = urllib.request.Request(mod["url"], headers={"User-Agent": "MineAgent-test-harness/1.0 (github.com/bingdongni/MineAgent)"})
        data = urllib.request.urlopen(request, timeout=120).read()
        if hashlib.sha512(data).hexdigest() != mod["sha512"]:
            raise ValueError("Dependency checksum mismatch: " + filename)
        temporary = path.with_suffix(".download")
        temporary.write_bytes(data)
        os.replace(temporary, path)
    print(f"Verified {mod['id']} {mod['version']}")
unexpected = set(p.name for p in destination.glob("*.jar")) - {
    m["filename"] for m in lock["mods"] if m["id"] in selected
}
if unexpected:
    raise ValueError("Unexpected jars in isolated test directory: " + repr(sorted(unexpected)))

# Loom's development classpath resolves nested mods separately from a production loader.
# Derive them from the already authenticated parent bytes, retaining the complete origin chain.
nested = []
seen = {}
def extract_nested(data, root_name, chain, depth=0):
    if depth > 8:
        raise ValueError("Nested dependency depth exceeds the test budget")
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        if "fabric.mod.json" not in archive.namelist():
            return
        metadata = json.loads(archive.read("fabric.mod.json"))
        for child in metadata.get("jars", []):
            entry = child["file"]
            info = archive.getinfo(entry)
            if info.file_size > 64 * 1024 * 1024 or len(nested) >= 64:
                raise ValueError("Nested dependency exceeds the test budget")
            content = archive.read(entry)
            with zipfile.ZipFile(io.BytesIO(content)) as child_archive:
                child_meta = json.loads(child_archive.read("fabric.mod.json")) if "fabric.mod.json" in child_archive.namelist() else {}
            mod_id = child_meta.get("id")
            if mod_id in selected or mod_id in lock.get("provided_mods", []):
                continue
            digest = hashlib.sha512(content).hexdigest()
            identity = mod_id or digest
            if identity in seen:
                if seen[identity] != digest:
                    raise ValueError("Conflicting embedded dependency: " + identity)
                continue
            seen[identity] = digest
            target = Path("nested") / (digest[:12] + "-" + Path(entry).name)
            (destination / target).parent.mkdir(parents=True, exist_ok=True)
            (destination / target).write_bytes(content)
            nested.append({"id": mod_id, "path": target.as_posix(), "sha512": digest,
                           "root": root_name, "chain": chain + [entry]})
            extract_nested(content, root_name, chain + [entry], depth + 1)
for mod in lock["mods"]:
    if mod["id"] in selected:
        extract_nested((destination / mod["filename"]).read_bytes(), mod["filename"], [])
(destination / "nested-manifest.json").write_text(json.dumps(nested, indent=2) + "\n", encoding="utf-8")
print("Verified embedded dependencies:", len(nested))
print("Profile ready:", args.profile, "Fabric Loader", lock["fabric_loader"])
