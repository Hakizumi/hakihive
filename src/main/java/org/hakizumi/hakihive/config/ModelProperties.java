package org.hakizumi.hakihive.config;

import lombok.Getter;
import lombok.Setter;
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
}
