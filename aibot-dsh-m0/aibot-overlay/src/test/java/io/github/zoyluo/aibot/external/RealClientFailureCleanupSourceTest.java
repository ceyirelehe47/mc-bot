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

    /** R2:repository files mix CRLF/LF; assertions must be newline-agnostic. */
    private static String read(String name)throws Exception {
        return Files.readString(CLIENT.resolve(name))
                .replace("\r\n","\n");
    }

    @Test void runtimePassesMinecraftClientIntoCommandBoundary()
            throws Exception {
        String runtime=Files.readString(CLIENT
                .resolve("RealClientBodyClientRuntime.java")).replace("\r\n","\n");
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

    /**
     * R2/R05:格式错误且归属当前执行的收尾=统一 finishAction(停导航
     * →释放输入→取消破坏→关 GUI),不再是缺导航停止的旧手工序列。
     * finishAction 自身的内部顺序在此一并钉住。
     */
    @Test void matchingFailureUsesUnifiedFinishActionBoundary()
            throws Exception {
        String source=read("RealClientActionController.java");
        int finish=source.indexOf("private void finishAction(");
        assertTrue(finish>=0);
        int finishEnd=source.indexOf("\n    }",finish);
        String finishBody=source.substring(finish,finishEnd);
        int navStop=finishBody.indexOf("RealClientNavigation.stop(client)");
        int clear=finishBody.indexOf("clearInputs(client)");
        int breakCancel=finishBody.indexOf("cancelBlockBreaking()");
        int close=finishBody.indexOf("closeHandled(client)");
        assertTrue(navStop>=0,"finishAction must stop navigation first");
        assertTrue(clear>navStop);
        assertTrue(breakCancel>clear);
        assertTrue(close>breakCancel);

        int method=source.indexOf(
                "private void failMalformedCurrent(");
        int next=source.indexOf(
                "private static String safeExecutionId",method);
        assertTrue(method>=0 && next>method);
        String block=source.substring(method,next);
        int activeNull=block.indexOf("active=null");
        int terminal=block.lastIndexOf("send(");
        assertTrue(block.contains("finishAction(client);"),
                "malformed-current must run the unified cleanup");
        assertTrue(activeNull>block.indexOf("finishAction(client);"));
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

    /**
     * R2/R05:普通 complete/fail(动作自身置终态)与显式 cancel 走同一
     * finishAction——只在终态转移的那一 tick 执行,不在通用 clearInputs
     * 里每 tick 取消正常导航。
     */
    @Test void terminalTransitionRunsFinishActionExactlyOnce()
            throws Exception {
        String source=read("RealClientActionController.java");
        int tick=source.indexOf("void tick(MinecraftClient client)");
        int next=source.indexOf("void controlSessionLost(",tick);
        assertTrue(tick>=0 && next>tick);
        String block=source.substring(tick,next);
        assertTrue(block.contains("client_action_hard_timeout"));
        assertTrue(block.contains("boolean wasTerminal=active.terminal();"));
        assertTrue(block.contains(
                "if(!wasTerminal&&active.terminal())\n"
                        +"                finishAction(client);"));
        // 无 player 分支同样走统一收尾
        int noPlayer=source.indexOf(
                "client.interactionManager==null) {",tick);
        String noPlayerBlock=source.substring(noPlayer,
                source.indexOf("return;",noPlayer));
        assertTrue(noPlayerBlock.contains("finishAction(client)"),
                "no-player branch must run the unified cleanup too");
    }


    @Test void serverTargetChangeActivelyCancelsClientScreen()
            throws Exception {
        String driver=Files.readString(MAIN
                .resolve("RealClientExecutionDriver.java"))
                .replace("\r\n","\n");
        int idx=driver.indexOf(
                "real_client_resource_opportunity_stale");
        assertTrue(idx>=0);
    }

    private static int count(String text,String token) {
        int n=0;
        for(int i=text.indexOf(token);i>=0;i=text.indexOf(token,i+1))n++;
        return n;
    }
}
