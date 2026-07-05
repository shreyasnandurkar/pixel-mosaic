# Pixel Mosaic — Complete Project Reference Document

This is the authoritative pre-implementation document. Everything decided across this entire design process is consolidated here. If something is not mentioned here, it has not been decided and needs to be before that piece is coded.

---

# Part 1 — What This Project Is

## The Concept

Pixel Mosaic is an image transformation system. It takes two images as input — a **Source** and a **Target** — and reconstructs the Target's visual shape and structure using exclusively the pixels belonging to the Source image. No new colors are invented. No blending occurs. Every pixel in the output is a real pixel taken from the Source, repositioned to form the Target's silhouette.

The name for this technique is a **pixel mosaic**: the Source image is the palette of tiles, and the Target image provides the blueprint of where each tile goes.

## What It Produces

The system produces two things:

1. **A 5-second WebGL animation** showing every pixel simultaneously flying from its original position in the Source image to its final mapped position on the Target's shape. This is the primary deliverable — the visual experience that communicates what the algorithm did.

2. **A downloadable PNG** of the final mosaic frame — the static result of the transformation, which users can save.

## The Artistic Behaviour

The artistic output is intentional and accepted:

- The Target's **structural shape** is preserved with pixel-perfect fidelity. Every coordinate in the target receives exactly one source pixel. Silhouettes, edges, and gradients are intact.
- The Source image's **spatial arrangement is completely destroyed**. Pixels from the source are sorted, scrambled, and redistributed by brightness and color. The source's original structure is gone; only its color palette survives.
- The Source's **color distribution is statistically preserved** within foreground and background lanes. Bright source pixels map to bright target regions; dark to dark — within each semantic group.
- If the source is a forest and the target is a portrait, the portrait will look like it is made of foliage. This is the accepted "made of foliage" aesthetic. It is not a bug.

## Why This Project Exists

This is a **resume project** built to demonstrate engineering depth. The algorithm — not the UI — is the primary technical contribution. The frontend exists to make that algorithm visible, legible, and impressive to a technical reviewer. The animation transforms an abstract sorting algorithm into something a person can watch and understand intuitively in five seconds.

---

# Part 2 — System Architecture Overview

## Components

The system has four components:

```
┌─────────────────────────────────────────────────────────────────┐
│                         CLIENT (Browser)                         │
│                                                                  │
│   GitHub Pages                                                   │
│   ├─ index.html + style.css + app.js                            │
│   └─ Three.js (CDN import)                                       │
│        └─ WebGL InstancedMesh renderer                           │
└──────────────────────────────┬──────────────────────────────────┘
                               │ WebSocket (wss://)
                               │ Cloudflare proxy
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│                     BACKEND (Oracle Ampere A1)                   │
│                                                                  │
│   Java 21 — Spring Boot — Embedded Tomcat                        │
│   ├─ WebSocket endpoint                                          │
│   ├─ Admission control (Semaphore + Rate limiter)                │
│   ├─ Image processing pipeline                                   │
│   │    ├─ TwelveMonkeys image decoder                           │
│   │    ├─ ONNX Runtime (U²-NetP, singleton session)             │
│   │    ├─ Bitwise pixel packer                                   │
│   │    ├─ Arrays.sort() (dual-pivot quicksort)                  │
│   │    └─ Dual-ratio mapping engine                              │
│   └─ WebSocket binary streamer (chunked)                         │
└─────────────────────────────────────────────────────────────────┘

Infrastructure:
  ├─ Cloudflare: DNS, TLS termination, DDoS protection
  ├─ Oracle Always Free: 4 OCPU Ampere A1, 24 GB RAM
  └─ GitHub Pages: static frontend hosting
```

## Technology Decisions (Final)

| Concern | Choice | Reason |
|---|---|---|
| Language | Java 21 | JDK 21 virtual threads, primitive arrays, Arrays.sort |
| Framework | Spring Boot 3 | WebSocket support, embedded Tomcat |
| Image decoding | TwelveMonkeys ImageIO | No `getRGB()`, fast raster access |
| Segmentation model | U²-NetP via ONNX Runtime | 4.7 MB, aarch64-compatible, saliency-trained |
| Memory strategy | Pre-allocated primitive arrays | Zero GC on hot path |
| Admission | Semaphore(2) | Protects free-tier VM from abuse |
| Rate limiting | Caffeine in-process | 5 req/IP/hour, no Redis needed |
| Frontend | Vanilla JS + Three.js | No framework overhead |
| Rendering | Three.js InstancedMesh | 1M+ particles at 60 FPS |
| Transport | WebSocket binary, 256 KB chunks | Streaming 16 MB payload |
| Deploy: backend | Oracle Always Free Ampere A1 | 24 GB RAM, always free |
| Deploy: frontend | GitHub Pages | Free, standard portfolio choice |
| Proxy | Cloudflare free tier | TLS + DDoS + DNS |

