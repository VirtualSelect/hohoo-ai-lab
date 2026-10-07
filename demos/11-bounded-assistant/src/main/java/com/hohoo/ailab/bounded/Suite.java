package com.hohoo.ailab.bounded;
import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
public final class Suite {
 static final List<Map<String,Object>> results=new ArrayList<>();
 static void check(String name,boolean condition,Object evidence){if(!condition)throw new AssertionError(name);Map<String,Object> r=new LinkedHashMap<>();r.put("case",name);r.put("passed",true);r.put("evidence",evidence);results.add(r);}
 public static void main(String[] args)throws Exception{
  Path out=Paths.get(args[0]);Files.createDirectory(out);
  List<ContextBudget.Turn> history=Arrays.asList(new ContextBudget.Turn("hello","world"),new ContextBudget.Turn("接口 \"是什么\"","Java\n接口"));String current="继续🙂",system="只根据已知信息回答。";
  int base=ContextBudget.request(system,Collections.emptyList(),current).length,one=ContextBudget.request(system,history.subList(1,2),current).length,all=ContextBudget.request(system,history,current).length;
  for(int budget:new int[]{base,one,all,all+100}){ContextBudget.Selection s=ContextBudget.select(system,history,current,budget);JsonArray messages=JsonParser.parseString(new String(s.payload,StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("messages");check("budget-"+budget,s.payload.length<=budget&&messages.size()==2+s.kept*2,Arrays.asList(s.kept,s.dropped,s.payload.length));}
  try{ContextBudget.select(system,history,current,base-1);throw new AssertionError();}catch(IllegalArgumentException expected){check("mandatory-overflow",true,base);}
  check("history-not-mutated",history.size()==2,history.size());
  check("bytes-not-characters",base>new String(ContextBudget.request(system,Collections.emptyList(),current),StandardCharsets.UTF_8).length(),base);
  AtomicInteger calls=new AtomicInteger();ExecutorService workers=Executors.newCachedThreadPool();HttpServer server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);server.setExecutor(workers);
  server.createContext("/",exchange->{int call=calls.incrementAndGet();String path=exchange.getRequestURI().getPath();int status=path.equals("/once")&&call>1?200:path.equals("/auth")?401:path.equals("/ok")?200:503;
   if(path.equals("/long"))exchange.getResponseHeaders().set("Retry-After","1");
   byte[] body="local fixture; no LLM".getBytes(StandardCharsets.UTF_8);try{if(path.equals("/slow"))Thread.sleep(200);exchange.sendResponseHeaders(status,body.length);exchange.getResponseBody().write(body);}catch(Exception ignored){}finally{exchange.close();}});server.start();
  try{
   String host="http://localhost:"+server.getAddress().getPort();
   for(String path:new String[]{"ok","once","auth","long","always","slow","unsafe"}){calls.set(0);RetryBudget.Result r=RetryBudget.get(new URL(host+"/"+path),!path.equals("unsafe"),3,path.equals("slow")?40:500);int expected=path.equals("once")?2:path.equals("always")?3:1;
    check("retry-"+path,r.attempts==expected,r);
    if(path.equals("ok")||path.equals("once"))check("success-"+path,"ok".equals(r.outcome),r.status);
   }
  }finally{server.stop(0);workers.shutdownNow();}
  Path journal=out.resolve("turns.log");try(TurnJournal j=new TurnJournal(journal)){j.commit("one","question","answer");check("deduplicate-commit",j.commit("one","question","different").equals("answer"),j.history().size());try{j.commit("one","other","x");throw new AssertionError();}catch(java.io.IOException ok){check("conflicting-id",true,"rejected");}try{new TurnJournal(journal);throw new AssertionError();}catch(java.io.IOException ok){check("second-writer",true,"rejected");}}
  byte[] first=Files.readAllBytes(journal),second=TurnJournal.record("two","第二问","第二答");
  for(int cut=0;cut<second.length;cut++){byte[] truncated=Arrays.copyOf(first,first.length+cut);System.arraycopy(second,0,truncated,first.length,cut);Files.write(journal,truncated);try(TurnJournal j=new TurnJournal(journal)){if(j.history().size()!=1||Files.size(journal)!=first.length)throw new AssertionError("tail "+cut);}}
  check("all-truncated-tails",true,second.length);try(TurnJournal j=new TurnJournal(journal)){j.commit("two","第二问","第二答");}
  try(TurnJournal j=new TurnJournal(journal)){check("restart-two-turns",j.history().size()==2,j.history().size());}
  byte[] corrupt=Files.readAllBytes(journal);corrupt[0]=(byte)'!';Files.write(journal,corrupt);try{new TurnJournal(journal);throw new AssertionError();}catch(java.io.IOException ok){check("complete-corruption-fails-closed",true,"rejected");}
  Files.write(out.resolve("results.json"),new GsonBuilder().setPrettyPrinting().create().toJson(results).getBytes(StandardCharsets.UTF_8));System.out.println("Verified cases: "+results.size()+"; truncated prefixes: "+second.length);
 }
}
