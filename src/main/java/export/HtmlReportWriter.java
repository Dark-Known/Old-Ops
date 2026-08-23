package export;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Writes a single, fully self-contained .html report of task run logs —
 * every style and script is inlined into the file itself, so it opens and
 * works (tree expand/collapse, search, donut chart, animations) completely
 * offline with no internet connection required, in any modern browser.
 *
 * <p>Rows are grouped client-side into a tree: Task name → individual runs
 * for that task, each expandable to show its full captured log. Not a
 * general-purpose HTML templating library — one report shape, tailored to
 * the Logs tab's export button.
 */
public final class HtmlReportWriter {

    private HtmlReportWriter() {}

    /**
     * @param outFile destination .html path
     * @param title   report heading (e.g. "Task Run Logs — exported 2026-08-22 10:00")
     * @param headers column headers — expected order: Task, Type, Status, Started, Ended, Duration, Reason, Details
     * @param rows    each element is one run's cell values, same column order as headers
     */
    public static void write(File outFile, String title, String[] headers, List<String[]> rows) throws Exception {
        int total = rows.size();
        int success = 0, failed = 0, skipped = 0;
        for (String[] r : rows) {
            String status = r.length > 2 ? r[2] : "";
            if ("SUCCESS".equalsIgnoreCase(status)) success++;
            else if ("FAILED".equalsIgnoreCase(status)) failed++;
            else if ("SKIPPED".equalsIgnoreCase(status)) skipped++;
        }

        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            String[] r = rows.get(i);
            if (i > 0) json.append(',');
            json.append("{")
                .append("\"task\":").append(jsonStr(get(r, 0))).append(',')
                .append("\"type\":").append(jsonStr(get(r, 1))).append(',')
                .append("\"status\":").append(jsonStr(get(r, 2))).append(',')
                .append("\"started\":").append(jsonStr(get(r, 3))).append(',')
                .append("\"ended\":").append(jsonStr(get(r, 4))).append(',')
                .append("\"duration\":").append(jsonStr(get(r, 5))).append(',')
                .append("\"reason\":").append(jsonStr(get(r, 6))).append(',')
                .append("\"details\":").append(jsonStr(get(r, 7)))
                .append("}");
        }
        json.append("]");

