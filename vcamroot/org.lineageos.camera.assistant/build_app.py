import os, sys, subprocess, zipfile, shutil, re

try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass

app_dir = os.path.dirname(os.path.abspath(__file__))
android_jar = r"C:\Users\admin\AppData\Local\Android\Sdk\platforms\android-33\android.jar"
d8_jar = r"C:\Users\admin\AppData\Local\Android\Sdk\build-tools\37.0.0\lib\d8.jar"

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

# Helper for XOR String Encryption
def gen_xor_java_call(plain_text, key=0x7B):
    data = plain_text.encode('utf-8')
    enc = [(b ^ key) for b in data]
    bytes_str = ", ".join(str(b if b < 128 else b - 256) for b in enc)
    return f"_xdec(new byte[]{{{bytes_str}}}, (byte){key})"

print("[3/5] Pre-processing & Compiling Java code with javac...")
src_proc = "build/src_proc"
if os.path.exists(src_proc):
    shutil.rmtree(src_proc)
shutil.copytree("src/main/java", src_proc)

lic_manager_path = os.path.join(src_proc, "org", "lineageos", "camera", "assistant", "LicenseManager.java")
if os.path.exists(lic_manager_path):
    with open(lic_manager_path, "r", encoding="utf-8") as f:
        content = f.read()

    server_match = re.search(r'public\s+static\s+final\s+String\s+SERVER_URL\s*=\s*"([^"]+)";', content)
    check_match = re.search(r'public\s+static\s+final\s+String\s+CHECK_URL\s*=\s*"([^"]+)";', content)
    pubkey_match = re.search(r'public\s+static\s+final\s+String\s+PUBLIC_KEY_PEM\s*=\s*"([^"]+)";', content, re.DOTALL)

    if server_match and check_match and pubkey_match:
        server_url = server_match.group(1)
        check_url = check_match.group(1)
        pubkey_pem = pubkey_match.group(1).replace('\\n', '\n').replace('\\r', '')

        enc_server = gen_xor_java_call(server_url, 0x4D)
        enc_check = gen_xor_java_call(check_url, 0x5E)
        enc_pubkey = gen_xor_java_call(pubkey_pem, 0x6A)
        enc_head = gen_xor_java_call("-----BEGIN PUBLIC KEY-----", 0x33)
        enc_tail = gen_xor_java_call("-----END PUBLIC KEY-----", 0x44)
        enc_lic_tmp = gen_xor_java_call("/data/local/tmp/vcam.lic", 0x1A)
        enc_lic_sd = gen_xor_java_call("/sdcard/vcam.lic", 0x2B)

        content = re.sub(
            r'public\s+static\s+final\s+String\s+SERVER_URL\s*=\s*"[^"]+";',
            f'public static final String SERVER_URL = {enc_server};',
            content
        )
        content = re.sub(
            r'public\s+static\s+final\s+String\s+CHECK_URL\s*=\s*"[^"]+";',
            f'public static final String CHECK_URL = {enc_check};',
            content
        )
        content = re.sub(
            r'public\s+static\s+final\s+String\s+PUBLIC_KEY_PEM\s*=\s*"[^"]+";',
            f'public static final String PUBLIC_KEY_PEM = {enc_pubkey};',
            content,
            flags=re.DOTALL
        )
        content = re.sub(
            r'private\s+static\s+final\s+String\s+LIC_FILE_TMP\s*=\s*"[^"]+";',
            f'private static final String LIC_FILE_TMP = {enc_lic_tmp};',
            content
        )
        content = re.sub(
            r'private\s+static\s+final\s+String\s+LIC_FILE_SD\s*=\s*"[^"]+";',
            f'private static final String LIC_FILE_SD = {enc_lic_sd};',
            content
        )
        content = content.replace('"-----BEGIN PUBLIC KEY-----"', enc_head)
        content = content.replace('"-----END PUBLIC KEY-----"', enc_tail)

        last_brace = content.rfind("}")
        if last_brace != -1:
            helper_code = """
    private static String _xdec(byte[] b, byte k) {
        byte[] r = new byte[b.length];
        for (int i = 0; i < b.length; i++) {
            r[i] = (byte)(b[i] ^ k);
        }
        try {
            return new String(r, "UTF-8");
        } catch (Throwable t) {
            return new String(r);
        }
    }
"""
            content = content[:last_brace] + helper_code + content[last_brace:]

        with open(lic_manager_path, "w", encoding="utf-8") as f:
            f.write(content)
        print("  -> Sensitive strings encrypted in build/src_proc/LicenseManager.java")

java_files = []
for root, _, files in os.walk(src_proc):
    for f in files:
        if f.endswith(".java"):
            java_files.append(os.path.join(root, f))
for root, _, files in os.walk("build/gen"):
    for f in files:
        if f.endswith(".java"):
            java_files.append(os.path.join(root, f))

if os.path.exists("build/obj"):
    shutil.rmtree("build/obj")
os.makedirs("build/obj", exist_ok=True)

subprocess.check_call(["javac", "-cp", f"{android_jar};build/gen", "-d", "build/obj"] + java_files, shell=True)

if os.path.exists(src_proc):
    shutil.rmtree(src_proc)

print(f"[4/5] Converting classes to dex ({build_mode} mode)...")

app_classes_jar = "build/app_classes.jar"
xposed_stubs_jar = "build/xposed_stubs.jar"

with zipfile.ZipFile(app_classes_jar, "w") as app_jar, zipfile.ZipFile(xposed_stubs_jar, "w") as stubs_jar:
    for root, _, files in os.walk("build/obj"):
        for f in files:
            if f.endswith(".class"):
                full_p = os.path.join(root, f)
                rel_p = os.path.relpath(full_p, "build/obj").replace("\\", "/")
                if rel_p.startswith("de/robv"):
                    stubs_jar.write(full_p, rel_p)
                else:
                    app_jar.write(full_p, rel_p)

if build_mode == "release" and os.path.exists("proguard-rules.pro") and os.path.exists(d8_jar):
    print("  -> Running Google R8 Obfuscator & Minifier with proguard-rules.pro...")
    if os.path.exists("build/apk/classes.dex"):
        os.remove("build/apk/classes.dex")
    r8_cmd = [
        "java", "-cp", d8_jar,
        "com.android.tools.r8.R8",
        "--release",
        "--min-api", "28",
        "--output", "build/apk",
        "--lib", android_jar,
        "--classpath", xposed_stubs_jar,
        "--pg-conf", "proguard-rules.pro",
        "--pg-map-output", "build/mapping.txt",
        app_classes_jar
    ]
    subprocess.check_call(r8_cmd)
else:
    d8_mode_flag = "--release" if build_mode == "release" else "--debug"
    class_files = []
    for root, _, files in os.walk("build/obj"):
        for f in files:
            if f.endswith(".class"):
                rel_path = os.path.relpath(os.path.join(root, f), "build/obj")
                if not rel_path.startswith("de" + os.sep + "robv") and not rel_path.startswith("de/robv"):
                    class_files.append(os.path.join(root, f))
    subprocess.check_call(["d8", d8_mode_flag, "--min-api", "28", "--output", "build/apk"] + class_files, shell=True)

print("[5/5] Packaging classes.dex and signing APK...")
with zipfile.ZipFile("build/apk/app-unsigned.apk", "a") as apk:
    apk.write("build/apk/classes.dex", "classes.dex")
    if os.path.exists("src/main/assets"):
        for root, _, files in os.walk("src/main/assets"):
            for f in files:
                full_path = os.path.join(root, f)
                rel_path = os.path.relpath(full_path, "src/main").replace("\\", "/")
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
