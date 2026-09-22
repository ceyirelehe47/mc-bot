package io.github.zoyluo.aibot.external.realclient;

import java.io.InputStream;
import java.net.URL;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * MC-RCF-1-R3 F02:runtime-loaded artifact identity.
 *
 * <p>Both ends of the pair report the SHA-256 of the artifact they
 * ACTUALLY loaded (this class's own code source — the remapped mod jar in
 * production), so runners/checkers bind evidence to the runtime artifacts
 * instead of a hardcoded commit SHA or a not-yet-deployed file on disk.
 * Server value surfaces through {@code /v1/status#server_mod_jar_sha256};
 * client value travels in the control-channel hello and surfaces in the
 * observation JSON ({@code client_mod_jar_sha256}).</p>
 */
public final class RealClientBuildIdentity {
    private RealClientBuildIdentity() {}

    private static volatile String cached;

    /** SHA-256 of the loaded artifact containing this class, or "unknown…". */
    public static String modJarSha256() {
        String existing=cached;
        if(existing!=null)return existing;
        String computed;
        try {
            URL source=RealClientBuildIdentity.class
                    .getProtectionDomain().getCodeSource().getLocation();
            java.nio.file.Path path=java.nio.file.Paths.get(source.toURI());
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(InputStream in=java.nio.file.Files.newInputStream(path)) {
                byte[] buf=new byte[65536];
                int n;
                while((n=in.read(buf))>0)
                    digest.update(buf,0,n);
            }
            computed=HexFormat.of().formatHex(digest.digest());
        } catch(Exception unavailable) {
            computed="unknown:"+unavailable.getClass().getSimpleName();
        }
        cached=computed;
        return computed;
    }
}
