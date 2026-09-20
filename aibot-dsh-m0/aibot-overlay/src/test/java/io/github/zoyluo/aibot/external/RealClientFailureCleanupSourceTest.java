package io.github.zoyluo.aibot.external;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class RealClientFailureCleanupSourceTest {
    private static final Path CLIENT=Path.of(
            "src/client/java/io/github/zoyluo/aibot/client/realclient");
    private static final Path MAIN=Path.of(
            "src/main/java/io/github/zoyluo/aibot/external/realclient");

    private static String read(String name)throws Exception {
        return Files.readString(CLIENT.resolve(name));
    }

    @Test void runtimePassesMinecraftClientIntoCommandBoundary()
            throws Exception {
        String runtime=read("RealClientBodyClientRuntime.java");
        assertTrue(runtime.contains(
                "actions.command(message,client)"));
        assertFalse(runtime.contains(
                "actions.command(message);"));
    }

    @Test void commandAndControlParsingUseOneCleanupBoundary()
            throws Exception {
        String source=read("RealClientActionController.java");
        assertTrue(source.contains(
                "void command(JsonObject message,MinecraftClient client)"));
        assertTrue(source.contains(
                "failMalformedCurrent(\n"
                        +"                client,executionId,\n"
                        +"                \"real_client_command_invalid:\""));
        assertTrue(source.contains(
                "\"real_client_control_invalid:\""));
        assertTrue(source.contains(
                "failMalformedCurrent(client,executionId,reason)"));
    }

    @Test void matchingFailureClosesScreenBeforeClearingAction()
            throws Exception {
        String source=read("RealClientActionController.java");
        int method=source.indexOf(
                "private void failMalformedCurrent(");
        int next=source.indexOf(
                "private static String safeExecutionId",method);
        assertTrue(method>=0 && next>method);
        String block=source.substring(method,next);
        int clear=block.indexOf("clearInputs(client)");
        int breakCancel=block.indexOf("cancelBlockBreaking()");
        int close=block.indexOf("closeHandled(client)");
        int activeNull=block.indexOf("active=null");
        int terminal=block.lastIndexOf("send(");
        assertTrue(clear>=0);
        assertTrue(breakCancel>clear);
        assertTrue(close>breakCancel);
        assertTrue(activeNull>close);
        assertTrue(terminal>activeNull);
    }

    @Test void staleNonMatchingFailureCannotDestroyCurrentAction()
            throws Exception {
        String source=read("RealClientActionController.java");
        int method=source.indexOf(
                "private void failMalformedCurrent(");
        int next=source.indexOf(
                "private static String safeExecutionId",method);
        String block=source.substring(method,next);
        assertTrue(block.contains(
                "boolean ownsCurrent=active!=null"));
        assertTrue(block.contains(
                "if(!ownsCurrent) {\n"
                        +"            send(executionId,\"failed\",0D,reason);\n"
                        +"            return;\n"
                        +"        }"));
    }

    @Test void matchingFailureEmitsExactlyOneTerminalReceipt()
            throws Exception {
        String source=read("RealClientActionController.java");
        int method=source.indexOf(
                "private void failMalformedCurrent(");
        int next=source.indexOf(
                "private static String safeExecutionId",method);
        String block=source.substring(method,next);
        assertEquals(2,count(block,"send("),
                "one non-owner receipt plus one owner receipt");
        assertFalse(block.contains("active.fail("));
    }

    @Test void cleanupStillProtectsTheHandledScreenPath()
            throws Exception {
        String source=read("RealClientActionController.java");
        assertTrue(source.contains(
                "client_preexisting_screen_open"));
        assertTrue(source.contains(
                "client_screen_ownership_lost"));
        assertTrue(source.contains(
                "closeHandled(client)"));
    }


    @Test void serverTargetChangeActivelyCancelsClientScreen()
            throws Exception {
        String source=Files.readString(
                MAIN.resolve("RealClientExecutionDriver.java"));
        int invalid=source.indexOf(
                "if(!target.stillValid(player))");
        int cancel=source.indexOf(
                "transport.sendControl(",invalid);
        int terminal=source.indexOf(
                "\"real_client_deposit_target_changed\"",cancel);
        assertTrue(invalid>=0);
        assertTrue(cancel>invalid);
        assertTrue(terminal>cancel);
    }

    private static int count(String text,String token) {
        int total=0,from=0;
        while((from=text.indexOf(token,from))>=0) {
            total++;
            from+=token.length();
        }
        return total;
    }
}
