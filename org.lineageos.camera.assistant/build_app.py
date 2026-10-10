import os, sys, subprocess, zipfile, shutil, re, time

def safe_rmtree(path):
    if not os.path.exists(path):
        return
    for attempt in range(5):
        try:
            shutil.rmtree(path)
            return
        except Exception:
            time.sleep(0.3)
    if os.name == 'nt' and os.path.exists(path):
        subprocess.run(f'cmd /c "rd /s /q \\"{os.path.abspath(path)}\\""', shell=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass

app_dir = os.path.dirname(os.path.abspath(__file__))

user_home = os.path.expanduser("~")
default_sdk = os.path.join(user_home, "AppData", "Local", "Android", "Sdk")
sdk_dir = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or default_sdk

# Find Java/JDK bin
java_bin = None
possible_javas = [
    r"C:\Program Files\Android\Android Studio\jbr\bin",
    os.path.join(os.environ.get("JAVA_HOME", ""), "bin")
]
for p in possible_javas:
    if os.path.exists(os.path.join(p, "javac.exe")):
        java_bin = p
        break

# Find highest build-tools version
build_tools_dir = None
build_tools_parent = os.path.join(sdk_dir, "build-tools")
if os.path.exists(build_tools_parent):
    versions = sorted([v for v in os.listdir(build_tools_parent) if os.path.isdir(os.path.join(build_tools_parent, v))], reverse=True)
    if versions:
        build_tools_dir = os.path.join(build_tools_parent, versions[0])

# Find platform android.jar
android_jar = None
platforms_parent = os.path.join(sdk_dir, "platforms")
if os.path.exists(platforms_parent):
    p_versions = sorted([v for v in os.listdir(platforms_parent) if os.path.isdir(os.path.join(platforms_parent, v))], reverse=True)
    for pv in p_versions:
        ajar = os.path.join(platforms_parent, pv, "android.jar")
        if os.path.exists(ajar):
            android_jar = ajar
            break

d8_jar = os.path.join(build_tools_dir, "lib", "d8.jar") if build_tools_dir else ""
apksigner_jar = os.path.join(build_tools_dir, "lib", "apksigner.jar") if build_tools_dir else ""

# Prepend java_bin and build_tools_dir to PATH
extra_paths = []
if java_bin and java_bin not in os.environ.get("PATH", ""):
    extra_paths.append(java_bin)
if build_tools_dir and build_tools_dir not in os.environ.get("PATH", ""):
    extra_paths.append(build_tools_dir)
if extra_paths:
    os.environ["PATH"] = os.pathsep.join(extra_paths) + os.pathsep + os.environ.get("PATH", "")

build_mode = "debug"
if len(sys.argv) > 1 and sys.argv[1].lower() in ["release", "--release", "-r"]:
    build_mode = "release"

print(f"=== CHE DO BIEN DICH: {build_mode.upper()} ===")

os.chdir(app_dir)

BDIR = "build_out"

os.makedirs(f"{BDIR}/gen", exist_ok=True)
os.makedirs(f"{BDIR}/obj", exist_ok=True)
os.makedirs(f"{BDIR}/apk", exist_ok=True)

print("[1/5] Compiling resources with aapt2...")
subprocess.check_call(["aapt2", "compile", "--dir", "src/main/res", "-o", f"{BDIR}/res.zip"], shell=True)

print("[2/5] Linking resources with aapt2...")
subprocess.check_call([
    "aapt2", "link",
    "-I", android_jar,
    "--manifest", "src/main/AndroidManifest.xml",
    "--java", f"{BDIR}/gen",
    "-o", f"{BDIR}/apk/app-unsigned.apk",
    f"{BDIR}/res.zip"
], shell=True)

# Helper for XOR String Encryption
def gen_xor_java_call(plain_text, key=0x7B):
    data = plain_text.encode('utf-8')
    enc = [(b ^ key) for b in data]
    bytes_str = ", ".join(str(b if b < 128 else b - 256) for b in enc)
    return f"_xdec(new byte[]{{{bytes_str}}}, (byte){key})"

print("[3/5] Pre-processing & Compiling Java code with javac...")
src_proc = f"{BDIR}/src_proc"
safe_rmtree(src_proc)
shutil.copytree("src/main/java", src_proc, dirs_exist_ok=True)

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
        print(f"  -> Sensitive strings encrypted in {BDIR}/src_proc/LicenseManager.java")

java_files = []
for root, _, files in os.walk(src_proc):
    for f in files:
        if f.endswith(".java"):
            java_files.append(os.path.join(root, f))
for root, _, files in os.walk(f"{BDIR}/gen"):
    for f in files:
        if f.endswith(".java"):
            java_files.append(os.path.join(root, f))

safe_rmtree(f"{BDIR}/obj")
os.makedirs(f"{BDIR}/obj", exist_ok=True)

subprocess.check_call(["javac", "-source", "8", "-target", "8", "-encoding", "utf-8", "-cp", f"{android_jar};{BDIR}/gen", "-d", f"{BDIR}/obj"] + java_files, shell=True)

safe_rmtree(src_proc)

print(f"[4/5] Converting classes to dex ({build_mode} mode)...")

app_classes_jar = f"{BDIR}/app_classes.jar"

with zipfile.ZipFile(app_classes_jar, "w") as app_jar:
    for root, _, files in os.walk(f"{BDIR}/obj"):
        for f in files:
            if f.endswith(".class"):
                full_p = os.path.join(root, f)
                rel_p = os.path.relpath(full_p, f"{BDIR}/obj").replace("\\", "/")
                app_jar.write(full_p, rel_p)

if build_mode == "release" and os.path.exists("proguard-rules.pro") and os.path.exists(d8_jar):
    print("  -> Running Google R8 Obfuscator & Minifier with proguard-rules.pro...")
    if os.path.exists(f"{BDIR}/apk/classes.dex"):
        os.remove(f"{BDIR}/apk/classes.dex")
    r8_cmd = [
        "java", "-cp", d8_jar,
        "com.android.tools.r8.R8",
        "--release",
        "--min-api", "28",
        "--output", f"{BDIR}/apk",
        "--lib", android_jar,
        "--pg-conf", "proguard-rules.pro",
        "--pg-map-output", f"{BDIR}/mapping.txt",
        app_classes_jar
    ]
    subprocess.check_call(r8_cmd)
else:
    d8_mode_flag = "--release" if build_mode == "release" else "--debug"
    class_files = []
    for root, _, files in os.walk(f"{BDIR}/obj"):
        for f in files:
            if f.endswith(".class"):
                class_files.append(os.path.join(root, f))
    subprocess.check_call(["d8", d8_mode_flag, "--min-api", "28", "--output", f"{BDIR}/apk"] + class_files, shell=True)

print("[5/5] Packaging classes.dex and signing APK...")
with zipfile.ZipFile(f"{BDIR}/apk/app-unsigned.apk", "a") as apk:
    apk.write(f"{BDIR}/apk/classes.dex", "classes.dex")
    if os.path.exists("src/main/assets"):
        for root, _, files in os.walk("src/main/assets"):
            for f in files:
                full_path = os.path.join(root, f)
                rel_path = os.path.relpath(full_path, "src/main").replace("\\", "/")
                apk.write(full_path, rel_path)

# ZipAlign APK
aligned_apk = f"{BDIR}/apk/app-aligned.apk"
if os.path.exists(aligned_apk):
    try:
        os.remove(aligned_apk)
    except Exception:
        pass

try:
    subprocess.check_call(["zipalign", "-p", "-f", "4", f"{BDIR}/apk/app-unsigned.apk", aligned_apk], shell=True)
    apk_to_sign = aligned_apk
except Exception as e:
    print(f"Warning: zipalign skipped ({e}), signing unaligned apk...")
    apk_to_sign = f"{BDIR}/apk/app-unsigned.apk"

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
    sign_args = [
        "sign",
        "--ks", release_keystore,
        "--ks-pass", f"pass:{release_pass}",
        "--ks-key-alias", release_alias,
        "--key-pass", f"pass:{key_pass}",
        "--out", out_name,
        apk_to_sign
    ]
    if apksigner_jar and os.path.exists(apksigner_jar):
        subprocess.check_call(["java", "-jar", apksigner_jar] + sign_args)
    else:
        subprocess.check_call(["apksigner"] + sign_args, shell=True)
else:
    out_name = os.path.join(app_dir, "CameraAssistant-debug.apk")
    debug_ks = os.path.join(app_dir, "debug.keystore")
    if not os.path.exists(debug_ks):
        user_ks = os.path.join(user_home, ".android", "debug.keystore")
        if os.path.exists(user_ks):
            shutil.copyfile(user_ks, debug_ks)
        else:
            print("Tạo mới debug.keystore bằng keytool...")
            subprocess.run([
                "keytool", "-genkeypair", "-v",
                "-keystore", debug_ks,
                "-alias", "androiddebugkey",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "10000",
                "-storepass", "android",
                "-keypass", "android",
                "-dname", "CN=Android Debug,O=Android,C=US"
            ], shell=True)
    sign_args = [
        "sign",
        "--ks", debug_ks,
        "--ks-pass", "pass:android",
        "--out", out_name,
        apk_to_sign
    ]
    if apksigner_jar and os.path.exists(apksigner_jar):
        subprocess.check_call(["java", "-jar", apksigner_jar] + sign_args)
    else:
        subprocess.check_call(["apksigner"] + sign_args, shell=True)

out_apk_target = os.path.join(app_dir, "CameraAssistant.apk")
shutil.copyfile(out_name, out_apk_target)
print(f"SUCCESS: {out_name} (and {out_apk_target}) built and signed successfully!")
