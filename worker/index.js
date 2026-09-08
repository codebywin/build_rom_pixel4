// ===================================================================================
// CLOUDFLARE WORKER: VCAM ADVANCED DEVICE & LICENSE MANAGEMENT SERVER PRO
// Target Domain: https://api.0x0134w.workers.dev
// Author: LineageOS Pixel 4 (flame) VCAM Team
// ===================================================================================

const RSA_PRIVATE_KEY_PEM = "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQDEW+Zjifujwy4a\nRxGJl/zvpYpy5wepRwLN2nZBHjjIKJkN4mxd9aCpAj4YtrRWNyP8bF9dlMEKdcG8\nxWFNWulXM2qzcT/cN7vKFBsbAPOKqRz7BnQ0x/+gWvjoAsOE4uL1YwkQ57lw5YYX\n3pf/4fzlnSuqZtUUoZCI+nAZNyzqPq1v0xGQJ8N1rv9/3qV0G6MLDWgg0XrJ4UnY\nRJK54enQpxNnDocB+jSN965LJq03rQw2ERgmeSIH9dFPCceHhTaBLnnDKVQ/K3CT\nwiLPV6+E+poeAV6ib8rnkyTL6SpJU0TIWdCrdmYPlGjXNWDEukKPB7QZlVMPjv9j\nm8aIyQ1rAgMBAAECggEABtoqJQkYpfNWtYYLX6DVK8u8FBxp0QdwWpyoCcezNZDt\nHmXrYDAFJkC0yAoAKw4LjHB/t3VMcz/+vcapiZiFkgxyScbG8rljLT8cXwneddVG\n9J+aCIl+Kythij8mcYm1X9jP5S4g84ae8lBLP5u0RpMAhhbGksy8jXsn1ElvoND1\nduKVkShtQPKxABu54RJGQp9AkHaRaJGivg1sbxtFTj/QHWP45WF1Lf/J3woVQ6MC\nXu4KvEFYDeOKBr0/T+PU8e/A0cWp/OAduIJ4mGqmV3mtcQdxVduQkpFFZGYx0FSB\nn0yKiuUuxb4mEXJoAX+mEev56vqY1TEyP6ebRrkAUQKBgQDmvxDxyiaDVsLGWHuo\nDrdO5XnDLSpu0uw2N0agasfNBbCnx87v4EX4J/IQ5w6RpfZ6jFJj9ENC3UfkuOHu\nZsBU56fxCuDnM7SRKFJM+izrVUuhz9AGMnq8jOdth79E8FfckSXhUXYBzZCua2+U\nRso6YTLV2ouhn5yaYP3BOCRU4wKBgQDZ2WLQqa99iEL68HTKCiViCd71BMOa5Jg+\n+8EcOd30xemBw0hKFFgMjN4dlAIiURhKB7nQ7kF6gl8hRxj0bN7B1eSzTx6zR+YS\nR+5sn9uKv6Hh82y+RDN4Agi9da+jBapCESBAdQJU7ktjmmW0xZ2xz8yaTjQjaazV\nE2yNZMLT2QKBgBL+uZNd88uuEbyoPg24oGhzRZHGnw6eeGmCJWNBRw9en4tATI28\npaXnC+tOSgm9Ysv1zzaBPiQ7+RYgDiFE/iI/K7kRDzCZNg0ZB6VkltwMmnxIkjRg\nZXAuHUMMALfZHTKAFGE3BoLhfD6Pg5DuPumNZNTr98CnVgnzYBBO4dbHAoGAQkMK\n+WkDhe1SYj2NaH7ZjA5wkJpYXN63KEEvJcS8LF2efufFLzMs7PRUAy8nzwRXnPzo\nmhI+PGM3SEn13zLWNqM2owunzORLqLfUX5noDzXmqXF/XAgml5QW0HnhaHaqqNnI\ns5JjmS26JJur3+ZT5ufL1gt/dF4KQe1ckU1arVECgYEA3iL/BppEMkYYE8r/UEwM\n+0JOXcjG1xe8UY6htPkQaenkAFnxs+HRjB9zxCH+8ERLyFsKIUl6NbZvmqfrv7ml\nAk05POqzUXQPY04a8GJKFCwvi5j9QR96aUgU13RHFZQmhChOARPL3+GM7x85zeaf\nIBOdjoECcQMJRyjFiP+zrhY=\n-----END PRIVATE KEY-----";

