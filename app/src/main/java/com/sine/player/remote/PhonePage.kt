package com.sine.player.remote

/** The phone remote page: keyboard for the focused field, D-pad, media keys, seek bar, play a link. */
object PhonePage {
    val html = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="theme-color" content="#121014">
<title>Sine remote</title>
<style>
  :root { --bg:#121014; --surface:#1A181D; --high:#26232B; --accent:#F5C451; --text:#F4F1EA; --muted:#A9A3B0; }
  * { box-sizing:border-box; -webkit-tap-highlight-color:transparent; }
  body { margin:0; background:var(--bg); color:var(--text); font:16px/1.4 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif; padding:16px 16px calc(24px + env(safe-area-inset-bottom)); max-width:520px; margin:auto; }
  h1 { font-size:20px; margin:4px 0 2px; } h1 span { color:var(--accent); }
  .status { color:var(--muted); font-size:14px; min-height:20px; margin-bottom:14px; }
  section { background:var(--surface); border-radius:16px; padding:14px; margin-bottom:14px; }
  label { display:block; color:var(--muted); font-size:13px; margin-bottom:6px; }
  input[type=text], input[type=url] { width:100%; padding:12px 14px; border-radius:10px; border:2px solid var(--high); background:var(--bg); color:var(--text); font-size:17px; }
  input:focus { outline:none; border-color:var(--accent); }
  .row { display:flex; gap:8px; margin-top:10px; flex-wrap:wrap; }
  button { flex:1; min-height:48px; border:0; border-radius:12px; background:var(--high); color:var(--text); font-size:16px; font-weight:600; cursor:pointer; touch-action:manipulation; }
  button:active { transform:scale(.96); background:#332f39; }
  button.primary { background:var(--accent); color:#1A1405; }
  .pad { display:grid; grid-template-columns:repeat(3,1fr); gap:8px; max-width:300px; margin:0 auto; }
  .pad button { min-height:64px; font-size:22px; }
  .pad .ok { background:var(--accent); color:#1A1405; border-radius:50%; aspect-ratio:1; }
  .colors button { min-height:36px; }
  input[type=range] { width:100%; accent-color:var(--accent); margin-top:8px; }
  .time { display:flex; justify-content:space-between; color:var(--muted); font-size:13px; }
  .hidden { display:none; }
</style>
</head>
<body>
<h1><span>Sine</span> remote</h1>
<div class="status" id="status">Connecting…</div>

<section>
  <label id="fieldLabel" for="text">Type here, then Send to the selected box on the TV</label>
  <input id="text" type="text" autocomplete="off" autocapitalize="off" spellcheck="false" placeholder="Text, URL, API key…">
  <div class="row">
    <button class="primary" onclick="sendText('replace')">Send</button>
    <button onclick="sendText('submit')">Next box ↓</button>
    <button onclick="post('text',{mode:'backspace'})">⌫</button>
  </div>
</section>

<section>
  <div class="pad">
    <button onclick="key('back')">↩</button><button onclick="key('up')">▲</button><button onclick="key('menu')">☰</button>
    <button onclick="key('left')">◀</button><button class="ok" onclick="key('ok')">OK</button><button onclick="key('right')">▶</button>
    <button onclick="key('search')">🔍</button><button onclick="key('down')">▼</button><button onclick="key('playpause')">⏯</button>
  </div>
  <div class="row">
    <button onclick="key('previous')">⏮</button><button onclick="key('rewind')">⏪</button>
    <button onclick="key('forward')">⏩</button><button onclick="key('next')">⏭</button>
  </div>
  <div class="row colors">
    <button style="background:#b3363a" onclick="key('red')" aria-label="Red"></button>
    <button style="background:#2f8f4e" onclick="key('green')" aria-label="Green"></button>
    <button style="background:#c9a227" onclick="key('yellow')" aria-label="Yellow"></button>
    <button style="background:#2d5fb3" onclick="key('blue')" aria-label="Blue"></button>
  </div>
</section>

<section id="player" class="hidden">
  <label id="nowTitle">Playing</label>
  <input id="seek" type="range" min="0" max="1000" value="0">
  <div class="time"><span id="pos">0:00</span><span id="dur">0:00</span></div>
</section>

<section>
  <label for="link">Play a link on the TV</label>
  <input id="link" type="url" placeholder="https://…/video.mkv or .m3u8">
  <div class="row"><button class="primary" onclick="playLink()">Play on TV</button></div>
</section>

<script>
  const base = location.pathname.replace(/\/?$/, '/');
  const ${'$'} = id => document.getElementById(id);
  let lastField = null, seeking = false, duration = 0;

  async function post(action, body) {
    try {
      const r = await fetch(base + action, { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify(body || {}) });
      if (r.ok) show(await r.json()); else ${'$'}('status').textContent = await r.text();
    } catch (e) { ${'$'}('status').textContent = 'Can\'t reach the TV. Is Sine still open on the same Wi-Fi?'; }
  }
  function key(k) { if (navigator.vibrate) navigator.vibrate(8); post('key', { key:k }); }
  function sendText(mode) { post('text', { mode, text: ${'$'}('text').value }); }
  function playLink() { const url = ${'$'}('link').value.trim(); if (url) post('play', { url }); }
  function fmt(ms) {
    const s = Math.max(0, Math.floor(ms / 1000)), h = Math.floor(s / 3600), m = Math.floor(s / 60) % 60, x = String(s % 60).padStart(2, '0');
    return h ? h + ':' + String(m).padStart(2, '0') + ':' + x : m + ':' + x;
  }
  function show(state) {
    const f = state.field;
    if (f) {
      ${'$'}('fieldLabel').textContent = 'Typing into: ' + f.label + (f.password ? ' (hidden)' : '');
      ${'$'}('text').type = f.password ? 'password' : 'text';
      const id = f.label;
      if (id !== lastField) { ${'$'}('text').value = f.value || ''; lastField = id; }
    } else {
      ${'$'}('fieldLabel').textContent = 'Select a text box on the TV, or just use the remote below';
      ${'$'}('text').type = 'text'; lastField = null;
    }
    const p = state.playing;
    ${'$'}('player').classList.toggle('hidden', !p);
    if (p) {
      duration = p.duration;
      ${'$'}('nowTitle').textContent = (p.isPlaying ? '▶ ' : '❚❚ ') + (p.title || 'Playing');
      ${'$'}('dur').textContent = fmt(p.duration);
      if (!seeking) { ${'$'}('pos').textContent = fmt(p.position); ${'$'}('seek').value = p.duration > 0 ? Math.round(p.position / p.duration * 1000) : 0; }
    }
    ${'$'}('status').textContent = state.foreground ? 'Connected' : 'Connected · Sine is in the background';
  }
  ${'$'}('text').addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); sendText('replace'); } });
  ${'$'}('seek').addEventListener('input', e => { seeking = true; ${'$'}('pos').textContent = fmt(e.target.value / 1000 * duration); });
  ${'$'}('seek').addEventListener('change', e => { seeking = false; post('seek', { ms: Math.round(e.target.value / 1000 * duration) }); });
  async function poll() {
    try { const r = await fetch(base + 'state', { cache:'no-store' }); if (r.ok) show(await r.json()); else ${'$'}('status').textContent = await r.text(); }
    catch (e) { ${'$'}('status').textContent = 'Can\'t reach the TV.'; }
    setTimeout(poll, document.hidden ? 5000 : 1500);
  }
  poll();
</script>
</body>
</html>
""".trimIndent()
}
