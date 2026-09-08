import os, sys, subprocess, zipfile, shutil

try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass

app_dir = os.path.dirname(os.path.abspath(__file__))
android_jar = r"C:\Users\admin\AppData\Local\Android\Sdk\platforms\android-33\android.jar"

build_mode = "debug"
if len(sys.argv) > 1 and sys.argv[1].lower() in ["release", "--release", "-r"]:
    build_mode = "release"

print(f"=== CHE DO BIEN DICH: {build_mode.upper()} ===")

os.chdir(app_dir)

os.makedirs("build/gen", exist_ok=True)
os.makedirs("build/obj", exist_ok=True)
os.makedirs("build/apk", exist_ok=True)

print("[1/5] Compiling resources with aapt2...")
subprocess.check_call(["aapt2", "compile", "--dir", "src/main/res", "-o", "build/res.zip"], shell=True)

print("[2/5] Linking resources with aapt2...")
subprocess.check_call([
    "aapt2", "link",
    "-I", android_jar,
    "--manifest", "src/main/AndroidManifest.xml",
    "--java", "build/gen",
    "-o", "build/apk/app-unsigned.apk",
    "build/res.zip"
], shell=True)

print("[3/5] Compiling Java code with javac...")
java_files = []
for root, _, files in os.walk("src/main/java"):
    for f in files:
        if f.endswith(".java"):
            java_files.append(os.path.join(root, f))
for root, _, files in os.walk("build/gen"):
    for f in files:
        if f.endswith(".java"):
            java_files.append(os.path.join(root, f))

subprocess.check_call(["javac", "-cp", f"{android_jar};build/gen", "-d", "build/obj"] + java_files, shell=True)

print(f"[4/5] Converting classes to dex with d8 ({build_mode} mode)...")
class_files = []
for root, _, files in os.walk("build/obj"):
    for f in files:
        if f.endswith(".class"):
            class_files.append(os.path.join(root, f))

d8_mode_flag = "--release" if build_mode == "release" else "--debug"
subprocess.check_call(["d8", d8_mode_flag, "--min-api", "33", "--output", "build/apk"] + class_files, shell=True)

print("[5/5] Packaging classes.dex and signing APK...")
with zipfile.ZipFile("build/apk/app-unsigned.apk", "a") as apk:
    apk.write("build/apk/classes.dex", "classes.dex")
    if os.path.exists("src/main/assets"):
        for root, _, files in os.walk("src/main/assets"):
            for f in files:
                full_path = os.path.join(root, f)
                rel_path = os.path.relpath(full_path, "src/main")
                apk.write(full_path, rel_path)

# ZipAlign APK
aligned_apk = "build/apk/app-aligned.apk"
if os.path.exists(aligned_apk):
    try:
        os.remove(aligned_apk)
    except Exception:
        pass

try:
    subprocess.check_call(["zipalign", "-p", "-f", "4", "build/apk/app-unsigned.apk", aligned_apk], shell=True)
    apk_to_sign = aligned_apk
except Exception as e:
    print(f"Warning: zipalign skipped ({e}), signing unaligned apk...")
    apk_to_sign = "build/apk/app-unsigned.apk"

if build_mode == "release":
    release_keystore = "release.keystore"
    release_alias = "vcam_release"
    release_pass = "vcam123456"
    key_pass = "vcam123456"

    # Đọc cấu hình từ keystore.properties nếu có
    keystore_props = "keystore.properties"
    if os.path.exists(keystore_props):
        try:
            with open(keystore_props, "r", encoding="utf-8") as pf:
                for line in pf:
                    line = line.strip()
                    if "=" in line and not line.startswith("#"):
                        k, v = line.split("=", 1)
                        k, v = k.strip(), v.strip()
                        if k == "keystore_file": release_keystore = v
                        elif k == "key_alias": release_alias = v
                        elif k == "keystore_pass": release_pass = v
                        elif k == "key_pass": key_pass = v
        except Exception as pe:
            print(f"Lưu ý: Không đọc được keystore.properties ({pe}), dùng mặc định.")
    
    if not os.path.exists(release_keystore):
        print(f"Tạo mới {release_keystore} bằng keytool...")
        subprocess.check_call([
            "keytool", "-genkeypair", "-v",
            "-keystore", release_keystore,
            "-alias", release_alias,
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-validity", "10000",
            "-storepass", release_pass,
            "-keypass", key_pass,
            "-dname", "CN=CameraAssistant, OU=LineageOS, O=Pixel4, L=VN, ST=VN, C=VN"
        ], shell=True)

    out_name = "CameraAssistant-release.apk"
    subprocess.check_call([
        "apksigner", "sign",
        "--ks", release_keystore,
        "--ks-pass", f"pass:{release_pass}",
        "--ks-key-alias", release_alias,
        "--key-pass", f"pass:{key_pass}",
        "--out", out_name,
        apk_to_sign
    ], shell=True)
else:
    out_name = "CameraAssistant-debug.apk"
    subprocess.check_call([
        "apksigner", "sign",
        "--ks", "debug.keystore",
        "--ks-pass", "pass:android",
        "--out", out_name,
        apk_to_sign
    ], shell=True)

shutil.copyfile(out_name, "CameraAssistant.apk")
print(f"SUCCESS: {out_name} (and CameraAssistant.apk) built and signed successfully!")
