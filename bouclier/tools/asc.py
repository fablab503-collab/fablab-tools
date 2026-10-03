#!/usr/bin/env python3
"""App Store Connect API helper for Bouclier.

    tools/asc.py status          read-only: what App Store Connect has (versions, builds, listing, price...)
    tools/asc.py fill            write the listing from appstore/listing.json: categories, names, texts,
                                 age rating, the version in extension/manifest.json, review details, screenshots, price, availability
    tools/asc.py fill --only screenshots,price    run only some steps (see STEPS)
    tools/asc.py attach          attach the newest processed build of that version to the iOS and macOS versions

Key: an App Store Connect API key (Users and Access > Integrations > Team Keys, access App Manager).
The .p8 file lives in ~/.appstoreconnect/private_keys/AuthKey_<KEY_ID>.p8 (chmod 600) and
~/.appstoreconnect/bouclier.json holds {"issuer_id": "...", "key_id": "...", "contact_phone": "..."}.
Nothing here submits the app for review: Daniel presses Submit himself.
Written 2026-09-26 by a Claude Cowork session. Needs pyjwt, cryptography, requests
(build/asc-venv has them: build/asc-venv/bin/python tools/asc.py status).
"""
import argparse
import hashlib
import json
import os
import sys
import time

import jwt
import requests

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONF_PATH = os.path.expanduser("~/.appstoreconnect/bouclier.json")
KEY_DIR = os.path.expanduser("~/.appstoreconnect/private_keys")
API = "https://api.appstoreconnect.apple.com"
BUNDLE_ID = "com.danielmadac.Bouclier"
# the version being prepared is the extension's (extension/manifest.json), so a release only bumps it there
VERSION = json.load(open(os.path.join(ROOT, "extension", "manifest.json")))["version"]
LOCALES = {"en-US": "en", "fr-FR": "fr"}
EDITABLE = {"PREPARE_FOR_SUBMISSION", "DEVELOPER_REJECTED", "REJECTED", "METADATA_REJECTED",
            "INVALID_BINARY", "WAITING_FOR_REVIEW"}
SHOT_SETS = {"IOS": [("iphone", ["APP_IPHONE_67", "APP_IPHONE_69"]),
                     ("ipad", ["APP_IPAD_PRO_3GEN_129"])],
             "MAC_OS": [("mac", ["APP_DESKTOP"])]}
STEPS = ["app", "categories", "names", "agerating", "versions", "texts", "review",
         "screenshots", "price", "availability"]


class ApiError(Exception):
    pass


class ASC:
    def __init__(self):
        conf = json.load(open(CONF_PATH))
        self.conf = conf
        key_path = os.path.join(KEY_DIR, f"AuthKey_{conf['key_id']}.p8")
        self.key = open(key_path).read()
        self._tok, self._exp = None, 0
        self.s = requests.Session()

    def token(self):
        now = int(time.time())
        if not self._tok or now > self._exp - 60:
            self._exp = now + 15 * 60
            self._tok = jwt.encode({"iss": self.conf["issuer_id"], "iat": now, "exp": self._exp,
                                    "aud": "appstoreconnect-v1"}, self.key, algorithm="ES256",
                                   headers={"kid": self.conf["key_id"], "typ": "JWT"})
        return self._tok

    def call(self, method, path, body=None, params=None, ok404=False):
        url = path if path.startswith("http") else API + path
        for attempt in range(4):
            r = self.s.request(method, url, params=params, json=body,
                               headers={"Authorization": f"Bearer {self.token()}"}, timeout=60)
            if r.status_code == 429 or r.status_code >= 500:
                time.sleep(2 + attempt * 3)
                continue
            break
        if ok404 and r.status_code == 404:
            return None
        if r.status_code >= 400:
            try:
                errs = r.json().get("errors", [])
                msg = "; ".join(f"{e.get('title')}: {e.get('detail')}"
                                + (f" [{e['source'].get('pointer') or e['source'].get('parameter')}]"
                                   if e.get("source") else "") for e in errs)
            except ValueError:
                msg = r.text[:500]
            raise ApiError(f"{method} {path} -> {r.status_code}: {msg}")
        return r.json() if r.content else {}

    def get(self, path, **params):
        return self.call("GET", path, params=params or None)

    def all(self, path, **params):
        out, url, p = [], path, dict(params, limit=params.get("limit", 200))
        while url:
            d = self.call("GET", url, params=p)
            out += d.get("data", [])
            url, p = d.get("links", {}).get("next"), None
        return out

    def patch(self, type_, id_, attrs=None, rels=None):
        data = {"type": type_, "id": id_}
        if attrs:
            data["attributes"] = attrs
        if rels:
            data["relationships"] = rels
        return self.call("PATCH", f"/v1/{type_}/{id_}", {"data": data})

    def post(self, type_, attrs=None, rels=None, included=None, path=None):
        data = {"type": type_}
        if attrs is not None:
            data["attributes"] = attrs
        if rels:
            data["relationships"] = rels
        body = {"data": data}
        if included:
            body["included"] = included
        return self.call("POST", path or f"/v1/{type_}", body)


