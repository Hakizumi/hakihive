package org.hakizumi.hakihive.config;

import com.k2fsa.sherpa.onnx.*;
import lombok.extern.slf4j.Slf4j;
import org.hakizumi.hakihive.service.SherpaOnnxSttService;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sherpa models configuration class
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
     * {@code OnlineRecognizerConfig} bean factory method
     *
     * @since 1.7.0
     *
     * @see SherpaOnnxSttService
     */
    @Bean
    @ConditionalOnBooleanProperty(value = "hakihive.models.stt.enable",matchIfMissing = true)
    public OnlineRecognizerConfig recognizerConfig(@NonNull ModelProperties modelProperties, @NonNull AudioProperties audioProperties) {
        log.debug("Loading online recognizer config,stt properties: {}",modelProperties.getStt());

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

        return OnlineRecognizerConfig.builder()
                .setOnlineModelConfig(modelConfig)
                .setDecodingMethod("greedy_search")
                .build();
    }
}
