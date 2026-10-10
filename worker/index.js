// ===================================================================================
// CLOUDFLARE WORKER: VCAM ADVANCED DEVICE & LICENSE MANAGEMENT SERVER PRO
// Target Domain: https://vandroid.hothangtech.workers.dev
// Author: LineageOS Pixel 4 (flame) VCAM Team
// ===================================================================================

const RSA_PRIVATE_KEY_PEM = "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQDEW+Zjifujwy4a\nRxGJl/zvpYpy5wepRwLN2nZBHjjIKJkN4mxd9aCpAj4YtrRWNyP8bF9dlMEKdcG8\nxWFNWulXM2qzcT/cN7vKFBsbAPOKqRz7BnQ0x/+gWvjoAsOE4uL1YwkQ57lw5YYX\n3pf/4fzlnSuqZtUUoZCI+nAZNyzqPq1v0xGQJ8N1rv9/3qV0G6MLDWgg0XrJ4UnY\nRJK54enQpxNnDocB+jSN965LJq03rQw2ERgmeSIH9dFPCceHhTaBLnnDKVQ/K3CT\nwiLPV6+E+poeAV6ib8rnkyTL6SpJU0TIWdCrdmYPlGjXNWDEukKPB7QZlVMPjv9j\nm8aIyQ1rAgMBAAECggEABtoqJQkYpfNWtYYLX6DVK8u8FBxp0QdwWpyoCcezNZDt\nHmXrYDAFJkC0yAoAKw4LjHB/t3VMcz/+vcapiZiFkgxyScbG8rljLT8cXwneddVG\n9J+aCIl+Kythij8mcYm1X9jP5S4g84ae8lBLP5u0RpMAhhbGksy8jXsn1ElvoND1\nduKVkShtQPKxABu54RJGQp9AkHaRaJGivg1sbxtFTj/QHWP45WF1Lf/J3woVQ6MC\nXu4KvEFYDeOKBr0/T+PU8e/A0cWp/OAduIJ4mGqmV3mtcQdxVduQkpFFZGYx0FSB\nn0yKiuUuxb4mEXJoAX+mEev56vqY1TEyP6ebRrkAUQKBgQDmvxDxyiaDVsLGWHuo\nDrdO5XnDLSpu0uw2N0agasfNBbCnx87v4EX4J/IQ5w6RpfZ6jFJj9ENC3UfkuOHu\nZsBU56fxCuDnM7SRKFJM+izrVUuhz9AGMnq8jOdth79E8FfckSXhUXYBzZCua2+U\nRso6YTLV2ouhn5yaYP3BOCRU4wKBgQDZ2WLQqa99iEL68HTKCiViCd71BMOa5Jg+\n+8EcOd30xemBw0hKFFgMjN4dlAIiURhKB7nQ7kF6gl8hRxj0bN7B1eSzTx6zR+YS\nR+5sn9uKv6Hh82y+RDN4Agi9da+jBapCESBAdQJU7ktjmmW0xZ2xz8yaTjQjaazV\nE2yNZMLT2QKBgBL+uZNd88uuEbyoPg24oGhzRZHGnw6eeGmCJWNBRw9en4tATI28\npaXnC+tOSgm9Ysv1zzaBPiQ7+RYgDiFE/iI/K7kRDzCZNg0ZB6VkltwMmnxIkjRg\nZXAuHUMMALfZHTKAFGE3BoLhfD6Pg5DuPumNZNTr98CnVgnzYBBO4dbHAoGAQkMK\n+WkDhe1SYj2NaH7ZjA5wkJpYXN63KEEvJcS8LF2efufFLzMs7PRUAy8nzwRXnPzo\nmhI+PGM3SEn13zLWNqM2owunzORLqLfUX5noDzXmqXF/XAgml5QW0HnhaHaqqNnI\ns5JjmS26JJur3+ZT5ufL1gt/dF4KQe1ckU1arVECgYEA3iL/BppEMkYYE8r/UEwM\n+0JOXcjG1xe8UY6htPkQaenkAFnxs+HRjB9zxCH+8ERLyFsKIUl6NbZvmqfrv7ml\nAk05POqzUXQPY04a8GJKFCwvi5j9QR96aUgU13RHFZQmhChOARPL3+GM7x85zeaf\nIBOdjoECcQMJRyjFiP+zrhY=\n-----END PRIVATE KEY-----";

const MEMORY_KEYS = {};
const MEMORY_DEVICES = {};
const MEMORY_SESSIONS = {};

// Helper: Lay thong tin dang nhap & 2FA tu Cloudflare Secrets (Variables) hoac KV
async function getAdminCredentials(env) {
  const { kv } = getKV(env);
  let user = env && (env.ADMIN_USER || env.ADMIN_USERNAME) ? (env.ADMIN_USER || env.ADMIN_USERNAME).trim() : "admin";
  let pass = env && (env.ADMIN_PASSWORD || env.ADMIN_PASS) ? (env.ADMIN_PASSWORD || env.ADMIN_PASS).trim() : "";
  let secret2fa = env && (env.ADMIN_2FA_SECRET || env.TWO_FACTOR_SECRET) ? (env.ADMIN_2FA_SECRET || env.TWO_FACTOR_SECRET).trim().replace(/\s+/g, "").toUpperCase() : "";

  if (kv) {
    try {
      const storedConfig = await kv.get("config:admin_auth");
      if (storedConfig) {
        const parsed = JSON.parse(storedConfig);
        if (parsed.user) user = parsed.user.trim();
        if (parsed.pass) pass = parsed.pass.trim();
        if (parsed.secret2fa) secret2fa = parsed.secret2fa.trim().replace(/\s+/g, "").toUpperCase();
      }
    } catch (e) {}
  }

  return { user, pass, secret2fa, isConfigured: !!(pass && secret2fa) };
}

// Helper: Base32 to Bytes
function base32ToBytes(base32) {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  let cleaned = (base32 || "").toUpperCase().replace(/=+$/, "").replace(/\s+/g, "");
  let bits = "";
  for (let i = 0; i < cleaned.length; i++) {
    const val = alphabet.indexOf(cleaned[i]);
    if (val === -1) continue;
    bits += val.toString(2).padStart(5, "0");
  }
  const bytes = [];
  for (let i = 0; i + 8 <= bits.length; i += 8) {
    bytes.push(parseInt(bits.substr(i, 8), 2));
  }
  return new Uint8Array(bytes);
}

// Helper: Verify Google Authenticator TOTP (RFC 6238)
async function verifyTotp(secretBase32, code, window = 1) {
  if (!code || typeof code !== "string") return false;
  code = code.trim();
  if (code.length !== 6 || !/^\d{6}$/.test(code)) return false;

  const keyBytes = base32ToBytes(secretBase32);
  const cryptoKey = await crypto.subtle.importKey(
    "raw",
    keyBytes,
    { name: "HMAC", hash: "SHA-1" },
    false,
    ["sign"]
  );

  const currentStep = Math.floor(Date.now() / 1000 / 30);
  for (let stepOffset = -window; stepOffset <= window; stepOffset++) {
    const step = currentStep + stepOffset;
    const buffer = new ArrayBuffer(8);
    const view = new DataView(buffer);
    view.setUint32(0, Math.floor(step / 0x100000000), false);
    view.setUint32(4, step >>> 0, false);

    const sig = await crypto.subtle.sign("HMAC", cryptoKey, buffer);
    const hmac = new Uint8Array(sig);
    const offset = hmac[hmac.length - 1] & 0x0f;
    const binary =
      ((hmac[offset] & 0x7f) << 24) |
      ((hmac[offset + 1] & 0xff) << 16) |
      ((hmac[offset + 2] & 0xff) << 8) |
      (hmac[offset + 3] & 0xff);

    const otp = (binary % 1000000).toString().padStart(6, "0");
    if (otp === code) return true;
  }
  return false;
}

// Helper: Read Cookie
function getCookie(request, name) {
  const cookieHeader = request.headers.get("Cookie") || "";
  const match = cookieHeader.match(new RegExp("(^|;\\s*)" + name + "=([^;]*)"));
  return match ? decodeURIComponent(match[2]) : null;
}

// Helper: Check if request is authenticated as Admin
async function isAuthorizedAdmin(request, env) {
  const { kv } = getKV(env);
  let token = getCookie(request, "vcam_session");
  if (!token) {
    const authHeader = request.headers.get("Authorization") || "";
    if (authHeader.startsWith("Bearer ")) {
      token = authHeader.substring(7).trim();
    }
  }
  if (!token) return false;

  // Direct Bearer token matching ADMIN_TOKEN from Cloudflare Secrets
  if (env && env.ADMIN_TOKEN && token === env.ADMIN_TOKEN.trim()) {
    return true;
  }

  if (kv) {
    const sessionData = await kv.get("session:" + token);
    if (sessionData) {
      try {
        const parsed = JSON.parse(sessionData);
        if (parsed.expires_at > Date.now()) return true;
      } catch (e) {}
    }
  }
  if (MEMORY_SESSIONS[token] && MEMORY_SESSIONS[token].expires_at > Date.now()) {
    return true;
  }
  return false;
}

// Helper: Tu dong phat hien KV namespace trong env
function getKV(env) {
  if (!env) return { kv: null, name: null };
  if (env.VCAM_KV && typeof env.VCAM_KV.get === "function") return { kv: env.VCAM_KV, name: "VCAM_KV" };
  if (env.KV && typeof env.KV.get === "function") return { kv: env.KV, name: "KV" };
  for (const k of Object.keys(env)) {
    const v = env[k];
    if (v && typeof v.get === "function" && typeof v.put === "function" && typeof v.list === "function") {
      return { kv: v, name: k };
    }
  }
  return { kv: null, name: null };
}

// Helper: Response JSON
function jsonResponse(data, status = 200, headers = {}) {
  return new Response(JSON.stringify(data), {
    status: status,
    headers: {
      "Content-Type": "application/json; charset=UTF-8",
      ...headers
    }
  });
}

// Chuyen PEM sang CryptoKey (PKCS#8 Private Key)
async function importPrivateKey(pem) {
  const cleanPem = pem
    .replace(/-----BEGIN PRIVATE KEY-----/, "")
    .replace(/-----END PRIVATE KEY-----/, "")
    .replace(/\s+/g, "");
  const binaryDer = Uint8Array.from(atob(cleanPem), c => c.charCodeAt(0));
  return await crypto.subtle.importKey(
    "pkcs8",
    binaryDer.buffer,
    {
      name: "RSASSA-PKCS1-v1_5",
      hash: "SHA-256"
    },
    false,
    ["sign"]
  );
}