---

# Part 3 — The Data Pipeline In Full Depth

This is the core of the project. Every design decision below has been reviewed, critiqued, and confirmed across the design process. This is the canonical description.

## The Two 64-Bit Data Structures

Every pixel in both images is packed into a single 64-bit `long`. This eliminates object allocation on the hot path and makes `Arrays.sort()` operate on primitives — no comparator overhead, no boxing, no GC.

Two **distinct** schemas exist. They share the same sort-key structure in the upper 32 bits but differ in the lower 32 bits because each stores different payload data.

### Source Long — `[1|7|16|8|16|16]`

```
Bit 63        : FG/BG flag (1 = foreground, 0 = background)
Bits 62–56    : Luminance (7 bits, 0–127)
Bits 55–40    : Hue (16 bits, 0–65535; achromatic sentinel = 65535)
Bits 39–32    : Spatial hash (8 bits)
Bits 31–16    : Source pixel X coordinate (16 bits, max 65535)
Bits 15–0     : Source pixel Y coordinate (16 bits, max 65535)
```

Source RGB is **not packed into the long**. It is recovered at serialization time by looking up `(srcX, srcY)` in the still-resident decoded source raster buffer. This eliminates 24 bits of wasted sort-key space and makes the layout clean.

### Target Long — `[1|7|16|8|16|16]`

```
Bit 63        : FG/BG flag (1 = foreground, 0 = background)
Bits 62–56    : Luminance (7 bits, 0–127)
Bits 55–40    : Hue (16 bits, 0–65535; achromatic sentinel = 65535)
Bits 39–32    : Spatial hash (8 bits)
Bits 31–16    : Target pixel X coordinate (16 bits)
Bits 15–0     : Target pixel Y coordinate (16 bits)
```

Structurally identical to source. The meaning of the lower 32 bits differs — these are the *destination* coordinates on the output canvas.

### Why This Layout Works

