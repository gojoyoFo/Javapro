import os
import re
import shutil

res_folder = "app/src/main/res"
backup_folder = "/sdcard/Backup/"

if not os.path.exists(backup_folder):
    os.makedirs(backup_folder)

string_pattern = re.compile(r'(<string\s+name="([^"]+)".*?>.*?</string>)', re.DOTALL)

for root, dirs, files in os.walk(res_folder):
    for file in files:
        if file == "strings.xml":
            path = os.path.join(root, file)
            # Backup file dulu
            shutil.copy2(path, os.path.join(backup_folder, os.path.basename(path)))

            with open(path, "r", encoding="utf-8") as f:
                lines = f.readlines()

            header_lines = []
            resource_lines = []
            inside_resources = False

            for line in lines:
                if line.strip().startswith("<?xml"):
                    header_lines.append(line.rstrip())
                elif "<resources>" in line:
                    inside_resources = True
                    resource_lines.append(line.rstrip())
                elif "</resources>" in line:
                    inside_resources = False
                    resource_lines.append(line.rstrip())
                elif inside_resources:
                    resource_lines.append(line.rstrip())

            # Hapus duplikat strings
            seen = set()
            new_resource_lines = []
            for line in resource_lines:
                match = string_pattern.search(line)
                if match:
                    name = match.group(2)
                    if name not in seen:
                        new_resource_lines.append(line)
                        seen.add(name)
                else:
                    new_resource_lines.append(line)

            # Tulis ulang file
            with open(path, "w", encoding="utf-8") as f:
                for h in header_lines:
                    f.write(h + "\n")
                for r in new_resource_lines:
                    f.write(r + "\n")

            print(f"Processed {path}, backup ada di {backup_folder}")
