<p align="center">
  <img src=".github/assets/logo.svg" width="96" alt="Pixel Mosaic logo" />
</p>

<h1 align="center">Pixel Mosaic</h1>

<p align="center">
  <strong>Rebuild any image out of another image's pixels</strong> — streamed to your browser
  as a live WebGL particle storm.
</p>

<p align="center">
  <a href="https://shreyasnandurkar.github.io/pixel-mosaic/">
    <img src="https://img.shields.io/badge/%E2%96%B6%20%20Try%20Now-38C8E6?style=for-the-badge" height="44" alt="Try Now" />
  </a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-21-orange?logo=openjdk&logoColor=white" alt="Java 21"/>
  <img src="https://img.shields.io/badge/Spring_Boot-3.2-6DB33F?logo=springboot&logoColor=white" alt="Spring Boot 3.2"/>
  <img src="https://img.shields.io/badge/Three.js-WebGL-000000?logo=threedotjs&logoColor=white" alt="Three.js"/>
  <img src="https://img.shields.io/badge/ONNX_Runtime-U²--Net-005CED?logo=onnx&logoColor=white" alt="ONNX Runtime"/>
  <a href="https://huggingface.co/spaces/shreyasvn/pixel-mosaic"><img src="https://img.shields.io/badge/Hosted_on-Hugging_Face-FFC24B?logo=huggingface&logoColor=black" alt="Hugging Face Space"/></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-4FD08A" alt="MIT License"/></a>
</p>

---

Pick two photos: a **source** and a **target**. Pixel Mosaic finds the subject of each with an
AI saliency model, then rebuilds the target using only pixels taken from the source. Foreground
pixels go to the foreground and background to background, matched by brightness and hue. You
then watch a ten-second animation of every pixel flying into its new place.

No sign-up and nothing to install. Open the page and pick two images.

## ✨ Features

- **GPU particle animation.** Three.js instanced points with custom shaders move every pixel at
  once — up to two million of them in a single draw call. The source holds for a beat, then flies
  into the target's shape.