def rel(type_, id_):
    return {"data": {"type": type_, "id": id_}}


def listing():
    return json.load(open(os.path.join(ROOT, "appstore", "listing.json"), encoding="utf-8"))


def find_app(c):
    apps = c.get("/v1/apps", **{"filter[bundleId]": BUNDLE_ID})["data"]
    if not apps:
        raise SystemExit(f"No app with bundle ID {BUNDLE_ID} in App Store Connect")
    return apps[0]


def versions(c, app_id):
    return c.all(f"/v1/apps/{app_id}/appStoreVersions")


def editable_version(c, app_id, platform):
    vs = [v for v in versions(c, app_id) if v["attributes"]["platform"] == platform]
    for v in vs:
        if v["attributes"]["appStoreState"] in EDITABLE:
            return v
    return None


def edit_app_info(c, app_id):
    infos = c.all(f"/v1/apps/{app_id}/appInfos")
    for i in infos:
        if i["attributes"].get("appStoreState") not in ("READY_FOR_SALE", "READY_FOR_DISTRIBUTION"):
            return i
    return infos[0]


def builds(c, app_id, platform=None, limit=10):
    params = {"filter[app]": app_id, "sort": "-uploadedDate", "include": "preReleaseVersion",
              "limit": limit}
    if platform:
        params["filter[preReleaseVersion.platform]"] = platform
    d = c.get("/v1/builds", **params)
    pre = {x["id"]: x["attributes"] for x in d.get("included", []) if x["type"] == "preReleaseVersions"}
    out = []
    for b in d["data"]:
        pv = pre.get(b["relationships"]["preReleaseVersion"]["data"]["id"], {}) \
            if b["relationships"].get("preReleaseVersion", {}).get("data") else {}
        out.append(dict(id=b["id"], build=b["attributes"]["version"], version=pv.get("version"),
                        platform=pv.get("platform"), state=b["attributes"]["processingState"],
                        uploaded=b["attributes"]["uploadedDate"], expired=b["attributes"]["expired"]))
    return out


# ---------------------------------------------------------------- status

