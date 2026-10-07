package com.hohoo.ailab.bounded;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
/** Child process deliberately exits without shutdown hooks. */
public final class CrashWriter {
 public static void main(String[] args)throws Exception{
  Path p=Paths.get(args[0]);String mode=args[1];
  if(mode.equals("verify")){try(TurnJournal j=new TurnJournal(p)){System.out.println(j.history().size());}return;}
  try(TurnJournal j=new TurnJournal(p)){j.commit("base","q","a");}
  if(mode.equals("partial")){byte[] record=TurnJournal.record("new","q2","a2");try(FileChannel c=FileChannel.open(p,StandardOpenOption.APPEND)){c.write(ByteBuffer.wrap(record,0,record.length/2));c.force(true);}}
  if(mode.equals("committed")){try(TurnJournal j=new TurnJournal(p)){j.commit("new","q2","a2");}}
  Runtime.getRuntime().halt(23);
 }
}
