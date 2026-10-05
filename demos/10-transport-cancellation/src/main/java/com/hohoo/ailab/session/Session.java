package com.hohoo.ailab.session;
import java.util.*;

/** One in-process conversation. Slow stream work must remain outside these locks. */
public final class Session {
    public static final class Ticket {
        private final Session owner; public final long epoch; public final String id,question;
        private Ticket(Session owner,long epoch,String id,String question){this.owner=owner;this.epoch=epoch;this.id=id;this.question=question;}
    }
    private final boolean guarded;
    private long epoch=0; private Ticket active;
    private String preview="";
    private final List<String> history=new ArrayList<>();
    private final LinkedHashMap<String,String> used=new LinkedHashMap<>();
    private final List<Map<String,Object>> events=new ArrayList<>();
    public Session(boolean guarded){this.guarded=guarded;}
    private void event(String kind,Ticket t,String result){
        Map<String,Object> e=new LinkedHashMap<>();e.put("n",events.size());e.put("kind",kind);e.put("ticket",t==null?null:t.id);e.put("result",result);e.put("epoch",epoch);e.put("preview",preview);e.put("history",new ArrayList<>(history));events.add(e);
    }
    public synchronized Ticket begin(String id,String question){
        if(id==null||id.trim().isEmpty()||id.length()>128||question==null||question.trim().isEmpty()||question.length()>4096)throw new IllegalArgumentException("input");
        if(used.containsKey(id)){event("begin",null,used.get(id).equals(question)?"DUPLICATE_ID":"ID_CONFLICT");return null;}
        if(active!=null)event("supersede",active,"INVALIDATED");
        active=new Ticket(this,++epoch,id,question);preview="";used.put(id,question);
        while(used.size()>8)used.remove(used.keySet().iterator().next());
        event("begin",active,"STARTED");return active;
    }
    private boolean accepts(Ticket t){return t!=null&&t.owner==this&&(!guarded||(t==active&&t.epoch==epoch));}
    public synchronized boolean preview(Ticket t,String text){
        if(text==null||text.length()>4096)throw new IllegalArgumentException("preview");
        boolean ok=accepts(t);if(ok)preview=text;event("preview",t,ok?"ACCEPTED":"STALE");return ok;
    }
    public synchronized boolean complete(Ticket t,String answer){
        if(answer==null||answer.trim().isEmpty()||answer.length()>4096)throw new IllegalArgumentException("answer");
        boolean ok=accepts(t);
        if(ok){history.add(t.question);history.add(answer);while(history.size()>16){history.remove(0);history.remove(0);}preview="";active=null;}
        event("complete",t,ok?"COMMITTED":"STALE");return ok;
    }
    public synchronized void cancel(){Ticket t=active;active=null;++epoch;preview="";event("cancel",t,"INVALIDATED");}
    public synchronized void clear(){Ticket t=active;active=null;++epoch;preview="";history.clear();event("clear",t,"INVALIDATED");}
    public synchronized void fail(Ticket t){
        if(accepts(t)){active=null;++epoch;preview="";event("fail",t,"FAILED");}else event("fail",t,"STALE");
    }
    public synchronized Map<String,Object> snapshot(){Map<String,Object> r=new LinkedHashMap<>();r.put("history",new ArrayList<>(history));r.put("preview",preview);r.put("events",new ArrayList<>(events));return r;}
}
