package com.hohoo.ailab.transport;
import com.google.gson.*;
import com.hohoo.ailab.session.Session;
import com.hohoo.ailab.stream.StreamReader;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Local transport fixture. Future status and worker termination are separate observations. */
public final class Suite {
  static void headers(InputStream in) throws IOException {
    int match=0; byte[] end={13,10,13,10};
    for(int n=0;n<8192;n++) {int b=in.read(); if(b<0)throw new EOFException("headers");
      match=b==end[match]?match+1:(b==13?1:0); if(match==4)return;}
    throw new IOException("headers too long");
  }
  static void send(OutputStream out,String s) throws IOException {out.write(s.getBytes(StandardCharsets.UTF_8));out.flush();}
  static String delta(String s){return "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\""+s+"\"},\"finish_reason\":null}]}\n\n";}
  static final String END="data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
  static Map<String,Object> run(String condition,String policy,int repeat) throws Exception {
    CountDownLatch release=new CountDownLatch(1), preview=new CountDownLatch(1), exited=new CountDownLatch(1);
    AtomicInteger work=new AtomicInteger(), writeErrors=new AtomicInteger();AtomicReference<Throwable> serverFailure=new AtomicReference<>();
    ServerSocket listener=new ServerSocket(0,1,InetAddress.getLoopbackAddress());listener.setSoTimeout(3000);
    Thread server=new Thread(()->{try(Socket s=listener.accept()) {
      s.setSoTimeout(3000);headers(s.getInputStream());OutputStream out=s.getOutputStream();
      send(out,"HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n"+delta("Hello"));
      if(condition.equals("complete")){send(out,END);return;}
      if(condition.endsWith("stalled")){if(!release.await(5,TimeUnit.SECONDS))throw new IOException("release timeout");return;}
      // This fixture intentionally continues its bounded work after a failed client write.
      for(int i=0;i<8;i++){Thread.sleep(80);work.incrementAndGet();try{send(out,delta("."));}catch(IOException e){writeErrors.incrementAndGet();}}
      try{send(out,END);}catch(IOException e){writeErrors.incrementAndGet();}
    }catch(Throwable e){serverFailure.set(e);}},"local-http-fixture");server.start();
    Socket socket=new Socket();socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(),listener.getLocalPort()),1000);socket.setSoTimeout(500);
    send(socket.getOutputStream(),"GET /stream HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
    Session session=new Session(true);Session.Ticket ticket=session.begin("request-1","question");
    AtomicReference<String> outcome=new AtomicReference<>();AtomicLong end=new AtomicLong(),action=new AtomicLong();
    AtomicBoolean cancelledNow=new AtomicBoolean(),exitedNow=new AtomicBoolean();
    long start=System.nanoTime();
    FutureTask<Void> future=new FutureTask<>(()->{try {
      headers(socket.getInputStream());String answer=new StreamReader().read(socket.getInputStream(),text->{session.preview(ticket,text);preview.countDown();});
      outcome.set(session.complete(ticket,answer)?"COMMITTED":"STALE_COMPLETE");
    }catch(Exception e){outcome.set(e.getClass().getSimpleName());session.fail(ticket);}
    finally{end.set(System.nanoTime());exited.countDown();}return null;});
    Thread worker=new Thread(future,"response-reader");worker.start();
    Runnable cancel=()->{action.set(System.nanoTime());session.cancel();future.cancel(true);cancelledNow.set(future.isCancelled());exitedNow.set(exited.getCount()==0);
      if(policy.equals("close-socket"))try{socket.close();}catch(IOException e){throw new UncheckedIOException(e);}};
    ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();ScheduledFuture<?> alarm=null;
    try {
      if(condition.startsWith("deadline"))alarm=timer.schedule(cancel,Math.max(0,200_000_000L-(System.nanoTime()-start)),TimeUnit.NANOSECONDS);
      if(!preview.await(3,TimeUnit.SECONDS))throw new AssertionError("missing preview");
      if(condition.startsWith("cancel"))cancel.run();
      if(!exited.await(5,TimeUnit.SECONDS))throw new AssertionError("reader did not terminate");
      if(alarm!=null)alarm.get(3,TimeUnit.SECONDS);
    }finally{release.countDown();socket.close();timer.shutdownNow();worker.join(3000);server.join(3000);listener.close();}
    if(server.isAlive()||worker.isAlive()||serverFailure.get()!=null)throw new AssertionError("fixture failure",serverFailure.get());
    Map<String,Object> row=new LinkedHashMap<>();row.put("condition",condition);row.put("policy",policy);row.put("repeat",repeat);
    row.put("outcome",outcome.get());row.put("futureCancelledAtAction",cancelledNow.get());row.put("workerExitedAtAction",exitedNow.get());
    row.put("actionMs",action.get()==0?null:(action.get()-start)/1e6);row.put("exitMs",(end.get()-start)/1e6);
    row.put("afterActionMs",action.get()==0?null:(end.get()-action.get())/1e6);row.put("serverWorkSteps",work.get());row.put("serverWriteErrors",writeErrors.get());row.put("session",session.snapshot());
    return row;
  }
  public static void main(String[] args)throws Exception {
    Path out=Paths.get(args[0]);Files.createDirectories(out);List<Map<String,Object>> rows=new ArrayList<>();
    for(String condition:Arrays.asList("complete","cancel-stalled","cancel-dripping","deadline-stalled","deadline-dripping"))
      for(String policy:Arrays.asList("interrupt-only","close-socket"))for(int r=0;r<3;r++){Map<String,Object> row=run(condition,policy,r);rows.add(row);System.out.println(condition+" "+policy+" "+row.get("outcome"));}
    Gson gson=new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    Files.write(out.resolve("results.json"),gson.toJson(rows).getBytes(StandardCharsets.UTF_8));
    Map<String,String> env=new LinkedHashMap<>();for(String k:Arrays.asList("java.version","java.vm.name","os.name","os.arch"))env.put(k,System.getProperty(k));
    Files.write(out.resolve("environment.json"),gson.toJson(env).getBytes(StandardCharsets.UTF_8));
  }
}
