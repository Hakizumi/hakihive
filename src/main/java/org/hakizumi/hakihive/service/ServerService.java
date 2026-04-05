package org.hakizumi.hakihive.service;

import lombok.extern.slf4j.Slf4j;
import org.hakizumi.hakihive.config.AudioProperties;
import org.hakizumi.hakihive.dto.ConversationRequest;
import org.hakizumi.hakihive.dto.ConversationResponse;
import org.hakizumi.hakihive.dto.UserAudioRequest;
import org.hakizumi.hakihive.memory.Conversation;
import org.hakizumi.hakihive.memory.ConversationState;
import org.hakizumi.hakihive.repository.storage.ConversationStore;
import org.hakizumi.hakihive.utils.AudioUtil;
import org.jspecify.annotations.NonNull;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * End-to-end server pipeline:
 * websocket receive -> per-cid audio/control queues -> per-cid serial worker -> STT(endpoint) -> LLM(stream) -> websocket.
 *
 * @since 1.7.0
 * @author Hakizumi
 */
@Service
@Slf4j
public class ServerService {
    private static final int DEFAULT_MAX_AUDIO_QUEUE_FRAMES = 128;
    private static final int DEFAULT_MAX_AUDIO_BYTES_PER_FRAME = 64 * 1024;
    private static final long WORKER_IDLE_POLL_MS = 40L;
    private static final int MAX_CONTROL_TASKS_PER_TICK = 32;

    private final ConversationStore conversationStore;
    private final LLMService llmService;
    private final SherpaOnnxSttService sherpaSttService;
    private final AudioProperties audioProperties;

    private final ConcurrentHashMap<String, BlockingQueue<AudioFrame>> conversationAudioQueues = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BlockingQueue<Runnable>> conversationControlQueues = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Thread> conversationWorkers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicBoolean> closingFlags = new ConcurrentHashMap<>();

    public ServerService(
            ConversationStore conversationStore,
            LLMService llmService,
            SherpaOnnxSttService sherpaSttService,
            AudioProperties audioProperties
    ) {
        this.conversationStore = conversationStore;
        this.llmService = llmService;
        this.sherpaSttService = sherpaSttService;
        this.audioProperties = audioProperties;
    }

    public void onUserText(@NonNull String cid, @NonNull String text, @NonNull OutstreamService outstreamService) {
        enqueueControl(cid, () -> handleUserText(cid, text, outstreamService));
    }

    public void onAudioMetadata(@NonNull String cid, int sampleRate) {
        enqueueControl(cid, () -> handleAudioMetadata(cid, sampleRate));
    }

    public void onUserVoice(@NonNull String cid, @NonNull ByteBuffer bytes, @NonNull OutstreamService outstreamService) {
        byte[] raw = new byte[bytes.remaining()];
        bytes.get(raw);

        if (!isValidPcmFrame(raw, cid, outstreamService)) {
            return;
        }

        ensureConversationWorkerStarted(cid);
        BlockingQueue<AudioFrame> audioQueue = getAudioQueue(cid);
        AudioFrame frame = new AudioFrame(raw, outstreamService);

        if (audioQueue.offer(frame)) {
            return;
        }

        AudioFrame dropped = audioQueue.poll();
        boolean enqueued = audioQueue.offer(frame);
        if (!enqueued) {
            log.warn("Dropping latest audio frame because queue is still full. cid={}, queueSize={}", cid, audioQueue.size());
            return;
        }

        if (dropped != null) {
            log.debug("Dropped oldest buffered audio frame to keep realtime STT responsive. cid={}", cid);
        }
    }

    public void stopConversation(@NonNull String cid, @NonNull OutstreamService outstreamService) {
        enqueueControl(cid, () -> handleStopConversation(cid, outstreamService));
    }

    public void closeConversation(@NonNull String cid) {
        ensureConversationWorkerStarted(cid);
        enqueueControl(cid, () -> handleCloseConversation(cid));
    }