// Ky chuoi License bang RSA-2048 SHA-256
async function signLicenseData(dataString) {
  const privateKey = await importPrivateKey(RSA_PRIVATE_KEY_PEM);
  const encoder = new TextEncoder();
  const dataBytes = encoder.encode(dataString);
  const signatureBytes = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    privateKey,
    dataBytes
  );
  return btoa(String.fromCharCode(...new Uint8Array(signatureBytes)));
}

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    const path = url.pathname;
    const clientIp = request.headers.get("cf-connecting-ip") || "Unknown";

    const corsHeaders = {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type, Authorization"
    };

    if (request.method === "OPTIONS") {
      return new Response(null, { headers: corsHeaders });
    }

    const { kv, name: kvName } = getKV(env);

    // ===============================================================================
    // 1. API KICH HOAT TU APP ANDROID (/api/v1/activate)
    // ===============================================================================
    if (path === "/api/v1/activate" && request.method === "POST") {
      try {
        const { key, serial } = await request.json();
        if (!key || !serial) {
          return jsonResponse({ success: false, message: "Thieu Key hoac Serial may!" }, 400, corsHeaders);
        }

        let keyData = null;
        if (kv) {
          const raw = await kv.get("key:" + key);
          if (raw) {
            try { keyData = JSON.parse(raw); } catch (e) {}
          }
        }
        
        if (!keyData && MEMORY_KEYS[key]) {
          keyData = MEMORY_KEYS[key];
        }

        // Tu dong nhan dien Key hop le tu Bot hoac he thong neu chua co trong KV
        if (!keyData) {
          const upperKey = key.toUpperCase();
          if (upperKey.startsWith("THANG-") || upperKey.startsWith("VCAM-") || upperKey.startsWith("TEST-")) {
            let durSec = 30 * 86400;
            let durLabel = "30 Ngày";
            if (upperKey.includes("-1D-") || upperKey.includes("-1D")) {
              durSec = 1 * 86400; durLabel = "1 Ngày";
            } else if (upperKey.includes("-7D-") || upperKey.includes("-7D")) {
              durSec = 7 * 86400; durLabel = "7 Ngày";
            } else if (upperKey.includes("-30D-") || upperKey.includes("-30D")) {
              durSec = 30 * 86400; durLabel = "30 Ngày";
            } else if (upperKey.includes("-90D-") || upperKey.includes("-90D")) {
              durSec = 90 * 86400; durLabel = "90 Ngày";
            } else if (upperKey.includes("-180D-") || upperKey.includes("-180D")) {
              durSec = 180 * 86400; durLabel = "180 Ngày";
            } else if (upperKey.includes("-365D-") || upperKey.includes("-365D") || upperKey.includes("-1Y")) {
              durSec = 365 * 86400; durLabel = "365 Ngày";
            } else if (upperKey.includes("-LIFE") || upperKey.includes("-VIP-") || upperKey.includes("VIP-2026")) {
              durSec = 0; durLabel = "Vĩnh viễn";
            }
            keyData = { duration_seconds: durSec, duration_label: durLabel, bound_serial: null, status: "ACTIVE", note: "Kích hoạt qua Telegram Bot" };
          }
        }

        if (!keyData) {
          return jsonResponse({ success: false, message: "Ma kich hoat khong ton tai tren he thong!" }, 404, corsHeaders);
        }

        if (keyData.status === "REVOKED") {
          return jsonResponse({ success: false, message: "Ma key nay da bi thu hoi hoac huy bo boi Admin!" }, 403, corsHeaders);
        }

        if (keyData.bound_serial && keyData.bound_serial !== serial) {
          return jsonResponse({ success: false, message: "Key nay da duoc kich hoat tren thiet bi khac: " + keyData.bound_serial }, 403, corsHeaders);
        }

        // Kiem tra xem may nay co bi Khoa (Ban) khong
        let isBanned = false;
        if (kv) {
          const devRaw = await kv.get("device:" + serial);
          if (devRaw) {
            try {
              if (JSON.parse(devRaw).status === "BANNED") isBanned = true;
            } catch (e) {}
          }
        } else if (MEMORY_DEVICES[serial] && MEMORY_DEVICES[serial].status === "BANNED") {
          isBanned = true;
        }

        if (isBanned) {
          return jsonResponse({ success: false, message: "Thiet bi (Serial: " + serial + ") da bi KHOA tu xa! Lien he Admin de mo khoa." }, 403, corsHeaders);
        }

        // Tinh thoi gian het han
        const now = Math.floor(Date.now() / 1000);
        let expiresAt = 0;
        if (keyData.duration_seconds && keyData.duration_seconds > 0) {
          expiresAt = now + keyData.duration_seconds;
        }
        // Tao chuoi License ky so theo dung chuan LicenseManager
        const licPayload = "SERIAL=" + serial + ";EXPIRES=" + expiresAt + ";ISSUED=" + now;
        const signature = await signLicenseData(licPayload);
        const licenseToken = licPayload + "|SIG=" + signature;

        // Ghi nhan Device & Cap nhat Key bound_serial
        const clientCountry = request.headers.get("cf-ipcountry") || "VN";
        const clientCity = request.cf?.city;
        const location = clientCity ? `${clientCity}, ${clientCountry}` : (clientCountry === "VN" ? "Ho Chi Minh City, VN" : clientCountry);
        const deviceModel = "Xiaomi M1906G7G";

        keyData.bound_serial = serial;
        keyData.activated_at = now;
        keyData.expires_at = expiresAt;
        keyData.last_ip = clientIp;
        keyData.location = location;
        if (!keyData.device_model) keyData.device_model = deviceModel;

        const devObj = {
          serial: serial,
          key: key,
          model: keyData.device_model,
          buyer_name: keyData.buyer_name || "Khách hàng",
          note: keyData.note || "Khách hàng",
          activated_at: now,
          expires_at: expiresAt,
          duration_label: keyData.duration_label || "Standard",
          last_ip: clientIp,
          location: location,
          last_seen: now,
          status: "ACTIVE"
        };

        if (kv) {
          await kv.put("key:" + key, JSON.stringify(keyData));
          await kv.put("device:" + serial, JSON.stringify(devObj));
        } else {
          MEMORY_KEYS[key] = keyData;
          MEMORY_DEVICES[serial] = devObj;
        }

        return jsonResponse({
          success: true,
          message: "Kich hoat thanh cong thiet bi " + serial + "!",
          license_token: licenseToken,
          license: licenseToken,
          expires_at: expiresAt,
          duration_label: keyData.duration_label,
          server_time: now
        }, 200, corsHeaders);

      } catch (err) {
        return jsonResponse({ success: false, message: "Loi kich hoat Server: " + err.message }, 500, corsHeaders);
      }
    }

    // ===============================================================================
    // 2. API KIEM TRA TRANG THAI THIET BI TU APP (/api/v1/check)
    // ===============================================================================
    if (path === "/api/v1/check" && request.method === "POST") {
      try {
        const { serial } = await request.json();
        if (!serial) return jsonResponse({ success: false, message: "Thieu serial" }, 400, corsHeaders);

        let dev = null;
        if (kv) {
          const raw = await kv.get("device:" + serial);
          if (raw) {
            try { dev = JSON.parse(raw); } catch (e) {}
          }
        }
        if (!dev && MEMORY_DEVICES[serial]) dev = MEMORY_DEVICES[serial];

        if (!dev) {
          return jsonResponse({ success: false, status: "NOT_FOUND", message: "Thiet bi chua duoc kich hoat." }, 404, corsHeaders);
        }

        dev.last_seen = Math.floor(Date.now() / 1000);
        dev.last_ip = clientIp;
        if (kv) await kv.put("device:" + serial, JSON.stringify(dev));

        const now = Math.floor(Date.now() / 1000);
        let isExpired = (dev.expires_at > 0 && dev.expires_at < now);

        return jsonResponse({
          success: true,
          status: dev.status,
          is_expired: isExpired,
          expires_at: dev.expires_at,
          duration_label: dev.duration_label
        }, 200, corsHeaders);
      } catch (err) {
        return jsonResponse({ success: false, message: err.message }, 500, corsHeaders);
      }
    }

    // ===============================================================================
    // AUTH 1. API DANG NHAP ADMIN (/api/admin/login)
    // ===============================================================================
    if (path === "/api/admin/login" && request.method === "POST") {
      try {
        const { username, password, otp } = await request.json();
        const creds = await getAdminCredentials(env);
        if (!creds.isConfigured) {
          return jsonResponse({
            success: false,
            message: "Hệ thống chưa thiết lập ADMIN_PASS hoặc TWO_FACTOR_SECRET trong mục Variables and Secrets trên Cloudflare Worker!"
          }, 500, corsHeaders);
        }
        if (username !== creds.user || password !== creds.pass) {
          return jsonResponse({ success: false, message: "Sai tên đăng nhập hoặc mật khẩu!" }, 401, corsHeaders);
        }

        const isOtpValid = await verifyTotp(creds.secret2fa, otp);
        if (!isOtpValid) {
          return jsonResponse({ success: false, message: "Mã 2FA Google Authenticator không đúng hoặc đã hết hạn (30s)!" }, 401, corsHeaders);
        }

        const sessionToken = crypto.randomUUID().replace(/-/g, "") + crypto.randomUUID().replace(/-/g, "");
        const sessionObj = {
          user: creds.user,
          created_at: Date.now(),
          expires_at: Date.now() + 7 * 86400 * 1000
        };

        if (kv) {
          await kv.put("session:" + sessionToken, JSON.stringify(sessionObj), { expirationTtl: 7 * 86400 });
        }
        MEMORY_SESSIONS[sessionToken] = sessionObj;

        const cookie = "vcam_session=" + sessionToken + "; Path=/; Max-Age=604800; HttpOnly; Secure; SameSite=Lax";
        return jsonResponse(
          { success: true, message: "Đăng nhập thành công!", token: sessionToken },
          200,
          { ...corsHeaders, "Set-Cookie": cookie }
        );
      } catch (err) {
        return jsonResponse({ success: false, message: "Lỗi đăng nhập: " + err.message }, 500, corsHeaders);
      }
    }

    // ===============================================================================
    // AUTH 2. API DANG XUAT ADMIN (/api/admin/logout)
    // ===============================================================================
    if (path === "/api/admin/logout") {
      const token = getCookie(request, "vcam_session");
      if (token) {
        if (kv) await kv.delete("session:" + token);
        delete MEMORY_SESSIONS[token];
      }
      const expiredCookie = "vcam_session=; Path=/; Max-Age=0; HttpOnly; Secure; SameSite=Lax";
      if (request.method === "GET") {
        return new Response(null, {
          status: 302,
          headers: { Location: "/", "Set-Cookie": expiredCookie }
        });
      }
      return jsonResponse({ success: true, message: "Đã đăng xuất!" }, 200, { ...corsHeaders, "Set-Cookie": expiredCookie });
    }

    // ===============================================================================
    // AUTH GUARD: CHAN TAT CA CAC REQUEST /api/admin/* KHI CHUA DANG NHAP
    // ===============================================================================
    if (path.startsWith("/api/admin/") && path !== "/api/admin/login") {
      const authorized = await isAuthorizedAdmin(request, env);
      if (!authorized) {
        return jsonResponse({ success: false, error: "Phiên đăng nhập đã hết hạn hoặc chưa đăng nhập!" }, 401, corsHeaders);
      }
    }

    // ===============================================================================
    // 3. ADMIN API: LAY DANH SACH THIET BI VA DANH SACH KEY (/api/admin/devices)
    // ===============================================================================
    if (path === "/api/admin/devices") {
      const devices = [];
      const keys = [];

      if (kv) {
        const devList = await kv.list({ prefix: "device:" });
        for (const k of devList.keys) {
          const val = await kv.get(k.name);
          if (val) {
            try { devices.push(JSON.parse(val)); } catch (e) {}
          }
        }

        const keyList = await kv.list({ prefix: "key:" });
        for (const k of keyList.keys) {
          const val = await kv.get(k.name);
          if (val) {
            try { keys.push(JSON.parse(val)); } catch (e) {}
          }
        }
      }

      for (const s in MEMORY_DEVICES) {
        if (!devices.some(d => d.serial === s)) devices.push(MEMORY_DEVICES[s]);
      }
      for (const k in MEMORY_KEYS) {
        if (!keys.some(x => x.key === k)) keys.push(MEMORY_KEYS[k]);
      }

      devices.sort((a, b) => (b.activated_at || 0) - (a.activated_at || 0));
      keys.sort((a, b) => (b.created_at || 0) - (a.created_at || 0));

      return jsonResponse({
        success: true,
        kv_bound: !!kv,
        kv_name: kvName,
        env_keys: Object.keys(env || {}),
        devices,
        keys
      }, 200, corsHeaders);
    }

    // ===============================================================================
    // 4. ADMIN API: KHOA / MO KHOA / GIA HAN / XOA THIET BI (/api/admin/device-action)
    // ===============================================================================
    if (path === "/api/admin/device-action" && request.method === "POST") {
      try {
        const { action, serial, days_add } = await request.json();

        let dev = null;
        if (kv) {
          const raw = await kv.get("device:" + serial);
          if (raw) {
            try { dev = JSON.parse(raw); } catch (e) {}
          }
        }
        if (!dev && MEMORY_DEVICES[serial]) dev = MEMORY_DEVICES[serial];

        if (!dev) {
          return jsonResponse({ error: "Khong tim thay thiet bi: " + serial }, 404, corsHeaders);
        }

        if (action === "ban") {
          dev.status = "BANNED";
          if (kv) await kv.put("device:" + serial, JSON.stringify(dev));
          MEMORY_DEVICES[serial] = dev;
          return jsonResponse({ success: true, message: "Da KHOA thiet bi: " + serial }, 200, corsHeaders);
        }

        if (action === "unban") {
          dev.status = "ACTIVE";
          if (kv) await kv.put("device:" + serial, JSON.stringify(dev));
          MEMORY_DEVICES[serial] = dev;
          return jsonResponse({ success: true, message: "Da MO KHOA cho may: " + serial }, 200, corsHeaders);
        }

        if (action === "extend") {
          const days = parseInt(days_add) || 30;
          const addSec = days * 86400;
          const now = Math.floor(Date.now() / 1000);
          if (!dev.expires_at || dev.expires_at < now) {
            dev.expires_at = now + addSec;
          } else {
            dev.expires_at += addSec;
          }
          dev.status = "ACTIVE";
          dev.duration_label = (dev.duration_label || "") + " (+" + days + "N)";
          if (kv) await kv.put("device:" + serial, JSON.stringify(dev));
          MEMORY_DEVICES[serial] = dev;
          return jsonResponse({ success: true, message: "Da gia han them " + days + " ngay cho may " + serial }, 200, corsHeaders);
        }

        if (action === "delete") {
          if (kv) await kv.delete("device:" + serial);
          delete MEMORY_DEVICES[serial];
          return jsonResponse({ success: true, message: "Da xoa thiet bi: " + serial }, 200, corsHeaders);
        }

        return jsonResponse({ error: "Hanh dong khong hop le" }, 400, corsHeaders);
      } catch (err) {
        return jsonResponse({ error: err.message }, 500, corsHeaders);
      }
    }

    // ===============================================================================
    // 5. ADMIN API: THAO TAC TREN KEY (/api/admin/key-action)
    // ===============================================================================
    if (path === "/api/admin/key-action" && request.method === "POST") {
      try {
        const { action, key } = await request.json();

        if (!key) return jsonResponse({ error: "Thieu ma key!" }, 400, corsHeaders);

        let keyObj = null;
        if (kv) {
          const raw = await kv.get("key:" + key);
          if (raw) {
            try { keyObj = JSON.parse(raw); } catch (e) {}
          }
        }
        if (!keyObj && MEMORY_KEYS[key]) keyObj = MEMORY_KEYS[key];

        if (!keyObj && action !== "delete") {
          return jsonResponse({ error: "Khong tim thay key: " + key }, 404, corsHeaders);
        }

        if (action === "delete") {
          if (kv) await kv.delete("key:" + key);
          delete MEMORY_KEYS[key];
          return jsonResponse({ success: true, message: "Da xoa ma key " + key + " khoi he thong." }, 200, corsHeaders);
        }

        if (action === "revoke") {
          keyObj.status = "REVOKED";
          if (kv) await kv.put("key:" + key, JSON.stringify(keyObj));
          MEMORY_KEYS[key] = keyObj;
          return jsonResponse({ success: true, message: "Da khoa ma key: " + key }, 200, corsHeaders);
        }

        if (action === "restore") {
          keyObj.status = "ACTIVE";
          if (kv) await kv.put("key:" + key, JSON.stringify(keyObj));
          MEMORY_KEYS[key] = keyObj;
          return jsonResponse({ success: true, message: "Da mo khoa ma key: " + key }, 200, corsHeaders);
        }

        if (action === "reset-device") {
          const oldSerial = keyObj.bound_serial;
          keyObj.bound_serial = null;
          keyObj.activated_at = null;
          keyObj.expires_at = null;
          keyObj.last_ip = null;
          keyObj.location = null;
          keyObj.device_model = null;
          if (kv) {
            await kv.put("key:" + key, JSON.stringify(keyObj));
            if (oldSerial) await kv.delete("device:" + oldSerial);
          }
          MEMORY_KEYS[key] = keyObj;
          if (oldSerial) delete MEMORY_DEVICES[oldSerial];
          return jsonResponse({ success: true, message: "Đã gỡ thiết bị cho key: " + key + ". Khách hàng có thể kích hoạt trên máy mới!" }, 200, corsHeaders);
        }

        return jsonResponse({ error: "Lenh khong hop le" }, 400, corsHeaders);
      } catch (err) {
        return jsonResponse({ error: err.message }, 500, corsHeaders);
      }
    }

    // ===============================================================================
    // 6. ADMIN API: TAO MA ACTIVE KEY MOI (/api/admin/create-key)
    // ===============================================================================
    if (path === "/api/admin/create-key" && request.method === "POST") {
      try {
        const {
          duration_type,
          duration_val,
          duration_label,
          price,
          buyer_name,
          social_platform,
          social_account,
          note,
          prefix
        } = await request.json();

        let durationSec = 7 * 86400;
        let label = duration_label || "7 Ngày";

        if (duration_type === "hours") {
          const h = parseFloat(duration_val) || 1;
          durationSec = Math.round(h * 3600);
          label = h + " Giờ";
        } else if (duration_type === "days") {
          const d = parseFloat(duration_val) || 1;
          durationSec = Math.round(d * 86400);
          label = d + " Ngày";
        } else if (duration_type === "lifetime") {
          durationSec = 0;
          label = "Vĩnh viễn";
        }

        const chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        let cleanPrefix = (prefix || "VITAMIN").trim().toUpperCase().replace(/[^A-Z0-9]/g, "");
        if (cleanPrefix.length > 7) {
          cleanPrefix = cleanPrefix.substring(0, 7);
        }
        if (!cleanPrefix) cleanPrefix = "VITAMIN";

        let randomSuffix = "";
        for (let i = 0; i < 7; i++) {
          randomSuffix += chars.charAt(Math.floor(Math.random() * chars.length));
        }

        const code = cleanPrefix + "-" + randomSuffix;

        const keyObj = {
          key: code,
          duration_seconds: durationSec,
          duration_label: label,
          price: parseInt(price) || 0,
          buyer_name: (buyer_name || "").trim() || "Khách hàng",
          social_platform: social_platform || "Telegram",
          social_account: (social_account || "").trim(),
          note: (note || "").trim(),
          bound_serial: null,
          device_model: null,
          last_ip: null,
          location: null,
          created_at: Math.floor(Date.now() / 1000),
          activated_at: null,
          expires_at: null,
          status: "ACTIVE"
        };

        if (kv) {
          await kv.put("key:" + code, JSON.stringify(keyObj));
        } else {
          MEMORY_KEYS[code] = keyObj;
        }

        return jsonResponse({
          success: true,
          key: keyObj,
          kv_bound: !!kv,
          kv_name: kvName
        }, 200, corsHeaders);
      } catch (err) {
        return jsonResponse({ error: "Loi tao key: " + err.message }, 500, corsHeaders);
      }
    }

    // ===============================================================================
    // 7. GIAO DIEN WEB QUAN TRI ADMIN (/ hoac /admin)
    // ===============================================================================
    if (path === "/" || path === "/admin") {
      const authorized = await isAuthorizedAdmin(request, env);
      const creds = await getAdminCredentials(env);
      if (!authorized) {
        return new Response(getLoginHtml(creds.secret2fa, creds.isConfigured), {
          headers: {
            "Content-Type": "text/html; charset=UTF-8",
            "Cache-Control": "no-store, no-cache, must-revalidate, max-age=0",
            "Pragma": "no-cache",
            ...corsHeaders
          }
        });
      }
      return new Response(getDashboardHtml(creds), {
        headers: {
          "Content-Type": "text/html; charset=UTF-8",
          "Cache-Control": "no-store, no-cache, must-revalidate, max-age=0",
          "Pragma": "no-cache",
          ...corsHeaders
        }
      });
    }

    // ===============================================================================
    // 8. TELEGRAM BOT WEBHOOK (Chạy 24/24 trên Cloudflare Workers)
    // ===============================================================================
    if (path === "/api/v1/telegram-webhook" && request.method === "POST") {
      try {
        const update = await request.json();
        if (ctx && typeof ctx.waitUntil === "function") {
          ctx.waitUntil(handleTelegramUpdate(update, env));
        } else {
          await handleTelegramUpdate(update, env);
        }
        return jsonResponse({ ok: true });
      } catch (e) {
        return jsonResponse({ ok: false, error: e.message }, 500);
      }
    }

    if (path === "/api/v1/telegram-set-webhook") {
      const cfg = await getTelegramConfig(env);
      const webhookUrl = `https://${url.host}/api/v1/telegram-webhook`;
      const teleRes = await teleApi(cfg.bot_token, "setWebhook", {
        url: webhookUrl,
        drop_pending_updates: true
      });
      return jsonResponse({
        success: teleRes.ok,
        webhook_url: webhookUrl,
        telegram_response: teleRes,
        message: teleRes.ok 
          ? "✅ Đã kích hoạt Telegram Bot chạy 24/24 trên Cloudflare Workers thành công!" 
          : "❌ Thiết lập Webhook thất bại, vui lòng kiểm tra bot token!"
      }, 200, corsHeaders);
    }

    if (path === "/api/v1/telegram-webhook-info") {
      const cfg = await getTelegramConfig(env);
      const teleRes = await teleApi(cfg.bot_token, "getWebhookInfo", {});
      return jsonResponse(teleRes, 200, corsHeaders);
    }

    return jsonResponse({ error: "Not Found", path: path }, 404, corsHeaders);
  }
};

// ===============================================================================
// MODULE TELEGRAM BOT TRÊN CLOUDFLARE WORKERS (CHẠY 24/24 MIỄN PHÍ)
// ===============================================================================

