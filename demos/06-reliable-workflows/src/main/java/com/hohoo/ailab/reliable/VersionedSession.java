package com.hohoo.ailab.reliable;
import java.util.*;
/** In-memory optimistic commit. Sending is deliberately outside the monitor lock. */
public final class VersionedSession {
    public static final class Ticket {
        private final Object owner;
        public final long version;
        public final String id,question;
        public final List<String> history;
        private Ticket(Object owner,long version,String id,String question,List<String> h){
            this.owner=owner;this.version=version;this.id=id;this.question=question;
            history=Collections.unmodifiableList(new ArrayList<String>(h));
        }
    }
    private long version;
    private final List<String> turns=new ArrayList<String>();
    private final LinkedHashMap<String,List<String>> receipts=new LinkedHashMap<String,List<String>>();
    private final int pairs,receiptLimit;
    public VersionedSession(int pairs,int receiptLimit){
        if(pairs<1||receiptLimit<1)throw new IllegalArgumentException("limits");
        this.pairs=pairs;this.receiptLimit=receiptLimit;
    }
    public synchronized Ticket begin(String id,String question){
        if(id==null||id.isEmpty()||question==null||question.trim().isEmpty())throw new IllegalArgumentException("input");
        return new Ticket(this,version,id,question,turns);
    }
    public synchronized String commit(Ticket t,String reply,String finish){
        if(t==null||t.owner!=this)throw new IllegalArgumentException("foreign ticket");
        if(reply==null||reply.trim().isEmpty()||!"stop".equals(finish))return "INVALID";
        List<String> old=receipts.get(t.id);
        if(old!=null)return old.equals(Arrays.asList(t.question,reply))?"DUPLICATE":"ID_CONFLICT";
        if(t.version!=version)return "STALE";
        turns.add(t.question);turns.add(reply);
        while(turns.size()>pairs*2)turns.subList(0,2).clear();
        receipts.put(t.id,Arrays.asList(t.question,reply));
        while(receipts.size()>receiptLimit)receipts.remove(receipts.keySet().iterator().next());
        version++;
        return "COMMITTED";
    }
    public synchronized long version(){return version;}
    public synchronized List<String> snapshot(){return new ArrayList<String>(turns);}
}
