import com.google.gson.*;
import com.hohoo.ailab.structured.Classification;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
public final class ContractProbe {
    public static void main(String[] args) throws Exception {
        JsonArray fixtures=JsonParser.parseString(new String(Files.readAllBytes(Paths.get(args[0])),StandardCharsets.UTF_8)).getAsJsonArray();
        JsonArray result=new JsonArray();
        for(JsonElement entry:fixtures){
            JsonObject f=entry.getAsJsonObject(), r=new JsonObject();
            r.add("id",f.get("id"));
            try{
                Classification c=Classification.parse(f.get("input").getAsString());
                r.addProperty("accepted",true);
                r.addProperty("category",c.category);
            }catch(Exception e){r.addProperty("accepted",false);}
            result.add(r);
        }
        JsonObject report=new JsonObject(); report.addProperty("javaVersion",System.getProperty("java.version")); report.add("rows",result); System.out.println(new Gson().toJson(report));
    }
}
