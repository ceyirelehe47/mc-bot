package io.github.zoyluo.aibot.external;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class RealClientProductionRuntimeSourceTest {
    private static final Path CLIENT=Path.of(
            "src/client/java/io/github/zoyluo/aibot/client/realclient");
    private static final Path MAIN=Path.of(
            "src/main/java/io/github/zoyluo/aibot/external/realclient");

    @Test void vanillaMatchingUsesRemappableTypesNotYarnStrings()
            throws Exception {
        String registry=Files.readString(
                CLIENT.resolve("RealClientScreenAdapterRegistry.java"));
        assertTrue(registry.contains(
                "screen.getClass()==GenericContainerScreen.class"));
        assertTrue(registry.contains(
                "instanceof GenericContainerScreenHandler"));
        assertFalse(registry.contains(
                "\"net.minecraft.client.gui.screen.ingame.GenericContainerScreen\""));
        assertFalse(registry.contains(
                "\"net.minecraft.screen.GenericContainerScreenHandler\""));
    }

    @Test void productionRuntimeLogsCurrentNamespace()
            throws Exception {
        String runtime=Files.readString(
                CLIENT.resolve("RealClientBodyClientRuntime.java"));
        assertTrue(runtime.contains("getCurrentRuntimeNamespace()"));
        assertTrue(runtime.contains("runtime_namespace={}"));
    }

    @Test void inboundCommandsAreBoundToControlAndGameIncarnations()
            throws Exception {
        String client=Files.readString(
                CLIENT.resolve("RealClientClientTransport.java"));
        String server=Files.readString(
                MAIN.resolve("RealClientServerTransport.java"));
        assertTrue(client.contains("record InboundFrame("));
        assertTrue(client.contains("frame.controlSession()"));
        assertTrue(client.contains("frame.gameSession()"));
        assertTrue(client.contains("frame.gameSessionSeq()"));
        assertTrue(client.contains("frame.commandSeq()"));
        assertTrue(client.contains("inbound.clear()"));
        assertTrue(server.contains("\"command_seq\""));
        assertTrue(server.contains("\"game_session\""));
        assertTrue(server.contains("resetServerCommandIncarnation()"));
    }

    @Test void malformedOrStaleCommitCannotEscapeClientTick()
            throws Exception {
        String actions=Files.readString(
                CLIENT.resolve("RealClientActionController.java"));
        assertTrue(actions.contains("real_client_command_invalid:"));
        assertTrue(actions.contains("real_client_control_invalid:"));
        assertTrue(actions.contains(
                "real_client_stale_or_unowned_commit"));
        assertTrue(actions.contains("safeExecutionId("));
    }

    @Test void uiTextIsExplicitlyUntrustedData()
            throws Exception {
        String controller=Files.readString(
                CLIENT.resolve("RealClientScreenController.java"));
        String server=Files.readString(
                MAIN.resolve("RealClientServerTransport.java"));
        String cognitive=Files.readString(
                MAIN.resolve("RealClientCognitiveViewBuilder.java"));
        assertTrue(controller.contains("\"title_origin\",\"mod_ui\""));
        assertTrue(controller.contains("\"title_trust\",\"untrusted_data\""));
        assertTrue(controller.contains("\"message_origin\""));
        assertTrue(server.contains(
                "real_client_screen_text_provenance_invalid"));
        assertTrue(cognitive.contains("\"trust\""));
        assertTrue(cognitive.contains("\"untrusted_data\""));
    }

    @Test void tomsStorageCompatIsExactAndBounded()
            throws Exception {
        String client=Files.readString(
                CLIENT.resolve("RealClientTomsStorageClientCompat.java"));
        String server=Files.readString(
                MAIN.resolve("RealClientTomsStorageServerCompat.java"));
        String target=Files.readString(
                MAIN.resolve("RealClientStorageTarget.java"));
        assertTrue(client.contains(
                "com.tom.storagemod.screen.StorageTerminalScreen"));
        assertTrue(client.contains("MAX_ITEMS=128"));
        assertTrue(server.contains(
                "com.tom.storagemod.block.entity.StorageTerminalBlockEntity"));
        assertTrue(server.contains(
                "toms_storage:storage_terminal"));
        assertTrue(target.contains("slot.inventory==barrel"));
        assertTrue(target.contains(
                "RealClientTomsStorageServerCompat.handlerOwns"));
        assertFalse(client.contains("getDeclaredMethods()"));
        assertFalse(server.contains("getDeclaredMethods()"));
    }

    @Test void screenReadModelDoesNotExposeGenericMutation()
            throws Exception {
        String combined=Files.readString(
                CLIENT.resolve("RealClientScreenAdapterRegistry.java"))
                +Files.readString(
                        CLIENT.resolve("RealClientTomsStorageClientCompat.java"))
                +Files.readString(
                        CLIENT.resolve("RealClientScreenController.java"));
        assertFalse(combined.contains("clickSlot("));
        assertFalse(combined.contains("mouseClicked("));
        assertFalse(combined.contains("activateWidget"));
        assertFalse(combined.contains("sendPacket("));
    }
}
