#!/bin/bash
# Downloads the lists compare.py judges with into ./ref (not shipped with Bouclier: GPL-3.0 / MPL).
set -euo pipefail
cd "$(dirname "$0")"; mkdir -p ref
curl -fsSL -o ref/adguard-dns.txt https://raw.githubusercontent.com/AdguardTeam/AdGuardSDNSFilter/gh-pages/Filters/filter.txt
curl -fsSL -o ref/hagezi-pro.txt https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/pro-onlydomains.txt
curl -fsSL -o ref/public_suffix_list.dat https://raw.githubusercontent.com/publicsuffix/list/master/public_suffix_list.dat
wc -l ref/*
