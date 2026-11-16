"""Checks that the vendored cross-SDK scenarios match absmartly/cross-sdk-tests at the pinned commit.

The fixture records the upstream commit it was copied from. This fetches test_scenarios_complete.json
at that commit and compares the scenarios with the vendored ones, so an edit to the copy, or a commit
label that does not match its content, fails CI. To update the fixture, regenerate it from a newer
commit with --update <commit>.
"""

import json
import os
import sys
import urllib.request

FIXTURE = "android-sdk/src/test/resources/cross-sdk/assignment_rules_semver_scenarios.json"
URL = "https://api.github.com/repos/absmartly/cross-sdk-tests/contents/test_scenarios_complete.json?ref={commit}"
RANGES = ((230, 253), (300, 323))


def selected(scenarios):
    def number(scenario):
        return int(scenario["name"].split(" ", 1)[0])

    return [s for s in scenarios if any(lo <= number(s) <= hi for lo, hi in RANGES)]


def upstream(commit):
    headers = {"Accept": "application/vnd.github.raw"}
    if os.environ.get("GITHUB_TOKEN"):
        headers["Authorization"] = "Bearer " + os.environ["GITHUB_TOKEN"]
    request = urllib.request.Request(URL.format(commit=commit), headers=headers)
    with urllib.request.urlopen(request, timeout=60) as response:
        return selected(json.load(response))


def main(argv):
    if len(argv) == 3 and argv[1] == "--update":
        commit = argv[2]
        fixture = {
            "source": "absmartly/cross-sdk-tests test_scenarios_complete.json, scenarios 230-253 and 300-323",
            "commit": commit,
            "scenarios": upstream(commit),
        }
        with open(FIXTURE, "w", encoding="utf-8") as f:
            json.dump(fixture, f, indent=1, ensure_ascii=False)
            f.write("\n")
        return 0

    with open(FIXTURE, encoding="utf-8") as f:
        fixture = json.load(f)
    expected = upstream(fixture["commit"])
    if fixture["scenarios"] != expected:
        vendored = {s["name"]: s for s in fixture["scenarios"]}
        pinned = {s["name"]: s for s in expected}
        print("The vendored scenarios differ from cross-sdk-tests@" + fixture["commit"])
        for name in sorted(set(vendored) | set(pinned)):
            if name not in pinned:
                print("  only in the fixture: " + name)
            elif name not in vendored:
                print("  only upstream: " + name)
            elif vendored[name] != pinned[name]:
                print("  changed: " + name)
        return 1
    print("%d scenarios match cross-sdk-tests@%s" % (len(expected), fixture["commit"]))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
