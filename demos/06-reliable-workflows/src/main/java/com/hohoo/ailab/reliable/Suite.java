package com.hohoo.ailab.reliable;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class Suite {
    static final List<Map<String,Object>> checks=new ArrayList<Map<String,Object>>();
    static void check(String name,boolean passed,Object observed){
        Map<String,Object> r=new LinkedHashMap<String,Object>();r.put("name",name);r.put("passed",passed);r.put("observed",observed);checks.add(r);
        if(!passed)throw new AssertionError(name+": "+observed);
    }
    static String call(String tool,String key){return "{\"tool\":\""+tool+"\",\"key\":\""+key+"\"}";}
    static void concurrency()throws Exception{
        VersionedSession s=new VersionedSession(2,4);VersionedSession.Ticket a=s.begin("a","slow"),b=s.begin("b","fast");
        CountDownLatch fast=new CountDownLatch(1);List<String> trace=Collections.synchronizedList(new ArrayList<String>());
        AtomicReference<Throwable> failure=new AtomicReference<Throwable>();
        Thread slowThread=new Thread(()->{try{if(!fast.await(2,TimeUnit.SECONDS))throw new AssertionError("latch");trace.add("slow:"+s.commit(a,"old answer","stop"));}catch(Throwable e){failure.set(e);}});
        Thread fastThread=new Thread(()->{try{trace.add("fast:"+s.commit(b,"new answer","stop"));}catch(Throwable e){failure.set(e);}finally{fast.countDown();}});
        slowThread.start();fastThread.start();slowThread.join(3000);fastThread.join(3000);
        check("concurrency/no-lost-update",failure.get()==null&&!slowThread.isAlive()&&trace.equals(Arrays.asList("fast:COMMITTED","slow:STALE")),trace);
        check("concurrency/pair-atomic",s.snapshot().equals(Arrays.asList("fast","new answer")),s.snapshot());
        check("concurrency/idempotent-commit",s.commit(b,"new answer","stop").equals("DUPLICATE")&&s.version()==1,s.version());
        check("concurrency/id-conflict",s.commit(b,"different","stop").equals("ID_CONFLICT"),s.snapshot());
        VersionedSession.Ticket invalid=s.begin("c","question");check("concurrency/incomplete-rejected",s.commit(invalid,"partial","length").equals("INVALID")&&s.version()==1,s.version());
        s.commit(s.begin("c","third"),"third answer","stop");s.commit(s.begin("d","fourth"),"fourth answer","stop");
        check("concurrency/whole-pair-trim",s.snapshot().equals(Arrays.asList("third","third answer","fourth","fourth answer")),s.snapshot());
        boolean foreign=false;try{new VersionedSession(1,1).commit(a,"x","stop");}catch(IllegalArgumentException e){foreign=true;}
        check("concurrency/foreign-ticket",foreign,foreign);
        // Begin and snapshot remain available while an external provider is blocked.
        check("concurrency/snapshot-immutable",a.history.isEmpty(),a.history);
    }
    static void tools()throws Exception{
        Map<String,ReadOnlyTools.Tool> registry=new HashMap<String,ReadOnlyTools.Tool>();AtomicInteger invoked=new AtomicInteger();
        registry.put("lookup",key->{invoked.incrementAndGet();return key.equals("known")?"local owned data":null;});
        try(ReadOnlyTools t=new ReadOnlyTools(registry,1)){
            for(String raw:Arrays.asList("{}","{\"tool\":\"lookup\",\"key\":\"x\",\"key\":\"known\"}","{\"tool\":\"lookup\",\"key\":4}","{\"tool\":\"lookup\",\"key\":\"../secret\"}","{\"tool\":\"lookup\",\"key\":\"x\",\"url\":\"https://example.com\"}","{\"tool\":\"lookup\",\"key\":\"known\"} trailing")){
                check("tools/invalid-"+checks.size(),t.call(raw,1000).status.equals("INVALID"),raw);
            }
            check("tools/allowlist",t.call(call("exec","known"),1000).status.equals("UNKNOWN_TOOL"),invoked.get());
            check("tools/reject-before-execution",invoked.get()==0&&t.calls()==0,invoked.get());
            ReadOnlyTools.Result r=t.call(call("lookup","known"),1000);check("tools/read",r.status.equals("OK")&&r.content.equals("local owned data"),r.status);
            check("tools/budget",t.call(call("lookup","known"),1000).status.equals("BUDGET")&&invoked.get()==1,t.calls());
        }
        try(ReadOnlyTools t=new ReadOnlyTools(registry,2)){check("tools/missing",t.call(call("lookup","absent"),1000).status.equals("NOT_FOUND"),"NOT_FOUND");}
        registry.put("explode",key->{throw new IllegalStateException("private detail");});
        try(ReadOnlyTools t=new ReadOnlyTools(registry,1)){check("tools/error-sanitized",t.call(call("explode","x"),1000).status.equals("TOOL_ERROR"),"TOOL_ERROR");}
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),exited=new CountDownLatch(1);AtomicInteger effects=new AtomicInteger();
        registry.put("blocked",key->{entered.countDown();try{while(release.getCount()>0){try{release.await();}catch(InterruptedException ignored){}}effects.incrementAndGet();return "late";}finally{exited.countDown();}});
        try(ReadOnlyTools t=new ReadOnlyTools(registry,3)){
            ExecutorService caller=Executors.newSingleThreadExecutor();
            try{
                Future<ReadOnlyTools.Result> pending=caller.submit(()->t.call(call("blocked","x"),150));
                if(!entered.await(2,TimeUnit.SECONDS))throw new AssertionError("worker did not enter");
                check("tools/timeout",pending.get(2,TimeUnit.SECONDS).status.equals("TIMEOUT"),"TIMEOUT");
                check("tools/no-queue-after-timeout",t.call(call("lookup","known"),1000).status.equals("BUSY"),t.calls());
                release.countDown();if(!exited.await(2,TimeUnit.SECONDS))throw new AssertionError("worker did not exit");
                check("tools/cancellation-not-termination",effects.get()==1,effects.get());
            }finally{release.countDown();caller.shutdownNow();}
        }
    }
    static List<Map<String,Object>> retrieval()throws Exception{
        JsonObject fixture=JsonParser.parseString(new String(Files.readAllBytes(Paths.get("corpus.json")),StandardCharsets.UTF_8)).getAsJsonObject();
        List<Retrieval.Doc> docs=new ArrayList<Retrieval.Doc>();
        for(JsonElement x:fixture.getAsJsonArray("documents")){JsonObject d=x.getAsJsonObject();docs.add(new Retrieval.Doc(d.get("id").getAsString(),d.get("text").getAsString()));}
        Retrieval index=new Retrieval(docs);List<Map<String,Object>> rows=new ArrayList<Map<String,Object>>();
        for(JsonElement x:fixture.getAsJsonArray("queries")){
            JsonObject q=x.getAsJsonObject();List<Retrieval.Hit> hits=index.search(q.get("query").getAsString(),3);List<String> ids=new ArrayList<String>();List<Double> scores=new ArrayList<Double>();
            for(Retrieval.Hit h:hits){ids.add(h.doc.id);scores.add(h.score);}
            String gold=q.get("gold").isJsonNull()?null:q.get("gold").getAsString();
            Map<String,Object> r=new LinkedHashMap<String,Object>();r.put("id",q.get("id").getAsString());r.put("query",q.get("query").getAsString());r.put("gold",gold);r.put("hits",ids);r.put("scores",scores);r.put("hitAt1",gold!=null&&!ids.isEmpty()&&gold.equals(ids.get(0)));r.put("hitAt3",gold!=null&&ids.contains(gold));rows.add(r);
        }
        List<Retrieval.Hit> h=index.search("timeout read socket",3);
        check("retrieval/expected-first",h.get(0).doc.id.equals("timeouts"),h.get(0).doc.id);
        check("retrieval/quote-bound",Retrieval.citation(h,"timeouts","A read timeout"),"existing exact span");
        check("retrieval/invented-quote",!Retrieval.citation(h,"timeouts","Server never ran"),"rejected");
        check("retrieval/unknown-citation",!Retrieval.citation(h,"absent","A read timeout"),"rejected");
        check("retrieval/empty-query",index.search("",3).isEmpty(),"no hits");
        check("retrieval/no-overlap",index.search("photosynthesis",3).isEmpty(),"no hits");
        check("retrieval/context-budget",Retrieval.pack(h,20).text.isEmpty(),"whole evidence block omitted");
        check("retrieval/pack-exact",Retrieval.pack(h,10000).text.contains("[timeouts]"),"source IDs retained");
        check("retrieval/omitted-citation",!Retrieval.pack(h,20).cites("timeouts","A read timeout"),"omitted evidence rejected");
        boolean duplicate=false;try{new Retrieval(Arrays.asList(docs.get(0),docs.get(0)));}catch(IllegalArgumentException e){duplicate=true;}
        check("retrieval/duplicate-id",duplicate,duplicate);
        return rows;
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("output file required");Path out=Paths.get(args[0]);
        if(Files.exists(out))throw new IllegalArgumentException("output already exists");
        concurrency();tools();List<Map<String,Object>> rows=retrieval();
        Map<String,Object> result=new LinkedHashMap<String,Object>();result.put("java",System.getProperty("java.version"));result.put("at",java.time.Instant.now().toString());result.put("networkRequests",0);result.put("checks",checks);result.put("retrieval",rows);
        Files.createDirectories(out.toAbsolutePath().getParent());Files.write(out,new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(result).getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
        System.out.println("Checks passed: "+checks.size()+"; retrieval cases: "+rows.size()+"; no provider requests.");
    }
}
