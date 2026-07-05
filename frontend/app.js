import * as THREE from "three";

// ===========================================================================
// SECTION 1 — Constants & Configuration
// ===========================================================================
const CONFIG = {
  WS_URL:
    location.hostname === "localhost" || location.hostname === "127.0.0.1"
      ? "ws://localhost:8080/ws/mosaic"
      : "wss://shreyasvn-pixel-mosaic.hf.space/ws/mosaic",
  ANIMATION_DURATION_MS: 10000,
  HOLD_MS: 1000,
  MAX_FILE_BYTES: 10 * 1024 * 1024,
  ACCEPTED_TYPES: ["image/jpeg", "image/png", "image/webp"],
  CHUNK_SIZE: 262144,
  HEADER_BYTES: 32,
  BYTES_PER_PARTICLE: 12,
};

const MAGIC = 0x4d4f5301;
const LITTLE_ENDIAN = false;

// ===========================================================================
// SECTION 2 — State Machine
// ===========================================================================
const STATES = {
  EMPTY: "EMPTY",
  READY: "READY",
  WORKING: "WORKING",
  ANIMATING: "ANIMATING",
  DONE: "DONE",
  ERROR: "ERROR",
};

let currentState = STATES.EMPTY;
let sourceFile = null;
let targetFile = null;
let ws = null;

function setState(newState, data = {}) {
  currentState = newState;

  document.querySelectorAll(".state-panel").forEach((p) => {
    p.style.display = "none";
  });
  const panel = document.getElementById(`state-${newState.toLowerCase()}`);
  if (panel) panel.style.display = "flex";

  const genBtn = document.getElementById("btn-generate");
  const dlBtn = document.getElementById("btn-download");
  genBtn.disabled = newState !== STATES.READY;
  dlBtn.style.display = newState === STATES.DONE ? "" : "none";

  if (newState === STATES.ERROR) {
    document.getElementById("error-message").textContent =
      data.reason || "Unknown error";
  }
  if (newState === STATES.ANIMATING) {
    if (!window.mosaicData) {
      setState(STATES.ERROR, { reason: "No mosaic data received" });
      return;
    }
    renderer.init(window.mosaicData);
    startAnimationProgressBar();
    renderer.startAnimation(() => setState(STATES.DONE));
  }

  console.log(`[state] → ${newState}`);
}

// ===========================================================================
// SECTION 3 — File Upload Handling
// ===========================================================================
function initFileInputs() {
  document
    .getElementById("source-input")
    .addEventListener("change", (e) => handleFileSelect(e, "source"));
  document
    .getElementById("target-input")
    .addEventListener("change", (e) => handleFileSelect(e, "target"));
}

function handleFileSelect(event, slot) {
  const file = event.target.files[0];
  if (!file) return;

  if (file.size > CONFIG.MAX_FILE_BYTES) {
    showFileError(slot, "File too large (max 10MB)");
    return;
  }
  if (!CONFIG.ACCEPTED_TYPES.includes(file.type)) {
    showFileError(slot, "Unsupported format. Use JPEG, PNG, or WebP");
    return;
  }

  const reader = new FileReader();
  reader.onload = () => {
    const record = {
      name: file.name,
      bytes: reader.result,
      type: file.type,
      size: file.size,
    };
    if (slot === "source") sourceFile = record;
    else targetFile = record;

    showThumbnail(slot, file);
    checkBothReady();
  };
  reader.onerror = () => showFileError(slot, "Could not read file");
  reader.readAsArrayBuffer(file);
}

function showThumbnail(slot, file) {
  const card = getCard(slot);
  const img = card.querySelector(".thumb-img");
  const placeholder = card.querySelector(".thumb-placeholder");
  const nameEl = card.querySelector(".file-name");
  const resEl = card.querySelector(".file-res");

  nameEl.classList.remove("file-error");

  const url = URL.createObjectURL(file);
  img.onload = () => {
    nameEl.textContent = file.name;
    resEl.textContent = ` — ${img.naturalWidth}×${img.naturalHeight}`;
    URL.revokeObjectURL(url);
  };
  img.src = url;
  img.hidden = false;
  placeholder.style.display = "none";
}

function showFileError(slot, message) {
  const card = getCard(slot);
  const nameEl = card.querySelector(".file-name");
  const resEl = card.querySelector(".file-res");
  nameEl.textContent = message;
  nameEl.classList.add("file-error");
  resEl.textContent = "";
}

function getCard(slot) {
  const id = slot === "source" ? "source-input" : "target-input";
  return document.getElementById(id).closest(".upload-card");
}

