import os, sys, subprocess, zipfile, shutil

app_dir = r"c:\Users\admin\Desktop\codebywin\build_rom_pixel4\org.lineageos.camera.assistant"
android_jar = r"C:\Users\admin\AppData\Local\Android\Sdk\platforms\android-33\android.jar"

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

print("[4/5] Converting classes to dex with d8...")
class_files = []
for root, _, files in os.walk("build/obj"):
    for f in files:
        if f.endswith(".class"):
            class_files.append(os.path.join(root, f))

subprocess.check_call(["d8", "--min-api", "33", "--output", "build/apk"] + class_files, shell=True)

print("[5/5] Packaging classes.dex and signing APK...")
with zipfile.ZipFile("build/apk/app-unsigned.apk", "a") as apk:
    apk.write("build/apk/classes.dex", "classes.dex")

subprocess.check_call([
    "apksigner", "sign",
    "--ks", "debug.keystore",
    "--ks-pass", "pass:android",
    "--out", "CameraAssistant.apk",
    "build/apk/app-unsigned.apk"
], shell=True)

print("SUCCESS: CameraAssistant.apk built and signed successfully!")