def status(c):
    app = find_app(c)
    a, app_id = app["attributes"], app["id"]
    print(f"App: {a['name']}  id {app_id}  bundle {a['bundleId']}  primary locale {a['primaryLocale']}"
          f"  SKU {a.get('sku')}  content rights {a.get('contentRightsDeclaration')}")
    info = edit_app_info(c, app_id)
    inc = c.get(f"/v1/appInfos/{info['id']}", include="primaryCategory,secondaryCategory")
    r = inc["data"]["relationships"]
    cat = lambda k: (r.get(k, {}).get("data") or {}).get("id")
    print(f"App info: state {info['attributes'].get('appStoreState')}  categories "
          f"{cat('primaryCategory')} / {cat('secondaryCategory')}")
    for l in c.all(f"/v1/appInfos/{info['id']}/appInfoLocalizations"):
        la = l["attributes"]
        print(f"  {la['locale']}: name {la.get('name')!r}  subtitle {la.get('subtitle')!r}"
              f"  privacy {la.get('privacyPolicyUrl')}")
    age = c.call("GET", f"/v1/appInfos/{info['id']}/ageRatingDeclaration", ok404=True)
    if age:
        unset = [k for k, v in age["data"]["attributes"].items() if v is None]
        print(f"  age rating: {len(unset)} questions unanswered" + (f" ({', '.join(unset)})" if unset else ""))
    print("Versions:")
    for v in versions(c, app_id):
        va = v["attributes"]
        b = c.call("GET", f"/v1/appStoreVersions/{v['id']}/build", ok404=True)
        bid = (b or {}).get("data")
        print(f"  {va['platform']:7} {va['versionString']:8} {va['appStoreState']:24} build "
              f"{bid['attributes']['version'] if bid else '-'}  copyright {va.get('copyright')!r}")
        for l in c.all(f"/v1/appStoreVersions/{v['id']}/appStoreVersionLocalizations"):
            la = l["attributes"]
            filled = [k for k in ("description", "keywords", "promotionalText", "supportUrl", "marketingUrl")
                      if la.get(k)]
            sets = c.all(f"/v1/appStoreVersionLocalizations/{l['id']}/appScreenshotSets",
                         include="appScreenshots")
            shots = ", ".join(f"{s['attributes']['screenshotDisplayType']}:"
                              f"{len(s['relationships']['appScreenshots'].get('data', []))}" for s in sets)
            print(f"    {la['locale']}: {', '.join(filled) or 'no text'} | screenshots {shots or 'none'}")
        rd = c.call("GET", f"/v1/appStoreVersions/{v['id']}/appStoreReviewDetail", ok404=True)
        if rd and rd.get("data"):
            ra = rd["data"]["attributes"]
            print(f"    review contact: {ra.get('contactFirstName')} {ra.get('contactLastName')} "
                  f"{ra.get('contactEmail')} phone {'set' if ra.get('contactPhone') else 'MISSING'}"
                  f"  notes {'set' if ra.get('notes') else 'missing'}")
        else:
            print("    review details: none")
    print("Builds (newest first):")
    for b in builds(c, app_id, limit=10):
        print(f"  {b['platform'] or '?':7} {b['version']} ({b['build']})  {b['state']:10} "
              f"uploaded {b['uploaded']}{'  EXPIRED' if b['expired'] else ''}")
    ps = c.call("GET", f"/v1/apps/{app_id}/appPriceSchedule", ok404=True,
                params={"include": "baseTerritory,manualPrices"})
    if ps and ps.get("data"):
        base = (ps["data"]["relationships"].get("baseTerritory", {}).get("data") or {}).get("id")
        n = len(ps["data"]["relationships"].get("manualPrices", {}).get("data", []))
        print(f"Price schedule: base territory {base}, {n} manual price(s)")
    else:
        print("Price schedule: none")
    av = c.call("GET", f"/v1/apps/{app_id}/appAvailabilityV2", ok404=True)
    if av and av.get("data"):
        print(f"Availability: set (new territories: {av['data']['attributes'].get('availableInNewTerritories')})")
    else:
        print("Availability: not set")


# ---------------------------------------------------------------- fill steps

def step_app(c, app, L):
    c.patch("apps", app["id"], {"contentRightsDeclaration": "USES_THIRD_PARTY_CONTENT"})
    return "content rights: uses third-party content (filter lists, with the rights to use them)"


def step_categories(c, app, L):
    info = edit_app_info(c, app["id"])
    c.patch("appInfos", info["id"], rels={
        "primaryCategory": rel("appCategories", "UTILITIES"),
        "secondaryCategory": rel("appCategories", "PRODUCTIVITY")})
    return "Utilities, then Productivity"