const MEMORY_KEYS = {};
const MEMORY_DEVICES = {};

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

        // Tu dong nhan dien Key neu chua co trong KV
        if (!keyData) {
          if (key.startsWith("VCAM-") || key === "VCAM-VIP-2026" || key.startsWith("TEST-")) {
            keyData = { duration_seconds: 30 * 86400, duration_label: "30 Ngay", bound_serial: null, status: "ACTIVE", note: "Kich hoat tu dong" };
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
        keyData.bound_serial = serial;
        keyData.activated_at = now;
        keyData.expires_at = expiresAt;

        const devObj = {
          serial: serial,
          key: key,
          note: keyData.note || "Khach hang",
          activated_at: now,
          expires_at: expiresAt,
          duration_label: keyData.duration_label || "Standard",
          last_ip: clientIp,
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
          return jsonResponse({ success: true, message: "Da thu hoi ma key: " + key }, 200, corsHeaders);
        }

        if (action === "restore") {
          keyObj.status = "ACTIVE";
          if (kv) await kv.put("key:" + key, JSON.stringify(keyObj));
          MEMORY_KEYS[key] = keyObj;
          return jsonResponse({ success: true, message: "Da kich hoat lai ma key: " + key }, 200, corsHeaders);
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
        const { duration_type, duration_val, note } = await request.json();

        let durationSec = 0;
        let label = "Vĩnh viễn";

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
        let part1 = "", part2 = "";
        for (let i = 0; i < 4; i++) part1 += chars.charAt(Math.floor(Math.random() * chars.length));
        for (let i = 0; i < 4; i++) part2 += chars.charAt(Math.floor(Math.random() * chars.length));
        const code = "VCAM-" + part1 + "-" + part2;

        const keyObj = {
          key: code,
          duration_seconds: durationSec,
          duration_label: label,
          note: note || "Khách hàng",
          bound_serial: null,
          created_at: Math.floor(Date.now() / 1000),
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
      return new Response(getDashboardHtml(), {
        headers: {
          "Content-Type": "text/html; charset=UTF-8",
          ...corsHeaders
        }
      });
    }

    return jsonResponse({ error: "Not Found", path: path }, 404, corsHeaders);
  }
};

function getDashboardHtml() {
  return `<!DOCTYPE html>
<html lang="vi">
<head>
  <meta charset="utf-8">
  <title>VCAM Manager Pro - Pixel 4</title>
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
      --yellow: #d29922;
      --purple: #bc8cff;
      --text: #c9d1d9;
      --text-white: #f0f6fc;
    }
    * { box-sizing: border-box; }
    body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background: var(--bg); color: var(--text); margin: 0; padding: 20px; }
    .container { max-width: 1300px; margin: 0 auto; }
    
    .top-bar { display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid var(--border); padding-bottom: 15px; margin-bottom: 24px; flex-wrap: wrap; gap: 10px; }
    .brand { font-size: 22px; font-weight: bold; color: var(--text-white); display: flex; align-items: center; gap: 10px; }
    .brand i { color: var(--accent); }
    
    .stats-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 16px; margin-bottom: 24px; }
    .stat-card { background: var(--card); border: 1px solid var(--border); border-radius: 8px; padding: 18px; display: flex; align-items: center; gap: 16px; }
    .stat-icon { width: 48px; height: 48px; border-radius: 8px; display: flex; align-items: center; justify-content: center; font-size: 20px; }
    .stat-val { font-size: 24px; font-weight: bold; color: var(--text-white); }
    .stat-label { font-size: 13px; color: #8b949e; }

    .main-grid { display: grid; grid-template-columns: 360px 1fr; gap: 24px; }
    @media (max-width: 960px) { .main-grid { grid-template-columns: 1fr; } }

    .panel { background: var(--card); border: 1px solid var(--border); border-radius: 8px; padding: 20px; margin-bottom: 24px; }
    .panel-title { font-size: 16px; font-weight: 600; color: var(--text-white); margin-top: 0; margin-bottom: 16px; display: flex; justify-content: space-between; align-items: center; }

    label { font-size: 13px; font-weight: 600; color: #8b949e; display: block; margin-top: 12px; margin-bottom: 4px; }
    input, select { width: 100%; padding: 10px; border-radius: 6px; border: 1px solid var(--border); background: var(--bg); color: #fff; font-size: 14px; }
    input:focus, select:focus { border-color: var(--accent); outline: none; }
    button.btn-primary { width: 100%; padding: 12px; background: var(--green); color: #fff; border: none; border-radius: 6px; font-weight: bold; cursor: pointer; margin-top: 18px; font-size: 14px; }
    button.btn-primary:hover { opacity: 0.9; }

    .key-box { background: #042416; border: 1px dashed var(--green); padding: 14px; border-radius: 6px; margin-top: 16px; display: none; text-align: center; }
    .key-code { font-family: monospace; font-size: 22px; color: #3fb950; font-weight: bold; letter-spacing: 2px; margin: 8px 0; user-select: all; }

    table { width: 100%; border-collapse: collapse; font-size: 13px; }
    th { text-align: left; padding: 10px 12px; background: #21262d; color: #8b949e; font-weight: 600; border-bottom: 1px solid var(--border); }
    td { padding: 12px; border-bottom: 1px solid var(--border); vertical-align: middle; }
    tr:hover { background: rgba(255,255,255,0.02); }

    .badge { display: inline-block; padding: 3px 8px; border-radius: 12px; font-size: 11px; font-weight: bold; white-space: nowrap; }
    .badge-active { background: rgba(35,134,54,0.2); color: #3fb950; border: 1px solid #238636; }
    .badge-banned { background: rgba(218,54,51,0.2); color: #f85149; border: 1px solid #da3633; }
    .badge-expired { background: rgba(210,153,34,0.2); color: #d29922; border: 1px solid #d29922; }
    .badge-used { background: rgba(88,166,255,0.2); color: #58a6ff; border: 1px solid #58a6ff; }
    .badge-unused { background: rgba(35,134,54,0.2); color: #3fb950; border: 1px solid #238636; }
    .badge-revoked { background: rgba(218,54,51,0.2); color: #f85149; border: 1px solid #da3633; }

    .btn-action { padding: 6px 10px; border-radius: 4px; border: 1px solid var(--border); background: #21262d; color: var(--text-white); cursor: pointer; font-size: 12px; margin-right: 4px; white-space: nowrap; }
    .btn-action:hover { background: #30363d; }
    .btn-ban { color: #f85149; border-color: rgba(218,54,51,0.4); }
    .btn-ban:hover { background: rgba(218,54,51,0.2); }
    .btn-extend { color: var(--accent); border-color: rgba(88,166,255,0.4); }
    .btn-extend:hover { background: rgba(88,166,255,0.2); }
    .btn-copy { color: #3fb950; border-color: rgba(63,185,80,0.4); }
    .btn-copy:hover { background: rgba(63,185,80,0.2); }
  </style>
</head>
<body>
  <div class="container">
    <div class="top-bar">
      <div class="brand">
        <i class="fa-solid fa-shield-halved"></i>
        <span>VCAM LICENSE & DEVICE MANAGER PRO</span>
      </div>
      <div style="display: flex; gap: 10px; align-items: center;">
        <span id="kvStatusBadge" class="badge badge-active" style="display: none;"></span>
        <button class="btn-action" onclick="refreshData()"><i class="fa-solid fa-arrows-rotate"></i> Làm mới dữ liệu</button>
      </div>
    </div>

    <!-- KHỐI CẢNH BÁO NẾU CHƯA GẮN KV BINDING -->
    <div id="kvWarningBox" style="display: none; background: rgba(218,54,51,0.15); border: 1px solid #da3633; color: #f85149; padding: 14px 18px; border-radius: 8px; margin-bottom: 20px; font-size: 13px; line-height: 1.6;">
      <strong style="font-size: 14px;"><i class="fa-solid fa-triangle-exclamation"></i> CHƯA CẤU HÌNH KV NAMESPACE BINDING!</strong><br>
      Cloudflare Worker chưa được liên kết với KV Namespace nên dữ liệu chỉ lưu tạm trong RAM. Để lưu vĩnh viễn vào KV:<br>
      1. Trên Cloudflare dashboard, vào Worker của bạn -> chọn tab <b>Settings</b> -> <b>Variables and Secrets</b> (hoặc <b>Bindings</b>).<br>
      2. Tại mục <b>KV Namespace Bindings</b> -> Bấm <b>Add binding</b>.<br>
      3. Đặt ô <b>Variable name</b> là: <code style="background: #21262d; padding: 2px 6px; border-radius: 4px; color: #58a6ff;">VCAM_KV</code><br>
      4. Chọn <b>KV namespace</b> của bạn -> Bấm <b>Save and Deploy</b>.
    </div>

    <div class="stats-grid">
      <div class="stat-card">
        <div class="stat-icon" style="background: rgba(88,166,255,0.15); color: var(--accent);">
          <i class="fa-solid fa-mobile-screen"></i>
        </div>
        <div>
          <div class="stat-val" id="statTotalDevices">0</div>
          <div class="stat-label">Tổng thiết bị kích hoạt</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon" style="background: rgba(35,134,54,0.15); color: #3fb950;">
          <i class="fa-solid fa-circle-check"></i>
        </div>
        <div>
          <div class="stat-val" id="statActiveDevices">0</div>
          <div class="stat-label">Đang hoạt động (Active)</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon" style="background: rgba(218,54,51,0.15); color: #f85149;">
          <i class="fa-solid fa-ban"></i>
        </div>
        <div>
          <div class="stat-val" id="statBannedDevices">0</div>
          <div class="stat-label">Bị khóa từ xa (Banned)</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon" style="background: rgba(210,153,34,0.15); color: var(--yellow);">
          <i class="fa-solid fa-key"></i>
        </div>
        <div>
          <div class="stat-val" id="statTotalKeys">0</div>
          <div class="stat-label">Tổng số Active Key</div>
        </div>
      </div>
    </div>

    <div class="main-grid">
      <!-- BẢNG TẠO KEY -->
      <div>
        <div class="panel">
          <div class="panel-title">
            <span><i class="fa-solid fa-plus-circle" style="color: var(--accent);"></i> Tạo Active Key Mới</span>
          </div>

          <label>Thời Hạn Bản Quyền:</label>
          <select id="durationSelect" onchange="toggleCustomTime()">
            <option value="hours:1">1 Giờ (Dùng thử siêu ngắn)</option>
            <option value="days:1">1 Ngày (24 Giờ)</option>
            <option value="days:3">3 Ngày</option>
            <option value="days:7">7 Ngày (1 Tuần)</option>
            <option value="days:15">15 Ngày (Nửa tháng)</option>
            <option value="days:30" selected>30 Ngày (1 Tháng)</option>
            <option value="days:90">90 Ngày (3 Tháng)</option>
            <option value="days:180">180 Ngày (6 Tháng)</option>
            <option value="days:365">365 Ngày (1 Năm)</option>
            <option value="lifetime:0">👑 Vĩnh Viễn (Lifetime - Không giới hạn)</option>
            <option value="custom">⚙️ Tùy chọn thời gian khác...</option>
          </select>

          <div id="customDiv" style="display: none; margin-top: 10px; background: rgba(255,255,255,0.03); padding: 12px; border-radius: 6px; border: 1px solid var(--border);">
            <label style="margin-top: 0;">Số lượng thời gian:</label>
            <div style="display: flex; gap: 8px;">
              <input type="number" id="customVal" value="5" min="1" style="width: 100px;">
              <select id="customUnit">
                <option value="hours">Giờ</option>
                <option value="days" selected>Ngày</option>
              </select>
            </div>
          </div>

          <label>Ghi Chú / Tên Khách:</label>
          <input type="text" id="note" placeholder="Ví dụ: Khách Pixel 4 / Zalo 09xx...">

          <button class="btn-primary" id="btnCreateKey" onclick="createKey()">
            <i class="fa-solid fa-wand-magic-sparkles"></i> TẠO ACTIVE KEY NGAY
          </button>

          <div class="key-box" id="resultBox">
            <div style="font-size: 11px; color: #8b949e;">MÃ ACTIVE KEY CHO KHÁCH:</div>
            <div class="key-code" id="keyCode">VCAM-XXXX-XXXX</div>
            <div id="keyInfo" style="font-size: 12px; color: var(--accent); margin-bottom: 10px;"></div>
            <button class="btn-action btn-copy" onclick="copyCreatedKey()"><i class="fa-solid fa-copy"></i> Sao chép mã gửi khách</button>
          </div>
        </div>
      </div>

      <!-- BẢNG DANH SÁCH KEY & THIẾT BỊ -->
      <div>
        <!-- DANH SÁCH MÃ KEY ĐÃ TẠO -->
        <div class="panel">
          <div class="panel-title">
            <span><i class="fa-solid fa-key" style="color: var(--yellow);"></i> Danh Sách Mã Key Đã Tạo Trong Hệ Thống</span>
            <span style="font-size: 12px; font-weight: normal; color: #8b949e;" id="keysCountBadge">0 Keys</span>
          </div>

          <div style="overflow-x: auto;">
            <table>
              <thead>
                <tr>
                  <th>Mã Key</th>
                  <th>Gói Hạn</th>
                  <th>Ghi Chú</th>
                  <th>Máy Kích Hoạt</th>
                  <th>Trạng Thái</th>
                  <th>Thao Tác</th>
                </tr>
              </thead>
              <tbody id="keyTableBody">
                <tr><td colspan="6" style="text-align: center; color: #8b949e; padding: 20px;">Đang tải dữ liệu key...</td></tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- DANH SÁCH THIẾT BỊ KÍCH HOẠT -->
        <div class="panel">
          <div class="panel-title">
            <span><i class="fa-solid fa-mobile-screen" style="color: var(--accent);"></i> Danh Sách Thiết Bị Đang Kích Hoạt</span>
            <span style="font-size: 12px; font-weight: normal; color: #8b949e;">Ràng buộc theo Hardware Serial</span>
          </div>

          <div style="overflow-x: auto;">
            <table>
              <thead>
                <tr>
                  <th>Serial Máy</th>
                  <th>Ghi Chú</th>
                  <th>Gói Hạn</th>
                  <th>Hết Hạn Lúc</th>
                  <th>Trạng Thái</th>
                  <th>Thao Tác Quản Lý</th>
                </tr>
              </thead>
              <tbody id="deviceTableBody">
                <tr><td colspan="6" style="text-align: center; color: #8b949e; padding: 20px;">Đang tải dữ liệu thiết bị...</td></tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </div>
  </div>

  <script>
    function escapeHtml(text) {
      if (!text) return '';
      return String(text)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#039;');
    }

    function toggleCustomTime() {
      var sel = document.getElementById('durationSelect').value;
      document.getElementById('customDiv').style.display = (sel === 'custom') ? 'block' : 'none';
    }

    function copyText(str) {
      if (!str) return;
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(str).then(function() {
          alert('Đã sao chép: ' + str);
        }).catch(function() {
          prompt('Sao chép mã bên dưới:', str);
        });
      } else {
        prompt('Sao chép mã bên dưới:', str);
      }
    }

    function copyCreatedKey() {
      var k = document.getElementById('keyCode').innerText;
      copyText(k);
    }

    async function refreshData() {
      try {
        var res = await fetch('/api/admin/devices');
        var data = await res.json();
        if (data.success) {
          renderKVStatus(data);
          renderStats(data.devices, data.keys);
          renderKeys(data.keys);
          renderDevices(data.devices);
        }
      } catch (e) {
        console.error(e);
      }
    }

    function renderKVStatus(data) {
      var badge = document.getElementById('kvStatusBadge');
      var warn = document.getElementById('kvWarningBox');
      if (data.kv_bound) {
        badge.style.display = 'inline-block';
        badge.className = 'badge badge-active';
        badge.innerHTML = '<i class="fa-solid fa-database"></i> KV: ' + escapeHtml(data.kv_name);
        warn.style.display = 'none';
      } else {
        badge.style.display = 'inline-block';
        badge.className = 'badge badge-banned';
        badge.innerHTML = '<i class="fa-solid fa-triangle-exclamation"></i> KV: CHƯA GẮN BINDING';
        warn.style.display = 'block';
      }
    }

    function renderStats(devices, keys) {
      devices = devices || [];
      keys = keys || [];
      document.getElementById('statTotalDevices').innerText = devices.length;
      document.getElementById('statTotalKeys').innerText = keys.length;
      document.getElementById('keysCountBadge').innerText = keys.length + ' Keys';

      var now = Math.floor(Date.now() / 1000);
      var active = 0, banned = 0;
      devices.forEach(function(d) {
        if (d.status === 'BANNED') banned++;
        else if (!d.expires_at || d.expires_at === 0 || d.expires_at > now) active++;
      });

      document.getElementById('statActiveDevices').innerText = active;
      document.getElementById('statBannedDevices').innerText = banned;
    }

    function renderKeys(keys) {
      var tbody = document.getElementById('keyTableBody');
      if (!keys || keys.length === 0) {
        tbody.innerHTML = '<tr><td colspan="6" style="text-align: center; color: #8b949e; padding: 20px;">Chưa có mã key nào trong KV. Hãy tạo key ở cột bên trái!</td></tr>';
        return;
      }

      var html = '';
      keys.forEach(function(k) {
        var statusBadge = '<span class="badge badge-unused">🟢 Sẵn sàng</span>';
        var boundText = '<span style="color: #8b949e;">Chưa dùng</span>';

        if (k.status === 'REVOKED') {
          statusBadge = '<span class="badge badge-revoked">⛔ Thu hồi</span>';
        } else if (k.bound_serial) {
          statusBadge = '<span class="badge badge-used">🔵 Đã kích hoạt</span>';
          boundText = '<strong style="color: var(--accent); font-family: monospace;">' + escapeHtml(k.bound_serial) + '</strong>';
        }

        html += '<tr>' +
          '<td>' +
            '<strong style="color: #3fb950; font-family: monospace; font-size: 14px; letter-spacing: 1px;">' + escapeHtml(k.key) + '</strong>' +
          '</td>' +
          '<td><span style="color: var(--accent); font-weight: 600;">' + escapeHtml(k.duration_label || 'Vĩnh viễn') + '</span></td>' +
          '<td>' + escapeHtml(k.note || 'Khách') + '</td>' +
          '<td>' + boundText + '</td>' +
          '<td>' + statusBadge + '</td>' +
          '<td>' +
            '<button class="btn-action btn-copy" data-copy="' + escapeHtml(k.key) + '" onclick="onBtnCopy(this)"><i class="fa-solid fa-copy"></i> Copy</button>' +
            '<button class="btn-action" data-key="' + escapeHtml(k.key) + '" onclick="onBtnDeleteKey(this)" style="color: #8b949e;"><i class="fa-solid fa-trash"></i> Xóa</button>' +
          '</td>' +
        '</tr>';
      });
      tbody.innerHTML = html;
    }

    function renderDevices(devices) {
      var tbody = document.getElementById('deviceTableBody');
      if (!devices || devices.length === 0) {
        tbody.innerHTML = '<tr><td colspan="6" style="text-align: center; color: #8b949e; padding: 20px;">Chưa có thiết bị nào kích hoạt.</td></tr>';
        return;
      }

      var now = Math.floor(Date.now() / 1000);
      var html = '';
      devices.forEach(function(d) {
        var statusBadge = '<span class="badge badge-active">🟢 Hoạt động</span>';
        if (d.status === 'BANNED') {
          statusBadge = '<span class="badge badge-banned">⛔ Đã khóa</span>';
        } else if (d.expires_at > 0 && d.expires_at <= now) {
          statusBadge = '<span class="badge badge-expired">🔴 Hết hạn</span>';
        }

        var expireStr = (!d.expires_at || d.expires_at === 0) ? 'Vĩnh viễn' : new Date(d.expires_at * 1000).toLocaleString('vi-VN');

        var banBtn = (d.status === 'BANNED') 
          ? '<button class="btn-action" data-action="unban" data-serial="' + escapeHtml(d.serial) + '" onclick="onBtnDevAction(this)"><i class="fa-solid fa-unlock"></i> Mở khóa</button>' 
          : '<button class="btn-action btn-ban" data-action="ban" data-serial="' + escapeHtml(d.serial) + '" onclick="onBtnDevAction(this)"><i class="fa-solid fa-ban"></i> Khóa máy</button>';

        html += '<tr>' +
          '<td><strong style="color: var(--accent); font-family: monospace;">' + escapeHtml(d.serial) + '</strong><br><small style="color: #8b949e;">Key: ' + escapeHtml(d.key || 'N/A') + '</small></td>' +
          '<td>' + escapeHtml(d.note || 'Không có') + '</td>' +
          '<td><span style="color: #58a6ff;">' + escapeHtml(d.duration_label || 'N/A') + '</span></td>' +
          '<td>' + expireStr + '</td>' +
          '<td>' + statusBadge + '</td>' +
          '<td>' +
            banBtn +
            '<button class="btn-action btn-extend" data-serial="' + escapeHtml(d.serial) + '" onclick="onBtnExtend(this)"><i class="fa-solid fa-clock"></i> +30 Ngày</button>' +
            '<button class="btn-action" data-action="delete" data-serial="' + escapeHtml(d.serial) + '" onclick="onBtnDevAction(this)" style="color: #8b949e;"><i class="fa-solid fa-trash"></i> Xóa</button>' +
          '</td>' +
        '</tr>';
      });
      tbody.innerHTML = html;
    }

    function onBtnCopy(btn) {
      var val = btn.getAttribute('data-copy');
      copyText(val);
    }

    function onBtnDeleteKey(btn) {
      var key = btn.getAttribute('data-key');
      if (!confirm('Bạn có chắc muốn XÓA vĩnh viễn mã key: ' + key + '?')) return;
      deleteKey(key);
    }

    async function deleteKey(key) {
      try {
        var res = await fetch('/api/admin/key-action', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ action: 'delete', key: key })
        });
        var data = await res.json();
        if (data.success) {
          alert(data.message);
          refreshData();
        } else {
          alert('Lỗi: ' + (data.error || JSON.stringify(data)));
        }
      } catch (e) {
        alert('Lỗi kết nối: ' + e.message);
      }
    }

    function onBtnDevAction(btn) {
      var action = btn.getAttribute('data-action');
      var serial = btn.getAttribute('data-serial');
      deviceAction(action, serial);
    }

    function onBtnExtend(btn) {
      var serial = btn.getAttribute('data-serial');
      extendDevice(serial);
    }

    async function deviceAction(action, serial, days_add) {
      days_add = days_add || 0;
      if (action === 'ban' && !confirm('Bạn có chắc muốn KHÓA từ xa máy: ' + serial + '? Máy này sẽ bị chặn dùng VCAM ngay lập tức!')) return;
      if (action === 'delete' && !confirm('Bạn có chắc muốn gỡ bỏ thiết bị: ' + serial + '?')) return;

      try {
        var res = await fetch('/api/admin/device-action', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ action: action, serial: serial, days_add: days_add })
        });
        var data = await res.json();
        if (data.success) {
          alert(data.message);
          refreshData();
        } else {
          alert(data.error || 'Lỗi thao tác');
        }
      } catch (e) {
        alert('Lỗi kết nối: ' + e.message);
      }
    }

    function extendDevice(serial) {
      var days = prompt('Nhập số ngày muốn gia hạn thêm cho máy ' + serial + ':', '30');
      if (days && parseInt(days) > 0) {
        deviceAction('extend', serial, parseInt(days));
      }
    }

    async function createKey() {
      var btn = document.getElementById('btnCreateKey');
      if (btn) { btn.disabled = true; btn.innerHTML = '<i class="fa-solid fa-spinner fa-spin"></i> Đang tạo Key...'; }

      var note = document.getElementById('note').value;
      var sel = document.getElementById('durationSelect').value;

      var duration_type = 'days';
      var duration_val = 30;

      if (sel === 'custom') {
        duration_type = document.getElementById('customUnit').value;
        duration_val = document.getElementById('customVal').value;
      } else {
        var parts = sel.split(':');
        duration_type = parts[0];
        duration_val = parts[1];
      }

      try {
        var res = await fetch('/api/admin/create-key', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ duration_type: duration_type, duration_val: duration_val, note: note })
        });
        var data = await res.json();
        if (data.success) {
          document.getElementById('resultBox').style.display = 'block';
          document.getElementById('keyCode').innerText = data.key.key;
          document.getElementById('keyInfo').innerText = 'Gói: ' + data.key.duration_label + ' | ' + (note || 'Khách');
          alert('Tạo Key thành công! Mã Key: ' + data.key.key + ' (' + data.key.duration_label + ')');
          refreshData();
        } else {
          alert('Lỗi tạo key: ' + (data.error || JSON.stringify(data)));
        }
      } catch (e) {
        alert('Lỗi kết nối Server: ' + e.message);
      } finally {
        if (btn) { btn.disabled = false; btn.innerHTML = '<i class="fa-solid fa-wand-magic-sparkles"></i> TẠO ACTIVE KEY NGAY'; }
      }
    }

    window.onload = refreshData;
  </script>
</body>
</html>`;
}
