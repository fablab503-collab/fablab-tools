#!/usr/bin/env python3
"""
Bouclier filter compiler.

Turns Adblock Plus / uBlock Origin style filter lists into what a Safari web
extension can use:

  extension/rules/<id>.json            declarativeNetRequest static ruleset
  extension/cosmetic/<id>.json         element-hiding data used at runtime
  extension/cosmetic/<id>.generic.css  generic element hiding, injected as a
                                        user stylesheet on every page
  extension/filters.json               catalogue shown in the popup/settings

Safari specifics this compiler respects (checked against WebKit source,
_WKWebExtensionDeclarativeNetRequestRule.mm, Sept 2026):
  * every enabled rule of every enabled ruleset is compiled into ONE WebKit
    content rule list, capped at 150,000 WebKit rules; going over the cap makes
    the whole list fail to compile, so we count WebKit rules exactly the way
    Safari expands them (one per requestDomain, one per excluded request
    domain, ...);
  * urlFilter / regexFilter / domains must be ASCII (IDN hosts are punycoded);
  * resource types Safari lacks (object, csp_report, webtransport...) are
    dropped; a rule left with no type is skipped;
  * regexFilter must fit WebKit's small regex dialect, so regex filters are
    skipped.

Standard library only, so it runs with the python3 that ships with macOS.
"""

import argparse
import json
import os
import re
import sys
from collections import Counter, defaultdict

SAFARI_TYPES = ["main_frame", "sub_frame", "stylesheet", "script", "image", "font",
                "xmlhttprequest", "ping", "media", "websocket", "other"]

# filter-list option name -> DNR resource type (None = not supported by Safari)
TYPE_OPTIONS = {
    "script": "script", "image": "image", "stylesheet": "stylesheet", "css": "stylesheet",
    "xmlhttprequest": "xmlhttprequest", "xhr": "xmlhttprequest",
    "subdocument": "sub_frame", "frame": "sub_frame",
    "ping": "ping", "beacon": "ping",
    "websocket": "websocket", "media": "media", "font": "font", "other": "other",
    "document": "main_frame", "doc": "main_frame",
    "object": None, "object-subrequest": None, "webrtc": None, "csp_report": None,
}

COSMETIC_FLAGS = {"generichide": "genericHide", "ghide": "genericHide",
                  "elemhide": "elemHide", "ehide": "elemHide",
                  "specifichide": "specificHide", "shide": "specificHide"}

# Options we cannot express with declarativeNetRequest: the whole filter is skipped
# rather than applied with different (broader) semantics.
UNSUPPORTED_OPTIONS = {
    "popunder", "csp", "removeparam", "queryprune", "redirect", "redirect-rule",
    "rewrite", "replace", "header", "permissions", "urlskip", "uritransform", "urltransform",
    "empty", "mp4", "inline-script", "inline-font", "cname", "sitekey", "genericblock",
    "content", "jsinject", "extension", "urlblock", "stealth", "cookie", "network", "app",
    "hls", "jsonprune", "xmlprune", "referrerpolicy", "ipaddress",
}

PRIORITY_BLOCK = 1
PRIORITY_ALLOW = 2
PRIORITY_IMPORTANT = 3

WEBKIT_RULE_CAP = 150000

OPTIONS_RE = re.compile(r"~?[a-z0-9_-]+(?:=[^,]*)?(?:,~?[a-z0-9_-]+(?:=[^,]*)?)*", re.I)
HOST_RE = re.compile(r"^(?:[a-z0-9_](?:[a-z0-9_-]*[a-z0-9_])?\.)+[a-z0-9-]{2,}$|^\d{1,3}(?:\.\d{1,3}){3}$")
PURE_HOST_PATTERN = re.compile(r"^\|\|([a-z0-9_.-]+)\^\|?$")
COSMETIC_RE = re.compile(r"^([^\s#/|$]*?)(#@?[?$%]?#)(.*)$")
COSMETIC_DOMAINS_OK = re.compile(r"^[a-z0-9.*~,:_\[\]\-]*$", re.I)
PROCEDURAL_RE = re.compile(
    r":(?:-abp-contains|-abp-properties|contains|has-text|matches-css(?:-before|-after)?|"
    r"matches-attr|matches-path|matches-prop|matches-media|min-text-length|others|upward|"
    r"xpath|nth-ancestor|remove|remove-attr|remove-class|style|watch-attr|watch-attrs|if|"
    r"if-not|shadow|properties|is-empty|spath)\(|\[-ext-|:-abp-has\(.*:-abp-", re.I)