def step_names(c, app, L):
    info = edit_app_info(c, app["id"])
    existing = c.all(f"/v1/appInfos/{info['id']}/appInfoLocalizations")
    urls = L["app"]["urls"]
    done = []
    for loc in LOCALES:
        x = L["locales"][loc]
        attrs = {"name": x["name"], "subtitle": x["subtitle"], "privacyPolicyUrl": urls["privacyPolicy"]}
        cur = next((e for e in existing if e["attributes"]["locale"] == loc), None)
        if cur:
            c.patch("appInfoLocalizations", cur["id"], attrs)
        else:
            c.post("appInfoLocalizations", dict(attrs, locale=loc), {"appInfo": rel("appInfos", info["id"])})
        done.append(f"{loc} {x['name']!r}")
    return ", ".join(done)


AGE_BOOL = ["gambling", "unrestrictedWebAccess", "lootBox", "messagingAndChat", "parentalControls",
            "ageAssurance", "userGeneratedContent", "advertising", "healthOrWellnessTopics", "seventeenPlus"]
AGE_ENUM = ["alcoholTobaccoOrDrugUseOrReferences", "contests", "gamblingSimulated",
            "medicalOrTreatmentInformation", "profanityOrCrudeHumor", "sexualContentGraphicAndNudity",
            "sexualContentOrNudity", "horrorOrFearThemes", "matureOrSuggestiveThemes",
            "violenceCartoonOrFantasy", "violenceRealistic", "violenceRealisticProlongedGraphicOrSadistic",
            "gunsOrOtherWeapons"]


def step_agerating(c, app, L):
    info = edit_app_info(c, app["id"])
    d = c.get(f"/v1/appInfos/{info['id']}/ageRatingDeclaration")["data"]
    cur = d["attributes"]
    attrs = {k: False for k in AGE_BOOL if k in cur}
    attrs.update({k: "NONE" for k in AGE_ENUM if k in cur})
    skipped = []
    for _ in range(8):
        try:
            c.patch("ageRatingDeclarations", d["id"], attrs)
            break
        except ApiError as e:
            bad = [k for k in attrs if f"/data/attributes/{k}" in str(e)]
            if not bad:
                raise
            for k in bad:
                attrs.pop(k)
                skipped.append(k)
    left = [k for k, v in c.get(f"/v1/ageRatingDeclarations/{d['id']}")["data"]["attributes"].items()
            if v is None and k not in ("kidsAgeBand", "koreaAgeRatingOverride", "ageRatingOverride",
                                       "developerAgeRatingInfoUrl")]
    return (f"{len(attrs)} answers set to None/No" + (f"; not accepted: {skipped}" if skipped else "")
            + (f"; still unanswered: {left}" if left else ""))


def step_versions(c, app, L):
    out = []
    for platform in ("IOS", "MAC_OS"):
        v = editable_version(c, app["id"], platform)
        attrs = {"versionString": VERSION, "copyright": L["app"]["copyright"],
                 "releaseType": "AFTER_APPROVAL"}
        if v:
            c.patch("appStoreVersions", v["id"], attrs)
            out.append(f"{platform} {v['attributes']['versionString']} -> {VERSION}")
        else:
            c.post("appStoreVersions", dict(attrs, platform=platform), {"app": rel("apps", app["id"])})
            out.append(f"{platform} {VERSION} created")
    return ", ".join(out)


