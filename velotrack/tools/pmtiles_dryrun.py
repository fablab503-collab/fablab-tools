#!/usr/bin/env python3
"""Minimal PMTiles v3 reader over HTTP range requests: computes the exact bytes needed for a bbox + zoom range.
Mirrors what the Kotlin extractor will do. Usage: pmtiles_dryrun.py URL minLon minLat maxLon maxLat maxZoom"""
import sys, struct, gzip, math, urllib.request, functools, collections

URL = sys.argv[1]
import subprocess
def fetch(off, ln):
    out = subprocess.run(["curl", "-sS", "--fail", "-r", f"{off}-{off+ln-1}", URL], capture_output=True, check=True).stdout
    assert len(out) == ln, (len(out), ln)
    return out

hdr = fetch(0, 127)
assert hdr[:7] == b"PMTiles" and hdr[7] == 3
(root_off, root_len, meta_off, meta_len, leaf_off, leaf_len, tile_off, tile_len, n_addr, n_entries, n_contents) = struct.unpack_from("<11Q", hdr, 8)
clustered, icomp, tcomp, ttype, minz, maxz = struct.unpack_from("<6B", hdr, 96)

def varint(buf, pos):
    shift = 0; result = 0
    while True:
        b = buf[pos]; pos += 1
        result |= (b & 0x7F) << shift
        if b < 0x80: return result, pos
        shift += 7

def decode_dir(buf):
    if icomp == 2: buf = gzip.decompress(buf)
    pos = 0
    n, pos = varint(buf, pos)
    ids = []; last = 0
    for _ in range(n):
        d, pos = varint(buf, pos); last += d; ids.append(last)
    runs = []
    for _ in range(n):
        v, pos = varint(buf, pos); runs.append(v)
    lens = []
    for _ in range(n):
        v, pos = varint(buf, pos); lens.append(v)
    offs = []
    for i in range(n):
        v, pos = varint(buf, pos)
        offs.append(offs[i-1] + lens[i-1] if v == 0 else v - 1)
    return list(zip(ids, runs, lens, offs))

def find_entry(entries, tid):
    lo, hi = 0, len(entries) - 1
    while lo <= hi:
        mid = (lo + hi) // 2
        if tid < entries[mid][0]: hi = mid - 1
        elif tid > entries[mid][0]: lo = mid + 1
        else: return entries[mid]
    if hi >= 0:
        e = entries[hi]
        if e[1] == 0 or tid - e[0] < e[1]: return e   # leaf pointer, or inside a run
    return None

# Hilbert tile id (from PMTiles spec)
def rotate(n, x, y, rx, ry):
    if ry == 0:
        if rx == 1: x = n - 1 - x; y = n - 1 - y
        x, y = y, x
    return x, y
def zxy_to_id(z, x, y):
    acc = ((1 << (2 * z)) - 1) // 3
    n = 1 << z; d = 0; s = n >> 1
    tx, ty = x, y
    while s > 0:
        rx = 1 if (tx & s) else 0; ry = 1 if (ty & s) else 0
        d += s * s * ((3 * rx) ^ ry)
        tx, ty = rotate(n, tx, ty, rx, ry)   # spec rotates with n
        s >>= 1
    return acc + d

def lonlat_to_tile(lon, lat, z):
    lat = max(min(lat, 85.0511), -85.0511)
    n = 1 << z
    x = int((lon + 180.0) / 360.0 * n)
    y = int((1 - math.log(math.tan(math.radians(lat)) + 1 / math.cos(math.radians(lat))) / math.pi) / 2 * n)
    return min(max(x, 0), n - 1), min(max(y, 0), n - 1)

root = decode_dir(fetch(root_off, root_len))
leaf_cache = {}
def lookup(tid):
    e = find_entry(root, tid)
    if e is None: return None
    if e[1] == 0:  # leaf directory
        key = e[3]
        if key not in leaf_cache:
            leaf_cache[key] = decode_dir(fetch(leaf_off + e[3], e[2]))
        e2 = find_entry(leaf_cache[key], tid)
        if e2 is None or e2[1] == 0: return None
        return e2
    return e

minlon, minlat, maxlon, maxlat, zmax = map(float, sys.argv[2:7]); zmax = int(zmax)
unique = {}; addressed = 0; per_zoom = collections.Counter(); per_zoom_bytes = collections.Counter()
for z in range(0, zmax + 1):
    x0, y1 = lonlat_to_tile(minlon, minlat, z); x1, y0 = lonlat_to_tile(maxlon, maxlat, z)
    for x in range(x0, x1 + 1):
        for y in range(y0, y1 + 1):
            addressed += 1
            e = lookup(zxy_to_id(z, x, y))
            if e is None: continue
            per_zoom[z] += 1
            if e[3] not in unique:
                unique[e[3]] = e[2]; per_zoom_bytes[z] += e[2]
total = sum(unique.values())
print(f"bbox {minlon},{minlat},{maxlon},{maxlat} z0-{zmax}: addressed {addressed} tiles, found {sum(per_zoom.values())}, unique blobs {len(unique)}, leaf dirs fetched {len(leaf_cache)}")
print(f"TOTAL tile bytes: {total/1e6:.1f} MB")
for z in sorted(per_zoom): print(f"  z{z:2}: {per_zoom[z]:6} tiles, {per_zoom_bytes[z]/1e6:8.1f} MB new")
