#!/usr/bin/env python3
"""Exercise delivered images and promote only independently verified immutable digests."""
import argparse
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile

spec = importlib.util.spec_from_file_location("release_manifest", Path(__file__).with_name("release-manifest.py"))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


def docker(*args, capture=True):
    return subprocess.run(["docker", *args], check=True, text=True, capture_output=capture, timeout=600).stdout


def fetch(url):
    try:
        with urllib.request.urlopen(url, timeout=5) as response:
            return response.status, response.headers, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.headers, error.read()


def verify_web(reference):
    """Use a synthetic upstream to prove routing, without accepting mock business behavior."""
    owner = "release-smoke-" + uuid.uuid4().hex[:12]
    network, upstream, frontend = owner + "-net", owner + "-platform", owner + "-web"
    created = []
    try:
        with tempfile.TemporaryDirectory() as temporary:
            config = Path(temporary) / "platform.conf"
            config.write_text('server { listen 6039; location / { default_type application/json; return 200 \'{"upstream":"platform-stub"}\'; } }\n', encoding="utf-8")
            docker("network", "create", "--label", "release-smoke-owner=" + owner, network)
            created.append(("network", network))
            docker("run", "--detach", "--name", upstream, "--network", network, "--network-alias", "platform", "--label", "release-smoke-owner=" + owner,
                   "--mount", f"type=bind,source={config},target=/etc/nginx/conf.d/default.conf,readonly", "nginx:1.27-alpine")
            created.append(("container", upstream))
            docker("run", "--detach", "--name", frontend, "--network", network, "--label", "release-smoke-owner=" + owner, "--publish", "127.0.0.1::80", reference)
            created.append(("container", frontend))
            docker("exec", frontend, "nginx", "-t")
            state = json.loads(docker("inspect", frontend))[0]
            port = state["NetworkSettings"]["Ports"]["80/tcp"][0]["HostPort"]
            origin = "http://127.0.0.1:" + port
            for attempt in range(30):
                try:
                    if fetch(origin + "/")[0] == 200:
                        break
                except (OSError, urllib.error.URLError):
                    pass
                time.sleep(1)
            pages = {}
            for prefix in ("/", "/admin/"):
                status, headers, body = fetch(origin + prefix)
                release.require(status == 200 and "text/html" in headers.get("Content-Type", ""), "Missing delivered application: " + prefix)
                text = body.decode("utf-8")
                assets = re.findall(r'(?:src|href)=["\']([^"\']+\.(?:js|css|mjs))(?:["\'])', text)
                release.require(any(asset.endswith(".js") for asset in assets), "Application entry script missing: " + prefix)
                for asset in assets:
                    parsed = urllib.parse.urlsplit(asset)
                    release.require(not parsed.scheme and not parsed.netloc, "Entry assets must stay on origin")
                    release.require(asset.startswith(prefix), "Application asset base mismatch: " + asset)
                    code, asset_headers, asset_body = fetch(urllib.parse.urljoin(origin + prefix, asset))
                    mime = asset_headers.get("Content-Type", "")
                    release.require(code == 200 and bool(asset_body) and "text/html" not in mime, "Missing or HTML-fallback asset: " + asset)
                    release.require("javascript" in mime if asset.endswith((".js", ".mjs")) else "text/css" in mime, "Wrong asset MIME: " + asset)
                deep = "/rag" if prefix == "/" else "/admin/ai/knowledge"
                code, _, deep_body = fetch(origin + deep)
                release.require(code == 200 and deep_body == body, "Wrong SPA fallback: " + deep)
                pages[prefix] = {"entrySha256": hashlib.sha256(body).hexdigest(), "assets": assets, "deepLink": deep}
            release.require(pages["/"]["entrySha256"] != pages["/admin/"]["entrySha256"], "Admin and workbench must be separate builds")
            for path in ("/api/probe", "/auth/probe", "/system/probe", "/monitor/probe"):
                code, headers, body = fetch(origin + path)
                release.require(code == 200 and "application/json" in headers.get("Content-Type", "") and json.loads(body)["upstream"] == "platform-stub", "Public proxy missing: " + path)
            for path in ("/internal/platform/v1/authorization/check", "/actuator/health"):
                release.require(fetch(origin + path)[0] == 404, "Internal route exposed: " + path)
            return {"pages": pages, "proxy": "synthetic upstream only; business authentication is covered by separate runtime CI", "internalRoutes": "404"}
    except Exception:
        for name in (upstream, frontend):
            subprocess.run(["docker", "logs", "--tail", "30", name], check=False, timeout=20)
        raise
    finally:
        for kind, name in reversed(created):
            subprocess.run(["docker", kind, "rm", *( ["--force"] if kind == "container" else []), name], check=False, capture_output=True, timeout=30)


