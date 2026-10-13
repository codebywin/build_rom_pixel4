#!/usr/bin/env python3
"""
obs_bridge.py — Cầu nối truyền luồng OBS Studio sang VCam qua Cáp USB (ADB) hoặc Mạng WiFi.
codebywin - @zero0x0134w

Hỗ trợ 2 chế độ:
  1. USB Mode: Kết nối qua cáp USB và ADB Port Forward (cực kỳ ổn định, độ trễ < 30ms).
  2. WiFi Mode: Kết nối trực tiếp qua mạng WiFi LAN (không cần cắm cáp, tự do di chuyển).
"""

import os
import sys
import time
import signal
import shutil
import argparse
import subprocess
import threading
from pathlib import Path

# Đảm bảo terminal Windows không bị lỗi Unicode
if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
if hasattr(sys.stderr, 'reconfigure'):
    sys.stderr.reconfigure(encoding='utf-8', errors='replace')
os.system("")  # Bật ANSI Virtual Terminal Processing trên Windows console

SCRIPT_DIR = Path(__file__).parent.resolve()
MEDIAMTX_EXE = SCRIPT_DIR / "mediamtx.exe"
MEDIAMTX_YML = SCRIPT_DIR / "mediamtx.yml"

# Thêm SCRIPT_DIR vào PATH để nhận diện ffmpeg.exe cục bộ
if str(SCRIPT_DIR) not in os.environ.get("PATH", ""):
    os.environ["PATH"] = str(SCRIPT_DIR) + os.pathsep + os.environ.get("PATH", "")

# Tự động tìm ADB trong Android SDK nếu chưa có trong PATH
user_home = Path.home()
platform_tools = user_home / "AppData" / "Local" / "Android" / "Sdk" / "platform-tools"
if platform_tools.exists() and str(platform_tools) not in os.environ.get("PATH", ""):
    os.environ["PATH"] = str(platform_tools) + os.pathsep + os.environ.get("PATH", "")

def get_ffmpeg_bin():
    local_ffmpeg = SCRIPT_DIR / "ffmpeg.exe"
    if local_ffmpeg.exists():
        return str(local_ffmpeg)
    which = shutil.which("ffmpeg")
    if which:
        return which
    dl_script = SCRIPT_DIR / "download_ffmpeg.py"
    if dl_script.exists():
        log("[*] Không tìm thấy FFmpeg, đang tự động tải về...", "yellow")
        try:
            subprocess.run([sys.executable, str(dl_script)], check=True)
            if local_ffmpeg.exists():
                return str(local_ffmpeg)
        except Exception:
            pass
    return "ffmpeg"

RTMP_URL = "rtmp://127.0.0.1:1935/live/stream"

g_mediamtx_proc = None
g_ffmpeg_proc = None
g_running = True

def log(msg, color="white"):
    colors = {
        "green": "\033[92m",
        "yellow": "\033[93m",
        "red": "\033[91m",
        "cyan": "\033[96m",
        "bold": "\033[1m",
        "reset": "\033[0m"
    }
    prefix = f"{colors.get(color, '')}{msg}{colors['reset']}"
    print(prefix, flush=True)

def cleanup(sig=None, frame=None):
    global g_running, g_mediamtx_proc, g_ffmpeg_proc
    g_running = False
    log("\n[*] Đang dừng các tiến trình phát luồng...", "yellow")
    if g_ffmpeg_proc:
        try:
            g_ffmpeg_proc.terminate()
            g_ffmpeg_proc.kill()
        except Exception:
            pass
    if g_mediamtx_proc:
        try:
            g_mediamtx_proc.terminate()
            g_mediamtx_proc.kill()
        except Exception:
            pass
    os.system("taskkill /f /im mediamtx.exe >nul 2>&1")
    log("[V] Đã dừng toàn bộ dịch vụ. Tạm biệt!", "green")
    sys.exit(0)

def check_adb_devices():
    log("[1] Đang kiểm tra thiết bị cắm cáp USB qua ADB...", "cyan")
    try:
        res = subprocess.run(["adb", "devices"], capture_output=True, text=True, timeout=5)
    except Exception as e:
        log(f"[X] Không thể chạy lệnh ADB: {e}", "red")
        log("    Vui lòng cài đặt platform-tools hoặc thêm ADB vào PATH.", "yellow")
        sys.exit(1)

    lines = [line.strip() for line in res.stdout.splitlines() if line.strip()]
    devices = [line.split()[0] for line in lines[1:] if "device" in line and not "offline" in line]

    if not devices:
        log("[X] LỖI: Không tìm thấy điện thoại nào kết nối USB!", "red")
        log("    Vui lòng kiểm tra: Đã cắm cáp, bật 'Gỡ lỗi USB' và chọn 'Luôn cho phép'.", "yellow")
        sys.exit(1)

    device_id = devices[0]
    log(f"    -> Đã kết nối thiết bị USB: {device_id}", "green")
    return device_id

