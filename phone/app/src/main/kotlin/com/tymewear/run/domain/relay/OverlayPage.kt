package com.tymewear.run.domain.relay

/** Self-contained overlay page served at GET /overlay. Polls /live once a second with the same token. */
object OverlayPage {
    val HTML: String = """<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>K-Breathe</title>
<style>
  html,body{margin:0;height:100%;background:#111;color:#fff;font-family:system-ui,Segoe UI,Roboto,sans-serif}
  #panel{box-sizing:border-box;min-height:100%;padding:24px;background:#424242;transition:background .3s}
  #panel.dead{background:#616161}
  .top{display:flex;align-items:center;gap:8px;font-size:14px;color:#bdbdbd}
  #dot{width:12px;height:12px;border-radius:50%;background:#9e9e9e}
  #ve{font-size:18vw;font-weight:700;line-height:1}
  .unit{font-size:14px;color:#bdbdbd}
  #zone{font-size:5vw}
  .row{display:flex;gap:32px;font-size:6vw;margin-top:12px}
  .row span small{display:block;font-size:14px;color:#bdbdbd}
</style></head>
<body><div id="panel">
  <div class="top"><div id="dot"></div><div id="status">connecting</div></div>
  <div id="ve">--</div><div class="unit">L/min</div><div id="zone"></div>
  <div class="row"><span><small>BR</small><b id="br">--</b></span><span><small>TV</small><b id="tv">--</b></span></div>
</div>
<script>
  var ZONE_COLORS=['#424242','#4db6ac','#0277bd','#f57f17','#ef6c00','#c62828'];
  var ZONE_NAMES=['--','Endurance','VT1','VT2','Top Z4','VO2Max'];
  var token=new URLSearchParams(location.search).get('token');
  var liveUrl='/live'+(token?'?token='+encodeURIComponent(token):'');
  var failures=0;
  function el(id){return document.getElementById(id)}
  function render(p){
    failures=0; el('panel').classList.remove('dead');
    var live=p.status==='connected';
    el('ve').textContent=live&&p.ve!=null?Math.round(p.ve):'--';
    el('br').textContent=live&&p.br!=null?Math.round(p.br):'--';
    el('tv').textContent=live&&p.tv!=null?p.tv.toFixed(1):'--';
    var z=live?p.zone:0;
    el('zone').textContent=live?ZONE_NAMES[z]||'':'';
    el('panel').style.background=ZONE_COLORS[z]||ZONE_COLORS[0];
    el('status').textContent=p.status;
    el('dot').style.background=p.status==='connected'?'#66bb6a':p.status==='stale'?'#ffb300':'#9e9e9e';
  }
  function fail(){
    failures++; el('status').textContent='phone?'; el('dot').style.background='#c62828';
    if(failures>=5){el('panel').classList.add('dead');el('panel').style.background='';}
  }
  function poll(){
    var ctl=new AbortController(); var t=setTimeout(function(){ctl.abort()},900);
    fetch(liveUrl,{signal:ctl.signal,cache:'no-store'}).then(function(r){if(!r.ok)throw new Error(r.status);return r.json()})
      .then(render).catch(fail).then(function(){clearTimeout(t)});
  }
  poll(); setInterval(poll,1000);
</script></body></html>
"""
}