def step_texts(c, app, L):
    urls = L["app"]["urls"]
    out = []
    for platform in ("IOS", "MAC_OS"):
        v = editable_version(c, app["id"], platform)
        if not v:
            out.append(f"{platform}: no editable version")
            continue
        existing = c.all(f"/v1/appStoreVersions/{v['id']}/appStoreVersionLocalizations")
        for loc in LOCALES:
            x = L["locales"][loc]
            attrs = {"description": x["description"], "keywords": x["keywords"],
                     "promotionalText": x["promotionalText"], "supportUrl": urls["support"],
                     "marketingUrl": urls["marketing"]}
            cur = next((e for e in existing if e["attributes"]["locale"] == loc), None)
            if cur:
                c.patch("appStoreVersionLocalizations", cur["id"], attrs)
            else:
                c.post("appStoreVersionLocalizations", dict(attrs, locale=loc),
                       {"appStoreVersion": rel("appStoreVersions", v["id"])})
            out.append(f"{platform} {loc}")
    return "texts in " + ", ".join(out)


def step_review(c, app, L):
    phone = c.conf.get("contact_phone")
    attrs = {"contactFirstName": "Daniel", "contactLastName": "Madac",
             "contactEmail": c.conf.get("contact_email", "fablab503@gmail.com"),
             "demoAccountRequired": False, "notes": L["reviewNotes"]}
    if phone:
        attrs["contactPhone"] = phone
    out = []
    for platform in ("IOS", "MAC_OS"):
        v = editable_version(c, app["id"], platform)
        if not v:
            continue
        rd = c.call("GET", f"/v1/appStoreVersions/{v['id']}/appStoreReviewDetail", ok404=True)
        if rd and rd.get("data"):
            c.patch("appStoreReviewDetails", rd["data"]["id"], attrs)
        else:
            c.post("appStoreReviewDetails", attrs, {"appStoreVersion": rel("appStoreVersions", v["id"])})
        out.append(platform)
    return f"review notes and contact for {', '.join(out)}" + ("" if phone else " (contact phone MISSING)")


def upload_shot(c, set_id, path):
    data = open(path, "rb").read()
    d = c.post("appScreenshots", {"fileName": os.path.basename(path), "fileSize": len(data)},
               {"appScreenshotSet": rel("appScreenshotSets", set_id)})
    sid = d["data"]["id"]
    for op in d["data"]["attributes"]["uploadOperations"]:
        chunk = data[op["offset"]:op["offset"] + op["length"]]
        hdrs = {h["name"]: h["value"] for h in op.get("requestHeaders", [])}
        r = requests.request(op["method"], op["url"], data=chunk, headers=hdrs, timeout=120)
        if r.status_code >= 300:
            raise ApiError(f"upload chunk {r.status_code} for {path}")
    c.patch("appScreenshots", sid, {"uploaded": True, "sourceFileChecksum": hashlib.md5(data).hexdigest()})
    return sid


def step_screenshots(c, app, L, replace=False):
    base = os.path.join(ROOT, "appstore", "screenshots")
    out = []
    for platform, groups in SHOT_SETS.items():
        v = editable_version(c, app["id"], platform)
        if not v:
            out.append(f"{platform}: no editable version")
            continue
        for l in c.all(f"/v1/appStoreVersions/{v['id']}/appStoreVersionLocalizations"):
            loc = l["attributes"]["locale"]
            if loc not in LOCALES:
                continue
            sets = c.all(f"/v1/appStoreVersionLocalizations/{l['id']}/appScreenshotSets",
                         include="appScreenshots")
            for folder, types in groups:
                files = sorted(f for f in os.listdir(os.path.join(base, LOCALES[loc], folder))
                               if f.endswith(".png"))
                cur = next((s for s in sets if s["attributes"]["screenshotDisplayType"] in types), None)
                if cur and cur["relationships"]["appScreenshots"].get("data"):
                    if not replace:
                        out.append(f"{platform} {loc} {folder}: already has screenshots, kept")
                        continue
                    for s in cur["relationships"]["appScreenshots"]["data"]:
                        c.call("DELETE", f"/v1/appScreenshots/{s['id']}")
                set_id = cur["id"] if cur else None
                err = None
                if not set_id:
                    for t in types:
                        try:
                            set_id = c.post("appScreenshotSets", {"screenshotDisplayType": t},
                                            {"appStoreVersionLocalization":
                                             rel("appStoreVersionLocalizations", l["id"])})["data"]["id"]
                            break
                        except ApiError as e:
                            err = e
                if not set_id:
                    raise err
                for f in files:
                    upload_shot(c, set_id, os.path.join(base, LOCALES[loc], folder, f))
                out.append(f"{platform} {loc} {folder}: {len(files)} uploaded")
    return "; ".join(out)