def to_ascii_host(host):
    """Lower-case a host and punycode it when it contains non-ASCII characters."""
    host = host.strip().lower().rstrip(".")
    if not host:
        return None
    try:
        host.encode("ascii")
        return host
    except UnicodeEncodeError:
        try:
            return ".".join(label.encode("idna").decode("ascii") if label and label != "*" else label
                            for label in host.split("."))
        except UnicodeError:
            return None


def split_options(text):
    """Return (pattern, options-string) using the right-most '$' that starts a valid option list."""
    idx = text.rfind("$")
    while idx != -1:
        opts = text[idx + 1:]
        if opts and OPTIONS_RE.fullmatch(opts):
            return text[:idx], opts
        idx = text.rfind("$", 0, idx)
    return text, ""


def split_option_list(opts):
    """Split 'a,domain=x|y,b' into items, respecting that domain lists never contain commas."""
    return [o.strip() for o in opts.split(",") if o.strip()]


def parse_domain_list(value, sep="|"):
    """Return (included, excluded, ok). ok=False if a positive entry could not be represented."""
    inc, exc = [], []
    ok = True
    for raw in value.split(sep):
        raw = raw.strip()
        if not raw:
            continue
        neg = raw.startswith("~")
        d = raw[1:] if neg else raw
        if d.startswith("/") or d.endswith(".*") or "*" in d:
            # regex domains and entities ("example.*") have no DNR equivalent
            if not neg:
                ok = False
            continue
        d = to_ascii_host(d)
        if not d:
            if not neg:
                ok = False
            continue
        (exc if neg else inc).append(d)
    return inc, exc, ok


class Stats:
    def __init__(self):
        self.c = Counter()

    def add(self, key, n=1):
        self.c[key] += n


def abp_to_regex(pattern):
    """ABP URL pattern -> JavaScript RegExp source (for the pop-up blocker, matched in JS)."""
    p = pattern
    prefix, suffix = "", ""
    if p.startswith("||"):
        p, prefix = p[2:], r"^[a-z][a-z0-9+.-]*://(?:[^/?#]*\.)?"
    elif p.startswith("|"):
        p, prefix = p[1:], "^"
    if p.endswith("|"):
        p, suffix = p[:-1], "$"
    out = []
    for ch in p:
        if ch == "*":
            out.append(".*")
        elif ch == "^":
            out.append(r"(?:[^\w.%-]|$)")
        elif ch in r"\.+?()[]{}$|/":
            out.append("\\" + ch)
        else:
            out.append(ch)
    return prefix + "".join(out) + suffix


def popup_entry(pattern, init_inc, init_exc, domain_type, case):
    p = pattern.strip()
    if not p or p in ("*", "|", "||") or (p.startswith("/") and p.endswith("/") and len(p) > 2):
        return None
    entry = {}
    m = PURE_HOST_PATTERN.match(p.lower())
    host = to_ascii_host(m.group(1)) if m else None
    if host and HOST_RE.match(host):
        entry["h"] = host
    else:
        try:
            p.encode("ascii")
        except UnicodeEncodeError:
            return None
        if len(p.replace("*", "").replace("^", "").replace("|", "")) < 4:
            return None
        entry["r"] = abp_to_regex(p if case else p.lower())
    if init_inc:
        entry["d"] = sorted(set(init_inc))
    if init_exc:
        entry["nd"] = sorted(set(init_exc))
    if domain_type:
        entry["tp"] = domain_type == "thirdParty"
    return entry


