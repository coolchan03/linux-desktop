#!/usr/bin/env python3
"""Builds the offline desktop pack that is embedded in the LXDesk APK.

The pack is an uncompressed tar holding a flat apt repository (``Packages`` + ``debs/``) with every
package needed to install the XFCE desktop core on top of the bootstrap that ships in the same APK:

* The dependency closure of the core packages is resolved against Termux's current package indexes.
  Packages the bootstrap already provides at a satisfying version are left out.
* Every .deb is downloaded over HTTPS and checked against the SHA-256 from the index.
* Each .deb is rewritten for the app's package name ahead of time (same job as the on-device
  ``lxdesk-relocate-debs`` apt hook, which then skips these packages), so the phone does not have to
  unpack and repack every package during the first launch.

Downloads and relocated packages are cached, so rebuilds only fetch what changed in the repositories.
Only the Python standard library is used.
"""

import argparse
import concurrent.futures
import gzip
import hashlib
import io
import lzma
import os
import re
import sys
import tarfile
import time
import urllib.error
import urllib.request
import zipfile
from collections import deque

OLD_PKG = "com.termux"
# Bump when relocate_deb() output changes, so cached relocated packages are rebuilt.
RELOCATOR_VERSION = "1"
RELOCATED_FIELD = "LXDesk-Relocated"
DEFAULT_REPOS = [
    "https://packages-cf.termux.dev/apt/termux-main stable main",
    "https://packages-cf.termux.dev/apt/termux-x11 x11 main",
]


def log(message):
    print("lxdesk-desktop-pack: " + message, file=sys.stderr, flush=True)


# ---- Debian version comparison (dpkg semantics) ----

def _order(c):
    if c == "~":
        return -1
    if c.isdigit():
        return 0
    if c.isalpha():
        return ord(c)
    return ord(c) + 256


def _compare_part(a, b):
    i = j = 0
    while i < len(a) or j < len(b):
        while (i < len(a) and not a[i].isdigit()) or (j < len(b) and not b[j].isdigit()):
            ac = _order(a[i]) if i < len(a) else 0
            bc = _order(b[j]) if j < len(b) else 0
            if ac != bc:
                return ac - bc
            i += 1
            j += 1
        while i < len(a) and a[i] == "0":
            i += 1
        while j < len(b) and b[j] == "0":
            j += 1
        first_diff = 0
        while i < len(a) and a[i].isdigit() and j < len(b) and b[j].isdigit():
            if not first_diff:
                first_diff = ord(a[i]) - ord(b[j])
            i += 1
            j += 1
        if i < len(a) and a[i].isdigit():
            return 1
        if j < len(b) and b[j].isdigit():
            return -1
        if first_diff:
            return first_diff
    return 0


def _split_version(version):
    epoch, _, rest = version.rpartition(":") if ":" in version else ("0", "", version)
    upstream, _, revision = rest.rpartition("-") if "-" in rest else (rest, "", "")
    return int(epoch or 0), upstream, revision


def compare_versions(a, b):
    ea, ua, ra = _split_version(a)
    eb, ub, rb = _split_version(b)
    if ea != eb:
        return ea - eb
    return _compare_part(ua, ub) or _compare_part(ra, rb)


def satisfies(version, op, wanted):
    if not op:
        return True
    c = compare_versions(version, wanted)
    return {"<<": c < 0, "<=": c <= 0, "=": c == 0, ">=": c >= 0, ">>": c > 0,
            "<": c <= 0, ">": c >= 0}[op]


# ---- Control data parsing ----

def parse_stanzas(text):
    """Parses deb822 stanzas (Packages, dpkg status) into ordered dicts of field -> value."""
    stanzas, current, last = [], {}, None
    for line in text.splitlines():
        if not line.strip():
            if current:
                stanzas.append(current)
            current, last = {}, None
        elif line[0] in " \t" and last:
            current[last] += "\n" + line
        else:
            key, _, value = line.partition(":")
            last = key.strip()
            current[last] = value.strip()
    if current:
        stanzas.append(current)
    return stanzas


