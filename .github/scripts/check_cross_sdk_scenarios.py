"""Checks that the vendored cross-SDK scenarios match absmartly/cross-sdk-tests at the pinned commits.

Each fixture records the upstream commit it was copied from. This fetches test_scenarios_complete.json
at that commit and compares the selected scenarios with the vendored ones, so an edit to a copy, or a
commit label that does not match its content, fails CI. To update a fixture, regenerate it from a
newer commit with --update <fixture> <commit>, where <fixture> is a key of FIXTURES.
"""

import json
import os
import sys
import urllib.request

DIRECTORY = "android-sdk/src/test/resources/cross-sdk/"
URL = "https://api.github.com/repos/absmartly/cross-sdk-tests/contents/test_scenarios_complete.json?ref={commit}"

FIXTURES = {
    "assignment_rules_semver_scenarios.json": ((230, 253), (300, 323)),
    "holdout_scenarios.json": ((203, 227),),
}


def describe(ranges):
    return " and ".join("%d-%d" % r for r in ranges)


def selected(scenarios, ranges):
    def number(scenario):
        return int(scenario["name"].split(" ", 1)[0])

    return [s for s in scenarios if any(lo <= number(s) <= hi for lo, hi in ranges)]


def upstream(commit, ranges):
    headers = {"Accept": "application/vnd.github.raw"}
    if os.environ.get("GITHUB_TOKEN"):
        headers["Authorization"] = "Bearer " + os.environ["GITHUB_TOKEN"]
    request = urllib.request.Request(URL.format(commit=commit), headers=headers)
    with urllib.request.urlopen(request, timeout=60) as response:
        return selected(json.load(response), ranges)


def update(name, commit):
    ranges = FIXTURES[name]
    fixture = {
        "source": "absmartly/cross-sdk-tests test_scenarios_complete.json, scenarios " + describe(ranges),
        "commit": commit,
        "scenarios": upstream(commit, ranges),
    }
    with open(DIRECTORY + name, "w", encoding="utf-8") as f:
        json.dump(fixture, f, indent=1, ensure_ascii=False)
        f.write("\n")


def check(name):
    ranges = FIXTURES[name]
    with open(DIRECTORY + name, encoding="utf-8") as f:
        fixture = json.load(f)
    expected = upstream(fixture["commit"], ranges)
    if fixture["scenarios"] != expected or not expected:
        vendored = {s["name"]: s for s in fixture["scenarios"]}
        pinned = {s["name"]: s for s in expected}
        print("%s: the vendored scenarios differ from cross-sdk-tests@%s" % (name, fixture["commit"]))
        for scenario in sorted(set(vendored) | set(pinned)):
            if scenario not in pinned:
                print("  only in the fixture: " + scenario)
            elif scenario not in vendored:
                print("  only upstream: " + scenario)
            elif vendored[scenario] != pinned[scenario]:
                print("  changed: " + scenario)
        return False
    print("%s: %d scenarios match cross-sdk-tests@%s" % (name, len(expected), fixture["commit"]))
    return True


USAGE = "usage: %s [--update <fixture> <commit>], <fixture> one of: %s"


def main(argv):
    if len(argv) == 4 and argv[1] == "--update" and argv[2] in FIXTURES:
        update(argv[2], argv[3])
        return 0
    if len(argv) != 1:
        print(USAGE % (argv[0], ", ".join(FIXTURES)), file=sys.stderr)
        return 2
    results = [check(name) for name in FIXTURES]
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