def step_price(c, app, L):
    target = "1.99"
    pps = c.all(f"/v1/apps/{app['id']}/appPricePoints", **{"filter[territory]": "FRA"})
    pp = next((p for p in pps if p["attributes"].get("customerPrice") in (target, "1.990")), None)
    if not pp:
        raise ApiError(f"no 1.99 price point for France among {len(pps)}")
    included = [{"type": "appPrices", "id": "${price1}", "attributes": {"startDate": None},
                 "relationships": {"appPricePoint": rel("appPricePoints", pp["id"])}}]
    c.post("appPriceSchedules", rels={
        "app": rel("apps", app["id"]),
        "baseTerritory": rel("territories", "FRA"),
        "manualPrices": {"data": [{"type": "appPrices", "id": "${price1}"}]}}, included=included)
    return f"1.99 EUR in France (base), proceeds {pp['attributes'].get('proceeds')} EUR; other countries follow"


def step_availability(c, app, L):
    av = c.call("GET", f"/v1/apps/{app['id']}/appAvailabilityV2", ok404=True)
    if av and av.get("data"):
        return "already set, kept"
    terr = [t["id"] for t in c.all("/v1/territories")]
    skip = {"CHN"}  # China mainland needs an ICP filing number; left out
    ids = [t for t in terr if t not in skip]
    included = [{"type": "territoryAvailabilities", "id": f"${{t{i}}}", "attributes": {"available": True},
                 "relationships": {"territory": rel("territories", t)}} for i, t in enumerate(ids)]
    c.post("appAvailabilities", {"availableInNewTerritories": True},
           {"app": rel("apps", app["id"]),
            "territoryAvailabilities": {"data": [{"type": "territoryAvailabilities", "id": x["id"]}
                                                 for x in included]}},
           included=included, path="/v2/appAvailabilities")
    return f"{len(ids)} countries and regions (all except China mainland)"


def fill(c, only=None, replace_shots=False):
    app = find_app(c)
    L = listing()
    failed = 0
    for name in STEPS:
        if only and name not in only:
            continue
        fn = globals()[f"step_{name}"]
        try:
            msg = fn(c, app, L, replace_shots) if name == "screenshots" else fn(c, app, L)
            print(f"ok    {name:12} {msg}")
        except ApiError as e:
            failed += 1
            print(f"FAIL  {name:12} {e}")
    return failed


def attach(c):
    app = find_app(c)
    for platform in ("IOS", "MAC_OS"):
        v = editable_version(c, app["id"], platform)
        if not v:
            print(f"{platform}: no editable version")
            continue
        bs = [b for b in builds(c, app["id"], platform, limit=10)
              if b["version"] == VERSION and b["state"] == "VALID" and not b["expired"]]
        if not bs:
            print(f"{platform}: no processed {VERSION} build yet")
            continue
        c.call("PATCH", f"/v1/appStoreVersions/{v['id']}/relationships/build",
               {"data": {"type": "builds", "id": bs[0]["id"]}})
        print(f"{platform}: build {bs[0]['build']} attached to {VERSION}")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["status", "fill", "attach"])
    ap.add_argument("--only", help="comma-separated fill steps: " + ",".join(STEPS))
    ap.add_argument("--replace-screenshots", action="store_true")
    a = ap.parse_args()
    c = ASC()
    if a.cmd == "status":
        status(c)
    elif a.cmd == "fill":
        sys.exit(1 if fill(c, set(a.only.split(",")) if a.only else None, a.replace_screenshots) else 0)
    else:
        attach(c)
