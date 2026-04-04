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

/**
 * End-to-end server pipeline:
 * STT -> LLM(stream) -> websocket.
 *
 * @since 1.7.0
 * @author Hakizumi
 */
@Service
@Slf4j
public class ServerService {
    private final ConversationStore conversationStore;
    private final LLMService llmService;
    private final SherpaOnnxSttService sherpaSttService;
    private final AudioProperties audioProperties;

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
        String normalized = text.trim();
        if (normalized.isEmpty()) {
            outstreamService.onError(cid, "Empty user conversation.");
            return;
        }

        Conversation conversation = conversationStore.getConversationMemoryOrStorage(cid);
        startAssistantTurn(conversation, normalized, outstreamService);
    }

    public void onAudioMetadata(@NonNull String cid, int sampleRate) {
        Conversation conversation = conversationStore.getConversationMemoryOrStorage(cid);
        if (sampleRate > 0) {
            conversation.clientSampleRate = sampleRate;
        }
    }

    public void onUserVoice(@NonNull String cid, @NonNull ByteBuffer bytes, @NonNull OutstreamService outstreamService) {
        Conversation conversation = conversationStore.getConversationMemoryOrStorage(cid);

        byte[] raw = new byte[bytes.remaining()];
        bytes.get(raw);

        float[] samples = AudioUtil.toFloatArray(raw);
        if (samples.length == 0) {
            return;
        }

        float rms = AudioUtil.rms(samples);
        float adaptiveThreshold = updateVadThreshold(conversation, rms);
        boolean speaking = rms >= adaptiveThreshold;
        conversation.speakingStreakFrames = speaking ? (conversation.speakingStreakFrames + 1) : 0;

        sherpaSttService.acceptWaveform(
                cid,
                samples,
                conversation.clientSampleRate > 0 ? conversation.clientSampleRate : audioProperties.getSampleRate()
        );

        if (conversation.isAssistantActive()
                && conversation.speakingStreakFrames >= audioProperties.getBargeInSpeechFrames()) {
            interruptAssistantForBargeIn(conversation, outstreamService);
        }

        String partial = sherpaSttService.getText(cid);

        if (speaking) {
            if (conversation.state != ConversationState.LISTENING) {
                conversation.state = ConversationState.LISTENING;
                conversation.speechFrames = 0;
                conversation.silenceFrames = 0;
            }
            conversation.speechFrames++;
            conversation.silenceFrames = 0;
            emitPartialIfChanged(conversation, cid, partial, outstreamService);
            return;
        }

        if (conversation.state != ConversationState.LISTENING) {
            return;
        }

        conversation.silenceFrames++;
        emitPartialIfChanged(conversation, cid, partial, outstreamService);

        boolean endpoint = sherpaSttService.isEndpoint(cid)
                || conversation.silenceFrames >= audioProperties.getSilenceTriggerFrame();

        if (endpoint) {
            finalizeUserSpeech(conversation, outstreamService);
        }
    }

    public void stopConversation(@NonNull String cid, @NonNull OutstreamService outstreamService) {
        Conversation conversation = conversationStore.getConversationMemoryOrStorage(cid);
        resetConversationRuntime(conversation);
        outstreamService.stop();
        outstreamService.onStopped(cid);
        sherpaSttService.reset(cid);
    }

    public void closeConversation(@NonNull String cid) {
        Conversation conversation = conversationStore.getConversationMemoryOrStorage(cid);
        resetConversationRuntime(conversation);
        sherpaSttService.closeConversation(cid);
    }

    private void emitPartialIfChanged(
            @NonNull Conversation conversation,
            @NonNull String cid,
            @NonNull String partial,
            @NonNull OutstreamService outstreamService
    ) {
        if (!partial.isBlank() && !partial.equals(conversation.lastPartial)) {
            conversation.lastPartial = partial;
            outstreamService.onUserPartialText(new UserAudioRequest(partial, cid));
        }
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

        sherpaSttService.reset(cid);
        conversation.resetSpeechRuntime();

        if (speechFrames < audioProperties.getSpeechTriggerFrame()) {
            return;
        }

        if (finalText.isBlank()) {
            return;
        }

        conversation.lastPartial = finalText;
        outstreamService.onUserFinalText(new UserAudioRequest(finalText, cid));
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
                .doOnError((ex) -> {
                    conversation.currentAssistantSubscription = null;
                    conversation.setAssistantActive(false);
                    conversation.state = ConversationState.IDLE;
                    outstreamService.onError(cid, ex.getMessage() == null ? "LLM stream failed." : ex.getMessage());
                })
                .doOnComplete(() -> {
                    conversation.currentAssistantSubscription = null;
                    conversation.setAssistantActive(false);
                    conversation.state = ConversationState.IDLE;
                })
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

    private float updateVadThreshold(@NonNull Conversation conversation, float rms) {
        float alpha = audioProperties.getVadNoiseFloorAlpha();
        if (conversation.vadNoiseFloor <= 0.0f) {
            conversation.vadNoiseFloor = rms;
        }
        else if (conversation.state != ConversationState.LISTENING || rms < conversation.vadNoiseFloor * 1.2f) {
            conversation.vadNoiseFloor = conversation.vadNoiseFloor * alpha + rms * (1.0f - alpha);
        }

        return Math.max(
                audioProperties.getMinVadRmsThreshold(),
                Math.max(audioProperties.getVadRmsThreshold(), conversation.vadNoiseFloor * audioProperties.getVadNoiseMultiplier())
        );
    }

    private void resetConversationRuntime(@NonNull Conversation conversation) {
        conversation.cancelAssistant();
        conversation.resetSpeechRuntime();
        conversation.currentUtteranceId = null;
    }
}