const DEFAULT_TELE_CONFIG = {
  bot_token: "",
  admin_username: "gitisgit",
  admin_chat_id: 5469372459,
  order_prefix: "NAP",
  bank: {
    bank_code: "SHB",
    bank_name: "SHB",
    account_no: "0110091989",
    account_name: "NGUYEN THI TY"
  },
  usdt: {
    network: "TRC20",
    address: "THKNF3W2BQ9bLqtJQUyNXqjHzjaXmcXXRj",
    note: "Chuyển đúng mạng TRC20"
  },
  packages: {
    "1d":   { name: "Gói 1 Ngày",          days: 1,   price: 50000,   price_usdt: 2,   label: "1 Ngày" },
    "7d":   { name: "Gói 7 Ngày",          days: 7,   price: 180000,  price_usdt: 7,   label: "7 Ngày" },
    "30d":  { name: "Gói 30 Ngày",         days: 30,  price: 500000,  price_usdt: 20,  label: "30 Ngày" },
    "90d":  { name: "Gói 90 Ngày",         days: 90,  price: 1200000, price_usdt: 48,  label: "90 Ngày" },
    "180d": { name: "Gói 180 Ngày",        days: 180, price: 2100000, price_usdt: 84,  label: "180 Ngày" },
    "365d": { name: "Gói 365 Ngày (1 Năm)", days: 365, price: 3600000, price_usdt: 144, label: "365 Ngày" },
    "life": { name: "Gói Vĩnh Viễn",       days: 0,   price: 6000000, price_usdt: 240, label: "Vĩnh Viễn" }
  },
  app_download_url: "https://github.com/"
};

function formatVnd(num) {
  return (num || 0).toString().replace(/\B(?=(\d{3})+(?!\d))/g, ".") + "đ";
}

async function getTelegramConfig(env) {
  const { kv } = getKV(env);
  let cfg = JSON.parse(JSON.stringify(DEFAULT_TELE_CONFIG));

  if (env) {
    if (env.TELEGRAM_BOT_TOKEN || env.BOT_TOKEN) {
      cfg.bot_token = (env.TELEGRAM_BOT_TOKEN || env.BOT_TOKEN).trim();
    }
    if (env.TELEGRAM_ADMIN_CHAT_ID || env.ADMIN_CHAT_ID) {
      const parsedId = parseInt(env.TELEGRAM_ADMIN_CHAT_ID || env.ADMIN_CHAT_ID, 10);
      if (!isNaN(parsedId)) cfg.admin_chat_id = parsedId;
    }
    if (env.BANK_ID || env.BANK_CODE) {
      cfg.bank.bank_code = (env.BANK_ID || env.BANK_CODE).trim().toUpperCase();
      cfg.bank.bank_name = cfg.bank.bank_code;
    }
    if (env.BANK_ACCOUNT || env.ACCOUNT_NO) {
      cfg.bank.account_no = (env.BANK_ACCOUNT || env.ACCOUNT_NO).trim();
    }
    if (env.BANK_NAME || env.ACCOUNT_NAME) {
      cfg.bank.account_name = (env.BANK_NAME || env.ACCOUNT_NAME).trim().toUpperCase();
    }
    if (env.USDT_NETWORK) {
      cfg.usdt.network = env.USDT_NETWORK.trim().toUpperCase();
    }
    if (env.USDT_ADDRESS) {
      cfg.usdt.address = env.USDT_ADDRESS.trim();
    }
  }

  if (kv) {
    try {
      const stored = await kv.get("config:telegram_bot");
      if (stored) {
        const parsed = JSON.parse(stored);
        return {
          ...cfg,
          ...parsed,
          bank: { ...cfg.bank, ...(parsed.bank || {}) },
          usdt: { ...cfg.usdt, ...(parsed.usdt || {}) },
          packages: { ...cfg.packages, ...(parsed.packages || {}) }
        };
      }
    } catch (e) {}
  }
  return cfg;
}

async function saveTelegramConfig(env, cfg) {
  const { kv } = getKV(env);
  if (kv) {
    try {
      await kv.put("config:telegram_bot", JSON.stringify(cfg));
    } catch (e) {}
  }
}

async function teleApi(token, method, payload) {
  try {
    const res = await fetch(`https://api.telegram.org/bot${token}/${method}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });
    return await res.json();
  } catch (e) {
    return { ok: false, error: e.message };
  }
}

async function sendTeleMessage(token, chatId, text, replyMarkup = null) {
  const payload = {
    chat_id: chatId,
    text: text,
    parse_mode: "HTML",
    disable_web_page_preview: true
  };
  if (replyMarkup) payload.reply_markup = replyMarkup;
  return await teleApi(token, "sendMessage", payload);
}

async function sendTelePhoto(token, chatId, photo, caption = "", replyMarkup = null) {
  const payload = {
    chat_id: chatId,
    photo: photo,
    caption: caption,
    parse_mode: "HTML"
  };
  if (replyMarkup) payload.reply_markup = replyMarkup;
  return await teleApi(token, "sendPhoto", payload);
}

async function editTeleCaption(token, chatId, messageId, caption) {
  return await teleApi(token, "editMessageCaption", {
    chat_id: chatId,
    message_id: messageId,
    caption: caption,
    parse_mode: "HTML"
  });
}

async function editTeleText(token, chatId, messageId, text) {
  return await teleApi(token, "editMessageText", {
    chat_id: chatId,
    message_id: messageId,
    text: text,
    parse_mode: "HTML",
    disable_web_page_preview: true
  });
}

async function answerTeleCallback(token, callbackQueryId, text = "", showAlert = false) {
  return await teleApi(token, "answerCallbackQuery", {
    callback_query_id: callbackQueryId,
    text: text,
    show_alert: showAlert
  });
}

function generateRandomKey(pkgKey = "30d") {
  const chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  let suffix = "";
  for (let i = 0; i < 5; i++) {
    suffix += chars.charAt(Math.floor(Math.random() * chars.length));
  }
  let tag = "30D";
  const k = (pkgKey || "30d").toLowerCase();
  if (k === "1d" || k === "1") tag = "1D";
  else if (k.includes("7")) tag = "7D";
  else if (k.includes("180")) tag = "180D";
  else if (k.includes("365") || k.includes("1y")) tag = "365D";
  else if (k.includes("90")) tag = "90D";
  else if (k.includes("30")) tag = "30D";
  else if (k.includes("life") || k.includes("vinh")) tag = "LIFE";
  return `VITAMIN-${tag}-${suffix}`;
}

async function createTelegramOrder(env, customerId, customerUsername, pkgKey, method = "VND") {
  const cfg = await getTelegramConfig(env);
  const pkg = cfg.packages[pkgKey];
  if (!pkg) return null;

  const prefix = (cfg.order_prefix || "NAP").trim().toUpperCase();
  const codeNum = Math.floor(1000 + Math.random() * 9000);
  const orderId = prefix + codeNum;
  const order = {
    order_id: orderId,
    customer_id: customerId,
    customer_username: customerUsername || "N/A",
    package_key: pkgKey,
    package_name: pkg.name,
    days: pkg.days,
    price: pkg.price,
    price_usdt: pkg.price_usdt || 0,
    method: method,
    label: pkg.label,
    created_at: Math.floor(Date.now() / 1000),
    status: "PENDING",
    key_issued: null,
    payment_note: null
  };

  const { kv } = getKV(env);
  if (kv) {
    await kv.put("tele_order:" + orderId, JSON.stringify(order), { expirationTtl: 86400 * 7 });
    await kv.put("tele_user_pending:" + customerId, orderId, { expirationTtl: 86400 * 7 });
  } else {
    MEMORY_KEYS["tele_order:" + orderId] = order;
    MEMORY_KEYS["tele_user_pending:" + customerId] = orderId;
  }
  return order;
}

async function getTelegramOrder(env, orderId) {
  const { kv } = getKV(env);
  if (kv) {
    const raw = await kv.get("tele_order:" + orderId);
    if (raw) {
      try { return JSON.parse(raw); } catch (e) {}
    }
  } else {
    return MEMORY_KEYS["tele_order:" + orderId] || null;
  }
  return null;
}

async function saveTelegramOrder(env, order) {
  const { kv } = getKV(env);
  if (kv) {
    await kv.put("tele_order:" + order.order_id, JSON.stringify(order), { expirationTtl: 86400 * 30 });
  } else {
    MEMORY_KEYS["tele_order:" + order.order_id] = order;
  }
}

async function getUserPendingOrder(env, customerId) {
  const { kv } = getKV(env);
  let orderId = null;
  if (kv) {
    orderId = await kv.get("tele_user_pending:" + customerId);
  } else {
    orderId = MEMORY_KEYS["tele_user_pending:" + customerId];
  }
  if (!orderId) return null;
  const o = await getTelegramOrder(env, orderId);
  if (o && o.status === "PENDING") return o;
  return null;
}

async function handleTelegramUpdate(u, env) {
  const cfg = await getTelegramConfig(env);
  const token = cfg.bot_token;
  const adminUser = (cfg.admin_username || "").toLowerCase().replace(/^@/, "");

  // 1. Xử lý Callback query (Bấm nút trên menu)
  if (u.callback_query) {
    const cb = u.callback_query;
    const data = cb.data || "";
    const chatId = cb.message?.chat?.id;
    const user = cb.from || {};

    if (data === "menu_buy") {
      await showMenuBuy(token, cfg, chatId);
    } else if (data.startsWith("choose_") || data.startsWith("buy_")) {
      const pkgKey = data.replace("choose_", "").replace("buy_", "");
      await showSelectMethod(token, cfg, chatId, pkgKey);
    } else if (data.startsWith("pay_vnd_")) {
      const pkgKey = data.replace("pay_vnd_", "");
      await processBuyVnd(token, cfg, env, chatId, user.username, pkgKey);
    } else if (data.startsWith("pay_usdt_")) {
      const pkgKey = data.replace("pay_usdt_", "");
      await processBuyUsdt(token, cfg, env, chatId, user.username, pkgKey);
    } else if (data === "menu_back") {
      await showStartMessage(token, cfg, env, chatId, user.username, user.first_name);
    } else if (data === "menu_guide") {
      await sendTeleMessage(token, chatId, 
        `📖 <b>HƯỚNG DẪN SỬ DỤNG VCAM ASSISTANT:</b>\n\n` +
        `1. Cài đặt Magisk Module <code>vcam_native_hook.zip</code> qua Magisk / KernelSU.\n` +
        `2. Cài app <code>CameraAssistant.apk</code>.\n` +
        (cfg.app_download_url ? `   👉 Link tải App APK: ${cfg.app_download_url}\n` : "") +
        `3. Mua Key và kích hoạt bản quyền trong app.\n` +
        `4. Chọn Video (.mp4), Audio (.mp3) hoặc Hình ảnh (.jpg) nguồn.\n` +
        `5. Bật <i>Kích hoạt Camera Ảo</i> và mở ứng dụng cần dùng!\n\n` +
        `💬 Hỗ trợ trực tiếp: @${cfg.admin_username}`
      );
    } else if (data.startsWith("approve_")) {
      const oid = data.replace("approve_", "");
      await processApproveOrder(token, cfg, env, cb, oid);
    } else if (data.startsWith("reject_")) {
      const oid = data.replace("reject_", "");
      await processRejectOrder(token, cfg, env, cb, oid);
    } else if (data.startsWith("cancel_")) {
      const oid = data.replace("cancel_", "");
      const order = await getTelegramOrder(env, oid);
      if (order) {
        order.status = "CANCELLED";
        await saveTelegramOrder(env, order);
        const { kv } = getKV(env);
        if (kv) await kv.delete("tele_user_pending:" + order.customer_id);
      }
      await sendTeleMessage(token, chatId, `Đã hủy đơn hàng #${oid}.`);
    }
    return;
  }

  // 2. Xử lý tin nhắn thường
  if (u.message) {
    const m = u.message;
    const chatId = m.chat?.id;
    const fromUser = m.from || {};
    const username = (fromUser.username || "").toLowerCase().replace(/^@/, "");
    const firstName = fromUser.first_name || "Bạn";
    const text = (m.text || "").trim();
    const isAdmin = (username === adminUser) || (chatId && String(chatId) === String(cfg.admin_chat_id));

    // Gửi ảnh biên lai
    if (m.photo && m.photo.length > 0) {
      const photoFileId = m.photo[m.photo.length - 1].file_id;
      await processPhotoBill(token, cfg, env, chatId, fromUser.username, photoFileId);
      return;
    }

    if (text.startsWith("/start")) {
      await showStartMessage(token, cfg, env, chatId, fromUser.username, firstName);
    } else if (text.startsWith("/muakey")) {
      await showMenuBuy(token, cfg, chatId);
    } else if (text.startsWith("/caidat")) {
      if (isAdmin) {
        let rows = "";
        for (const [k, v] of Object.entries(cfg.packages)) {
          rows += `• ${v.name} (<code>${k}</code>): <b>${formatVnd(v.price)}</b> | <b>${v.price_usdt || 0} USDT</b>\n`;
        }
        await sendTeleMessage(token, chatId,
          `⚙️ <b>CẤU HÌNH CLOUDFLARE WORKER BOT:</b>\n\n` +
          `👑 <b>Admin:</b> @${cfg.admin_username} (ID: <code>${cfg.admin_chat_id}</code>)\n\n` +
          `🏦 <b>Ngân hàng (VNĐ):</b>\n` +
          `• Ngân hàng: ${cfg.bank.bank_name} (${cfg.bank.bank_code})\n` +
          `• STK: <code>${cfg.bank.account_no}</code>\n` +
          `• Chủ TK: ${cfg.bank.account_name}\n\n` +
          `📝 <b>Cú pháp CK (Nội dung chuyển khoản):</b>\n` +
          `• Tiền tố mã đơn: <b>${cfg.order_prefix || "NAP"}</b> (Nội dung CK: <code>${cfg.order_prefix || "NAP"}xxxx</code>)\n\n` +
          `📥 <b>Link tải APK hiện tại:</b>\n` +
          `• <code>${cfg.app_download_url || "Chưa cài"}</code>\n\n` +
          `🪙 <b>Ví Crypto (USDT):</b>\n` +
          `• Mạng: <b>${cfg.usdt.network}</b>\n` +
          `• Địa chỉ ví: <code>${cfg.usdt.address}</code>\n\n` +
          `💎 <b>Bảng giá hiện tại (VNĐ / USDT):</b>\n${rows}\n` +
          `🛠 <b>LỆNH CÀI ĐẶT NHANH:</b>\n` +
          `• <code>/setapk https://...</code> - Đổi link tải APK\n` +
          `• <code>/setprefix NAP</code> - Đổi tiền tố nội dung CK (VD: NAPxxxx)\n` +
          `• <code>/setbank MB STK CTK</code> - Đổi ngân hàng\n` +
          `• <code>/setusdt TRC20 &lt;địa_chỉ_ví&gt;</code> - Cài ví USDT\n` +
          `• <code>/setgia 30d 500000</code> - Đổi giá VNĐ gói 30 ngày\n` +
          `• <code>/setgiausdt 30d 20</code> - Đổi giá USDT gói 30 ngày\n` +
          `• <code>/taokey 30d</code> - Tạo key kích hoạt thủ công`
        );
      }
    } else if (text.startsWith("/setapk")) {
      if (isAdmin) {
        const parts = text.split(/\s+/);
        if (parts.length >= 2) {
          const apkUrl = parts.slice(1).join(" ").trim();
          cfg.app_download_url = apkUrl;
          await saveTelegramConfig(env, cfg);
          await sendTeleMessage(token, chatId, `✅ Đã cập nhật link tải App APK thành:\n<code>${apkUrl}</code>`);
        } else {
          await sendTeleMessage(token, chatId, `Cú pháp: <code>/setapk &lt;LINK_TẢI_APK&gt;</code>\nVí dụ: <code>/setapk https://github.com/.../releases</code>`);
        }
      }
    } else if (text.startsWith("/setprefix")) {
      if (isAdmin) {
        const parts = text.split(/\s+/);
        if (parts.length >= 2) {
          const pref = parts[1].trim().toUpperCase().replace(/[^A-Z0-9]/g, "");
          if (pref) {
            cfg.order_prefix = pref;
            await saveTelegramConfig(env, cfg);
            await sendTeleMessage(token, chatId, `✅ Đã đổi tiền tố nội dung CK sang: <b>${pref}</b>\nVí dụ mã đơn khi khách mua: <code>${pref}1234</code>, <code>${pref}9899</code>`);
          } else {
            await sendTeleMessage(token, chatId, `❌ Tiền tố không hợp lệ! Vui lòng chỉ dùng chữ cái và số (VD: NAP)`);
          }
        } else {
          await sendTeleMessage(token, chatId, `Cú pháp: <code>/setprefix &lt;TIỀN_TỐ&gt;</code>\nVí dụ: <code>/setprefix NAP</code>`);
        }
      }
    } else if (text.startsWith("/setbank")) {
      if (isAdmin) {
        const parts = text.split(/\s+/);
        if (parts.length >= 4) {
          cfg.bank.bank_code = parts[1].toUpperCase();
          cfg.bank.account_no = parts[2];
          cfg.bank.account_name = parts.slice(3).join(" ").toUpperCase();
          await saveTelegramConfig(env, cfg);
          await sendTeleMessage(token, chatId, `✅ Đã cập nhật ngân hàng: ${cfg.bank.bank_code} - ${cfg.bank.account_no} - ${cfg.bank.account_name}`);
        } else {
          await sendTeleMessage(token, chatId, `Cú pháp: <code>/setbank &lt;MÃ_NH&gt; &lt;STK&gt; &lt;TÊN_CHỦ_TK&gt;</code>`);
        }
      }
    } else if (text.startsWith("/setusdt")) {
      if (isAdmin) {
        const parts = text.split(/\s+/);
        if (parts.length >= 3) {
          cfg.usdt.network = parts[1].toUpperCase();
          cfg.usdt.address = parts[2].trim();
          await saveTelegramConfig(env, cfg);
          await sendTeleMessage(token, chatId, 
            `✅ <b>ĐÃ CẬP NHẬT VÍ USDT:</b>\n\n` +
            `• Mạng: <b>${cfg.usdt.network}</b>\n` +
            `• Địa chỉ ví: <code>${cfg.usdt.address}</code>`
          );
        } else {
          await sendTeleMessage(token, chatId, `Cú pháp: <code>/setusdt &lt;MẠNG&gt; &lt;ĐỊA_CHỈ_VÍ&gt;</code>\nVí dụ: <code>/setusdt TRC20 TFxxxxxxxxxxxxxxxxxxxxxxxxx</code>`);
        }
      }
    } else if (text.startsWith("/setgiausdt")) {
      if (isAdmin) {
        const parts = text.split(/\s+/);
        if (parts.length >= 3) {
          const pkg = parts[1].toLowerCase();
          const p = parseFloat(parts[2].replace(",", "."));
          if (cfg.packages[pkg]) {
            cfg.packages[pkg].price_usdt = p;
            await saveTelegramConfig(env, cfg);
            await sendTeleMessage(token, chatId, `✅ Đã cập nhật giá USDT <b>${cfg.packages[pkg].name}</b> thành: <code>${p} USDT</code>`);
          } else {
            await sendTeleMessage(token, chatId, `❌ Không tìm thấy gói '${pkg}'! Các gói: <code>1d</code>, <code>7d</code>, <code>30d</code>, <code>90d</code>, <code>180d</code>, <code>365d</code>, <code>life</code>`);
          }
        } else {
          await sendTeleMessage(token, chatId, `Cú pháp: <code>/setgiausdt &lt;gói&gt; &lt;số_usdt&gt;</code>\nVí dụ: <code>/setgiausdt 30d 20</code>`);
        }
      }
    } else if (text.startsWith("/setgia")) {
      if (isAdmin) {
        const parts = text.split(/\s+/);
        if (parts.length >= 3) {
          const pkg = parts[1].toLowerCase();
          const p = parseInt(parts[2].replace(/[.,]/g, ""), 10);
          if (cfg.packages[pkg]) {
            cfg.packages[pkg].price = p;
            await saveTelegramConfig(env, cfg);
            await sendTeleMessage(token, chatId, `✅ Đã cập nhật giá VNĐ <b>${cfg.packages[pkg].name}</b> thành: <code>${formatVnd(p)}</code>`);
          } else {
            await sendTeleMessage(token, chatId, `❌ Không tìm thấy gói '${pkg}'! Các gói: <code>1d</code>, <code>7d</code>, <code>30d</code>, <code>90d</code>, <code>180d</code>, <code>365d</code>, <code>life</code>`);
          }
        } else {
          await sendTeleMessage(token, chatId, `Cú pháp: <code>/setgia &lt;gói&gt; &lt;số_tiền&gt;</code>\nVí dụ: <code>/setgia 30d 500000</code>`);
        }
      }
    } else if (text.startsWith("/taokey")) {
      if (isAdmin) {
        const parts = text.split(/\s+/);
        const pkg = parts.length > 1 ? parts[1].toLowerCase() : "30d";
        const key = generateRandomKey(pkg);
        
        let durSec = 30 * 86400;
        let durLabel = "30 Ngày";
        if (pkg === "1d" || pkg === "1") { durSec = 86400; durLabel = "1 Ngày"; }
        else if (pkg.includes("7")) { durSec = 7 * 86400; durLabel = "7 Ngày"; }
        else if (pkg.includes("30")) { durSec = 30 * 86400; durLabel = "30 Ngày"; }
        else if (pkg.includes("90")) { durSec = 90 * 86400; durLabel = "90 Ngày"; }
        else if (pkg.includes("180")) { durSec = 180 * 86400; durLabel = "180 Ngày"; }
        else if (pkg.includes("365") || pkg.includes("1y")) { durSec = 365 * 86400; durLabel = "365 Ngày"; }
        else if (pkg.includes("life")) { durSec = 0; durLabel = "Vĩnh viễn"; }

        const keyObj = {
          key: key,
          duration_seconds: durSec,
          duration_label: durLabel,
          note: "Tạo thủ công bởi Admin Telegram",
          bound_serial: null,
          created_at: Math.floor(Date.now() / 1000),
          status: "ACTIVE"
        };
        const { kv } = getKV(env);
        if (kv) await kv.put("key:" + key, JSON.stringify(keyObj));
        else MEMORY_KEYS[key] = keyObj;

        await sendTeleMessage(token, chatId,
          `🔑 <b>Key thủ công (${pkg.toUpperCase()}):</b>\n` +
          `👉 <code>${key}</code> 👈 (Bấm để copy)\n\n` +
          `⏳ Thời hạn: <b>${durLabel}</b>\n` +
          `<i>Key đã được lưu trực tiếp vào Cloudflare Worker và có thể kích hoạt ngay trên app.</i>`
        );
      }
    } else {
      const handled = await processTextPayment(token, cfg, env, chatId, fromUser.username, text);
      if (!handled && !isAdmin) {
        await sendTeleMessage(token, chatId, "👋 Xin chào! Vui lòng bấm /start để mở Menu hoặc chọn gói mua bản quyền nhé.");
      }
    }
  }
}