DEP_RE = re.compile(r"^\s*([A-Za-z0-9.+\-]+)(?::[A-Za-z0-9\-]+)?\s*(?:\(\s*(<<|<=|=|>=|>>|<|>)\s*([^)\s]+)\s*\))?\s*$")


def parse_relations(value):
    """'a (>= 1) | b, c' -> [[('a', '>=', '1'), ('b', None, None)], [('c', None, None)]]"""
    groups = []
    for group in (value or "").split(","):
        if not group.strip():
            continue
        alternatives = []
        for alternative in group.split("|"):
            alternative = re.sub(r"\[.*?\]|<.*?>", "", alternative)  # architecture and build-profile qualifiers
            m = DEP_RE.match(alternative)
            if not m:
                raise ValueError("Cannot parse dependency: " + alternative)
            alternatives.append((m.group(1), m.group(2), m.group(3)))
        groups.append(alternatives)
    return groups


def provides_of(stanza):
    return [alts[0] for alts in parse_relations(stanza.get("Provides", ""))]


# ---- Downloading ----

def fetch(url, retries=4):
    delay = 2
    for attempt in range(retries + 1):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "lxdesk-build"}), timeout=120) as response:
                return response.read()
        except urllib.error.HTTPError as e:
            if e.code == 404 or attempt == retries:
                raise
        except (urllib.error.URLError, OSError):
            if attempt == retries:
                raise
        log("retrying " + url)
        time.sleep(delay)
        delay *= 2


def load_index(base, dist, component, arch):
    """Returns the Packages stanzas of one repository, verified against its Release file when possible."""
    release_hashes = {}
    try:
        release = fetch("%s/dists/%s/Release" % (base, dist)).decode("utf-8", "replace")
        for stanza in parse_stanzas(release):
            for line in stanza.get("SHA256", "").splitlines():
                parts = line.split()
                if len(parts) == 3:
                    release_hashes[parts[2]] = parts[0]
    except urllib.error.HTTPError:
        log("warning: no Release file for %s %s" % (base, dist))
    for suffix, decode in ((".xz", lzma.decompress), (".gz", gzip.decompress), ("", lambda d: d)):
        rel_path = "%s/binary-%s/Packages%s" % (component, arch, suffix)
        try:
            data = fetch("%s/dists/%s/%s" % (base, dist, rel_path))
        except urllib.error.HTTPError as e:
            if e.code == 404:
                continue
            raise
        expected = release_hashes.get(rel_path)
        if expected and hashlib.sha256(data).hexdigest() != expected:
            raise RuntimeError("Checksum mismatch for %s/dists/%s/%s" % (base, dist, rel_path))
        stanzas = parse_stanzas(decode(data).decode("utf-8", "replace"))
        for stanza in stanzas:
            stanza["_base"] = base
        log("%s %s: %d packages" % (base, dist, len(stanzas)))
        return stanzas
    raise RuntimeError("No Packages index at %s/dists/%s/%s/binary-%s" % (base, dist, component, arch))


def read_bootstrap_status(path):
    """Installed packages of the bootstrap zip (prefix-relative paths, as shipped by termux-packages)."""
    if not path or not os.path.isfile(path):
        return []
    with zipfile.ZipFile(path) as z:
        try:
            text = z.read("var/lib/dpkg/status").decode("utf-8", "replace")
        except KeyError:
            log("warning: %s has no var/lib/dpkg/status; bundling the full dependency closure" % path)
            return []
    return [s for s in parse_stanzas(text) if s.get("Status", "").endswith(" installed")]


# ---- Dependency resolution ----

