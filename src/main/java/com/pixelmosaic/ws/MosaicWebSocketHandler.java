package com.pixelmosaic.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixelmosaic.admission.RateLimiterService;
import com.pixelmosaic.pipeline.MosaicPipeline;
import com.pixelmosaic.pipeline.MosaicResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;

/**
 * WebSocket protocol for one mosaic request:
 * <ol>
 *   <li>client sends a {@code begin_request} JSON control frame (declared sizes + format);</li>
 *   <li>server replies {@code accepted} with a request id;</li>
 *   <li>client sends the source image as one binary frame, then the target image;</li>
 *   <li>server processes off the I/O thread, then streams a 32-byte binary header, the
 *       particle payload in 256&nbsp;KB chunks, and a final {@code complete} JSON frame.</li>
 * </ol>
 *
 * Admission is two-layered: per-IP hourly rate limiting at connect time, and a global
 * concurrency semaphore at processing time (excess requests are rejected as {@code server_busy}).
 */
@Component
public class MosaicWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(MosaicWebSocketHandler.class);

    /** Binary protocol header: magic bytes "MOS\1". */
    private static final int MAGIC = 0x4D4F5301;
    private static final int PROTOCOL_VERSION = 1;
    private static final int HEADER_BYTES = 32;

    private static final String ATTR_STATE = "state";
    private static final String ATTR_SOURCE = "sourceBytes";
    private static final String ATTR_TARGET = "targetBytes";
    private static final String ATTR_REQUEST_ID = "requestId";

    private static final Set<String> ALLOWED_FORMATS =
            Set.of("image/jpeg", "image/png", "image/webp");

    enum State {AWAITING_BEGIN, AWAITING_SOURCE, AWAITING_TARGET, PROCESSING}

    private final Semaphore admissionSemaphore;
    private final RateLimiterService rateLimiter;
    private final MosaicPipeline pipeline;
    private final ExecutorService requestExecutor;
    private final ObjectMapper objectMapper;
    private final int chunkSize;
    private final long maxImageBytes;
    private final boolean behindProxy;

    public MosaicWebSocketHandler(Semaphore admissionSemaphore,
                                  RateLimiterService rateLimiter,
                                  MosaicPipeline pipeline,
                                  @Qualifier("requestExecutor") ExecutorService requestExecutor,
                                  ObjectMapper objectMapper,
                                  @Value("${pixelmosaic.chunk-size-bytes}") int chunkSize,
                                  @Value("${pixelmosaic.max-image-bytes}") long maxImageBytes,
                                  @Value("${pixelmosaic.behind-proxy}") boolean behindProxy) {
        this.admissionSemaphore = admissionSemaphore;
        this.rateLimiter = rateLimiter;
        this.pipeline = pipeline;
        this.requestExecutor = requestExecutor;
        this.objectMapper = objectMapper;
        this.chunkSize = chunkSize;
        this.maxImageBytes = maxImageBytes;
        this.behindProxy = behindProxy;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String ip = clientIp(session);
        if (ip == null) {
            log.warn("Connection {}: no client IP resolved; skipping rate limit", session.getId());
        } else if (!rateLimiter.tryAcquire(ip)) {
            log.info("Connection {} from {} rejected: rate limit exceeded", session.getId(), ip);
            session.close(new CloseStatus(1008, "rate_limit_exceeded"));
            return;
        }
        session.getAttributes().put(ATTR_STATE, State.AWAITING_BEGIN);
        log.info("Connection {} from {}", session.getId(), ip);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        if (state(session) != State.AWAITING_BEGIN) {
            sendJson(session, Map.of("type", "error", "reason", "unexpected_message"));
            silentClose(session);
            return;
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(message.getPayload());
        } catch (IOException e) {
            sendJson(session, Map.of("type", "error", "reason", "invalid_json"));
            silentClose(session);
            return;
        }

        String type = root.path("type").asText("");
        long sourceBytes = root.path("source_bytes").asLong(-1);
        long targetBytes = root.path("target_bytes").asLong(-1);
        String sourceFormat = root.path("source_format").asText("");
        String targetFormat = root.path("target_format").asText("");

        String rejection = validateBegin(type, sourceBytes, targetBytes, sourceFormat, targetFormat);
        if (rejection != null) {
            sendJson(session, Map.of("type", "error", "reason", rejection));
            silentClose(session);
            return;
        }

        String requestId = UUID.randomUUID().toString();
        session.getAttributes().put(ATTR_REQUEST_ID, requestId);
        session.getAttributes().put(ATTR_STATE, State.AWAITING_SOURCE);
        sendJson(session, Map.of("type", "accepted", "request_id", requestId));
    }

    private String validateBegin(String type, long sourceBytes, long targetBytes,
                                 String sourceFormat, String targetFormat) {
        if (!"begin_request".equals(type)) {
            return "expected_begin_request";
        }
        if (sourceBytes <= 0 || sourceBytes > maxImageBytes) {
            return "source_too_large";
        }
        if (targetBytes <= 0 || targetBytes > maxImageBytes) {
            return "target_too_large";
        }
        if (!ALLOWED_FORMATS.contains(sourceFormat)) {
            return "unsupported_source_format";
        }
        if (!ALLOWED_FORMATS.contains(targetFormat)) {
            return "unsupported_target_format";
        }
        return null;
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        State state = state(session);
        byte[] bytes = toByteArray(message.getPayload());

        switch (state) {
            case AWAITING_SOURCE -> {
                session.getAttributes().put(ATTR_SOURCE, bytes);
                session.getAttributes().put(ATTR_STATE, State.AWAITING_TARGET);
            }
            case AWAITING_TARGET -> {
                session.getAttributes().put(ATTR_TARGET, bytes);
                session.getAttributes().put(ATTR_STATE, State.PROCESSING);
                triggerProcessing(session);
            }
            default -> session.close(new CloseStatus(1002, "unexpected_binary"));
        }
    }

    /**
     * Admit or reject on the I/O thread (a non-blocking {@code tryAcquire}), then hand the work
     * to the orchestrator pool. Only admitted requests reach the pool, so it never has to queue
     * behind a full server.
     */
    private void triggerProcessing(WebSocketSession session) {
        if (!admissionSemaphore.tryAcquire()) {
            sendJson(session, Map.of("type", "rejected", "reason", "server_busy"));
            silentClose(session);
            return;
        }
        requestExecutor.execute(() -> {
            try {
                byte[] src = (byte[]) session.getAttributes().get(ATTR_SOURCE);
                byte[] tgt = (byte[]) session.getAttributes().get(ATTR_TARGET);
                MosaicResult result = pipeline.process(src, tgt);
                streamPayload(session, result);
            } catch (Exception e) {
                log.error("Processing failed for session {}: {}", session.getId(), e.getMessage(), e);
                sendJson(session, Map.of("type", "error", "reason", "processing_failed"));
                silentClose(session);
            } finally {
                // Drop the (up to 10 MB each) image buffers as soon as we are done with them.
                session.getAttributes().remove(ATTR_SOURCE);
                session.getAttributes().remove(ATTR_TARGET);
                admissionSemaphore.release();
            }
        });
    }

    private void streamPayload(WebSocketSession session, MosaicResult result) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
        header.putInt(MAGIC);
        header.putInt(PROTOCOL_VERSION);
        header.putInt(result.particleCount());
        header.putInt(result.srcWidth());
        header.putInt(result.srcHeight());
        header.putInt(result.tgtWidth());
        header.putInt(result.tgtHeight());
        header.putInt(0); // reserved
        header.flip();
        session.sendMessage(new BinaryMessage(header));

        ByteBuffer payload = result.payload();
        while (payload.hasRemaining()) {
            int size = Math.min(chunkSize, payload.remaining());
            byte[] chunk = new byte[size];
            payload.get(chunk);
            boolean isLast = !payload.hasRemaining();
            session.sendMessage(new BinaryMessage(chunk, isLast));
        }

        sendJson(session, Map.of("type", "complete", "particle_count", result.particleCount()));
        log.info("Streamed {} particles ({} MB) to session {}",
                result.particleCount(),
                String.format("%.2f", result.particleCount() * 12 / 1_048_576.0),
                session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("Session {} closed: {}", session.getId(), status);
    }

    private static State state(WebSocketSession session) {
        return (State) session.getAttributes().get(ATTR_STATE);
    }

    private void sendJson(WebSocketSession session, Map<String, ?> data) {
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(data)));
        } catch (IOException e) {
            // Client may have disconnected mid-exchange; nothing useful to do.
        }
    }

    private static void silentClose(WebSocketSession session) {
        try {
            session.close();
        } catch (IOException ignored) {
            // best-effort
        }
    }

    private static byte[] toByteArray(ByteBuffer buf) {
        byte[] bytes = new byte[buf.remaining()];
        buf.get(bytes);
        return bytes;
    }

    private String clientIp(WebSocketSession session) {
        List<String> forwarded = session.getHandshakeHeaders().get("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty() && !forwarded.get(0).isBlank()) {
            return forwarded.get(0).split(",")[0].trim();
        }
        if (behindProxy) {
            return null;
        }
        InetSocketAddress remote = session.getRemoteAddress();
        return remote != null && remote.getAddress() != null
                ? remote.getAddress().getHostAddress()
                : null;
    }
}
