package service;

import model.TaskRunRecord;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * The page behind "Open logs": one self-contained HTML file (styles and script inline, no network needed) with the
 * alert, the task's recent log lines, its recent runs and the events around it. Opens in any browser from a file or
 * a share. The script only filters, highlights and copies what is already in the page.
 */
public final class AlertReport {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private AlertReport() { }

    public static String build(TeamsCard.Alert a, List<String> logLines, List<TaskRunRecord> runs, List<TaskRunRecord> events) {
        StringBuilder h = new StringBuilder(32_000);
        h.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
         .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
         .append("<title>").append(esc(a.title())).append("</title><style>").append(CSS).append("</style></head><body>");

        String sev = a.recovery() ? "good" : a.kind() != null && a.kind().defaultSeverity() == model.EventKind.Severity.ERROR ? "bad"
                : a.kind() != null && a.kind().defaultSeverity() == model.EventKind.Severity.WARN ? "warn" : "info";
        h.append("<header class=\"").append(sev).append("\"><div><h1>").append(esc(a.title())).append("</h1>")
         .append("<p>").append(esc(nz(a.message()))).append("</p></div>")
         .append("<div class=\"tools\"><button id=\"theme\" type=\"button\">Dark / light</button>")
         .append("<button id=\"copy\" type=\"button\">Copy summary</button>")
         .append("<button id=\"dl\" type=\"button\">Download</button></div></header>");

        h.append("<main><section class=\"cards\">");
        card(h, "Task", a.taskName());
        card(h, "Type", a.taskType());
        card(h, "Event", a.kind() == null ? "" : a.kind().label());
        card(h, "Result", a.status());
        card(h, "Raised", a.raisedAt() == null ? "" : TS.format(a.raisedAt()));
        card(h, "Run started", a.startedAt() == null ? "" : TS.format(a.startedAt()));
        card(h, "Duration", a.durationMs() > 0 ? TeamsCard.duration(a.durationMs()) : "");
        card(h, "Triggered by", a.trigger());
        card(h, "Server", a.host());
        card(h, "Run id", a.runKey());
        h.append("</section>");

        if (!nz(a.details()).isBlank() && !nz(a.details()).equals(nz(a.message()))) {
            h.append("<section><h2>Details</h2><pre class=\"box\">").append(esc(a.details())).append("</pre></section>");
        }
        if (!nz(a.suggestedAction()).isBlank()) {
            h.append("<section><h2>What to do</h2><p class=\"box\">").append(esc(a.suggestedAction())).append("</p></section>");
        }

        h.append("<nav class=\"tabs\" role=\"tablist\">")
         .append("<button class=\"on\" data-tab=\"log\" type=\"button\">Task log (").append(logLines.size()).append(")</button>")
         .append("<button data-tab=\"runs\" type=\"button\">Recent runs (").append(runs.size()).append(")</button>")
         .append("<button data-tab=\"events\" type=\"button\">Events (").append(events.size()).append(")</button></nav>");

        h.append("<div class=\"bar\"><input id=\"q\" type=\"search\" placeholder=\"Search this tab...\" autocomplete=\"off\">")
         .append("<label><input type=\"checkbox\" id=\"lError\" checked> errors</label>")
         .append("<label><input type=\"checkbox\" id=\"lWarn\" checked> warnings</label>")
         .append("<label><input type=\"checkbox\" id=\"lOther\" checked> other</label>")
         .append("<label><input type=\"checkbox\" id=\"wrap\" checked> wrap lines</label>")
         .append("<span id=\"count\"></span></div>");

        h.append("<div id=\"log\" class=\"pane\">");
        if (logLines.isEmpty()) h.append("<p class=\"empty\">No log lines are available for this task.</p>");
        for (String line : logLines) h.append("<div class=\"row ").append(level(line)).append("\">").append(esc(line)).append("</div>");
        h.append("</div><div id=\"runs\" class=\"pane\" hidden>");
        if (runs.isEmpty()) h.append("<p class=\"empty\">No recent runs.</p>");
        for (TaskRunRecord r : runs) {
            h.append("<div class=\"row ").append(r.getStatus() == TaskRunRecord.Status.FAILED ? "e" : "o").append("\">")
             .append(esc(r.getStartedAt() == null ? "" : TS.format(r.getStartedAt()))).append("  ")
             .append(esc(String.valueOf(r.getStatus()))).append("  ").append(esc(TeamsCard.duration(r.getDurationMs()))).append("  ")
             .append(esc(nz(r.getReason()))).append("</div>");
        }
        h.append("</div><div id=\"events\" class=\"pane\" hidden>");
        if (events.isEmpty()) h.append("<p class=\"empty\">No events recorded around this alert.</p>");
        for (TaskRunRecord r : events) {
            String lv = r.getStatus() == TaskRunRecord.Status.FAILED ? "e" : r.getEventKind() != null && r.getStatus() != TaskRunRecord.Status.SUCCESS ? "w" : "o";
            h.append("<div class=\"row ").append(lv).append("\">")
             .append(esc(r.getStartedAt() == null ? "" : TS.format(r.getStartedAt()))).append("  [")
             .append(esc(nz(r.getEventKind()))).append("]  ").append(esc(nz(r.getReason()))).append("</div>");
        }
        h.append("</div></main><footer>Generated by Ops Transfer Tool on ").append(esc(nz(a.host()))).append(" at ")
         .append(esc(a.raisedAt() == null ? "" : TS.format(a.raisedAt()))).append(". This file works offline.</footer>");

        h.append("<script>").append(JS).append("</script></body></html>");
        return h.toString();
    }