class Resolver:
    def __init__(self, index_stanzas, installed_stanzas):
        self.index = {}
        for stanza in index_stanzas:
            name = stanza["Package"]
            if name not in self.index or compare_versions(stanza["Version"], self.index[name]["Version"]) > 0:
                self.index[name] = stanza
        self.index_providers = {}
        for name, stanza in self.index.items():
            for virtual, _, _ in provides_of(stanza):
                self.index_providers.setdefault(virtual, []).append(name)
        self.installed = {s["Package"]: s for s in installed_stanzas}
        self.installed_providers = {}
        for name, stanza in self.installed.items():
            for virtual, _, _ in provides_of(stanza):
                self.installed_providers.setdefault(virtual, set()).add(name)
        self.selected = {}
        self.queue = deque()

    def _effective(self, name):
        """Version of a package after the pack is installed: bundled, else installed, else None."""
        if name in self.selected:
            return self.selected[name]
        return self.installed.get(name)

    def _satisfied(self, name, op, version):
        stanza = self._effective(name)
        if stanza and satisfies(stanza["Version"], op, version):
            return True
        if op:
            return False
        # An unversioned dependency can also be met by a package that provides it.
        return bool(self.installed_providers.get(name)) or \
            any(name == virtual for s in self.selected.values() for virtual, _, _ in provides_of(s))

    def select(self, name, reason):
        if name in self.selected:
            return
        stanza = self.index[name]
        self.selected[name] = stanza
        self.queue.append((name, reason))

    def require(self, groups, required, owner):
        for alternatives in groups:
            if any(self._satisfied(*alt) for alt in alternatives):
                continue
            chosen = None
            for name, op, version in alternatives:
                if name in self.index and satisfies(self.index[name]["Version"], op, version):
                    chosen = name
                elif not op and self.index_providers.get(name):
                    chosen = sorted(self.index_providers[name])[0]
                if chosen:
                    break
            if chosen:
                self.select(chosen, owner)
            elif required:
                raise RuntimeError("%s depends on %s, which is not available in the repositories"
                                   % (owner, " | ".join(n + (" (%s %s)" % (o, v) if o else "") for n, o, v in alternatives)))

    def _upgrade_if_needed(self, name, why):
        if name in self.selected or name not in self.index:
            return False
        self.select(name, why)
        return True

    def run(self, roots, optional_roots, with_recommends):
        for name in roots:
            if name not in self.index:
                raise RuntimeError("Core package %s is not available in the repositories" % name)
            if name not in self.installed:
                self.select(name, "core")
        for name in optional_roots:
            if name in self.index and name not in self.installed:
                self.select(name, "optional")
            elif name not in self.index:
                log("optional package %s is not available for this architecture; skipping" % name)
        while self.queue:
            name, _ = self.queue.popleft()
            stanza = self.selected[name]
            self.require(parse_relations(stanza.get("Pre-Depends", "")), True, name)
            self.require(parse_relations(stanza.get("Depends", "")), True, name)
            if with_recommends:
                self.require(parse_relations(stanza.get("Recommends", "")), False, name)
            # Installed packages that the new version breaks, or that pin the old version, must be upgraded too:
            # the offline install can only use what is in the pack.
            for group in parse_relations(stanza.get("Breaks", "")) + parse_relations(stanza.get("Conflicts", "")):
                for other, op, version in group:
                    if other != name and other in self.installed and other not in self.selected \
                            and satisfies(self.installed[other]["Version"], op, version):
                        if not self._upgrade_if_needed(other, name + " breaks the bootstrap version"):
                            raise RuntimeError("%s breaks installed %s and no replacement is available" % (name, other))
            if name in self.installed:
                for other, other_stanza in self.installed.items():
                    if other in self.selected:
                        continue
                    for field in ("Pre-Depends", "Depends", "Breaks", "Conflicts"):
                        for group in parse_relations(other_stanza.get(field, "")):
                            for dep, op, version in group:
                                if dep != name or not op:
                                    continue
                                ok = satisfies(stanza["Version"], op, version)
                                if (field in ("Pre-Depends", "Depends") and not ok and len(group) == 1) or \
                                        (field in ("Breaks", "Conflicts") and ok):
                                    self._upgrade_if_needed(other, "follows the upgrade of " + name)
        return self.selected