def setup_usb_device(device_id, port):
    log(f"[2] Đang thiết lập kênh truyền dữ liệu USB (ADB Port Forward {port})...", "cyan")
    subprocess.run(["adb", "-s", device_id, "forward", f"tcp:{port}", f"tcp:{port}"], check=True)
    log(f"    -> Cổng TCP {port} đã sẵn sàng!", "green")

    # Kiểm tra vcam_stream_receiver daemon (nếu dùng ROM custom đã tích hợp sẵn trong framework thì không cần bắt buộc)
    try:
        check_cmd = subprocess.run(["adb", "-s", device_id, "shell", "ps -ef | grep vcam_stream_receiver | grep -v grep"],
                                   capture_output=True, text=True, timeout=2)
        if "vcam_stream_receiver" not in check_cmd.stdout:
            subprocess.run(["adb", "-s", device_id, "shell",
                            "su -c 'chmod 755 /data/local/tmp/vcam_stream_receiver 2>/dev/null; "
                            "if [ -x /system/bin/vcam_stream_receiver ]; then /system/bin/vcam_stream_receiver & "
                            "elif [ -x /data/local/tmp/vcam_stream_receiver ]; then /data/local/tmp/vcam_stream_receiver & fi'"],
                            capture_output=True, timeout=2)
    except Exception:
        pass
    log("    -> Kênh truyền VCam USB đã sẵn sàng kết nối!", "green")

def get_device_wifi_ip():
    try:
        res = subprocess.run(["adb", "shell", "ip -4 addr show wlan0"],
                             capture_output=True, text=True, timeout=3)
        for line in res.stdout.splitlines():
            line = line.strip()
            if line.startswith("inet "):
                ip = line.split()[1].split('/')[0]
                return ip
    except Exception:
        pass
    return None

def find_mediamtx():
    candidates = [
        MEDIAMTX_EXE,
        Path(r"c:\Users\admin\Desktop\codebywin\USB_Stream_Tool\mediamtx.exe"),
        Path(r"c:\Users\admin\Desktop\Cam2\USB_Stream_Tool\mediamtx.exe"),
        Path(r"c:\Users\admin\Desktop\mediamtx.exe"),
    ]
    for c in candidates:
        if c.exists():
            return c
    return None

def start_mediamtx():
    global g_mediamtx_proc
    exe = find_mediamtx()
    if not exe:
        log("[X] Không tìm thấy mediamtx.exe trên máy tính!", "red")
        log("    Vui lòng đặt file mediamtx.exe vào thư mục tools/obs_usb_stream/", "yellow")
        sys.exit(1)

    os.system("taskkill /f /im mediamtx.exe >nul 2>&1")
    log(f"[*] Đang khởi động máy chủ MediaMTX RTMP trên cổng 1935 ({exe.name})...", "cyan")

    cmd = [str(exe)]
    if MEDIAMTX_YML.exists():
        cmd.append(str(MEDIAMTX_YML))

    g_mediamtx_proc = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(1)
    log("    -> Máy chủ RTMP đã chạy tại rtmp://127.0.0.1:1935/live/stream", "green")

def print_banner(mode, target_display):
    os.system("cls" if os.name == "nt" else "clear")
    log("==================================================================", "cyan")
    if mode == "wifi":
        log("       BỘ CÔNG CỤ STREAM OBS SANG VCAM QUA MẠNG WIFI (KHÔNG DÂY)   ", "bold")
    else:
        log("       BỘ CÔNG CỤ STREAM OBS SANG VCAM QUA CÁP USB (LOW LATENCY)  ", "bold")
    log("                  codebywin - @zero0x0134w                        ", "cyan")
    log("==================================================================", "cyan")
    print()
    log(f"  📡 Chế độ kết nối : {mode.upper()}", "green")
    log(f"  🎯 Đích đến       : {target_display}", "green")
    print()
    log("📋 HƯỚNG DẪN CẤU HÌNH TRÊN OBS STUDIO:", "yellow")
    log("  1. Mở OBS Studio -> Settings -> Stream (Phát luồng):", "white")
    log("     - Service : Custom...", "green")
    log("     - Server  : rtmp://127.0.0.1:1935/live", "green")
    log("     - Key     : stream", "green")
    log("  2. Settings -> Output (Đầu ra) -> Stream:", "white")
    log("     - Rate Control : CBR", "green")
    log("     - Bitrate      : 4000 - 6000 Kbps", "green")
    log("     - Keyframe     : 1s hoặc 2s", "green")
    log("     - CPU Preset   : veryfast hoặc ultrafast", "green")
    log("     - Tune         : zerolatency", "green")
    log("  3. Bấm [Start Streaming] trên OBS để bắt đầu truyền hình ảnh!", "yellow")
    log("==================================================================", "cyan")
    print()