    private static void card(StringBuilder h, String k, String v) {
        if (v == null || v.isBlank()) return;
        h.append("<div class=\"card\"><span>").append(esc(k)).append("</span><b>").append(esc(v)).append("</b></div>");
    }

    /** e = error, w = warning, o = anything else. */
    static String level(String line) {
        String l = line.toLowerCase(java.util.Locale.ROOT);
        if (l.contains("error") || l.contains("fail") || l.contains("exception") || l.contains("denied") || l.contains("refused")) return "e";
        if (l.contains("warn") || l.contains("retry") || l.contains("skipp") || l.contains("timeout") || l.contains("no files")) return "w";
        return "o";
    }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&#39;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static final String CSS =
            ":root{--bg:#f6f7f9;--fg:#1d2330;--card:#fff;--line:#d9dde5;--sub:#5d6678;--bad:#b3261e;--warn:#a85d00;--good:#2e7d32;--info:#1f5fbf}"
            + "body.dark{--bg:#12151c;--fg:#e6e9f0;--card:#1c212b;--line:#2d3442;--sub:#98a1b3}"
            + "*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:14px/1.45 Segoe UI,Roboto,Arial,sans-serif}"
            + "header{display:flex;justify-content:space-between;gap:16px;align-items:flex-start;padding:18px 24px;color:#fff}"
            + "header.bad{background:var(--bad)}header.warn{background:var(--warn)}header.good{background:var(--good)}header.info{background:var(--info)}"
            + "h1{margin:0 0 4px;font-size:20px}header p{margin:0;opacity:.95}.tools button{margin-left:8px}"
            + "button{font:inherit;padding:5px 12px;border:1px solid var(--line);background:var(--card);color:var(--fg);border-radius:6px;cursor:pointer}"
            + "main{max-width:1200px;margin:0 auto;padding:16px 24px}h2{font-size:15px;margin:18px 0 6px}"
            + ".cards{display:grid;grid-template-columns:repeat(auto-fill,minmax(200px,1fr));gap:8px}"
            + ".card{background:var(--card);border:1px solid var(--line);border-radius:8px;padding:8px 12px}"
            + ".card span{display:block;color:var(--sub);font-size:12px}.card b{font-weight:600;word-break:break-word}"
            + ".box{background:var(--card);border:1px solid var(--line);border-radius:8px;padding:10px 12px;white-space:pre-wrap;word-break:break-word;margin:0}"
            + ".tabs{margin-top:18px;border-bottom:1px solid var(--line)}.tabs button{border:0;border-bottom:3px solid transparent;border-radius:0;background:none;padding:8px 14px}"
            + ".tabs button.on{border-bottom-color:var(--info);font-weight:600}"
            + ".bar{display:flex;flex-wrap:wrap;gap:14px;align-items:center;margin:10px 0}.bar input[type=search]{padding:6px 10px;min-width:260px;border:1px solid var(--line);border-radius:6px;background:var(--card);color:var(--fg)}"
            + "#count{color:var(--sub);margin-left:auto}"
            + ".pane{background:var(--card);border:1px solid var(--line);border-radius:8px;max-height:60vh;overflow:auto}"
            + ".row{font:12px/1.5 Consolas,Menlo,monospace;padding:2px 10px;border-bottom:1px solid var(--line);white-space:pre-wrap;word-break:break-word}"
            + ".nowrap .row{white-space:pre;word-break:normal}"
            + ".row.e{color:var(--bad);background:rgba(179,38,30,.07)}.row.w{color:var(--warn);background:rgba(168,93,0,.07)}"
            + "mark{background:#ffe066;color:#000;border-radius:2px}.empty{padding:14px;color:var(--sub)}"
            + "footer{max-width:1200px;margin:0 auto;padding:10px 24px 30px;color:var(--sub);font-size:12px}[hidden]{display:none!important}";