def pattern_to_condition(pattern, stats):
    """Map an ABP URL pattern to (urlFilter, requestDomain). Returns (None, None) for match-all,
    raises ValueError when the pattern cannot be expressed."""
    p = pattern.strip()
    if p in ("", "*", "|", "||", "|*", "||*"):
        return None, None
    m = PURE_HOST_PATTERN.match(p.lower())
    if m:
        host = to_ascii_host(m.group(1))
        if host and HOST_RE.match(host):
            return None, host
    if p.startswith("/") and p.endswith("/") and len(p) > 2:
        raise ValueError("regex")
    try:
        p.encode("ascii")
    except UnicodeEncodeError:
        # try punycoding a leading host
        m = re.match(r"^(\|\|)([^/^*|]+)(.*)$", p)
        if not m:
            raise ValueError("non-ascii")
        host = to_ascii_host(m.group(2))
        if not host:
            raise ValueError("non-ascii")
        p = m.group(1) + host + m.group(3)
        try:
            p.encode("ascii")
        except UnicodeEncodeError:
            raise ValueError("non-ascii")
    if p.startswith("||*"):
        p = p[2:]
    anchored_start = p.startswith("|")
    body_start = 2 if p.startswith("||") else (1 if anchored_start else 0)
    body_end = len(p) - 1 if (p.endswith("|") and len(p) > body_start) else len(p)
    if "|" in p[body_start:body_end]:
        raise ValueError("inner-pipe")
    if not anchored_start:
        p = p.lstrip("*")
    if not p.endswith("|"):
        p = p.rstrip("*")
    if not p or p in ("|", "||"):
        return None, None
    if len(p.replace("*", "").replace("^", "").replace("|", "")) < 3:
        raise ValueError("too-generic")
    return p, None