def verify_platform(reference):
    config = json.loads(docker("image", "inspect", reference))[0]["Config"]
    release.require("--spring.profiles.active=prod" not in config.get("Entrypoint", []), "Hard-coded profile prevents embedded deployment")
    release.require(config["User"] == "10001", "Platform must retain its non-root runtime")
    name = "release-jar-" + uuid.uuid4().hex[:12]
    docker("create", "--name", name, reference)
    try:
        with tempfile.TemporaryDirectory() as temporary:
            jar = Path(temporary) / "application.jar"
            docker("cp", name + ":/app/application.jar", str(jar))
            with zipfile.ZipFile(jar) as archive:
                entries = archive.namelist()
                for artifact in ("ruoyi-chat", "ruoyi-aiflow", "ruoyi-generator"):
                    release.require(not any(path.startswith("BOOT-INF/lib/" + artifact + "-") for path in entries), "Retired module packaged: " + artifact)
                libraries = [path for path in entries if path.startswith("BOOT-INF/lib/ruoyi-ai-web-") and path.endswith(".jar")]
                release.require(len(libraries) == 1, "Embedded AI web module missing")
                with zipfile.ZipFile(io.BytesIO(archive.read(libraries[0]))) as embedded:
                    release.require("org/ruoyi/aiweb/embedded/AiEmbeddedRunConfiguration.class" in embedded.namelist(), "Embedded runtime wiring missing")
                release.require("BOOT-INF/classes/application-embedded.yml" in entries, "Embedded profile missing")
            return {"jarSha256": hashlib.sha256(jar.read_bytes()).hexdigest(), "retiredModules": "absent", "embeddedProfile": "present", "runtimeUser": config["User"]}
    finally:
        docker("rm", name)


def current_main(repository):
    token = os.environ["GH_TOKEN"]
    request = urllib.request.Request(f"https://api.github.com/repos/{repository}/git/ref/heads/main", headers={"Authorization": "Bearer " + token, "Accept": "application/vnd.github+json", "User-Agent": "release-image-verifier"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)["object"]["sha"]


def promote(manifest, source, repository):
    result = {"sourceCommit": source, "promoted": {}, "superseded": False}
    for name, reference in sorted(manifest["images"].items()):
        if current_main(repository) != source:
            result["superseded"] = True
            return result
        latest = reference.split("@")[0] + ":latest"
        docker("buildx", "imagetools", "create", "--tag", latest, reference)
        inspected = docker("buildx", "imagetools", "inspect", latest)
        digest = re.search(r"^Digest:\s+(sha256:[0-9a-f]{64})\s*$", inspected, re.MULTILINE)
        release.require(digest and digest.group(1) == reference.split("@")[1], "Latest digest differs: " + name)
        result["promoted"][name] = {"tag": latest, "digest": digest.group(1)}
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--web")
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--source")
    parser.add_argument("--repository")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--promote", action="store_true")
    args = parser.parse_args()
    if args.web:
        release.require(not args.promote and args.manifest is None, "Web check cannot publish images")
        result = verify_web(args.web)
    else:
        release.require(args.manifest is not None and args.source and args.repository, "Manifest, source and repository required")
        manifest = release.validate(json.loads(args.manifest.read_text(encoding="utf-8")), args.source, args.repository)
        if args.promote:
            result = promote(manifest, args.source, args.repository)
        else:
            result = {"sourceCommit": args.source, "images": {}}
            for name, reference in sorted(manifest["images"].items()):
                docker("pull", "--platform", "linux/amd64", reference, capture=False)
                config = json.loads(docker("image", "inspect", reference))[0]["Config"]
                labels = config.get("Labels", {})
                release.require(labels.get("org.opencontainers.image.revision") == args.source, "OCI source SHA mismatch")
                release.require(labels.get("org.opencontainers.image.source") == "https://github.com/" + args.repository, "OCI repository mismatch")
                result["images"][name] = {"reference": reference, "sourceVerified": True}
                if name.endswith("-platform"):
                    result["images"][name]["contents"] = verify_platform(reference)
                if name.endswith("-web"):
                    result["images"][name]["contents"] = verify_web(reference)
    encoded = json.dumps(result, indent=2) + "\n"
    if args.output:
        args.output.write_text(encoded, encoding="utf-8")
    print(encoded)


if __name__ == "__main__":
    main()