async function showStartMessage(token, cfg, env, chatId, username, firstName) {
  const adminUser = (cfg.admin_username || "").toLowerCase().replace(/^@/, "");
  const isAdmin = (username && username.toLowerCase().replace(/^@/, "") === adminUser) || (chatId && String(chatId) === String(cfg.admin_chat_id));

  if (isAdmin) {
    if (cfg.admin_chat_id !== chatId) {
      cfg.admin_chat_id = chatId;
      await saveTelegramConfig(env, cfg);
    }
    const adminText = 
      `👑 <b>XIN CHÀO QUẢN TRỊ VIÊN @${username}!</b>\n\n` +
      `☁️ <b>Bot chạy 24/24 trên Cloudflare Workers.</b>\n` +
      `Mọi thông báo đơn hàng VNĐ & USDT sẽ gửi trực tiếp về đây để bạn duyệt.\n\n` +
      `<b>Lệnh tạo key nhanh:</b>\n` +
      `• <code>/taokey 1d</code> | <code>/taokey 7d</code>\n` +
      `• <code>/taokey 30d</code> | <code>/taokey 90d</code>\n` +
      `• <code>/taokey 180d</code> | <code>/taokey 365d</code>\n` +
      `• <code>/taokey life</code> (Vĩnh viễn)\n\n` +
      `<b>Cài đặt hệ thống:</b>\n` +
      `• <code>/setprefix NAP</code> - Tiền tố CK (VD: NAPxxxx)\n` +
      `• <code>/setbank MB STK CTK</code>\n` +
      `• <code>/setusdt TRC20 &lt;ví_usdt&gt;</code>\n` +
      `• <code>/setgia 30d 500000</code>\n` +
      `• <code>/setgiausdt 30d 20</code>\n` +
      `• <code>/caidat</code> - Xem cấu hình & bảng giá`;
    await sendTeleMessage(token, chatId, adminText);
    return;
  }

  const welcomeText = 
    `👋 Xin chào <b>${firstName || "Bạn"}</b>!\n\n` +
    `Chào mừng bạn đến với <b>Camera Assistant</b> — ` +
    `Giải pháp camera ảo chống phát hiện hàng đầu cho Android và IOS\n\n` +
    `✨ Hỗ trợ KYC nhận diện khuôn mặt, Livestream, giả lập camera mượt mà không delay.\n` +
    `💳 Chấp nhận thanh toán qua <b>Chuyển khoản Ngân hàng (VNĐ)</b> và <b>USDT Crypto</b>.\n\n` +
    `Vui lòng chọn chức năng bạn cần bên dưới:`;

  const keyboard = {
    inline_keyboard: [
      [{ text: "🛒 Mua Key Bản Quyền", callback_data: "menu_buy" }],
      [
        { text: "📥 Tải App APK", url: cfg.app_download_url || `https://t.me/${adminUser}` },
        { text: "📖 Hướng Dẫn Dùng", callback_data: "menu_guide" }
      ],
      [{ text: "💬 Hỗ Trợ Kỹ Thuật", url: `https://t.me/${adminUser}` }]
    ]
  };
  await sendTeleMessage(token, chatId, welcomeText, keyboard);
}

async function showMenuBuy(token, cfg, chatId) {
  const p = cfg.packages;
  const text = 
    `💎 <b>BẢNG GIÁ BẢN QUYỀN CAMERA ASSISTANT (VCAM)</b>\n\n` +
    `• <b>${p['1d'].name}:</b> <code>${formatVnd(p['1d'].price)}</code> | <code>${p['1d'].price_usdt || 2} USDT</code>\n` +
    `• <b>${p['7d'].name}:</b> <code>${formatVnd(p['7d'].price)}</code> | <code>${p['7d'].price_usdt || 7} USDT</code>\n` +
    `• <b>${p['30d'].name}:</b> <code>${formatVnd(p['30d'].price)}</code> | <code>${p['30d'].price_usdt || 20} USDT</code> 🔥\n` +
    `• <b>${p['90d'].name}:</b> <code>${formatVnd(p['90d'].price)}</code> | <code>${p['90d'].price_usdt || 48} USDT</code>\n` +
    `• <b>${p['180d'].name}:</b> <code>${formatVnd(p['180d'].price)}</code> | <code>${p['180d'].price_usdt || 84} USDT</code>\n` +
    `• <b>${p['365d'].name}:</b> <code>${formatVnd(p['365d'].price)}</code> | <code>${p['365d'].price_usdt || 144} USDT</code>\n` +
    `• <b>${p['life'].name}:</b> <code>${formatVnd(p['life'].price)}</code> | <code>${p['life'].price_usdt || 240} USDT</code> 👑\n\n` +
    `👉 <i>Vui lòng bấm chọn gói bạn muốn mua bên dưới:</i>`;

  const keyboard = {
    inline_keyboard: [
      [
        { text: `1 Ngày (${formatVnd(p['1d'].price)} / ${p['1d'].price_usdt}U)`, callback_data: "choose_1d" },
        { text: `7 Ngày (${formatVnd(p['7d'].price)} / ${p['7d'].price_usdt}U)`, callback_data: "choose_7d" }
      ],
      [
        { text: `🔥 30 Ngày (${formatVnd(p['30d'].price)} / ${p['30d'].price_usdt}U)`, callback_data: "choose_30d" },
        { text: `90 Ngày (${formatVnd(p['90d'].price)} / ${p['90d'].price_usdt}U)`, callback_data: "choose_90d" }
      ],
      [
        { text: `180 Ngày (${formatVnd(p['180d'].price)} / ${p['180d'].price_usdt}U)`, callback_data: "choose_180d" },
        { text: `365 Ngày (${formatVnd(p['365d'].price)} / ${p['365d'].price_usdt}U)`, callback_data: "choose_365d" }
      ],
      [
        { text: `💎 Vĩnh Viễn (${formatVnd(p['life'].price)} / ${p['life'].price_usdt}U)`, callback_data: "choose_life" }
      ],
      [{ text: "⬅️ Quay lại Menu chính", callback_data: "menu_back" }]
    ]
  };
  await sendTeleMessage(token, chatId, text, keyboard);
}

async function showSelectMethod(token, cfg, chatId, pkgKey) {
  const pkg = cfg.packages[pkgKey];
  if (!pkg) {
    await sendTeleMessage(token, chatId, "❌ Gói bản quyền không hợp lệ!");
    return;
  }
  const text = 
    `📦 <b>BẠN ĐÃ CHỌN: ${pkg.name.toUpperCase()}</b>\n\n` +
    `⏳ Thời hạn sử dụng: <b>${pkg.label}</b>\n` +
    `💵 Giá VNĐ: <b>${formatVnd(pkg.price)}</b>\n` +
    `🪙 Giá USDT: <b>${pkg.price_usdt || 0} USDT</b>\n\n` +
    `👉 <b>Vui lòng chọn hình thức bạn muốn thanh toán:</b>`;

  const keyboard = {
    inline_keyboard: [
      [{ text: `🏦 Chuyển khoản VNĐ (${formatVnd(pkg.price)})`, callback_data: `pay_vnd_${pkgKey}` }],
      [{ text: `🪙 Thanh toán USDT (${pkg.price_usdt || 0} USDT)`, callback_data: `pay_usdt_${pkgKey}` }],
      [{ text: "⬅️ Chọn lại gói khác", callback_data: "menu_buy" }]
    ]
  };
  await sendTeleMessage(token, chatId, text, keyboard);
}

async function processBuyVnd(token, cfg, env, chatId, username, pkgKey) {
  const order = await createTelegramOrder(env, chatId, username, pkgKey, "VND");
  if (!order) {
    await sendTeleMessage(token, chatId, "❌ Gói bản quyền không hợp lệ!");
    return;
  }
  const b = cfg.bank;
  const qrUrl = `https://img.vietqr.io/image/${b.bank_code}-${b.account_no}-compact2.png?amount=${order.price}&addInfo=${order.order_id}&accountName=${encodeURIComponent(b.account_name)}`;

  const caption = 
    `🧾 <b>ĐƠN HÀNG VNĐ: #${order.order_id}</b>\n` +
    `📦 Gói: <b>${order.package_name}</b> (${order.label})\n` +
    `💰 Số tiền: <b>${formatVnd(order.price)}</b>\n\n` +
    `🏦 <b>THÔNG TIN CHUYỂN KHOẢN:</b>\n` +
    `• Ngân hàng: <b>${b.bank_name} (${b.bank_code})</b>\n` +
    `• Số tài khoản: <code>${b.account_no}</code> (Bấm để copy)\n` +
    `• Tên chủ thẻ: <b>${b.account_name}</b>\n` +
    `• Nội dung CK (bắt buộc): <code>${order.order_id}</code>\n\n` +
    `⚠️ <b>LƯU Ý QUAN TRỌNG:</b>\n` +
    `1. Quét mã QR ở trên hoặc chuyển khoản đúng nội dung: <code>${order.order_id}</code>\n` +
    `2. Sau khi chuyển xong, <b>hãy gửi ảnh chụp màn hình biên lai (bill)</b> vào chat này.\n` +
    `3. Hệ thống sẽ xác nhận và gửi Key kích hoạt cho bạn ngay lập tức!`;

  const keyboard = {
    inline_keyboard: [
      [{ text: "❌ Hủy đơn này", callback_data: `cancel_${order.order_id}` }],
      [{ text: "💬 Hỗ Trợ Kỹ Thuật", url: `https://t.me/${cfg.admin_username}` }]
    ]
  };
  await sendTelePhoto(token, chatId, qrUrl, caption, keyboard);
}

async function processBuyUsdt(token, cfg, env, chatId, username, pkgKey) {
  const order = await createTelegramOrder(env, chatId, username, pkgKey, "USDT");
  if (!order) {
    await sendTeleMessage(token, chatId, "❌ Gói bản quyền không hợp lệ!");
    return;
  }
  const u = cfg.usdt;
  const qrUrl = `https://api.qrserver.com/v1/create-qr-code/?size=300x300&data=${encodeURIComponent(u.address)}`;

  const caption = 
    `🧾 <b>ĐƠN HÀNG USDT: #${order.order_id}</b>\n` +
    `📦 Gói: <b>${order.package_name}</b> (${order.label})\n` +
    `💰 Số tiền cần chuyển: <b>${order.price_usdt} USDT</b>\n\n` +
    `🪙 <b>THÔNG TIN VÍ NHẬN USDT:</b>\n` +
    `• Mạng (Network): <b>${u.network}</b>\n` +
    `• Địa chỉ ví nhận:\n` +
    `<code>${u.address}</code>\n` +
    `<i>(Bấm vào địa chỉ ví ở trên để copy nhanh)</i>\n` +
    `• Ghi chú: <i>${u.note}</i>\n\n` +
    `⚠️ <b>HƯỚNG DẪN XÁC NHẬN:</b>\n` +
    `1. Chuyển chính xác <b>${order.price_usdt} USDT</b> (mạng <b>${u.network}</b>) tới ví trên.\n` +
    `2. Sau khi chuyển xong, <b>hãy gửi Mã giao dịch (TxID / Hash)</b> hoặc <b>ảnh chụp màn hình biên lai</b> vào ô chat này.\n` +
    `3. Admin sẽ kiểm tra trên Blockchain và gửi Key kích hoạt cho bạn ngay lập tức!`;

  const keyboard = {
    inline_keyboard: [
      [{ text: "❌ Hủy đơn này", callback_data: `cancel_${order.order_id}` }],
      [{ text: "💬 Hỗ Trợ Kỹ Thuật", url: `https://t.me/${cfg.admin_username}` }]
    ]
  };
  await sendTelePhoto(token, chatId, qrUrl, caption, keyboard);
}

