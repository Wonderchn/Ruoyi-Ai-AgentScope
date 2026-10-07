#!/usr/bin/env python3
"""Bind exactly three immutable images to their source and schema contracts."""
import argparse
import json
from pathlib import Path
import re

IMAGES = frozenset({"ruoyi-ai-agentscope-platform", "ruoyi-ai-agentscope-ai", "ruoyi-ai-agentscope-web"})
REPO_ROOT = Path(__file__).resolve().parents[2]


def require(condition, message):
    if not condition:
        raise ValueError(message)


def identity(source, repository):
    require(re.fullmatch(r"[0-9a-f]{40}", source), "Source must be a full Git SHA")
    require(repository == "Wonderchn/Ruoyi-Ai-AgentScope", "Unexpected source repository")


def compatibility(root=REPO_ROOT):
    data = json.loads((root / "docs/release-compatibility.json").read_text(encoding="utf-8"))
    contract = json.loads((root / "scripts/ci/unified-schema-contract.json").read_text(encoding="utf-8"))
    require(data["schema"]["unifiedPlatform"]["versionList"] == contract["platformVersions"], "Unified version contract drift")
    require(data["schema"]["unifiedPlatform"]["migrations"] == len(contract["platformVersions"]), "Unified migration count drift")
    for name, directory, key in (
        ("unifiedPlatform", "services/platform/docs/script/sql/postgres", "versionList"),
        ("ai", "services/ai/resources/database/postgres/migrations", "frozenVersionList"),
    ):
        versions = sorted(int(re.match(r"V(\d+)__", p.name).group(1)) for p in (root / directory).glob("V*__*.sql"))
        require(versions == data["schema"][name][key], f"Shipped migration versions differ: {name}")
    require(data["schema"]["platform"]["frozenVersionList"] == list(range(1, 7)), "Legacy platform contract drift")
    fields = ("schemaCompatibility", "contractCompatibility", "verifiedUpgradePaths", "notVerified")
    return {**{key: data["compatibility"][key] for key in fields}, "runtimeModes": data["runtimeModes"]}


def validate(manifest, source, repository, root=REPO_ROOT):
    identity(source, repository)
    require(manifest["sourceCommit"] == source, "Source commit mismatch")
    require(manifest["sourceRepository"] == repository, "Source repository mismatch")
    require(set(manifest["images"]) == IMAGES, "Expected exactly platform, AI and web")
    for name, reference in manifest["images"].items():
        require(re.fullmatch(rf"ghcr\.io/wonderchn/{name}@sha256:[0-9a-f]{{64}}", reference), f"Invalid immutable reference: {name}")
    for key, expected in compatibility(root).items():
        require(manifest.get(key) == expected, f"Compatibility field mismatch: {key}")
    return manifest


def create(digests, source, repository, root=REPO_ROOT):
    identity(source, repository)
    files = list(Path(digests).iterdir())
    require({p.name for p in files} == {name + ".txt" for name in IMAGES}, "Digest file set differs")
    images = {}
    for file in files:
        require(file.is_file() and not file.is_symlink(), "Digest record must be a regular file")
        lines = file.read_text(encoding="utf-8").splitlines()
        require(len(lines) == 2, "Digest record must contain exactly two lines")
        require(lines[1] == "source=" + source, "Digest record source mismatch")
        images[file.stem] = lines[0]
    return validate({"sourceRepository": repository, "sourceCommit": source, "images": images, **compatibility(root)}, source, repository, root)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("create", "validate"))
    parser.add_argument("--source", required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--digests", type=Path)
    parser.add_argument("--output", type=Path, default=Path("release-manifest.json"))
    args = parser.parse_args()
    if args.mode == "create":
        require(args.digests is not None, "--digests is required")
        args.output.write_text(json.dumps(create(args.digests, args.source, args.repository), indent=2) + "\n", encoding="utf-8")
    else:
        manifest = validate(json.loads(args.output.read_text(encoding="utf-8")), args.source, args.repository)
        for name in sorted(IMAGES):
            print(manifest["images"][name])


if __name__ == "__main__":
    main()
