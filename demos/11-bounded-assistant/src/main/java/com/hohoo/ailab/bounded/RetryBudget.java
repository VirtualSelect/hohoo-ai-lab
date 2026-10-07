package com.hohoo.ailab.bounded;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
/** Only callers with an explicitly safe operation may retry. No model calls here. */
public final class RetryBudget {
 public static final class Result {public int attempts,status;public String outcome,body;public long elapsedMs;}
 static int remaining(long deadline)throws InterruptedIOException{
  if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("interrupted");
  long ns=deadline-System.nanoTime();if(ns<=0)throw new InterruptedIOException("total deadline");
  return (int)Math.min(Integer.MAX_VALUE,Math.max(1,TimeUnit.NANOSECONDS.toMillis(ns)));
 }
 public static Result get(URL url,boolean safeToRetry,int maxAttempts,int totalMs)throws IOException,InterruptedException{
  if(maxAttempts<1||maxAttempts>5||totalMs<1)throw new IllegalArgumentException("budget");
  long start=System.nanoTime(),deadline=start+TimeUnit.MILLISECONDS.toNanos(totalMs);Result r=new Result();r.outcome="attempt-limit";
  for(int i=1;i<=maxAttempts;i++){
   HttpURLConnection c=null;
   try{
    remaining(deadline);r.attempts=i;c=(HttpURLConnection)url.openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(remaining(deadline));c.setReadTimeout(remaining(deadline));r.status=c.getResponseCode();
    if(r.status==200){ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=c.getInputStream()){int b;while(true){c.setReadTimeout(remaining(deadline));b=in.read();if(b<0)break;if(out.size()>=4096)throw new IOException("response too large");out.write(b);}}remaining(deadline);r.body=new String(out.toByteArray(),StandardCharsets.UTF_8);r.outcome="ok";break;}
    if(!safeToRetry||(r.status!=429&&r.status!=503)){r.outcome="not-retryable";break;}
    if(i==maxAttempts)break;
    long wait=20L*i;String ra=c.getHeaderField("Retry-After");
    if(ra!=null){try{long seconds=Long.parseLong(ra);if(seconds<0||seconds>86400)throw new NumberFormatException();wait=Math.max(wait,seconds*1000);}catch(NumberFormatException e){r.outcome="unsupported-retry-after";break;}}
    if(wait>=remaining(deadline)){r.outcome="deadline-before-retry";break;}
    c.disconnect();c=null;Thread.sleep(wait);
   }catch(InterruptedIOException e){r.outcome="deadline-or-interrupt";break;}finally{if(c!=null)c.disconnect();}
  }
  r.elapsedMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);return r;
 }
}