function resetUploads() {
  sourceFile = null;
  targetFile = null;
  ["source", "target"].forEach((slot) => {
    const card = getCard(slot);
    card.querySelector(".thumb-img").hidden = true;
    card.querySelector(".thumb-img").removeAttribute("src");
    card.querySelector(".thumb-placeholder").style.display = "";
    const nameEl = card.querySelector(".file-name");
    nameEl.textContent = "No file chosen";
    nameEl.classList.remove("file-error");
    card.querySelector(".file-res").textContent = "";
    document.getElementById(
      slot === "source" ? "source-input" : "target-input"
    ).value = "";
  });
}

function checkBothReady() {
  const resettable = [
    STATES.EMPTY,
    STATES.READY,
    STATES.DONE,
    STATES.ERROR,
  ];
  if (sourceFile && targetFile && resettable.includes(currentState)) {
    setState(STATES.READY);
  }
}

// ===========================================================================
// SECTION 4 — WebSocket Protocol
// ===========================================================================
let receivedBytes = 0;
let expectedBytes = 0;
let headerParsed = false;
let payloadChunks = [];
let particleCount = 0;
let srcW, srcH, tgtW, tgtH;

function connect() {
  receivedBytes = 0;
  expectedBytes = 0;
  headerParsed = false;
  payloadChunks = [];
  particleCount = 0;
  updateWorkingProgress(0);
  updateWorkingStatus("Connecting…");

  ws = new WebSocket(CONFIG.WS_URL);
  ws.binaryType = "arraybuffer";

  ws.onopen = () => sendBeginRequest();

  ws.onmessage = (event) => {
    if (typeof event.data === "string") {
      handleTextFrame(JSON.parse(event.data));
    } else {
      handleBinaryFrame(event.data);
    }
  };

  ws.onerror = () => {
    setState(STATES.ERROR, { reason: "WebSocket connection failed" });
  };

  ws.onclose = (event) => {
    if (currentState !== STATES.WORKING) return;
    if (event.code === 1008) {
      setState(STATES.ERROR, {
        reason: "You've reached the hourly request limit. Please try again later.",
      });
    } else {
      setState(STATES.ERROR, { reason: `Connection closed (${event.code})` });
    }
  };
}

function sendBeginRequest() {
  ws.send(
    JSON.stringify({
      type: "begin_request",
      source_bytes: sourceFile.size,
      target_bytes: targetFile.size,
      source_format: sourceFile.type,
      target_format: targetFile.type,
    })
  );
}

function handleTextFrame(msg) {
  switch (msg.type) {
    case "accepted":
      updateWorkingStatus("Generating segmentation mask…");
      ws.send(sourceFile.bytes);
      ws.send(targetFile.bytes);
      break;
    case "rejected":
      setState(STATES.ERROR, { reason: "Server busy. Try again shortly." });
      ws.close();
      break;
    case "complete":
      onPayloadComplete(msg.particle_count);
      break;
    case "error":
      setState(STATES.ERROR, { reason: msg.reason || "Server error" });
      ws.close();
      break;
  }
}

function handleBinaryFrame(data) {
  if (!headerParsed) {
    parseHeader(data);
    return;
  }

  payloadChunks.push(data);
  receivedBytes += data.byteLength;

  const pct = Math.min((receivedBytes / expectedBytes) * 100, 99);
  updateWorkingProgress(pct);
  updateWorkingStatus(`Receiving payload… ${Math.round(pct)}%`);
}

function parseHeader(data) {
  const view = new DataView(data);
  const magic = view.getUint32(0, LITTLE_ENDIAN);
  if (magic !== MAGIC) {
    setState(STATES.ERROR, { reason: "Invalid server response" });
    ws.close();
    return;
  }
  particleCount = view.getUint32(8, LITTLE_ENDIAN);
  srcW = view.getUint32(12, LITTLE_ENDIAN);
  srcH = view.getUint32(16, LITTLE_ENDIAN);
  tgtW = view.getUint32(20, LITTLE_ENDIAN);
  tgtH = view.getUint32(24, LITTLE_ENDIAN);
  expectedBytes = particleCount * CONFIG.BYTES_PER_PARTICLE;
  headerParsed = true;
  document.querySelector(".canvas-section").style.aspectRatio = `${tgtW} / ${tgtH}`;
  updateWorkingStatus("Receiving payload…");
  console.log(
    `Header parsed: ${particleCount} particles, src=${srcW}x${srcH}, tgt=${tgtW}x${tgtH}`
  );
}

function onPayloadComplete(confirmedCount) {
  const total = payloadChunks.reduce((s, c) => s + c.byteLength, 0);
  const assembled = new Uint8Array(total);
  let offset = 0;
  for (const chunk of payloadChunks) {
    assembled.set(new Uint8Array(chunk), offset);
    offset += chunk.byteLength;
  }

  console.log(
    `Payload assembled: ${total} bytes, ${confirmedCount} particles`
  );

  const parsed = parsePayload(assembled.buffer, confirmedCount);

  window.mosaicData = {
    startX: parsed.startX,
    startY: parsed.startY,
    endX: parsed.endX,
    endY: parsed.endY,
    colors: parsed.colors,
    count: confirmedCount,
    srcW,
    srcH,
    tgtW,
    tgtH,
  };
  console.log("mosaicData ready for renderer:", window.mosaicData);

  ws.close();
  setState(STATES.ANIMATING);
}