    private void handleUserText(@NonNull String cid, @NonNull String text, @NonNull OutstreamService outstreamService) {
        String normalized = text.trim();
        if (normalized.isEmpty()) {
            outstreamService.onError(cid, "Empty user conversation.");
            return;
        }

        clearBufferedAudio(cid);
        Conversation conversation = conversationStore.getConversationMemoryOrStorage(cid);
        startAssistantTurn(conversation, normalized, outstreamService);
    }

    private void handleAudioMetadata(@NonNull String cid, int sampleRate) {
        if (sampleRate <= 0) {
            return;
        }

        Conversation conversation = conversationStore.getConversationMemoryOrStorage(cid);
        conversation.clientSampleRate = sampleRate;
    }

    private void handleUserVoice(
            @NonNull Conversation conversation,
            @NonNull String cid,
            byte @NonNull [] raw,
            @NonNull OutstreamService outstreamService
    ) {
        float[] samples = AudioUtil.pcm16leToFloat(raw);
        if (samples.length == 0) {
            return;
        }

        sherpaSttService.acceptWaveform(
                cid,
                samples,
                conversation.clientSampleRate > 0 ? conversation.clientSampleRate : audioProperties.getSampleRate()
        );

        String partial = sherpaSttService.getText(cid);
        boolean hasTranscript = !partial.isBlank();
        boolean partialChanged = hasTranscript && !partial.equals(conversation.lastPartial);

        if (hasTranscript) {
            if (conversation.state != ConversationState.LISTENING) {
                conversation.state = ConversationState.LISTENING;
                conversation.speechFrames = 0;
                conversation.silenceFrames = 0;
            }

            conversation.speechFrames++;
            conversation.silenceFrames = 0;

            if (partialChanged) {
                conversation.lastPartial = partial;
                outstreamService.onUserPartialText(new UserAudioRequest(partial, cid));
            }

            if (conversation.isAssistantActive()) {
                conversation.speakingStreakFrames = partialChanged ? (conversation.speakingStreakFrames + 1) : 0;
                if (conversation.speakingStreakFrames >= audioProperties.getBargeInSpeechFrames()) {
                    interruptAssistantForBargeIn(conversation, outstreamService);
                }
            }
            else {
                conversation.speakingStreakFrames = 0;
            }
        }
        else {
            conversation.speakingStreakFrames = 0;
            if (conversation.state == ConversationState.LISTENING) {
                conversation.silenceFrames++;
            }
            else {
                return;
            }
        }

        if (conversation.state == ConversationState.LISTENING && sherpaSttService.isEndpoint(cid)) {
            finalizeUserSpeech(conversation, outstreamService);
        }
    }

    private void handleStopConversation(@NonNull String cid, @NonNull OutstreamService outstreamService) {
        clearBufferedAudio(cid);

        Conversation conversation = conversationStore.getConversationMemoryOrStorage(cid);
        resetConversationRuntime(conversation);
        outstreamService.stop();
        outstreamService.onStopped(cid);
        sherpaSttService.reset(cid);
    }

    private void handleCloseConversation(@NonNull String cid) {
        AtomicBoolean closing = closingFlags.computeIfAbsent(cid, (key) -> new AtomicBoolean(false));
        closing.set(true);

        clearBufferedAudio(cid);

        Conversation conversation = conversationStore.getConversationMemoryOrStorage(cid);
        resetConversationRuntime(conversation);
        sherpaSttService.closeConversation(cid);
    }