- **AI subject extraction.** A [U²-Net](https://github.com/xuebinqin/U-2-Net) saliency model on
  ONNX Runtime separates subject from background in both images.
- **Binary streaming protocol.** Over a WebSocket the server sends a 32-byte header, then
  7 bytes per particle in 256 KB chunks.
- **Fair under load.** Two mosaics are built at a time. Everyone else waits in a first-come,
  first-served queue and sees their live position. Sending finished results never holds up
  processing.
- **Hardened for the public internet.** Oversized and decompression-bomb images are rejected
  before decoding, and each client is rate-limited.
- **Light on the upload.** Big photos are scaled down in the browser before they are sent, so a
  10 MB phone picture becomes a few hundred kilobytes.
- **Download your mosaic** as a pixel-exact PNG when the animation finishes.

## 🎬 How it works

```mermaid
flowchart TB
    pick["🌐 <b>Browser</b><br/>pick a source and a target<br/>downscale to ≤2 MP"]

    subgraph server["☁️ Backend · Hugging Face Space"]
        queue["Admission queue<br/>2 running · 8 waiting"]
        src["Source lane<br/>decode → U²-Net mask → pack"]
        tgt["Target lane<br/>decode → U²-Net mask → pack"]
        map["Sort both lanes by subject · brightness · hue<br/>then match pixel to pixel"]
    end

    anim["🌐 <b>Browser</b><br/>Three.js instanced points · 10-second GPU animation"]

    pick -- "WebSocket upload" --> queue
    queue -. "live queue position" .-> pick
    queue -- "in parallel" --> src & tgt
    src & tgt --> map
    map -- "32-byte header + 256 KB binary chunks" --> anim
```

Each pixel is packed into a single 64-bit key — subject/background bit, brightness, hue, a dither
hash, then x and y — in that order of significance. Sorting the keys is the entire matching
algorithm: it lines up source and target pixels that belong together, so subject pixels fill the
target's subject and background pixels fill its background.

The payload is written in the target's raster order, so each particle only needs to carry where it
came from and what colour it is: two 16-bit source coordinates and an RGB triple, 7 bytes.

<details>
<summary>Wire protocol</summary>

```mermaid
sequenceDiagram
    participant B as Browser
    participant S as Server
    B->>S: begin_request (sizes, formats)
    S-->>B: accepted
    B->>S: source image (binary)
    B->>S: target image (binary)
    alt a slot is free
        S-->>B: processing
    else waiting in line
        S-->>B: queued, position N (repeats as the line moves)
        S-->>B: processing
    else line is full
        S-->>B: rejected, queue_full (connection closes)
    end
    S-->>B: 32-byte header (dimensions, particle count)
    S-->>B: particle payload, 7 bytes each, 256 KB chunks
    S-->>B: complete
```
</details>

For the full design — the 64-bit key, the dual-ratio mapping, the shaders, and the concurrency,
memory and safety budgets — see **[ARCHITECTURE.md](ARCHITECTURE.md)**.

## 📏 Limits

| | |
| --- | --- |
| Formats | JPEG, PNG, WebP |
| File size | up to 10 MB per image |
| Resolution | up to 100 MP; anything over 2 MP is scaled down, in the browser where possible |
| Usage | 30 mosaics per hour per client |

The backend runs on a free Hugging Face Space. If it has been idle, the first request can take
up to a minute while it starts.

## 🧱 Tech stack

| Layer | Technology |
| --- | --- |
| Frontend | Three.js (WebGL), vanilla ES modules, GitHub Pages |
| Backend | Java 21, Spring Boot 3.2, WebSocket streaming |
| AI | U²-Net (`u2netp`) saliency model on ONNX Runtime |
| Imaging | TwelveMonkeys + JAI ImageIO (JPEG / PNG / WebP) |
| Infra | Docker, Hugging Face Spaces, GitHub Actions, Caffeine |

## 🐳 Run it yourself

**1. Start the backend.** Use the published image:

```bash
docker run --rm -p 8080:7860 ghcr.io/shreyasnandurkar/pixel-mosaic:latest
```

or build it from source:

```bash
docker build -t pixel-mosaic .
docker run --rm -p 8080:7860 pixel-mosaic
```

**2. Serve the frontend** from `localhost`. It connects to the backend on `localhost:8080`
automatically.

```bash
python -m http.server 5500 -d frontend
```

Then open <http://localhost:5500>. Opening `index.html` directly from disk won't work, because
browsers block ES module imports from `file://`.

<details>
<summary>Prefer plain Maven?</summary>

```bash
mvn spring-boot:run        # backend on http://localhost:8080
mvn test                   # unit + integration tests
```

Requires JDK 21. The U²-Net model ships with the repo (`src/main/resources/models/u2netp.onnx`);
the tests that need it disable themselves if it is missing.
</details>

<details>
<summary>Configuration</summary>

Set these in `src/main/resources/application.yml` or as environment variables.

| Setting | Default | Meaning |
| --- | --- | --- |
| `pixelmosaic.max-concurrent` | `2` | mosaics processed at once; also the buffer-pool size |
| `pixelmosaic.max-queued` | `8` | requests that can wait in line; more are turned away |
| `pixelmosaic.max-streams` | `16` | concurrent payload streams |
| `pixelmosaic.rate-limit-per-hour` | `30` | requests per client per hour |
| `pixelmosaic.max-image-bytes` | `10485760` | per-image upload cap |
| `pixelmosaic.max-pixels` | `2000000` | working resolution per image |
| `pixelmosaic.trusted-proxy-hops` | `0` | proxies in front of the app that append to `X-Forwarded-For`; `0` uses the connection address |
| `pixelmosaic.allowed-origins` | localhost + Pages origin | WebSocket and CORS allowlist |
| `ADMIN_TOKEN` | *(unset)* | enables `GET /admin/stats` (send it in the `X-Admin-Token` header) |

Also available: `GET /health` and `GET /info`.
</details>

## 📁 Project layout

```
frontend/                     static WebGL client (GitHub Pages)
src/main/java/com/pixelmosaic/
  ├── ws/                     WebSocket handler + binary protocol
  ├── pipeline/               decode → mask → pack → sort → map
  ├── admission/              request queue + per-client rate limiting
  ├── stats/                  usage counter
  ├── web/                    health and admin endpoints
  └── config/                 ONNX session, executors, buffer pool
src/test/java/                unit tests + an end-to-end WebSocket round trip
.github/workflows/            Pages deploy, Docker publish, keep-awake ping
Dockerfile                    backend image (Hugging Face Space)
ARCHITECTURE.md               full technical design
```

## 📄 License

Released under the [MIT License](LICENSE) © 2026 Shreyas Nandurkar.

<p align="center"><sub>Made with ♥ — every pixel counts.</sub></p>
