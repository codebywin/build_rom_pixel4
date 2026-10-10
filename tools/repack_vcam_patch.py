import os, sys

def repack_patch():
    patch_path = 'patches/vcam_pixel4.patch'
    with open(patch_path, 'r', encoding='utf-8', errors='ignore') as f:
        content = f.read()

    parts = content.split('diff --git ')
    print(f'Total parts: {len(parts)-1}')

    updated_map = {
        8: ('core/java/android/hardware/camera2/impl/VcamConfig.java', 'temp_patch_src/VcamConfig.java'),
        11: ('core/java/android/hardware/camera2/impl/VcamRenderer.java', 'temp_patch_src/VcamRenderer.java'),
        12: ('core/java/android/hardware/camera2/impl/VcamColorSync.java', 'temp_patch_src/VcamColorSync.java'),
        15: ('core/java/android/hardware/camera2/impl/VcamStillCapture.java', 'temp_patch_src/VcamStillCapture.java')
    }

    new_parts = [parts[0]] # leading empty or comments if any

    for i in range(1, len(parts)):
        if i in updated_map:
            repo_path, src_file = updated_map[i]
            with open(src_file, 'r', encoding='utf-8') as sf:
                src_lines = sf.read().splitlines()
            line_count = len(src_lines)

            hunk_header = (
                f"a/{repo_path} b/{repo_path}\n"
                f"new file mode 100644\n"
                f"--- /dev/null\n"
                f"+++ b/{repo_path}\n"
                f"@@ -0,0 +1,{line_count} @@\n"
            )
            body = "\n".join("+" + l for l in src_lines) + "\n"
            new_parts.append(hunk_header + body)
            print(f"Updated part {i}: {repo_path} ({line_count} lines)")
        else:
            new_parts.append(parts[i])

    new_content = 'diff --git '.join(new_parts)

    # Backup original before overwriting
    backup_path = patch_path + '.pre_face_fix'
    if not os.path.exists(backup_path):
        with open(backup_path, 'w', encoding='utf-8', newline='\n') as bf:
            bf.write(content)
        print(f"Saved backup to {backup_path}")

    with open(patch_path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(new_content)

    print(f"Repacked {patch_path} successfully. Total size: {len(new_content)} bytes.")

if __name__ == '__main__':
    repack_patch()