function parsePayload(buffer, count) {
  const startX = new Float32Array(count);
  const startY = new Float32Array(count);
  const endX = new Float32Array(count);
  const endY = new Float32Array(count);
  const colors = new Float32Array(count * 3);

  const view = new DataView(buffer);
  for (let i = 0; i < count; i++) {
    const off = i * CONFIG.BYTES_PER_PARTICLE;
    startX[i] = view.getUint16(off, LITTLE_ENDIAN) / srcW; // normalize [0,1]
    startY[i] = view.getUint16(off + 2, LITTLE_ENDIAN) / srcH;
    endX[i] = view.getUint16(off + 4, LITTLE_ENDIAN) / tgtW;
    endY[i] = view.getUint16(off + 6, LITTLE_ENDIAN) / tgtH;
    colors[i * 3] = view.getUint8(off + 8) / 255;
    colors[i * 3 + 1] = view.getUint8(off + 9) / 255;
    colors[i * 3 + 2] = view.getUint8(off + 10) / 255;
  }
  return { startX, startY, endX, endY, colors };
}

// ===========================================================================
// SECTION 5 — UI Helpers
// ===========================================================================
function updateWorkingStatus(text) {
  document.getElementById("working-status").textContent = text;
}

function updateWorkingProgress(pct) {
  document.getElementById("working-progress-fill").style.width = pct + "%";
}

function startAnimationProgressBar() {
  const bar = document.getElementById("animation-progress-fill");
  bar.style.transition = "none";
  bar.style.width = "0%";
  void bar.offsetWidth;
  bar.style.transition = `width ${CONFIG.ANIMATION_DURATION_MS}ms linear`;
  bar.style.width = "100%";
}

// ===========================================================================
// SECTION 7 — WebGL Renderer (instanced points via Three.js)
// ===========================================================================
class MosaicRenderer {
  constructor(canvas) {
    this.canvas = canvas;
    this.renderer = null;
    this.scene = null;
    this.camera = null;
    this.mesh = null;
    this.animationId = null;
    this.startTime = null;
  }

  init(data) {
    this.dispose();

    this.renderer = new THREE.WebGLRenderer({
      canvas: this.canvas,
      antialias: false,
      alpha: false,
      powerPreference: "high-performance",
    });
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    this.renderer.setSize(
      this.canvas.clientWidth,
      this.canvas.clientHeight,
      false
    );
    this.renderer.setClearColor(0x0a0a0f, 1);

    this.camera = new THREE.OrthographicCamera(-1, 1, 1, -1, 0, 1);
    this.scene = new THREE.Scene();

    const geometry = new THREE.InstancedBufferGeometry();
    geometry.setAttribute(
      "position",
      new THREE.BufferAttribute(new Float32Array([0, 0, 0]), 3)
    );
    geometry.instanceCount = data.count;

    geometry.setAttribute(
      "aStartX",
      new THREE.InstancedBufferAttribute(data.startX, 1)
    );
    geometry.setAttribute(
      "aStartY",
      new THREE.InstancedBufferAttribute(data.startY, 1)
    );
    geometry.setAttribute(
      "aEndX",
      new THREE.InstancedBufferAttribute(data.endX, 1)
    );
    geometry.setAttribute(
      "aEndY",
      new THREE.InstancedBufferAttribute(data.endY, 1)
    );
    geometry.setAttribute(
      "aColor",
      new THREE.InstancedBufferAttribute(data.colors, 3)
    );

    const material = new THREE.RawShaderMaterial({
      uniforms: {
        uProgress: { value: 0.0 },
        uPointSize: { value: this.computePointSize(data.count) },
        uHoldTime: { value: CONFIG.HOLD_MS / CONFIG.ANIMATION_DURATION_MS },
      },
      vertexShader: this.vertexShader(),
      fragmentShader: this.fragmentShader(),
      transparent: false,
      depthTest: false,
      depthWrite: false,
    });

    this.mesh = new THREE.Points(geometry, material);
    this.mesh.frustumCulled = false;
    this.scene.add(this.mesh);
  }

  computePointSize(count) {
    const area = this.canvas.clientWidth * this.canvas.clientHeight;
    const density = count / area;
    if (density > 2) return 1.0;
    if (density > 1) return 1.5;
    return 2.0;
  }

