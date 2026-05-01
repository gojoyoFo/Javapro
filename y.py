#!/usr/bin/env python3
"""
Fix: add formatted="false" to string keys that have multiple %d/%s args
without positional notation, across all locale strings.xml files.
"""

import os
import re
import sys

KEYS_TO_FIX = {
    "notif_time_hour_left",
    "notif_time_hour_to_full",
}

PATTERN = re.compile(
    r'(<string\s+name="(' + '|'.join(re.escape(k) for k in KEYS_TO_FIX) + r')")(>)'
)

def fix_file(path: str) -> bool:
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()

    new_content, count = PATTERN.subn(
        lambda m: m.group(1) + ' formatted="false"' + m.group(3),
        content
    )

    if count == 0:
        return False

    with open(path, "w", encoding="utf-8") as f:
        f.write(new_content)

    print(f"  Fixed {count} key(s) in: {path}")
    return True


def main():
    if len(sys.argv) < 2:
        print("Usage: python fix_strings.py <res_directory>")
        print("Example: python fix_strings.py app/src/main/res")
        sys.exit(1)

    res_dir = sys.argv[1]
    if not os.path.isdir(res_dir):
        print(f"ERROR: Directory not found: {res_dir}")
        sys.exit(1)

    total = 0
    for entry in os.scandir(res_dir):
        if not entry.is_dir():
            continue
        if not (entry.name == "values" or entry.name.startswith("values-")):
            continue
        xml_path = os.path.join(entry.path, "strings.xml")
        if not os.path.isfile(xml_path):
            continue
        if fix_file(xml_path):
            total += 1

    if total == 0:
        print("No files needed fixing (keys may already have formatted=\"false\" or don't exist).")
    else:
        print(f"\nDone. Fixed {total} file(s).")


if __name__ == "__main__":
    main()