    private static final String JS =
            "(function(){var $=function(i){return document.getElementById(i)};var tab='log';"
            + "var SRC='<!doctype html>\\n'+document.documentElement.outerHTML;"
            + "$('dl').onclick=function(){var n=(document.title||'alert').replace(/[^A-Za-z0-9._-]+/g,'-')+'.html';var a=document.createElement('a');"
            + "a.href=URL.createObjectURL(new Blob([SRC],{type:'text/html'}));a.download=n;document.body.appendChild(a);a.click();document.body.removeChild(a)};"
            + "function rows(){return document.querySelectorAll('#'+tab+' .row')}"
            + "function apply(){var q=$('q').value.toLowerCase(),e=$('lError').checked,w=$('lWarn').checked,o=$('lOther').checked,n=0,t=0;"
            + "rows().forEach(function(r){t++;var c=r.className,ok=(c.indexOf(' e')>-1&&e)||(c.indexOf(' w')>-1&&w)||(c.indexOf(' o')>-1&&o);"
            + "if(!r.dataset.t)r.dataset.t=r.textContent;var txt=r.dataset.t;if(ok&&q&&txt.toLowerCase().indexOf(q)<0)ok=false;"
            + "r.hidden=!ok;if(ok){n++;if(q){var i=txt.toLowerCase().indexOf(q);r.innerHTML=esc(txt.slice(0,i))+'<mark>'+esc(txt.slice(i,i+q.length))+'</mark>'+esc(txt.slice(i+q.length))}else r.textContent=txt}});"
            + "$('count').textContent=n+' of '+t+' lines'}"
            + "function esc(s){return s.replace(/[&<>]/g,function(c){return{'&':'&amp;','<':'&lt;','>':'&gt;'}[c]})}"
            + "document.querySelectorAll('.tabs button').forEach(function(b){b.onclick=function(){document.querySelectorAll('.tabs button').forEach(function(x){x.className=''});"
            + "b.className='on';document.querySelectorAll('.pane').forEach(function(p){p.hidden=true});tab=b.dataset.tab;$(tab).hidden=false;apply()}});"
            + "['q','lError','lWarn','lOther'].forEach(function(i){$(i).oninput=apply;$(i).onchange=apply});"
            + "$('wrap').onchange=function(){document.querySelectorAll('.pane').forEach(function(p){p.classList.toggle('nowrap',!$('wrap').checked)})};"
            + "$('theme').onclick=function(){document.body.classList.toggle('dark')};"
            + "$('copy').onclick=function(){var t=document.querySelector('header h1').textContent+'\\n'+document.querySelector('header p').textContent+'\\n'+"
            + "Array.prototype.map.call(document.querySelectorAll('.card'),function(c){return c.firstChild.textContent+': '+c.lastChild.textContent}).join('\\n');"
            + "if(navigator.clipboard)navigator.clipboard.writeText(t);else{var a=document.createElement('textarea');a.value=t;document.body.appendChild(a);a.select();document.execCommand('copy');a.remove()}};"
            + "if(window.matchMedia&&matchMedia('(prefers-color-scheme: dark)').matches)document.body.classList.add('dark');apply()})();";
}