async function processPhotoBill(token, cfg, env, chatId, username, photoFileId) {
  const userPending = await getUserPendingOrder(env, chatId);
  if (!userPending) {
    await sendTeleMessage(token, chatId,
      `⚠️ Bạn chưa có đơn hàng nào đang chờ thanh toán!\n` +
      `Vui lòng bấm /start và chọn gói bản quyền trước khi gửi ảnh biên lai nhé.`
    );
    return;
  }

  const oid = userPending.order_id;
  const method = userPending.method || "VND";
  await sendTeleMessage(token, chatId,
    `✅ <b>Đã nhận được ảnh biên lai cho đơn #${oid}!</b>\n` +
    `⏳ Admin đang kiểm tra thanh toán. Key kích hoạt sẽ được gửi trực tiếp vào đây trong 1 - 3 phút.`
  );

  const adminCid = cfg.admin_chat_id;
  if (adminCid) {
    let adminCaption = "";
    if (method === "USDT") {
      adminCaption = 
        `🔔 <b>CÓ ĐƠN HÀNG USDT CẦN DUYỆT!</b>\n\n` +
        `🧾 Mã đơn: <b>#${oid}</b>\n` +
        `👤 Khách: @${username || "N/A"} (ID: <code>${chatId}</code>)\n` +
        `📦 Gói: <b>${userPending.package_name}</b> (${userPending.label})\n` +
        `🪙 Số tiền: <b>${userPending.price_usdt || 0} USDT</b> (Mạng ${cfg.usdt.network})\n` +
        `📸 Hình thức: Ảnh chụp màn hình biên lai\n\n` +
        `👉 <i>Kiểm tra ví nhận và bấm nút bên dưới:</i>`;
    } else {
      adminCaption = 
        `🔔 <b>CÓ ĐƠN HÀNG VNĐ CẦN DUYỆT!</b>\n\n` +
        `🧾 Mã đơn: <b>#${oid}</b>\n` +
        `👤 Khách: @${username || "N/A"} (ID: <code>${chatId}</code>)\n` +
        `📦 Gói: <b>${userPending.package_name}</b> (${userPending.label})\n` +
        `💰 Số tiền: <b>${formatVnd(userPending.price)}</b>\n\n` +
        `👉 <i>Kiểm tra tài khoản ngân hàng và bấm nút bên dưới:</i>`;
    }

    const adminKb = {
      inline_keyboard: [
        [
          { text: `✅ Duyệt Cấp Key (#${oid})`, callback_data: `approve_${oid}` },
          { text: `❌ Từ Chối`, callback_data: `reject_${oid}` }
        ]
      ]
    };
    await sendTelePhoto(token, adminCid, photoFileId, adminCaption, adminKb);
  }
}

async function processTextPayment(token, cfg, env, chatId, username, text) {
  const userPending = await getUserPendingOrder(env, chatId);
  if (!userPending) return false;

  const oid = userPending.order_id;
  const method = userPending.method || "VND";
  userPending.payment_note = text;
  await saveTelegramOrder(env, userPending);

  await sendTeleMessage(token, chatId,
    `✅ <b>Đã nhận được thông tin thanh toán cho đơn #${oid}!</b>\n\n` +
    `📝 <b>Nội dung / TxID bạn gửi:</b>\n<code>${text}</code>\n\n` +
    `⏳ Admin đang kiểm tra giao dịch. Key kích hoạt sẽ được gửi trực tiếp vào đây trong 1 - 3 phút.`
  );

  const adminCid = cfg.admin_chat_id;
  if (adminCid) {
    let adminMsg = "";
    if (method === "USDT") {
      adminMsg = 
        `🔔 <b>CÓ GIAO DỊCH USDT CẦN DUYỆT! (TXID)</b>\n\n` +
        `🧾 Mã đơn: <b>#${oid}</b>\n` +
        `👤 Khách: @${username || "N/A"} (ID: <code>${chatId}</code>)\n` +
        `📦 Gói: <b>${userPending.package_name}</b> (${userPending.label})\n` +
        `🪙 Số tiền: <b>${userPending.price_usdt || 0} USDT</b> (Mạng ${cfg.usdt.network})\n\n` +
        `🔗 <b>Mã TxID / Ghi chú của khách:</b>\n` +
        `<code>${text}</code>\n\n` +
        `👉 <i>Kiểm tra ví nhận và bấm nút bên dưới:</i>`;
    } else {
      adminMsg = 
        `🔔 <b>CÓ THÔNG TIN THANH TOÁN CHO ĐƠN #${oid}!</b>\n\n` +
        `👤 Khách: @${username || "N/A"} (ID: <code>${chatId}</code>)\n` +
        `📦 Gói: <b>${userPending.package_name}</b> (${userPending.label})\n` +
        `💰 Số tiền: <b>${formatVnd(userPending.price)}</b>\n\n` +
        `📝 <b>Nội dung nhắn:</b>\n<code>${text}</code>\n\n` +
        `👉 <i>Kiểm tra tài khoản và bấm nút bên dưới:</i>`;
    }

    const adminKb = {
      inline_keyboard: [
        [
          { text: `✅ Duyệt Cấp Key (#${oid})`, callback_data: `approve_${oid}` },
          { text: `❌ Từ Chối`, callback_data: `reject_${oid}` }
        ]
      ]
    };
    await sendTeleMessage(token, adminCid, adminMsg, adminKb);
  }
  return true;
}

async function processApproveOrder(token, cfg, env, cb, orderId) {
  const order = await getTelegramOrder(env, orderId);
  if (!order) return;
  if (order.status === "COMPLETED") {
    await answerTeleCallback(token, cb.id, `Đơn #${orderId} đã được duyệt trước đó!`, true);
    return;
  }

  const pkgKey = order.package_key || "30d";
  const keyCode = generateRandomKey(pkgKey);
  order.status = "COMPLETED";
  order.key_issued = keyCode;
  order.completed_at = Math.floor(Date.now() / 1000);
  await saveTelegramOrder(env, order);

  // LƯU TRỰC TIẾP VÀO HỆ THỐNG KEY WORKER
  let durSec = 30 * 86400;
  let durLabel = order.label || "30 Ngày";
  if (order.days === 1) durSec = 86400;
  else if (order.days === 7) durSec = 7 * 86400;
  else if (order.days === 30) durSec = 30 * 86400;
  else if (order.days === 90) durSec = 90 * 86400;
  else if (order.days === 180) durSec = 180 * 86400;
  else if (order.days === 365) durSec = 365 * 86400;
  else if (order.days === 0) { durSec = 0; durLabel = "Vĩnh viễn"; }

  const keyObj = {
    key: keyCode,
    duration_seconds: durSec,
    duration_label: durLabel,
    note: `Khách @${order.customer_username || "N/A"} - Đơn #${order.order_id}`,
    bound_serial: null,
    created_at: Math.floor(Date.now() / 1000),
    status: "ACTIVE"
  };

  const { kv } = getKV(env);
  if (kv) {
    await kv.put("key:" + keyCode, JSON.stringify(keyObj));
    await kv.delete("tele_user_pending:" + order.customer_id);
  } else {
    MEMORY_KEYS[keyCode] = keyObj;
  }

  // Gửi key cho khách
  const custMsg = 
    `🎉 <b>XÁC NHẬN THANH TOÁN THÀNH CÔNG!</b>\n\n` +
    `Cảm ơn bạn đã mua bản quyền <b>Camera Assistant</b>.\n\n` +
    `🔑 <b>MÃ KÍCH HOẠT (KEY) CỦA BẠN:</b>\n` +
    `👉 <code>${keyCode}</code> 👈 (Bấm vào mã để copy)\n\n` +
    `⏳ Thời hạn: <b>${order.label}</b>\n\n` +
    `📲 <b>HƯỚNG DẪN KÍCH HOẠT:</b>\n` +
    (cfg.app_download_url ? `📥 Link tải App APK: ${cfg.app_download_url}\n` : "") +
    `1. Mở ứng dụng <b>Camera Assistant</b> trên điện thoại\n` +
    `2. Dán mã <code>${keyCode}</code> vào ô <i>Mã kích hoạt</i>\n` +
    `3. Bấm nút <b>Kích hoạt Online</b> là hoàn tất!\n\n` +
    `💬 Hỗ trợ kỹ thuật: @${cfg.admin_username}`;
  await sendTeleMessage(token, order.customer_id, custMsg);

  await answerTeleCallback(token, cb.id, `Đã duyệt đơn #${orderId} thành công!`, true);

  // Cập nhật tin nhắn Admin
  const adminName = cb.from?.username || "Admin";
  const approvedText = 
    `✅ <b>ĐÃ DUYỆT ĐƠN #${orderId}</b>\n` +
    `👤 Duyệt bởi: @${adminName}\n` +
    `🔑 Key đã cấp: <code>${keyCode}</code>\n` +
    `📦 Gói: ${order.package_name} (${order.label})\n` +
    `👤 Khách: @${order.customer_username} (ID: <code>${order.customer_id}</code>)`;

  const msg = cb.message;
  if (msg && msg.chat && msg.message_id) {
    if (msg.photo && msg.photo.length > 0) {
      await editTeleCaption(token, msg.chat.id, msg.message_id, approvedText);
    } else {
      await editTeleText(token, msg.chat.id, msg.message_id, approvedText);
    }
  }
}

async function processRejectOrder(token, cfg, env, cb, orderId) {
  const order = await getTelegramOrder(env, orderId);
  if (!order) return;
  order.status = "REJECTED";
  await saveTelegramOrder(env, order);

  await sendTeleMessage(token, order.customer_id,
    `❌ <b>Đơn hàng #${orderId} chưa thể xác nhận thanh toán!</b>\n\n` +
    `Biên lai chuyển khoản/TxID chưa hợp lệ hoặc tiền chưa vào tài khoản.\n` +
    `Vui lòng liên hệ @${cfg.admin_username} để được kiểm tra và hỗ trợ nhé.`
  );

  await answerTeleCallback(token, cb.id, `Đã từ chối đơn #${orderId}!`, true);

  const msg = cb.message;
  if (msg && msg.chat && msg.message_id) {
    const rejectedText = `❌ <b>ĐÃ TỪ CHỐI ĐƠN #${orderId}</b> (Khách: @${order.customer_username})`;
    if (msg.photo && msg.photo.length > 0) {
      await editTeleCaption(token, msg.chat.id, msg.message_id, rejectedText);
    } else {
      await editTeleText(token, msg.chat.id, msg.message_id, rejectedText);
    }
  }
}

