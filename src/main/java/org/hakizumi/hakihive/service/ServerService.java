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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end server pipeline:
 * websocket receive -> per-cid serial queue -> STT(endpoint) -> LLM(stream) -> websocket.
 *
 * @since 1.7.0
 * @author Hakizumi
 */
@Service
@Slf4j
public class ServerService {
    private static final int DEFAULT_MAX_CONVERSATION_TASKS = 512;
    private static final int DEFAULT_MAX_AUDIO_BYTES_PER_FRAME = 64 * 1024;

    private final ConversationStore conversationStore;
    private final LLMService llmService;
    private final SherpaOnnxSttService sherpaSttService;
    private final AudioProperties audioProperties;
    private final ConcurrentHashMap<String, ThreadPoolExecutor> conversationWorkers = new ConcurrentHashMap<>();

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
        dispatch(cid, outstreamService, () -> handleUserText(cid, text, outstreamService));
    }

    public void onAudioMetadata(@NonNull String cid, int sampleRate) {
        dispatch(cid, null, () -> handleAudioMetadata(cid, sampleRate));
    }

    public void onUserVoice(@NonNull String cid, @NonNull ByteBuffer bytes, @NonNull OutstreamService outstreamService) {
        byte[] raw = new byte[bytes.remaining()];
        bytes.get(raw);
        dispatch(cid, outstreamService, () -> handleUserVoice(cid, raw, outstreamService));
    }

    public void stopConversation(@NonNull String cid, @NonNull OutstreamService outstreamService) {
        dispatch(cid, outstreamService, () -> handleStopConversation(cid, outstreamService));
    }

    public void closeConversation(@NonNull String cid) {
        dispatch(cid, null, () -> {
            Conversation conversation = conversationStore.getConversationMemoryOrStorage(cid);
            resetConversationRuntime(conversation);
            sherpaSttService.closeConversation(cid);

            ThreadPoolExecutor worker = conversationWorkers.remove(cid);
            if (worker != null) {
                worker.shutdown();
            }
        });
    }

    private void handleUserText(@NonNull String cid, @NonNull String text, @NonNull OutstreamService outstreamService) {
        String normalized = text.trim();
        if (normalized.isEmpty()) {
            outstreamService.onError(cid, "Empty user conversation.");
            return;
        }

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

    private void handleUserVoice(@NonNull String cid, byte[] raw, @NonNull OutstreamService outstreamService) {
        Conversation conversation = conversationStore.getConversationMemoryOrStorage(cid);

        if (!isValidPcmFrame(raw, cid, outstreamService)) {
            return;
        }

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
        Conversation conversation = conversationStore.getConversationMemoryOrStorage(cid);
        resetConversationRuntime(conversation);
        outstreamService.stop();
        outstreamService.onStopped(cid);
        sherpaSttService.reset(cid);
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
        startAssistantTurn(conversation, finalText, outstreamService);
    }

    private void startAssistantTurn(
            @NonNull Conversation conversation,
            @NonNull String text,
            @NonNull OutstreamService outstreamService
    ) {
        String cid = conversation.getCid();

        conversation.cancelAssistant();
        conversation.currentUtteranceId = cid + "-" + conversation.turnCounter.incrementAndGet() + "-" + UUID.randomUUID();
        conversation.segmentIndex = 0;
        conversation.lastPartial = "";
        conversation.setAssistantActive(true);
        conversation.state = ConversationState.THINKING;

        conversation.currentAssistantSubscription = llmService.streaming(new ConversationRequest(cid, text))
                .doOnNext((event) -> handleAssistantEvent(conversation, outstreamService, event))
                .doOnError((ex) -> dispatch(cid, outstreamService, () -> {
                    conversation.currentAssistantSubscription = null;
                    conversation.setAssistantActive(false);
                    conversation.state = ConversationState.IDLE;
                    outstreamService.onError(cid, ex.getMessage() == null ? "LLM stream failed." : ex.getMessage());
                }))
                .doOnComplete(() -> dispatch(cid, null, () -> {
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

    private void dispatch(@NonNull String cid, OutstreamService outstreamService, @NonNull Runnable task) {
        ThreadPoolExecutor worker = conversationWorkers.computeIfAbsent(cid, this::newConversationWorker);
        try {
            worker.execute(task);
        }
        catch (RejectedExecutionException ex) {
            log.warn("Conversation worker queue is full. cid={}", cid, ex);
            if (outstreamService != null) {
                outstreamService.onError(cid, "Conversation worker is busy. Please retry.");
            }
        }
    }

    private @NonNull ThreadPoolExecutor newConversationWorker(@NonNull String cid) {
        int queueCapacity = audioProperties.getMaxConversationTasks() > 0
                ? audioProperties.getMaxConversationTasks()
                : DEFAULT_MAX_CONVERSATION_TASKS;

        return new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                (runnable) -> {
                    Thread thread = new Thread(runnable, "conversation-worker-" + cid);
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }
}
