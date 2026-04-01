package org.hakizumi.hakihive.config;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Online / local models configuration properties class.
 * <p>
 * YAML shape like:
 * <pre>
 * hakihive:
 *   models:
 *     decision-model: gpt-4o-mini
 * </pre>
 *
 * @since 1.4.0
 * @author Hakizumi
 */
@ConfigurationProperties("hakihive.models")
@Getter
@Setter
public class ModelProperties {
    /**
     * Main reply model.
     */
    private @NonNull String decisionModel = "gpt-4";

    /**
     * Sherpa-onnx native speech-to-conversation model configuration.
     *
     * @see Stt
     */
    private @NonNull Stt stt = new Stt();

    /**
     * Sherpa-onnx native speech-to-conversation model configuration.
     */
    @Getter
    @Setter
    @ToString
    public static class Stt {
        private boolean enable = true;

        private @NonNull String tokenFilePath = "models/stt/tokens.txt";
        private @NonNull String joinerFilePath = "models/stt/joiner-epoch-99-avg-1.int8.onnx";
        private @NonNull String encoderFilePath = "models/stt/encoder-epoch-99-avg-1.int8.onnx";
        private @NonNull String decoderFilePath = "models/stt/decoder-epoch-99-avg-1.int8.onnx";
    }
}