function getDashboardHtml() {
  return `<!DOCTYPE html>
<html lang="vi">
<head>
  <meta charset="utf-8">
  <title>CAMERA ASSISTANT LICENSE MANAGER</title>
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.4.0/css/all.min.css">
  <script>
    function selectDurationCard(btn, val, label, price) {
      try {
        if (!btn) return;
        var card = (btn.closest && btn.closest('.duration-card')) ? btn.closest('.duration-card') : btn;
        if (!card) return;

        var cards = document.querySelectorAll('.duration-card');
        for (var i = 0; i < cards.length; i++) {
          cards[i].classList.remove('active');
        }
        card.classList.add('active');

        if (!val) val = card.getAttribute('data-val');
        if (!label) label = card.getAttribute('data-label');
        if (price === undefined || price === null || price === '') price = card.getAttribute('data-price');

        var vEl = document.getElementById('selectedDurationVal');
        var lEl = document.getElementById('selectedDurationLabel');
        if (vEl && val) vEl.value = val;
        if (lEl && label) lEl.value = label;

        var pEl = document.getElementById('inpPrice');
        if (pEl && price !== undefined && price !== null && price !== '') {
          var pNum = Number(price);
          pEl.value = pNum.toLocaleString('vi-VN') + ' đ';
        }
      } catch (err) {
        console.error('Error in selectDurationCard:', err);
      }
    }
    window.selectDurationCard = selectDurationCard;
  </script>
  <style>
    :root {
      --bg: #07090e;
      --card: #0d111a;
      --card-inner: #090c13;
      --border: #161e2e;
      --border-light: #222d42;
      --cyan: #00d2ff;
      --green: #10b981;
      --green-btn: #00c853;
      --yellow: #fbbf24;
      --red: #ef4444;
      --blue: #0284c7;
      --text: #94a3b8;
      --text-white: #f8fafc;
      --text-dim: #64748b;
    }

    * { box-sizing: border-box; }
    body {
      margin: 0;
      padding: 24px;
      background: var(--bg);
      color: var(--text);
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Inter", sans-serif;
      font-size: 13px;
      line-height: 1.5;
    }

    .container {
      max-width: 1400px;
      margin: 0 auto;
    }

    /* TOP BAR */
    .top-bar {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 24px;
      flex-wrap: wrap;
      gap: 16px;
    }
    .brand-wrap {
      display: flex;
      align-items: center;
      gap: 12px;
    }
    .avatar-vt {
      width: 42px;
      height: 42px;
      background: #00d2ff;
      color: #021a24;
      font-weight: 900;
      font-size: 19px;
      display: flex;
      align-items: center;
      justify-content: center;
      border-radius: 8px;
      box-shadow: 0 0 15px rgba(0, 210, 255, 0.35);
    }
    .brand-title {
      font-size: 19px;
      font-weight: 800;
      color: #fff;
      letter-spacing: 0.5px;
    }
    .badge-cf {
      background: rgba(16, 185, 129, 0.12);
      color: #10b981;
      border: 1px solid rgba(16, 185, 129, 0.35);
      padding: 2px 8px;
      border-radius: 4px;
      font-size: 11px;
      font-weight: 600;
      margin-left: 8px;
    }
    .brand-sub {
      font-size: 12px;
      color: #64748b;
      margin-top: 2px;
    }
    .pulse-dot {
      width: 7px;
      height: 7px;
      background: #10b981;
      border-radius: 50%;
      display: inline-block;
      box-shadow: 0 0 8px #10b981;
    }
    .btn-top {
      background: #111827;
      border: 1px solid #1f293d;
      color: #cbd5e1;
      border-radius: 6px;
      padding: 8px 14px;
      font-size: 12px;
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      gap: 6px;
      font-weight: 500;
      transition: all 0.2s;
    }
    .btn-top:hover {
      background: #1a2234;
      border-color: #334155;
    }
    .btn-logout {
      background: rgba(239, 68, 68, 0.08);
      border-color: rgba(239, 68, 68, 0.3);
      color: #f87171;
    }
    .btn-logout:hover {
      background: rgba(239, 68, 68, 0.18);
    }

    /* 5 STATS CARDS */
    .stats-row {
      display: grid;
      grid-template-columns: repeat(5, 1fr);
      gap: 14px;
      margin-bottom: 24px;
    }
    @media (max-width: 1100px) {
      .stats-row { grid-template-columns: repeat(2, 1fr); }
    }
    @media (max-width: 700px) {
      .stats-row { grid-template-columns: 1fr; }
    }

    .stat-card {
      background: var(--card);
      border: 1px solid var(--border);
      border-radius: 10px;
      padding: 16px 18px;
      display: flex;
      flex-direction: column;
      justify-content: space-between;
    }
    .stat-title {
      font-size: 13px;
      color: #8492a6;
      margin-bottom: 8px;
      display: flex;
      align-items: center;
      justify-content: space-between;
    }
    .stat-val {
      font-size: 26px;
      font-weight: 800;
      color: #f1f5f9;
      margin-bottom: 6px;
    }
    .stat-val-green { color: #10b981; }
    .stat-val-gold { color: #fbbf24; }
    .stat-val-cyan { color: #00d2ff; }
    .stat-val-red { color: #ef4444; }
    .stat-sub {
      font-size: 11px;
      color: #526079;
    }
    .badge-vnd {
      background: #78350f;
      color: #fef08a;
      padding: 2px 6px;
      border-radius: 4px;
      font-size: 10px;
      font-weight: 700;
    }
    .dot-green {
      width: 7px;
      height: 7px;
      background: #10b981;
      border-radius: 50%;
      display: inline-block;
      box-shadow: 0 0 6px #10b981;
    }
    .dot-red {
      width: 7px;
      height: 7px;
      background: #ef4444;
      border-radius: 50%;
      display: inline-block;
      box-shadow: 0 0 6px #ef4444;
    }

    /* PANELS */
    .panel {
      background: var(--card);
      border: 1px solid var(--border);
      border-radius: 10px;
      padding: 20px 22px;
      margin-bottom: 24px;
    }
    .panel-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 18px;
      flex-wrap: wrap;
      gap: 10px;
    }
    .panel-title-cyan {
      font-size: 14px;
      font-weight: 700;
      color: #00d2ff;
      letter-spacing: 0.5px;
      display: flex;
      align-items: center;
      gap: 8px;
    }
    .panel-sub-right {
      font-size: 12px;
      color: #64748b;
    }

    /* FORMS */
    .form-row {
      display: flex;
      gap: 16px;
      margin-bottom: 16px;
      flex-wrap: wrap;
    }
    .form-group {
      display: flex;
      flex-direction: column;
    }
    .form-group label {
      font-size: 12px;
      font-weight: 600;
      color: #8b949e;
      margin-bottom: 6px;
      display: flex;
      align-items: center;
      gap: 6px;
    }
    .form-group input, .form-group select {
      background: var(--card-inner);
      border: 1px solid var(--border);
      border-radius: 6px;
      color: #fff;
      padding: 10px 12px;
      font-size: 13px;
      outline: none;
      transition: border-color 0.2s;
    }
    .form-group input:focus, .form-group select:focus {
      border-color: #00d2ff;
    }
    .field-hint {
      font-size: 11px;
      color: #526079;
      margin-top: 4px;
    }
    .char-count {
      font-size: 11px;
      color: #64748b;
      font-family: monospace;
    }
    .badge-free-edit {
      color: #10b981;
      font-size: 11px;
      font-weight: 500;
    }
    .btn-create-key {
      background: #00c853;
      color: #fff;
      border: none;
      border-radius: 6px;
      padding: 11px 22px;
      font-weight: 700;
      font-size: 13px;
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      gap: 8px;
      white-space: nowrap;
      height: 42px;
      box-shadow: 0 0 15px rgba(0, 200, 83, 0.35);
      transition: all 0.2s;
    }
    .btn-create-key:hover {
      background: #00e676;
      box-shadow: 0 0 20px rgba(0, 200, 83, 0.55);
    }

    /* DURATION BUTTON CARDS */
    .duration-block {
      margin-bottom: 20px;
    }
    .duration-label {
      font-size: 13px;
      font-weight: 600;
      color: #cbd5e1;
      display: flex;
      align-items: center;
      gap: 6px;
      margin-bottom: 10px;
    }
    .duration-selector {
      display: grid;
      grid-template-columns: repeat(8, 1fr);
      gap: 10px;
    }
    @media (max-width: 1200px) {
      .duration-selector { grid-template-columns: repeat(4, 1fr); }
    }
    @media (max-width: 650px) {
      .duration-selector { grid-template-columns: repeat(2, 1fr); }
    }
    .duration-card, button.duration-card {
      background: #111827;
      border: 1px solid #1f293d;
      border-radius: 8px;
      padding: 12px 6px;
      text-align: center;
      cursor: pointer;
      user-select: none;
      transition: all 0.2s cubic-bezier(0.4, 0, 0.2, 1);
      display: flex;
      flex-direction: column;
      justify-content: center;
      align-items: center;
      font-family: inherit;
      width: 100%;
      box-sizing: border-box;
      outline: none;
      -webkit-tap-highlight-color: transparent;
    }
    .duration-card:hover {
      background: #1a2234;
      border-color: #3b82f6;
      transform: translateY(-1px);
    }
    .duration-card:active {
      transform: scale(0.97);
    }
    .duration-card .dur-name {
      font-size: 13px;
      font-weight: 700;
      color: #f1f5f9;
      letter-spacing: 0.2px;
      white-space: nowrap;
      pointer-events: none;
    }
    .duration-card .dur-price {
      font-size: 12px;
      color: #8b949e;
      margin-top: 4px;
      font-weight: 500;
      white-space: nowrap;
      pointer-events: none;
    }
    .duration-card.active,
    button.duration-card.active {
      background: #2563eb !important;
      border-color: #3b82f6 !important;
      box-shadow: 0 0 16px rgba(37, 99, 235, 0.45) !important;
      transform: translateY(-2px);
    }
    .duration-card.active .dur-name,
    .duration-card.active .dur-price,
    button.duration-card.active .dur-name,
    button.duration-card.active .dur-price,
    button.duration-card.active span {
      color: #ffffff !important;
    }

    /* TABLE */
    .table-top {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 16px;
      flex-wrap: wrap;
      gap: 12px;
    }
    .table-title {
      font-size: 14px;
      font-weight: 700;
      color: #f1f5f9;
      letter-spacing: 0.5px;
    }
    .badge-count {
      font-size: 12px;
      color: #64748b;
      font-weight: normal;
    }
    .search-wrap {
      position: relative;
      display: flex;
      align-items: center;
    }
    .search-wrap i {
      position: absolute;
      left: 12px;
      color: #64748b;
      font-size: 12px;
    }
    .search-wrap input {
      background: var(--card-inner);
      border: 1px solid var(--border);
      border-radius: 6px;
      padding: 8px 12px 8px 34px;
      color: #fff;
      font-size: 12px;
      width: 280px;
      outline: none;
    }
    .search-wrap input:focus {
      border-color: #00d2ff;
    }
    .filter-select {
      background: var(--card-inner);
      border: 1px solid var(--border);
      border-radius: 6px;
      padding: 8px 12px;
      color: #cbd5e1;
      font-size: 12px;
      outline: none;
    }
    .table-scroll {
      overflow-x: auto;
    }
    .pro-table {
      width: 100%;
      border-collapse: collapse;
      font-size: 12px;
    }
    .pro-table th {
      text-align: left;
      padding: 12px 14px;
      color: #64748b;
      font-weight: 700;
      font-size: 11px;
      border-bottom: 1px solid var(--border);
      letter-spacing: 0.5px;
      white-space: nowrap;
    }
    .pro-table td {
      padding: 14px;
      border-bottom: 1px solid #131a28;
      vertical-align: middle;
    }
    .pro-table tr:hover {
      background: rgba(255, 255, 255, 0.02);
    }

    /* BADGES & BUTTONS */
    .badge-mxh {
      display: inline-flex;
      align-items: center;
      gap: 5px;
      background: #111827;
      border: 1px solid #1f293d;
      color: #94a3b8;
      padding: 2px 7px;
      border-radius: 4px;
      font-size: 11px;
      margin-top: 4px;
    }
    .pkg-badge {
      display: inline-block;
      padding: 3px 8px;
      border-radius: 4px;
      font-weight: 700;
      font-size: 11px;
      background: rgba(16, 185, 129, 0.15);
      color: #10b981;
      border: 1px solid rgba(16, 185, 129, 0.35);
    }
    .status-pill {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 4px 10px;
      border-radius: 12px;
      font-size: 11px;
      font-weight: 600;
      white-space: nowrap;
    }
    .status-active {
      background: rgba(16, 185, 129, 0.15);
      color: #10b981;
      border: 1px solid rgba(16, 185, 129, 0.4);
    }
    .status-banned {
      background: rgba(239, 68, 68, 0.15);
      color: #ef4444;
      border: 1px solid rgba(239, 68, 68, 0.4);
    }
    .status-expired {
      background: rgba(245, 158, 11, 0.15);
      color: #f59e0b;
      border: 1px solid rgba(245, 158, 11, 0.4);
    }
    .status-unused {
      background: rgba(100, 116, 139, 0.15);
      color: #94a3b8;
      border: 1px solid rgba(100, 116, 139, 0.4);
    }

    .btn-tbl-action {
      padding: 6px 10px;
      border-radius: 5px;
      font-size: 11px;
      font-weight: 600;
      cursor: pointer;
      border: 1px solid transparent;
      display: inline-flex;
      align-items: center;
      gap: 5px;
      white-space: nowrap;
      transition: all 0.2s;
    }
    .btn-tbl-ban {
      background: rgba(239, 68, 68, 0.12);
      border-color: rgba(239, 68, 68, 0.35);
      color: #f87171;
    }
    .btn-tbl-ban:hover {
      background: rgba(239, 68, 68, 0.25);
    }
    .btn-tbl-reset {
      background: rgba(2, 132, 199, 0.12);
      border-color: rgba(2, 132, 199, 0.35);
      color: #38bdf8;
    }
    .btn-tbl-reset:hover {
      background: rgba(2, 132, 199, 0.25);
    }
    .btn-tbl-del {
      background: #111827;
      border-color: #273449;
      color: #94a3b8;
      padding: 6px 8px;
    }
    .btn-tbl-del:hover {
      background: #1e293b;
      color: #f87171;
    }

    .key-strong {
      font-family: monospace;
      font-size: 13px;
      color: #f1f5f9;
      letter-spacing: 0.5px;
      cursor: pointer;
    }
    .key-strong:hover {
      color: #00d2ff;
    }

    /* KEY CREATED POPUP / BOX */
    .key-alert-box {
      margin-top: 16px;
      background: rgba(0, 210, 255, 0.05);
      border: 1px dashed #00d2ff;
      border-radius: 8px;
      padding: 14px 18px;
      display: none;
      align-items: center;
      justify-content: space-between;
      flex-wrap: wrap;
      gap: 12px;
    }
    .key-alert-code {
      font-family: monospace;
      font-size: 20px;
      font-weight: 800;
      color: #00d2ff;
      letter-spacing: 1px;
    }
  </style>
</head>
<body>
  <div class="container">
    <!-- TOP BAR -->
    <header class="top-bar">
      <div class="brand-wrap">
        <div class="avatar-vt">VT</div>
        <div>
          <div class="brand-title">
            CAMERA ASSISTANT LICENSE MANAGER
            <span class="badge-cf">Cloudflare Worker</span>
          </div>
          <div class="brand-sub">Hệ thống cấp phép &amp; kiểm soát bản quyền thời gian thực (ECDSA + KV Storage)</div>
        </div>
      </div>
      <div style="display:flex;align-items:center;gap:12px;flex-wrap:wrap;">
        <div style="display:inline-flex;align-items:center;gap:6px;background:#111827;border:1px solid #1f293d;padding:6px 12px;border-radius:20px;font-size:12px;color:#cbd5e1;">
          <span class="pulse-dot"></span> Server Trực tuyến (&lt; 20ms)
        </div>
        <button class="btn-top" onclick="refreshData()">
          <i class="fa-solid fa-arrows-rotate"></i> Làm mới KV
        </button>
        <button class="btn-top btn-logout" onclick="logoutAdmin()">
          <i class="fa-solid fa-right-from-bracket"></i> Thoát
        </button>
      </div>
    </header>

    <!-- KV WARNING BOX IF NOT BOUND -->
    <div id="kvWarningBox" style="display:none;background:rgba(218,54,51,0.15);border:1px solid #da3633;color:#f85149;padding:14px 18px;border-radius:8px;margin-bottom:20px;font-size:13px;line-height:1.6;">
      <strong style="font-size:14px;"><i class="fa-solid fa-triangle-exclamation"></i> CHƯA CẤU HÌNH KV NAMESPACE BINDING!</strong><br>
      Cloudflare Worker chưa được liên kết với KV Namespace nên dữ liệu chỉ lưu tạm trong RAM. Để lưu vĩnh viễn vào KV:<br>
      1. Trên Cloudflare dashboard, vào Worker của bạn -&gt; chọn tab <b>Settings</b> -&gt; <b>Variables and Secrets</b> (hoặc <b>Bindings</b>).<br>
      2. Tại mục <b>KV Namespace Bindings</b> -&gt; Bấm <b>Add binding</b> với Variable name là <b>VCAM_KV</b>.<br>
      3. Chọn <b>KV namespace</b> của bạn -&gt; Bấm <b>Save and Deploy</b>.
    </div>

    <!-- 5 STATS CARDS -->
    <div class="stats-row">
      <!-- Card 1 -->
      <div class="stat-card">
        <div class="stat-title">TỔNG SỐ KEY ĐÃ TẠO</div>
        <div class="stat-val" id="statTotalKeys">0</div>
        <div class="stat-sub">Tất cả các gói</div>
      </div>
      <!-- Card 2 -->
      <div class="stat-card">
        <div class="stat-title">ĐANG HOẠT ĐỘNG</div>
        <div class="stat-val stat-val-green" id="statActiveKeys">0</div>
        <div class="stat-sub">Thiết bị đang chạy</div>
      </div>
      <!-- Card 3 -->
      <div class="stat-card">
        <div class="stat-title">
          <span>TỔNG DOANH THU (VNĐ)</span>
          <span class="badge-vnd">Đã thu</span>
        </div>
        <div class="stat-val stat-val-gold" id="statTotalRevenue">0 đ</div>
        <div class="stat-sub">Cộng dồn từ các key</div>
      </div>
      <!-- Card 4 -->
      <div class="stat-card">
        <div class="stat-title">GÓI TEST THỬ 60P</div>
        <div class="stat-val stat-val-cyan" id="statTestKeys">0</div>
        <div class="stat-sub">Key dùng thử nghiệm</div>
      </div>
      <!-- Card 5 -->
      <div class="stat-card">
        <div class="stat-title">ĐÃ KHÓA / HẾT HẠN</div>
        <div class="stat-val stat-val-red" id="statExpiredKeys">0</div>
        <div class="stat-sub">Bị thu hồi hoặc quá hạn</div>
      </div>
    </div>

    <!-- PANEL: TẠO KEY BẢN QUYỀN & LƯU HỒ SƠ KHÁCH HÀNG -->
    <div class="panel">
      <div class="panel-header">
        <div class="panel-title-cyan">
          <i class="fa-solid fa-square-plus"></i> TẠO KEY BẢN QUYỀN &amp; LƯU HỒ SƠ KHÁCH HÀNG
        </div>
        <div class="panel-sub-right">1 Key chỉ kích hoạt được trên 1 thiết bị duy nhất</div>
      </div>

      <!-- CHỌN THỜI HẠN BẢN QUYỀN (THẺ CARD NHƯ HÌNH) -->
      <div class="duration-block">
        <div class="duration-label">
          <i class="fa-solid fa-clock-rotate-left" style="color: #00d2ff;"></i> Thời hạn bản quyền:
        </div>
        <div class="duration-selector" id="durationSelector">
          <button type="button" class="duration-card" data-val="1_hours" data-price="0" data-label="1 Giờ" onclick="selectDurationCard(this, '1_hours', '1 Giờ', 0)">
            <span class="dur-name">1 Giờ (Test)</span>
            <span class="dur-price">0 đ</span>
          </button>
          <button type="button" class="duration-card" data-val="1_days" data-price="50000" data-label="1 Ngày" onclick="selectDurationCard(this, '1_days', '1 Ngày', 50000)">
            <span class="dur-name">1 Ngày</span>
            <span class="dur-price">50.000 đ</span>
          </button>
          <button type="button" class="duration-card" data-val="7_days" data-price="180000" data-label="7 Ngày" onclick="selectDurationCard(this, '7_days', '7 Ngày', 180000)">
            <span class="dur-name">7 Ngày</span>
            <span class="dur-price">180.000 đ</span>
          </button>
          <button type="button" class="duration-card active" data-val="30_days" data-price="500000" data-label="30 Ngày" onclick="selectDurationCard(this, '30_days', '30 Ngày', 500000)">
            <span class="dur-name">30 Ngày</span>
            <span class="dur-price">500.000 đ</span>
          </button>
          <button type="button" class="duration-card" data-val="90_days" data-price="1200000" data-label="90 Ngày" onclick="selectDurationCard(this, '90_days', '90 Ngày', 1200000)">
            <span class="dur-name">90 Ngày</span>
            <span class="dur-price">1.200.000 đ</span>
          </button>
          <button type="button" class="duration-card" data-val="180_days" data-price="2100000" data-label="180 Ngày" onclick="selectDurationCard(this, '180_days', '180 Ngày', 2100000)">
            <span class="dur-name">180 Ngày</span>
            <span class="dur-price">2.100.000 đ</span>
          </button>
          <button type="button" class="duration-card" data-val="365_days" data-price="3600000" data-label="365 Ngày" onclick="selectDurationCard(this, '365_days', '365 Ngày', 3600000)">
            <span class="dur-name">365 Ngày</span>
            <span class="dur-price">3.600.000 đ</span>
          </button>
          <button type="button" class="duration-card" data-val="lifetime" data-price="6000000" data-label="Vĩnh Viễn" onclick="selectDurationCard(this, 'lifetime', 'Vĩnh Viễn', 6000000)">
            <span class="dur-name">Vĩnh Viễn</span>
            <span class="dur-price">6.000.000 đ</span>
          </button>
        </div>
        <input type="hidden" id="selectedDurationVal" value="30_days">
        <input type="hidden" id="selectedDurationLabel" value="30 Ngày">
      </div>

      <!-- ROW 1 -->
      <div class="form-row">
        <div class="form-group" style="flex:1;min-width:180px;">
          <label>
            <span>TIỀN TỐ KEY (PREFIX)</span>
            <span class="char-count" id="prefixLen">7/7</span>
          </label>
          <input type="text" id="inpPrefix" maxlength="7" value="VITAMIN" placeholder="VITAMIN" oninput="updatePrefixCount(this)">
          <div class="field-hint">Tối đa 7 ký tự chữ hoặc số. Định dạng: TIỀN TỐ-XXXXXXX</div>
        </div>

        <div class="form-group" style="flex:1;min-width:180px;">
          <label>
            <span>SỐ TIỀN THANH TOÁN (VNĐ)</span>
            <span class="badge-free-edit">● Tự do chỉnh</span>
          </label>
          <input type="text" id="inpPrice" value="500.000 đ" oninput="formatPriceInput(this)">
          <div class="field-hint">Tự động điền theo gói chọn hoặc nhập tự do số tiền</div>
        </div>

        <div class="form-group" style="flex:1.2;min-width:180px;">
          <label><i class="fa-solid fa-user"></i> TÊN NGƯỜI MUA</label>
          <input type="text" id="inpBuyer" placeholder="VD: Nguyễn Văn A">
          <div class="field-hint">Họ tên hoặc biệt danh người nhận key</div>
        </div>
      </div>

      <!-- ROW 2 -->
      <div class="form-row" style="align-items:flex-end;">
        <div class="form-group" style="flex:1;min-width:140px;">
          <label>NỀN TẢNG MXH</label>
          <select id="selSocial">
            <option value="Telegram">✈ Telegram</option>
            <option value="Zalo">💬 Zalo</option>
            <option value="Facebook">🌐 Facebook</option>
            <option value="Phone">📞 Số điện thoại</option>
            <option value="Khác">Khác</option>
          </select>
        </div>

        <div class="form-group" style="flex:1.2;min-width:170px;">
          <label>TÀI KHOẢN / LINK MXH</label>
          <input type="text" id="inpSocialAcc" placeholder="VD: @username hoặc link...">
        </div>

        <div class="form-group" style="flex:1.5;min-width:180px;">
          <label>GHI CHÚ PHỤ</label>
          <input type="text" id="inpNote" placeholder="VD: Khách quen, giảm giá 10%...">
        </div>

        <div style="margin-bottom:0;">
          <button type="button" class="btn-create-key" id="btnSubmitKey" onclick="handleCreateKey()">
            <i class="fa-solid fa-key"></i> 🔑 TẠO &amp; LƯU KEY
          </button>
        </div>
      </div>

      <!-- POPUP / BOX KEY VỪA TẠO -->
      <div id="createdKeyBox" class="key-alert-box">
        <div>
          <div style="font-size:12px;color:#10b981;font-weight:700;margin-bottom:4px;">
            <i class="fa-solid fa-circle-check"></i> TẠO KEY THÀNH CÔNG!
          </div>
          <div class="key-alert-code" id="createdKeyCode">VITAMIN-XXXXXXX</div>
          <div style="font-size:12px;color:#94a3b8;margin-top:4px;" id="createdKeyInfo"></div>
        </div>
        <button class="btn-top" onclick="copyCreatedKey()" style="background:#00d2ff;color:#021a24;font-weight:700;border:none;">
          <i class="fa-regular fa-copy"></i> Sao chép Key
        </button>
      </div>
    </div>

    <!-- PANEL: DANH SÁCH KEY & HỒ SƠ KHÁCH HÀNG -->
    <div class="panel">
      <div class="table-top">
        <div class="table-title">
          <i class="fa-solid fa-list-check" style="color:#00d2ff;"></i> DANH SÁCH KEY, HỒ SƠ KHÁCH HÀNG &amp; THIẾT BỊ
          <span class="badge-count" id="keyCountBadge">(0 key)</span>
        </div>
        <div style="display:flex;gap:10px;align-items:center;flex-wrap:wrap;">
          <div class="search-wrap">
            <i class="fa-solid fa-magnifying-glass"></i>
            <input type="text" id="searchKey" placeholder="🔍 Tìm theo Key, Tên, MXH, Thiết bị..." oninput="filterKeys()">
          </div>
          <select id="filterStatus" class="filter-select" onchange="filterKeys()">
            <option value="ALL">Tất cả trạng thái</option>
            <option value="ACTIVE">Đang hoạt động</option>
            <option value="UNUSED">Chưa kích hoạt</option>
            <option value="EXPIRED">Hết hạn</option>
            <option value="REVOKED">Đã khóa</option>
          </select>
        </div>
      </div>

      <div class="table-scroll">
        <table class="pro-table">
          <thead>
            <tr>
              <th>MÃ KEY BẢN QUYỀN</th>
              <th>NGƯỜI MUA &amp; MẠNG XÃ HỘI</th>
              <th>GÓI &amp; THỜI HẠN</th>
              <th>SỐ TIỀN</th>
              <th>THIẾT BỊ ĐANG GẮN (1 KEY = 1 MÁY)</th>
              <th>ĐỊA CHỈ IP &amp; VỊ TRÍ</th>
              <th>TRẠNG THÁI</th>
              <th>HÀNH ĐỘNG KHẨN CẤP</th>
            </tr>
          </thead>
          <tbody id="keyTableBody">
            <tr>
              <td colspan="8" style="text-align:center;padding:36px;color:#64748b;">
                <i class="fa-solid fa-spinner fa-spin"></i> Đang tải dữ liệu...
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>

  <script>
    var g_keys = [];
    var g_devices = [];

    function escapeHtml(text) {
      if (!text) return '';
      return String(text)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#039;');
    }

    function formatDate(sec) {
      if (!sec) return 'N/A';
      var d = new Date(sec * 1000);
      var pad = function(n) { return (n < 10 ? '0' + n : n); };
      return pad(d.getDate()) + '/' + pad(d.getMonth() + 1) + '/' + d.getFullYear() + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
    }

    function formatPrice(val) {
      if (val === undefined || val === null || val === '') return '0 đ';
      var num = parseInt(String(val).replace(/[^0-9]/g, '')) || 0;
      return num.toLocaleString('vi-VN') + ' đ';
    }

    function formatPriceInput(el) {
      var num = el.value.replace(/[^0-9]/g, '');
      if (!num) { el.value = '0 đ'; return; }
      el.value = Number(num).toLocaleString('vi-VN') + ' đ';
    }

    function updatePrefixCount(el) {
      el.value = el.value.toUpperCase().replace(/[^A-Z0-9]/g, '');
      var counter = document.getElementById('prefixLen');
      if (counter) counter.innerText = el.value.length + '/7';
    }

    function selectDurationCard(btn, val, label, price) {
      if (!btn) return;
      var card = (btn.closest && btn.closest('.duration-card')) ? btn.closest('.duration-card') : btn;
      if (!card) return;

      var cards = document.querySelectorAll('.duration-card');
      for (var i = 0; i < cards.length; i++) {
        cards[i].classList.remove('active');
      }
      card.classList.add('active');

      if (!val) val = card.getAttribute('data-val');
      if (!label) label = card.getAttribute('data-label');
      if (price === undefined || price === null || price === '') price = card.getAttribute('data-price');

      var valInput = document.getElementById('selectedDurationVal');
      var labelInput = document.getElementById('selectedDurationLabel');
      if (valInput && val) valInput.value = val;
      if (labelInput && label) labelInput.value = label;

      var priceInput = document.getElementById('inpPrice');
      if (priceInput && price !== null && price !== undefined && price !== '') {
        var pNum = Number(price);
        priceInput.value = pNum.toLocaleString('vi-VN') + ' đ';
      }
    }
    window.selectDurationCard = selectDurationCard;

    function initDurationCards() {
      var container = document.getElementById('durationSelector');
      if (!container) return;
      container.addEventListener('click', function(e) {
        var btn = (e.target && e.target.closest) ? e.target.closest('.duration-card') : null;
        if (!btn) {
          var p = e.target;
          while (p && p !== container) {
            if (p.classList && p.classList.contains('duration-card')) { btn = p; break; }
            p = p.parentNode;
          }
        }
        if (btn) {
          selectDurationCard(btn);
        }
      });
    }

    function socialIcon(platform) {
      if (!platform) return '<i class="fa-solid fa-user"></i>';
      var p = platform.toLowerCase();
      if (p.indexOf('tele') !== -1) return '<i class="fa-brands fa-telegram" style="color:#229ED9;"></i>';
      if (p.indexOf('zalo') !== -1) return '<i class="fa-solid fa-comment-dots" style="color:#0068FF;"></i>';
      if (p.indexOf('face') !== -1) return '<i class="fa-brands fa-facebook" style="color:#1877F2;"></i>';
      if (p.indexOf('phone') !== -1 || p.indexOf('thoại') !== -1 || p.indexOf('sđt') !== -1) return '<i class="fa-solid fa-phone" style="color:#10b981;"></i>';
      return '<i class="fa-solid fa-globe" style="color:#94a3b8;"></i>';
    }

    function copyText(str) {
      if (!str) return;
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(str).then(function() {
          showToast('Đã sao chép: ' + str);
        }).catch(function() {
          prompt('Sao chép mã bên dưới:', str);
        });
      } else {
        prompt('Sao chép mã bên dưới:', str);
      }
    }

    function copyCreatedKey() {
      var code = document.getElementById('createdKeyCode').innerText;
      copyText(code);
    }

    function showToast(msg) {
      var toast = document.getElementById('vcamToast');
      if (!toast) {
        toast = document.createElement('div');
        toast.id = 'vcamToast';
        toast.style.position = 'fixed';
        toast.style.bottom = '24px';
        toast.style.right = '24px';
        toast.style.background = '#00d2ff';
        toast.style.color = '#021a24';
        toast.style.padding = '10px 18px';
        toast.style.borderRadius = '6px';
        toast.style.fontWeight = 'bold';
        toast.style.boxShadow = '0 4px 15px rgba(0,0,0,0.5)';
        toast.style.zIndex = '9999';
        toast.style.transition = 'opacity 0.3s';
        document.body.appendChild(toast);
      }
      toast.innerText = msg;
      toast.style.opacity = '1';
      toast.style.display = 'block';
      setTimeout(function() {
        toast.style.opacity = '0';
        setTimeout(function() { toast.style.display = 'none'; }, 300);
      }, 2000);
    }

    async function handleCreateKey() {
      var prefix = (document.getElementById('inpPrefix').value || 'VITAMIN').trim();
      var valInput = document.getElementById('selectedDurationVal');
      var labelInput = document.getElementById('selectedDurationLabel');
      var durationValRaw = valInput ? valInput.value : '30_days';
      var durationLabel = labelInput ? labelInput.value : '30 Ngày';

      var duration_type = 'days';
      var duration_val = 30;
      if (durationValRaw === 'lifetime') {
        duration_type = 'lifetime';
        duration_val = 0;
      } else if (durationValRaw.indexOf('_hours') !== -1) {
        duration_type = 'hours';
        duration_val = parseInt(durationValRaw);
      } else if (durationValRaw.indexOf('_days') !== -1) {
        duration_type = 'days';
        duration_val = parseInt(durationValRaw);
      }

      var price = document.getElementById('inpPrice').value.trim();
      var buyer_name = document.getElementById('inpBuyer').value.trim();
      var social_platform = document.getElementById('selSocial').value;
      var social_account = document.getElementById('inpSocialAcc').value.trim();
      var note = document.getElementById('inpNote').value.trim();

      var btn = document.getElementById('btnSubmitKey');
      if (btn) {
        btn.disabled = true;
        btn.innerHTML = '<i class="fa-solid fa-spinner fa-spin"></i> Đang tạo...';
      }

      try {
        var res = await fetch('/api/admin/create-key', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            prefix: prefix,
            duration_type: duration_type,
            duration_val: duration_val,
            duration_label: durationLabel,
            price: price,
            buyer_name: buyer_name,
            social_platform: social_platform,
            social_account: social_account,
            note: note
          })
        });
        var data = await res.json();
        if (data.success && data.key) {
          var box = document.getElementById('createdKeyBox');
          var codeEl = document.getElementById('createdKeyCode');
          var infoEl = document.getElementById('createdKeyInfo');
          if (codeEl) codeEl.innerText = data.key.key;
          if (infoEl) {
            infoEl.innerText = (data.key.buyer_name || 'Khách vãng lai') + ' | ' + (data.key.duration_label || '') + ' | ' + (data.key.price || '0 đ');
          }
          if (box) box.style.display = 'flex';

          document.getElementById('inpBuyer').value = '';
          document.getElementById('inpSocialAcc').value = '';
          document.getElementById('inpNote').value = '';

          refreshData();
        } else {
          alert('Lỗi tạo key: ' + (data.error || 'Không xác định'));
        }
      } catch (e) {
        alert('Lỗi kết nối Server: ' + e.message);
      } finally {
        if (btn) {
          btn.disabled = false;
          btn.innerHTML = '<i class="fa-solid fa-key"></i> 🔑 TẠO &amp; LƯU KEY';
        }
      }
    }

    async function doKeyAction(key, action) {
      var confirmMsg = '';
      if (action === 'revoke') confirmMsg = 'Bạn có chắc chắn muốn KHÓA key ' + key + '?' + String.fromCharCode(10) + 'Khách hàng sẽ bị ngắt bản quyền ngay lập tức!';
      if (action === 'restore') confirmMsg = 'Mở khóa lại cho key ' + key + '?';
      if (action === 'reset-device') confirmMsg = 'Bạn có chắc muốn RESET THIẾT BỊ cho key ' + key + '?' + String.fromCharCode(10) + 'Thiết bị cũ sẽ bị hủy liên kết, khách hàng có thể kích hoạt key trên điện thoại mới!';
      if (action === 'delete') confirmMsg = 'CẢNH BÁO: Xóa vĩnh viễn key ' + key + ' khỏi hệ thống?';

      if (!confirm(confirmMsg)) return;

      try {
        var res = await fetch('/api/admin/key-action', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ key: key, action: action })
        });
        var data = await res.json();
        if (data.success) {
          alert(data.message || 'Thao tác thành công!');
          refreshData();
        } else {
          alert('Lỗi: ' + (data.error || 'Không thực hiện được'));
        }
      } catch (e) {
        alert('Lỗi kết nối: ' + e.message);
      }
    }

    async function refreshData() {
      try {
        var res = await fetch('/api/admin/devices');
        if (res.status === 401) {
          window.location.reload();
          return;
        }
        var data = await res.json();
        if (!data.success) {
          alert('Lỗi lấy dữ liệu: ' + (data.error || ''));
          return;
        }

        g_keys = data.keys || [];
        g_devices = data.devices || [];

        var warnBox = document.getElementById('kvWarningBox');
        if (warnBox) {
          warnBox.style.display = data.kv_bound ? 'none' : 'block';
        }

        renderStats();
        filterKeys();
      } catch (e) {
        console.error('refreshData error:', e);
      }
    }

    function renderStats() {
      var nowSec = Math.floor(Date.now() / 1000);

      // 1. Tổng số key đã tạo
      document.getElementById('statTotalKeys').innerText = g_keys.length;

      // 2. Đang hoạt động
      var activeCount = g_keys.filter(function(k) {
        if (k.status !== 'ACTIVE') return false;
        if (!k.bound_serial) return false;
        if (k.expires_at && k.expires_at > 0 && k.expires_at <= nowSec) return false;
        return true;
      }).length;
      document.getElementById('statActiveKeys').innerText = activeCount;

      // 3. Tổng doanh thu
      var totalRevenue = 0;
      g_keys.forEach(function(k) {
        if (k.price) {
          var num = parseInt(String(k.price).replace(/[^0-9]/g, '')) || 0;
          totalRevenue += num;
        }
      });
      document.getElementById('statTotalRevenue').innerText = totalRevenue.toLocaleString('vi-VN') + ' đ';

      // 4. Gói test thử 60p
      var testCount = g_keys.filter(function(k) {
        if (k.duration_sec === 3600) return true;
        if (k.duration_label) {
          var lbl = k.duration_label.toLowerCase();
          if (lbl.indexOf('1 giờ') !== -1 || lbl.indexOf('60p') !== -1 || lbl.indexOf('test') !== -1) return true;
        }
        return false;
      }).length;
      document.getElementById('statTestKeys').innerText = testCount;

      // 5. Đã khóa / Hết hạn
      var expiredOrRevoked = g_keys.filter(function(k) {
        if (k.status === 'REVOKED') return true;
        if (k.expires_at && k.expires_at > 0 && k.expires_at <= nowSec) return true;
        return false;
      }).length;
      document.getElementById('statExpiredKeys').innerText = expiredOrRevoked;
    }

    function filterKeys() {
      var q = (document.getElementById('searchKey').value || '').trim().toLowerCase();
      var stFilter = document.getElementById('filterStatus').value;
      var nowSec = Math.floor(Date.now() / 1000);

      var filtered = g_keys.filter(function(k) {
        var isRevoked = k.status === 'REVOKED';
        var isExp = k.expires_at && k.expires_at > 0 && k.expires_at <= nowSec;
        var isAct = k.status === 'ACTIVE' && k.bound_serial && !isExp;
        var isUnused = !k.bound_serial && !isRevoked;

        if (stFilter === 'ACTIVE' && !isAct) return false;
        if (stFilter === 'UNUSED' && !isUnused) return false;
        if (stFilter === 'EXPIRED' && !isExp) return false;
        if (stFilter === 'REVOKED' && !isRevoked) return false;

        if (q) {
          var matchKey = (k.key || '').toLowerCase().indexOf(q) !== -1;
          var matchBuyer = (k.buyer_name || '').toLowerCase().indexOf(q) !== -1;
          var matchMxh = (k.social_account || '').toLowerCase().indexOf(q) !== -1;
          var matchSerial = (k.bound_serial || '').toLowerCase().indexOf(q) !== -1;
          var matchModel = (k.device_model || '').toLowerCase().indexOf(q) !== -1;
          var matchIp = (k.last_ip || '').toLowerCase().indexOf(q) !== -1;
          var matchNote = (k.note || '').toLowerCase().indexOf(q) !== -1;
          if (!matchKey && !matchBuyer && !matchMxh && !matchSerial && !matchModel && !matchIp && !matchNote) {
            return false;
          }
        }
        return true;
      });

      renderTable(filtered);
    }

    function renderTable(list) {
      document.getElementById('keyCountBadge').innerText = '(' + list.length + ' key)';
      var tbody = document.getElementById('keyTableBody');
      if (!tbody) return;

      if (list.length === 0) {
        tbody.innerHTML = '<tr><td colspan="8" style="text-align:center;padding:36px;color:#64748b;">Chưa có dữ liệu phù hợp</td></tr>';
        return;
      }

      var nowSec = Math.floor(Date.now() / 1000);
      var html = '';

      for (var i = 0; i < list.length; i++) {
        var k = list[i];
        var dev = g_devices.find(function(d) { return d.serial === k.bound_serial; });

        // Col 1: Mã key
        var colKey = '<div class="key-strong" onclick="copyText(&quot;' + k.key + '&quot;)" title="Bấm để copy">' +
          k.key + ' <i class="fa-regular fa-copy" style="font-size:11px;color:#64748b;margin-left:4px;"></i></div>' +
          '<div style="font-size:11px;color:#526079;margin-top:4px;"><i class="fa-regular fa-clock"></i> Tạo: ' + formatDate(k.created_at) + '</div>';

        // Col 2: Người mua & MXH
        var colBuyer = '<div style="font-weight:700;color:#f1f5f9;">' + escapeHtml(k.buyer_name || 'Khách vãng lai') + '</div>' +
          '<div class="badge-mxh">' + socialIcon(k.social_platform) + ' ' + escapeHtml(k.social_account || 'Chưa lưu') + '</div>' +
          (k.note ? '<div style="font-size:11px;color:#94a3b8;margin-top:3px;font-style:italic;">📝 ' + escapeHtml(k.note) + '</div>' : '');

        // Col 3: Gói & Thời hạn
        var expiryHtml = '';
        if (k.duration_sec === 0 || k.duration_label === 'Vĩnh viễn') {
          expiryHtml = '<div style="font-size:11px;color:#38bdf8;margin-top:4px;">✨ Vĩnh viễn</div>';
        } else if (!k.activated_at) {
          expiryHtml = '<div style="font-size:11px;color:#64748b;margin-top:4px;">⏳ Chưa kích hoạt</div>';
        } else if (k.expires_at && k.expires_at <= nowSec) {
          expiryHtml = '<div style="font-size:11px;color:#ef4444;margin-top:4px;">✕ Hết hạn: ' + formatDate(k.expires_at) + '</div>';
        } else {
          expiryHtml = '<div style="font-size:11px;color:#10b981;margin-top:4px;">✓ Hết: ' + formatDate(k.expires_at) + '</div>';
        }
        var colPkg = '<span class="pkg-badge">' + escapeHtml(k.duration_label || 'Tùy chỉnh') + '</span>' + expiryHtml;

        // Col 4: Số tiền
        var colPrice = '<span style="font-weight:700;color:#fbbf24;font-size:13px;">' + formatPrice(k.price) + '</span>';

        // Col 5: Thiết bị đang gắn
        var colDevice = '';
        if (k.bound_serial) {
          var model = k.device_model || (dev ? dev.model : null) || 'Android Device';
          colDevice = '<div style="font-weight:600;color:#e2e8f0;display:flex;align-items:center;gap:5px;">' +
            '<i class="fa-solid fa-mobile-screen" style="color:#00d2ff;"></i> ' + escapeHtml(model) + '</div>' +
            '<div style="font-family:monospace;font-size:11px;color:#64748b;margin-top:3px;">SN: ' +
            escapeHtml(k.bound_serial.substring(0, 16) + (k.bound_serial.length > 16 ? '...' : '')) + '</div>';
        } else {
          colDevice = '<div style="color:#64748b;font-style:italic;">(Chưa gắn máy nào)</div>';
        }

        // Col 6: Địa chỉ IP & Vị trí
        var colIp = '';
        var lastIp = k.last_ip || (dev ? dev.last_ip : null);
        var location = k.location || (dev ? dev.location : null);
        if (lastIp) {
          colIp = '<div><i class="fa-solid fa-globe" style="color:#38bdf8;font-size:11px;"></i> ' + escapeHtml(lastIp) + '</div>' +
            '<div style="font-size:11px;color:#94a3b8;margin-top:3px;"><i class="fa-solid fa-location-dot" style="color:#f87171;font-size:10px;"></i> ' + escapeHtml(location || 'N/A') + '</div>';
        } else {
          colIp = '<div style="color:#64748b;font-size:11px;">Chưa có dữ liệu</div>';
        }

        // Col 7: Trạng thái
        var colStatus = '';
        if (k.status === 'REVOKED') {
          colStatus = '<span class="status-pill status-banned"><span class="dot-red"></span> Đã khóa</span>';
        } else if (k.expires_at && k.expires_at > 0 && k.expires_at <= nowSec) {
          colStatus = '<span class="status-pill status-expired"><i class="fa-solid fa-triangle-exclamation" style="font-size:10px;"></i> Hết hạn</span>';
        } else if (k.bound_serial) {
          colStatus = '<span class="status-pill status-active"><span class="dot-green"></span> Đang hoạt động</span>';
        } else {
          colStatus = '<span class="status-pill status-unused"><i class="fa-regular fa-circle" style="font-size:10px;"></i> Chưa kích hoạt</span>';
        }

        // Col 8: Hành động
        var colAction = '<div style="display:flex;gap:6px;align-items:center;">';
        if (k.status === 'REVOKED') {
          colAction += '<button class="btn-tbl-action btn-tbl-reset" onclick="doKeyAction(&quot;' + k.key + '&quot;, &quot;restore&quot;)"><i class="fa-solid fa-unlock"></i> Mở Khóa</button>';
        } else {
          colAction += '<button class="btn-tbl-action btn-tbl-ban" onclick="doKeyAction(&quot;' + k.key + '&quot;, &quot;revoke&quot;)"><i class="fa-solid fa-ban"></i> Khóa Key</button>';
        }
        if (k.bound_serial) {
          colAction += '<button class="btn-tbl-action btn-tbl-reset" onclick="doKeyAction(&quot;' + k.key + '&quot;, &quot;reset-device&quot;)" title="Gỡ máy hiện tại để khách đổi điện thoại"><i class="fa-solid fa-rotate-left"></i> Reset Máy</button>';
        }
        colAction += '<button class="btn-tbl-action btn-tbl-del" onclick="doKeyAction(&quot;' + k.key + '&quot;, &quot;delete&quot;)" title="Xóa vĩnh viễn"><i class="fa-solid fa-trash-can"></i></button>';
        colAction += '</div>';

        html += '<tr>' +
          '<td>' + colKey + '</td>' +
          '<td>' + colBuyer + '</td>' +
          '<td>' + colPkg + '</td>' +
          '<td>' + colPrice + '</td>' +
          '<td>' + colDevice + '</td>' +
          '<td>' + colIp + '</td>' +
          '<td>' + colStatus + '</td>' +
          '<td>' + colAction + '</td>' +
          '</tr>';
      }

      tbody.innerHTML = html;
    }

    async function logoutAdmin() {
      if (confirm('Bạn có chắc muốn đăng xuất khỏi trang quản trị?')) {
        await fetch('/api/admin/logout');
        window.location.reload();
      }
    }

    window.onload = function() {
      initDurationCards();
      refreshData();
    };
    initDurationCards();
  </script>
</body>
</html>`;
}

