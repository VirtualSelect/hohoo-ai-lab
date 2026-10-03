package com.hohoo.ailab.reliable;
import java.util.*;
/** Tiny BM25 index over owned English fixtures. Not an embedding service or Chinese segmenter. */
public final class Retrieval {
    public static final class Doc {
        public final String id,text;
        public Doc(String id,String text){this.id=id;this.text=text;}
    }
    public static final class Hit {
        public final Doc doc;public final double score;
        Hit(Doc doc,double score){this.doc=doc;this.score=score;}
    }
    private final List<Doc> docs;
    private final List<List<String>> words=new ArrayList<List<String>>();
    private final Map<String,Integer> df=new HashMap<String,Integer>();
    private final double avg;
    static List<String> tokens(String s){
        List<String> result=new ArrayList<String>();
        for(String x:s.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))if(!x.isEmpty())result.add(x);
        return result;
    }
    public Retrieval(List<Doc> corpus){
        if(corpus.isEmpty())throw new IllegalArgumentException("empty corpus");
        Set<String> ids=new HashSet<String>();double size=0;docs=new ArrayList<Doc>(corpus);
        for(Doc doc:docs){
            if(doc.id==null||!ids.add(doc.id)||doc.text==null)throw new IllegalArgumentException("document");
            List<String> terms=tokens(doc.text);if(terms.isEmpty())throw new IllegalArgumentException("empty text");
            words.add(terms);size+=terms.size();
            for(String t:new HashSet<String>(terms))df.put(t,df.getOrDefault(t,0)+1);
        }
        avg=size/docs.size();
    }
    public List<Hit> search(String query,int k){
        if(k<1||k>10)throw new IllegalArgumentException("k");
        List<Hit> hits=new ArrayList<Hit>();Set<String> q=new HashSet<String>(tokens(query));
        for(int i=0;i<docs.size();i++){
            List<String> ts=words.get(i);double score=0;
            for(String term:q){
                int f=Collections.frequency(ts,term);if(f==0)continue;
                double idf=Math.log(1+(docs.size()-df.get(term)+.5)/(df.get(term)+.5));
                score+=idf*(f*2.2)/(f+1.2*(.25+.75*ts.size()/avg));
            }
            if(score>0)hits.add(new Hit(docs.get(i),score));
        }
        hits.sort(Comparator.<Hit>comparingDouble(h->-h.score).thenComparing(h->h.doc.id));
        return new ArrayList<Hit>(hits.subList(0,Math.min(k,hits.size())));
    }
    public static final class Evidence {
        public final String text;
        private final List<Hit> included;
        Evidence(String text,List<Hit> included){this.text=text;this.included=included;}
        public boolean cites(String id,String quote){return citation(included,id,quote);}
    }
    public static Evidence pack(List<Hit> hits,int maxChars){
        if(maxChars<0)throw new IllegalArgumentException("budget");StringBuilder b=new StringBuilder();
        List<Hit> included=new ArrayList<Hit>();
        for(Hit h:hits){String block="["+h.doc.id+"] "+h.doc.text+"\n";if(b.length()+block.length()<=maxChars){b.append(block);included.add(h);}}
        return new Evidence(b.toString(),included);
    }
    public static boolean citation(List<Hit> supplied,String id,String exactQuote){
        if(exactQuote==null||exactQuote.trim().isEmpty())return false;
        for(Hit h:supplied)if(h.doc.id.equals(id)&&h.doc.text.contains(exactQuote))return true;
        return false;
    }
}
