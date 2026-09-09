package io.github.zoyluo.aibot.external;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Loopback-only adapter. Mutations are admitted by the kernel, never run in HTTP workers. */
public final class BridgeHttpServer implements AutoCloseable {
    private final HttpServer server;
    private final ThreadPoolExecutor workers;
    private final BridgeKernel kernel;
    private final byte[] bearer;
    private final Semaphore eventReaders=new Semaphore(2);
    public BridgeHttpServer(BridgeKernel kernel,int port,String bearer) throws IOException {
        if(bearer==null || bearer.length()<32 || bearer.length()>256 || !bearer.matches("[A-Za-z0-9_-]+"))
            throw new IllegalArgumentException("AIBOT_BRIDGE_TOKEN must be 32..256 URL-safe characters");
        this.kernel=kernel; this.bearer=bearer.getBytes(StandardCharsets.UTF_8);
        server=HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),port),16);
        workers=new ThreadPoolExecutor(4,8,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32),r->{
            Thread t=new Thread(r,"aibot-bridge-http"); t.setDaemon(true); return t;
        },new ThreadPoolExecutor.AbortPolicy());
        server.setExecutor(workers); server.createContext("/",this::handle);
    }
    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }
    private void handle(HttpExchange x) throws IOException {
        try {
            if(x.getRequestHeaders().getFirst("Origin")!=null) throw new BridgeFault(403,"browser_origin_not_allowed");
            String auth=x.getRequestHeaders().getFirst("Authorization");
            if(auth==null || !auth.startsWith("Bearer ") || !MessageDigest.isEqual(bearer,auth.substring(7).getBytes(StandardCharsets.UTF_8)))
                throw new BridgeFault(401,"unauthorized");
            String method=x.getRequestMethod(),path=x.getRequestURI().getPath();
            String lease=x.getRequestHeaders().getFirst("X-Control-Token");
            String request=x.getRequestHeaders().getFirst("X-Request-Id");
            Object result; int status=200;
            if(method.equals("GET") && path.equals("/v1/status")) result=kernel.status();
            else if(method.equals("GET") && path.equals("/v1/observe")) result=kernel.observe();
            else if(method.equals("POST") && path.equals("/v1/lease"))
                result=kernel.claim(x.getRequestHeaders().getFirst("X-Owner-Id"),lease);
            else if(method.equals("POST") && path.equals("/v1/lease/renew")) result=kernel.renew(lease);
            else if(method.equals("DELETE") && path.equals("/v1/lease")) result=kernel.release(lease);
            else if(method.equals("GET") && path.equals("/v1/events")) {
                if(!eventReaders.tryAcquire()) throw new BridgeFault(429,"too_many_event_readers");
                try {
                    Map<String,String> q=query(x.getRequestURI().getRawQuery());
                    String epoch=q.getOrDefault("epoch","");
                    long after=Long.parseLong(q.getOrDefault("after","0"));
                    int wait=Integer.parseInt(q.getOrDefault("wait_ms","0"));
                    result=kernel.journal().waitAfter(epoch,after,wait);
                } finally { eventReaders.release(); }
            } else if(method.equals("GET") && path.startsWith("/v1/requests/")) {
                result=kernel.requestStatus(segment(path,3));
            } else if(path.startsWith("/v1/executions/")) {
                String[] parts=path.split("/",-1);
                if(parts.length==4 && method.equals("POST")) {
                    String type=x.getRequestHeaders().getFirst("Content-Type");
                    if(type==null || !type.split(";",2)[0].trim().equalsIgnoreCase("application/json"))
                        throw new BridgeFault(415,"json_content_type_required");
                    result=kernel.submit(lease,request,parts[3],readBody(x)); status=202;
                } else if(parts.length==4 && method.equals("GET")) result=kernel.execution(parts[3]);
                else if(parts.length==5 && method.equals("POST")) {
                    result=kernel.control(lease,request,parts[3],parts[4]); status=202;
                } else throw new BridgeFault(404,"route_not_found");
            } else throw new BridgeFault(404,"route_not_found");
            reply(x,status,Map.of("ok",true,"data",result));
        } catch(BridgeFault e) { reply(x,e.status,Map.of("ok",false,"error",e.code)); }
        catch(NumberFormatException e) { reply(x,400,Map.of("ok",false,"error","invalid_number")); }
        catch(IllegalArgumentException e) { reply(x,400,Map.of("ok",false,"error","invalid_request")); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); reply(x,503,Map.of("ok",false,"error","server_stopping")); }
        catch(RuntimeException e) { reply(x,500,Map.of("ok",false,"error","internal_error_do_not_blindly_retry_mutations")); }
        finally { x.close(); }
    }
    private static String segment(String path,int index) {
        String[] parts=path.split("/",-1);
        if(parts.length!=index+1 || parts[index].isEmpty()) throw new BridgeFault(404,"route_not_found");
        return parts[index];
    }
    private static Map<String,String> query(String raw) {
        Map<String,String> out=new HashMap<>();
        if(raw==null) return out;
        if(raw.length()>1024) throw new BridgeFault(400,"query_too_long");
        for(String part:raw.split("&")) {
            String[] entry=part.split("=",2);
            String key=URLDecoder.decode(entry[0],StandardCharsets.UTF_8);
            String value=entry.length==2?URLDecoder.decode(entry[1],StandardCharsets.UTF_8):"";
            if(out.put(key,value)!=null) throw new BridgeFault(400,"duplicate_query_parameter");
        }
        return out;
    }
    private static String readBody(HttpExchange x) throws IOException {
        String len=x.getRequestHeaders().getFirst("Content-Length");
        if(len!=null && Long.parseLong(len)>16384) throw new BridgeFault(413,"arguments_too_large");
        byte[] bytes=x.getRequestBody().readNBytes(16385);
        if(bytes.length>16384) throw new BridgeFault(413,"arguments_too_large");
        String value=StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        if(value.isBlank()) throw new BridgeFault(400,"empty_arguments");
        return value;
    }
    private static void reply(HttpExchange x,int status,Object value) throws IOException {
        byte[] data=JsonOutput.encode(value).getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
        x.getResponseHeaders().set("Cache-Control","no-store");
        x.getResponseHeaders().set("X-Content-Type-Options","nosniff");
        x.sendResponseHeaders(status,data.length);
        try(OutputStream out=x.getResponseBody()) { out.write(data); }
    }
    @Override public void close() { server.stop(0); workers.shutdownNow(); }
}
