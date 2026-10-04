package com.hohoo.ailab.session;
import com.google.gson.*;
import com.hohoo.ailab.stream.StreamReader;
import java.io.*;import java.nio.charset.StandardCharsets;import java.nio.file.*;import java.util.*;import java.util.concurrent.*;

public final class Suite {
    static final String[] CASES={"normal","cancel","clear","supersede-old-first","supersede-new-first","late-preview","duplicate-complete","truncated","late-error"};
    static void await(CountDownLatch l){try{if(!l.await(5,TimeUnit.SECONDS))throw new IllegalStateException("schedule timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
    static String wire(boolean full){return "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"answer-A\"},\"finish_reason\":null}]}\n\n"+(full?"data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n":"");}
    static Map<String,Object> run(String name,boolean guarded)throws Exception{
        Session s=new Session(guarded);Session.Ticket a=s.begin("A","question-A");
        CountDownLatch reached=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService pool=Executors.newSingleThreadExecutor();
        Future<?> worker=pool.submit(()->{
            try{
                if(name.equals("late-error")){reached.countDown();await(release);s.fail(a);return;}
                String answer=new StreamReader().read(new ByteArrayInputStream(wire(!name.equals("truncated")).getBytes(StandardCharsets.UTF_8)),text->{
                    if(!name.equals("late-preview"))s.preview(a,text);
                    reached.countDown();await(release);
                    if(name.equals("late-preview"))s.preview(a,text);
                });
                s.complete(a,answer);if(name.equals("duplicate-complete"))s.complete(a,answer);
            }catch(IOException e){s.fail(a);}
        });
        try{
            await(reached);Session.Ticket b=null;
            if(name.equals("cancel"))s.cancel();if(name.equals("clear"))s.clear();
            if(name.startsWith("supersede")||name.equals("late-preview")||name.equals("late-error")){
                b=s.begin("B","question-B");s.preview(b,"answer-B");
                if(name.equals("supersede-new-first"))s.complete(b,"answer-B");
            }
            release.countDown();worker.get(5,TimeUnit.SECONDS);
            Map<String,Object> middle=s.snapshot();
            if(b!=null&&!name.equals("supersede-new-first"))s.complete(b,"answer-B");
            Map<String,Object> r=s.snapshot();r.put("case",name);r.put("policy",guarded?"owned":"naive");r.put("before_new_complete",middle);return r;
        }finally{release.countDown();pool.shutdownNow();if(!pool.awaitTermination(5,TimeUnit.SECONDS))throw new IllegalStateException("worker leak");}
    }
    static void contracts(){
        Session a=new Session(true),b=new Session(true);Session.Ticket t=a.begin("one","q");
        if(a.begin("one","q")!=null||a.begin("one","other")!=null||b.preview(t,"x")||b.complete(t,"x"))throw new AssertionError("identity");
        a.cancel();if(a.preview(t,"x")||a.complete(t,"x"))throw new AssertionError("cancel");
        for(int i=0;i<10;i++){Session.Ticket n=a.begin("id"+i,"q"+i);if(!a.complete(n,"a"+i))throw new AssertionError("commit");}
        if(((List<?>)a.snapshot().get("history")).size()!=16)throw new AssertionError("pairs");
        // Bounded receipts are explicitly NOT permanent idempotency.
        if(a.begin("one","q")==null)throw new AssertionError("eviction");
        boolean bad=false;try{a.begin("","q");}catch(IllegalArgumentException e){bad=true;}if(!bad)throw new AssertionError("input");
    }
    public static void main(String[] args)throws Exception{
        contracts();List<Map<String,Object>> rows=new ArrayList<>();for(String n:CASES)for(boolean g:new boolean[]{false,true})rows.add(run(n,g));
        Map<String,Object> result=new LinkedHashMap<>();result.put("java",System.getProperty("java.version"));result.put("cases",rows);result.put("contract_checks",7);
        Files.write(Paths.get(args[0]).resolve("results.json"),new GsonBuilder().setPrettyPrinting().create().toJson(result).getBytes(StandardCharsets.UTF_8));System.out.println("18 deterministic two-thread schedules completed; 7 contract groups checked.");
    }
}
