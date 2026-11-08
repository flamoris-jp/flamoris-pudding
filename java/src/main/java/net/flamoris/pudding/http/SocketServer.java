package net.flamoris.pudding.http;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/** One request per connection, bounded sockets, absolute deadline, owned shutdown. */
public final class SocketServer implements AutoCloseable {
    private final ServerSocket listener;
    private final McpEndpoint endpoint;
    private final Semaphore slots;
    private final int deadlineMillis;
    private final ExecutorService workers=Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledThreadPoolExecutor timers=new ScheduledThreadPoolExecutor(1);
    private final Set<Socket> active=ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private Thread acceptor;
    public SocketServer(InetAddress bind,int port,int maxConnections,int deadlineMillis,McpEndpoint endpoint)throws IOException {
        if(port<0||port>65535||maxConnections<1||maxConnections>128||deadlineMillis<100||deadlineMillis>30000)throw new IllegalArgumentException("server limits");
        this.endpoint=endpoint;this.deadlineMillis=deadlineMillis;slots=new Semaphore(maxConnections);timers.setRemoveOnCancelPolicy(true);
        listener=new ServerSocket();listener.setReuseAddress(true);listener.bind(new InetSocketAddress(bind,port),maxConnections);
    }
    public int port(){return listener.getLocalPort();}
    public synchronized void start(){if(closed||acceptor!=null)throw new IllegalStateException("server state");acceptor=Thread.ofPlatform().name("pudding-http-accept").start(this::accept);}
    private void accept(){
        while(!closed){
            try {
                Socket socket=listener.accept();socket.setSoTimeout(deadlineMillis);socket.setTcpNoDelay(true);
                if(!slots.tryAcquire()){try(socket){write(socket,Reply.refused(503),false);}continue;}
                active.add(socket);
                try{workers.submit(()->serve(socket));}catch(RejectedExecutionException e){active.remove(socket);slots.release();socket.close();}
            }catch(IOException e){if(!closed)close();}
        }
    }
    private void serve(Socket socket){
        ScheduledFuture<?> deadline=null;
        try(socket){
            deadline=timers.schedule(()->{try{socket.close();}catch(IOException ignored){}},deadlineMillis,TimeUnit.MILLISECONDS);
            HttpRequest request=null;Reply response;
            var reader=new RequestReader();
            try{request=reader.read(new BufferedInputStream(socket.getInputStream()));response=endpoint.handle(request);}
            catch(HttpFailure e){response=Reply.refused(e.status);}
            catch(IOException e){return;}
            catch(Exception e){response=Reply.refused(500);}
            write(socket,response,reader.parsedMethod()==HttpMethod.HEAD);
        }catch(IOException|RejectedExecutionException ignored){
            // A lost response is reconciled using the domain request_id, never logs.
        }finally{if(deadline!=null)deadline.cancel(false);active.remove(socket);slots.release();}
    }
    private static void write(Socket socket,Reply response,boolean head)throws IOException {
        String reason=switch(response.status()){
            case 200->"OK";case 202->"Accepted";case 204->"No Content";case 400->"Bad Request";
            case 401->"Unauthorized";case 403->"Forbidden";case 404->"Not Found";case 405->"Method Not Allowed";
            case 406->"Not Acceptable";case 411->"Length Required";case 413->"Content Too Large";
            case 415->"Unsupported Media Type";case 417->"Expectation Failed";case 429->"Too Many Requests";
            case 431->"Request Header Fields Too Large";case 503->"Service Unavailable";case 505->"HTTP Version Not Supported";
            default->"Internal Server Error";
        };
        byte[] body=response.body();
        var header=new StringBuilder("HTTP/1.1 ").append(response.status()).append(' ').append(reason).append("\r\n");
        response.headers().forEach((k,v)->header.append(k).append(": ").append(v).append("\r\n"));
        header.append("Content-Length: ").append(body.length).append("\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n");
        header.append("Date: ").append(DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC))).append("\r\n\r\n");
        var out=new BufferedOutputStream(socket.getOutputStream());out.write(header.toString().getBytes(StandardCharsets.US_ASCII));if(!head)out.write(body);out.flush();
    }
    @Override public void close(){
        if(closed)return;closed=true;
        try{listener.close();}catch(IOException ignored){}
        active.forEach(s->{try{s.close();}catch(IOException ignored){}});workers.shutdown();
        try{if(!workers.awaitTermination(20,TimeUnit.SECONDS))workers.shutdownNow();}catch(InterruptedException e){Thread.currentThread().interrupt();workers.shutdownNow();}
        timers.shutdownNow();
    }
}
