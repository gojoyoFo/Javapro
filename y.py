import os
import re

res_folder = "app/src/main/res"
string_pattern = re.compile(r'(<string\s+name="([^"]+)".*?>.*?</string>)', re.DOTALL)

for root, dirs, files in os.walk(res_folder):
    for file in files:
        if file == "strings.xml":
            path = os.path.join(root, file)
            with open(path, "r", encoding="utf-8") as f:
                content = f.read()

            matches = string_pattern.findall(content)
            seen = set()
            new_lines = []

            for full_tag, name in matches:
                if name not in seen:
                    new_lines.append(full_tag)
                    seen.add(name)

            new_content = "\n".join(new_lines)
            with open(path, "w", encoding="utf-8") as f:
                f.write(new_content)

            print(f"Processed {path}, removed duplicates if any.")
