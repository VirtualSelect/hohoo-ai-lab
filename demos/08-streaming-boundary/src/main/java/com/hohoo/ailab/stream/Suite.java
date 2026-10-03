package com.hohoo.ailab.stream;
import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class Suite {
 static class Case {String id,expected;byte[] body;int cap=7,status=200,cancelAfter=0,stall=0;String mime="text/event-stream";Case(String i,String e,String s){id=i;expected=e;body=s.getBytes(StandardCharsets.UTF_8);}}
 static String delta(String s){JsonObject d=new JsonObject();d.addProperty("content",s);return "data: {\"choices\":[{\"index\":0,\"delta\":"+d+",\"finish_reason\":null}]}\n\n";}
 static final String FINISH="data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n",DONE="data: [DONE]\n\n",ANSWER="你好，Java 🌱";
 static String good(){return delta("你好，")+delta("Java 🌱")+FINISH+DONE;}
 static List<Case> cases(){
  List<Case> c=new ArrayList<>();Case x;
  x=new Case("one-byte","COMPLETE",good());x.cap=1;c.add(x);
  c.add(new Case("seven-byte","COMPLETE",good()));
  c.add(new Case("crlf","COMPLETE",good().replace("\n","\r\n")));
  c.add(new Case("bare-cr","COMPLETE",good().replace("\n","\r")));
  c.add(new Case("multiline","COMPLETE",good().replace("data: {\"choices\":","data: {\ndata: \"choices\":")));
  c.add(new Case("bom-heartbeat","COMPLETE","\ufeff: ping\n\nid: 5\n\n"+good().replace(FINISH,FINISH+"data: {\"choices\":[],\"usage\":{}}\n\n")));
  c.add(new Case("missing-done","INCOMPLETE_STREAM",delta("你好，")+FINISH));
  c.add(new Case("unterminated-done","INCOMPLETE_STREAM",delta("你好，")+FINISH+"data: [DONE]"));
  c.add(new Case("done-without-finish","PROTOCOL",delta("你好，")+DONE));
  c.add(new Case("length-finish","INCOMPLETE_FINISH",good().replace("\"stop\"","\"length\"")));
  c.add(new Case("provider-error","PROVIDER_ERROR",delta("你好，")+"data: {\"error\":{\"message\":\"fixture\"}}\n\n"));
  c.add(new Case("malformed-json","INVALID_JSON","data: {oops}\n\n"));
  c.add(new Case("duplicate-json","INVALID_JSON","data: {\"choices\":[],\"choices\":[]}\n\n"));
  x=new Case("invalid-utf8","INVALID_UTF8","");x.body=new byte[]{(byte)0xc3,0x28};c.add(x);
  c.add(new Case("oversized-line","LIMIT","data: "+String.join("",Collections.nCopies(8200,"x"))+"\n\n"));
  c.add(new Case("oversized-output","LIMIT",delta(String.join("",Collections.nCopies(4097,"a")))+FINISH+DONE));
  c.add(new Case("after-finish","PROTOCOL",delta("你好，")+FINISH+delta("extra")+DONE));
  x=new Case("wrong-mime","CONTENT_TYPE",good());x.mime="application/json";c.add(x);
  x=new Case("http-503","HTTP_503",good());x.status=503;c.add(x);
  x=new Case("cancel","CANCELLED",good());x.cancelAfter=3;c.add(x);
  x=new Case("read-timeout","READ_TIMEOUT","");x.stall=400;c.add(x);
  c.add(new Case("tool-delta","PROTOCOL","data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[]}}]}\n\n"));
  return c;
 }
 static final class Capped extends FilterInputStream {final int cap;int reads=0;Capped(InputStream in,int cap){super(in);this.cap=cap;}public int read(byte[] b,int o,int l)throws IOException{reads++;return in.read(b,o,Math.min(l,cap));}}
 public static void main(String[] args)throws Exception{
  Path out=Paths.get(args[0]);List<Case> cases=cases();JsonArray rows=new JsonArray();
  ExecutorService executor=Executors.newSingleThreadExecutor();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(executor);
  for(Case c:cases)server.createContext("/"+c.id,e->{e.getResponseHeaders().set("Content-Type",c.mime);e.sendResponseHeaders(c.status,0);try(OutputStream body=e.getResponseBody()){if(c.stall>0){body.flush();try{Thread.sleep(c.stall);}catch(InterruptedException ex){Thread.currentThread().interrupt();}}body.write(c.body);}catch(IOException ignored){/* expected client cancellation */}finally{e.close();}});
  server.start();
  try{
   for(Case c:cases){
    Files.write(out.resolve(c.id+".sse"),c.body);List<String> history=new ArrayList<>(Arrays.asList("U:earlier","A:earlier"));String before=history.toString();
    StreamReader reader=new StreamReader();String status="",answer="";int http=0,reads=0;Capped stream=null;
    HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:"+server.getAddress().getPort()+"/"+c.id).openConnection();
    connection.setConnectTimeout(1000);connection.setReadTimeout(c.stall>0?100:1000);connection.setInstanceFollowRedirects(false);
    try{
     http=connection.getResponseCode();if(http!=200)throw new StreamReader.Failure("HTTP_"+http);
     if(!"text/event-stream".equals(connection.getContentType().split(";")[0].trim().toLowerCase(Locale.ROOT)))throw new StreamReader.Failure("CONTENT_TYPE");
     try(Capped in=new Capped(connection.getInputStream(),c.cap)) {stream=in;answer=reader.read(in,s->{if(c.cancelAfter>0&&s.length()>=c.cancelAfter)throw new StreamReader.Cancelled();});}
     history.add("U:question");history.add("A:"+answer);status="COMPLETE";
    }catch(StreamReader.Cancelled e){status="CANCELLED";}catch(SocketTimeoutException e){status="READ_TIMEOUT";}catch(CharacterCodingException e){status="INVALID_UTF8";}catch(StreamReader.Failure e){status=e.code;}finally{connection.disconnect();if(stream!=null)reads=stream.reads;}
    if(!status.equals(c.expected))throw new AssertionError(c.id+":"+status+" expected "+c.expected);
    if(status.equals("COMPLETE")){if(!answer.equals(ANSWER)||history.size()!=4)throw new AssertionError(c.id);}else if(!before.equals(history.toString()))throw new AssertionError("partial commit");
    JsonObject row=new JsonObject();row.addProperty("case",c.id);row.addProperty("expected",c.expected);row.addProperty("status",status);row.addProperty("http",http);row.addProperty("readCapBytes",c.cap);row.addProperty("readCalls",reads);row.addProperty("preview",reader.preview());row.addProperty("historySize",history.size());row.addProperty("committed",status.equals("COMPLETE"));
    rows.add(row);System.out.println(c.id+" "+status);
   }
  }finally{server.stop(0);executor.shutdownNow();executor.awaitTermination(2,TimeUnit.SECONDS);}
  JsonObject result=new JsonObject();result.addProperty("providerRequests",0);result.addProperty("loopbackRequests",cases.size());result.addProperty("java",System.getProperty("java.version"));result.add("cases",rows);
  Files.write(out.resolve("results.json"),new GsonBuilder().setPrettyPrinting().create().toJson(result).getBytes(StandardCharsets.UTF_8));
 }
}