def parse_network(line, list_cfg, stats, badfilters):
    """Parse one network filter. Returns dict with keys: kind ('rule'|'cosmetic-flag'), ..."""
    text = line
    exception = text.startswith("@@")
    if exception:
        text = text[2:]
    pattern, opts = split_options(text)
    options = split_option_list(opts) if opts else []

    types_in, types_out = set(), set()
    domain_type = None
    init_inc, init_exc = [], []
    req_inc, req_exc = [], []
    methods_in, methods_out = [], []
    important = False
    case = False
    flags = set()
    saw_all = False
    popup = False

    for opt in options:
        neg = opt.startswith("~")
        body = opt[1:] if neg else opt
        name, value = (body.split("=", 1) + [None])[:2] if "=" in body else (body, None)
        name = name.lower()
        if name in TYPE_OPTIONS:
            t = TYPE_OPTIONS[name]
            if neg:
                if t:
                    types_out.add(t)
            else:
                types_in.add(t)  # may add None (unsupported)
        elif name in ("third-party", "3p"):
            domain_type = "firstParty" if neg else "thirdParty"
        elif name in ("first-party", "1p"):
            domain_type = "thirdParty" if neg else "firstParty"
        elif name == "strict3p":
            domain_type = "thirdParty"
        elif name == "strict1p":
            domain_type = "firstParty"
        elif name in ("domain", "from") and value:
            inc, exc, ok = parse_domain_list(value)
            if not ok and not inc:
                stats.add("skip:domain-unrepresentable")
                return None
            init_inc += inc
            init_exc += exc
        elif name == "to" and value:
            inc, exc, ok = parse_domain_list(value)
            if not ok and not inc:
                stats.add("skip:to-unrepresentable")
                return None
            req_inc += inc
            req_exc += exc
        elif name == "denyallow" and value:
            inc, _, _ = parse_domain_list(value)
            req_exc += inc
        elif name == "match-case":
            case = True
        elif name == "important":
            important = True
        elif name == "method" and value:
            for m in value.lower().split("|"):
                if m.startswith("~"):
                    methods_out.append(m[1:])
                else:
                    methods_in.append(m)
        elif name == "all":
            saw_all = True
            types_in.update(SAFARI_TYPES)
        elif name == "popup":
            if not neg:
                popup = True
        elif name in COSMETIC_FLAGS:
            flags.add(COSMETIC_FLAGS[name])
        elif name == "badfilter":
            continue
        elif name in UNSUPPORTED_OPTIONS:
            stats.add("skip:opt-" + name)
            return None
        else:
            stats.add("skip:unknown-opt-" + name)
            return None

    result = []

    # --- pop-up filters: kept for the runtime pop-up blocker (Safari's DNR has no popup type)
    if popup:
        entry = popup_entry(pattern, init_inc, init_exc, domain_type, case)
        if entry:
            result.append({"kind": "popup", "exception": exception, "entry": entry})
            stats.add("ok:popup-" + ("allow" if exception else "block"))
        else:
            stats.add("skip:popup-pattern")
        if not types_in and not types_out:
            return result or None

    # --- cosmetic switches carried by exception filters ($generichide, $elemhide, ...)
    if flags:
        if not exception:
            stats.add("skip:flag-on-block")
            return None
        hosts = list(init_inc)
        m = re.match(r"^\|\|([a-z0-9.\-_]+)", pattern.lower())
        if m:
            h = to_ascii_host(m.group(1))
            if h:
                hosts.append(h)
        else:
            m = re.match(r"^\|https?://([a-z0-9.\-_]+)", pattern.lower())
            if m:
                h = to_ascii_host(m.group(1))
                if h:
                    hosts.append(h)
        for flag in flags:
            for h in hosts:
                result.append({"kind": "flag", "flag": flag, "host": h})
        if not result:
            stats.add("skip:flag-no-host")
        other_types = {t for t in types_in if t} - {"main_frame"}
        if not other_types and "main_frame" not in types_in:
            stats.add("ok:cosmetic-flag")
            return result

    # --- resource types
    if types_in:
        supported = {t for t in types_in if t}
        if not supported:
            stats.add("skip:unsupported-type")
            return result or None
        types_in = supported
    try:
        url_filter, req_host = pattern_to_condition(pattern, stats)
    except ValueError as e:
        stats.add("skip:pattern-" + str(e))
        return result or None

    cond = {}
    if url_filter:
        cond["urlFilter"] = url_filter
    if req_host:
        req_inc = [req_host] + req_inc if not req_inc else req_inc  # host anchor wins
        if req_inc != [req_host]:
            # both a host pattern and $to=: keep the host pattern as urlFilter instead
            cond["urlFilter"] = "||" + req_host + "^"
            req_inc = [h for h in req_inc if h != req_host] or []
    if req_inc:
        cond["requestDomains"] = sorted(set(req_inc))
    if req_exc:
        cond["excludedRequestDomains"] = sorted(set(req_exc))
    if init_inc:
        cond["initiatorDomains"] = sorted(set(init_inc))
    if init_exc:
        cond["excludedInitiatorDomains"] = sorted(set(init_exc))
    if domain_type:
        cond["domainType"] = domain_type
    if case and "urlFilter" in cond:
        cond["isUrlFilterCaseSensitive"] = True
    if methods_in:
        cond["requestMethods"] = sorted(set(methods_in))
    elif methods_out:
        cond["excludedRequestMethods"] = sorted(set(methods_out))

    if not exception and not ("urlFilter" in cond or "requestDomains" in cond or "initiatorDomains" in cond):
        stats.add("skip:too-broad")
        return result or None

    if exception and "main_frame" in types_in:
        # @@...$document  -> allow everything on matching pages
        rule_cond = {k: v for k, v in cond.items() if k not in ("domainType",)}
        rule_cond["resourceTypes"] = ["main_frame", "sub_frame"]
        if "requestDomains" in rule_cond and "urlFilter" not in rule_cond and len(rule_cond["requestDomains"]) == 1:
            rule_cond["urlFilter"] = "||" + rule_cond.pop("requestDomains")[0] + "^"
        result.append({"kind": "rule", "action": {"type": "allowAllRequests"},
                       "priority": PRIORITY_ALLOW, "condition": rule_cond})
        types_in = types_in - {"main_frame", "sub_frame"}
        if not types_in:
            stats.add("ok:allowAllRequests")
            return result

    if types_in:
        cond["resourceTypes"] = sorted(types_in, key=SAFARI_TYPES.index)
    elif types_out:
        cond["excludedResourceTypes"] = sorted(types_out, key=SAFARI_TYPES.index)

    if exception:
        action, priority = {"type": "allow"}, PRIORITY_ALLOW
    else:
        action, priority = {"type": "block"}, (PRIORITY_IMPORTANT if important else PRIORITY_BLOCK)
    result.append({"kind": "rule", "action": action, "priority": priority, "condition": cond})
    stats.add("ok:network-" + ("allow" if exception else "block"))
    return result


def clean_selector(sel, stats):
    sel = sel.strip()
    if not sel:
        return None
    if sel.startswith("+js(") or sel.startswith("^") or sel.startswith("script:"):
        stats.add("skip:cosmetic-scriptlet-or-html")
        return None
    if ":-abp-has(" in sel:
        sel = sel.replace(":-abp-has(", ":has(")
    if PROCEDURAL_RE.search(sel):
        stats.add("skip:cosmetic-procedural")
        return None
    if any(ch in sel for ch in "{}\n\r") or len(sel) > 1500 or "/*" in sel:
        stats.add("skip:cosmetic-unsafe")
        return None
    return sel