        String html = TEMPLATE
                .replace("__TITLE__", escapeHtml(title))
                .replace("__GENERATED__", escapeHtml(java.time.LocalDateTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))))
                .replace("__TOTAL__", String.valueOf(total))
                .replace("__SUCCESS__", String.valueOf(success))
                .replace("__FAILED__", String.valueOf(failed))
                .replace("__SKIPPED__", String.valueOf(skipped))
                .replace("__SUCCESS_PCT__", pct(success, total))
                .replace("__FAILED_PCT__", pct(failed, total))
                .replace("__SKIPPED_PCT__", pct(skipped, total))
                .replace("__DATA_JSON__", json.toString());

        outFile.getParentFile().mkdirs();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(outFile), StandardCharsets.UTF_8)) {
            w.write(html);
        }
    }

    private static String get(String[] r, int i) {
        return r != null && i < r.length && r[i] != null ? r[i] : "";
    }

    private static String pct(int part, int total) {
        if (total <= 0) return "0";
        return String.valueOf(Math.round(part * 100.0 / total));
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** Minimal, correct JSON string literal — escapes quotes, backslashes, control chars. */
    private static String jsonStr(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                case '<':  sb.append("\\u003C"); break; // avoid premature </script> termination
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append("\"");
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Everything below is inlined into the output file — no external CSS,
    // JS, fonts, or images. Opens and fully works with the browser offline.
    // ─────────────────────────────────────────────────────────────────────
    private static final String TEMPLATE = "<!DOCTYPE html>\n"
        + "<html lang=\"en\">\n"
        + "<head>\n"
        + "<meta charset=\"UTF-8\">\n"
        + "<title>__TITLE__</title>\n"
        + "<style>\n"
        + "  :root {\n"
        + "    --bg: #0f1115; --panel: #161923; --panel-2: #1c2030; --border: #2a2f42;\n"
        + "    --text: #e6e8ef; --muted: #8b90a6; --accent: #5b8cff;\n"
        + "    --green: #2ecc71; --red: #ff5c5c; --amber: #ffb347;\n"
        + "  }\n"
        + "  * { box-sizing: border-box; }\n"
        + "  body {\n"
        + "    margin: 0; padding: 32px; background: radial-gradient(1200px 600px at 10% -10%, #1a2040 0%, var(--bg) 60%);\n"
        + "    color: var(--text); font-family: -apple-system, Segoe UI, Roboto, Helvetica, Arial, sans-serif;\n"
        + "    min-height: 100vh;\n"
        + "  }\n"
        + "  header { margin-bottom: 22px; opacity: 0; animation: fadeIn .5s ease forwards; }\n"
        + "  h1 { margin: 0 0 4px; font-size: 22px; letter-spacing: .2px; }\n"
        + "  .sub { color: var(--muted); font-size: 12.5px; }\n"
        + "  .cards {\n"
        + "    display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 12px; margin: 20px 0 22px;\n"
        + "  }\n"
        + "  .card {\n"
        + "    background: var(--panel); border: 1px solid var(--border); border-radius: 12px; padding: 14px 16px;\n"
        + "    opacity: 0; transform: translateY(8px); animation: fadeUp .45s ease forwards;\n"
        + "  }\n"
        + "  .card .n { font-size: 26px; font-weight: 700; }\n"
        + "  .card .l { color: var(--muted); font-size: 11.5px; text-transform: uppercase; letter-spacing: .6px; }\n"
        + "  .card.success .n { color: var(--green); } .card.failed .n { color: var(--red); } .card.skipped .n { color: var(--amber); }\n"
        + "  .layout { display: grid; grid-template-columns: 1fr 220px; gap: 18px; align-items: start; }\n"
        + "  .panel {\n"
        + "    background: var(--panel); border: 1px solid var(--border); border-radius: 14px; padding: 16px;\n"
        + "    opacity: 0; animation: fadeUp .5s ease forwards; animation-delay: .1s;\n"
        + "  }\n"
        + "  .toolbar { display: flex; gap: 8px; margin-bottom: 12px; flex-wrap: wrap; align-items: center; }\n"
        + "  input[type=text] {\n"
        + "    flex: 1; min-width: 160px; background: var(--panel-2); border: 1px solid var(--border); color: var(--text);\n"
        + "    padding: 8px 12px; border-radius: 8px; font-size: 13px; outline: none;\n"
        + "  }\n"
        + "  input[type=text]:focus { border-color: var(--accent); }\n"
        + "  button {\n"
        + "    background: var(--panel-2); color: var(--text); border: 1px solid var(--border); padding: 8px 12px;\n"
        + "    border-radius: 8px; font-size: 12.5px; cursor: pointer; transition: all .15s ease;\n"
        + "  }\n"
        + "  button:hover { border-color: var(--accent); color: #fff; transform: translateY(-1px); }\n"
        + "  .chip {\n"
        + "    padding: 5px 10px; border-radius: 999px; font-size: 11.5px; border: 1px solid var(--border); cursor: pointer; color: var(--muted);\n"
        + "  }\n"
        + "  .chip.active { color: #fff; border-color: var(--accent); background: rgba(91,140,255,.15); }\n"
        + "  .tree-task {\n"
        + "    border: 1px solid var(--border); border-radius: 10px; margin-bottom: 10px; overflow: hidden;\n"
        + "    background: var(--panel-2); opacity: 0; transform: translateY(6px); animation: fadeUp .35s ease forwards;\n"
        + "  }\n"
        + "  .task-head {\n"
        + "    display: flex; align-items: center; gap: 10px; padding: 11px 14px; cursor: pointer; user-select: none;\n"
        + "  }\n"
        + "  .task-head:hover { background: rgba(255,255,255,.03); }\n"
        + "  .chevron { display: inline-block; transition: transform .2s ease; color: var(--muted); width: 10px; }\n"
        + "  .task-head.open .chevron { transform: rotate(90deg); }\n"
        + "  .task-name { font-weight: 600; font-size: 13.5px; flex: 1; }\n"
        + "  .badge { font-size: 10.5px; padding: 2px 8px; border-radius: 999px; font-weight: 600; letter-spacing: .3px; }\n"
        + "  .badge.SUCCESS { background: rgba(46,204,113,.15); color: var(--green); }\n"
        + "  .badge.FAILED  { background: rgba(255,92,92,.15);  color: var(--red); }\n"
        + "  .badge.SKIPPED { background: rgba(255,179,71,.15); color: var(--amber); }\n"
        + "  .count-pill { font-size: 11px; color: var(--muted); }\n"
        + "  .runs { max-height: 0; overflow: hidden; transition: max-height .3s ease; }\n"
        + "  .runs.open { max-height: 4000px; }\n"
        + "  .run {\n"
        + "    padding: 9px 14px 9px 34px; border-top: 1px solid var(--border); cursor: pointer; font-size: 12.5px;\n"
        + "  }\n"
        + "  .run:hover { background: rgba(255,255,255,.025); }\n"
        + "  .run-row { display: flex; align-items: center; gap: 10px; }\n"
        + "  .run-row .dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }\n"
        + "  .dot.SUCCESS { background: var(--green); box-shadow: 0 0 8px var(--green); }\n"
        + "  .dot.FAILED  { background: var(--red);   box-shadow: 0 0 8px var(--red); }\n"
        + "  .dot.SKIPPED { background: var(--amber);  box-shadow: 0 0 8px var(--amber); }\n"
        + "  .run-row .started { color: var(--muted); min-width: 150px; }\n"
        + "  .run-row .dur { color: var(--muted); min-width: 60px; text-align: right; }\n"
        + "  .run-row .reason { flex: 1; color: #c7cae0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }\n"
        + "  .detail {\n"
        + "    display: none; margin: 8px 0 4px 18px; padding: 12px; background: #0c0e14; border: 1px solid var(--border);\n"
        + "    border-radius: 8px; white-space: pre-wrap; font-family: 'SFMono-Regular', Consolas, Menlo, monospace;\n"
        + "    font-size: 11.5px; color: #b9c0d6; line-height: 1.5; max-height: 340px; overflow: auto;\n"
        + "  }\n"
        + "  .detail.open { display: block; animation: fadeIn .2s ease; }\n"
        + "  .side { position: sticky; top: 16px; }\n"
        + "  .donut-wrap { display: flex; flex-direction: column; align-items: center; gap: 10px; }\n"
        + "  .donut {\n"
        + "    width: 150px; height: 150px; border-radius: 50%;\n"
        + "    background: conic-gradient(var(--green) 0 __SUCCESS_PCT__%, var(--red) __SUCCESS_PCT__% calc(__SUCCESS_PCT__% + __FAILED_PCT__%), var(--amber) calc(__SUCCESS_PCT__% + __FAILED_PCT__%) 100%);\n"
        + "    position: relative; transform: scale(.4); opacity: 0; animation: popIn .5s ease .2s forwards;\n"
        + "  }\n"
        + "  .donut::after {\n"
        + "    content: ''; position: absolute; inset: 22px; background: var(--panel); border-radius: 50%;\n"
        + "  }\n"
        + "  .legend { font-size: 11.5px; color: var(--muted); display: flex; flex-direction: column; gap: 6px; width: 100%; }\n"
        + "  .legend div { display: flex; align-items: center; gap: 6px; }\n"
        + "  .legend .sw { width: 9px; height: 9px; border-radius: 3px; }\n"
        + "  footer { margin-top: 22px; color: var(--muted); font-size: 11px; text-align: center; opacity: 0; animation: fadeIn .5s ease .3s forwards; }\n"
        + "  @keyframes fadeIn { from { opacity: 0; } to { opacity: 1; } }\n"
        + "  @keyframes fadeUp { from { opacity: 0; transform: translateY(8px); } to { opacity: 1; transform: translateY(0); } }\n"
        + "  @keyframes popIn { from { opacity: 0; transform: scale(.4); } to { opacity: 1; transform: scale(1); } }\n"
        + "  ::-webkit-scrollbar { width: 8px; height: 8px; }\n"
        + "  ::-webkit-scrollbar-thumb { background: var(--border); border-radius: 4px; }\n"
        + "  .empty { color: var(--muted); font-size: 12.5px; padding: 20px; text-align: center; }\n"
        + "</style>\n"
        + "</head>\n"
        + "<body>\n"
        + "<header>\n"
        + "  <h1>__TITLE__</h1>\n"
        + "  <div class=\"sub\">Generated __GENERATED__ &middot; __TOTAL__ run(s) &middot; self-contained report, no internet connection needed</div>\n"
        + "</header>\n"
        + "\n"
        + "<div class=\"cards\">\n"
        + "  <div class=\"card\" style=\"animation-delay:.05s\"><div class=\"n\">__TOTAL__</div><div class=\"l\">Total Runs</div></div>\n"
        + "  <div class=\"card success\" style=\"animation-delay:.1s\"><div class=\"n\">__SUCCESS__</div><div class=\"l\">Success</div></div>\n"
        + "  <div class=\"card failed\" style=\"animation-delay:.15s\"><div class=\"n\">__FAILED__</div><div class=\"l\">Failed</div></div>\n"
        + "  <div class=\"card skipped\" style=\"animation-delay:.2s\"><div class=\"n\">__SKIPPED__</div><div class=\"l\">Skipped</div></div>\n"
        + "</div>\n"
        + "\n"
        + "<div class=\"layout\">\n"
        + "  <div class=\"panel\">\n"
        + "    <div class=\"toolbar\">\n"
        + "      <input type=\"text\" id=\"search\" placeholder=\"Search task, reason, or log text\u2026\">\n"
        + "      <span class=\"chip active\" data-status=\"ALL\">All</span>\n"
        + "      <span class=\"chip\" data-status=\"SUCCESS\">Success</span>\n"
        + "      <span class=\"chip\" data-status=\"FAILED\">Failed</span>\n"
        + "      <span class=\"chip\" data-status=\"SKIPPED\">Skipped</span>\n"
        + "      <button id=\"expandAll\">Expand all</button>\n"
        + "      <button id=\"collapseAll\">Collapse all</button>\n"
        + "    </div>\n"
        + "    <div id=\"tree\"></div>\n"
        + "  </div>\n"
        + "  <div class=\"panel side\">\n"
        + "    <div class=\"donut-wrap\">\n"
        + "      <div class=\"donut\"></div>\n"
        + "      <div class=\"legend\">\n"
        + "        <div><span class=\"sw\" style=\"background:var(--green)\"></span> Success &mdash; __SUCCESS_PCT__%</div>\n"
        + "        <div><span class=\"sw\" style=\"background:var(--red)\"></span> Failed &mdash; __FAILED_PCT__%</div>\n"
        + "        <div><span class=\"sw\" style=\"background:var(--amber)\"></span> Skipped &mdash; __SKIPPED_PCT__%</div>\n"
        + "      </div>\n"
        + "    </div>\n"
        + "  </div>\n"
        + "</div>\n"
        + "\n"
        + "<footer>Exported from OpsTransferTool &mdash; this file has no external dependencies and can be shared or archived offline.</footer>\n"
        + "\n"
        + "<script>\n"
        + "(function () {\n"
        + "  var DATA = __DATA_JSON__;\n"
        + "  var statusFilter = 'ALL';\n"
        + "\n"
        + "  function esc(s) {\n"
        + "    return String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');\n"
        + "  }\n"
        + "\n"
        + "  function groupByTask(rows) {\n"
        + "    var map = {}, order = [];\n"
        + "    rows.forEach(function (r) {\n"
        + "      if (!map[r.task]) { map[r.task] = []; order.push(r.task); }\n"
        + "      map[r.task].push(r);\n"
        + "    });\n"
        + "    return order.map(function (name) { return { task: name, runs: map[name] }; });\n"
        + "  }\n"
        + "\n"
        + "  function matches(r, q) {\n"
        + "    if (statusFilter !== 'ALL' && r.status !== statusFilter) return false;\n"
        + "    if (!q) return true;\n"
        + "    q = q.toLowerCase();\n"
        + "    return (r.task + ' ' + r.reason + ' ' + r.details).toLowerCase().indexOf(q) !== -1;\n"
        + "  }\n"
        + "\n"
        + "  function render() {\n"
        + "    var q = document.getElementById('search').value.trim();\n"
        + "    var filtered = DATA.filter(function (r) { return matches(r, q); });\n"
        + "    var groups = groupByTask(filtered);\n"
        + "    var tree = document.getElementById('tree');\n"
        + "    tree.innerHTML = '';\n"
        + "\n"
        + "    if (!groups.length) {\n"
        + "      var empty = document.createElement('div');\n"
        + "      empty.className = 'empty';\n"
        + "      empty.textContent = 'No runs match the current filters.';\n"
        + "      tree.appendChild(empty);\n"
        + "      return;\n"
        + "    }\n"
        + "\n"
        + "    groups.forEach(function (g, gi) {\n"
        + "      var successCount = g.runs.filter(function (r) { return r.status === 'SUCCESS'; }).length;\n"
        + "      var failedCount  = g.runs.filter(function (r) { return r.status === 'FAILED';  }).length;\n"
        + "\n"
        + "      var taskEl = document.createElement('div');\n"
        + "      taskEl.className = 'tree-task';\n"
        + "      taskEl.style.animationDelay = (gi * 0.04) + 's';\n"
        + "\n"
        + "      var head = document.createElement('div');\n"
        + "      head.className = 'task-head open';\n"
        + "      head.innerHTML = '<span class=\"chevron\">&#9656;</span>'\n"
        + "        + '<span class=\"task-name\">' + esc(g.task) + '</span>'\n"
        + "        + '<span class=\"count-pill\">' + g.runs.length + ' run(s) &middot; ' + successCount + ' ok &middot; ' + failedCount + ' failed</span>';\n"
        + "\n"
        + "      var runsWrap = document.createElement('div');\n"
        + "      runsWrap.className = 'runs open';\n"
        + "\n"
        + "      g.runs.forEach(function (r) {\n"
        + "        var runEl = document.createElement('div');\n"
        + "        runEl.className = 'run';\n"
        + "        runEl.innerHTML = '<div class=\"run-row\">'\n"
        + "          + '<span class=\"dot ' + esc(r.status) + '\"></span>'\n"
        + "          + '<span class=\"badge ' + esc(r.status) + '\">' + esc(r.status) + '</span>'\n"
        + "          + '<span class=\"started\">' + esc(r.started) + '</span>'\n"
        + "          + '<span class=\"dur\">' + esc(r.duration) + '</span>'\n"
        + "          + '<span class=\"reason\">' + esc(r.reason || '(no reason captured)') + '</span>'\n"
        + "          + '</div>';\n"
        + "\n"
        + "        var detail = document.createElement('div');\n"
        + "        detail.className = 'detail';\n"
        + "        detail.textContent = 'Type:     ' + r.type\n"
        + "          + '\\nStarted:  ' + r.started\n"
        + "          + '\\nEnded:    ' + r.ended\n"
        + "          + '\\nDuration: ' + r.duration\n"
        + "          + '\\nReason:   ' + (r.reason || '-')\n"
        + "          + '\\n\\n--- Full run log ---\\n'\n"
        + "          + (r.details || '(no detail lines captured)');\n"
        + "\n"
        + "        runEl.addEventListener('click', function () {\n"
        + "          detail.classList.toggle('open');\n"
        + "        });\n"
        + "\n"
        + "        runsWrap.appendChild(runEl);\n"
        + "        runsWrap.appendChild(detail);\n"
        + "      });\n"
        + "\n"
        + "      head.addEventListener('click', function () {\n"
        + "        head.classList.toggle('open');\n"
        + "        runsWrap.classList.toggle('open');\n"
        + "      });\n"
        + "\n"
        + "      taskEl.appendChild(head);\n"
        + "      taskEl.appendChild(runsWrap);\n"
        + "      tree.appendChild(taskEl);\n"
        + "    });\n"
        + "  }\n"
        + "\n"
        + "  document.getElementById('search').addEventListener('input', render);\n"
        + "\n"
        + "  Array.prototype.forEach.call(document.querySelectorAll('.chip'), function (chip) {\n"
        + "    chip.addEventListener('click', function () {\n"
        + "      Array.prototype.forEach.call(document.querySelectorAll('.chip'), function (c) { c.classList.remove('active'); });\n"
        + "      chip.classList.add('active');\n"
        + "      statusFilter = chip.getAttribute('data-status');\n"
        + "      render();\n"
        + "    });\n"
        + "  });\n"
        + "\n"
        + "  document.getElementById('expandAll').addEventListener('click', function () {\n"
        + "    Array.prototype.forEach.call(document.querySelectorAll('.task-head'), function (h) { h.classList.add('open'); });\n"
        + "    Array.prototype.forEach.call(document.querySelectorAll('.runs'), function (r) { r.classList.add('open'); });\n"
        + "  });\n"
        + "  document.getElementById('collapseAll').addEventListener('click', function () {\n"
        + "    Array.prototype.forEach.call(document.querySelectorAll('.task-head'), function (h) { h.classList.remove('open'); });\n"
        + "    Array.prototype.forEach.call(document.querySelectorAll('.runs'), function (r) { r.classList.remove('open'); });\n"
        + "  });\n"
        + "\n"
        + "  render();\n"
        + "})();\n"
        + "</script>\n"
        + "</body>\n"
        + "</html>\n";
}