def stream_loop(tcp_target):
    global g_ffmpeg_proc, g_running

    log("[*] ĐANG CHỜ OBS STUDIO BẤM 'START STREAMING'...", "yellow")
    log("    (Khi bạn bấm Start Streaming trên OBS, video sẽ tự động hiển thị trên camera điện thoại)", "white")

    while g_running:
        try:
            subprocess.run(["taskkill", "/f", "/im", "ffmpeg.exe"],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except Exception:
            pass

        ffmpeg_cmd = [
            get_ffmpeg_bin(),
            "-hide_banner",
            "-nostats",
            "-loglevel", "quiet",
            "-fflags", "+nobuffer+genpts+discardcorrupt",
            "-flags", "low_delay",
            "-rtmp_live", "live",
            "-rtmp_buffer", "100",
            "-i", RTMP_URL,
            "-an",
            "-c:v", "copy",
            "-bsf:v", "h264_mp4toannexb",
            "-f", "h264",
            "-y",
            f"{tcp_target}?timeout=3000000"
        ]

        try:
            g_ffmpeg_proc = subprocess.Popen(
                ffmpeg_cmd,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL
            )

            state = {"active": False}

            def monitor_status(proc, st):
                time.sleep(1.2)
                if proc.poll() is None:
                    st["active"] = True
                    log("\n[🟢 ACTIVE] ĐÃ KẾT NỐI LUỒNG OBS STUDIO SANG VCAM THÀNH CÔNG!", "green")
                    log("    -> Video/ảnh đang được truyền trực tiếp sang camera điện thoại.", "cyan")
                    log("    -> Độ trễ: < 30ms | Chuẩn H.264 Annex-B trực tiếp từ OBS.", "cyan")
                    log("    -> Đầy đủ hiệu ứng: Zoom, Pan, Noise, Spotlight & Chụp ảnh tĩnh 100%!", "green")
                    log("    -> Khi dừng stream trên OBS, VCam sẽ tự động quay về video offline.", "yellow")

            mon_thread = threading.Thread(target=monitor_status, args=(g_ffmpeg_proc, state), daemon=True)
            mon_thread.start()

            g_ffmpeg_proc.wait()

            if state["active"]:
                log("\n[🟡 IDLE] OBS đã dừng stream. VCam đã tự động quay về phát video file sẵn có.", "yellow")
                log("[*] Đang chờ bạn bấm 'Start Streaming' lần tiếp theo trên OBS...", "white")

        except Exception:
            pass

        time.sleep(0.5)

def main():
    signal.signal(signal.SIGINT, cleanup)
    signal.signal(signal.SIGTERM, cleanup)

    parser = argparse.ArgumentParser(description="VCam OBS Bridge (USB & WiFi)")
    parser.add_argument("--mode", choices=["usb", "wifi"], default="usb",
                        help="Che do ket noi: usb (mac dinh) hoac wifi")
    parser.add_argument("--ip", type=str, default="",
                        help="Dia chi IP WiFi cua dien thoai (dung cho mode wifi)")
    parser.add_argument("--port", type=int, default=7777,
                        help="Cong TCP stream (mac dinh 7777)")
    args = parser.parse_args()

    mode = args.mode
    port = args.port

    if mode == "wifi":
        phone_ip = args.ip.strip()
        if not phone_ip:
            detected_ip = get_device_wifi_ip()
            if detected_ip:
                log(f"[*] Tu dong phat hien IP dien thoai qua ADB: {detected_ip}", "green")
                phone_ip = detected_ip
            else:
                log("[?] Vui long nhap dia chi IP WiFi cua dien thoai:", "yellow")
                log("    (Tren dien thoai: Cai dat -> Wi-Fi -> Bam vao mang dang ket noi de xem IP)", "cyan")
                try:
                    user_input = input("  -> IP: ").strip()
                    if user_input:
                        phone_ip = user_input
                except Exception:
                    pass

        if not phone_ip:
            log("[X] LOI: Ban chua nhap dia chi IP cua dien thoai!", "red")
            sys.exit(1)

        tcp_target = f"tcp://{phone_ip}:{port}"
        target_display = f"{phone_ip}:{port} (WiFi LAN)"
    else:
        dev_id = check_adb_devices()
        setup_usb_device(dev_id, port)
        tcp_target = f"tcp://127.0.0.1:{port}"
        target_display = f"127.0.0.1:{port} (Cáp USB qua ADB)"

    print_banner(mode, target_display)
    start_mediamtx()
    stream_loop(tcp_target)

if __name__ == "__main__":
    main()
