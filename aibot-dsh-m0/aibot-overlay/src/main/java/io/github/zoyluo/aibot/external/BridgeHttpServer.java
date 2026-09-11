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
            } else if(method.equals("POST") && path.equals("/v1/view")) {
                // MC-2A0 只读认知查询:query semantics,POST 只是参数通道。
                result=kernel.view();
            } else if(method.equals("POST") && path.equals("/v1/inspect")) {
                // MC-2A0.1:ref/detail fail-fast 校验同步返回(400/404/429/503),materialize
                // 本体由 server 线程查询队列按每 tick 预算完成,HTTP 线程无锁等待。
                Map<String,String> inspect=query(x.getRequestURI().getRawQuery());
                String ref=inspect.get("ref");
                String rawDetail=inspect.get("detail");
                String level=rawDetail==null||rawDetail.isBlank()?"summary":rawDetail;
                result=awaitEvidence(kernel.submitInspectQuery(ref,rawDetail),kernel.cognitiveGameTime(),ref,level);
            } else if(method.equals("POST") && path.equals("/v1/inspect-local")) {
                Map<String,String> local=query(x.getRequestURI().getRawQuery());
                int radius;
                try { radius=Integer.parseInt(local.getOrDefault("radius","4")); }
                catch(NumberFormatException bad) { throw new BridgeFault(400,"invalid_radius"); }
                result=awaitLocal(kernel.submitLocalQuery(radius,local.get("detail")));
            } else if(method.equals("GET") && path.equals("/v1/graphs")) {
                result=kernel.graphList();
            } else if(method.equals("POST") && path.equals("/v1/graphs/opportunity")) {
                Map<String,String> q=query(x.getRequestURI().getRawQuery());
                result=kernel.graphPlanOpportunity(lease,q.get("plan_key"),q.get("ref")); status=201;
            } else if(path.startsWith("/v1/graphs/")) {
                String[] parts=path.split("/",-1);
                if(parts.length==4 && method.equals("GET")) {
                    result=kernel.graphInspect(parts[3]);
                } else if(parts.length==5 && method.equals("POST") && "run-next".equals(parts[4])) {
                    result=kernel.graphRunNext(lease,request,parts[3]); status=202;
                } else if(parts.length==5 && method.equals("POST") && "cancel".equals(parts[4])) {
                    result=kernel.graphCancel(lease,parts[3],query(x.getRequestURI().getRawQuery()).get("reason"));
                } else throw new BridgeFault(404,"route_not_found");
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
    /** HTTP 线程无锁等待 server 线程完成局部查询(kernel synchronized,持锁等待会死锁)。 */
    private static Map<String,Object> awaitLocal(java.util.concurrent.CompletableFuture<String> future) {
        return Map.of("schema","mc.local_view.v0_wrapper","snapshot",new JsonOutput.Raw(awaitJson(future)));
    }
    /** MC-2A0.1:等待单个 evidence 的 on-demand materialize 并包回 mc.evidence.v0 响应。 */
    private static Map<String,Object> awaitEvidence(java.util.concurrent.CompletableFuture<String> future,
                                                    long gameTime,String ref,String detail) {
        Map<String,Object> meta=new LinkedHashMap<>();
        meta.put("generated_game_time",gameTime);
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("schema","mc.evidence.v0");
        out.put("ref",ref);
        out.put("detail",detail);
        out.put("meta",meta);
        out.put("evidence",new JsonOutput.Raw(awaitJson(future)));
        return out;
    }
    private static String awaitJson(java.util.concurrent.CompletableFuture<String> future) {
        try { return future.get(5,TimeUnit.SECONDS); }
        catch(java.util.concurrent.TimeoutException timeout) { throw new BridgeFault(503,"query_timeout"); }
        catch(java.util.concurrent.ExecutionException execution) {
            if(execution.getCause() instanceof BridgeFault fault) throw fault;
            throw new BridgeFault(500,"query_failed");
        }
        catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new BridgeFault(503,"server_stopping"); }
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
