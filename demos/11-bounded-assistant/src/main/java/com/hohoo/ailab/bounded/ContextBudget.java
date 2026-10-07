package com.hohoo.ailab.bounded;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** A wire-byte bound, never a token estimate; keeps a contiguous suffix of whole turns. */
public final class ContextBudget {
 public static final class Turn { public final String question,answer; public Turn(String q,String a){question=Objects.requireNonNull(q);answer=Objects.requireNonNull(a);} }
 private static JsonObject message(String role,String text){JsonObject o=new JsonObject();o.addProperty("role",role);o.addProperty("content",text);return o;}
 public static byte[] request(String system,List<Turn> turns,String question){
  JsonObject body=new JsonObject();body.addProperty("model","agnes-3.0-flash");JsonArray messages=new JsonArray();messages.add(message("system",system));
  for(Turn t:turns){messages.add(message("user",t.question));messages.add(message("assistant",t.answer));}
  messages.add(message("user",question));body.add("messages",messages);return body.toString().getBytes(StandardCharsets.UTF_8);
 }
 public static final class Selection { public final byte[] payload;public final int kept,dropped;Selection(byte[] p,int k,int d){payload=p;kept=k;dropped=d;} }
 public static Selection select(String system,List<Turn> history,String current,int maxBytes){
  if(maxBytes<1)throw new IllegalArgumentException("positive budget required");
  for(int start=0;start<=history.size();start++){
   byte[] data=request(system,history.subList(start,history.size()),current);
   if(data.length<=maxBytes)return new Selection(data,history.size()-start,start);
  }
  throw new IllegalArgumentException("system and current message exceed wire-byte budget");
 }
}