Sorting a `long[]` in ascending order processes the bits from MSB to LSB as a single unsigned 63-bit integer (Java's `long` is signed but `Arrays.sort` handles negatives correctly by natural ordering — positive first, then negatives would be a problem, but all values here are positive since bit 63 = 0 for BG and 1 for FG).

Wait — bit 63 being 1 makes the value negative in Java's signed long. This means background pixels (bit 63 = 0) sort **before** foreground pixels (bit 63 = 1) because negative numbers sort after positive numbers in Java's signed arithmetic? No — `Arrays.sort(long[])` sorts numerically. In Java, `long` with bit 63 set is negative. Negative values sort before positive. So FG (negative) sorts before BG (positive)?

Actually: 0-prefixed longs (BG, bit 63 = 0) are non-negative. 1-prefixed longs (FG, bit 63 = 1) are negative. `Arrays.sort` puts negatives first. So **FG pixels sort before BG pixels**.

This is fine — it's a consistent partition. The split point is where values transition from negative to non-negative. Document this explicitly in code comments so it's not surprising.

Within FG: sorted by luminance (bits 62–56), then hue (55–40), then spatial hash (39–32), then coordinates (31–0). Within BG: same order. The sort perfectly clusters semantically similar pixels within each lane.

## The Pixel Fields — Precise Computation

### Relative Luminance (7 bits)

```java
float Y = 0.2126f * r + 0.7152f * g + 0.0722f * b;  // [0, 255]
int lum = (int)(Y / 255.0f * 127.0f);                // [0, 127]
```

Uses the ITU-R BT.709 coefficients for perceptual accuracy. This is relative luminance, not simple average.

### Hue (16 bits, with achromatic handling)

Convert RGB to HSV. Extract H:

```java
float r = R / 255f, g = G / 255f, b = B / 255f;
float max = Math.max(r, Math.max(g, b));
float min = Math.min(r, Math.min(g, b));
float delta = max - min;

if (delta < 0.04f) {
    // Achromatic: saturation below threshold
    // Sentinel value that sorts outside chromatic range
    hue16 = 65535;
} else {
    float h;
    if (max == r)      h = 60f * (((g - b) / delta) % 6f);
    else if (max == g) h = 60f * (((b - r) / delta) + 2f);
    else               h = 60f * (((r - g) / delta) + 4f);
    if (h < 0) h += 360f;
    hue16 = (int)(h / 360f * 65534f);  // [0, 65534], 65535 reserved
}
```

Achromatic pixels (grays, blacks, whites) get sentinel hue 65535. This sorts them *after* all chromatic pixels within their luminance band, preventing gray pixels from colliding with red pixels (both would otherwise have hue ≈ 0).

### Spatial Hash (8 bits)

Simple multiplicative hash for deterministic tiebreaking within identical (FG, luminance, hue) values:

```java
int hash = ((x * 2654435761) ^ (y * 40503)) & 0xFF;
```

Two lines, no lookup table, distributes uniformly. Wrapped in `Hash.spatial8(int x, int y)` static method for testability and swappability.

### Packing (both layouts)

```java
// Source
long srcWord = ((long)(fgBit  & 0x1)    << 63)
             | ((long)(lum    & 0x7F)   << 56)
             | ((long)(hue16  & 0xFFFF) << 40)
             | ((long)(hash   & 0xFF)   << 32)
             | ((long)(x      & 0xFFFF) << 16)
             | ((long)(y      & 0xFFFF));

// Target (identical structure, different semantic for lower 32 bits)
long tgtWord = ((long)(fgBit  & 0x1)    << 63)
             | ((long)(lum    & 0x7F)   << 56)
             | ((long)(hue16  & 0xFFFF) << 40)
             | ((long)(hash   & 0xFF)   << 32)
             | ((long)(x      & 0xFFFF) << 16)
             | ((long)(y      & 0xFFFF));
```

## The Complete Processing Pipeline — Stage by Stage

### Stage 0 — Server Startup (Once)

Before any request is served:

1. `OrtEnvironment` instantiated as a JVM-wide singleton.
2. `OrtSession` created from `u2netp.onnx` with `intra_op=1, inter_op=1, ALL_OPT`. Loaded once, shared across all requests, thread-safe for `run()` calls.
3. `BufferPool` instantiated with 2 pre-allocated `RequestBuffers` objects. Each holds:
    - `long[] sourceData` sized to `maxPixels = 2_000_000`
    - `long[] targetData` sized to `maxPixels = 2_000_000`
    - `BitSet sourceMask` sized to `maxPixels`
    - `BitSet targetMask` sized to `maxPixels`
    - `int[] sourceRaster` sized to `maxPixels` (ARGB ints)
    - `int[] targetRaster` sized to `maxPixels` (ARGB ints)
4. `Semaphore(2, fair=true)` instantiated.
5. Caffeine rate limiter instantiated: 100K max IPs, expire-after-write 1 hour.
6. Spring Boot WebSocket endpoint registered.

Startup time: ~300–500 ms (dominated by ONNX session creation).

### Stage 1 — WebSocket Connection & Admission (Per Request)

Client connects. On the Tomcat I/O thread (outside semaphore):

**1a. Origin check.** Reject if `Origin` header is not your frontend domain. Close with code 1008.

**1b. Rate limit check.**
```java
AtomicInteger count = rateLimiter.get(clientIp, k -> new AtomicInteger(0));
if (count.incrementAndGet() > 5) {
    session.close(new CloseStatus(1008, "rate limit exceeded"));
    return;
}
```

**1c. Receive begin_request control frame (JSON):**
```json
{
  "type": "begin_request",
  "source_bytes": 4823100,
  "target_bytes": 3145728,
  "source_format": "image/jpeg",
  "target_format": "image/png"
}
```

Validate: formats in `{image/jpeg, image/png, image/webp}`, sizes ≤ 10 MB each. Reject outside bounds.

**1d. Receive image binary frames.** Two sequential binary WebSocket frames: source image bytes, then target image bytes. Accumulated into heap `byte[]` arrays (these are small and short-lived; pool not worth it).

**1e. Semaphore acquisition.**
```java
if (!semaphore.tryAcquire()) {
    session.sendMessage(new TextMessage(
        "{\"type\":\"rejected\",\"reason\":\"server_busy\"}"));
    session.close(CloseStatus.SERVICE_RESTARTED);
    return;
}
```

Immediate acquire-or-reject. No wait queue for resume project.

**1f. Buffer pool checkout.**
```java
RequestBuffers buf = bufferPool.acquire(); // never blocks — pool size == semaphore permits
```

The request now holds one semaphore slot and one buffer set. From this point forward, both must be returned in a `finally` block.

### Stage 2 — Parallel Fork: Decode + Mask + Pack

Two `CompletableFuture`s launched on a bounded `ExecutorService` (core=4, max=4, queue=10):

```java
CompletableFuture<Integer> srcFuture = CompletableFuture.supplyAsync(
    () -> processImage(sourceBytes, buf.sourceRaster, buf.sourceMask, buf.sourceData),
    processingPool);

CompletableFuture<Integer> tgtFuture = CompletableFuture.supplyAsync(
    () -> processImage(targetBytes, buf.targetRaster, buf.targetMask, buf.targetData),
    processingPool);
```

Each branch runs identically:

**2a. Decode.** TwelveMonkeys `ImageIO.read()` from `ByteArrayInputStream`. Access raster via `WritableRaster raster = image.getRaster()`, then `DataBufferInt db = (DataBufferInt) raster.getDataBuffer()`, then `int[] pixels = db.getData()`. Copy into `buf.sourceRaster` or `buf.targetRaster` directly.

**2b. Resolution cap.** If `width * height > 2_000_000`: compute scale factor, resize via bilinear interpolation into the pre-allocated raster. Track actual `srcWidth, srcHeight, srcPixelCount` for later.

**2c. ONNX inference.** Resize raster to 320×320, normalize to float `[0,1]`, apply ImageNet mean/std. Load into pre-allocated `FloatBuffer`. Create `OnnxTensor` from the float buffer.

```java
Map<String, OnnxTensor> inputs = Map.of("input", inputTensor);
OrtSession.Result result = sharedSession.run(inputs);
OnnxTensor output = (OnnxTensor) result.get(0);
FloatBuffer maskBuf = output.getFloatBuffer(); // direct read, no heap copy
```

**2d. Mask upsampling and thresholding.** For each pixel `(px, py)` in the original (possibly resized) image, compute the corresponding coordinate `(mx, my)` in the 320×320 mask. Read `maskBuf.get(my * 320 + mx)`. If `> 0.5f`, set bit in `BitSet`. This is the only place where a soft-threshold matters — for hard binary masks, 0.5 is fine. Morphological cleanup (one pass of open/close with 3×3 kernel) to remove speckle.

**2e. Pixel packing.** Walk every pixel `(x, y)` in the (resized) image:

```java
int argb = rasterInts[y * width + x];
int r = (argb >> 16) & 0xFF;
int g = (argb >> 8)  & 0xFF;
int b =  argb        & 0xFF;

int lum    = computeLuminance(r, g, b);    // 7-bit
int hue16  = computeHue(r, g, b);          // 16-bit with achromatic sentinel
int hash   = Hash.spatial8(x, y);          // 8-bit
int fgBit  = mask.get(y * width + x) ? 1 : 0;

long word = packSource(fgBit, lum, hue16, hash, x, y);
sourceData[pixelIndex++] = word;
```

Branch result: actual pixel count written.

### Stage 3 — Join + Sort

```java
int srcLen = srcFuture.get();
int tgtLen = tgtFuture.get();
// Both branches complete. sourceData[0..srcLen) and targetData[0..tgtLen) populated.
```

Sort both arrays concurrently:

```java
CompletableFuture<Void> sortSrc = CompletableFuture.runAsync(
    () -> Arrays.sort(buf.sourceData, 0, srcLen), processingPool);
CompletableFuture<Void> sortTgt = CompletableFuture.runAsync(
    () -> Arrays.sort(buf.targetData, 0, tgtLen), processingPool);
CompletableFuture.allOf(sortSrc, sortTgt).get();
```

`Arrays.sort(long[])` is dual-pivot quicksort, single-threaded per call as mandated. Two arrays sort in parallel on two cores. Wall-clock: ~80–110 ms.

After sort:
- `buf.sourceData[0..srcSplit)` = FG pixels (negative longs, bit 63 = 1), sorted by `[lum | hue | hash | x | y]`
- `buf.sourceData[srcSplit..srcLen)` = BG pixels (non-negative longs), same sort order
- Mirrored for target array

Wait — re-examine. Bit 63 = 1 makes the long negative. `Arrays.sort` puts negatives (smaller values) first. So FG (bit 63=1, negative) sorts *before* BG (bit 63=0, non-negative). FG occupies `[0, split)`, BG occupies `[split, len)`.

Within FG: sorted ascending by the remaining 63 bits, meaning: darker FG pixels come first (lower luminance → smaller remaining value), lighter FG pixels later.

### Stage 4 — Split Detection

```java
// Find where values transition from negative to non-negative
int srcSplit = findSplit(buf.sourceData, srcLen);
int tgtSplit = findSplit(buf.targetData, tgtLen);

int findSplit(long[] arr, int len) {
    // Binary search for first non-negative value
    int lo = 0, hi = len;
    while (lo < hi) {
        int mid = (lo + hi) >>> 1;
        if (arr[mid] < 0) lo = mid + 1;
        else hi = mid;
    }
    return lo; // all arr[0..lo) are negative (FG), arr[lo..len) non-negative (BG)
}
```

~3 ms.

### Stage 5 — Degenerate Lane Detection & Ratio Computation

Check for degenerate cases:

```java
boolean srcHasFG = srcSplit > 0;
boolean srcHasBG = srcSplit < srcLen;
boolean tgtHasFG = tgtSplit > 0;
boolean tgtHasBG = tgtSplit < tgtLen;

if (!tgtHasFG || !tgtHasBG || !srcHasFG || !srcHasBG) {
    // Degenerate: fall back to single-lane mapping
    // Use entire source mapped to entire target with one ratio
    singleLaneFallback(buf, srcLen, tgtLen);
    return;
}
```

Normal path:

```java
// FG lane: source [0..srcSplit) → target [0..tgtSplit)
float fgRatio = (float) srcSplit / (float) tgtSplit;

// BG lane: source [srcSplit..srcLen) → target [tgtSplit..tgtLen)
float bgRatio = (float)(srcLen - srcSplit) / (float)(tgtLen - tgtSplit);
```

Warn if `fgRatio < 0.01f` or `fgRatio > 100f` — extreme ratios mean one image has almost no foreground, which produces poor visual output. Log the warning; don't abort.

### Stage 6 — Dual-Ratio Mapping

Single-threaded O(N) loop over target array. For each target pixel, find its source pixel:

```java
ByteBuffer output = ByteBuffer.allocateDirect(tgtLen * 8);

for (int i = 0; i < tgtLen; i++) {
    long tgt = buf.targetData[i];

    // Determine lane and compute source index
    int srcIdx;
    if (i < tgtSplit) {
        // FG lane
        srcIdx = (int)(i * fgRatio);
        srcIdx = Math.min(srcIdx, srcSplit - 1); // clamp for float imprecision
    } else {
        // BG lane
        int bgTargetOffset = i - tgtSplit;
        srcIdx = srcSplit + (int)(bgTargetOffset * bgRatio);
        srcIdx = Math.min(srcIdx, srcLen - 1);   // clamp
    }

    long src = buf.sourceData[srcIdx];

    // Unpack source coordinates
    int srcX = (int)((src >> 16) & 0xFFFF);
    int srcY = (int)(src & 0xFFFF);

    // Look up source RGB from original raster
    int argb = buf.sourceRaster[srcY * srcWidth + srcX];
    byte r = (byte)((argb >> 16) & 0xFF);
    byte g = (byte)((argb >> 8)  & 0xFF);
    byte bv = (byte)(argb & 0xFF);

    // Unpack target coordinates
    // Target X/Y derived from raster position — no need to decode from long
    // since target array is in raster scan order within each lane... 
    // Actually: target pixels were packed in raster order initially, 
    // but sort scrambles them. Must decode X/Y from the long.
    int tgtX = (int)((tgt >> 16) & 0xFFFF);
    int tgtY = (int)(tgt & 0xFFFF);

    // Write 8 bytes to output
    output.putShort((short) srcX);
    output.putShort((short) srcY);
    output.put(r).put(g).put(bv);
    output.put((byte) 0); // reserved / alpha
}

output.flip();
```

**Important note on target coordinates:** after sorting, target pixels are in luminance/hue order, not raster order. The frontend cannot derive `(endX, endY)` from the particle index. Target X and Y must be read from the packed long. This is correct in the layout above — they live in bits 31–16 and 15–0.

**So why are endX/endY in the output if they're in the long?** Because the client doesn't receive the longs — it receives the 8-byte output. The output explicitly includes `srcX, srcY` (start position of the animation) and `r, g, b` (color). The end position `tgtX, tgtY` must also be sent, which means the 8-byte layout needs to grow.

**Corrected output layout — 12 bytes per particle:**

```
Bytes 0–1:  srcX  (uint16) — animation start X
Bytes 2–3:  srcY  (uint16) — animation start Y
Bytes 4–5:  tgtX  (uint16) — animation end X
Bytes 6–7:  tgtY  (uint16) — animation end Y
Bytes 8–10: R, G, B        — particle color
Byte  11:   reserved
```

Total payload: `tgtLen * 12 bytes`. At 2M particles: **24 MB uncompressed**. With `permessage-deflate`: ~10–12 MB on the wire for typical images.

Output `ByteBuffer` is allocated as direct memory: `ByteBuffer.allocateDirect(tgtLen * 12)`.

### Stage 7 — Slot Release + Stream

```java
} finally {
    buf.reset();               // clear BitSets, zero position counters
    bufferPool.release(buf);   // return arrays to pool
    semaphore.release();       // free processing slot
}

// Output ByteBuffer now belongs to the WebSocket session
// Streaming happens here, outside the semaphore
streamPayload(session, output, srcWidth, srcHeight, tgtWidth, tgtHeight, tgtLen);
```

The 32-byte header is sent first as a binary frame:

```java
ByteBuffer header = ByteBuffer.allocate(32);
header.putInt(0x4D4F5301);  // magic: "MOS\x01"
header.putInt(1);           // version
header.putInt(tgtLen);      // particle count
header.putInt(srcWidth);
header.putInt(srcHeight);
header.putInt(tgtWidth);
header.putInt(tgtHeight);
header.putInt(0);           // reserved
header.flip();
session.sendMessage(new BinaryMessage(header));
```

Then chunked payload:

```java
final int CHUNK = 256 * 1024; // 256 KB
while (output.hasRemaining()) {
    int size = Math.min(CHUNK, output.remaining());
    ByteBuffer chunk = output.slice(output.position(), size);
    output.position(output.position() + size);
    session.sendMessage(new BinaryMessage(chunk, !output.hasRemaining()));
}
```

After last chunk, send completion frame:

```json
{"type":"complete","particle_count":2073600}
```

Release the output `ByteBuffer` — direct memory freed when GC collects the reference.

---

# Part 4 — The Frontend Pipeline

## Wire Protocol

```
CLIENT → SERVER (3 frames)
  Frame 1 (text):   begin_request JSON
  Frame 2 (binary): source image bytes
  Frame 3 (binary): target image bytes

SERVER → CLIENT (N frames)
  Frame 1 (text):   accepted/rejected JSON
  Frame 2 (binary): 32-byte header
  Frame 3..N (binary): 256 KB payload chunks (permessage-deflate applied)
  Frame N+1 (text): complete JSON
```

## Frontend State Machine

Six states, managed in plain JavaScript:

```
EMPTY → READY → WORKING → ANIMATING → DONE
                   ↓
                 ERROR
```

- **EMPTY:** Both upload zones shown in canvas area.
- **READY:** Both images chosen, thumbnails shown, Generate button enabled.
- **WORKING:** WebSocket open, processing + receiving chunks. Real progress bar from `bytesReceived / (particleCount * 12)`. Spinner during the pre-chunk compute phase.
- **ANIMATING:** 5-second WebGL animation plays. All controls disabled.
- **DONE:** Final frame visible. Download button enabled.
- **ERROR:** Message shown. "Try again" button resets to EMPTY.

## WebGL Renderer — Three.js InstancedMesh

### Buffer construction

On first `complete` frame receipt (all chunks assembled):

```javascript
const count = particleCount;

// Typed arrays assembled from reassembled payload
const startX = new Float32Array(count);
const startY = new Float32Array(count);
const endX   = new Float32Array(count);
const endY   = new Float32Array(count);
const colors = new Float32Array(count * 3); // RGB normalized

// Parse binary payload
const view = new DataView(reassembledBuffer);
for (let i = 0; i < count; i++) {
    const off = i * 12;
    startX[i] = view.getUint16(off,     true) / srcWidth;   // normalize [0,1]
    startY[i] = view.getUint16(off + 2, true) / srcHeight;
    endX[i]   = view.getUint16(off + 4, true) / tgtWidth;
    endY[i]   = view.getUint16(off + 6, true) / tgtHeight;
    colors[i * 3]     = view.getUint8(off + 8)  / 255;
    colors[i * 3 + 1] = view.getUint8(off + 9)  / 255;
    colors[i * 3 + 2] = view.getUint8(off + 10) / 255;
}
```

### Three.js setup

```javascript
const geometry = new THREE.InstancedBufferGeometry();
geometry.setAttribute('position',
    new THREE.BufferAttribute(new Float32Array([0,0,0]), 3)); // single point

geometry.setAttribute('aStartX', new THREE.InstancedBufferAttribute(startX, 1));
geometry.setAttribute('aStartY', new THREE.InstancedBufferAttribute(startY, 1));
geometry.setAttribute('aEndX',   new THREE.InstancedBufferAttribute(endX, 1));
geometry.setAttribute('aEndY',   new THREE.InstancedBufferAttribute(endY, 1));
geometry.setAttribute('aColor',  new THREE.InstancedBufferAttribute(colors, 3));

const material = new THREE.RawShaderMaterial({
    uniforms: {
        uProgress: { value: 0.0 },
        uPointSize: { value: 1.5 }
    },
    vertexShader: VERT,
    fragmentShader: FRAG,
    transparent: false,
    depthTest: false
});

const mesh = new THREE.Mesh(geometry, material);
scene.add(mesh);
```

### Vertex shader

```glsl
precision highp float;

attribute vec3 position;
attribute float aStartX;
attribute float aStartY;
attribute float aEndX;
attribute float aEndY;
attribute vec3 aColor;

uniform float uProgress;    // 0.0 to 1.0 over 5 seconds
uniform float uPointSize;

varying vec3 vColor;

float easeOutExpo(float t) {
    return t >= 1.0 ? 1.0 : 1.0 - pow(2.0, -10.0 * t);
}

void main() {
    float t = easeOutExpo(uProgress);

    // Normalized [0,1] coords → NDC [-1,+1]
    float x = mix(aStartX, aEndX, t) * 2.0 - 1.0;
    float y = -(mix(aStartY, aEndY, t) * 2.0 - 1.0); // flip Y axis

    gl_Position = vec4(x, y, 0.0, 1.0);
    gl_PointSize = uPointSize;

    // Subtle brightness pulse: peak at t=0.5
    float brightness = 1.0 + 0.3 * sin(uProgress * 3.14159);
    vColor = aColor * brightness;
}
```

### Fragment shader

```glsl
precision highp float;
varying vec3 vColor;

void main() {
    // Circular point discard for soft particles
    vec2 coord = gl_PointCoord - 0.5;
    if (dot(coord, coord) > 0.25) discard;
    gl_FragColor = vec4(vColor, 1.0);
}
```

### Animation loop

```javascript
const DURATION = 5.0;       // seconds
let startTime = null;

function animate(timestamp) {
    if (!startTime) startTime = timestamp;
    const elapsed = (timestamp - startTime) / 1000.0;
    const progress = Math.min(elapsed / DURATION, 1.0);

    material.uniforms.uProgress.value = progress;
    renderer.render(scene, camera);

    if (progress < 1.0) {
        requestAnimationFrame(animate);
    } else {
        onAnimationComplete();
    }
}

requestAnimationFrame(animate);
```

## Download

On animation complete, render final state to a 2D canvas:

```javascript
function downloadMosaic() {
    const offscreen = new OffscreenCanvas(tgtWidth, tgtHeight);
    const ctx = offscreen.getContext('2d');
    const imageData = ctx.createImageData(tgtWidth, tgtHeight);
    const data = imageData.data;

    // Place each particle at its final position
    for (let i = 0; i < particleCount; i++) {
        const px = Math.round(endX[i] * tgtWidth);
        const py = Math.round(endY[i] * tgtHeight);
        const idx = (py * tgtWidth + px) * 4;
        data[idx]     = Math.round(colors[i*3]   * 255);
        data[idx + 1] = Math.round(colors[i*3+1] * 255);
        data[idx + 2] = Math.round(colors[i*3+2] * 255);
        data[idx + 3] = 255;
    }

    ctx.putImageData(imageData, 0, 0);
    offscreen.convertToBlob({ type: 'image/png' }).then(blob => {
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url; a.download = 'mosaic.png'; a.click();
        URL.revokeObjectURL(url);
    });
}
```

---

# Part 5 — Resolved Design Decisions

Every decision that was explicitly debated and settled:

| Decision | Resolution |
|---|---|
| Multi-class semantic routing | Rejected. Binary FG/BG only. |
| "Made of foliage" aesthetic | Accepted. Not a bug. |
| Parallel array (`sourceStartCoords`) | Rejected. Source XY packed inline in source long. |
| Buffer pooling | Size 2 (resume project). Not 7. |
| `Arrays.parallelSort` | Rejected. Single-threaded sort per array. Two arrays sorted concurrently via futures. |
| Otsu thresholding | Rejected as primary segmentation. Acceptable on ONNX soft mask output only. |
| Redis | Rejected. Caffeine in-process only. |
| Nginx | Rejected. Spring Boot embedded Tomcat serves directly. |
| MediaRecorder / MP4 export | Rejected. PNG download only. |
| Replay button | Rejected. |
| Semaphore queue with 2s wait | Rejected. Immediate acquire-or-reject. |
| `getRGB()` | Banned. Raster DataBuffer direct access only. |
| `float[][]` ONNX output | Banned. `tensor.getFloatBuffer()` direct read only. |
| ORT session per request | Banned. Singleton session at startup only. |
| Achromatic hue | Sentinel value 65535, sorts after all chromatic pixels. |
| Spatial hash | `((x * 2654435761) ^ (y * 40503)) & 0xFF`. |
| Particle initial position | Source pixel's normalized `(srcX/srcW, srcY/srcH)` mapped to canvas space. |
| Canvas coordinate system | Normalized `[0,1]` sent in payload; shader converts to NDC. |
| Easing curve | `1.0 - pow(2.0, -10.0 * t)` ease-out-expo, 5 seconds. |
| Particle visual style | Round dots, 1.5 px, circular discard in fragment shader. |
| Brightness behaviour | Pulse: `1.0 + 0.3 * sin(progress * π)` peaking at t=0.5. |
| Animation hold at t=0 | 200ms hold before motion begins. |
| Deployment | Oracle Always Free Ampere A1. 4 OCPU / 24 GB. |
| Frontend hosting | GitHub Pages. |
| Proxy | Cloudflare free tier. |
| Frontend framework | Vanilla JS + Three.js. No React/Vue. |

---

# Part 6 — Memory Budget (Final)

## At Full Load (2 concurrent requests)

### Heap

| Item | Memory |
|---|---|
| Buffer pool × 2 slots (`long[]` × 2, `int[]` × 2, BitSet × 2, per slot) | 2 × 48.5 MB = **97 MB** |
| Inbound image bytes (per-request, transient) | 2 × 20 MB = **40 MB** |
| JVM + Spring framework baseline | **400 MB** |
| Caffeine rate limiter (100K IPs) | **8 MB** |
| Thread stacks (50 Tomcat + 4 processing + 8 I/O × 256 KB) | **16 MB** |
| Misc (request metadata, futures, objects) | **10 MB** |
| **Total heap working set** | **~571 MB** |
| **With G1 30% headroom** | **~743 MB** |
| **-Xmx setting** | **1 GB** |

### Direct Memory

| Item | Memory |
|---|---|
| Output ByteBuffers (2 × 24 MB, potentially overlapping with streaming) | **48 MB** |
| ONNX session + model resident | **80 MB** |
| ONNX input FloatBuffers (2 × 1.2 MB) | **2.4 MB** |
| WebSocket send buffers (1 MB per active connection, max 50) | **50 MB** |
| **Total direct working set** | **~180 MB** |
| **-XX:MaxDirectMemorySize** | **384 MB** |

### Total JVM process RSS

~1 GB heap + 384 MB direct + 200 MB JVM native overhead = **~1.6 GB**. On Oracle Ampere's 24 GB: **~7% utilization**.

