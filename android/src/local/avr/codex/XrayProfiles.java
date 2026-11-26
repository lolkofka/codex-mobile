package local.avr.codex.copy;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Subscription data is untrusted. Import proxy outbounds, never routing, listeners or files. */
final class XrayProfiles {
    static IOException invalid(String reason){return new IOException(reason);}
    static String decode(String s)throws Exception{return URLDecoder.decode(s.replace("+","%2B"),"UTF-8");}
    static String b64(String s)throws Exception{return new String(Base64.getDecoder().decode(s.replace('-','+').replace('_','/').replaceAll("\\s", "")),StandardCharsets.UTF_8);}
    static JSONObject obj(Object... fields)throws JSONException{JSONObject o=new JSONObject();for(int i=0;i<fields.length;i+=2)o.put((String)fields[i],fields[i+1]);return o;}
    static String identity(JSONObject outbound)throws Exception{
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(outbound.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder s=new StringBuilder();for(int i=0;i<12;i++)s.append(String.format(Locale.ROOT,"%02x",digest[i]));return s.toString();
    }
    static final class ImportResult {
        final JSONArray profiles;final String warning;
        ImportResult(JSONArray profiles,Map<String,Integer> skipped){
            this.profiles=profiles;
            ArrayList<String> protocols=new ArrayList<>();int count=0;
            for(Map.Entry<String,Integer> item:skipped.entrySet()){count+=item.getValue();protocols.add(item.getKey()+" ("+item.getValue()+")");}
            warning=count==0?"":"Пропущено серверов: "+count+". Не поддерживаются: "+String.join(", ",protocols)+".";
        }
    }
    static String unsupported(String protocol){
        switch(protocol.toLowerCase(Locale.ROOT)){
            case "hy2":case "hysteria2":return "Hysteria2";
            case "hysteria":return "Hysteria";
            case "tuic":return "TUIC";
            case "ssr":return "ShadowsocksR";
            case "wireguard":return "WireGuard";
            default:return "";
        }
    }
    static void skip(Map<String,Integer> skipped,String protocol){skipped.put(protocol,skipped.getOrDefault(protocol,0)+1);}
    static JSONArray parse(String body)throws Exception{return parseSubscription(body).profiles;}
    static ImportResult parseSubscription(String body)throws Exception{
        if(body.length()>2*1024*1024)throw invalid("Подписка больше 2 МБ");
        body=body.trim();if(body.isEmpty())throw invalid("Подписка пуста");
        if(!body.startsWith("[")&&!body.startsWith("{")&&!body.contains("://"))try{body=b64(body).trim();}catch(Exception e){throw invalid("Нужны ссылки VLESS, VMess, Trojan, Shadowsocks или Xray JSON");}
        JSONArray found=new JSONArray();Set<String> seen=new HashSet<>();Map<String,Integer> skipped=new LinkedHashMap<>();
        if(body.startsWith("{")||body.startsWith("[")){
            Object json=new JSONTokener(body).nextValue();JSONArray configs=json instanceof JSONArray?(JSONArray)json:new JSONArray().put(json);
            for(int i=0;i<configs.length();i++){
                JSONObject config=configs.getJSONObject(i);JSONObject outbound=config;
                if(config.has("outbounds")){JSONArray all=config.getJSONArray("outbounds");outbound=null;for(int j=0;j<all.length();j++){JSONObject o=all.getJSONObject(j);if(supported(o.optString("protocol"))){outbound=o;break;}}if(outbound==null)throw invalid("В JSON нет поддерживаемого прокси");}
                String ignored=unsupported(outbound.optString("protocol"));if(!ignored.isEmpty()){skip(skipped,ignored);continue;}
                add(found,seen,config.optString("remarks",config.optString("name","Сервер "+(i+1))),sanitize(outbound));
            }
        }else{
            int lineNumber=0;for(String line:body.split("\\r?\\n")){
                line=line.trim();lineNumber++;if(line.isEmpty()||line.startsWith("#"))continue;
                int separator=line.indexOf("://");String ignored=separator<0?"":unsupported(line.substring(0,separator));
                if(!ignored.isEmpty()){skip(skipped,ignored);continue;}
                try{JSONObject profile=link(line);add(found,seen,profile.getString("name"),profile.getJSONObject("outbound"));}
                catch(Exception e){throw invalid("Строка "+lineNumber+": "+(e instanceof IOException?e.getMessage():"некорректная ссылка сервера"));}
            }
        }
        ImportResult result=new ImportResult(found,skipped);
        if(found.length()==0)throw invalid(skipped.isEmpty()?"В подписке нет серверов":"В подписке нет поддерживаемых серверов. "+result.warning);
        if(found.length()>500)throw invalid("В подписке больше 500 серверов");return result;
    }
    static boolean supported(String protocol){return Arrays.asList("vless","vmess","trojan","shadowsocks").contains(protocol);}
    static void add(JSONArray array,Set<String> seen,String name,JSONObject outbound)throws Exception{
        String id=identity(outbound);if(!seen.add(id))return;
        name=name.replaceAll("[\\p{Cntrl}]", " ").trim();if(name.isEmpty())name="Сервер";if(name.length()>120)name=name.substring(0,120);
        array.put(obj("id",id,"name",name,"outbound",outbound));
    }
    static JSONObject sanitize(JSONObject original)throws Exception{
        String protocol=original.optString("protocol");if(!supported(protocol))throw invalid("Неподдерживаемый протокол");
        JSONObject o=obj("tag","selected","protocol",protocol,"settings",new JSONObject(original.getJSONObject("settings").toString()));
        if(original.has("streamSettings"))o.put("streamSettings",sanitizeStream(original.getJSONObject("streamSettings"),0));
        JSONObject endpoint=endpoint(o);String host=endpoint.optString("address");if(host.startsWith("[")&&host.endsWith("]")){host=host.substring(1,host.length()-1);endpoint.put("address",host);}int port=endpoint.optInt("port");
        if(host.isEmpty()||host.length()>253||host.matches(".*[\\s/\\\\].*")||port<1||port>65535)throw invalid("Проверь адрес и порт сервера");
        // A chosen host is exactly one endpoint. No balancing or alternate outbound.
        return o;
    }
    static JSONObject extra(String text)throws Exception{
        if(text.length()>262144)throw invalid("XHTTP extra больше 256 КБ");
        boolean quoted=false,escaped=false;int depth=0;
        for(int i=0;i<text.length();i++){
            char c=text.charAt(i);if(quoted){if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c=='"')quoted=false;}
            else if(c=='"')quoted=true;else if(c=='{'||c=='['){if(++depth>32)throw invalid("XHTTP extra: слишком большая вложенность JSON");}else if(c=='}'||c==']')depth--;
        }
        try{JSONTokener parser=new JSONTokener(text);Object parsed=parser.nextValue();if(!(parsed instanceof JSONObject)||parser.nextClean()!=0)throw invalid("XHTTP extra должен быть объектом JSON");return (JSONObject)parsed;}
        catch(JSONException e){throw invalid("XHTTP extra: некорректный JSON");}
    }
    static JSONObject sanitizeXhttp(JSONObject input,int depth)throws Exception{
        JSONObject result=new JSONObject(input.toString());
        if(result.has("extra")&&!result.isNull("extra")){
            Object value=result.get("extra");JSONObject extra=value instanceof JSONObject?(JSONObject)value:value instanceof String?extra((String)value):null;
            if(extra==null)throw invalid("XHTTP extra должен быть объектом JSON");
            extra=new JSONObject(extra.toString());sanitizeDownload(extra,depth);result.put("extra",extra);
        }else result.remove("extra");
        sanitizeDownload(result,depth);return result;
    }
    static void sanitizeDownload(JSONObject transport,int depth)throws Exception{
        if(!transport.has("downloadSettings")||transport.isNull("downloadSettings"))return;
        if(!(transport.get("downloadSettings") instanceof JSONObject))throw invalid("XHTTP downloadSettings должен быть объектом JSON");
        JSONObject stream=sanitizeStream(transport.getJSONObject("downloadSettings"),depth+1);
        if(!stream.optString("network").equals("xhttp"))throw invalid("XHTTP downloadSettings требует network=xhttp");
        transport.put("downloadSettings",stream);
    }
    static JSONObject sanitizeStream(JSONObject raw,int depth)throws Exception{
        if(depth>4)throw invalid("Слишком много вложенных XHTTP downloadSettings");
        String network=raw.optString("network",raw.optString("method","raw"));if(network.equals("tcp"))network="raw";if(network.equals("splithttp"))network="xhttp";
        if(!Arrays.asList("raw","ws","grpc","httpupgrade","xhttp").contains(network))throw invalid("Транспорт JSON пока не поддерживается");
        String security=raw.optString("security","none");if(!Arrays.asList("none","tls","reality").contains(security))throw invalid("Неподдерживаемая защита транспорта");
        JSONObject stream=obj("network",network,"security",security);
        if(raw.has("address")){String address=raw.getString("address");if(address.length()>253||address.matches(".*[\\s/\\\\].*"))throw invalid("Проверь адрес XHTTP downloadSettings");stream.put("address",address);}
        if(raw.has("port")){int port=raw.getInt("port");if(port<0||port>65535)throw invalid("Проверь порт XHTTP downloadSettings");stream.put("port",port);}
        String settings=network+"Settings",source=settings;
        if(!raw.has(source)&&network.equals("xhttp"))source="splithttpSettings";
        if(!raw.has(source)&&network.equals("raw"))source="tcpSettings";
        if(raw.has(source)){
            JSONObject input=raw.getJSONObject(source),transport;
            if(network.equals("xhttp"))transport=sanitizeXhttp(input,depth);
            else{transport=new JSONObject();for(String k:new String[]{"host","path","mode","headers","serviceName","multiMode","authority","maxEarlyData","earlyDataHeaderName","header"})if(input.has(k))transport.put(k,input.get(k));}
            stream.put(settings,transport);
        }
        if(raw.has("tlsSettings")){
            JSONObject input=raw.getJSONObject("tlsSettings");if(input.optBoolean("allowInsecure"))throw invalid("Сервер отключает проверку TLS-сертификата");
            JSONObject tls=new JSONObject();for(String k:new String[]{"serverName","fingerprint","alpn","minVersion","maxVersion","pinnedPeerCertificateChainSha256","verifyPeerCertInNames"})if(input.has(k))tls.put(k,input.get(k));stream.put("tlsSettings",tls);
        }
        if(raw.has("realitySettings")){
            JSONObject input=raw.getJSONObject("realitySettings"),tls=new JSONObject();for(String k:new String[]{"serverName","fingerprint","password","publicKey","shortId","spiderX","mldsa65Verify"})if(input.has(k))tls.put(k,input.get(k));stream.put("realitySettings",tls);
        }
        return stream;
    }
    static JSONObject endpoint(JSONObject o)throws Exception{
        JSONObject settings=o.getJSONObject("settings");JSONArray servers=settings.getJSONArray(o.optString("protocol").matches("vless|vmess")?"vnext":"servers");
        if(servers.length()!=1)throw invalid("У одного профиля должен быть один сервер");return servers.getJSONObject(0);
    }
    static JSONObject link(String link)throws Exception{
        if(link.startsWith("vmess://")){
            JSONObject v=new JSONObject(b64(link.substring(8).split("#",2)[0]));
            JSONObject user=obj("id",v.getString("id"),"security",v.optString("scy","auto"));
            if(v.optInt("aid")!=0)throw invalid("Устаревший VMess alterId не поддерживается");
            Map<String,String> q=new HashMap<>();q.put("type",v.optString("net","tcp"));q.put("security",v.optString("tls").isEmpty()?"none":v.optString("tls"));for(String k:new String[]{"host","path","sni","alpn","fp"})q.put(k,v.optString(k));q.put("serviceName",v.optString("path"));
            JSONObject o=obj("protocol","vmess","settings",obj("vnext",new JSONArray().put(obj("address",v.getString("add"),"port",Integer.parseInt(v.getString("port")),"users",new JSONArray().put(user)))),"streamSettings",stream(q));
            return obj("name",v.optString("ps","VMess"),"outbound",sanitize(o));
        }
        // SIP002 also permits the entire Shadowsocks authority in base64.
        if(link.startsWith("ss://")){String rest=link.substring(5),fragment="";int hash=rest.indexOf('#');if(hash>=0){fragment=rest.substring(hash);rest=rest.substring(0,hash);}if(!rest.contains("@"))rest=b64(rest);link="ss://"+rest+fragment;}
        URI uri=new URI(link);String scheme=uri.getScheme();if(!Arrays.asList("vless","trojan","ss").contains(scheme))throw invalid("Протокол этой ссылки не поддерживается");
        if(uri.getHost()==null||uri.getRawUserInfo()==null)throw invalid("Не указан сервер или ключ");
        Map<String,String> q=new HashMap<>();if(uri.getRawQuery()!=null)for(String part:uri.getRawQuery().split("&")){String[] kv=part.split("=",2);q.put(decode(kv[0]),kv.length==2?decode(kv[1]):"");}
        if(q.containsKey("plugin")&&!q.get("plugin").isEmpty())throw invalid("Shadowsocks-плагин не поддерживается");
        if("1".equals(q.get("allowInsecure"))||"true".equals(q.get("allowInsecure")))throw invalid("Отключение проверки TLS не поддерживается");
        String secret=decode(uri.getRawUserInfo());int port=uri.getPort()<0?443:uri.getPort();JSONObject settings;String protocol=scheme;
        if(scheme.equals("vless")){JSONObject user=obj("id",secret,"encryption",q.getOrDefault("encryption","none"));if(q.containsKey("flow"))user.put("flow",q.get("flow"));settings=obj("vnext",new JSONArray().put(obj("address",uri.getHost(),"port",port,"users",new JSONArray().put(user))));}
        else if(scheme.equals("trojan")){settings=obj("servers",new JSONArray().put(obj("address",uri.getHost(),"port",port,"password",secret)));q.putIfAbsent("security","tls");}
        else{protocol="shadowsocks";if(!secret.contains(":"))secret=b64(secret);String[] pair=secret.split(":",2);if(pair.length!=2)throw invalid("Некорректный ключ Shadowsocks");settings=obj("servers",new JSONArray().put(obj("address",uri.getHost(),"port",port,"method",pair[0],"password",pair[1])));}
        JSONObject o=obj("protocol",protocol,"settings",settings,"streamSettings",stream(q));
        return obj("name",uri.getRawFragment()==null?uri.getHost():decode(uri.getRawFragment()),"outbound",sanitize(o));
    }
    static JSONObject stream(Map<String,String> q)throws Exception{
        String network=q.getOrDefault("type","tcp"),security=q.getOrDefault("security","none");if(network.equals("tcp"))network="raw";if(network.equals("splithttp"))network="xhttp";
        if(!Arrays.asList("raw","ws","grpc","httpupgrade","xhttp").contains(network))throw invalid("Транспорт "+network+" пока не поддерживается");
        JSONObject s=obj("network",network,"security",security);String host=q.getOrDefault("host",""),path=q.getOrDefault("path","/");
        if(network.equals("ws"))s.put("wsSettings",obj("path",path,"headers",host.isEmpty()?new JSONObject():obj("Host",host)));
        if(network.equals("grpc"))s.put("grpcSettings",obj("serviceName",q.getOrDefault("serviceName",""),"multiMode","multi".equals(q.get("mode")),"authority",q.getOrDefault("authority",host)));
        if(network.equals("httpupgrade"))s.put("httpupgradeSettings",obj("path",path,"host",host));
        if(network.equals("xhttp")){
            JSONObject xhttp=obj("path",path,"host",host,"mode",q.getOrDefault("mode","auto"));
            if(q.containsKey("extra")&&!q.get("extra").isEmpty())xhttp.put("extra",extra(q.get("extra")));
            s.put("xhttpSettings",xhttp);
        }
        if(network.equals("raw")&&q.containsKey("headerType")&&!q.get("headerType").equals("none"))throw invalid("Маскировка TCP headerType не поддерживается");
        if(!security.equals("none")){
            JSONObject tls=obj("serverName",q.getOrDefault("sni",q.getOrDefault("serverName",host)),"fingerprint",q.getOrDefault("fp","chrome"));
            if(security.equals("tls")){String alpn=q.getOrDefault("alpn","");if(!alpn.isEmpty())tls.put("alpn",new JSONArray(Arrays.asList(alpn.split(","))));s.put("tlsSettings",tls);}
            else if(security.equals("reality")){tls.put("password",q.getOrDefault("pbk","")).put("shortId",q.getOrDefault("sid","")).put("spiderX",q.getOrDefault("spx","/"));if(q.containsKey("pqv"))tls.put("mldsa65Verify",q.get("pqv"));s.put("realitySettings",tls);}
            else throw invalid("Неподдерживаемая защита транспорта");
        }
        return s;
    }
    static JSONObject config(JSONObject selected,int port,String password)throws Exception{
        return obj("log",obj("loglevel","none"),"inbounds",new JSONArray().put(obj("tag","engine","listen","127.0.0.1","port",port,"protocol","http","settings",obj("accounts",new JSONArray().put(obj("user","codex","pass",password))))),"outbounds",new JSONArray().put(sanitize(selected)),"routing",obj("domainStrategy","AsIs"));
    }
}
