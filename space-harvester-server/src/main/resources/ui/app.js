// Schlanke Oberfläche über der bestehenden REST-API (#17): Quellen und Spaces anzeigen, Abgleich und Export starten,
// Fortschritt, Warnungen und Fehler verfolgen, Artefakte herunterladen. Das Bearer-Token gibt der Benutzer zur Laufzeit
// ein; es liegt nur im sessionStorage dieses Tabs und geht ausschließlich als Authorization-Header an /api/**.

const API = new URL('../api/v1', document.baseURI).pathname;
const TOKEN_KEY = 'space-harvester.token';
const POLL_MILLIS = 1000;
const FINISHED = new Set(['completed', 'failed', 'cancelled', 'interrupted']);

const $ = (id) => document.getElementById(id);
const jobs = new Map();

function token() {
  try {
    return sessionStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

function setToken(value) {
  try {
    if (value) {
      sessionStorage.setItem(TOKEN_KEY, value);
    } else {
      sessionStorage.removeItem(TOKEN_KEY);
    }
  } catch {
    // Ohne sessionStorage gilt die Anmeldung nur bis zum Neuladen.
  }
  memoryToken = value;
}

let memoryToken = token();

/** Ruft die API auf; Fehler werden als Problem Details (RFC 9457) gelesen und als Error geworfen. */
async function api(path, options = {}) {
  const response = await fetch(path.startsWith('/') && !path.startsWith(API) ? API + path : path, {
    ...options,
    headers: { Authorization: `Bearer ${memoryToken}`, Accept: 'application/json, application/problem+json' },
    credentials: 'omit',
    cache: 'no-store',
  });
  if (!response.ok) {
    let detail = `HTTP ${response.status}`;
    try {
      const problem = await response.json();
      detail = [problem.title, problem.detail].filter(Boolean).join(': ') || detail;
    } catch {
      // Kein JSON: Statuszeile genügt.
    }
    const error = new Error(detail);
    error.status = response.status;
    throw error;
  }
  return response;
}

const json = async (path, options) => (await api(path, options)).json();

function show(message) {
  const box = $('message');
  box.textContent = message || '';
  box.hidden = !message;
}

function cell(row, content) {
  const td = row.insertCell();
  if (content instanceof Node) {
    td.append(content);
  } else if (content !== undefined && content !== null) {
    td.textContent = String(content);
  }
  return td;
}

function button(label, onClick) {
  const element = document.createElement('button');
  element.type = 'button';
  element.textContent = label;
  element.addEventListener('click', onClick);
  return element;
}

function list(items) {
  const ul = document.createElement('ul');
  ul.className = 'details';
  for (const item of items) {
    const li = document.createElement('li');
    li.textContent = item;
    ul.append(li);
  }
  return ul;
}

async function loadSources() {
  const sources = await json('/sources');
  const select = $('source');
  select.replaceChildren(...sources.map((source) => new Option(`${source.title || source.id} (${source.id})`, source.id)));
  $('sources-section').hidden = false;
  $('jobs-section').hidden = false;
  if (sources.length === 0) {
    show('Für dieses Token ist keine Quelle freigegeben.');
    return;
  }
  await loadSpaces();
}

async function loadSpaces() {
  const sourceId = $('source').value;
  const body = $('spaces').tBodies[0];
  body.replaceChildren();
  const result = await json(`/sources/${encodeURIComponent(sourceId)}/spaces`);
  for (const space of result.spaces) {
    const row = body.insertRow();
    cell(row, space.key);
    cell(row, space.name);
    const actions = document.createElement('span');
    actions.append(
      button('Abgleichen', () => start('sync', sourceId, space.key)),
      button('Exportieren', () => start('export', sourceId, space.key)),
    );
    cell(row, actions);
  }
}

async function start(kind, sourceId, spaceKey) {
  show('');
  try {
    const path = `/sources/${encodeURIComponent(sourceId)}/spaces/${encodeURIComponent(spaceKey)}/${kind}s`;
    const response = await api(path, { method: 'POST' });
    const status = await response.json();
    const location = response.headers.get('Location') || `${API}/${kind}s/${status.id}`;
    jobs.set(status.id, { kind, location, status, manifest: null });
    render();
    poll(status.id);
  } catch (error) {
    show(`${kind === 'sync' ? 'Abgleich' : 'Export'} für ${spaceKey} nicht gestartet: ${error.message}`);
  }
}

async function poll(id) {
  const job = jobs.get(id);
  if (!job) {
    return;
  }
  try {
    job.status = await json(job.location);
    if (job.kind === 'export' && job.status.state === 'completed' && job.status.artifact && !job.manifest) {
      job.manifest = await json(`${API}/exports/${id}/artifact/manifest.json`);
    }
  } catch (error) {
    job.status = { ...job.status, state: 'failed', error: { message: error.message } };
  }
  render();
  if (!FINISHED.has(job.status.state)) {
    setTimeout(() => poll(id), POLL_MILLIS);
  }
}

async function cancel(id) {
  const job = jobs.get(id);
  try {
    await api(`${API}/${job.kind}s/${id}/cancel`, { method: 'POST' });
  } catch (error) {
    show(`Abbruch nicht möglich: ${error.message}`);
  }
}

async function download(id, file) {
  try {
    const blob = await (await api(`${API}/exports/${id}/artifact/${file.name}`)).blob();
    const link = document.createElement('a');
    link.href = URL.createObjectURL(blob);
    link.download = file.name.split('/').pop();
    link.click();
    setTimeout(() => URL.revokeObjectURL(link.href), 10000);
  } catch (error) {
    show(`Download von ${file.name} fehlgeschlagen: ${error.message}`);
  }
}

/** Ergebnis eines Auftrags: Vollständigkeit, Zähler, Warnungen, Fehler und Dateien. */
function outcome(id, job) {
  const status = job.status;
  const box = document.createElement('div');
  const line = document.createElement('span');
  const details = [];
  if (status.error) {
    line.className = 'failed';
    line.textContent = [status.error.code, status.error.message].filter(Boolean).join(': ') || 'Fehler';
  }
  if (job.kind === 'sync' && status.summary) {
    const s = status.summary;
    if (!status.error) {
      line.className = s.complete ? 'complete' : 'incomplete';
      line.textContent = s.complete ? `Generation ${s.generation} bestätigt` : 'unvollständig';
    }
    details.push(`${s.added} neu, ${s.contentChanged} geändert, ${s.unchanged} unverändert, ${s.removed} entfernt`);
    if (s.failed) {
      details.push(`${s.failed} Seiten nicht geladen`);
    }
  }
  if (job.kind === 'export' && status.artifact) {
    const complete = status.artifact.complete;
    line.className = complete ? 'complete' : 'incomplete';
    line.textContent = complete ? 'vollständig' : 'unvollständig (partieller Export)';
    for (const warning of job.manifest?.warnings ?? []) {
      details.push(`Warnung: ${typeof warning === 'string' ? warning : JSON.stringify(warning)}`);
    }
    for (const failure of job.manifest?.failures ?? []) {
      details.push(`Seite ${failure.pageId ?? '?'}: ${failure.reason ?? failure.code ?? JSON.stringify(failure)}`);
    }
  }
  box.append(line);
  if (details.length) {
    box.append(list(details));
  }
  if (job.kind === 'export' && status.artifact) {
    const files = document.createElement('div');
    for (const file of status.artifact.files) {
      files.append(button(file.name, () => download(id, file)));
    }
    box.append(files);
  }
  return box;
}

function render() {
  const body = $('jobs').tBodies[0];
  body.replaceChildren();
  for (const [id, job] of [...jobs].reverse()) {
    const status = job.status;
    const row = body.insertRow();
    cell(row, job.kind === 'sync' ? 'Abgleich' : 'Export');
    cell(row, status.spaceKey);
    cell(row, status.cancelRequested && !FINISHED.has(status.state) ? `${status.state} (Abbruch angefordert)` : status.state);
    const p = status.progress || {};
    cell(row, `${p.processed ?? 0}/${p.discovered ?? 0} Seiten, ${p.written ?? 0} geschrieben, ${p.failed ?? 0} fehlerhaft`);
    cell(row, outcome(id, job));
    cell(row, FINISHED.has(status.state) ? '' : button('Abbrechen', () => cancel(id)));
  }
}

async function signIn() {
  show('');
  try {
    await loadSources();
    $('login').hidden = true;
    $('logout').hidden = false;
  } catch (error) {
    setToken(null);
    show(error.status === 401 ? 'Token ungültig.' : `Laden fehlgeschlagen: ${error.message}`);
  }
}

$('login').addEventListener('submit', (event) => {
  event.preventDefault();
  setToken($('token').value.trim());
  $('token').value = '';
  signIn();
});
$('logout').addEventListener('click', () => {
  setToken(null);
  location.reload();
});
$('source').addEventListener('change', () => loadSpaces().catch((error) => show(error.message)));

if (memoryToken) {
  signIn();
}
