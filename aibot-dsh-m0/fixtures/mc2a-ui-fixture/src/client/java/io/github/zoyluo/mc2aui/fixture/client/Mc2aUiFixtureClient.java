package io.github.zoyluo.mc2aui.fixture.client;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Mc2aUiFixtureClient implements ClientModInitializer {
    public static final Logger LOGGER=LoggerFactory.getLogger("mc2a-ui-fixture");

    @Override public void onInitializeClient() {
        LOGGER.info("MC-2A UI fixture enabled");
    }
}