def parse_cosmetic(m, out, stats, scriptlets):
    domains_raw, sep, body = m.group(1), m.group(2), m.group(3)
    if not COSMETIC_DOMAINS_OK.match(domains_raw):
        return False
    exception = "@" in sep
    if sep in ("#$#", "#@$#", "#%#", "#@%#"):
        stats.add("skip:cosmetic-" + sep)
        return True
    if body.startswith("+js("):
        if not exception:
            scriptlets.append({"domains": domains_raw, "body": body})
        stats.add("skip:scriptlet")
        return True
    sel = clean_selector(body, stats)
    if not sel:
        return True
    inc, exc = [], []
    for raw in [d.strip() for d in domains_raw.split(",") if d.strip()]:
        neg = raw.startswith("~")
        d = raw[1:] if neg else raw
        if d.startswith("/"):
            if not neg:
                stats.add("skip:cosmetic-regex-domain")
                return True
            continue
        if d.endswith(".*"):
            d = to_ascii_host(d[:-2])
            d = d + ".*" if d else None
        else:
            d = to_ascii_host(d)
        if not d:
            continue
        (exc if neg else inc).append(d)

    if exception:
        if inc:
            for d in inc:
                out["exceptions"][d].add(sel)
        else:
            out["genericExceptions"].add(sel)
        stats.add("ok:cosmetic-exception")
        return True
    if inc:
        for d in inc:
            out["specific"][d].add(sel)
        for d in exc:
            out["specificNeg"][d].add(sel)
        stats.add("ok:cosmetic-specific")
    else:
        if exc:
            for d in exc:
                out["genericExcluded"][sel].add(d)
        out["generic"].add(sel)
        stats.add("ok:cosmetic-generic")
    return True


def webkit_count(rule):
    """Number of WebKit content-blocker rules Safari generates for one DNR rule."""
    c = rule["condition"]
    t = rule["action"]["type"]
    allow_all = t == "allowAllRequests"
    rd = c.get("requestDomains") if "regexFilter" not in c else None
    rm = c.get("requestMethods") if not allow_all else None
    main_units = (len(rd) if rd else 1) * (len(rm) if rm else 1)
    erd = c.get("excludedRequestDomains")
    erm = c.get("excludedRequestMethods") if not allow_all else None
    if erd:
        excl_units = len(erd) * (len(erm) if erm else 1)
    else:
        excl_units = len(erm) if erm else 0
    per_unit = 1
    if c.get("initiatorDomains") and c.get("excludedInitiatorDomains") and not allow_all:
        per_unit += 1
    if t == "upgradeScheme":
        per_unit += 1
    return (main_units + excl_units) * per_unit