    private boolean isValidPcmFrame(byte @NonNull [] raw, @NonNull String cid, @NonNull OutstreamService outstreamService) {
        if (raw.length == 0) {
            return false;
        }

        if ((raw.length & 1) != 0) {
            log.warn("Dropping odd-length PCM frame. cid={}, bytes={}", cid, raw.length);
            return false;
        }

        int maxAudioBytesPerFrame = audioProperties.getMaxAudioBytesPerFrame() > 0
                ? audioProperties.getMaxAudioBytesPerFrame()
                : DEFAULT_MAX_AUDIO_BYTES_PER_FRAME;

        if (raw.length > maxAudioBytesPerFrame) {
            log.warn("Dropping oversized PCM frame. cid={}, bytes={}, max={}", cid, raw.length, maxAudioBytesPerFrame);
            outstreamService.onError(cid, "Audio frame too large.");
            return false;
        }

        return true;
    }

    private void interruptAssistantForBargeIn(
            @NonNull Conversation conversation,
            @NonNull OutstreamService outstreamService
    ) {
        if (!conversation.isAssistantActive()) {
            return;
        }

        log.debug("Assistant interrupted by barge-in. cid={}", conversation.getCid());
        conversation.cancelAssistant();
        outstreamService.stop();
    }

    private void finalizeUserSpeech(@NonNull Conversation conversation, @NonNull OutstreamService outstreamService) {
        String cid = conversation.getCid();
        String finalText = sherpaSttService.getText(cid).trim();
        long speechFrames = conversation.speechFrames;

        boolean accepted = speechFrames >= audioProperties.getSpeechTriggerFrame() && !finalText.isBlank();

        if (!accepted) {
            sherpaSttService.reset(cid);
            conversation.resetSpeechRuntime();
            return;
        }

        conversation.lastPartial = finalText;
        outstreamService.onUserFinalText(new UserAudioRequest(finalText, cid));

        sherpaSttService.reset(cid);
        conversation.resetSpeechRuntime();
        clearBufferedAudio(cid);
        startAssistantTurn(conversation, finalText, outstreamService);
    }

    private void startAssistantTurn(
            @NonNull Conversation conversation,
            @NonNull String text,
            @NonNull OutstreamService outstreamService
    ) {
        String cid = conversation.getCid();

        clearBufferedAudio(cid);
        conversation.cancelAssistant();
        conversation.currentUtteranceId = cid + "-" + conversation.turnCounter.incrementAndGet() + "-" + UUID.randomUUID();
        conversation.segmentIndex = 0;
        conversation.lastPartial = "";
        conversation.setAssistantActive(true);
        conversation.state = ConversationState.THINKING;

        conversation.currentAssistantSubscription = llmService.streaming(new ConversationRequest(cid, text))
                .doOnNext((event) -> handleAssistantEvent(conversation, outstreamService, event))
                .doOnError((ex) -> enqueueControl(cid, () -> {
                    conversation.currentAssistantSubscription = null;
                    conversation.setAssistantActive(false);
                    conversation.state = ConversationState.IDLE;
                    outstreamService.onError(cid, ex.getMessage() == null ? "LLM stream failed." : ex.getMessage());
                }))
                .doOnComplete(() -> enqueueControl(cid, () -> {
                    conversation.currentAssistantSubscription = null;
                    conversation.setAssistantActive(false);
                    conversation.state = ConversationState.IDLE;
                }))
                .onErrorResume((ex) -> Flux.empty())
                .subscribe(null, (ex) -> log.debug("Assistant stream already handled error. cid={}", cid, ex));
    }

    private void handleAssistantEvent(
            @NonNull Conversation conversation,
            @NonNull OutstreamService outstreamService,
            @NonNull ServerSentEvent<@NonNull ConversationResponse> event
    ) {
        outstreamService.onAssistantEvent(event);

        String eventName = event.event();
        if (event.data() == null || !event.data().isSuccess()) {
            return;
        }

        String data = event.data().getMessage();
        if ("delta".equals(eventName) && data != null && !data.isEmpty()) {
            conversation.state = ConversationState.REPLYING;
        }
    }

    private void resetConversationRuntime(@NonNull Conversation conversation) {
        conversation.cancelAssistant();
        conversation.resetSpeechRuntime();
        conversation.currentUtteranceId = null;
    }

