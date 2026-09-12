package io.github.zoyluo.aibot.external.realclient;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Length-prefixed loopback protocol shared by the dedicated server and Bob's Fabric client. */
public final class RealClientWire {
    public static final int PROTOCOL_VERSION=1;
    public static final int MAX_FRAME_BYTES=64*1024;

    private RealClientWire() {}

    public static JsonObject read(DataInputStream input)throws IOException {
        int length;
        try {
            length=input.readInt();
        } catch(EOFException end) {
            throw end;
        }
        if(length<2 || length>MAX_FRAME_BYTES)
            throw new IOException("real_client_frame_length_invalid:"+length);
        byte[] bytes=input.readNBytes(length);
        if(bytes.length!=length)throw new EOFException("real_client_frame_truncated");
        try {
            return JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch(RuntimeException invalid) {
            throw new IOException("real_client_frame_json_invalid",invalid);
        }
    }

    public static void write(DataOutputStream output,JsonObject message)throws IOException {
        byte[] bytes=message.toString().getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_FRAME_BYTES)
            throw new IOException("real_client_frame_too_large");
        output.writeInt(bytes.length);
        output.write(bytes);
        output.flush();
    }

    public static String requiredString(JsonObject object,String key,int max)throws IOException {
        if(!object.has(key) || !object.get(key).isJsonPrimitive()
                || !object.getAsJsonPrimitive(key).isString())
            throw new IOException("real_client_missing_string:"+key);
        String value=object.get(key).getAsString();
        if(value.isBlank() || value.length()>max || value.chars().anyMatch(c->c<0x20))
            throw new IOException("real_client_invalid_string:"+key);
        return value;
    }

    public static String optionalString(JsonObject object,String key,String fallback,int max)
            throws IOException {
        if(!object.has(key))return fallback;
        return requiredString(object,key,max);
    }
}