def compile_list(list_cfg, text, stats):
    fmt = list_cfg.get("format", "abp")
    host_action = list_cfg.get("hostAction")
    lines = text.splitlines()
    badfilters = set()
    for raw in lines:
        s = raw.strip()
        if "badfilter" in s and not s.startswith("!"):
            pat, opts = split_options(s)
            kept = [o for o in split_option_list(opts) if o.lower() != "badfilter"]
            badfilters.add(pat + ("$" + ",".join(kept) if kept else ""))

    rules = []
    cosm = {
        "generic": set(), "genericExcluded": defaultdict(set), "genericExceptions": set(),
        "specific": defaultdict(set), "specificNeg": defaultdict(set),
        "exceptions": defaultdict(set),
        "genericHide": set(), "elemHide": set(), "specificHide": set(),
        "popups": [], "popupAllow": [],
    }
    scriptlets = []
    version = None
    for raw in lines:
        s = raw.strip()
        if not s:
            continue
        if s.startswith("!"):
            m = re.match(r"^!\s*(?:Version|Last modified)\s*:\s*(.+)$", s, re.I)
            if m and not version and "%" not in m.group(1):
                version = m.group(1).strip()
            continue
        if s.startswith("[") and s.endswith("]"):
            continue
        if s.startswith("#") and not re.match(r"^#@?[?$%]?#", s):
            continue  # hosts-file comment
        if s in badfilters or "badfilter" in s.lower():
            stats.add("skip:badfilter")
            continue
        if fmt in ("hosts", "mixed-hosts"):
            parts = s.split()
            if len(parts) == 2 and parts[0] in ("0.0.0.0", "127.0.0.1", "::", "::1"):
                s = parts[1]
            if HOST_RE.match(s.lower()) and not s.startswith("||"):
                host = to_ascii_host(s)
                if host:
                    cond = {"requestDomains": [host]}
                    if host_action == "all":
                        cond["resourceTypes"] = list(SAFARI_TYPES)
                    rules.append({"action": {"type": "block"}, "priority": PRIORITY_BLOCK, "condition": cond})
                    stats.add("ok:host")
                continue
        m = COSMETIC_RE.match(s)
        if m and parse_cosmetic(m, cosm, stats, scriptlets):
            continue
        parsed = parse_network(s, list_cfg, stats, badfilters)
        if not parsed:
            continue
        for item in parsed:
            if item["kind"] == "popup":
                cosm["popupAllow" if item["exception"] else "popups"].append(item["entry"])
            elif item["kind"] == "flag":
                cosm[item["flag"]].add(item["host"])
            else:
                rules.append({"action": item["action"], "priority": item["priority"],
                              "condition": item["condition"]})
    return rules, cosm, scriptlets, version


def merge_rules(rules, chunk=2000):
    """Deduplicate rules and fold pure-host rules with identical conditions into requestDomains lists."""
    seen = set()
    folded = defaultdict(set)
    out = []
    for r in rules:
        c = r["condition"]
        if set(c.keys()) - {"resourceTypes", "domainType", "initiatorDomains", "excludedInitiatorDomains"} == {"requestDomains"} \
                and "urlFilter" not in c:
            key = json.dumps({"a": r["action"], "p": r["priority"],
                              "c": {k: v for k, v in c.items() if k != "requestDomains"}}, sort_keys=True)
            folded[key].update(c["requestDomains"])
            continue
        key = json.dumps(r, sort_keys=True)
        if key in seen:
            continue
        seen.add(key)
        out.append(r)
    for key, hosts in folded.items():
        base = json.loads(key)
        hosts = sorted(hosts)
        # drop hosts whose parent domain is already listed (requestDomains matches subdomains)
        hostset = set(hosts)
        pruned = []
        for h in hosts:
            parts = h.split(".")
            if any(".".join(parts[i:]) in hostset for i in range(1, len(parts) - 1)):
                continue
            pruned.append(h)
        for i in range(0, len(pruned), chunk):
            cond = dict(base["c"])
            cond["requestDomains"] = pruned[i:i + chunk]
            out.append({"action": base["a"], "priority": base["p"], "condition": cond})
    out.sort(key=lambda r: 0 if r["action"]["type"] in ("allow", "allowAllRequests") else 1)
    for i, r in enumerate(out, 1):
        r["id"] = i
    return [{"id": r["id"], "priority": r["priority"], "action": r["action"], "condition": r["condition"]} for r in out]


def allow_id_max(rules):
    """Rules are numbered allow-first; ids 1..N are allow rules (not counted as blocks)."""
    n = 0
    for r in rules:
        if r["action"]["type"] in ("allow", "allowAllRequests"):
            n = max(n, r["id"])
    return n


def build_urlclean(path):
    """Built-in 'Clean links' ruleset: strips tracking parameters from page addresses.

    Each parameter gets two rules so a redirect can never loop: a regexFilter that only
    matches when the parameter comes right after the first '?', and a urlFilter for
    '&param='. Matching is case-sensitive because removeParams is.
    """
    cfg = json.load(open(path, encoding="utf-8"))
    params = cfg["global"]
    rules = []
    for prm in params:
        esc = re.escape(prm)
        base = {"priority": 1, "action": {"type": "redirect", "redirect": {"transform": {"queryTransform": {"removeParams": params}}}}}
        rules.append(dict(base, condition={"regexFilter": r"^[^?#]*\?" + esc + "=", "isUrlFilterCaseSensitive": True, "resourceTypes": ["main_frame"]}))
        rules.append(dict(base, condition={"urlFilter": "&" + prm + "=", "isUrlFilterCaseSensitive": True, "resourceTypes": ["main_frame"]}))
    for site in cfg.get("sites", []):
        host = site["host"]
        sp = site["params"]
        base = {"priority": 1, "action": {"type": "redirect", "redirect": {"transform": {"queryTransform": {"removeParams": sp}}}}}
        for prm in sp:
            esc = re.escape(prm)
            rules.append(dict(base, condition={"regexFilter": "^https://" + re.escape(host) + r"/[^?#]*\?" + esc + "=", "isUrlFilterCaseSensitive": True, "resourceTypes": ["main_frame"]}))
            rules.append(dict(base, condition={"urlFilter": "|https://" + host + "/*&" + prm + "=", "isUrlFilterCaseSensitive": True, "resourceTypes": ["main_frame"]}))
    for i, r in enumerate(rules, 1):
        r["id"] = i
    return [{"id": r["id"], "priority": r["priority"], "action": r["action"], "condition": r["condition"]} for r in rules]