    private void enqueueControl(@NonNull String cid, @NonNull Runnable task) {
        ensureConversationWorkerStarted(cid);
        getControlQueue(cid).offer(task);
    }

    private void ensureConversationWorkerStarted(@NonNull String cid) {
        conversationWorkers.computeIfAbsent(cid, (key) -> {
            conversationAudioQueues.computeIfAbsent(key, (ignored) -> new ArrayBlockingQueue<>(resolveAudioQueueCapacity()));
            conversationControlQueues.computeIfAbsent(key, (ignored) -> new LinkedBlockingQueue<>());
            closingFlags.computeIfAbsent(key, (ignored) -> new AtomicBoolean(false));

            Thread thread = new Thread(() -> runConversationLoop(key), "conversation-worker-" + key);
            thread.setDaemon(true);
            thread.start();
            return thread;
        });
    }

    private void runConversationLoop(@NonNull String cid) {
        BlockingQueue<Runnable> controlQueue = getControlQueue(cid);
        BlockingQueue<AudioFrame> audioQueue = getAudioQueue(cid);
        AtomicBoolean closing = closingFlags.computeIfAbsent(cid, (key) -> new AtomicBoolean(false));
        Conversation conversation = conversationStore.getConversationMemoryOrStorage(cid);

        try {
            while (true) {
                drainControlTasks(cid, controlQueue);

                if (closing.get() && controlQueue.isEmpty() && audioQueue.isEmpty()) {
                    break;
                }

                AudioFrame frame = audioQueue.poll(WORKER_IDLE_POLL_MS, TimeUnit.MILLISECONDS);
                if (frame != null) {
                    try {
                        handleUserVoice(conversation, cid, frame.raw(), frame.outstreamService());
                    } catch (Exception ex) {
                        log.warn("Failed to process audio frame. cid={}", cid, ex);
                        frame.outstreamService().onError(cid, "Audio processing failed.");
                    }
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.debug("Conversation worker interrupted. cid={}", cid);
        } finally {
            cleanupConversationWorker(cid, Thread.currentThread());
        }
    }

    private void drainControlTasks(@NonNull String cid, @NonNull BlockingQueue<Runnable> controlQueue) {
        int processed = 0;
        Runnable task;

        while (processed < MAX_CONTROL_TASKS_PER_TICK && (task = controlQueue.poll()) != null) {
            try {
                task.run();
            } catch (Exception ex) {
                log.warn("Conversation control task failed. cid={}", cid, ex);
            }
            processed++;
        }
    }

    private void cleanupConversationWorker(@NonNull String cid, @NonNull Thread workerThread) {
        conversationWorkers.remove(cid, workerThread);
        conversationAudioQueues.remove(cid);
        conversationControlQueues.remove(cid);
        closingFlags.remove(cid);
    }

    private void clearBufferedAudio(@NonNull String cid) {
        BlockingQueue<AudioFrame> audioQueue = conversationAudioQueues.get(cid);
        if (audioQueue != null) {
            audioQueue.clear();
        }
    }

    private @NonNull BlockingQueue<AudioFrame> getAudioQueue(@NonNull String cid) {
        return conversationAudioQueues.computeIfAbsent(cid, (key) -> new ArrayBlockingQueue<>(resolveAudioQueueCapacity()));
    }

    private @NonNull BlockingQueue<Runnable> getControlQueue(@NonNull String cid) {
        return conversationControlQueues.computeIfAbsent(cid, (key) -> new LinkedBlockingQueue<>());
    }

    private int resolveAudioQueueCapacity() {
        return audioProperties.getMaxConversationTasks() > 0
                ? audioProperties.getMaxConversationTasks()
                : DEFAULT_MAX_AUDIO_QUEUE_FRAMES;
    }

    private record AudioFrame(byte[] raw, OutstreamService outstreamService) {
    }
}
