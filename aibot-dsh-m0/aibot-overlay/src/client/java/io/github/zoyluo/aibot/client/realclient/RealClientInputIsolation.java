package io.github.zoyluo.aibot.client.realclient;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;
import java.util.Set;

/**
 * Desktop-input isolation for Bob's unattended real client.
 *
 * <p>The class is safe to load before the Fabric client entrypoint: mode is derived only from
 * environment variables, so the Mouse mixin can deny vanilla cursor capture from the first call.</p>
 */
public final class RealClientInputIsolation {
    public enum Mode {
        BACKGROUND("background"),
        MINIMIZED("minimized"),
        INTERACTIVE("interactive");

        private final String wireName;
        Mode(String wireName) { this.wireName=wireName; }
        public String wireName() { return wireName; }
    }

    private static final boolean REAL_CLIENT=
            "1".equals(System.getenv().getOrDefault("AIBOT_REAL_CLIENT","0"));
    private static final Mode MODE=readMode();
    private static long configuredWindow;
    private static boolean iconified;

    private RealClientInputIsolation() {}

    private static Mode readMode() {
        if(!REAL_CLIENT)return Mode.INTERACTIVE;
        if("1".equals(System.getenv().getOrDefault(
                "AIBOT_REAL_CLIENT_INTERACTIVE","0")))
            return Mode.INTERACTIVE;
        String raw=System.getenv().getOrDefault(
                "AIBOT_REAL_CLIENT_WINDOW_MODE","background")
                .trim().toLowerCase(Locale.ROOT);
        if(!Set.of("background","minimized","interactive").contains(raw))
            throw new IllegalArgumentException(
                    "AIBOT_REAL_CLIENT_WINDOW_MODE_must_be_background_minimized_or_interactive");
        return switch(raw) {
            case "background" -> Mode.BACKGROUND;
            case "minimized" -> Mode.MINIMIZED;
            default -> Mode.INTERACTIVE;
        };
    }

    public static boolean enabled() {
        return REAL_CLIENT;
    }

    public static Mode mode() {
        return MODE;
    }

    public static String modeName() {
        return MODE.wireName();
    }

    /** Mixins use this before the normal client initializer has run. */
    public static boolean suppressHumanInput() {
        return REAL_CLIENT && MODE!=Mode.INTERACTIVE;
    }

    /**
     * Called at END_CLIENT_TICK before the internal actuator writes its own key state.
     * Physical input is cleared; the actuator may then set forward/attack/etc deterministically.
     */
    public static void beforeActions(MinecraftClient client) {
        if(!suppressHumanInput() || client==null)return;
        KeyBinding.unpressAll();
        if(client.getWindow()==null)return;
        long handle=client.getWindow().getHandle();
        if(handle==0L)return;
        if(configuredWindow!=handle) {
            configuredWindow=handle;
            iconified=false;
            GLFW.glfwSetWindowAttrib(
                    handle,GLFW.GLFW_FOCUS_ON_SHOW,GLFW.GLFW_FALSE);
        }
        // Defensive repeat: a screen transition or reconnect must not re-enable disabled cursor.
        GLFW.glfwSetInputMode(
                handle,GLFW.GLFW_CURSOR,GLFW.GLFW_CURSOR_NORMAL);
        if(MODE==Mode.MINIMIZED && !iconified) {
            GLFW.glfwIconifyWindow(handle);
            iconified=true;
        }
    }

    public static void onGameJoin(MinecraftClient client) {
        iconified=false;
        beforeActions(client);
    }
}