def css_escape_for_block(sel):
    return sel + "{display:none!important}"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--lists", default=os.path.join(os.path.dirname(__file__), "lists.json"))
    ap.add_argument("--cache", required=True, help="folder holding <id>.txt downloads")
    ap.add_argument("--out", required=True, help="extension folder")
    ap.add_argument("--report", action="store_true")
    args = ap.parse_args()

    catalogue = json.load(open(args.lists, encoding="utf-8"))["lists"]
    rules_dir = os.path.join(args.out, "rules")
    cosm_dir = os.path.join(args.out, "cosmetic")
    os.makedirs(rules_dir, exist_ok=True)
    os.makedirs(cosm_dir, exist_ok=True)

    compiled = []
    empty_cosm = lambda: {"generic": set(), "genericExcluded": defaultdict(set), "genericExceptions": set(),
                          "specific": defaultdict(set), "specificNeg": defaultdict(set), "exceptions": defaultdict(set),
                          "genericHide": set(), "elemHide": set(), "specificHide": set(), "popups": [], "popupAllow": []}
    for cfg in catalogue:
        if cfg.get("format") == "builtin-urlclean":
            src = os.path.join(os.path.dirname(os.path.abspath(args.lists)), "tracking-params.json")
            rules = build_urlclean(src)
            compiled.append((cfg, rules, empty_cosm(), [], None, Stats(), os.path.getmtime(src)))
            continue
        path = os.path.join(args.cache, cfg["id"] + ".txt")
        if not os.path.exists(path):
            print(f"  ! {cfg['id']}: no download at {path}, skipped", file=sys.stderr)
            continue
        text = open(path, encoding="utf-8", errors="replace").read()
        stats = Stats()
        rules, cosm, scriptlets, version = compile_list(cfg, text, stats)
        rules = merge_rules(rules)
        compiled.append((cfg, rules, cosm, scriptlets, version, stats, os.path.getmtime(path)))

    # Generic selectors that any list excepts somewhere cannot live in the static stylesheet.
    dynamic = set()
    for _, _, cosm, *_ in compiled:
        dynamic |= cosm["genericExceptions"]
        dynamic |= set(cosm["genericExcluded"].keys())
        for sels in cosm["exceptions"].values():
            dynamic |= sels
    global_off = set()
    for _, _, cosm, *_ in compiled:
        global_off |= cosm["genericExceptions"]

    catalogue_out = []
    popup_data = {}
    total_default_webkit = 0
    for cfg, rules, cosm, scriptlets, version, stats, mtime in compiled:
        lid = cfg["id"]
        with open(os.path.join(rules_dir, lid + ".json"), "w", encoding="utf-8") as f:
            json.dump(rules, f, separators=(",", ":"))
        generic_static = sorted(s for s in cosm["generic"] if s not in dynamic)
        generic_dynamic = sorted(s for s in cosm["generic"] if s in dynamic and s not in global_off)
        with open(os.path.join(cosm_dir, lid + ".generic.css"), "w", encoding="utf-8") as f:
            f.write("/* Bouclier generic element hiding: " + cfg["source"] + " */\n")
            for sel in generic_static:
                f.write(css_escape_for_block(sel) + "\n")
        data = {
            "genericDynamic": generic_dynamic,
            "genericExcluded": {k: sorted(v) for k, v in sorted(cosm["genericExcluded"].items())},
            "specific": {k: sorted(v) for k, v in sorted(cosm["specific"].items())},
            "specificNeg": {k: sorted(v) for k, v in sorted(cosm["specificNeg"].items())},
            "exceptions": {k: sorted(v) for k, v in sorted(cosm["exceptions"].items())},
            "genericHide": sorted(cosm["genericHide"]),
            "elemHide": sorted(cosm["elemHide"]),
            "specificHide": sorted(cosm["specificHide"]),
        }
        with open(os.path.join(cosm_dir, lid + ".json"), "w", encoding="utf-8") as f:
            json.dump(data, f, separators=(",", ":"), ensure_ascii=False)
        wk = sum(webkit_count(r) for r in rules)
        if cosm["popups"] or cosm["popupAllow"]:
            popup_data[lid] = {"block": cosm["popups"], "allow": cosm["popupAllow"]}
        if cfg.get("default"):
            total_default_webkit += wk
        entry = {
            "id": lid, "name": cfg["name"], "description": cfg["description"],
            "source": cfg["source"], "homepage": cfg["homepage"], "license": cfg["license"],
            "licenseUrl": cfg.get("licenseUrl", ""), "authors": cfg.get("authors", ""),
            "default": bool(cfg.get("default")),
            "version": version,
            "downloaded": int(mtime),
            "networkRules": len(rules), "webkitRules": wk, "allowIdMax": allow_id_max(rules),
            "popupFilters": len(cosm["popups"]),
            "genericSelectors": len(generic_static), "dynamicSelectors": len(generic_dynamic),
            "specificSites": len(data["specific"]),
            "noGenericHideSites": sorted(set(data["genericHide"]) | set(data["elemHide"])),
        }
        catalogue_out.append(entry)
        if args.report:
            print(f"== {lid}: {len(rules)} DNR rules -> {wk} WebKit rules; generic css {len(generic_static)}, "
                  f"dynamic {len(generic_dynamic)}, specific sites {len(data['specific'])}, scriptlets {len(scriptlets)}")
            for k, v in sorted(stats.c.items(), key=lambda kv: -kv[1])[:18]:
                print(f"     {v:7d}  {k}")

    data_dir = os.path.join(args.out, "data")
    os.makedirs(data_dir, exist_ok=True)
    with open(os.path.join(data_dir, "popups.json"), "w", encoding="utf-8") as f:
        json.dump(popup_data, f, separators=(",", ":"))
    # the extension recognises cleaned links in Safari's match reports with this list
    params_src = os.path.join(os.path.dirname(os.path.abspath(args.lists)), "tracking-params.json")
    if os.path.exists(params_src):
        with open(params_src, encoding="utf-8") as f:
            params = json.load(f)
        params.pop("_comment", None)
        with open(os.path.join(data_dir, "tracking-params.json"), "w", encoding="utf-8") as f:
            json.dump(params, f, separators=(",", ":"))

    total_all = sum(e["webkitRules"] for e in catalogue_out)
    meta = {"generated": int(max((c[6] for c in compiled), default=0)),
            "webkitRuleCap": WEBKIT_RULE_CAP, "defaultWebkitRules": total_default_webkit,
            "allWebkitRules": total_all, "lists": catalogue_out}
    with open(os.path.join(args.out, "filters.json"), "w", encoding="utf-8") as f:
        json.dump(meta, f, indent=1)

    # keep manifest's ruleset table in sync with the catalogue
    man_path = os.path.join(args.out, "manifest.json")
    if os.path.exists(man_path):
        man = json.load(open(man_path, encoding="utf-8"))
        man.setdefault("declarative_net_request", {})["rule_resources"] = [
            {"id": e["id"], "enabled": e["default"], "path": "rules/" + e["id"] + ".json"} for e in catalogue_out]
        with open(man_path, "w", encoding="utf-8") as f:
            json.dump(man, f, indent=2, ensure_ascii=False)
            f.write("\n")

    print(f"WebKit rules: defaults {total_default_webkit:,} / everything {total_all:,} (Safari cap {WEBKIT_RULE_CAP:,})")
    if total_default_webkit > WEBKIT_RULE_CAP * 0.9:
        print("  !! default lists are too close to Safari's cap", file=sys.stderr)
        sys.exit(2)


if __name__ == "__main__":
    main()
