---
title: Pixel Mosaic
emoji: 🧩
colorFrom: indigo
colorTo: purple
sdk: docker
app_port: 7860
pinned: false
---

# Pixel Mosaic — backend

Reconstructs a target image's shape using only the pixels of a source image, and
streams the result to the browser as a WebGL particle animation.

This Space runs the **backend only** (Java 21 + Spring Boot, WebSocket at
`/ws/mosaic`). The frontend is hosted separately on GitHub Pages and connects to
this Space over `wss://`.

## Pipeline

decode → U²-Net saliency mask → bitwise pixel pack → concurrent dual-lane
sort/map → chunked binary stream.

## Run locally

```
mvn spring-boot:run          # backend on http://localhost:8080
```

Then open `frontend/index.html` (it falls back to the local backend automatically).

## Deploy

- **Backend (this Space):** Docker SDK builds the `Dockerfile` and serves on port 7860.
- **Frontend:** GitHub Pages; set `WS_URL` in `frontend/app.js` to this Space's
  `wss://<user>-<space>.hf.space/ws/mosaic`.
