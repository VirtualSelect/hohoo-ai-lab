package com.hohoo.ailab.bounded;
import com.google.gson.*;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
/** Single-writer append log; rejects corrupt complete lines, truncates incomplete tail. */
public final class TurnJournal implements AutoCloseable {
 private final FileChannel channel;private final FileLock lock;private final Map<String,ContextBudget.Turn> turns=new LinkedHashMap<>();
 static byte[] record(String id,String q,String a){JsonObject o=new JsonObject();o.addProperty("id",id);o.addProperty("question",q);o.addProperty("answer",a);byte[] payload=o.toString().getBytes(StandardCharsets.UTF_8);return (Base64.getEncoder().encodeToString(payload)+" "+hash(payload)+"\n").getBytes(StandardCharsets.US_ASCII);}
 static String hash(byte[] b){try{StringBuilder s=new StringBuilder();for(byte v:MessageDigest.getInstance("SHA-256").digest(b))s.append(String.format("%02x",v&255));return s.toString();}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
 public TurnJournal(Path path)throws IOException{
  channel=FileChannel.open(path,StandardOpenOption.CREATE,StandardOpenOption.READ,StandardOpenOption.WRITE);FileLock candidate=null;
  try{
   candidate=channel.tryLock();if(candidate==null)throw new IOException("journal already open");
   if(channel.size()>4*1024*1024)throw new IOException("teaching log exceeds 4 MiB");
   ByteBuffer buffer=ByteBuffer.allocate((int)channel.size());while(buffer.hasRemaining()&&channel.read(buffer)!=-1){}byte[] bytes=buffer.array();int begin=0;
   for(int i=0;i<bytes.length;i++)if(bytes[i]=='\n'){
    String[] fields=new String(bytes,begin,i-begin,StandardCharsets.US_ASCII).split(" ");
    if(fields.length!=2)throw new IOException("corrupt complete record");byte[] payload=Base64.getDecoder().decode(fields[0]);if(!hash(payload).equals(fields[1]))throw new IOException("checksum mismatch");
    JsonObject o=JsonParser.parseString(new String(payload,StandardCharsets.UTF_8)).getAsJsonObject();String id=o.get("id").getAsString();if(turns.containsKey(id))throw new IOException("duplicate journal id");turns.put(id,new ContextBudget.Turn(o.get("question").getAsString(),o.get("answer").getAsString()));begin=i+1;
   }
   if(begin<bytes.length){channel.truncate(begin);channel.force(true);}channel.position(begin);lock=candidate;
  }catch(Exception e){if(candidate!=null)candidate.release();channel.close();throw new IOException("cannot open journal",e);}
 }
 public ContextBudget.Turn find(String id){return turns.get(id);}
 public List<ContextBudget.Turn> history(){return new ArrayList<>(turns.values());}
 public synchronized String commit(String id,String q,String a)throws IOException{
  if(id==null||id.isEmpty()||q==null||a==null)throw new IllegalArgumentException("complete turn required");
  ContextBudget.Turn old=turns.get(id);if(old!=null){if(!old.question.equals(q))throw new IOException("id reused for a different question");return old.answer;}
  byte[] bytes=record(id,q,a);if(channel.size()+bytes.length>4*1024*1024)throw new IOException("journal full");ByteBuffer b=ByteBuffer.wrap(bytes);while(b.hasRemaining())channel.write(b);channel.force(true);turns.put(id,new ContextBudget.Turn(q,a));return a;
 }
 public void close()throws IOException{lock.release();channel.close();}
}
