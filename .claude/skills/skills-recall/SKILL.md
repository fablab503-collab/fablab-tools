---
name: skills-recall
description: Find and load exactly one procedural skill from the FabLab 503 Skills library (2,400+ skills on cooking, cycling, photo and video, 3D and Unreal, software, iOS and Android apps, health, home and more) when a task hits a how-do-I or why-is-this-failing question. The library lives in the fablab503-collab/second-brain repository, not here - attach it first in a web session. Search with the problem in plain words, then load one skill by id. Never read the library in bulk or install it.
---

# Skills recall from fablab-tools

The library is in `fablab503-collab/second-brain`, folder `Skills/`: one file per skill, about
340 tokens each, more than 900,000 tokens in total. It is recalled, never carried, and never
copied into this repository.

## Procedure

1. **Reach the library.**
   - Web session (Claude Code on claude.ai): call `add_repo` with owner `fablab503-collab` and
     repo `second-brain`, run the clone command it returns, and use that clone's path below.
     Once per session is enough.
   - On the Mac: the vault is at `/Volumes/Volume1/SecondBrain`; use that path.
2. **Search** with the problem in the words a person would use when it goes wrong -
   "chain keeps skipping under load", "compose list is janky when scrolling", "sauce split when
   I added butter" - not the name of the technique:
   `python3 <path>/Skills/_scripts/recall.py "the problem in plain words"`
3. **Load one.** Take the best id from the result, usually the first:
   `python3 <path>/Skills/_scripts/recall.py --show <domain>/<skill>`
4. **Use it and stop.** A skill has a procedure, pitfalls, a verify step, and sometimes a
   `Related skills` section. Follow a related skill only if the first did not answer.
5. **Did not answer?** Search again with different words. Do not load two or three skills at
   once; that is the failure mode the library exists to avoid.

## Browsing, only when you cannot name the problem

`Skills/DOMAINS.tsv` lists the 182 domains at about 5,400 tokens;
`recall.py --domain <slug> --list` lists one domain's skills at about 200 tokens. Prefer a search.

## Writing a correction back

A skill is wrong? Fix its row in `Skills/_meta/wave-001/<domain>.tsv` in the second-brain
repository, then run `python3 Skills/_scripts/build.py --force --only <domain>` and
`python3 Skills/_scripts/index.py` there. Editing only the `.md` is lost on the next rebuild.

## Never

- Read `Skills/index.tsv` end to end (190,000 tokens) or glob the skill files.
- Copy `Skills/` into this repository, or install it as skills: the metadata alone is about
  62,000 tokens a session. This one skill is the only one that belongs installed.
