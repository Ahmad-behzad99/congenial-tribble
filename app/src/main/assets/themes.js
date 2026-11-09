/* قالب‌های رنگی برنامه — مشترک بین فاکتورساز (index.html) و لیست کار (work.html) */
(function () {
  var P = {
    night:  { n: "آبی شب",     d: 1, pg: "linear-gradient(180deg,#06142b,#0a2347)", bg: "#07162e", sf: "#0f2747", so: "#14315a", ink: "#eaf3ff", mu: "#8fb0d6", ln: "#1e4a86", ac: "#2d8cff", ai: "#7cc0ff", as: "#12355f", on: "#06142b", ok: "#31d08c", os: "#0f3a33", wa: "#f2b23a", ws: "#3a2f12", dn: "#ff7b7b", hd: "linear-gradient(120deg,#0c2d5e,#0a1d3d)", h1: "#0c2d5e", gl: "rgba(45,140,255,.35)" },
    sky:    { n: "آسمان روشن", d: 0, pg: "linear-gradient(180deg,#e6f3ff,#f7fbff)", bg: "#eaf5ff", sf: "#ffffff", so: "#dcecfb", ink: "#10233f", mu: "#5f7692", ln: "#c9dcf0", ac: "#1f7cf0", ai: "#1558b8", as: "#e1eefe", on: "#ffffff", ok: "#1aa56a", os: "#dcf5e9", wa: "#d98a10", ws: "#fdf0d8", dn: "#c93a3a", hd: "linear-gradient(120deg,#1e78f0,#12b5c0)", h1: "#1e78f0", gl: "rgba(31,124,240,.22)" },
    gold:   { n: "مشکی طلایی", d: 1, pg: "linear-gradient(180deg,#0b0906,#1a1408)", bg: "#0b0906", sf: "#16120a", so: "#211a0c", ink: "#f5e9c8", mu: "#b49a62", ln: "#5b4616", ac: "#e0b040", ai: "#f3cf70", as: "#2a2008", on: "#1a1204", ok: "#8fd16a", os: "#1f2a10", wa: "#e0b040", ws: "#2a2008", dn: "#ff8a6b", hd: "linear-gradient(120deg,#33260a,#14100a)", h1: "#33260a", gl: "rgba(224,176,64,.28)" },
    violet: { n: "بنفش",       d: 1, pg: "linear-gradient(180deg,#0d0820,#1a0f3a)", bg: "#0d0820", sf: "#1a1236", so: "#251a4d", ink: "#f1e9ff", mu: "#b09ad8", ln: "#4a2f8f", ac: "#a259ff", ai: "#cfa8ff", as: "#2b1b57", on: "#12082b", ok: "#46d9a0", os: "#123a30", wa: "#f5b35a", ws: "#3a2a14", dn: "#ff7f9a", hd: "linear-gradient(120deg,#5b2bc9,#2a1260)", h1: "#4a22a8", gl: "rgba(162,89,255,.35)" },
    leaf:   { n: "سبز روشن",   d: 0, pg: "linear-gradient(180deg,#eef8e4,#fafdf4)", bg: "#eff8e6", sf: "#ffffff", so: "#e1f0d2", ink: "#12281a", mu: "#4d6a4a", ln: "#c2dba8", ac: "#278a3a", ai: "#17652a", as: "#e3f4d9", on: "#ffffff", ok: "#1f7a31", os: "#dff3dc", wa: "#b8730a", ws: "#fbefd5", dn: "#b3261e", hd: "linear-gradient(120deg,#1b6e2d,#3d9a45)", h1: "#1b6e2d", gl: "rgba(39,138,58,.3)" },
    sunset: { n: "غروب",       d: 1, pg: "linear-gradient(180deg,#1a0d07,#2e1409)", bg: "#1a0d07", sf: "#2a170d", so: "#38200f", ink: "#ffeedd", mu: "#d1a07a", ln: "#6b3a17", ac: "#ff8a1f", ai: "#ffb766", as: "#3d230f", on: "#1a0d07", ok: "#7bd389", os: "#1f3320", wa: "#ffc14a", ws: "#3d2c0d", dn: "#ff7a6a", hd: "linear-gradient(120deg,#b4531a,#5a2410)", h1: "#9a4516", gl: "rgba(255,138,31,.3)" },
    forest: { n: "جنگل",       d: 1, pg: "linear-gradient(180deg,#04140c,#0a2a17)", bg: "#04140c", sf: "#0e2a1a", so: "#153a24", ink: "#e6f8ec", mu: "#8fc4a2", ln: "#1f6a3f", ac: "#22c55e", ai: "#6fe39b", as: "#14402a", on: "#04140c", ok: "#22c55e", os: "#14402a", wa: "#e6b94a", ws: "#3a300f", dn: "#ff8a80", hd: "linear-gradient(120deg,#12633a,#0a2a17)", h1: "#12633a", gl: "rgba(34,197,94,.3)" }
  };
  window.__PRESETS = P;
  function get() { try { var k = localStorage.getItem("uth"); return P[k] ? k : ""; } catch (e) { return ""; } }
  window.__presetKey = get;
  function vars(p) {
    return "--pg:" + p.pg + ";--gl:" + p.gl + ";color-scheme:" + (p.d ? "dark" : "light") +
      /* فاکتورساز */
      ";--bg:" + p.bg + ";--surface:" + p.sf + ";--soft:" + p.so + ";--ink:" + p.ink + ";--mute:" + p.mu + ";--line:" + p.ln +
      ";--orange:" + p.ac + ";--orange-ink:" + p.ai + ";--on-orange:" + p.on + ";--peach:" + p.as + ";--mint:" + p.os +
      ";--danger:" + p.dn + ";--saffron:" + p.wa + ";--lapis:" + p.h1 + ";--pill:" + p.h1 + ";--hdr:" + p.hd +
      /* لیست کار */
      ";--cd:" + p.sf + ";--tx:" + p.ink + ";--mu:" + p.mu + ";--bd:" + p.ln + ";--ac:" + p.ac + ";--ab:" + p.as +
      ";--am:" + p.wa + ";--amb:" + p.ws + ";--gn:" + p.ok + ";--gnb:" + p.os + ";--rd:" + p.dn + ";--hd:" + p.hd +
      ";--xo:" + p.so + ";--xg:" + p.ac + ";--xgt:" + p.on;
  }
  window.__applyPreset = function () {
    var r = document.documentElement, k = get(), el = document.getElementById("preset-css");
    if (!k) { r.removeAttribute("data-pt"); if (el) el.textContent = ""; return; }
    var p = P[k];
    r.setAttribute("data-pt", k);
    r.setAttribute("data-theme", p.d ? "dark" : "light");
    if (!el) { el = document.createElement("style"); el.id = "preset-css"; document.head.appendChild(el); }
    el.textContent =
      "html:root[data-pt]{" + vars(p) + "}" +
      "html[data-pt] body{background:var(--pg) fixed no-repeat;background-color:var(--bg)}" +
      "html[data-pt] .app{background:transparent}" +
      "html[data-pt] .bar{background-color:var(--lapis);background-image:var(--hdr)}" +
      "html[data-pt] .card{box-shadow:0 0 0 1px var(--line),0 10px 26px -14px var(--gl)}" +
      "html[data-pt] .add button:not(.alt):not(.bad),html[data-pt] .btn.p{color:var(--on-orange)}";
  };
  /* انتخاب‌گر قالب و فونت (برای تنظیمات) */
  var st = document.createElement("style");
  st.textContent = ".tpick{display:grid;grid-template-columns:1fr 1fr;gap:8px}.tpick button{display:flex;align-items:center;gap:8px;padding:8px 10px;border-radius:12px;border:1.5px solid var(--line,var(--bd));background:var(--surface,var(--cd));color:var(--ink,var(--tx));font:inherit;font-size:13px;cursor:pointer;text-align:start}.tpick button[aria-pressed=true]{border-color:var(--orange,var(--ac));box-shadow:0 0 0 2px var(--orange,var(--ac)) inset}.tpick i{flex:none;width:30px;height:30px;border-radius:9px;border:1px solid rgba(128,128,128,.4)}.tpick.fnt{grid-template-columns:1fr}";
  document.head.appendChild(st);
  window.__presetHTML = function () {
    var cur = get();
    var h = '<div class="tpick"><button data-a="theme" data-k="" aria-pressed="' + (cur === "") + '"><i style="background:linear-gradient(135deg,#f6f1e7 50%,#3b4cca 50%)"></i>پیش‌فرض</button>';
    Object.keys(P).forEach(function (k) {
      var p = P[k];
      h += '<button data-a="theme" data-k="' + k + '" aria-pressed="' + (cur === k) + '"><i style="background:linear-gradient(135deg,' + p.bg + ' 50%,' + p.ac + ' 50%)"></i>' + p.n + "</button>";
    });
    return h + "</div>";
  };
  window.__setPreset = function (k) {
    try { localStorage.setItem("uth", P[k] ? k : ""); } catch (e) {}
    if (window.__applyUI) window.__applyUI();          /* فاکتورساز: حالت روشن/تیره را دوباره می‌گذارد و قالب را اعمال می‌کند */
    else if (parent !== window && parent.__applyUI) parent.__applyUI();
    else { window.__applyPreset(); if (!P[k]) document.documentElement.removeAttribute("data-theme"); }
    try { window.__applyPreset(); } catch (e) {}
  };
  /* فونت (فقط برای صفحهٔ فاکتورساز؛ لیست کار منطق خودش را دارد) */
  var FN = { gandom: "گندم (پیش‌فرض)", labkhand: "لبخند", gofteh: "گفته", vazir: "وزیرمتن", shabnam: "شبنم", kara: "کارا", soraya: "ثریا", titr: "تیتر", ordi: "اردیبهشت", rooz: "روزنامه", nast: "ایران نستعلیق" };
  var FF = { gandom: "Gandom", labkhand: "Labkhand", gofteh: "Gofteh", vazir: "Vazirmatn", shabnam: "Shabnam", kara: "SGKara", soraya: "ASoraya", titr: "BTitr", ordi: "Ordibehesht", rooz: "ARooznameh", nast: "IranNastaliq" };
  window.__fontHTML = function () {
    var c = "gandom"; try { c = localStorage.getItem("uf") || "gandom"; } catch (e) {}
    return '<div class="tpick fnt">' + Object.keys(FN).map(function (k) {
      return '<button data-a="font" data-f="' + k + '" aria-pressed="' + (c === k) + '" style="font-family:' + FF[k] + ',Gandom">' + (c === k ? "✓ " : "") + FN[k] + " — ۱۲۳٬۴۵۶ تومان</button>";
    }).join("") + "</div>";
  };
  window.__setFont = function (k) {
    try { localStorage.setItem("uf", k); } catch (e) {}
    try { window.__applyFont && window.__applyFont(); } catch (e) {}
    try { var f = document.getElementById("wf"); if (f && f.contentWindow) f.contentWindow.dispatchEvent(new StorageEvent("storage", { key: "uf" })); } catch (e) {}
  };
  /* ---- اندازه، وزن و رنگ متن (برای همهٔ برنامه) ---- */
  function tg(k, d) { try { var v = localStorage.getItem(k); return v == null || v === "" ? d : v; } catch (e) { return d; } }
  function ts(k, v) { try { if (v === "" || v == null) localStorage.removeItem(k); else localStorage.setItem(k, String(v)); } catch (e) {} }
  window.__applyTypo = function () {
    var z = parseInt(tg("ufs", "100"), 10) || 100, w = tg("ufw", ""), c = tg("ufc", "");
    var el = document.getElementById("typo-css");
    if (!el) { el = document.createElement("style"); el.id = "typo-css"; document.head.appendChild(el); }
    var css = "";
    if (c) css += "html:root:root:root{--ink:" + c + ";--tx:" + c + "}";
    if (w) css += "html body,html body *{font-weight:" + w + "!important}";
    el.textContent = css;
    if (parent === window) { try { if (window.Android && window.Android.setTextZoom) window.Android.setTextZoom(z); } catch (e) {} }
  };
  var tst = document.createElement("style");
  tst.textContent = ".tyr{display:flex;flex-wrap:wrap;gap:6px;margin:6px 0 12px}.tyr button{padding:6px 12px;border-radius:12px;border:1.5px solid var(--line,var(--bd));background:var(--surface,var(--cd));color:var(--ink,var(--tx));font:inherit;font-size:13px;cursor:pointer}.tyr button[aria-pressed=true]{border-color:var(--orange,var(--ac));box-shadow:0 0 0 2px var(--orange,var(--ac)) inset}.tyr .tysw{width:32px;height:32px;padding:0;border-radius:50%}.tyl{display:flex;align-items:center;gap:10px;margin:6px 0 12px}.tyl input{flex:1}.typrev{padding:10px;border-radius:12px;border:1px dashed var(--line,var(--bd));margin:4px 0 10px}";
  document.head.appendChild(tst);
  var TCOL = ["#000000", "#1f2340", "#444444", "#b3261e", "#c97a12", "#1f8a5b", "#0f7a9b", "#3b4cca", "#7a3bb5", "#ffffff"];
  function typoInner() {
    var z = parseInt(tg("ufs", "100"), 10) || 100, w = tg("ufw", ""), c = tg("ufc", ""), hh = tg("ufh", "220"), ll = tg("ufl", "30");
    var W = [["", "پیش‌فرض"], ["300", "نازک"], ["400", "معمولی"], ["700", "ضخیم"]];
    var h = '<p class="hint" style="text-align:start;margin:0 0 6px">اندازه، وزن و رنگ نوشته‌های برنامه (لیست‌ها، صفحه‌ها و تنظیمات).</p>';
    h += '<h3>اندازهٔ متن: <span id="tyz">' + z + '٪</span></h3><div class="tyl"><span>کوچک</span><input type="range" min="80" max="150" step="5" value="' + z + '" data-typo="size"><span>بزرگ</span></div>';
    h += '<h3>وزن متن</h3><div class="tyr">' + W.map(function (x) { return '<button type="button" data-typo="wt" data-v="' + x[0] + '" aria-pressed="' + (w === x[0]) + '">' + x[1] + "</button>"; }).join("") + "</div>";
    h += '<p class="hint" style="text-align:start;margin:-6px 0 10px">وزن «نازک» روی بعضی فونت‌ها اثر کمتری دارد؛ وزیرمتن و شبنم بهتر نشان می‌دهند.</p>';
    h += '<h3>رنگ متن</h3><div class="tyr"><button type="button" data-typo="col" data-v="" aria-pressed="' + (c === "") + '">پیش‌فرض</button>' +
      TCOL.map(function (x) { return '<button type="button" class="tysw" data-typo="col" data-v="' + x + '" aria-pressed="' + (c === x) + '" aria-label="' + x + '" style="background:' + x + '"></button>'; }).join("") + "</div>";
    h += '<div class="tyl"><span>رنگ</span><input type="range" min="0" max="360" value="' + hh + '" data-typo="hue" style="background:linear-gradient(90deg,#f00,#ff0,#0f0,#0ff,#00f,#f0f,#f00);height:10px;border-radius:6px"></div>';
    h += '<div class="tyl"><span>روشنی</span><input type="range" min="5" max="95" value="' + ll + '" data-typo="lit"></div>';
    h += '<div class="typrev" id="typrev">نمونه: ۱۲۳٬۴۵۶ تومان — قرض گرفته‌ام</div><button type="button" class="tyr-reset" data-typo="reset" style="padding:8px 14px;border-radius:12px;border:1.5px solid var(--line,var(--bd));background:none;color:inherit;font:inherit;cursor:pointer">بازنشانی اندازه، وزن و رنگ</button>';
    return h;
  }
  window.__typoHTML = function () { return '<div id="typobox">' + typoInner() + "</div>"; };
  function typoApplyAll() {
    window.__applyTypo();
    try { var f = document.getElementById("wf"); if (f && f.contentWindow && f.contentWindow.__applyTypo) f.contentWindow.__applyTypo(); } catch (e) {}
  }
  function typoRefresh() { var b = document.getElementById("typobox"); if (b) b.innerHTML = typoInner(); }
  document.addEventListener("input", function (e) {
    var t = e.target, k = t && t.dataset && t.dataset.typo; if (!k) return;
    if (k === "size") { ts("ufs", t.value === "100" ? "" : t.value); var l = document.getElementById("tyz"); if (l) l.textContent = t.value + "٪"; typoApplyAll(); return; }
    if (k === "hue" || k === "lit") {
      if (k === "hue") ts("ufh", t.value); else ts("ufl", t.value);
      var col = "hsl(" + tg("ufh", "220") + ",70%," + tg("ufl", "30") + "%)";
      ts("ufc", col); typoApplyAll();
      var pv = document.getElementById("typrev"); if (pv) pv.style.color = col;
    }
  });
  document.addEventListener("click", function (e) {
    var b = e.target.closest && e.target.closest("button[data-typo]"); if (!b) return;
    var k = b.dataset.typo;
    if (k === "wt") ts("ufw", b.dataset.v);
    else if (k === "col") ts("ufc", b.dataset.v);
    else if (k === "reset") { ["ufs", "ufw", "ufc", "ufh", "ufl"].forEach(function (x) { ts(x, ""); }); }
    else return;
    typoApplyAll(); typoRefresh();
  });
  window.__applyTypo();
})();