# ---- Relocation ----

def relocation_pairs(new_pkg):
    pairs = []
    for root in ("/data/data/", "/data/user/0/"):
        pairs.append(((root + OLD_PKG).encode(), (root + new_pkg).encode()))
    return pairs


def relocate_bytes(data, pairs):
    for old, new in pairs:
        data = data.replace(old, new)
    return data


def relocate_member_name(name, new_pkg):
    for root in ("data/data/", "data/user/0/"):
        name = name.replace(root + OLD_PKG + "/", root + new_pkg + "/")
        if name.endswith(root + OLD_PKG):
            name = name[: -len(OLD_PKG)] + new_pkg
    return name


def read_ar(data):
    if not data.startswith(b"!<arch>\n"):
        raise ValueError("not an ar archive")
    members, pos = [], 8
    while pos < len(data):
        header = data[pos:pos + 60]
        if len(header) < 60:
            break
        size = int(header[48:58].decode().strip())
        members.append((header, data[pos + 60:pos + 60 + size]))
        pos += 60 + size + (size & 1)
    return members


def write_ar(members):
    out = io.BytesIO()
    out.write(b"!<arch>\n")
    for header, body in members:
        out.write(header[:48] + ("%-10d" % len(body)).encode() + b"`\n")
        out.write(body)
        if len(body) & 1:
            out.write(b"\n")
    return out.getvalue()


def _decompress(name, data):
    if name.endswith(".xz"):
        return lzma.decompress(data)
    if name.endswith(".gz"):
        return gzip.decompress(data)
    if name.endswith(".tar"):
        return data
    raise ValueError("unsupported compression in " + name)


def _compress(name, data):
    if name.endswith(".xz"):
        return lzma.compress(data, format=lzma.FORMAT_XZ, check=lzma.CHECK_CRC64, preset=6)
    if name.endswith(".gz"):
        return gzip.compress(data, 9, mtime=0)
    return data


def _rewrite_tar(raw, transform):
    src = tarfile.open(fileobj=io.BytesIO(raw), mode="r:")
    out = io.BytesIO()
    dst = tarfile.open(fileobj=out, mode="w:", format=tarfile.GNU_FORMAT)
    for info in src.getmembers():
        content = src.extractfile(info).read() if info.isreg() else None
        info, content = transform(info, content)
        if content is not None:
            info.size = len(content)
            dst.addfile(info, io.BytesIO(content))
        else:
            dst.addfile(info)
    dst.close()
    return out.getvalue()


def relocate_deb(src_path, dst_path, new_pkg):
    """Rewrites one .deb for /data/data/<new_pkg>. Mirrors assets/desktop/lxdesk-relocate-debs."""
    pairs = relocation_pairs(new_pkg)
    with open(src_path, "rb") as f:
        members = read_ar(f.read())
    names = [h[:16].decode().strip().rstrip("/") for h, _ in members]
    if not names or names[0] != "debian-binary":
        raise ValueError("unexpected .deb layout: %s" % names)
    data_index = next(i for i, n in enumerate(names) if n.startswith("data.tar"))
    control_index = next(i for i, n in enumerate(names) if n.startswith("control.tar"))

    md5 = {}

    def data_transform(info, content):
        info.name = relocate_member_name(info.name, new_pkg)
        if info.islnk():
            info.linkname = relocate_member_name(info.linkname, new_pkg)
        elif info.issym():
            info.linkname = relocate_bytes(info.linkname.encode("utf-8", "surrogateescape"), pairs).decode("utf-8", "surrogateescape")
        if content is not None:
            content = relocate_bytes(content, pairs)
            md5[info.name[2:] if info.name.startswith("./") else info.name] = hashlib.md5(content).hexdigest()
        return info, content

    data_raw = _rewrite_tar(_decompress(names[data_index], members[data_index][1]), data_transform)
    leftovers = [p for p, _ in pairs if p in data_raw]
    if leftovers:
        raise RuntimeError("relocation left %s in %s" % (leftovers[0].decode(), src_path))

    def control_transform(info, content):
        base = os.path.basename(info.name)
        if content is None:
            return info, content
        if base == "md5sums":
            listed = []
            for line in content.decode("utf-8", "surrogateescape").splitlines():
                parts = line.split(None, 1)
                if len(parts) == 2:
                    listed.append(relocate_member_name(parts[1], new_pkg))
            content = "".join("%s  %s\n" % (md5[p], p) for p in listed if p in md5).encode("utf-8", "surrogateescape")
        else:
            content = relocate_bytes(content, pairs)
            if base == "control":
                text = content.decode("utf-8", "surrogateescape").rstrip("\n")
                content = (text + "\n%s: %s\n" % (RELOCATED_FIELD, new_pkg)).encode("utf-8", "surrogateescape")
        return info, content

    control_raw = _rewrite_tar(_decompress(names[control_index], members[control_index][1]), control_transform)

    members[data_index] = (members[data_index][0], _compress(names[data_index], data_raw))
    members[control_index] = (members[control_index][0], _compress(names[control_index], control_raw))
    tmp = dst_path + ".tmp"
    with open(tmp, "wb") as f:
        f.write(write_ar(members))
    os.replace(tmp, dst_path)


