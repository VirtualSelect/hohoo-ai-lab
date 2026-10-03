package com.hohoo.ailab.reliable;
import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
/** Single worker, zero queue. Tool output is data, never executable instructions. */
public final class ReadOnlyTools implements AutoCloseable {
    public interface Tool { String read(String key) throws Exception; }
    public static final class Result {
        public final String status,content;
        Result(String status,String content){this.status=status;this.content=content;}
    }
    private final Map<String,Tool> tools;
    private final ThreadPoolExecutor pool;
    private final int maxCalls;
    private int calls;
    public ReadOnlyTools(Map<String,Tool> tools,int maxCalls){
        if(maxCalls<1)throw new IllegalArgumentException("budget");
        this.tools=Collections.unmodifiableMap(new HashMap<String,Tool>(tools));this.maxCalls=maxCalls;
        pool=new ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,new SynchronousQueue<Runnable>(),r->{
            Thread t=new Thread(r,"readonly-demo");t.setDaemon(true);return t;
        },new ThreadPoolExecutor.AbortPolicy());
    }
    private static Map<String,String> parse(String raw) throws IOException {
        if(raw==null||raw.length()>4096)throw new IOException("size");
        JsonReader r=new JsonReader(new StringReader(raw));r.setLenient(false);
        Map<String,String> v=new HashMap<String,String>();r.beginObject();
        while(r.hasNext()){
            String k=r.nextName();
            if(v.containsKey(k)||r.peek()!=JsonToken.STRING)throw new IOException("duplicate or non-string");
            v.put(k,r.nextString());
        }
        r.endObject();if(r.peek()!=JsonToken.END_DOCUMENT)throw new IOException("trailing");
        if(!v.keySet().equals(new HashSet<String>(Arrays.asList("tool","key"))))throw new IOException("fields");
        if(!v.get("key").matches("[a-z][a-z0-9-]{0,63}"))throw new IOException("key");
        return v;
    }
    public Result call(String raw,long timeoutMs){
        if(timeoutMs<1)throw new IllegalArgumentException("timeout");
        Map<String,String> args;
        try{args=parse(raw);}catch(Exception e){return new Result("INVALID",null);}
        Tool tool=tools.get(args.get("tool"));if(tool==null)return new Result("UNKNOWN_TOOL",null);
        Future<String> task;
        synchronized(this){
            if(calls>=maxCalls)return new Result("BUDGET",null);
            try{task=pool.submit(()->tool.read(args.get("key")));calls++;}
            catch(RejectedExecutionException e){return new Result("BUSY",null);}
        }
        try{
            String value=task.get(timeoutMs,TimeUnit.MILLISECONDS);
            if(value==null||value.isEmpty())return new Result("NOT_FOUND",null);
            if(value.length()>8192)return new Result("TOO_LARGE",null);
            return new Result("OK",value);
        }catch(TimeoutException e){task.cancel(true);return new Result("TIMEOUT",null);}
        catch(InterruptedException e){task.cancel(true);Thread.currentThread().interrupt();return new Result("CANCELLED",null);}
        catch(ExecutionException e){return new Result("TOOL_ERROR",null);}
    }
    public synchronized int calls(){return calls;}
    public void close(){pool.shutdownNow();}
}
