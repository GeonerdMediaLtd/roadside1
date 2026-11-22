/* Roadside: a GeoGuessr-style game on Mapillary street-level imagery.
   Plain browser JavaScript, no build step. */
(() => {
"use strict";
const CFG = Object.assign({ mapillaryToken: "", roundsPerGame: 5,
  tileUrl: "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
  tileAttribution: "&copy; OpenStreetMap contributors" }, window.ROADSIDE_CONFIG || {});
const $ = id => document.getElementById(id);
const API = "https://graph.mapillary.com/images";
const N = CFG.roundsPerGame;

/* ---------- storage (never throws) ---------- */
const store = {
  get(k, d) { try { const v = localStorage.getItem("roadside-" + k); return v == null ? d : JSON.parse(v); } catch (e) { return d; } },
  set(k, v) { try { localStorage.setItem("roadside-" + k, JSON.stringify(v)); } catch (e) {} }
};
const token = () => (store.get("token", "") || CFG.mapillaryToken || "").trim();

/* ---------- helpers ---------- */
const esc = s => String(s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const fmt = n => Math.round(n).toLocaleString("en-US");
const hav = (a, b, c, d) => { const r = Math.PI / 180, x = Math.sin((c - a) * r / 2) ** 2 + Math.cos(a * r) * Math.cos(c * r) * Math.sin((d - b) * r / 2) ** 2; return 12742 * Math.asin(Math.min(1, Math.sqrt(x))); };
function mulberry32(a) { return () => { a |= 0; a = a + 0x6D2B79F5 | 0; let t = Math.imul(a ^ a >>> 15, 1 | a); t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t; return ((t ^ t >>> 14) >>> 0) / 4294967296; }; }
const todayKey = () => new Date().toISOString().slice(0, 10); // UTC day, same for everyone
const seedFrom = s => { let h = 2166136261; for (const ch of s) { h ^= ch.charCodeAt(0); h = Math.imul(h, 16777619); } return h >>> 0; };
const shuffle = (arr, rnd) => { const a = arr.slice(); for (let i = a.length - 1; i > 0; i--) { const j = Math.floor(rnd() * (i + 1)); [a[i], a[j]] = [a[j], a[i]]; } return a; };
// Scoring: like GeoGuessr, the falloff scales with the size of the playing area.
const SCALE = { World: 2000, Europe: 600, Asia: 1200, Africa: 1000, "North America": 900, "South America": 800, Oceania: 900 };
const scoreFor = (km, region) => km < 0.05 ? 5000 : Math.round(5000 * Math.exp(-km / (SCALE[region] || 2000)));

/* ---------- Mapillary search ---------- */
class TokenError extends Error {}
async function searchImages(lat, lon, d, pano, tok) {
  const bbox = [lon - d, lat - d, lon + d, lat + d].map(v => v.toFixed(5)).join(",");
  const url = `${API}?access_token=${encodeURIComponent(tok)}&fields=id,computed_geometry,geometry,is_pano,creator,captured_at&bbox=${bbox}&limit=100${pano ? "&is_pano=true" : ""}`;
  const r = await fetch(url);
  if (r.status === 401 || r.status === 403) throw new TokenError("Mapillary rejected the token.");
  if (!r.ok) throw new Error("Mapillary search failed (" + r.status + ")");
  const j = await r.json();
  return (j.data || []).filter(x => x.id && (x.computed_geometry || x.geometry));
}
// Find a street-level image near a seed place. Prefers 360° panoramas.
async function findRoad(place, rnd, tok) {
  const spread = place.spread ?? 0.06;
  const lat = place.lat + (rnd() - 0.5) * 2 * spread, lon = place.lon + (rnd() - 0.5) * 2 * spread;
  const tries = [[lat, lon, 0.01, true], [lat, lon, 0.03, true], [place.lat, place.lon, 0.01, true], [lat, lon, 0.02, false], [place.lat, place.lon, 0.02, false]];
  for (const [a, b, d, pano] of tries) {
    let list;
    try { list = await searchImages(a, b, d, pano, tok); } catch (e) { if (e instanceof TokenError) throw e; continue; }
    if (!list.length) continue;
    list.sort((x, y) => (x.id < y.id ? -1 : 1)); // stable order so the daily pick is the same for everyone
    const im = list[Math.floor(rnd() * list.length)];
    const [ilon, ilat] = (im.computed_geometry || im.geometry).coordinates;
    return { id: String(im.id), lat: ilat, lon: ilon, pano: !!im.is_pano, user: im.creator && im.creator.username, at: im.captured_at, place };
  }
  return null;
}

/* ---------- state ---------- */
let PLACES = [];
const S = { override: {}, mode: "classic", region: "World", timer: 0, rounds: [], ri: 0, total: 0, guess: null, results: [], queue: null, rnd: Math.random, tick: 0, left: 0, locked: false };

/* ---------- home screen ---------- */
for (const b of document.querySelectorAll(".mode")) b.onclick = () => {
  for (const o of document.querySelectorAll(".mode")) { o.classList.toggle("on", o === b); o.setAttribute("aria-checked", o === b); }
  S.mode = b.dataset.mode; homeNote();
};
$("optRegion").value = store.get("region", "World");
$("optTimer").value = String(store.get("timer", 0));
function homeNote() {
  const best = store.get("best", 0), daily = store.get("daily-" + todayKey(), null);
  let t = best ? `Your best: ${fmt(best)} / ${fmt(N * 5000)}` : "";
  if (S.mode === "daily") t = daily != null ? `You scored ${fmt(daily)} on today's daily. Replays won't count.` : "Daily: everyone gets the same roads today, whole world, no timer.";
  $("homeNote").textContent = t;
  $("optRegion").disabled = $("optTimer").disabled = S.mode === "daily";
}
function tokStatus() { $("tokMsg").textContent = token() ? "A token is set." : "No token yet: the game needs one to load streets."; }
$("tok").value = store.get("token", "");
$("btnTok").onclick = async () => {
  const v = $("tok").value.trim();
  store.set("token", v);
  if (!v) { tokStatus(); return; }
  $("tokMsg").textContent = "Checking…";
  try { await searchImages(52.3676, 4.9041, 0.005, false, v); $("tokMsg").textContent = "Token works. You're ready to play."; }
  catch (e) { $("tokMsg").textContent = e instanceof TokenError ? "Mapillary didn't accept that token. Check you copied the client token (MLY|…)." : "Couldn't reach Mapillary to check it. It's saved; try playing."; }
};
tokStatus(); homeNote();

/* ---------- maps ---------- */
const pinIcon = color => L.divIcon({ className: "pin", iconSize: [26, 34], iconAnchor: [13, 34],
  html: `<svg viewBox="0 0 26 34" width="26" height="34"><path d="M13 33C9 26 2 21 2 13a11 11 0 1 1 22 0c0 8-7 13-11 20Z" fill="${color}" stroke="#fff" stroke-width="2.5"/><circle cx="13" cy="13" r="4" fill="#fff"/></svg>` });
const tiles = () => L.tileLayer(CFG.tileUrl, { maxZoom: 18, attribution: CFG.tileAttribution });
if (!window.L || !window.mapillary) { document.getElementById("homeNote").textContent = "The map or street viewer library didn't load. Check your connection (or an ad blocker) and reload."; return; }
const gmap = L.map("gmap", { worldCopyJump: true, minZoom: 1, zoomControl: true }).setView([20, 0], 1);
tiles().addTo(gmap);
let gpin = null;
gmap.on("click", e => {
  if (S.locked) return;
  const ll = e.latlng.wrap();
  S.guess = ll;
  if (gpin) gpin.setLatLng(e.latlng); else gpin = L.marker(e.latlng, { icon: pinIcon("#ff5a2e"), keyboard: false }).addTo(gmap);
  $("btnGuess").disabled = false; $("mapMsg").textContent = "Pin dropped. Tap again to move it.";
});
const mapbox = $("mapbox");
$("btnMapOpen").onclick = () => { mapbox.classList.add("open"); setTimeout(() => gmap.invalidateSize(), 320); };
$("btnMapClose").onclick = () => mapbox.classList.remove("open");
mapbox.addEventListener("transitionend", () => gmap.invalidateSize());
const rmap = L.map("rmap", { worldCopyJump: true, minZoom: 1 }).setView([20, 0], 1); tiles().addTo(rmap);
const rlayer = L.layerGroup().addTo(rmap);
const emap = L.map("emap", { worldCopyJump: true, minZoom: 1, zoomControl: false, attributionControl: true }).setView([20, 0], 1); tiles().addTo(emap);
const elayer = L.layerGroup().addTo(emap);
// Draw guess→answer on the shorter way round the globe.
function lineFor(g, a) { let lon = g.lng; while (lon - a.lon > 180) lon -= 360; while (a.lon - lon > 180) lon += 360; return [[g.lat, lon], [a.lat, a.lon]]; }

/* ---------- street viewer ---------- */
let viewer = null, viewerToken = "";
function ensureViewer(tok) {
  if (viewer && viewerToken === tok) return viewer;
  if (viewer) { try { viewer.remove(); } catch (e) {} }
  viewerToken = tok;
  viewer = new mapillary.Viewer({
    accessToken: tok, container: "viewer",
    component: { cover: false, attribution: false, bearing: true, direction: true, sequence: true, keyboard: true, zoom: true }
  });
  return viewer;
}
function setMovement(allowed) {
  for (const c of ["direction", "sequence", "keyboard"]) {
    try { allowed ? viewer.activateComponent(c) : viewer.deactivateComponent(c); } catch (e) {}
  }
  $("btnStart").hidden = !allowed;
}

addEventListener("resize", () => { if (viewer) viewer.resize(); });

/* ---------- game flow ---------- */
function loader(on, msg) { $("loader").classList.toggle("off", !on); if (msg) $("loaderMsg").textContent = msg; }
function pool() { return S.region === "World" ? PLACES : PLACES.filter(p => p.region === S.region); }

// Builds rounds one at a time in the background so round 1 starts quickly.
function makeQueue(tok) {
  const order = shuffle(pool(), S.rnd);
  const usedCountries = new Set(); let k = 0;
  const next = async () => {
    for (let guard = 0; guard < 25 && k < order.length; guard++) {
      const p = order[k++];
      if (usedCountries.has(p.country) && order.length > N * 3) continue;
      const road = await findRoad(p, S.rnd, tok);
      if (road) { usedCountries.add(p.country); return road; }
    }
    return null;
  };
  // Chain the searches so rounds are found in order (keeps the daily the same for everyone).
  const promises = []; let chain = Promise.resolve();
  for (let i = 0; i < N; i++) { chain = chain.then(next); chain.catch(() => {}); promises.push(chain); }
  return i => promises[i];
}

async function startGame() {
  const tok = token();
  if (!tok) { $("settings").open = true; $("tok").focus(); $("tokMsg").textContent = "Paste a Mapillary token first: it's free, see the link above."; return; }
  if (!PLACES.length) { $("homeNote").textContent = "Couldn't load the location list. Check your connection and reload."; return; }
  S.region = S.mode === "daily" ? "World" : $("optRegion").value;
  S.timer = S.mode === "daily" ? 0 : +$("optTimer").value;
  store.set("region", S.region); store.set("timer", S.timer);
  S.rnd = S.mode === "daily" ? mulberry32(seedFrom("roadside-" + todayKey())) : Math.random;
  S.rounds = []; S.results = []; S.ri = 0; S.total = 0; S.override = {};
  $("home").hidden = true; $("end").hidden = true; $("game").hidden = false;
  loader(true, "Finding a road…");
  try { ensureViewer(tok); } catch (e) { return fail("Your browser couldn't start the street viewer (it needs WebGL)."); }
  setMovement(S.mode !== "nomove");
  S.queue = makeQueue(tok);
  playRound();
}

async function playRound() {
  S.locked = false; S.guess = null;
  if (gpin) { gmap.removeLayer(gpin); gpin = null; }
  $("btnGuess").disabled = true; $("mapMsg").textContent = "Tap the map to drop your pin";
  mapbox.classList.remove("open", "pinned");
  gmap.setView([20, 0], 1);
  $("hudRound").textContent = `Round ${S.ri + 1}/${N}`; $("hudScore").textContent = fmt(S.total);
  loader(true, "Finding a road…");
  let road;
  try { road = await (S.override[S.ri] || S.queue(S.ri)); }
  catch (e) { return fail(e instanceof TokenError ? "Mapillary rejected the token. Check it in settings." : "Couldn't reach Mapillary. Check your connection."); }
  if (!road) return fail("Couldn't find street imagery in this region right now. Try another region.");
  S.rounds[S.ri] = road;
  try { await viewer.moveTo(road.id); }
  catch (e) { // image unavailable: grab another
    const p = pool(); S.override[S.ri] = findRoad(p[Math.floor(Math.random() * p.length)], Math.random, token());
    return playRound();
  }
  loader(false);
  startTimer();
}
$("btnStart").onclick = () => { const r = S.rounds[S.ri]; if (r) viewer.moveTo(r.id).catch(() => {}); };
$("btnQuit").onclick = () => { stopTimer(); $("game").hidden = true; $("home").hidden = false; homeNote(); };
$("btnPlay").onclick = startGame;

function startTimer() {
  stopTimer(); const el = $("hudTimer");
  if (!S.timer) { el.hidden = true; return; }
  S.left = S.timer; el.hidden = false;
  const show = () => { el.textContent = `${Math.floor(S.left / 60)}:${String(S.left % 60).padStart(2, "0")}`; el.classList.toggle("low", S.left <= 10); };
  show();
  S.tick = setInterval(() => { S.left--; show(); if (S.left <= 0) { stopTimer(); submit(true); } }, 1000);
}
function stopTimer() { clearInterval(S.tick); S.tick = 0; }

$("btnGuess").onclick = () => submit(false);
function submit(timeout) {
  if (S.locked) return; S.locked = true; stopTimer();
  const road = S.rounds[S.ri]; if (!road) return;
  const g = S.guess;
  const km = g ? hav(g.lat, g.lng, road.lat, road.lon) : null;
  const pts = g ? scoreFor(km, S.region) : 0;
  S.total += pts; S.results.push({ road, guess: g, km, pts });
  showResult(road, g, km, pts, timeout);
}

function showResult(road, g, km, pts, timeout) {
  $("game").hidden = true; $("result").hidden = false;
  rlayer.clearLayers(); rmap.invalidateSize();
  const a = L.marker([road.lat, road.lon], { icon: pinIcon("#0e6b3c") }).addTo(rlayer);
  if (g) {
    const ln = lineFor(g, road);
    L.polyline(ln, { color: "#0f1d3a", weight: 3, dashArray: "8 7" }).addTo(rlayer);
    L.marker(ln[0], { icon: pinIcon("#ff5a2e") }).addTo(rlayer);
    rmap.fitBounds(L.latLngBounds(ln).pad(0.3), { maxZoom: 15 });
  } else rmap.setView(a.getLatLng(), 4);
  $("rDist").textContent = !g ? (timeout ? "Time's up" : "No guess") : km < 1 ? `${fmt(km * 1000)} m away` : `${fmt(km)} km away`;
  $("rPts").textContent = `+${fmt(pts)}`;
  $("rMeter").style.width = "0"; requestAnimationFrame(() => requestAnimationFrame(() => { $("rMeter").style.width = pts / 50 + "%"; }));
  $("rPlace").textContent = `Near ${road.place.name}, ${road.place.country}`;
  const when = road.at ? new Date(road.at).toLocaleDateString("en-GB", { month: "short", year: "numeric" }) : "";
  $("rCredit").innerHTML = `Imagery: ${esc(road.user || "Mapillary contributor")}${when ? ", " + esc(when) : ""} · <a href="https://www.mapillary.com/app/?pKey=${encodeURIComponent(road.id)}&focus=photo">View on Mapillary</a> · CC BY-SA 4.0`;
  $("rLinks").innerHTML = `<a href="https://earth.google.com/web/@${road.lat},${road.lon},300a,1500d,35y,0h,60t,0r">Explore in Google Earth</a><a href="https://www.openstreetmap.org/?mlat=${road.lat}&mlon=${road.lon}#map=15/${road.lat}/${road.lon}">OpenStreetMap</a>`;
  $("btnNext").textContent = S.ri === N - 1 ? "See results" : "Next round";
  $("hudScore").textContent = fmt(S.total);
}
$("btnNext").onclick = () => {
  $("result").hidden = true;
  if (S.ri < N - 1) { S.ri++; $("game").hidden = false; playRound(); } else endGame();
};

function endGame() {
  $("game").hidden = true; $("end").hidden = false;
  const max = N * 5000, best = store.get("best", 0);
  if (S.total > best) store.set("best", S.total);
  let note = S.total > best && best ? "New personal best!" : best ? `Your best: ${fmt(Math.max(best, S.total))}` : "";
  if (S.mode === "daily") {
    const k = "daily-" + todayKey();
    if (store.get(k, null) == null) { store.set(k, S.total); note = "Daily score saved. Come back tomorrow for new roads."; }
    else note = "Replay: your first daily score stays saved.";
  }
  $("endTitle").textContent = S.mode === "daily" ? `Daily · ${todayKey()}` : "Trip complete";
  $("endScore").innerHTML = `${fmt(S.total)} <span class="muted">/ ${fmt(max)}</span>`;
  const f = S.total / max;
  $("endRank").textContent = f >= .85 ? "Road scholar" : f >= .6 ? "Seasoned traveller" : f >= .3 ? "Weekend tourist" : "Happily lost";
  $("endTable").innerHTML = S.results.map(r => `<tr><td>${esc(r.road.place.name)}, ${esc(r.road.place.country)}<small>${r.km == null ? "no guess" : fmt(r.km) + " km off"}</small></td><td class="n">${fmt(r.pts)}</td></tr>`).join("");
  $("endNote").textContent = note;
  emap.invalidateSize(); elayer.clearLayers();
  const pts = [];
  for (const r of S.results) {
    L.marker([r.road.lat, r.road.lon], { icon: pinIcon("#0e6b3c") }).addTo(elayer); pts.push([r.road.lat, r.road.lon]);
    if (r.guess) { const ln = lineFor(r.guess, r.road); L.polyline(ln, { color: "#0f1d3a", weight: 2, dashArray: "6 6" }).addTo(elayer); pts.push(ln[0]); }
  }
  if (pts.length) emap.fitBounds(L.latLngBounds(pts).pad(0.2));
}
$("btnAgain").onclick = () => { $("end").hidden = true; startGame(); };
$("btnShare").onclick = async () => {
  const sq = S.results.map(r => r.pts >= 4500 ? "🟩" : r.pts >= 2500 ? "🟨" : r.pts > 0 ? "🟧" : "⬜").join("");
  const head = S.mode === "daily" ? `Roadside daily ${todayKey()}` : `Roadside (${S.region})`;
  const text = `${head}: ${fmt(S.total)}/${fmt(N * 5000)} ${sq}\n${location.href.split("#")[0]}`;
  try { if (navigator.share) { await navigator.share({ text }); return; } } catch (e) { if (e && e.name === "AbortError") return; }
  try { await navigator.clipboard.writeText(text); $("btnShare").textContent = "Copied!"; }
  catch (e) { $("endNote").textContent = text; }
};

function fail(msg) {
  stopTimer(); loader(false);
  $("game").hidden = true; $("result").hidden = true; $("end").hidden = true; $("home").hidden = false;
  $("homeNote").textContent = msg;
}

/* ---------- boot ---------- */
fetch("locations.json").then(r => r.json()).then(j => { PLACES = (j.places || []).filter(p => isFinite(p.lat) && isFinite(p.lon)); })
  .catch(() => { $("homeNote").textContent = "Couldn't load the location list."; });
if ("serviceWorker" in navigator && location.protocol === "https:") navigator.serviceWorker.register("sw.js").catch(() => {});
})();
