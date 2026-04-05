package org.hakizumi.hakihive.config;

import com.k2fsa.sherpa.onnx.*;
import lombok.extern.slf4j.Slf4j;
import org.hakizumi.hakihive.service.SherpaOnnxSttService;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sherpa models configuration class.
 *
 * @see SherpaOnnxSttService
 *
 * @since 1.7.0
 * @author Hakizumi
 */
@Slf4j
@Configuration
public class SherpaConfig {
    /**
     * {@link OnlineRecognizerConfig} bean factory method
     *
     * @since 1.7.0
     *
     * @see SherpaOnnxSttService
     */
    @Bean
    @ConditionalOnBooleanProperty(value = "hakihive.models.stt.enable", matchIfMissing = true)
    public OnlineRecognizerConfig recognizerConfig(@NonNull ModelProperties modelProperties, @NonNull AudioProperties audioProperties) {
        log.debug("Loading online recognizer config, stt properties: {}", modelProperties.getStt());

        String encoder = modelProperties.getStt().getEncoderFilePath();
        String decoder = modelProperties.getStt().getDecoderFilePath();
        String joiner = modelProperties.getStt().getJoinerFilePath();
        String tokens = modelProperties.getStt().getTokenFilePath();

        int numThreads = Math.max(1, audioProperties.getAsrThreads());

        OnlineTransducerModelConfig transducer = OnlineTransducerModelConfig.builder()
                .setEncoder(encoder)
                .setDecoder(decoder)
                .setJoiner(joiner)
                .build();

        OnlineModelConfig modelConfig = OnlineModelConfig.builder()
                .setTransducer(transducer)
                .setTokens(tokens)
                .setNumThreads(numThreads)
                .setDebug(false)
                .build();

        OnlineRecognizerConfig.Builder builder = OnlineRecognizerConfig.builder()
                .setOnlineModelConfig(modelConfig)
                .setDecodingMethod("greedy_search");

        applyEndpointConfig(builder, audioProperties);

        return builder.build();
    }

    private void applyEndpointConfig(OnlineRecognizerConfig.@NotNull Builder builder,
                                                  @NotNull AudioProperties audioProperties
    ) {
        EndpointRule rule1 = new EndpointRule.Builder()
                .setMustContainNonSilence(false)
                .setMinTrailingSilence(audioProperties.getSherpaRule1MinTrailingSilence())
                .setMinUtteranceLength(0.0f)
                .build();

        EndpointRule rule2 = new EndpointRule.Builder()
                .setMustContainNonSilence(true)
                .setMinTrailingSilence(audioProperties.getSherpaRule2MinTrailingSilence())
                .setMinUtteranceLength(0.0f)
                .build();

        EndpointRule rule3 = new EndpointRule.Builder()
                .setMustContainNonSilence(false)
                .setMinTrailingSilence(0.0f)
                .setMinUtteranceLength(audioProperties.getSherpaRule3MinUtteranceLength())
                .build();

        EndpointConfig endpointConfig = new EndpointConfig.Builder()
                .setRule1(rule1)
                .setRule2(rule2)
                .setRule3(rule3)
                .build();

        builder.setEnableEndpoint(true);
        builder.setEndpointConfig(endpointConfig);

        log.debug("Applied sherpa endpoint config via EndpointConfig.");
    }
}