function getLoginHtml(secret2fa, isConfigured) {
  const currentSecret = secret2fa || "";
  const qrUrl = currentSecret 
    ? "https://api.qrserver.com/v1/create-qr-code/?size=200x200&data=" + encodeURIComponent("otpauth://totp/VCAM Server:admin?secret=" + currentSecret + "&issuer=VCAM Manager")
    : "";
  return `<!DOCTYPE html>
<html lang="vi">
<head>
  <meta charset="utf-8">
  <title>Đăng Nhập Quản Trị - VCAM Server</title>
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.4.0/css/all.min.css">
  <style>
    :root {
      --bg: #0d1117;
      --card: #161b22;
      --border: #30363d;
      --accent: #58a6ff;
      --green: #238636;
      --red: #da3633;
      --text: #c9d1d9;
      --text-white: #f0f6fc;
    }
    * { box-sizing: border-box; }
    body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background: var(--bg); color: var(--text); margin: 0; min-height: 100vh; display: flex; align-items: center; justify-content: center; padding: 20px; }
    .login-box { background: var(--card); border: 1px solid var(--border); border-radius: 12px; max-width: 460px; width: 100%; padding: 32px; box-shadow: 0 10px 30px rgba(0,0,0,0.5); }
    .logo-header { text-align: center; margin-bottom: 24px; }
    .logo-icon { width: 56px; height: 56px; background: rgba(88,166,255,0.15); color: var(--accent); border-radius: 50%; display: inline-flex; align-items: center; justify-content: center; font-size: 26px; margin-bottom: 12px; }
    .title { font-size: 20px; font-weight: bold; color: var(--text-white); margin: 0; }
    .subtitle { font-size: 13px; color: #8b949e; margin-top: 6px; }
    
    label { font-size: 13px; font-weight: 600; color: #8b949e; display: block; margin-top: 14px; margin-bottom: 6px; }
    .input-wrap { position: relative; }
    .input-wrap i { position: absolute; left: 12px; top: 12px; color: #8b949e; }
    input { width: 100%; padding: 10px 12px 10px 36px; border-radius: 6px; border: 1px solid var(--border); background: var(--bg); color: #fff; font-size: 14px; }
    input:focus { border-color: var(--accent); outline: none; }
    input.otp-input { font-family: monospace; font-size: 22px; letter-spacing: 6px; text-align: center; padding-left: 12px; }

    button.btn-login { width: 100%; padding: 12px; background: var(--green); color: #fff; border: none; border-radius: 6px; font-weight: bold; cursor: pointer; margin-top: 20px; font-size: 15px; display: flex; align-items: center; justify-content: center; gap: 8px; }
    button.btn-login:hover { opacity: 0.9; }
    button.btn-login:disabled { opacity: 0.6; cursor: not-allowed; }

    .error-msg { background: rgba(218,54,51,0.15); border: 1px solid var(--red); color: #f85149; padding: 10px 14px; border-radius: 6px; font-size: 13px; margin-top: 16px; display: none; }
    
    .setup-2fa { margin-top: 24px; padding-top: 18px; border-top: 1px solid var(--border); }
    .setup-toggle { font-size: 12px; color: var(--accent); cursor: pointer; text-align: center; display: block; text-decoration: none; }
    .setup-toggle:hover { text-decoration: underline; }
    .setup-content { display: none; margin-top: 14px; background: #040d21; border: 1px dashed #1f6feb; border-radius: 8px; padding: 16px; text-align: center; }
    .qr-img { width: 180px; height: 180px; border-radius: 8px; background: #fff; padding: 6px; margin: 10px auto; display: block; }
    .secret-code { font-family: monospace; background: #21262d; padding: 6px 10px; border-radius: 4px; color: #3fb950; font-size: 13px; word-break: break-all; margin: 8px 0; display: inline-block; user-select: all; }
  </style>
</head>
<body>
  <div class="login-box">
    <div class="logo-header">
      <div class="logo-icon"><i class="fa-solid fa-shield-halved"></i></div>
      <h1 class="title">VCAM SERVER ADMIN</h1>
      <p class="subtitle">Bảo mật xác thực 2 lớp (2FA)</p>
    </div>

    ${!isConfigured ? `
    <div style="background: rgba(218,54,51,0.15); border: 1px solid #da3633; color: #f85149; padding: 14px; border-radius: 8px; font-size: 13px; line-height: 1.6; margin-bottom: 20px;">
      <strong style="font-size: 14px;"><i class="fa-solid fa-triangle-exclamation"></i> CHƯA CẤU HÌNH SECRETS!</strong><br>
      Vào Cloudflare Worker <b>vandroid</b> -> <b>Settings</b> -> <b>Variables and Secrets</b> -> Thêm 3 biến:<br>
      • <code style="background: #21262d; padding: 2px 4px; border-radius: 4px; color: #58a6ff;">ADMIN_USER</code> = admin<br>
      • <code style="background: #21262d; padding: 2px 4px; border-radius: 4px; color: #58a6ff;">ADMIN_PASS</code> = Mật khẩu của bạn<br>
      • <code style="background: #21262d; padding: 2px 4px; border-radius: 4px; color: #58a6ff;">TWO_FACTOR_SECRET</code> = Khóa 2FA
    </div>` : ''}

    <form id="loginForm" onsubmit="doLogin(event)">
      <label>Tên đăng nhập:</label>
      <div class="input-wrap">
        <i class="fa-solid fa-user"></i>
        <input type="text" id="username" value="admin" required autocomplete="username">
      </div>

      <label>Mật khẩu:</label>
      <div class="input-wrap">
        <i class="fa-solid fa-lock"></i>
        <input type="password" id="password" required placeholder="Nhập mật khẩu" autocomplete="current-password">
      </div>

      <label>Mã 2FA Google Authenticator (6 số):</label>
      <div class="input-wrap">
        <i class="fa-solid fa-key"></i>
        <input type="text" id="otp" class="otp-input" maxlength="6" pattern="[0-9]{6}" inputmode="numeric" required placeholder="000000" autocomplete="one-time-code">
      </div>

      <div id="errMsg" class="error-msg"></div>

      <button type="submit" id="btnLogin" class="btn-login">
        <i class="fa-solid fa-right-to-bracket"></i> ĐĂNG NHẬP
      </button>
    </form>

    <div class="setup-2fa">
      <a class="setup-toggle" onclick="toggle2FaSetup()"><i class="fa-solid fa-qrcode"></i> Lần đầu cài đặt? Bấm xem mã quét 2FA Google Authenticator</a>
      <div id="setupContent" class="setup-content">
        <div style="font-size: 12px; color: #8b949e; line-height: 1.5;">
          Mở ứng dụng <b>Google Authenticator</b> trên điện thoại -> Chọn <b>Quét mã QR</b>:
        </div>
        <img class="qr-img" src="${qrUrl}" alt="2FA QR">
        <div style="font-size: 11px; color: #8b949e; margin-top: 8px;">Hoặc nhập mã khóa thiết lập thủ công:</div>
        <div class="secret-code">${currentSecret}</div>
      </div>
    </div>
  </div>

  <script>
    function toggle2FaSetup() {
      var el = document.getElementById('setupContent');
      el.style.display = (el.style.display === 'block') ? 'none' : 'block';
    }

    async function doLogin(e) {
      e.preventDefault();
      var btn = document.getElementById('btnLogin');
      var err = document.getElementById('errMsg');
      err.style.display = 'none';

      var username = document.getElementById('username').value.trim();
      var password = document.getElementById('password').value;
      var otp = document.getElementById('otp').value.trim();

      btn.disabled = true;
      btn.innerHTML = '<i class="fa-solid fa-spinner fa-spin"></i> Đang xác thực...';

      try {
        var res = await fetch('/api/admin/login', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ username: username, password: password, otp: otp })
        });
        var data = await res.json();
        if (data.success) {
          window.location.reload();
        } else {
          err.innerText = data.message || 'Sai thông tin đăng nhập!';
          err.style.display = 'block';
        }
      } catch (ex) {
        err.innerText = 'Lỗi kết nối: ' + ex.message;
        err.style.display = 'block';
      } finally {
        btn.disabled = false;
        btn.innerHTML = '<i class="fa-solid fa-right-to-bracket"></i> ĐĂNG NHẬP';
      }
    }
  </script>
</body>
</html>`;
}
