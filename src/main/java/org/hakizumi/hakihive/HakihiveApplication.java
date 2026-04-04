package org.hakizumi.hakihive;

import lombok.extern.slf4j.Slf4j;
import org.hakizumi.hakihive.utils.PlatformUtil;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.nio.file.Path;

/**
 * Springboot application starter class.
 *
 * @since 1.0.0
 * @author Hakizumi
 */
@SpringBootApplication
@Slf4j
public class HakihiveApplication {
    public static void main(String[] args) {
        preloadNativeLibs();
        SpringApplication.run(HakihiveApplication.class, args);
    }

    private static void preloadNativeLibs() {
        try {
            String platform = PlatformUtil.getPlatform();

            Path base = Path.of("lib").toAbsolutePath().normalize();

            Path ort;
            Path sherpa;

            switch (platform) {
                case "win-x64" -> {
                    ort = base.resolve("onnxruntime.dll");
                    sherpa = base.resolve("sherpa-onnx-jni.dll");
                }
                case "mac-x64","mac-aarch64" -> {
                    ort = base.resolve("libonnxruntime.1.23.2.dylib");
                    sherpa = base.resolve("libsherpa-onnx-jni.dylib");
                }
                case "linux-x64","linux-aarch64" -> {
                    ort = base.resolve("libonnxruntime.so");
                    sherpa = base.resolve("libsherpa-onnx-jni.so");
                }
                default -> throw new IllegalStateException("Unsupported platform: " + platform);
            }

            log.info("Preloading native libs from: {}", base);

            System.load(ort.toString());
            System.load(sherpa.toString());

            log.info("Native libs loaded.");
        } catch (Throwable e) {
            log.error("Cannot preload native libs", e);
        }
    }
}