  vertexShader() {
    return `
      precision highp float;

      attribute vec3 position;
      attribute float aStartX;
      attribute float aStartY;
      attribute float aEndX;
      attribute float aEndY;
      attribute vec3 aColor;

      uniform float uProgress;
      uniform float uPointSize;
      uniform float uHoldTime;

      varying vec3 vColor;

      float easeOutExpo(float t) {
        return t >= 1.0 ? 1.0 : 1.0 - pow(2.0, -10.0 * t);
      }

      void main() {
        // Hold for uHoldTime, then animate over the remaining timeline.
        float adjustedProgress = max(0.0,
          (uProgress - uHoldTime) / (1.0 - uHoldTime));
        float t = easeOutExpo(clamp(adjustedProgress, 0.0, 1.0));

        // Normalized [0,1] -> NDC [-1,+1]. Y flips: image coords are top-down,
        // WebGL is bottom-up.
        float x = mix(aStartX, aEndX, t) * 2.0 - 1.0;
        float y = -(mix(aStartY, aEndY, t) * 2.0 - 1.0);

        gl_Position = vec4(x, y, 0.0, 1.0);
        gl_PointSize = uPointSize;

        // Particles keep their true source-pixel color for the whole flight.
        vColor = aColor;
      }
    `;
  }

  fragmentShader() {
    return `
      precision mediump float;
      varying vec3 vColor;

      void main() {
        // Circular discard for round particles.
        vec2 coord = gl_PointCoord - 0.5;
        if (dot(coord, coord) > 0.25) discard;
        gl_FragColor = vec4(vColor, 1.0);
      }
    `;
  }

  startAnimation(onComplete) {
    this.startTime = null;
    const duration = CONFIG.ANIMATION_DURATION_MS / 1000; // seconds

    const tick = (timestamp) => {
      if (!this.startTime) this.startTime = timestamp;
      const elapsed = (timestamp - this.startTime) / 1000.0;
      const progress = Math.min(elapsed / duration, 1.0);

      this.mesh.material.uniforms.uProgress.value = progress;
      this.renderer.render(this.scene, this.camera);

      if (progress < 1.0) {
        this.animationId = requestAnimationFrame(tick);
      } else {
        onComplete();
      }
    };

    this.animationId = requestAnimationFrame(tick);
  }

  dispose() {
    if (this.animationId) cancelAnimationFrame(this.animationId);
    if (this.mesh) {
      this.mesh.geometry.dispose();
      this.mesh.material.dispose();
      this.scene.remove(this.mesh);
    }
    if (this.renderer) this.renderer.dispose();
    this.mesh = null;
    this.renderer = null;
  }

  handleResize() {
    if (!this.renderer) return;
    this.renderer.setSize(
      this.canvas.clientWidth,
      this.canvas.clientHeight,
      false
    );
    if (this.mesh) this.renderer.render(this.scene, this.camera);
  }
}

const renderer = new MosaicRenderer(document.getElementById("mosaic-canvas"));

// ===========================================================================
// SECTION 8 — Download
// ===========================================================================
function downloadMosaic() {
  if (!window.mosaicData) return;
  const { endX, endY, colors, count, tgtW, tgtH } = window.mosaicData;

  const offscreen = new OffscreenCanvas(tgtW, tgtH);
  const ctx = offscreen.getContext("2d");
  ctx.fillStyle = "#0a0a0f";
  ctx.fillRect(0, 0, tgtW, tgtH);

  const imageData = ctx.createImageData(tgtW, tgtH);
  const px = imageData.data;

  for (let i = 0; i < count; i++) {
    const x = Math.round(endX[i] * (tgtW - 1));
    const y = Math.round(endY[i] * (tgtH - 1));
    const idx = (y * tgtW + x) * 4;
    px[idx] = Math.round(colors[i * 3] * 255);
    px[idx + 1] = Math.round(colors[i * 3 + 1] * 255);
    px[idx + 2] = Math.round(colors[i * 3 + 2] * 255);
    px[idx + 3] = 255;
  }

  ctx.putImageData(imageData, 0, 0);
  offscreen.convertToBlob({ type: "image/png" }).then((blob) => {
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = "mosaic.png";
    a.click();
    URL.revokeObjectURL(url);
  });
}

// ===========================================================================
// SECTION 6 — Event Binding
// ===========================================================================
document.getElementById("btn-generate").onclick = () => {
  if (currentState !== STATES.READY) return;
  setState(STATES.WORKING);
  connect();
};

document.getElementById("btn-try-again").onclick = () => {
  resetUploads();
  document.querySelector(".canvas-section").style.aspectRatio = ""; // back to idle 16:9
  setState(STATES.EMPTY);
};

document.getElementById("btn-download").onclick = downloadMosaic;

window.addEventListener("resize", () => renderer.handleResize());

initFileInputs();
setState(STATES.EMPTY);