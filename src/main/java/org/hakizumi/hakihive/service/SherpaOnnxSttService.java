package org.hakizumi.hakihive.service;

import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Unmodifiable;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wrapper around sherpa-onnx online ASR.
 * <p>
 * The Java bindings vary a little across versions, so this service uses reflection
 * for the hot-path API calls while still keeping the configured model types explicit.
 *
 * @since 1.7.0
 * @author Hakizumi
 */
@Component
@Slf4j
public class SherpaOnnxSttService {
    private final Map<String, Map.Entry<OnlineRecognizer, OnlineStream>> sherpaEntries = new ConcurrentHashMap<>();
    private final OnlineRecognizerConfig config;

    protected SherpaOnnxSttService(OnlineRecognizerConfig config) {
        this.config = config;
    }

    public OnlineRecognizer getRecognizer(String cid) {
        return sherpaEntries.computeIfAbsent(cid, this::newEntry).getKey();
    }

    public OnlineStream getStream(String cid) {
        return sherpaEntries.computeIfAbsent(cid, this::newEntry).getValue();
    }

    /**
     * Feed one audio chunk and decode available frames.
     *
     * @since 1.7.0
     */
    public void acceptWaveform(@NonNull String cid, float[] samples, int sampleRate) {
        OnlineRecognizer recognizer = getRecognizer(cid);
        OnlineStream stream = getStream(cid);

        stream.acceptWaveform(samples, sampleRate);

        while (recognizer.isReady(stream)) {
            recognizer.decode(stream);
        }
    }

    /**
     * Read the best current partial conversation.
     *
     * @since 1.7.0
     */
    public @NonNull String getText(@NonNull String cid) {
        OnlineRecognizer recognizer = getRecognizer(cid);
        OnlineStream stream = getStream(cid);
        try {
            Object result = recognizer.getClass().getMethod("getResult", stream.getClass()).invoke(recognizer, stream);
            if (result == null) {
                return "";
            }

            for (@NonNull String methodName : new String[]{"getText", "text"}) {
                try {
                    Method method = result.getClass().getMethod(methodName);
                    Object value = method.invoke(result);
                    return value == null ? "" : value.toString().trim();
                }
                catch (NoSuchMethodException ignored) {
                    // try next
                }
            }
            return result.toString().trim();
        }
        catch (Exception ex) {
            throw new IllegalStateException("Cannot read sherpa result conversation.", ex);
        }
    }

    /**
     * Whether current stream hits an endpoint.
     *
     * @since 1.7.0
     */
    public boolean isEndpoint(@NonNull String cid) {
        OnlineRecognizer recognizer = getRecognizer(cid);
        OnlineStream stream = getStream(cid);
        return recognizer.isEndpoint(stream);
    }

    /**
     * Reset the current online stream after one finalized utterance.
     *
     * @since 1.7.0
     */
    public void reset(@NonNull String cid) {
        OnlineRecognizer recognizer = getRecognizer(cid);
        OnlineStream stream = getStream(cid);

        recognizer.reset(stream);
    }

    /**
     * Drop all state for a conversation.
     *
     * @since 1.7.0
     */
    public void closeConversation(@NonNull String cid) {
        Map.Entry<OnlineRecognizer, OnlineStream> entry = sherpaEntries.remove(cid);
        if (entry != null) {
            entry.getValue().release();
            entry.getKey().release();
        }
    }

    @PreDestroy
    public void destroy() {
        for (Map.@NonNull Entry<OnlineRecognizer, OnlineStream> entry : sherpaEntries.values()) {
            entry.getValue().release();
            entry.getKey().release();
        }
        sherpaEntries.clear();
    }

    private Map.@NonNull @Unmodifiable Entry<OnlineRecognizer, OnlineStream> newEntry(String cid) {
        OnlineRecognizer recognizer = new OnlineRecognizer(config);
        OnlineStream stream = recognizer.createStream();
        return Map.entry(recognizer, stream);
    }
}