def _relocate_job(args):
    src, dst, new_pkg = args
    if not os.path.exists(dst):
        relocate_deb(src, dst, new_pkg)
    return dst


# ---- Pack assembly ----

def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def download_deb(stanza, cache):
    expected = stanza["SHA256"]
    path = os.path.join(cache, "orig-" + expected + ".deb")
    if os.path.exists(path) and sha256_file(path) == expected:
        return path
    data = fetch(stanza["_base"] + "/" + stanza["Filename"])
    if hashlib.sha256(data).hexdigest() != expected:
        raise RuntimeError("Checksum mismatch for " + stanza["Filename"])
    tmp = path + ".tmp"
    with open(tmp, "wb") as f:
        f.write(data)
    os.replace(tmp, path)
    return path


def deb_file_name(stanza):
    return "%s_%s_%s.deb" % (stanza["Package"], stanza["Version"].replace(":", "%3a"), stanza["Architecture"])


PACKAGES_FIELDS = ("Package", "Version", "Architecture", "Maintainer", "Installed-Size", "Pre-Depends",
                   "Depends", "Recommends", "Suggests", "Breaks", "Conflicts", "Replaces", "Provides",
                   "Essential", "Multi-Arch", "Section", "Priority", "Homepage", "Description")


def build_pack(args):
    repos = [r.split() for r in (args.repo or DEFAULT_REPOS)]
    index = []
    for base, dist, component in repos:
        index += load_index(base.rstrip("/"), dist, component, args.arch)
    installed = read_bootstrap_status(args.bootstrap)
    log("bootstrap provides %d installed packages" % len(installed))

    roots = list(args.package)
    if args.packages_from:
        with open(args.packages_from) as f:
            m = re.search(r"^CORE_PACKAGES=\(([^)]*)\)", f.read(), re.M)
        if not m:
            raise RuntimeError("No CORE_PACKAGES=(...) in " + args.packages_from)
        roots = m.group(1).split() + roots
    selected = Resolver(index, installed).run(roots, args.optional, not args.no_recommends)
    log("%d packages to bundle for %s" % (len(selected), args.arch))

    os.makedirs(args.cache, exist_ok=True)
    stanzas = [selected[n] for n in sorted(selected)]
    with concurrent.futures.ThreadPoolExecutor(8) as pool:
        originals = list(pool.map(lambda s: download_deb(s, args.cache), stanzas))
    jobs = []
    for stanza, original in zip(stanzas, originals):
        relocated = os.path.join(args.cache, "reloc%s-%s-%s.deb" % (RELOCATOR_VERSION, args.new_pkg, stanza["SHA256"]))
        jobs.append((original, relocated, args.new_pkg))
    if args.new_pkg == OLD_PKG:
        debs = originals
    else:
        with concurrent.futures.ProcessPoolExecutor() as pool:
            debs = list(pool.map(_relocate_job, jobs))

    entries = []
    for stanza, deb in zip(stanzas, debs):
        fields = ["%s: %s" % (k, stanza[k]) for k in PACKAGES_FIELDS if k in stanza]
        fields += ["Filename: debs/" + deb_file_name(stanza), "Size: %d" % os.path.getsize(deb), "SHA256: " + sha256_file(deb)]
        entries.append("\n".join(fields) + "\n")
    packages = "\n".join(entries).encode()
    manifest = "".join("%s %s %s\n" % (s["Package"], s["Version"], s["Architecture"]) for s in stanzas).encode()

    def add_bytes(tar, name, data):
        info = tarfile.TarInfo(name)
        info.size, info.mode, info.mtime = len(data), 0o644, 0
        tar.addfile(info, io.BytesIO(data))

    os.makedirs(os.path.dirname(os.path.abspath(args.output)), exist_ok=True)
    tmp = args.output + ".tmp"
    with tarfile.open(tmp, "w:", format=tarfile.GNU_FORMAT) as tar:
        add_bytes(tar, "Packages", packages)
        add_bytes(tar, "manifest.txt", manifest)
        for stanza, deb in zip(stanzas, debs):
            info = tar.gettarinfo(deb, "debs/" + deb_file_name(stanza))
            info.mtime, info.uid, info.gid, info.uname, info.gname, info.mode = 0, 0, 0, "", "", 0o644
            with open(deb, "rb") as f:
                tar.addfile(info, f)
    os.replace(tmp, args.output)
    size = sum(os.path.getsize(d) for d in debs)
    log("wrote %s (%d packages, %.1f MB)" % (args.output, len(debs), size / 1e6))

    # Drop cache entries of packages that are no longer in the repositories.
    keep = {os.path.basename(p) for p in originals} | {os.path.basename(j[1]) for j in jobs}
    for name in os.listdir(args.cache):
        if name.endswith(".deb") and name not in keep:
            os.remove(os.path.join(args.cache, name))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--arch", required=True, help="Termux architecture: aarch64, arm, i686 or x86_64")
    parser.add_argument("--output", required=True, help="tar file to write")
    parser.add_argument("--cache", required=True, help="download cache folder, one per architecture (unused files are pruned)")
    parser.add_argument("--new-pkg", default=OLD_PKG, help="Android package name to relocate the packages to")
    parser.add_argument("--bootstrap", help="bootstrap zip whose installed packages are not bundled again")
    parser.add_argument("--packages-from", help="shell file with a CORE_PACKAGES=(...) array")
    parser.add_argument("--package", action="append", default=[], help="additional required package")
    parser.add_argument("--optional", action="append", default=[], help="package bundled only if the repositories have it")
    parser.add_argument("--repo", action="append", help='"BASE_URL DIST COMPONENT" (default: Termux main and x11)')
    parser.add_argument("--no-recommends", action="store_true", help="do not bundle Recommends")
    parser.add_argument("--keep-going", action="store_true",
                        help="on failure, keep an existing pack (or write none) and exit 0 instead of failing the build")
    args = parser.parse_args()
    if len(args.new_pkg) != len(OLD_PKG):
        parser.error("--new-pkg must be exactly %d characters, like %s" % (len(OLD_PKG), OLD_PKG))
    try:
        build_pack(args)
    except Exception as e:  # noqa: BLE001 - reported to the build log
        if not args.keep_going:
            raise
        log("WARNING: could not build the offline desktop pack for %s: %s" % (args.arch, e))
        if os.path.exists(args.output):
            log("WARNING: keeping the previous pack %s" % args.output)
        else:
            log("WARNING: the APK will download the desktop on first launch instead")
    return 0


if __name__ == "__main__":
    sys.exit(main())
