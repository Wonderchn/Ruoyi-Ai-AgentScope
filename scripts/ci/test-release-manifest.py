#!/usr/bin/env python3
import copy
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("release_manifest", Path(__file__).with_name("release-manifest.py"))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)
SOURCE = "a" * 40
REPOSITORY = "Wonderchn/Ruoyi-Ai-AgentScope"


class ManifestContractTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.digests = Path(self.temporary.name) / "digests"
        self.digests.mkdir()
        for name in release.IMAGES:
            (self.digests / (name + ".txt")).write_text(f"ghcr.io/wonderchn/{name}@sha256:{'b' * 64}\nsource={SOURCE}\n", encoding="utf-8")

    def manifest(self):
        return release.create(self.digests, SOURCE, REPOSITORY)

    def test_three_immutable_images_and_modes_round_trip(self):
        manifest = self.manifest()
        self.assertEqual(manifest, release.validate(json.loads(json.dumps(manifest)), SOURCE, REPOSITORY))
        self.assertEqual({"embedded", "compatibility"}, set(manifest["runtimeModes"]))

    def test_missing_or_extra_record_rejected(self):
        for missing in release.IMAGES:
            file = self.digests / (missing + ".txt")
            contents = file.read_text()
            file.unlink()
            with self.assertRaises(ValueError): self.manifest()
            file.write_text(contents)
        (self.digests / "unknown.txt").write_text("unexpected")
        with self.assertRaises(ValueError): self.manifest()

    def test_record_source_and_line_count_rejected(self):
        file = self.digests / (sorted(release.IMAGES)[0] + ".txt")
        contents = file.read_text()
        for bad in (contents.replace(SOURCE, "c" * 40), contents + "extra\n", contents.splitlines()[0]):
            file.write_text(bad)
            with self.assertRaises(ValueError): self.manifest()

    def test_mutable_wrong_owner_wrong_image_malformed_digest_rejected(self):
        manifest = self.manifest()
        name = sorted(release.IMAGES)[0]
        original = manifest["images"][name]
        for bad in (original.split("@")[0] + ":latest", original.replace("wonderchn", "other"), original.replace(name, "other"), original[:-1], original + "0", original.replace("sha256:", "sha512:")):
            manifest["images"][name] = bad
            with self.assertRaises(ValueError): release.validate(manifest, SOURCE, REPOSITORY)

    def test_changed_source_repository_image_set_or_compatibility_rejected(self):
        original = self.manifest()
        for key, bad in (("sourceCommit", "d" * 40), ("sourceRepository", "other/repo"), ("images", {}), ("schemaCompatibility", {"unifiedPlatform": "wrong"}), ("notVerified", []), ("runtimeModes", {})):
            manifest = copy.deepcopy(original)
            manifest[key] = bad
            with self.assertRaises(ValueError): release.validate(manifest, SOURCE, REPOSITORY)

    def test_non_full_sha_rejected(self):
        for source in ("main", SOURCE[:7], SOURCE.upper(), "g" * 40):
            with self.assertRaises(ValueError): release.create(self.digests, source, REPOSITORY)

    def test_unified_or_shipped_migration_drift_rejected(self):
        root = Path(self.temporary.name) / "repository"
        for path in ("docs/release-compatibility.json", "scripts/ci/unified-schema-contract.json"):
            target = root / path
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(release.REPO_ROOT / path, target)
        with self.assertRaises(ValueError): release.compatibility(root)
        path = root / "docs/release-compatibility.json"
        data = json.loads(path.read_text())
        data["schema"]["unifiedPlatform"]["versionList"] = [1, 27]
        path.write_text(json.dumps(data))
        with self.assertRaises(ValueError): release.compatibility(root)


if __name__ == "__main__":
    unittest.main()
