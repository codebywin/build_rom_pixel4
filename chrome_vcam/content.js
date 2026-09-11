/**
 * Chrome VCAM - Virtual Camera Injector v1.2.0 (Desktop Standard 16:9)
 * Manifest V3 - Main World Content Script
 */
(function() {
  'use strict';

  if (window.__CHROME_VCAM_INITIALIZED__) return;
  window.__CHROME_VCAM_INITIALIZED__ = true;

  console.log('[Chrome VCAM] Initializing Desktop Standard 16:9 Virtual Camera...');

  const state = {
    enabled: true,
    mediaType: null, // 'video' | 'image'
    fileName: 'Chưa chọn file',
    fileSize: '',
    fitMode: 'desktop_std', // 'desktop_std' (16:9 chuẩn) | 'contain' (viền đen) | 'cover'
    panY: 0,
    panX: 0,
    zoom: 1.0,
    mirror: false,
    microNoise: true,
    width: 1920,
    height: 1080,
    stream: null
  };

  // Canvas & Hidden Elements
  const canvas = document.createElement('canvas');
  canvas.width = state.width;
  canvas.height = state.height;
  canvas.style.display = 'none';
  const ctx = canvas.getContext('2d', { willReadFrequently: true });

  const hiddenVideo = document.createElement('video');
  hiddenVideo.loop = true;
  hiddenVideo.muted = true;
  hiddenVideo.playsInline = true;
  hiddenVideo.crossOrigin = 'anonymous';
  hiddenVideo.style.display = 'none';

  const hiddenImg = new Image();
  hiddenImg.crossOrigin = 'anonymous';

  let lastNoiseTick = 0;

  function renderLoop() {
    requestAnimationFrame(renderLoop);

    ctx.save();
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    ctx.fillStyle = '#111111';
    ctx.fillRect(0, 0, canvas.width, canvas.height);

    if (state.mediaType === 'video' && hiddenVideo.readyState >= 2) {
      drawMedia(hiddenVideo);
    } else if (state.mediaType === 'image' && hiddenImg.complete && hiddenImg.naturalWidth > 0) {
      drawMedia(hiddenImg);
      applyAntiSpoofing();
    } else {
      drawStandby();
    }

    ctx.restore();
  }

  function drawMedia(media) {
    const mw = media.videoWidth || media.naturalWidth || 1920;
    const mh = media.videoHeight || media.naturalHeight || 1080;

    let dw, dh, dx, dy;
    const canvasRatio = canvas.width / canvas.height; // 16:9 = 1.7777...
    const mediaRatio = mw / mh;

    if (state.fitMode === 'desktop_std') {
      // Chuẩn Webcam Máy Tính 16:9:
      // Tự động lấp đầy toàn bộ khung hình 16:9 (KHÔNG CÓ VIỀN ĐEN)
      // Căn chỉnh khuôn mặt và tờ giấy vào đúng tầm mắt webcam
      dw = canvas.width * state.zoom;
      dh = dw / mediaRatio;
      dx = (canvas.width - dw) / 2 + (state.panX * canvas.width);

      if (mediaRatio < canvasRatio) {
        // Ảnh dọc (portrait): Căn lấy từ trán/mắt xuống tờ giấy (bỏ qua đèn trần)
        const baseOffset = -dh * 0.12;
        dy = baseOffset + (state.panY * canvas.height);
      } else {
        // Ảnh ngang (landscape): Căn giữa
        dy = (canvas.height - dh) / 2 + (state.panY * canvas.height);
      }
    } else if (state.fitMode === 'contain') {
      if (mediaRatio > canvasRatio) {
        dw = canvas.width * state.zoom;
        dh = dw / mediaRatio;
      } else {
        dh = canvas.height * state.zoom;
        dw = dh * mediaRatio;
      }
      dx = (canvas.width - dw) / 2 + (state.panX * canvas.width);
      dy = (canvas.height - dh) / 2 + (state.panY * canvas.height);
    } else { // cover
      if (mediaRatio > canvasRatio) {
        dh = canvas.height * state.zoom;
        dw = dh * mediaRatio;
      } else {
        dw = canvas.width * state.zoom;
        dh = dw / mediaRatio;
      }
      dx = (canvas.width - dw) / 2 + (state.panX * canvas.width);
      dy = (canvas.height - dh) / 2 + (state.panY * canvas.height);
    }

    ctx.save();
    if (state.mirror) {
      ctx.translate(canvas.width, 0);
      ctx.scale(-1, 1);
    }

    // Micro-motion chống phát hiện ảnh tĩnh
    if (state.microNoise && state.mediaType === 'image') {
      const swayX = Math.sin(Date.now() / 900) * 1.5;
      const swayY = Math.cos(Date.now() / 1300) * 1.5;
      ctx.drawImage(media, dx + swayX, dy + swayY, dw, dh);
    } else {
      ctx.drawImage(media, dx, dy, dw, dh);
    }

    ctx.restore();
  }

  function applyAntiSpoofing() {
    if (!state.microNoise) return;
    const now = Date.now();
    if (now - lastNoiseTick > 100) {
      lastNoiseTick = now;
      try {
        const patch = ctx.getImageData(0, 0, 16, 16);
        for (let i = 0; i < patch.data.length; i += 4) {
          const delta = (Math.random() > 0.5 ? 1 : -1) * (Math.floor(Math.random() * 3) + 1);
          patch.data[i] = Math.min(255, Math.max(0, patch.data[i] + delta));
        }
        ctx.putImageData(patch, 0, 0);
      } catch (e) {}
    }
  }

  function drawStandby() {
    ctx.fillStyle = '#0f172a';
    ctx.fillRect(0, 0, canvas.width, canvas.height);

    ctx.font = 'bold 44px sans-serif';
    ctx.fillStyle = '#38bdf8';
    ctx.textAlign = 'center';
    ctx.fillText('Chrome VCAM - Chuẩn Desktop 16:9', canvas.width / 2, canvas.height / 2 - 40);

    ctx.font = '24px sans-serif';
    ctx.fillStyle = '#94a3b8';
    ctx.fillText('Bấm [ Chọn Video / Ảnh ] để phát hình vào Webcam', canvas.width / 2, canvas.height / 2 + 20);

    const x = (Date.now() / 15) % canvas.width;
    ctx.fillStyle = '#38bdf8';
    ctx.fillRect(x, canvas.height - 8, 80, 8);
  }

  renderLoop();

  function getVcamStream(constraints) {
    if (!state.stream || !state.stream.active) {
      state.stream = canvas.captureStream(30);
    }

    const stream = state.stream.clone();
    const track = stream.getVideoTracks()[0];
    if (track) {
      Object.defineProperty(track, 'label', {
        value: 'HD Pro Webcam C920 (VCAM Virtual)',
        configurable: true
      });

      const origGetSettings = track.getSettings.bind(track);
      track.getSettings = () => ({
        ...origGetSettings(),
        width: canvas.width,
        height: canvas.height,
        frameRate: 30,
        facingMode: (constraints && constraints.video && constraints.video.facingMode) || 'user',
        aspectRatio: canvas.width / canvas.height
      });
    }

    return stream;
  }

  // Hook WebRTC API
  if (navigator.mediaDevices) {
    const origGetUserMedia = navigator.mediaDevices.getUserMedia?.bind(navigator.mediaDevices);
    const origEnumerateDevices = navigator.mediaDevices.enumerateDevices?.bind(navigator.mediaDevices);

    navigator.mediaDevices.getUserMedia = async function(constraints) {
      console.log('[Chrome VCAM] getUserMedia requested with constraints:', constraints);
      if (state.enabled && constraints && constraints.video) {
        console.log('[Chrome VCAM] Virtual Camera stream active (1920x1080 16:9)!');
        return getVcamStream(constraints);
      }
      return origGetUserMedia ? origGetUserMedia(constraints) : Promise.reject(new Error('No camera available'));
    };

    if (origEnumerateDevices) {
      navigator.mediaDevices.enumerateDevices = async function() {
        const devices = await origEnumerateDevices();
        const vcamDevice = {
          deviceId: 'vcam-virtual-device-0',
          groupId: 'vcam-group-0',
          kind: 'videoinput',
          label: 'HD Pro Webcam C920 (VCAM Virtual)',
          toJSON: function() { return this; }
        };
        return [vcamDevice, ...devices.filter(d => d.deviceId !== 'vcam-virtual-device-0')];
      };
    }
  }

  if (navigator.getUserMedia) {
    navigator.getUserMedia = function(constraints, success, error) {
      if (state.enabled && constraints && constraints.video) {
        try {
          success(getVcamStream(constraints));
        } catch (e) {
          error(e);
        }
      } else {
        error(new Error('No camera'));
      }
    };
  }
  if (navigator.webkitGetUserMedia) {
    navigator.webkitGetUserMedia = navigator.getUserMedia;
  }

  // IndexedDB Storage
  const DB_NAME = 'ChromeVcamDB';
  const STORE_NAME = 'mediaStore';

  function openDB() {
    return new Promise((resolve, reject) => {
      const req = indexedDB.open(DB_NAME, 1);
      req.onupgradeneeded = () => req.result.createObjectStore(STORE_NAME);
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
  }

  async function saveMediaToDB(file, name, type) {
    try {
      const db = await openDB();
      const tx = db.transaction(STORE_NAME, 'readwrite');
      tx.objectStore(STORE_NAME).put({ data: file, name, type }, 'lastMedia');
    } catch (e) {}
  }

  async function loadMediaFromDB() {
    try {
      const db = await openDB();
      const tx = db.transaction(STORE_NAME, 'readonly');
      const req = tx.objectStore(STORE_NAME).get('lastMedia');
      req.onsuccess = () => {
        const res = req.result;
        if (res && res.data) {
          loadMediaSource(res.data, res.name, res.type, false);
        }
      };
    } catch (e) {}
  }

  function loadMediaSource(fileBlob, name, type, save = true) {
    const url = URL.createObjectURL(fileBlob);
    state.fileName = name;
    state.fileSize = (fileBlob.size / 1024 / 1024).toFixed(2) + ' MB';

    if (type.startsWith('video/')) {
      state.mediaType = 'video';
      hiddenVideo.src = url;
      hiddenVideo.onloadedmetadata = () => {
        hiddenVideo.play();
        updateHUD();
      };
    } else {
      state.mediaType = 'image';
      hiddenImg.src = url;
      hiddenImg.onload = () => updateHUD();
    }

    if (save) {
      saveMediaToDB(fileBlob, name, type);
    }
  }

  function initHUD() {
    if (document.getElementById('chrome-vcam-hud')) return;

    const hud = document.createElement('div');
    hud.id = 'chrome-vcam-hud';
    hud.innerHTML = `
      <div class="vcam-header">
        <div class="vcam-title">
          <span class="vcam-dot"></span>
          <span>Chrome VCAM (Chuẩn Máy Tính)</span>
        </div>
        <div class="vcam-header-actions">
          <button class="vcam-btn-icon" id="vcam-btn-min" title="Thu nhỏ">_</button>
        </div>
      </div>
      <div class="vcam-body" id="vcam-body">
        <div class="vcam-status-bar">
          <span class="vcam-badge" id="vcam-status-badge">🟢 Chuẩn 16:9 Sẵn Sàng</span>
          <label class="vcam-toggle-label">
            <input type="checkbox" id="vcam-toggle-active" checked>
            <span>Bật</span>
          </label>
        </div>

        <div class="vcam-preview-box" id="vcam-preview-box">
          <span class="vcam-preview-text" id="vcam-preview-text">Chưa có video/ảnh</span>
          <canvas id="vcam-preview-canvas" width="320" height="180" style="display:none;width:100%;height:100%;object-fit:contain;"></canvas>
        </div>

        <div class="vcam-file-info" id="vcam-file-info">
          <b id="vcam-file-name">Chưa chọn file</b>
          <span id="vcam-file-size"></span>
        </div>

        <div class="vcam-actions">
          <input type="file" id="vcam-file-input" accept="video/*,image/*" style="display:none">
          <button class="vcam-btn vcam-btn-primary" id="vcam-btn-select">📁 Chọn Video / Ảnh Mới</button>
        </div>

        <div class="vcam-settings">
          <div class="vcam-setting-row">
            <span>Tỉ lệ:</span>
            <select class="vcam-select" id="vcam-fit-mode">
              <option value="desktop_std" selected>🖥️ Chuẩn Máy Tính 16:9 (Không Viền)</option>
              <option value="contain">Vừa vặn (Có viền đen 2 bên)</option>
              <option value="cover">Lấp đầy (Căn giữa)</option>
            </select>
          </div>

          <div class="vcam-setting-slider">
            <div class="vcam-slider-header">
              <span>↕ Căn chỉnh Vị trí (Lên / Xuống):</span>
              <span id="vcam-pan-y-val">0%</span>
            </div>
            <input type="range" class="vcam-range" id="vcam-range-pan-y" min="-0.35" max="0.35" step="0.01" value="0">
          </div>

          <div class="vcam-setting-slider">
            <div class="vcam-slider-header">
              <span>🔍 Phóng to (Zoom):</span>
              <span id="vcam-zoom-val">1.0x</span>
            </div>
            <input type="range" class="vcam-range" id="vcam-range-zoom" min="0.8" max="2.0" step="0.05" value="1.0">
          </div>

          <div class="vcam-setting-row">
            <label class="vcam-checkbox-label">
              <input type="checkbox" id="vcam-cb-mirror">
              <span>Lật gương (Mirror)</span>
            </label>
          </div>
          <div class="vcam-setting-row">
            <label class="vcam-checkbox-label">
              <input type="checkbox" id="vcam-cb-noise" checked>
              <span>Rung vi sai (Chống cờ "Same!")</span>
            </label>
          </div>
        </div>
      </div>
    `;

    document.body.appendChild(hud);

    const btnSelect = hud.querySelector('#vcam-btn-select');
    const fileInput = hud.querySelector('#vcam-file-input');
    const toggleActive = hud.querySelector('#vcam-toggle-active');
    const selFitMode = hud.querySelector('#vcam-fit-mode');
    const rangePanY = hud.querySelector('#vcam-range-pan-y');
    const rangeZoom = hud.querySelector('#vcam-range-zoom');
    const panYVal = hud.querySelector('#vcam-pan-y-val');
    const zoomVal = hud.querySelector('#vcam-zoom-val');
    const cbMirror = hud.querySelector('#vcam-cb-mirror');
    const cbNoise = hud.querySelector('#vcam-cb-noise');
    const btnMin = hud.querySelector('#vcam-btn-min');
    const vcamBody = hud.querySelector('#vcam-body');

    btnSelect.onclick = () => fileInput.click();

    fileInput.onchange = (e) => {
      const file = e.target.files[0];
      if (file) {
        loadMediaSource(file, file.name, file.type, true);
      }
    };

    toggleActive.onchange = () => {
      state.enabled = toggleActive.checked;
      hud.querySelector('#vcam-status-badge').textContent = state.enabled ? '🟢 Chuẩn 16:9 Sẵn Sàng' : '⚪ Đã Tắt';
    };

    selFitMode.onchange = () => {
      state.fitMode = selFitMode.value;
    };

    rangePanY.oninput = () => {
      state.panY = parseFloat(rangePanY.value);
      panYVal.textContent = Math.round(state.panY * 100) + '%';
    };

    rangeZoom.oninput = () => {
      state.zoom = parseFloat(rangeZoom.value);
      zoomVal.textContent = state.zoom.toFixed(1) + 'x';
    };

    cbMirror.onchange = () => {
      state.mirror = cbMirror.checked;
    };

    cbNoise.onchange = () => {
      state.microNoise = cbNoise.checked;
    };

    btnMin.onclick = () => {
      vcamBody.classList.toggle('vcam-hidden');
      btnMin.textContent = vcamBody.classList.contains('vcam-hidden') ? '+' : '_';
    };

    let isDragging = false, startX, startY, origX, origY;
    const header = hud.querySelector('.vcam-header');
    header.onmousedown = (e) => {
      if (e.target.tagName === 'BUTTON') return;
      isDragging = true;
      startX = e.clientX;
      startY = e.clientY;
      origX = hud.offsetLeft;
      origY = hud.offsetTop;
      document.onmousemove = (e) => {
        if (!isDragging) return;
        hud.style.right = 'auto';
        hud.style.bottom = 'auto';
        hud.style.left = (origX + e.clientX - startX) + 'px';
        hud.style.top = (origY + e.clientY - startY) + 'px';
      };
      document.onmouseup = () => {
        isDragging = false;
        document.onmousemove = null;
      };
    };

    const pCanvas = hud.querySelector('#vcam-preview-canvas');
    const pCtx = pCanvas.getContext('2d');
    function updateHUDPreview() {
      if (state.mediaType && pCanvas.style.display !== 'none') {
        pCtx.drawImage(canvas, 0, 0, pCanvas.width, pCanvas.height);
      }
      requestAnimationFrame(updateHUDPreview);
    }
    updateHUDPreview();

    loadMediaFromDB();
  }

  function updateHUD() {
    const hud = document.getElementById('chrome-vcam-hud');
    if (!hud) return;

    hud.querySelector('#vcam-file-name').textContent = state.fileName;
    hud.querySelector('#vcam-file-size').textContent = state.fileSize;

    const pText = hud.querySelector('#vcam-preview-text');
    const pCanvas = hud.querySelector('#vcam-preview-canvas');

    pText.style.display = 'none';
    pCanvas.style.display = 'block';
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initHUD);
  } else {
    initHUD();
  }

})();
